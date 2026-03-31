package com.consist.cache.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.sql.Timestamp;

/**
 * Cache invalidation message entity for transactional outbox pattern.
 * 
 * Stored in hcc_cache_message table to guarantee eventual consistency
 * even when application nodes crash.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
     * Cache key to invalidate.
     * Format: "cacheName:key" (e.g., "products:123")
     */
    private String cacheKey;

    private String cacheLevel;

    private String consistencyLevel;

    /**
     * Operation type: DELETE, UPDATE
     */
    private String operationType;
    
    /**
     * Record status:
     * 0 = PENDING (not yet processed)
     * 1 = COMPLETED (successfully invalidated)
     * 2 = FAILED (multiple failures, requires manual intervention)
     */
    private Integer status;
    
    /**
     * Number of retry attempts.
     */
    private Integer retryCount;
    
    /**
     * Last error message (if failed).
     */
    private String errorMessage;

    /**
     * Node ID that created this record.
     * Format: hostname-pid
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
     * Mark record as completed.
     */
    public void markCompleted() {
        this.status = 1;
        this.updateTime = new Timestamp(System.currentTimeMillis());
    }
    
    /**
     * Mark record as failed with error message.
     */
    public void markFailed(String error) {
        this.retryCount = (this.retryCount != null ? this.retryCount : 0) + 1;
        this.errorMessage = error;
        this.updateTime = new Timestamp(System.currentTimeMillis());
        
        // Mark as permanently failed after too many retries
        if (this.retryCount >= 5) {
            this.status = 2;
        }
    }

    public enum OperationType {
        DELETE,
        UPDATE
    }

    public enum RecordStatus {
        PENDING (0),
        COMPLETED (1),
        FAILED (2);

        private int status;

        RecordStatus(int status) {
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }
}
