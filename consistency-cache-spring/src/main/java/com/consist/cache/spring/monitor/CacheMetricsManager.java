package com.consist.cache.spring.monitor;

import com.consist.cache.core.circuitbreaker.CacheCircuitBreaker;
import com.consist.cache.core.distributed.DistributedCacheManager;
import com.consist.cache.core.hotspot.reads.ReadHotspotDetector;
import com.consist.cache.core.hotspot.writes.WriteHotspotDetector;
import com.consist.cache.core.local.LocalCacheManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * Centralized Metrics Manager for cache operations
 * Provides convenient methods to record various metrics
 */
@Slf4j
public class CacheMetricsManager {

    private final MeterRegistry meterRegistry;
    private final CacheMetricsBinder metricsBinder;
    // Timers
    private final Timer cacheGetTimer;
    private final Timer cachePutTimer;
    private final Timer cacheEvictTimer;

    public CacheMetricsManager(MeterRegistry meterRegistry,
                               LocalCacheManager localCacheManager,
                               DistributedCacheManager distributedCacheManager,
                               CacheCircuitBreaker circuitBreaker,
                               ReadHotspotDetector readHotspotDetector,
                               WriteHotspotDetector writeHotspotDetector) {
        this.meterRegistry = meterRegistry;
        
        // Create and bind metrics
        this.metricsBinder = new CacheMetricsBinder(
            localCacheManager,
            distributedCacheManager,
            circuitBreaker,
            readHotspotDetector,
            writeHotspotDetector
        );
        this.metricsBinder.bindTo(meterRegistry);

        // Initialize timers
        this.cacheGetTimer = Timer.builder("hcc_cache_operation_duration_seconds")
                .description("Duration of cache get operations")
                .tag("operation", "get")
                .tag("cache_level", "L1")
                .register(meterRegistry);
        
        this.cachePutTimer = Timer.builder("hcc_cache_operation_duration_seconds")
                .description("Duration of cache put operations")
                .tag("operation", "put")
                .tag("cache_level", "L1")
                .register(meterRegistry);
        
        this.cacheEvictTimer = Timer.builder("hcc_cache_operation_duration_seconds")
                .description("Duration of cache evict operations")
                .tag("operation", "evict")
                .tag("cache_level", "L1")
                .register(meterRegistry);
        
        log.info("Cache Metrics Manager initialized with Micrometer registry: {}", 
                meterRegistry.getClass().getSimpleName());
    }
    
    /**
     * Record cache get operation with timing
     */
    public <T> T recordCacheGet(String key, java.util.function.Supplier<T> operation) {
        return cacheGetTimer.record(operation);
    }
    
    /**
     * Record cache put operation with timing
     */
    public void recordCachePut(String key, Runnable operation) {
        cachePutTimer.record(operation);
    }
    
    /**
     * Record cache evict operation with timing
     */
    public void recordCacheEvict(String key, Runnable operation) {
        cacheEvictTimer.record(operation);
    }
    
    /**
     * Get current hit rate from LocalCacheManager
     */
    public double getHitRate() {
        if (metricsBinder != null) {
            // Will be calculated from gauges
            return 0.0; // Placeholder - actual value comes from Gauge
        }
        return 0.0;
    }
    
    /**
     * Get current cache size
     */
    public long getCacheSize() {
        if (metricsBinder != null) {
            // Will be read from Gauge
            return 0L; // Placeholder
        }
        return 0L;
    }
    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }
    /**
     * Export metrics summary for logging
     */
    public void logMetricsSummary() {
        log.info("=== Cache Metrics Summary ===");
        log.info("Cache Get Avg Time: {} ms", cacheGetTimer.mean(TimeUnit.MILLISECONDS));
        log.info("Cache Put Avg Time: {} ms", cachePutTimer.mean(TimeUnit.MILLISECONDS));
        log.info("==============================");
    }
}
