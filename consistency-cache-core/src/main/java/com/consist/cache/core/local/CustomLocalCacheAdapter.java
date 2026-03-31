package com.consist.cache.core.local;

import com.consist.cache.core.model.LocalCacheProperties;
import com.consist.cache.core.model.CacheValue;
import com.consist.cache.core.util.ClassUtil;
import lombok.extern.slf4j.Slf4j;
import java.util.function.Function;

@Slf4j
public class CustomLocalCacheAdapter<K,V extends CacheValue> implements LocalCache<K,V> {

    private final LocalCache<K,V> localCache;
    private final long maxSize;

    public CustomLocalCacheAdapter(LocalCacheProperties properties) {
        this.localCache = instantiate(properties);
        this.maxSize = properties.getMaximumSize();
    }

    public LocalCache instantiate(LocalCacheProperties properties) {
        LocalCache localCache = null;
        Class clz = null;
        try {
            clz =  Class.forName(properties.getCustomCacheClz());
            localCache = (LocalCache)ClassUtil.newInstance(clz,
                    new Class[]{LocalCacheProperties.class},
                    new Object[]{properties}
            );
        } catch (Exception e) {
            log.error("instantiate", e);
            return null;
        }
        return localCache;
    }

    @Override
    public V get(K key) {
        return this.localCache.get(key);
    }

    @Override
    public V get(K key, Function<K, V> loader) {
        try {
            return this.localCache.get(key, loader);
        } catch (Exception e) {
            throw new RuntimeException("this.localCache load failed", e);
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
        return this.localCache.getSize();
    }

    @Override
    public long getMaxSize() {
        return this.maxSize;
    }
}
