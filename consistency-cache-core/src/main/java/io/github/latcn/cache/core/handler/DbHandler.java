package io.github.latcn.cache.core.handler;

import static io.github.latcn.cache.core.handler.CacheMetricsConstants.SingleFlightDeduplicationType.DB;

import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import io.github.latcn.cache.core.executor.CacheExecutorConfig;
import io.github.latcn.cache.core.manager.SingleFlightExecutor;
import io.github.latcn.cache.core.manager.SingleFlightResult;
import io.github.latcn.cache.core.model.CacheKey;
import io.github.latcn.cache.core.model.CacheLevel;
import io.github.latcn.cache.core.model.CacheValue;
import io.github.latcn.cache.core.util.ThreadUtils;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DbHandler extends BaseCacheHandler {

	private final SingleFlightExecutor dbSingleFlightExecutor;

	private final ExecutorService threadPool;

	public DbHandler(CacheHandler next, CacheExecutorConfig cacheExecutorConfig) {
		super(next, cacheExecutorConfig);
		this.dbSingleFlightExecutor = new SingleFlightExecutor();
		this.threadPool = ThreadUtils.getThreadPool("DbHandler-getAsync", true, 256);
	}

	@Override
	public CacheValue get(CacheContext cacheContext) {
		CacheKey cacheKey = cacheContext.getCacheKey();
		Function doSingleFlightFun = cacheContext.getDoSingleFlightFun();
		CacheMetricsRecorder recorder = cacheContext.getMetricsRecorder();
		long startTime = System.currentTimeMillis();
		try {
			return loadFromDb(cacheKey, doSingleFlightFun, recorder);
		}
		finally {
			recorder.recordDbOperation(startTime);
		}
	}

	@Override
	public CompletableFuture<CacheValue> getAsync(CacheContext cacheContext) {
		CacheKey cacheKey = cacheContext.getCacheKey();
		Function doSingleFlightFun = cacheContext.getDoSingleFlightFun();
		CacheMetricsRecorder recorder = cacheContext.getMetricsRecorder();
		long startTime = System.currentTimeMillis();
		return loadFromDbAsync(cacheKey, doSingleFlightFun)
			.whenComplete((r, e) -> recorder.recordDbOperation(startTime));
	}

	@Override
	public void evict(CacheContext cacheContext) {
		throw new CacheException(CacheError.UNSUPPORTED_OPERATION, "evict should not execute on DbHandler");
	}

	@Override
	public CompletableFuture<Boolean> evictAsync(CacheContext cacheContext) {
		throw new CacheException(CacheError.UNSUPPORTED_OPERATION, "evict should not execute on DbHandler");
	}

	private CacheValue loadFromDb(CacheKey cacheKey, Function doSingleFlightFun, CacheMetricsRecorder recorder) {
		SingleFlightResult<CacheValue> result = this.dbSingleFlightExecutor.executeWithResult(cacheKey, (k) -> {
			Object dbResult = doSingleFlightFun.apply(k.getKey());
			if (dbResult == null) {
				if (!k.isCacheNullValues()) {
					return null;
				}
			}
			CacheValue cacheValue = CacheValue.builder()
				.value(dbResult)
				.createdAt(System.currentTimeMillis())
				.build();
			if (cacheKey.getExpireTimeMs() > 0) {
				cacheValue.setExpireTime(System.currentTimeMillis() + cacheKey.getExpireTimeMs());
			}
			if (cacheKey.getCacheLevel() == CacheLevel.LOCAL_CACHE) {
				cacheExecutorConfig.getLocalCacheManager().put(k, cacheValue);
			}
			else if (cacheKey.getCacheLevel() == CacheLevel.ADAPTIVE_CACHE
					|| cacheKey.getCacheLevel() == CacheLevel.L2_CACHE) {
				cacheExecutorConfig.getDistributedCacheManager().put(k, cacheValue);
			}
			return cacheValue;
		});
		if (result.isDeduplicated()) {
			recorder.recordSingleFlightDeduplication(DB);
		}
		return result.getValue();
	}

	private CompletableFuture<CacheValue> loadFromDbAsync(CacheKey cacheKey, Function doSingleFlightFun) {
		CompletableFuture<CacheValue> completableResult = new CompletableFuture<>();
		CompletableFuture<Object> completableFuture = CompletableFuture
			.supplyAsync(() -> doSingleFlightFun.apply(cacheKey.getKey()), threadPool);
		completableFuture.whenComplete((r, e) -> {
			if (e != null) {
				completableResult.completeExceptionally(e);
				return;
			}
			if (r == null) {
				if (!cacheKey.isCacheNullValues()) {
					completableResult.complete(null);
					return;
				}
			}
			CacheValue cacheValue = CacheValue.builder()
				.value(r)
				.createdAt(System.currentTimeMillis())
				.build();
			if (cacheKey.getExpireTimeMs() > 0) {
				cacheValue.setExpireTime(System.currentTimeMillis() + cacheKey.getExpireTimeMs());
			}
			if (cacheKey.getCacheLevel() == CacheLevel.LOCAL_CACHE) {
				cacheExecutorConfig.getLocalCacheManager().put(cacheKey, cacheValue);
				completableResult.complete(cacheValue);
			}
			else if (cacheKey.getCacheLevel() == CacheLevel.ADAPTIVE_CACHE
					|| cacheKey.getCacheLevel() == CacheLevel.L2_CACHE) {
				cacheExecutorConfig.getDistributedCacheManager()
					.putInBatch(cacheKey, cacheValue)
					.whenComplete((r1, e1) -> {
						if (e1 != null) {
							completableResult.completeExceptionally(e1);
						}
						else {
							completableResult.complete(cacheValue);
						}
					});
			}
		});
		return completableResult;
	}

}