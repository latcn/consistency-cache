package com.consist.cache.core.executor;

import com.consist.cache.core.circuitbreaker.CacheCircuitBreaker;
import com.consist.cache.core.hotspot.reads.ReadHotspotDetector;
import com.consist.cache.core.hotspot.writes.WriteHotspotDetector;
import com.consist.cache.core.local.LocalCacheManager;
import com.consist.cache.core.local.LocalCacheMarkerManager;
import lombok.Builder;
import lombok.Data;

/**
 * CacheExecutor配置参数对象
 */
@Data
@Builder
public class CacheExecutorConfig {

    private final LocalCacheManager localCacheManager;
    private final com.consist.cache.core.distributed.DistributedCacheManager distributedCacheManager;
    private final LocalCacheMarkerManager localCacheMarkerManager;
    private final WriteHotspotDetector writeHotspotDetector;
    private final ReadHotspotDetector readStatistics;
    private final CacheCircuitBreaker cacheCircuitBreaker;
    private final CacheBloomFilter cacheBloomFilter;
}
