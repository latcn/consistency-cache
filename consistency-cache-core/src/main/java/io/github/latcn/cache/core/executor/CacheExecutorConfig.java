package io.github.latcn.cache.core.executor;

import io.github.latcn.cache.core.circuitbreaker.CacheCircuitBreaker;
import io.github.latcn.cache.core.distributed.DistributedCacheManager;
import io.github.latcn.cache.core.hotspot.HotspotDetector;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.github.latcn.cache.core.local.LocalCacheMarkerManager;
import io.micrometer.core.instrument.MeterRegistry;
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

	private final HotspotDetector writeHotspotDetector;

	private final HotspotDetector readStatistics;

	private final CacheCircuitBreaker cacheCircuitBreaker;

	private final CacheBloomFilter cacheBloomFilter;

	private final MeterRegistry meterRegistry;

}
