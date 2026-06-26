package io.github.latcn.cache.spring.handler;

import cn.hutool.core.util.EnumUtil;
import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import io.github.latcn.cache.core.executor.CacheEvictHandler;
import io.github.latcn.cache.core.executor.CacheExecutor;
import io.github.latcn.cache.core.function.CallableWithThrowable;
import io.github.latcn.cache.core.model.CacheKey;
import io.github.latcn.cache.core.model.CacheLevel;
import io.github.latcn.cache.core.model.ConsistencyLevel;
import io.github.latcn.cache.core.model.InvalidationRecord;
import io.github.latcn.cache.core.repository.InvalidationRecordDAO;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
public class SpringCacheEvictHandler implements CacheEvictHandler {

	private static final int INVALIDATION_RECORD_QUEUE_CAPACITY = 1000;

	private static final int CLEAN_CACHE_INITIAL_DELAY_SECONDS = 5;

	private static final int CLEAN_CACHE_PERIOD_SECONDS = 5;

	private final CacheExecutor cacheExecutor;

	private DataSource dataSource;

	private PlatformTransactionManager platformTransactionManager;

	private TransactionTemplate transactionTemplate;

	private InvalidationRecordDAO invalidationRecordDAO;

	private final ScheduledExecutorService scheduledExecutorService;

	private final LinkedBlockingQueue<InvalidationRecord> invalidationRecords = new LinkedBlockingQueue<>(
			INVALIDATION_RECORD_QUEUE_CAPACITY);

	public SpringCacheEvictHandler(CacheExecutor cacheExecutor) {
		this.cacheExecutor = cacheExecutor;
		this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread thread = new Thread(r, "Invalidate-Cleaner-Scheduled-Thread");
			thread.setDaemon(true);
			return thread;
		});
		this.scheduledExecutorService.scheduleWithFixedDelay(this::cleanCache, CLEAN_CACHE_INITIAL_DELAY_SECONDS,
				CLEAN_CACHE_PERIOD_SECONDS, TimeUnit.SECONDS);
	}

	public SpringCacheEvictHandler(CacheExecutor cacheExecutor, DataSource dataSource,
			PlatformTransactionManager platformTransactionManager) {
		this(cacheExecutor);
		this.dataSource = dataSource;
		this.platformTransactionManager = platformTransactionManager;
		this.transactionTemplate = new TransactionTemplate(this.platformTransactionManager);
		this.invalidationRecordDAO = InvalidationRecordDAO.getInstance();
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
		if (invalidationRecord != null) {
			if (this.invalidationRecords.size() < INVALIDATION_RECORD_QUEUE_CAPACITY) {
				this.invalidationRecords.add(invalidationRecord);
			}
			else {
				doClean(invalidationRecord);
			}
		}
	}

	/**
	 * cleanCache
	 */
	private void cleanCache() {
		List<InvalidationRecord> records = new ArrayList<>();
		this.invalidationRecords.drainTo(records);
		for (InvalidationRecord record : records) {
			try {
				doClean(record);
			}
			catch (Exception e) {
				log.error("cleanCache", e);
			}
		}
	}

	/**
	 * doClean
	 * @param invalidationRecord
	 */
	private void doClean(InvalidationRecord invalidationRecord) {
		try {
			CacheKey cacheKey = CacheKey.builder()
				.key(invalidationRecord.getCacheKey())
				.cacheLevel(EnumUtil.fromString(CacheLevel.class, invalidationRecord.getCacheLevel(),
						CacheLevel.ADAPTIVE_CACHE))
				.consistencyLevel(EnumUtil.fromString(ConsistencyLevel.class, invalidationRecord.getConsistencyLevel(),
						ConsistencyLevel.HIGH))
				.build();
			this.cacheExecutor.evict(cacheKey);
			if (invalidationRecord.isTransactionEnabled()) {
				// 标记成功
				markCompleted(invalidationRecord.getUid(), invalidationRecord.getCacheKey());
			}
		}
		catch (Exception e) {
			if (invalidationRecord.isTransactionEnabled()) {
				// 标记失败
				markFailed(invalidationRecord.getUid(), invalidationRecord.getCacheKey(), e.getMessage());
			}
			log.error("cleanCache", e);
		}
	}

	private boolean markFailed(String uid, String cacheKey, String errorMessage) {
		return this.transactionTemplate.execute(status -> {
			try {
				Connection conn = DataSourceUtils.getConnection(dataSource);
				boolean result = this.invalidationRecordDAO.markFailed(conn, uid, cacheKey, errorMessage);
				log.info("markFailed uid:{}, cacheKey:{}", uid, cacheKey);
				return result;
			}
			catch (Throwable t) {
				status.setRollbackOnly();
				log.error("markFailed uid:{}, cacheKey:{}", uid, cacheKey, t);
			}
			return false;
		});
	}

	private boolean markCompleted(String uid, String cacheKey) {
		return this.transactionTemplate.execute(status -> {
			try {
				Connection conn = DataSourceUtils.getConnection(dataSource);
				boolean result = this.invalidationRecordDAO.markCompleted(conn, uid, cacheKey);
				log.info("markCompleted uid:{}, cacheKey:{}", uid, cacheKey);
				return result;
			}
			catch (Throwable t) {
				status.setRollbackOnly();
				log.error("markCompleted uid:{}, cacheKey:{}", uid, cacheKey, t);
			}
			return false;
		});
	}

}
