package io.github.latcn.cache.core.distributed;


import io.github.latcn.cache.core.manager.CacheManager;
import io.github.latcn.cache.core.model.CacheKey;
import io.github.latcn.cache.core.model.CacheValue;

public interface DistributedCacheManager extends CacheManager<CacheKey, CacheValue> {

    boolean isHealthy();
}
