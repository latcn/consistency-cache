package io.github.latcn.cache.core.executor;

import io.github.latcn.cache.core.circuitbreaker.CacheCircuitBreaker;
import io.github.latcn.cache.core.distributed.DistributedCacheManager;
import io.github.latcn.cache.core.hotspot.reads.ReadHotspotDetector;
import io.github.latcn.cache.core.hotspot.writes.WriteHotspotDetector;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.github.latcn.cache.core.local.LocalCacheMarkerManager;
import lombok.Builder;
import lombok.Data;

/**
 * CacheExecutor配置参数对象
 */
@Data
@Builder
public class CacheExecutorConfig {

	private final LocalCacheManager localCacheManager;

	private final DistributedCacheManager distributedCacheManager;

	private final LocalCacheMarkerManager localCacheMarkerManager;

	private final WriteHotspotDetector writeHotspotDetector;

	private final ReadHotspotDetector readStatistics;

	private final CacheCircuitBreaker cacheCircuitBreaker;

	private final CacheBloomFilter cacheBloomFilter;

}
