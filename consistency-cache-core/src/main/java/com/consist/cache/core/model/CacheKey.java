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
    private CacheLevel cacheLevel = CacheLevel.ADAPTIVE_CACHE;

    private ConsistencyLevel consistencyLevel = ConsistencyLevel.HIGH;
}
