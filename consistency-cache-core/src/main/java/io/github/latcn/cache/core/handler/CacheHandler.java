package io.github.latcn.cache.core.handler;

import io.github.latcn.cache.core.model.CacheValue;
import java.util.concurrent.CompletableFuture;

public interface CacheHandler {

	CacheValue get(CacheContext cacheContext);

	CompletableFuture<CacheValue> getAsync(CacheContext cacheContext);

	void evict(CacheContext cacheContext);

	CompletableFuture<Boolean> evictAsync(CacheContext cacheContext);

}
