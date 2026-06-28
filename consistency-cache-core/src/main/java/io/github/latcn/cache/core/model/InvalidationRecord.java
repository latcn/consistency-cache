package io.github.latcn.cache.core.model;

import java.io.Serializable;
import java.sql.Timestamp;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Cache invalidation message entity for transactional outbox pattern.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class InvalidationRecord implements Serializable {

	private static final long serialVersionUID = 1L;

	/**
	 * Unique record identifier.
	 */
	private Long id;

	/**
	 * unique key
	 */
	private String uid;

	/**
	 * Cache key to invalidate. Format: "cacheName:key" (e.g., "products:123")
	 */
	private String cacheKey;

	private String cacheLevel;

	private String consistencyLevel;

	private boolean transactionEnabled;

	private EvictPolicy evictPolicy = EvictPolicy.DELAYED;

	/**
	 * Operation type: DELETE, UPDATE
	 */
	private String operationType;

	/**
	 * Record status: 0 = PENDING (not yet processed) 1 = COMPLETED (successfully
	 * invalidated) 2 = FAILED (multiple failures, requires manual intervention)
	 */
	private Integer status = 0;

	/**
	 * Number of retry attempts.
	 */
	private Integer retryCount = 0;

	/**
	 * Last error message (if failed).
	 */
	private String errorMessage;

	/**
	 * Node ID that created this record. Format: hostname-pid
	 */
	private String nodeId;

	/**
	 * Creation timestamp (when transaction committed).
	 */
	private Timestamp createTime;

	/**
	 * Last update timestamp.
	 */
	private Timestamp updateTime;

	/**
	 * Next execution time for exponential backoff scheduling.
	 */
	private Timestamp nextExecutionTime;

	private static final long MAX_DELAY_MS = 24 * 60 * 60 * 1000L;

	/**
	 * Check if this record is old enough to be processed by compensation task.
	 * @param thresholdSeconds age threshold in seconds
	 * @return true if older than threshold
	 */
	public boolean isOldEnoughForCompensation(int thresholdSeconds) {
		if (createTime == null) {
			return false;
		}

		long now = System.currentTimeMillis();
		long createTimestamp = createTime.getTime();

		return (now - createTimestamp) > (thresholdSeconds * 1000L);
	}

	/**
	 * Check if record is in pending status.
	 * @return true if status is 0 (PENDING)
	 */
	public boolean isPending() {
		return this.status != null && this.status == 0;
	}

	/**
	 * Check if record is ready for execution based on nextExecutionTime.
	 * @return true if current time >= nextExecutionTime or nextExecutionTime is null
	 */
	public boolean isReadyForExecution() {
		if (nextExecutionTime == null) {
			return true;
		}
		return System.currentTimeMillis() >= nextExecutionTime.getTime();
	}

	/**
	 * Mark record as completed.
	 */
	public void markCompleted() {
		this.status = 1;
		this.updateTime = new Timestamp(System.currentTimeMillis());
	}

	/**
	 * Calculate and set next execution time using exponential backoff. Initial delay is
	 * baseDelayMs, then doubles on each failure, max 24 hours.
	 * @param baseDelayMs base delay in milliseconds, default 1000
	 */
	public void calculateNextExecutionTime(long baseDelayMs) {
		long now = System.currentTimeMillis();
		if (this.nextExecutionTime == null) {
			this.nextExecutionTime = new Timestamp(now + baseDelayMs);
		}
		else {
			long newDelay = Math.min(baseDelayMs << this.retryCount, MAX_DELAY_MS);
			if (newDelay <= 0) {
				log.error("markFailed uid:{}, cacheKey:{}", uid, cacheKey);
			}
			this.nextExecutionTime = new Timestamp(now + newDelay);
		}
	}

	/**
	 * Mark record as failed with error message and update next execution time.
	 * @param error error message
	 * @param baseDelayMs base delay for calculating next execution time
	 */
	public void markFailed(String error, long baseDelayMs) {
		this.retryCount = (this.retryCount != null ? this.retryCount : 0) + 1;
		this.errorMessage = error;
		this.updateTime = new Timestamp(System.currentTimeMillis());
		calculateNextExecutionTime(baseDelayMs);

		if (this.retryCount >= 5) {
			this.status = 2;
		}
	}

	public enum OperationType {

		DELETE, UPDATE

	}

	public enum RecordStatus {

		PENDING(0), COMPLETED(1), FAILED(2);

		private final int status;

		RecordStatus(int status) {
			this.status = status;
		}

		public int getStatus() {
			return status;
		}

	}

	public enum EvictPolicy {

		DELAYED, IMMEDIATE

	}

}
