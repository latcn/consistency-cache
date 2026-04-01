package com.consist.cache.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Duration;

/**
 * Cache value
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheValue<V> {

    public static final long MAX_EXPIRE_TIME = Long.MAX_VALUE>>1;

    private V value;
    
    /**
     * Absolute expiration timestamp (milliseconds since epoch)
     */
    @Builder.Default
    private long expireTime = MAX_EXPIRE_TIME;
    
    /**
     * Creation timestamp
     */
    private long createdAt;
    
    /**
     * Weight for LRU prioritization (used for hot key enhancement)
     */
    @Builder.Default
    private double weight = 1.0;

    /**
     * Check if entry has expired
     */
    public boolean isExpired() {
        return System.currentTimeMillis() > expireTime;
    }

    public boolean notExist() {
        return value==null;
    }
    
    /**
     * Calculate TTL in milliseconds
     */
    public long getTtl() {
        return expireTime - createdAt;
    }

    /**
     * 获取wrap内的具体数据
     * @param value
     * @return
     * @param <V>
     */
    public static <V> V extractValue(Object value) {
        if (value==null || !(value instanceof CacheValue)) {
            return (V) value;
        }
        CacheValue cacheValue = (CacheValue) value;
        if (cacheValue ==null || cacheValue.isExpired() || cacheValue.notExist()) {
            return null;
        }
        return (V) cacheValue.getValue();
    }

    @Override
    public String toString() {
        return "CacheValue{" +
                "value=" + value +
                ", expireTime=" + expireTime +
                ", createdAt=" + createdAt +
                ", weight=" + weight +
                '}';
    }
}
