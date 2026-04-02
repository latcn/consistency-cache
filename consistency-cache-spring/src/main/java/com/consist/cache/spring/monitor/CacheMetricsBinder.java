package com.consist.cache.spring.monitor;

import com.consist.cache.core.circuitbreaker.CacheCircuitBreaker;
import com.consist.cache.core.distributed.DistributedCacheManager;
import com.consist.cache.core.hotspot.reads.ReadHotspotDetector;
import com.consist.cache.core.hotspot.writes.WriteHotspotDetector;
import com.consist.cache.core.local.LocalCacheManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Cache Metrics Binder for Micrometer
 * Binds cache performance metrics to MeterRegistry for Prometheus scraping
 */
public class CacheMetricsBinder implements MeterBinder {

    private final LocalCacheManager localCacheManager;
    private final DistributedCacheManager distributedCacheManager;
    private final CacheCircuitBreaker circuitBreaker;
    private final ReadHotspotDetector readHotspotDetector;
    private final WriteHotspotDetector writeHotspotDetector;
    
    private MeterRegistry meterRegistry;

    public CacheMetricsBinder(LocalCacheManager localCacheManager,
                              DistributedCacheManager distributedCacheManager,
                              CacheCircuitBreaker circuitBreaker,
                              ReadHotspotDetector readHotspotDetector,
                              WriteHotspotDetector writeHotspotDetector) {
        this.localCacheManager = localCacheManager;
        this.distributedCacheManager = distributedCacheManager;
        this.circuitBreaker = circuitBreaker;
        this.readHotspotDetector = readHotspotDetector;
        this.writeHotspotDetector = writeHotspotDetector;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        this.meterRegistry = registry;
        
        // L1 Cache Metrics
        bindLocalCacheMetrics();
        
        // L2 Cache Metrics
        bindDistributedCacheMetrics();
        
        // Circuit Breaker Metrics
        bindCircuitBreakerMetrics();
        
        // System Performance Metrics
        bindSystemPerformanceMetrics();
    }

    /**
     * Bind L1 (Local) Cache Metrics
     */
    private void bindLocalCacheMetrics() {
        if (localCacheManager == null) {
            return;
        }

        // Cache Hit/Miss Counter
        Counter.builder("hcc_cache_requests_total")
                .description("Total number of cache requests (hit + miss)")
                .tag("cache_level", "L1")
                .tag("type", "requests")
                .register(meterRegistry);

        // Hit Count Gauge
        Gauge.builder("hcc_cache_hits_total", localCacheManager, 
                        manager -> manager.getStats().getHitCount())
                .description("Total number of cache hits")
                .tag("cache_level", "L1")
                .baseUnit("hits")
                .register(meterRegistry);

        // Miss Count Gauge
        Gauge.builder("hcc_cache_misses_total", localCacheManager,
                        manager -> manager.getStats().getMissCount())
                .description("Total number of cache misses")
                .tag("cache_level", "L1")
                .baseUnit("misses")
                .register(meterRegistry);

        // Hit Rate Gauge
        Gauge.builder("hcc_cache_hit_ratio", localCacheManager,
                        manager -> manager.getStats().getHitRate())
                .description("Cache hit rate (0.0-1.0)")
                .tag("cache_level", "L1")
                .baseUnit("ratio")
                .register(meterRegistry);

        // Cache Size Gauge
        Gauge.builder("hcc_cache_size", localCacheManager,
                        manager -> manager.getStats().getSize())
                .description("Current number of entries in cache")
                .tag("cache_level", "L1")
                .baseUnit("entries")
                .register(meterRegistry);

        // Max Size Gauge
        Gauge.builder("hcc_cache_max_size", localCacheManager,
                        manager -> manager.getStats().getMaxSize())
                .description("Maximum configured cache size")
                .tag("cache_level", "L1")
                .baseUnit("entries")
                .register(meterRegistry);

        // Eviction Count Gauge
        Gauge.builder("hcc_cache_evictions_total", localCacheManager,
                        manager -> manager.getStats().getEvictionCount())
                .description("Total number of cache evictions")
                .tag("cache_level", "L1")
                .baseUnit("evictions")
                .register(meterRegistry);
    }

    /**
     * Bind L2 (Distributed) Cache Metrics
     */
    private void bindDistributedCacheMetrics() {
        if (distributedCacheManager == null) {
            return;
        }

        // Redis Connection Status
        Gauge.builder("hcc_distributed_cache_connected", distributedCacheManager,
                        manager -> distributedCacheManager.isHealthy() ? 1 : 0)
                .description("Redis connection status (1=connected, 0=disconnected)")
                .tag("cache_level", "L2")
                .baseUnit("status")
                .register(meterRegistry);

        // L2 Cache Operations Counter
        Counter.builder("hcc_distributed_cache_operations_total")
                .description("Total number of distributed cache operations")
                .tag("cache_level", "L2")
                .tag("type", "operations")
                .register(meterRegistry);
    }

    /**
     * Bind Circuit Breaker Metrics
     */
    private void bindCircuitBreakerMetrics() {
        if (circuitBreaker == null) {
            return;
        }

        // Circuit State Gauge (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
        Gauge.builder("hcc_circuit_breaker_state", circuitBreaker,
                        breaker -> {
                            String state = breaker.getStats().getState()+"";
                            switch (state) {
                                case "CLOSED": return 0.0;
                                case "OPEN": return 1.0;
                                case "HALF_OPEN": return 2.0;
                                default: return 0.0;
                            }
                        })
                .description("Circuit breaker state (0=CLOSED, 1=OPEN, 2=HALF_OPEN)")
                .tag("component", "circuitbreaker")
                .baseUnit("state")
                .register(meterRegistry);

        // Failure Count Gauge
        Gauge.builder("hcc_circuit_breaker_failures_total", circuitBreaker,
                        breaker -> breaker.getStats().getFailureCount())
                .description("Current failure count")
                .tag("component", "circuitbreaker")
                .baseUnit("failures")
                .register(meterRegistry);

        // Success Count Gauge
        Gauge.builder("hcc_circuit_breaker_successes_total", circuitBreaker,
                        breaker -> breaker.getStats().getSuccessCount())
                .description("Current success count")
                .tag("component", "circuitbreaker")
                .baseUnit("successes")
                .register(meterRegistry);

        // Rejected Calls Counter
        Counter.builder("hcc_circuit_breaker_rejected_total")
                .description("Total number of calls rejected when circuit is OPEN")
                .tag("component", "circuitbreaker")
                .register(meterRegistry);
    }

    /**
     * Bind System Performance Metrics
     */
    private void bindSystemPerformanceMetrics() {
        // Hotspot Detection Metrics
        if (readHotspotDetector!=null) {
            // Read Hotspot Count
            Gauge.builder("hcc_hotspot_read_hotkeys_count", readHotspotDetector,
                            readKey-> readKey.readHotKeyCount() )
                    .description("Number of read hotspot keys detected")
                    .tag("hotspot_type", "read")
                    .baseUnit("keys")
                    .register(meterRegistry);
        }
        if (writeHotspotDetector!=null) {
            // Write Hotspot Count
            Gauge.builder("hcc_hotspot_write_hotkeys_count", writeHotspotDetector,
                            writeKey->writeKey.writeHotKeyCount())
                    .description("Number of write hotspot keys detected")
                    .tag("hotspot_type", "write")
                    .baseUnit("keys")
                    .register(meterRegistry);
        }
    }

}
