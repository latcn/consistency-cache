package io.github.latcn.cache.core.handler;

import io.github.latcn.cache.core.exception.CacheException;
import io.github.latcn.cache.core.executor.CacheExecutorConfig;
import io.github.latcn.cache.core.manager.SingleFlightExecutor;
import io.github.latcn.cache.core.model.CacheKey;
import io.github.latcn.cache.core.model.CacheLevel;
import io.github.latcn.cache.core.model.CacheValue;
import io.github.latcn.cache.core.util.ThreadPoolUtils;
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
		this.threadPool = ThreadPoolUtils.getThreadPool("DbHandler-getAsync", 256);
	}

	@Override
	public CacheValue get(CacheContext cacheContext) {
		CacheKey cacheKey = cacheContext.getCacheKey();
		Function doSingleFlightFun = cacheContext.getDoSingleFlightFun();
		return loadFromDb(cacheKey, doSingleFlightFun);
	}

	@Override
	public CompletableFuture<CacheValue> getAsync(CacheContext cacheContext) {
		CacheKey cacheKey = cacheContext.getCacheKey();
		Function doSingleFlightFun = cacheContext.getDoSingleFlightFun();
		return loadFromDbAsync(cacheKey, doSingleFlightFun);
	}

	@Override
	public void evict(CacheContext cacheContext) {
		throw new CacheException("evict should not execute: " + DbHandler.class.getName());
	}

	@Override
	public CompletableFuture<Boolean> evictAsync(CacheContext cacheContext) {
		throw new CacheException("evict should not execute: " + DbHandler.class.getName());
	}

	private CacheValue loadFromDb(CacheKey cacheKey, Function doSingleFlightFun) {
		CacheValue value = this.dbSingleFlightExecutor.execute(cacheKey, (k) -> {
			Object result = doSingleFlightFun.apply(k.getKey());
			if (result == null) {
				if (!k.isCacheNullValues()) {
					return null;
				}
			}
			CacheValue cacheValue = CacheValue.builder().value(result).createdAt(System.currentTimeMillis()).build();
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
		return value;
	}

	private CompletableFuture loadFromDbAsync(CacheKey cacheKey, Function doSingleFlightFun) {
		CompletableFuture completableResult = new CompletableFuture();
		CompletableFuture completableFuture = CompletableFuture
			.supplyAsync(() -> doSingleFlightFun.apply(cacheKey.getKey()), threadPool);
		completableFuture.whenComplete((r, e) -> {
			if (r == null) {
				if (!cacheKey.isCacheNullValues()) {
					completableResult.complete(null);
					return;
				}
			}
			CacheValue cacheValue = CacheValue.builder().value(r).createdAt(System.currentTimeMillis()).build();
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
					.whenComplete((r1, e1) -> completableResult.complete(cacheValue));
			}
		});
		return completableResult;
	}

}
