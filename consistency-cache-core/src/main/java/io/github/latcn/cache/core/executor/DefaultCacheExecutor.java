package io.github.latcn.cache.core.executor;

import io.github.latcn.cache.core.circuitbreaker.CacheCircuitBreaker;
import io.github.latcn.cache.core.distributed.DistributedCacheManager;
import io.github.latcn.cache.core.handler.MultiLevelCachePipeLine;
import io.github.latcn.cache.core.hotspot.reads.ReadHotspotDetector;
import io.github.latcn.cache.core.hotspot.writes.WriteHotspotDetector;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.github.latcn.cache.core.local.LocalCacheMarkerManager;
import io.github.latcn.cache.core.model.CacheKey;
import io.github.latcn.cache.core.model.CacheLevel;
import io.github.latcn.cache.core.model.CacheValue;
import io.github.latcn.cache.core.pubsub.Broadcaster;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * DefaultCacheExecutor with circuit breaker protection.
 */
@Slf4j
public class DefaultCacheExecutor implements CacheExecutor {

	private Broadcaster broadcaster;

	private final CacheExecutorConfig cacheExecutorConfig;

	private MultiLevelCachePipeLine multiLevelCachePipeLine;

	public DefaultCacheExecutor(LocalCacheManager localCacheManager, DistributedCacheManager distributedCacheManager,
			LocalCacheMarkerManager localCacheMarkerManager, WriteHotspotDetector writeHotspotDetector,
			ReadHotspotDetector readStatistics, CacheCircuitBreaker cacheCircuitBreaker,
			CacheBloomFilter cacheBloomFilter) {
		this(CacheExecutorConfig.builder()
			.localCacheManager(localCacheManager)
			.distributedCacheManager(distributedCacheManager)
			.localCacheMarkerManager(localCacheMarkerManager)
			.writeHotspotDetector(writeHotspotDetector)
			.readStatistics(readStatistics)
			.cacheCircuitBreaker(cacheCircuitBreaker)
			.cacheBloomFilter(cacheBloomFilter)
			.build());
	}

	public DefaultCacheExecutor(CacheExecutorConfig config) {
		this.cacheExecutorConfig = config;
		log.info("Initialized DefaultCacheExecutor with circuit breaker protection");
	}

	public void setBroadcaster(Broadcaster broadcaster) {
		this.broadcaster = broadcaster;
		this.multiLevelCachePipeLine = new MultiLevelCachePipeLine(this.cacheExecutorConfig, broadcaster);
	}

	/**
	 * 数据变更，失效L1/L2缓存，本地缓存需要广播失效消息
	 * @param cacheKey
	 */
	@Override
	public void evict(CacheKey cacheKey) {
		multiLevelCachePipeLine.evict(cacheKey);
	}

	@Override
	public boolean exists(CacheKey cacheKey) {
		CacheKey.check(cacheKey);
		if (cacheKey.getCacheLevel() == CacheLevel.LOCAL_CACHE) {
			return cacheExecutorConfig.getLocalCacheManager().containKey(cacheKey);
		}
		// ADAPTIVE_CACHE key是否存在以L2为主
		if (cacheKey.getCacheLevel() == CacheLevel.ADAPTIVE_CACHE || cacheKey.getCacheLevel() == CacheLevel.L2_CACHE) {
			return cacheExecutorConfig.getDistributedCacheManager().containKey(cacheKey);
		}
		return false;
	}

	@Override
	public CacheValue get(CacheKey cacheKey, Function doSingleFlightFun) {
		return multiLevelCachePipeLine.get(cacheKey, doSingleFlightFun);
	}

	@Override
	public CompletableFuture<Boolean> evictAsync(CacheKey cacheKey) {
		return multiLevelCachePipeLine.evictAsync(cacheKey);
	}

	@Override
	public CompletableFuture<CacheValue> getAsync(CacheKey cacheKey, Function doSingleFlightFun) {
		return multiLevelCachePipeLine.getAsync(cacheKey, doSingleFlightFun);
	}

}
