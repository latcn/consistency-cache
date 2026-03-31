package com.consist.cache.core.distributed;


import com.consist.cache.core.manager.CacheManager;
import com.consist.cache.core.model.CacheKey;
import com.consist.cache.core.model.CacheValue;

public interface DistributedCacheManager extends CacheManager<CacheKey, CacheValue> {

    boolean isHealthy();
}
