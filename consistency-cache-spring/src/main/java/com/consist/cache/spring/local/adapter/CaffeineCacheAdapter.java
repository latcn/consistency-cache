package com.consist.cache.spring.local.adapter;

import com.consist.cache.core.local.LocalCache;
import com.consist.cache.core.model.CacheValue;
import com.consist.cache.core.model.HccProperties;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.checkerframework.checker.index.qual.NonNegative;

import java.util.function.Function;

public class CaffeineCacheAdapter<K,V extends CacheValue> implements LocalCache<K,V> {

    private final com.github.benmanes.caffeine.cache.Cache<K, V> localCache;
    private final long maxSize;

    public CaffeineCacheAdapter(HccProperties.LocalCacheProperties properties) {
        this.localCache = Caffeine.newBuilder()
                .initialCapacity(properties.getInitialCapacity())
                .maximumSize(properties.getMaximumSize())
                .expireAfter(new CommonExpiry())
                .build();
        this.maxSize = properties.getMaximumSize();
    }

    @Override
    public V get(K key) {
        return this.localCache.getIfPresent(key);
    }

    @Override
    public V get(K key, Function<K, V> loader) {
        // Caffeine的get(key, mappingFunction)也是原子性的
        return this.localCache.get(key, loader::apply);
    }

    @Override
    public void put(K key, V value) {
        this.localCache.put(key, value);
    }

    @Override
    public void invalidate(K key) {
        this.localCache.invalidate(key);
    }

    @Override
    public void invalidateAll() {
        this.localCache.invalidateAll();
    }

    @Override
    public void cleanUp() {
        this.localCache.cleanUp();
    }

    @Override
    public long getSize() {
        return this.localCache.estimatedSize();
    }

    @Override
    public long getMaxSize() {
        return this.maxSize;
    }

    static class CommonExpiry<K, V> implements Expiry<K, V> {
        @Override
        public long expireAfterCreate(K key, V value, long currentTime) {
            CacheValue cacheValue = (CacheValue) value;
            return cacheValue.getExpireTime();
        }

        @Override
        public long expireAfterUpdate(K key, V value, long currentTime, @NonNegative long currentDuration) {
            return currentDuration;
        }

        @Override
        public long expireAfterRead(K key, V value, long currentTime, @NonNegative long currentDuration) {
            return currentDuration;
        }
    }


    public static void main(String[] args) {
        HccProperties.LocalCacheProperties localCacheProperties = new HccProperties.LocalCacheProperties();
        CaffeineCacheAdapter<String, CacheValue<String>> caffeineCacheAdapter = new CaffeineCacheAdapter(localCacheProperties);
        //caffeineCacheAdapter.put("123", "321");
    }
}
