package io.github.latcn.cache.core.distributed;

import io.github.latcn.cache.core.manager.CacheManager;
import io.github.latcn.cache.core.model.CacheKey;
import io.github.latcn.cache.core.model.CacheValue;
import java.util.concurrent.CompletableFuture;

public interface DistributedCacheManager extends CacheManager<CacheKey, CacheValue> {

	boolean isHealthy();

	CompletableFuture<CacheValue> getInBatch(CacheKey key);

	CompletableFuture<Boolean> putInBatch(CacheKey key, CacheValue cacheValue);

	CompletableFuture<Boolean> removeInBatch(CacheKey key);

}
