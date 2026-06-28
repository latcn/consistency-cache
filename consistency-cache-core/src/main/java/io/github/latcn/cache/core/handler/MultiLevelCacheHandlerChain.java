package io.github.latcn.cache.core.handler;

import io.github.latcn.cache.core.executor.CacheExecutorConfig;
import io.github.latcn.cache.core.model.CacheKey;
import io.github.latcn.cache.core.model.CacheValue;
import io.github.latcn.cache.core.pubsub.Broadcaster;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * 多级缓存处理
 */
@Slf4j
public class MultiLevelCacheHandlerChain {

	private final BaseCacheHandler firstHandler;

	private final CacheMetricsRecorder metricsRecorder;

	public MultiLevelCacheHandlerChain(CacheExecutorConfig cacheExecutorConfig, Broadcaster broadcaster) {
		this(cacheExecutorConfig, broadcaster, CacheMetricsRecorder.of(cacheExecutorConfig.getMeterRegistry()));
	}

	public MultiLevelCacheHandlerChain(CacheExecutorConfig cacheExecutorConfig, Broadcaster broadcaster,
			CacheMetricsRecorder metricsRecorder) {
		this.metricsRecorder = metricsRecorder != null ? metricsRecorder : CacheMetricsRecorder.noOp();
		DbHandler dbHandler = new DbHandler(null, cacheExecutorConfig);
		DistributedCacheHandler distributedCacheHandler = new DistributedCacheHandler(dbHandler, cacheExecutorConfig,
				broadcaster);
		LocalCacheHandler localCacheHandler = new LocalCacheHandler(distributedCacheHandler, cacheExecutorConfig,
				broadcaster);
		this.firstHandler = new PreCheckHandler(localCacheHandler, cacheExecutorConfig);
	}

	public CacheValue get(CacheKey cacheKey, Function doSingleFlightFun) {
		CacheContext cacheContext = CacheContext.builder()
			.cacheKey(cacheKey)
			.doSingleFlightFun(doSingleFlightFun)
			.metricsRecorder(metricsRecorder)
			.build();
		return firstHandler.get(cacheContext);
	}

	public CompletableFuture<CacheValue> getAsync(CacheKey cacheKey, Function doSingleFlightFun) {
		CacheContext cacheContext = CacheContext.builder()
			.cacheKey(cacheKey)
			.doSingleFlightFun(doSingleFlightFun)
			.metricsRecorder(metricsRecorder)
			.build();
		return firstHandler.getAsync(cacheContext);
	}

	public void evict(CacheKey cacheKey) {
		CacheContext cacheContext = CacheContext.builder().cacheKey(cacheKey).metricsRecorder(metricsRecorder).build();
		firstHandler.evict(cacheContext);
	}

	public CompletableFuture<Boolean> evictAsync(CacheKey cacheKey) {
		CacheContext cacheContext = CacheContext.builder().cacheKey(cacheKey).metricsRecorder(metricsRecorder).build();
		return firstHandler.evictAsync(cacheContext);
	}

}
