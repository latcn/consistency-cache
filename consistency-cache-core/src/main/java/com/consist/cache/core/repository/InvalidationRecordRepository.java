package com.consist.cache.core.repository;


import com.consist.cache.core.model.InvalidationRecord;

import java.sql.Connection;
import java.util.List;

/**
 * Repository interface for cache invalidation message persistence.
 */
public interface InvalidationRecordRepository {
    
    /**
     * Save invalidation record within transaction.
     * Called during Spring transaction, will commit/rollback with business transaction.
     * @param record invalidation record to save
     * @return saved record with generated ID
     */
    boolean insert(Connection conn, InvalidationRecord record);
    
    /**
     * Find all pending records older than threshold.
     * Used by compensation task to find records that need retry.
     * @param thresholdSeconds age threshold in seconds
     * @param limit maximum number of records to return
     * @return list of pending records
     */
    List<InvalidationRecord> findPendingRecordsOlderThan(Connection conn, int thresholdSeconds, int limit);

    /**
     * findByUidAndCacheKey
     * @param conn
     * @param uid
     * @param cacheKey
     * @return
     */
    List<InvalidationRecord> findByUidAndCacheKey(Connection conn, String uid, String cacheKey);

    /**
     * Mark record as completed.
     * @param uid
     * @param cacheKey
     * @return true if updated successfully
     */
    boolean markCompleted(Connection conn, String uid, String cacheKey);

    /**
     * markFailed
     * @param conn
     * @param uid
     * @param cacheKey
     * @param errorMessage
     * @return
     */
    boolean markFailed(Connection conn, String uid, String cacheKey, String errorMessage);
    
    /**
     * Delete old completed records (cleanup).
     * @param olderThanSeconds delete records older than this many Seconds
     * @return number of deleted records
     */
    int deleteOldCompletedRecords(Connection conn, long olderThanSeconds);
    
    /**
     * Get count of pending records (for monitoring).
     * @return pending record count
     */
    long getPendingCount(Connection conn);
    
    /**
     * Get count of failed records (for alerting).
     * @return failed record count
     */
    long getFailedCount(Connection conn);
}
