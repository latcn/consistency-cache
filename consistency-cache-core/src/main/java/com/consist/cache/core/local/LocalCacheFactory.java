package com.consist.cache.core.local;

import com.consist.cache.core.model.ConsistencyLevel;
import com.consist.cache.core.exception.CacheError;
import com.consist.cache.core.exception.CacheException;
import com.consist.cache.core.model.HccProperties;
import com.consist.cache.core.model.LocalCacheType;
import com.consist.cache.core.util.ClassUtil;

import java.util.concurrent.ConcurrentHashMap;

public class LocalCacheFactory {

    private static final ConcurrentHashMap<String, Class<? extends LocalCache>> cacheClzMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LocalCache> cacheInstanceMap = new ConcurrentHashMap<>();
    static  {
        cacheClzMap.put(LocalCacheType.CUSTOM.name(), CustomLocalCacheAdapter.class);
    }

    public static void registerCacheType(String cacheType, Class<? extends LocalCache> cacheClz) {
        cacheClzMap.putIfAbsent(cacheType, cacheClz);
    }

    public static void removeByCacheType(String cacheType) {
        cacheClzMap.remove(cacheType);
    }

    public static <K,V> LocalCache<K, V> getOrCreateLocalCache(ConsistencyLevel consistencyLevel, HccProperties.LocalCacheProperties properties) {
        String cacheInstanceKey = getCacheInstanceKey(properties.getCacheType(), consistencyLevel);
        LocalCache localCache = cacheInstanceMap.get(cacheInstanceKey);
        if (localCache!=null) {
            return localCache;
        }
        return cacheInstanceMap.computeIfAbsent(cacheInstanceKey, (k)->createLocalCache(properties));
    }

    private static String getCacheInstanceKey(String cacheType, ConsistencyLevel consistencyLevel) {
        return cacheType+"&"+consistencyLevel;
    }

    private static <K,V> LocalCache<K, V> createLocalCache(HccProperties.LocalCacheProperties properties) {
        Class<? extends LocalCache> cacheClz = cacheClzMap.get(properties.getCacheType());
        if (cacheClz==null) {
            throw CacheException.newInstance(CacheError.NOT_EXISTS_LOCAL_CACHE_CLASS);
        }
        LocalCache localCache = ClassUtil.newInstance(cacheClz,
                new Class[]{HccProperties.LocalCacheProperties.class},
                new Object[]{properties}
                );
        return localCache;
    }

}
