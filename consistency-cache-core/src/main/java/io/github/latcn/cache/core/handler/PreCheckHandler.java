package io.github.latcn.cache.core.handler;

import io.github.latcn.cache.core.executor.CacheExecutorConfig;
import io.github.latcn.cache.core.model.CacheKey;
import io.github.latcn.cache.core.model.CacheValue;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PreCheckHandler extends BaseCacheHandler {

	public PreCheckHandler(CacheHandler next, CacheExecutorConfig cacheExecutorConfig) {
		super(next, cacheExecutorConfig);
	}

	@Override
	public CacheValue get(CacheContext cacheContext) {
		CacheKey cacheKey = cacheContext.getCacheKey();
		if (!checkAndRecord(cacheKey)) {
			return null;
		}
		return next.get(cacheContext);
	}

	@Override
	public CompletableFuture<CacheValue> getAsync(CacheContext cacheContext) {
		CacheKey cacheKey = cacheContext.getCacheKey();
		if (!checkAndRecord(cacheKey)) {
			return null;
		}
		return next.getAsync(cacheContext);
	}

	@Override
	public void evict(CacheContext cacheContext) {
		CacheKey cacheKey = cacheContext.getCacheKey();
		CacheKey.check(cacheKey);
		next.evict(cacheContext);
	}

	@Override
	public CompletableFuture<Boolean> evictAsync(CacheContext cacheContext) {
		CacheKey cacheKey = cacheContext.getCacheKey();
		CacheKey.check(cacheKey);
		return next.evictAsync(cacheContext);
	}

	private boolean checkAndRecord(CacheKey cacheKey) {
		CacheKey.check(cacheKey);
		// read record
		cacheExecutorConfig.getReadStatistics().recordRead(cacheKey.getKey());
		// 布隆过滤器校验
		return existsInBloomFilter(cacheKey);
	}

	private boolean existsInBloomFilter(CacheKey cacheKey) {
		if (cacheKey.isBloomFilterEnabled()) {
			try {
				return cacheExecutorConfig.getCacheBloomFilter()
					.exists(cacheKey.getBloomFilterName(), cacheKey.getKey().toString());
			}
			catch (Exception e) {
				log.error("existsInBloomFilter", e);
			}
		}
		return true;
	}

}
