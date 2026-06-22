package io.github.latcn.cache.core.local;

import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import io.github.latcn.cache.core.manager.CacheManager;
import io.github.latcn.cache.core.model.CacheKey;
import io.github.latcn.cache.core.model.CacheValue;
import io.github.latcn.cache.core.model.ConsistencyLevel;
import io.github.latcn.cache.core.model.HccProperties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class LocalCacheManager implements CacheManager<CacheKey, CacheValue> {

	private final HccProperties.LocalCacheProperties properties;

	private final ConcurrentHashMap<ConsistencyLevel, LocalCache<Object, CacheValue>> cacheLevelMap;

	private final AtomicLong hitCount = new AtomicLong(0);

	private final AtomicLong missCount = new AtomicLong(0);

	private final AtomicLong evictionCount = new AtomicLong(0);

	private static final Set<Class> supportKeyClz = Set.of(Integer.class, Long.class, String.class);

	public LocalCacheManager(HccProperties.LocalCacheProperties properties) {
		this.properties = properties;
		this.cacheLevelMap = new ConcurrentHashMap<>();
	}

	public ConcurrentHashMap<ConsistencyLevel, LocalCache<Object, CacheValue>> getCacheLevelMap() {
		return this.cacheLevelMap;
	}

	private LocalCache<Object, CacheValue> getOrCreateCache(ConsistencyLevel consistencyLevel) {
		if (consistencyLevel == null) {
			consistencyLevel = ConsistencyLevel.HIGH;
		}
		LocalCache<Object, CacheValue> localCache = this.cacheLevelMap.get(consistencyLevel);
		if (localCache == null) {
			ConsistencyLevel finalConsistencyLevel = consistencyLevel;
			localCache = this.cacheLevelMap.computeIfAbsent(consistencyLevel,
					(k) -> LocalCacheFactory.getOrCreateLocalCache(finalConsistencyLevel, this.properties));
		}
		return localCache;
	}

	@Override
	public CacheValue get(CacheKey cacheKey) {
		checkCacheKey(cacheKey);
		CacheValue cacheValue = getOrCreateCache(cacheKey.getConsistencyLevel()).get(cacheKey.getKey());
		if (cacheValue == null) {
			this.missCount.incrementAndGet();
			return null;
		}
		if (cacheValue.isExpired()) {
			getOrCreateCache(cacheKey.getConsistencyLevel()).invalidate(cacheKey.getKey());
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
		getOrCreateCache(cacheKey.getConsistencyLevel()).put(cacheKey.getKey(), cacheValue);
	}

	public CacheValue getByActualKey(Object key) {
		checkActualCacheKey(key);
		return iterateCachesWithReturn(cache -> cache.get(key));
	}

	public void removeByActualKey(Object key) {
		checkActualCacheKey(key);
		iterateCachesWithoutReturn(cache -> cache.invalidate(key));
	}

	@Override
	public void remove(CacheKey cacheKey) {
		if (containKey(cacheKey)) {
			getOrCreateCache(cacheKey.getConsistencyLevel()).invalidate(cacheKey.getKey());
		}
	}

	public void clear() {
		iterateCachesWithoutReturn(LocalCache::invalidateAll);
	}

	@Override
	public boolean containKey(CacheKey cacheKey) {
		checkCacheKey(cacheKey);
		CacheValue cacheValue = getOrCreateCache(cacheKey.getConsistencyLevel()).get(cacheKey.getKey());
		return cacheValue != null && !cacheValue.isExpired();
	}

	public long getSize() {
		long[] total = { 0 };
		iterateCachesWithoutReturn(cache -> total[0] += cache.getSize());
		return total[0];
	}

	/**
	 * manually cleanUp() forces immediate processing
	 */
	public void runEviction() {
		iterateCachesWithoutReturn(LocalCache::cleanUp);
	}

	private void checkCacheKey(CacheKey cacheKey) {
		if (cacheKey == null) {
			throw CacheException.newInstance(CacheError.EMPTY_KEY);
		}
		checkActualCacheKey(cacheKey.getKey());
	}

	private void checkActualCacheKey(Object key) {
		if (key == null) {
			throw CacheException.newInstance(CacheError.EMPTY_KEY);
		}
		if (!supportKeyClz.contains(key.getClass())) {
			throw CacheException.newInstance(CacheError.ERROR_KEY_TYPE);
		}
	}

	private <T> T iterateCachesWithReturn(java.util.function.Function<LocalCache<Object, CacheValue>, T> function) {
		for (java.util.Map.Entry<ConsistencyLevel, LocalCache<Object, CacheValue>> entry : cacheLevelMap.entrySet()) {
			T result = function.apply(entry.getValue());
			if (result != null) {
				return result;
			}
		}
		return null;
	}

	private void iterateCachesWithoutReturn(Consumer<LocalCache<Object, CacheValue>> consumer) {
		for (java.util.Map.Entry<ConsistencyLevel, LocalCache<Object, CacheValue>> entry : cacheLevelMap.entrySet()) {
			consumer.accept(entry.getValue());
		}
	}

	@lombok.Data
	@lombok.Builder
	public static class CacheStats {

		private long hitCount;

		private long missCount;

		private double hitRate;

		private long size;

		private long maxSize;

		private long evictionCount;

		public String getFormattedHitRate() {
			return String.format("%.2f", this.hitRate * 100) + "%";
		}

	}

	public CacheStats getStats() {
		long totalRequests = this.hitCount.get() + this.missCount.get();
		double hitRate = totalRequests > 0 ? (double) this.hitCount.get() / totalRequests : 0.0;
		int[] totalSize = { 0 };
		int[] totalMaxSize = { 0 };
		iterateCachesWithoutReturn(cache -> {
			totalSize[0] += cache.getSize();
			totalMaxSize[0] += cache.getMaxSize();
		});
		return CacheStats.builder()
			.hitCount(this.hitCount.get())
			.missCount(this.missCount.get())
			.hitRate(hitRate)
			.size(totalSize[0])
			.maxSize(totalMaxSize[0])
			.evictionCount(this.evictionCount.get())
			.build();
	}

}
