package com.consist.cache.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cache key
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheKey<K> {

    private K key;

    /**
     * 过期时间 毫秒
     */
    private long expireTimeMs;

    /**
     * 缓存级别
     */
    @Builder.Default
    private CacheLevel cacheLevel = CacheLevel.ADAPTIVE_CACHE;

    @Builder.Default
    private ConsistencyLevel consistencyLevel = ConsistencyLevel.HIGH;

    @Builder.Default
    private boolean bloomFilterEnabled = false;

    @Builder.Default
    private boolean cacheNullValues = true;

    @Builder.Default
    private boolean broadcastEnabled = true;

    private String bloomFilterName;
}
