package io.github.latcn.cache.core.repository;

import io.github.latcn.cache.core.model.InvalidationRecord;
import java.sql.Connection;
import java.util.List;

/**
 * Repository interface for cache invalidation message persistence.
 */
public interface InvalidationRecordRepository {

	/**
	 * Save invalidation record within transaction. Called during Spring transaction, will
	 * commit/rollback with business transaction.
	 * @param record invalidation record to save
	 * @return saved record with generated ID
	 */
	boolean insert(Connection conn, InvalidationRecord record);

	/**
	 * Find pending records by next execution time for compensation task. Uses FOR UPDATE
	 * SKIP LOCKED to avoid distributed lock contention.
	 *
	 * Query conditions: - next_execution_time <= current time - status is PENDING(0) or
	 * FAILED(2) - retry_count less than max retry count
	 * @param conn database connection
	 * @param maxRetryCount maximum retry count, records exceeding this are not processed
	 * @param limit maximum number of records to return
	 * @return list of pending records
	 */
	List<InvalidationRecord> findPendingRecords(Connection conn, int maxRetryCount, int limit);

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
	 * @param nextExecutionTime next execution time for exponential backoff
	 * @return
	 */
	boolean markFailed(Connection conn, String uid, String cacheKey, String errorMessage,
			java.sql.Timestamp nextExecutionTime);

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
