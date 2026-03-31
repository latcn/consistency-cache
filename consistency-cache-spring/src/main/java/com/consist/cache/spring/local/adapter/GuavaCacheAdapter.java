package com.consist.cache.spring.local.adapter;

import com.consist.cache.core.model.LocalCacheProperties;
import com.consist.cache.core.local.LocalCache;
import com.consist.cache.core.model.CacheValue;
import com.google.common.cache.CacheBuilder;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class GuavaCacheAdapter<K,V extends CacheValue> implements LocalCache<K,V> {

    private final com.google.common.cache.Cache<K, V> localCache;
    private final long maxSize;

    public GuavaCacheAdapter(LocalCacheProperties properties) {
        this.localCache = CacheBuilder.newBuilder()
                .initialCapacity(properties.getInitialCapacity())
                .maximumSize(properties.getMaximumSize())
                .expireAfterWrite(properties.getExpireAfterWrite(), TimeUnit.SECONDS)
                .expireAfterAccess(properties.getExpireAfterAccess(), TimeUnit.SECONDS)
                .build();
        this.maxSize = properties.getMaximumSize();
    }

    @Override
    public V get(K key) {
        return this.localCache.getIfPresent(key);
    }

    @Override
    public V get(K key, Function<K, V> loader) {
        try {
            // Guava的get(key, callable)自带原子性加载逻辑
            return this.localCache.get(key, () -> loader.apply(key));
        } catch (Exception e) {
            throw new RuntimeException("Guava this.localCache load failed", e);
        }
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
        return this.localCache.size();
    }

    @Override
    public long getMaxSize() {
        return this.maxSize;
    }
}
