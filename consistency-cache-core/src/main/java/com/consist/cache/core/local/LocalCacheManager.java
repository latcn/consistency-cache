package com.consist.cache.core.local;

import com.consist.cache.core.manager.CacheManager;
import com.consist.cache.core.exception.CacheError;
import com.consist.cache.core.exception.CacheException;
import com.consist.cache.core.model.CacheKey;
import com.consist.cache.core.model.CacheValue;
import com.consist.cache.core.model.ConsistencyLevel;
import com.consist.cache.core.model.HccProperties;
import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class LocalCacheManager implements CacheManager<CacheKey, CacheValue> {

    private final HccProperties.LocalCacheProperties properties;
    private final ConcurrentHashMap<ConsistencyLevel, LocalCache<Object, CacheValue>> cacheLevelMap = new ConcurrentHashMap<>();
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);
    private final AtomicLong evictionCount = new AtomicLong(0);
    private static final Set<Class> supportKeyClz = Set.of(
            Integer.class,
            Long.class,
            String.class
    );

    public LocalCacheManager(HccProperties.LocalCacheProperties properties) {
        this.properties = properties;
    }

    public ConcurrentHashMap<ConsistencyLevel, LocalCache<Object, CacheValue>> getCacheLevelMap() {
        return this.cacheLevelMap;
    }

    private LocalCache<Object, CacheValue> getOrCreateCache(ConsistencyLevel consistencyLevel) {
        if (consistencyLevel == null) {
            consistencyLevel = ConsistencyLevel.HIGH;
        }
        LocalCache<Object, CacheValue> localCache = this.cacheLevelMap.get(consistencyLevel);
        if (localCache==null) {
            ConsistencyLevel finalConsistencyLevel = consistencyLevel;
            localCache = this.cacheLevelMap.computeIfAbsent(consistencyLevel, (k)->LocalCacheFactory.getOrCreateLocalCache(finalConsistencyLevel, this.properties));
        }
        return localCache;
    }

    @Override
    public CacheValue get(CacheKey cacheKey) {
        checkCacheKey(cacheKey);
        CacheValue cacheValue = this.getOrCreateCache(cacheKey.getConsistencyLevel()).get(cacheKey.getKey());
        if (cacheValue == null) {
            this.missCount.incrementAndGet();
            return null;
        }
        if (cacheValue.isExpired()) {
            this.getOrCreateCache(cacheKey.getConsistencyLevel()).invalidate(cacheKey.getKey());
            this.missCount.incrementAndGet();
            this.evictionCount.incrementAndGet();
            return null;
        }
        this.hitCount.incrementAndGet();
        return cacheValue;
    }

    @Override
    public void put(CacheKey cacheKey, CacheValue cacheValue) {
        checkCacheKey(cacheKey);
        this.getOrCreateCache(cacheKey.getConsistencyLevel()).put(cacheKey.getKey(), cacheValue);
    }

    public CacheValue getByActualKey(Object key) {
        checkActualCacheKey(key);
        CacheValue cacheValue = null;
        for (Map.Entry<ConsistencyLevel,LocalCache<Object,CacheValue>> entry: this.cacheLevelMap.entrySet()) {
             cacheValue = entry.getValue().get(key);
             if (cacheValue!=null) {
                 return cacheValue;
             }
        }
        return null;
    }

    public void removeByActualKey(Object key) {
        checkActualCacheKey(key);
        for (Map.Entry<ConsistencyLevel,LocalCache<Object,CacheValue>> entry: this.cacheLevelMap.entrySet()) {
             entry.getValue().invalidate(key);
        }
    }

    @Override
    public void remove(CacheKey cacheKey) {
        if (containKey(cacheKey)) {
            this.getOrCreateCache(cacheKey.getConsistencyLevel()).invalidate(cacheKey.getKey());
        }
    }

    public void clear() {
        for (Map.Entry<ConsistencyLevel,LocalCache<Object,CacheValue>> entry: this.cacheLevelMap.entrySet()) {
            entry.getValue().invalidateAll();
        }
    }

    @Override
    public boolean containKey(CacheKey cacheKey) {
        checkCacheKey(cacheKey);
        CacheValue cacheValue = this.getOrCreateCache(cacheKey.getConsistencyLevel()).get(cacheKey.getKey());
        if (cacheValue !=null && !cacheValue.isExpired()) {
            return true;
        }
        return false;
    }

    public long getSize() {
        long cacheSize = 0;
        for (Map.Entry<ConsistencyLevel,LocalCache<Object,CacheValue>> entry: this.cacheLevelMap.entrySet()) {
            cacheSize += entry.getValue().getSize();
        }
        return cacheSize;
    }

    /**
     * manually cleanUp() forces immediate processing
     */
    public void runEviction() {
        for (Map.Entry<ConsistencyLevel,LocalCache<Object,CacheValue>> entry: this.cacheLevelMap.entrySet()) {
              entry.getValue().cleanUp();
        }
    }

    private void checkCacheKey(CacheKey cacheKey) {
        if (cacheKey==null) {
            throw CacheException.newInstance(CacheError.EMPTY_KEY);
        }
        checkActualCacheKey(cacheKey.getKey());
    }

    private void checkActualCacheKey(Object key) {
        if (key==null) {
            throw CacheException.newInstance(CacheError.EMPTY_KEY);
        }
        if (!supportKeyClz.contains(key.getClass())) {
            throw CacheException.newInstance(CacheError.ERROR_KEY_TYPE);
        }
    }

    public CacheStats getStats() {
        long totalRequests = this.hitCount.get() + this.missCount.get();
        double hitRate = totalRequests > 0 ? (double) this.hitCount.get() / totalRequests : 0.0;
        int cacheSize = 0;
        int maxSize = 0;
        for (Map.Entry<ConsistencyLevel,LocalCache<Object,CacheValue>> entry: this.cacheLevelMap.entrySet()) {
            cacheSize += entry.getValue().getSize();
            maxSize += entry.getValue().getMaxSize();
        }
        return CacheStats.builder()
                .hitCount(this.hitCount.get())
                .missCount(this.missCount.get())
                .hitRate(hitRate)
                .size(cacheSize)
                .maxSize(maxSize)
                .evictionCount(this.evictionCount.get())
                .build();
    }

    @Data
    @Builder
    public static class CacheStats {
        private long hitCount;
        private long missCount;
        private double hitRate;
        private long size;
        private long maxSize;
        private long evictionCount;

        public String getFormattedHitRate() {
            return String.format("%.2f", this.hitRate * 100)+"%";
        }
    }
}
