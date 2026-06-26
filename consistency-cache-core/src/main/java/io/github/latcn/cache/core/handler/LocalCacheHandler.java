package io.github.latcn.cache.core.handler;

import io.github.latcn.cache.core.executor.CacheExecutorConfig;
import io.github.latcn.cache.core.model.CacheKey;
import io.github.latcn.cache.core.model.CacheLevel;
import io.github.latcn.cache.core.model.CacheValue;
import io.github.latcn.cache.core.model.ConsistencyLevel;
import io.github.latcn.cache.core.pubsub.Broadcaster;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LocalCacheHandler extends BaseCacheHandler {

	private final Broadcaster broadcaster;

	public LocalCacheHandler(CacheHandler next, CacheExecutorConfig cacheExecutorConfig, Broadcaster broadcaster) {
		super(next, cacheExecutorConfig);
		this.broadcaster = broadcaster;
	}

	@Override
	public CacheValue get(CacheContext cacheContext) {
		CacheKey cacheKey = cacheContext.getCacheKey();
		CacheLevel level = cacheKey.getCacheLevel();
		if (level == CacheLevel.LOCAL_CACHE) {
			return getFromLocalCache(cacheContext);
		}
		else if (level == CacheLevel.ADAPTIVE_CACHE) {
			return getFromAdaptiveCache(cacheContext);
		}
		else {
			return next.get(cacheContext);
		}
	}

	@Override
	public CompletableFuture<CacheValue> getAsync(CacheContext cacheContext) {
		CacheKey cacheKey = cacheContext.getCacheKey();
		CacheLevel level = cacheKey.getCacheLevel();
		if (level == CacheLevel.LOCAL_CACHE) {
			return CompletableFuture.completedFuture(getFromLocalCache(cacheContext));
		}
		else if (level == CacheLevel.ADAPTIVE_CACHE) {
			return getFromAdaptiveCacheAsync(cacheContext);
		}
		else {
			return next.getAsync(cacheContext);
		}
	}

	@Override
	public void evict(CacheContext cacheContext) {
		CacheKey cacheKey = cacheContext.getCacheKey();
		if (cacheKey.getCacheLevel() == CacheLevel.LOCAL_CACHE) {
			evictLocalCache(cacheKey);
		}
		else {
			next.evict(cacheContext);
		}
	}

	@Override
	public CompletableFuture<Boolean> evictAsync(CacheContext cacheContext) {
		CacheKey cacheKey = cacheContext.getCacheKey();
		if (cacheKey.getCacheLevel() == CacheLevel.LOCAL_CACHE) {
			evictLocalCache(cacheKey);
			return CompletableFuture.completedFuture(true);
		}
		else {
			return next.evictAsync(cacheContext);
		}
	}

	private void evictLocalCache(CacheKey cacheKey) {
		cacheExecutorConfig.getLocalCacheManager().remove(cacheKey);
		cacheExecutorConfig.getWriteHotspotDetector().recordInvalidation(cacheKey.getKey());
		if (cacheKey.isBroadcastEnabled()) {
			if (cacheKey.getConsistencyLevel() == ConsistencyLevel.HIGH
					|| !cacheExecutorConfig.getWriteHotspotDetector().shouldBypassL1(cacheKey.getKey())) {
				this.broadcaster.addKey(cacheKey);
			}
		}
	}

	private CacheValue getFromLocalCache(CacheContext cacheContext) {
		cacheContext.getMetricsRecorder().recordL1Request();
		CacheValue cacheValue = cacheExecutorConfig.getLocalCacheManager().get(cacheContext.getCacheKey());
		if (cacheValue != null) {
			cacheContext.setL1Hit(true);
			cacheContext.getMetricsRecorder().recordL1Hit();
			return cacheValue;
		}
		cacheContext.getMetricsRecorder().recordL1Miss();
		return next.get(cacheContext);
	}

	private CacheValue getFromAdaptiveCache(CacheContext cacheContext) {
		cacheContext.getMetricsRecorder().recordL1Request();
		CacheKey cacheKey = cacheContext.getCacheKey();
		CacheValue cacheValue = null;
		boolean isWriteHotKey = cacheExecutorConfig.getWriteHotspotDetector().shouldBypassL1(cacheKey.getKey());
		setIsWriteHotKey(cacheContext, isWriteHotKey);
		if (!isWriteHotKey) {
			cacheValue = cacheExecutorConfig.getLocalCacheManager().get(cacheKey);
		}
		if (cacheValue != null) {
			cacheContext.setL1Hit(true);
			cacheContext.getMetricsRecorder().recordL1Hit();
			return cacheValue;
		}
		cacheContext.getMetricsRecorder().recordL1Miss();
		return next.get(cacheContext);
	}

	private CompletableFuture<CacheValue> getFromAdaptiveCacheAsync(CacheContext cacheContext) {
		cacheContext.getMetricsRecorder().recordL1Request();
		CacheKey cacheKey = cacheContext.getCacheKey();
		CacheValue cacheValue = null;
		boolean isWriteHotKey = cacheExecutorConfig.getWriteHotspotDetector().shouldBypassL1(cacheKey.getKey());
		setIsWriteHotKey(cacheContext, isWriteHotKey);
		if (!isWriteHotKey) {
			cacheValue = cacheExecutorConfig.getLocalCacheManager().get(cacheKey);
		}
		if (cacheValue != null) {
			cacheContext.setL1Hit(true);
			cacheContext.getMetricsRecorder().recordL1Hit();
			return CompletableFuture.completedFuture(cacheValue);
		}
		cacheContext.getMetricsRecorder().recordL1Miss();
		return next.getAsync(cacheContext);
	}

}
