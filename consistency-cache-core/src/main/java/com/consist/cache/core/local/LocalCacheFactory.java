package com.consist.cache.core.local;

import com.consist.cache.core.model.LocalCacheProperties;
import com.consist.cache.core.exception.CacheError;
import com.consist.cache.core.exception.CacheException;
import com.consist.cache.core.util.ClassUtil;

import java.util.concurrent.ConcurrentHashMap;

public class LocalCacheFactory {

    private static final ConcurrentHashMap<String, Class<? extends LocalCache>> cacheClzMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LocalCache> cacheInstanceMap = new ConcurrentHashMap<>();
    static  {
        cacheClzMap.put("CUSTOM", CustomLocalCacheAdapter.class);
    }

    public static void registerCacheType(String cacheType, Class<? extends LocalCache> cacheClz) {
        cacheClzMap.putIfAbsent(cacheType, cacheClz);
    }

    public static void removeByCacheType(String cacheType) {
        cacheClzMap.remove(cacheType);
    }

    public static <K,V> LocalCache<K, V> getOrCreateLocalCache(LocalCacheProperties properties) {
        LocalCache localCache = cacheInstanceMap.get(properties.getCacheType());
        if (localCache!=null) {
            return localCache;
        }
        cacheInstanceMap.putIfAbsent(properties.getCacheType(), createLocalCache(properties));
        return cacheInstanceMap.get(properties.getCacheType());
    }

    private static <K,V> LocalCache<K, V> createLocalCache(LocalCacheProperties properties) {
        Class<? extends LocalCache> cacheClz = cacheClzMap.get(properties.getCacheType());
        if (cacheClz==null) {
            throw CacheException.newInstance(CacheError.NOT_EXISTS_LOCAL_CACHE_CLASS);
        }
        LocalCache localCache = ClassUtil.newInstance(cacheClz,
                new Class[]{LocalCacheProperties.class},
                new Object[]{properties}
                );
        return localCache;
    }

}
