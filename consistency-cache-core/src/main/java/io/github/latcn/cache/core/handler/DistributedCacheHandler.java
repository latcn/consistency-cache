package io.github.latcn.cache.core.handler;

import io.github.latcn.cache.core.circuitbreaker.CacheCircuitBreaker;
import io.github.latcn.cache.core.executor.CacheExecutorConfig;
import io.github.latcn.cache.core.manager.SingleFlightExecutor;
import io.github.latcn.cache.core.model.CacheKey;
import io.github.latcn.cache.core.model.CacheLevel;
import io.github.latcn.cache.core.model.CacheValue;
import io.github.latcn.cache.core.model.NodeInstanceHolder;
import io.github.latcn.cache.core.pubsub.Broadcaster;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DistributedCacheHandler extends BaseCacheHandler {

	private final Broadcaster broadcaster;

	private final SingleFlightExecutor distributedCacheSingleFlightExecutor;

	public DistributedCacheHandler(CacheHandler next, CacheExecutorConfig cacheExecutorConfig,
			Broadcaster broadcaster) {
		super(next, cacheExecutorConfig);
		this.broadcaster = broadcaster;
		this.distributedCacheSingleFlightExecutor = new SingleFlightExecutor();
	}

	@Override
	public CacheValue get(CacheContext cacheContext) {
		CacheKey cacheKey = cacheContext.getCacheKey();
		if (cacheKey.getCacheLevel() == CacheLevel.LOCAL_CACHE) {
			return next.get(cacheContext);
		}
		CacheValue cacheValue = getValueFromDistributedCache(cacheKey);
		if (cacheValue != null) {
			return cacheValue;
		}
		// L2 from db
		cacheValue = next.get(cacheContext);
		if (cacheKey.getCacheLevel() == CacheLevel.L2_CACHE) {
			return cacheValue;
		}
		// CacheLevel.ADAPTIVE_CACHE
		// if hot key write to local cache
		boolean isWriteHotKey = isWriteHotKey(cacheContext);
		if (!isWriteHotKey && cacheExecutorConfig.getReadStatistics().isHotKey(cacheKey.getKey())) {
			// 向 redis 上报使用本地缓存的热 key, 更新使用本地缓存的数量
			if (cacheKey.isBroadcastEnabled()) {
				cacheExecutorConfig.getLocalCacheMarkerManager()
					.markLocalCacheUsage(cacheKey.getKey().toString(), cacheValue.getExpireTime());
			}
			cacheExecutorConfig.getLocalCacheManager().put(cacheKey, cacheValue);
		}
		return cacheValue;
	}

	@Override
	public CompletableFuture<CacheValue> getAsync(CacheContext cacheContext) {
		CacheKey cacheKey = cacheContext.getCacheKey();
		if (cacheKey.getCacheLevel() == CacheLevel.LOCAL_CACHE) {
			return next.getAsync(cacheContext);
		}
		CompletableFuture<CacheValue> cacheValue = getValueFromDistributedCacheAsync(cacheKey);
		cacheValue.whenComplete((r, e) -> {
			if (r != null) {
				return;
			}
			// L2 from db
			next.getAsync(cacheContext).whenComplete((r1, e1) -> {
				cacheValue.complete(r1);
				if (cacheKey.getCacheLevel() == CacheLevel.L2_CACHE) {
					return;
				}
				// CacheLevel.ADAPTIVE_CACHE
				// if hot key write to local cache
				boolean isWriteHotKey = isWriteHotKey(cacheContext);
				if (!isWriteHotKey && cacheExecutorConfig.getReadStatistics().isHotKey(cacheKey.getKey())) {
					// 向 redis 上报使用本地缓存的热 key, 更新使用本地缓存的数量
					if (cacheKey.isBroadcastEnabled()) {
						cacheExecutorConfig.getLocalCacheMarkerManager()
							.markLocalCacheUsage(cacheKey.getKey().toString(), r1.getExpireTime());
					}
					cacheExecutorConfig.getLocalCacheManager().put(cacheKey, r1);
				}
			});

		});
		return cacheValue;
	}

	@Override
	public void evict(CacheContext cacheContext) {
		CacheKey cacheKey = cacheContext.getCacheKey();
		cacheExecutorConfig.getDistributedCacheManager().remove(cacheKey);
		deleteAndBroadcast(cacheKey);
	}

	@Override
	public CompletableFuture<Boolean> evictAsync(CacheContext cacheContext) {
		CacheKey cacheKey = cacheContext.getCacheKey();
		return cacheExecutorConfig.getDistributedCacheManager()
			.removeInBatch(cacheKey)
			.whenComplete((r, e) -> deleteAndBroadcast(cacheKey));
	}

	private void deleteAndBroadcast(CacheKey cacheKey) {
		if (cacheKey.getCacheLevel() != CacheLevel.ADAPTIVE_CACHE) {
			return;
		}
		if (cacheKey.isBroadcastEnabled()) {
			List<String> nodeIds = cacheExecutorConfig.getLocalCacheMarkerManager()
				.getActiveNodes(cacheKey.getKey().toString());
			if (nodeIds.size() > 0) {
				cacheExecutorConfig.getLocalCacheMarkerManager().removeLocalCacheUsage(cacheKey.getKey().toString());
				if (!(nodeIds.size() == 1 && NodeInstanceHolder.getNodeId().equals(nodeIds.get(0)))) {
					this.broadcaster.addKey(cacheKey);
				}
				deleteLocalCache(cacheKey);
			}
		}
		else {
			deleteLocalCache(cacheKey);
		}
	}

	private void deleteLocalCache(CacheKey cacheKey) {
		cacheExecutorConfig.getLocalCacheManager().remove(cacheKey);
		// Record invalidation for hotspot detection (write operation)
		cacheExecutorConfig.getWriteHotspotDetector().recordInvalidation(cacheKey.getKey());
	}

	private CacheValue getValueFromDistributedCache(CacheKey cacheKey) {
		CacheValue cacheValue;
		try {
			cacheValue = cacheExecutorConfig.getCacheCircuitBreaker()
				.execute(() -> this.distributedCacheSingleFlightExecutor.execute(cacheKey,
						(k) -> cacheExecutorConfig.getDistributedCacheManager().get(k)));
		}
		catch (CacheCircuitBreaker.CircuitBreakerOpenException e) {
			log.warn("Circuit breaker OPEN, bypassing distributed cache for key: {}", cacheKey.getKey());
			cacheValue = null;
		}
		return cacheValue;
	}

	private CompletableFuture<CacheValue> getValueFromDistributedCacheAsync(CacheKey cacheKey) {
		return cacheExecutorConfig.getDistributedCacheManager().getInBatch(cacheKey);
	}

}
