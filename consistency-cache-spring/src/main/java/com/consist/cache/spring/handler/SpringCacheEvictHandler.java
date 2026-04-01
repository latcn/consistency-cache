package com.consist.cache.spring.handler;

import cn.hutool.core.util.EnumUtil;
import com.consist.cache.core.exception.CacheException;
import com.consist.cache.core.executor.CacheExecutor;
import com.consist.cache.core.executor.CacheEvictHandler;
import com.consist.cache.core.function.CallableWithThrowable;
import com.consist.cache.core.model.CacheKey;
import com.consist.cache.core.model.CacheLevel;
import com.consist.cache.core.model.ConsistencyLevel;
import com.consist.cache.core.model.InvalidationRecord;
import com.consist.cache.core.repository.InvalidationRecordDAO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.*;

@Slf4j
public class SpringCacheEvictHandler implements CacheEvictHandler {

    private final boolean enableTransaction;
    private final CacheExecutor cacheExecutor;
    private DataSource dataSource;
    private PlatformTransactionManager platformTransactionManager;
    private TransactionTemplate transactionTemplate;
    private InvalidationRecordDAO invalidationRecordDAO;
    private final LinkedBlockingQueue<InvalidationRecord> invalidationRecords = new LinkedBlockingQueue<>(1000);
    private final ScheduledExecutorService scheduledExecutorService;


    public SpringCacheEvictHandler(CacheExecutor cacheExecutor, boolean enableTransaction) {
        this.cacheExecutor = cacheExecutor;
        this.enableTransaction = enableTransaction;
        this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Invalidate-Cleaner-Scheduled-Thread");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduledExecutorService.scheduleWithFixedDelay(this::cleanCache, 5, 5, TimeUnit.SECONDS);
    }

    public SpringCacheEvictHandler(CacheExecutor cacheExecutor,
                                   DataSource dataSource,
                                   PlatformTransactionManager platformTransactionManager) {
        this(cacheExecutor, true);
        this.dataSource = dataSource;
        this.platformTransactionManager = platformTransactionManager;
        this.transactionTemplate = new TransactionTemplate(this.platformTransactionManager);
        this.invalidationRecordDAO = InvalidationRecordDAO.getInstance();
    }

    @Override
    public Object startInvalidate(InvalidationRecord invalidationRecord, CallableWithThrowable<Object> targetCallback) {
        String uid = invalidationRecord.getUid();
        String cacheKey = invalidationRecord.getCacheKey();
        if (this.enableTransaction) {
            return this.transactionTemplate.execute(status -> {
                try {
                    Connection conn = DataSourceUtils.getConnection(dataSource);
                    this.invalidationRecordDAO.insert(conn, invalidationRecord);
                    log.info("startInvalidate record uid:{}, cacheKey:{}", uid, cacheKey);
                    return targetCallback.apply();
                } catch (Throwable t) {
                    status.setRollbackOnly();
                    log.error("startInvalidate uid:{}, cacheKey:{}", uid, cacheKey, t);
                    throw new CacheException(t.getMessage());
                }
            });
        } else {
            try {
                return targetCallback.apply();
            } catch (Throwable e) {
                log.error("startInvalidate uid:{}, cacheKey:{}", uid, cacheKey, e);
                throw new CacheException(e.getMessage());
            }
        }
    }

    @Override
    public void addToSuccess(InvalidationRecord invalidationRecord) {
        if (invalidationRecord!=null) {
            if (this.invalidationRecords.size() < 1000) {
                this.invalidationRecords.add(invalidationRecord);
            } else {
                doClean(invalidationRecord);
            }
        }
    }

    /**
     * cleanCache
     */
    private void cleanCache() {
        // 1. 删除缓存
        while(true) {
            try {
                InvalidationRecord invalidationRecord = this.invalidationRecords.take();
                doClean(invalidationRecord);
            } catch (Exception e) {
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
                    .cacheLevel(EnumUtil.fromString(CacheLevel.class, invalidationRecord.getCacheLevel(), CacheLevel.ADAPTIVE_CACHE))
                    .consistencyLevel(EnumUtil.fromString(ConsistencyLevel.class, invalidationRecord.getConsistencyLevel(), ConsistencyLevel.HIGH))
                    .build();
            this.cacheExecutor.evict(cacheKey);
            if (this.enableTransaction) {
                // 标记成功
                markCompleted(invalidationRecord.getUid(), invalidationRecord.getCacheKey());
            }
        } catch (Exception e) {
            if (this.enableTransaction) {
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
            } catch (Throwable t) {
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
            } catch (Throwable t) {
                status.setRollbackOnly();
                log.error("markCompleted uid:{}, cacheKey:{}", uid, cacheKey, t);
            }
            return false;
        });
    }
}
