package io.github.latcn.cache.spring.monitor;

import io.github.latcn.cache.core.circuitbreaker.CacheCircuitBreaker;
import io.github.latcn.cache.core.distributed.DistributedCacheManager;
import io.github.latcn.cache.core.hotspot.reads.ReadHotspotDetector;
import io.github.latcn.cache.core.hotspot.writes.WriteHotspotDetector;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.github.latcn.cache.core.manager.SingleFlightExecutor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class CacheMetricsBinder implements MeterBinder {

    private final LocalCacheManager localCacheManager;
    private final DistributedCacheManager distributedCacheManager;
    private final CacheCircuitBreaker circuitBreaker;
    private final ReadHotspotDetector readHotspotDetector;
    private final WriteHotspotDetector writeHotspotDetector;
    private final SingleFlightExecutor singleFlightExecutor;

    private MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Timer> getDurationTimers = new ConcurrentHashMap<>();
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);
    private final AtomicLong invalidationSuccessCount = new AtomicLong(0);
    private final AtomicLong invalidationFailureCount = new AtomicLong(0);

    public CacheMetricsBinder(LocalCacheManager localCacheManager,
                              DistributedCacheManager distributedCacheManager,
                              CacheCircuitBreaker circuitBreaker,
                              ReadHotspotDetector readHotspotDetector,
                              WriteHotspotDetector writeHotspotDetector) {
        this(localCacheManager, distributedCacheManager, circuitBreaker,
             readHotspotDetector, writeHotspotDetector, null);
    }

    public CacheMetricsBinder(LocalCacheManager localCacheManager,
                              DistributedCacheManager distributedCacheManager,
                              CacheCircuitBreaker circuitBreaker,
                              ReadHotspotDetector readHotspotDetector,
                              WriteHotspotDetector writeHotspotDetector,
                              SingleFlightExecutor singleFlightExecutor) {
        this.localCacheManager = localCacheManager;
        this.distributedCacheManager = distributedCacheManager;
        this.circuitBreaker = circuitBreaker;
        this.readHotspotDetector = readHotspotDetector;
        this.writeHotspotDetector = writeHotspotDetector;
        this.singleFlightExecutor = singleFlightExecutor;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        this.meterRegistry = registry;

        bindLocalCacheMetrics();
        bindDistributedCacheMetrics();
        bindCircuitBreakerMetrics();
        bindSystemPerformanceMetrics();
        bindInvalidationMetrics();
        bindSingleFlightMetrics();
    }

    private void bindLocalCacheMetrics() {
        if (localCacheManager == null) {
            return;
        }

        Counter.builder("hcc_cache_requests_total")
                .description("Total number of cache requests (hit + miss)")
                .tag("cache_level", "L1")
                .tag("type", "requests")
                .register(meterRegistry);

        Gauge.builder("hcc_cache_hits_total", localCacheManager,
                        manager -> manager.getStats().getHitCount())
                .description("Total number of cache hits")
                .tag("cache_level", "L1")
                .baseUnit("hits")
                .register(meterRegistry);

        Gauge.builder("hcc_cache_misses_total", localCacheManager,
                        manager -> manager.getStats().getMissCount())
                .description("Total number of cache misses")
                .tag("cache_level", "L1")
                .baseUnit("misses")
                .register(meterRegistry);

        Gauge.builder("hcc_cache_hit_ratio", localCacheManager,
                        manager -> manager.getStats().getHitRate())
                .description("Cache hit rate (0.0-1.0)")
                .tag("cache_level", "L1")
                .baseUnit("ratio")
                .register(meterRegistry);

        Gauge.builder("hcc_cache_size", localCacheManager,
                        manager -> manager.getStats().getSize())
                .description("Current number of entries in cache")
                .tag("cache_level", "L1")
                .baseUnit("entries")
                .register(meterRegistry);

        Gauge.builder("hcc_cache_max_size", localCacheManager,
                        manager -> manager.getStats().getMaxSize())
                .description("Maximum configured cache size")
                .tag("cache_level", "L1")
                .baseUnit("entries")
                .register(meterRegistry);

        Gauge.builder("hcc_cache_evictions_total", localCacheManager,
                        manager -> manager.getStats().getEvictionCount())
                .description("Total number of cache evictions")
                .tag("cache_level", "L1")
                .baseUnit("evictions")
                .register(meterRegistry);
    }

    private void bindDistributedCacheMetrics() {
        if (distributedCacheManager == null) {
            return;
        }

        Gauge.builder("hcc_distributed_cache_connected", distributedCacheManager,
                        manager -> distributedCacheManager.isHealthy() ? 1 : 0)
                .description("Redis connection status (1=connected, 0=disconnected)")
                .tag("cache_level", "L2")
                .baseUnit("status")
                .register(meterRegistry);

        Counter.builder("hcc_distributed_cache_operations_total")
                .description("Total number of distributed cache operations")
                .tag("cache_level", "L2")
                .tag("type", "operations")
                .register(meterRegistry);
    }

    private void bindCircuitBreakerMetrics() {
        if (circuitBreaker == null) {
            return;
        }

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

        Gauge.builder("hcc_circuit_breaker_failures_total", circuitBreaker,
                        breaker -> breaker.getStats().getFailureCount())
                .description("Current failure count")
                .tag("component", "circuitbreaker")
                .baseUnit("failures")
                .register(meterRegistry);

        Gauge.builder("hcc_circuit_breaker_successes_total", circuitBreaker,
                        breaker -> breaker.getStats().getSuccessCount())
                .description("Current success count")
                .tag("component", "circuitbreaker")
                .baseUnit("successes")
                .register(meterRegistry);

        Counter.builder("hcc_circuit_breaker_rejected_total")
                .description("Total number of calls rejected when circuit is OPEN")
                .tag("component", "circuitbreaker")
                .register(meterRegistry);
    }

    private void bindSystemPerformanceMetrics() {
        if (readHotspotDetector != null) {
            Gauge.builder("hcc_hotspot_read_hotkeys_count", readHotspotDetector,
                            readKey -> readKey.readHotKeyCount())
                    .description("Number of read hotspot keys detected")
                    .tag("hotspot_type", "read")
                    .tag("type", "read")
                    .baseUnit("keys")
                    .register(meterRegistry);
        }
        if (writeHotspotDetector != null) {
            Gauge.builder("hcc_hotspot_write_hotkeys_count", writeHotspotDetector,
                            writeKey -> writeKey.writeHotKeyCount())
                    .description("Number of write hotspot keys detected")
                    .tag("hotspot_type", "write")
                    .tag("type", "write")
                    .baseUnit("keys")
                    .register(meterRegistry);
        }
    }

    private void bindInvalidationMetrics() {
        Counter.builder("cache_invalidation_publish_total")
                .description("Total number of invalidation messages published")
                .tag("result", "success")
                .register(meterRegistry);

        Counter.builder("cache_invalidation_publish_total")
                .description("Total number of invalidation messages published")
                .tag("result", "failure")
                .register(meterRegistry);

        Timer.builder("cache_invalidation_delay")
                .description("Delay in invalidation message publishing")
                .register(meterRegistry);
    }

    private void bindSingleFlightMetrics() {
        if (singleFlightExecutor == null) {
            return;
        }

        Counter.builder("singleflight_requests_total")
                .description("Total number of merged requests")
                .register(meterRegistry);

        Gauge.builder("singleflight_inflight_requests", singleFlightExecutor,
                        executor -> executor.getInflightCount())
                .description("Number of requests currently being merged")
                .baseUnit("requests")
                .register(meterRegistry);
    }

    public void recordCacheGet(String cacheLevel, String result, long durationMs) {
        String key = cacheLevel + "_" + result;
        getDurationTimers.computeIfAbsent(key, k ->
            Timer.builder("cache_get_duration")
                .tag("cache_level", cacheLevel)
                .tag("result", result)
                .register(meterRegistry)
        ).record(durationMs, TimeUnit.MILLISECONDS);

        if ("hit".equals(result)) {
            hitCount.incrementAndGet();
        } else if ("miss".equals(result)) {
            missCount.incrementAndGet();
        }
    }

    public void recordInvalidationPublish(boolean success) {
        if (success) {
            invalidationSuccessCount.incrementAndGet();
        } else {
            invalidationFailureCount.incrementAndGet();
        }
    }

    public double calculateHitRatio() {
        long total = hitCount.get() + missCount.get();
        return total > 0 ? (double) hitCount.get() / total : 0.0;
    }
}