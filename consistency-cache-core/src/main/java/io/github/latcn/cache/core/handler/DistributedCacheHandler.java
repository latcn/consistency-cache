package io.github.latcn.cache.core.handler;

import static io.github.latcn.cache.core.handler.CacheMetricsConstants.L2OperationType.DELETE;
import static io.github.latcn.cache.core.handler.CacheMetricsConstants.L2OperationType.GET;
import static io.github.latcn.cache.core.handler.CacheMetricsConstants.SingleFlightDeduplicationType.CACHE;

import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import io.github.latcn.cache.core.executor.CacheExecutorConfig;
import io.github.latcn.cache.core.manager.SingleFlightExecutor;
import io.github.latcn.cache.core.manager.SingleFlightResult;
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
		CacheValue cacheValue = getValueFromDistributedCache(cacheContext);
		if (cacheValue != null) {
			return cacheValue;
		}
		cacheValue = next.get(cacheContext);
		if (cacheKey.getCacheLevel() == CacheLevel.L2_CACHE) {
			return cacheValue;
		}
		boolean isWriteHotKey = isWriteHotKey(cacheContext);
		if (!isWriteHotKey && cacheExecutorConfig.getReadStatistics().isHotKey(cacheKey.getKey())) {
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
		CompletableFuture<CacheValue> completableFuture = new CompletableFuture<>();
		CompletableFuture<CacheValue> cacheValue = getValueFromDistributedCacheAsync(cacheContext);
		cacheValue.whenComplete((r, e) -> {
			if (e != null) {
				completableFuture.completeExceptionally(e);
				return;
			}
			if (r != null) {
				completableFuture.complete(r);
				return;
			}
			next.getAsync(cacheContext).whenComplete((r1, e1) -> {
				if (e1 != null) {
					completableFuture.completeExceptionally(e1);
					return;
				}
				if (r1 != null) {
					completableFuture.complete(r1);
					return;
				}
				if (cacheKey.getCacheLevel() == CacheLevel.L2_CACHE) {
					return;
				}
				boolean isWriteHotKey = isWriteHotKey(cacheContext);
				if (!isWriteHotKey && cacheExecutorConfig.getReadStatistics().isHotKey(cacheKey.getKey())) {
					if (cacheKey.isBroadcastEnabled()) {
						cacheExecutorConfig.getLocalCacheMarkerManager()
							.markLocalCacheUsage(cacheKey.getKey().toString(), r1.getExpireTime());
					}
					cacheExecutorConfig.getLocalCacheManager().put(cacheKey, r1);
				}
			});

		});
		return completableFuture;
	}

	@Override
	public void evict(CacheContext cacheContext) {
		CacheKey cacheKey = cacheContext.getCacheKey();
		CacheMetricsRecorder recorder = cacheContext.getMetricsRecorder();
		long startTime = System.currentTimeMillis();
		try {
			cacheExecutorConfig.getDistributedCacheManager().remove(cacheKey);
			recorder.recordL2Operation(startTime, DELETE);
		}
		finally {
			deleteAndBroadcast(cacheKey);
		}
	}

	@Override
	public CompletableFuture<Boolean> evictAsync(CacheContext cacheContext) {
		CacheKey cacheKey = cacheContext.getCacheKey();
		CacheMetricsRecorder recorder = cacheContext.getMetricsRecorder();
		long startTime = System.currentTimeMillis();
		return cacheExecutorConfig.getDistributedCacheManager().removeInBatch(cacheKey).whenComplete((r, e) -> {
			if (e == null) {
				recorder.recordL2Operation(startTime, DELETE);
			}
			deleteAndBroadcast(cacheKey);
		});
	}

	private void deleteAndBroadcast(CacheKey cacheKey) {
		if (cacheKey.getCacheLevel() != CacheLevel.ADAPTIVE_CACHE) {
			return;
		}
		cacheExecutorConfig.getLocalCacheManager().remove(cacheKey);
		cacheExecutorConfig.getWriteHotspotDetector().recordInvalidation(cacheKey.getKey());
		if (cacheKey.isBroadcastEnabled()) {
			List<String> nodeIds = cacheExecutorConfig.getLocalCacheMarkerManager()
				.getActiveNodes(cacheKey.getKey().toString());
			if (nodeIds.size() > 0) {
				cacheExecutorConfig.getLocalCacheMarkerManager().removeLocalCacheUsage(cacheKey.getKey().toString());
				if (!(nodeIds.size() == 1 && NodeInstanceHolder.getNodeId().equals(nodeIds.get(0)))) {
					this.broadcaster.addKey(cacheKey);
				}
			}
		}
	}

	private CacheValue getValueFromDistributedCache(CacheContext cacheContext) {
		CacheKey cacheKey = cacheContext.getCacheKey();
		CacheMetricsRecorder recorder = cacheContext.getMetricsRecorder();
		CacheValue cacheValue;
		long startTime = System.currentTimeMillis();
		try {
			SingleFlightResult<CacheValue> result = cacheExecutorConfig.getCacheCircuitBreaker()
				.execute(() -> this.distributedCacheSingleFlightExecutor.executeWithResult(cacheKey,
						(k) -> cacheExecutorConfig.getDistributedCacheManager().get(k)));
			if (result.isDeduplicated()) {
				recorder.recordSingleFlightDeduplication(CACHE);
			}
			cacheValue = result.getValue();
			recorder.recordL2Operation(startTime, GET);
		}
		catch (CacheException e) {
			if (e.getErrorCode() == CacheError.CIRCUIT_BREAKER_OPEN.getErrorCode()) {
				log.warn("Circuit breaker OPEN, bypassing distributed cache for key: {}", cacheKey.getKey());
				recorder.recordCircuitBreakerRejection();
				cacheValue = null;
			}
			else {
				throw e;
			}
		}
		return cacheValue;
	}

	private CompletableFuture<CacheValue> getValueFromDistributedCacheAsync(CacheContext cacheContext) {
		CacheKey cacheKey = cacheContext.getCacheKey();
		CacheMetricsRecorder recorder = cacheContext.getMetricsRecorder();
		long startTime = System.currentTimeMillis();
		return cacheExecutorConfig.getDistributedCacheManager().getInBatch(cacheKey).whenComplete((r, e) -> {
			if (e == null) {
				recorder.recordL2Operation(startTime, GET);
			}
		});
	}

}