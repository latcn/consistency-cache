package io.github.latcn.cache.core.local;

import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import io.github.latcn.cache.core.model.ConsistencyLevel;
import io.github.latcn.cache.core.model.HccProperties;
import io.github.latcn.cache.core.util.ClassUtil;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LocalCacheFactory {

	private static final ConcurrentHashMap<String, Class<? extends LocalCache>> cacheClzMap = new ConcurrentHashMap<>();

	private static final ConcurrentHashMap<String, LocalCache> cacheInstanceMap = new ConcurrentHashMap<>();

	public static void registerCacheType(String cacheType, String cacheClz) {
		Class clz = ClassUtil.getClzByClassName(cacheClz);
		if (clz == null || !LocalCache.class.isAssignableFrom(clz)) {
			log.error("registerCacheType cacheType:{} cacheClz:{}", cacheType, cacheClz);
			return;
		}
		cacheClzMap.putIfAbsent(cacheType, clz);
	}

	public static void removeByCacheType(String cacheType) {
		cacheClzMap.remove(cacheType);
	}

	public static <K, V> LocalCache<K, V> getOrCreateLocalCache(ConsistencyLevel consistencyLevel,
			HccProperties.LocalCacheProperties properties) {
		String cacheInstanceKey = getCacheInstanceKey(properties.getCacheType(), consistencyLevel);
		LocalCache localCache = cacheInstanceMap.get(cacheInstanceKey);
		if (localCache != null) {
			return localCache;
		}
		return cacheInstanceMap.computeIfAbsent(cacheInstanceKey, (k) -> createLocalCache(properties));
	}

	private static String getCacheInstanceKey(String cacheType, ConsistencyLevel consistencyLevel) {
		return cacheType + "&" + consistencyLevel;
	}

	private static <K, V> LocalCache<K, V> createLocalCache(HccProperties.LocalCacheProperties properties) {
		Class<? extends LocalCache> cacheClz = cacheClzMap.get(properties.getCacheType());
		if (cacheClz == null) {
			throw CacheException.newInstance(CacheError.NOT_EXISTS_LOCAL_CACHE_CLASS);
		}
		LocalCache localCache = ClassUtil.newInstance(cacheClz,
				new Class[] { HccProperties.LocalCacheProperties.class }, new Object[] { properties });
		return localCache;
	}

}
