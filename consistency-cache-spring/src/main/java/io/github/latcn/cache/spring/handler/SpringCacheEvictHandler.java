package io.github.latcn.cache.spring.handler;

import cn.hutool.core.util.EnumUtil;
import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import io.github.latcn.cache.core.executor.CacheEvictHandler;
import io.github.latcn.cache.core.executor.CacheExecutor;
import io.github.latcn.cache.core.function.CallableWithThrowable;
import io.github.latcn.cache.core.model.*;
import io.github.latcn.cache.core.repository.InvalidationRecordDAO;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
public class SpringCacheEvictHandler implements CacheEvictHandler {

	private static final int INITIAL_DELAY_SECONDS = 1;

	private final CacheExecutor cacheExecutor;

	private DataSource dataSource;

	private PlatformTransactionManager platformTransactionManager;

	private TransactionTemplate transactionTemplate;

	private InvalidationRecordDAO invalidationRecordDAO;

	private final ScheduledExecutorService cleanCacheScheduler;

	private final ScheduledExecutorService compensationScheduler;

	private final LinkedBlockingQueue<InvalidationRecord> invalidationRecords;

	private final long baseDelayMs;

	private final int compensationBatchSize;

	private final int maxRetryCount;

	private final int invalidationQueueCapacity;

	public SpringCacheEvictHandler(CacheExecutor cacheExecutor,
			HccProperties.CacheEvictProperties cacheEvictProperties) {
		this.cacheExecutor = cacheExecutor;
		this.baseDelayMs = cacheEvictProperties.getBaseDelayMs();
		this.compensationBatchSize = cacheEvictProperties.getCompensationBatchSize();
		this.maxRetryCount = cacheEvictProperties.getMaxRetryCount();
		this.invalidationQueueCapacity = cacheEvictProperties.getInvalidationQueueCapacity();
		this.invalidationRecords = new LinkedBlockingQueue(invalidationQueueCapacity);
		this.cleanCacheScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread thread = new Thread(r, "Invalidate-Cleaner-Scheduled-Thread");
			thread.setDaemon(true);
			return thread;
		});
		this.compensationScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread thread = new Thread(r, "Compensation-Task-Scheduled-Thread");
			thread.setDaemon(true);
			return thread;
		});
	}

	public SpringCacheEvictHandler(CacheExecutor cacheExecutor, HccProperties.CacheEvictProperties cacheEvictProperties,
			DataSource dataSource, PlatformTransactionManager platformTransactionManager) {
		this(cacheExecutor, cacheEvictProperties);
		this.dataSource = dataSource;
		this.platformTransactionManager = platformTransactionManager;
		this.transactionTemplate = new TransactionTemplate(this.platformTransactionManager);
		this.invalidationRecordDAO = InvalidationRecordDAO.getInstance();
		this.cleanCacheScheduler.scheduleAtFixedRate(this::cleanCache, INITIAL_DELAY_SECONDS,
				cacheEvictProperties.getCleanCachePeriodSeconds(), TimeUnit.SECONDS);
		this.compensationScheduler.scheduleAtFixedRate(this::compensatePendingRecords, INITIAL_DELAY_SECONDS,
				cacheEvictProperties.getCompensationPeriodSeconds(), TimeUnit.SECONDS);
	}

	@Override
	public Object startInvalidate(InvalidationRecord invalidationRecord, CallableWithThrowable<Object> targetCallback) {
		String uid = invalidationRecord.getUid();
		String cacheKey = invalidationRecord.getCacheKey();
		if (invalidationRecord.isTransactionEnabled()) {
			return this.transactionTemplate.execute(status -> {
				try {
					Connection conn = DataSourceUtils.getConnection(dataSource);
					this.invalidationRecordDAO.insert(conn, invalidationRecord);
					log.info("startInvalidate record uid:{}, cacheKey:{}", uid, cacheKey);
					return targetCallback.apply();
				}
				catch (Throwable t) {
					status.setRollbackOnly();
					log.error("startInvalidate uid:{}, cacheKey:{}", uid, cacheKey, t);
					throw CacheException.wrap(t, CacheError.CACHE_EVICT_FAILED);
				}
			});
		}
		else {
			try {
				return targetCallback.apply();
			}
			catch (Throwable e) {
				log.error("startInvalidate uid:{}, cacheKey:{}", uid, cacheKey, e);
				throw CacheException.wrap(e, CacheError.CACHE_EVICT_FAILED);
			}
		}
	}

	@Override
	public void addToSuccess(InvalidationRecord invalidationRecord) {
		if (invalidationRecord == null) {
			return;
		}

		if (invalidationRecord.getEvictPolicy() == InvalidationRecord.EvictPolicy.IMMEDIATE) {
			doClean(null, invalidationRecord);
		}
		else {
			if (this.invalidationRecords.size() < invalidationQueueCapacity) {
				this.invalidationRecords.add(invalidationRecord);
			}
			else {
				doClean(null, invalidationRecord);
			}
		}
	}

	private void cleanCache() {
		List<InvalidationRecord> records = new ArrayList<>();
		this.invalidationRecords.drainTo(records);
		for (InvalidationRecord record : records) {
			try {
				doClean(null, record);
			}
			catch (Exception e) {
				log.error("cleanCache", e);
			}
		}
	}

	private void compensatePendingRecords() {
		if (dataSource == null || invalidationRecordDAO == null) {
			return;
		}
		try {
			this.transactionTemplate.execute(status -> {
				Connection conn = DataSourceUtils.getConnection(dataSource);
				List<InvalidationRecord> pendingRecords = invalidationRecordDAO.findPendingRecords(conn, maxRetryCount,
						compensationBatchSize);
				if (pendingRecords != null && !pendingRecords.isEmpty()) {
					log.info("Compensation task found {} pending records (maxRetry={})", pendingRecords.size(),
							maxRetryCount);
					for (InvalidationRecord record : pendingRecords) {
						try {
							doClean(conn, record);
						}
						catch (Exception e) {
							log.error("Compensation task failed for record uid: {}, cacheKey: {}", record.getUid(),
									record.getCacheKey(), e);
						}
					}
				}
				return null;
			});

		}
		catch (Exception e) {
			log.error("Compensation task error", e);
		}
	}

	private void doClean(Connection conn, InvalidationRecord invalidationRecord) {
		try {
			CacheKey cacheKey = CacheKey.builder()
				.key(invalidationRecord.getCacheKey())
				.cacheLevel(EnumUtil.fromString(CacheLevel.class, invalidationRecord.getCacheLevel(),
						CacheLevel.ADAPTIVE_CACHE))
				.consistencyLevel(EnumUtil.fromString(ConsistencyLevel.class, invalidationRecord.getConsistencyLevel(),
						ConsistencyLevel.HIGH))
				.build();
			this.cacheExecutor.evict(cacheKey);
			markCompleted(conn, invalidationRecord);
		}
		catch (Exception e) {
			markFailed(conn, invalidationRecord);
			log.error("doClean", e);
		}
	}

	private boolean markFailed(Connection conn, InvalidationRecord invalidationRecord) {
		if (conn == null && invalidationRecord.isTransactionEnabled()) {
			invalidationRecord.calculateNextExecutionTime(baseDelayMs);
			return this.transactionTemplate.execute(status -> {
				try {
					Connection newConn = DataSourceUtils.getConnection(dataSource);
					boolean result = this.invalidationRecordDAO.markFailed(newConn, invalidationRecord.getUid(),
							invalidationRecord.getCacheKey(), invalidationRecord.getErrorMessage(),
							invalidationRecord.getNextExecutionTime());
					log.info("markFailed uid:{}, cacheKey:{}", invalidationRecord.getUid(),
							invalidationRecord.getCacheKey());
					return result;
				}
				catch (Throwable t) {
					status.setRollbackOnly();
					log.error("markFailed failed uid:{}, cacheKey:{}", invalidationRecord.getUid(),
							invalidationRecord.getCacheKey(), t);
				}
				return false;
			});
		}
		else if (conn != null) {
			invalidationRecord.calculateNextExecutionTime(baseDelayMs);
			boolean result = this.invalidationRecordDAO.markFailed(conn, invalidationRecord.getUid(),
					invalidationRecord.getCacheKey(), invalidationRecord.getErrorMessage(),
					invalidationRecord.getNextExecutionTime());
			log.info("markFailed conn uid:{}, cacheKey:{}", invalidationRecord.getUid(),
					invalidationRecord.getCacheKey());
			return result;
		}
		return false;
	}

	public boolean markCompleted(Connection conn, InvalidationRecord invalidationRecord) {

		if (conn == null && invalidationRecord.isTransactionEnabled()) {
			return this.transactionTemplate.execute(status -> {
				try {
					Connection newConn = DataSourceUtils.getConnection(dataSource);
					boolean result = this.invalidationRecordDAO.markCompleted(newConn, invalidationRecord.getUid(),
							invalidationRecord.getCacheKey());
					log.info("markCompleted uid:{}, cacheKey:{}", invalidationRecord.getUid(),
							invalidationRecord.getCacheKey());
					return result;
				}
				catch (Throwable t) {
					status.setRollbackOnly();
					log.error("markCompleted uid:{}, cacheKey:{}", invalidationRecord.getUid(),
							invalidationRecord.getCacheKey(), t);
				}
				return false;
			});
		}
		else if (conn != null) {
			boolean result = this.invalidationRecordDAO.markCompleted(conn, invalidationRecord.getUid(),
					invalidationRecord.getCacheKey());
			log.info("markCompleted conn uid:{}, cacheKey:{}", invalidationRecord.getUid(),
					invalidationRecord.getCacheKey());
			return result;
		}
		return false;
	}

	public int getQueueSize() {
		return this.invalidationRecords.size();
	}

	public List<InvalidationRecord> getPendingRecords(int maxRetryCount, int limit) {
		if (dataSource == null || invalidationRecordDAO == null) {
			return List.of();
		}
		try {
			return this.transactionTemplate.execute(status -> {
				Connection conn = DataSourceUtils.getConnection(dataSource);
				return invalidationRecordDAO.findPendingRecords(conn, maxRetryCount, limit);
			});
		}
		catch (Exception e) {
			log.error("getPendingRecords error", e);
			return List.of();
		}
	}

	public void processRecord(InvalidationRecord record) {
		if (record.isTransactionEnabled()) {
			doClean(null, record);
		}
	}

	public long getPendingRecordCount(DataSource ds) {
		if (ds == null) {
			return 0;
		}
		try (Connection conn = ds.getConnection()) {
			return invalidationRecordDAO.getPendingCount(conn);
		}
		catch (SQLException e) {
			log.error("getPendingRecordCount error", e);
			return 0;
		}
	}

}