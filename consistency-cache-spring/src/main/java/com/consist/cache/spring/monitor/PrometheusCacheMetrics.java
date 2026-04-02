package com.consist.cache.spring.monitor;

import com.consist.cache.core.circuitbreaker.CacheCircuitBreaker;
import com.consist.cache.core.distributed.DistributedCacheManager;
import com.consist.cache.core.hotspot.reads.ReadHotspotDetector;
import com.consist.cache.core.hotspot.writes.WriteHotspotDetector;
import com.consist.cache.core.local.LocalCacheManager;
import com.consist.cache.core.manager.SingleFlightExecutor;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.CollectorRegistry;

/**
 * Prometheus Meter Registry Configuration
 * Provides Prometheus-compatible metrics endpoint
 */
public class PrometheusCacheMetrics {

    private final PrometheusMeterRegistry prometheusMeterRegistry;
    private final CacheMetricsManager cacheMetricsManager;

    public PrometheusCacheMetrics(LocalCacheManager localCacheManager,
                                  DistributedCacheManager distributedCacheManager,
                                  CacheCircuitBreaker circuitBreaker,
                                  ReadHotspotDetector readHotspotDetector,
                                  WriteHotspotDetector writeHotspotDetector) {
        
        // Configure Prometheus registry
        this.prometheusMeterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        
        // Create metrics manager with Prometheus registry
        this.cacheMetricsManager = new CacheMetricsManager(
            prometheusMeterRegistry,
            localCacheManager,
            distributedCacheManager,
            circuitBreaker,
            readHotspotDetector,
            writeHotspotDetector
        );
        
        // Add JVM metrics for system performance monitoring
        io.micrometer.core.instrument.binder.jvm.JvmGcMetrics jvmGcMetrics = 
            new io.micrometer.core.instrument.binder.jvm.JvmGcMetrics();
        jvmGcMetrics.bindTo(prometheusMeterRegistry);
        
        io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics jvmMemoryMetrics = 
            new io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics();
        jvmMemoryMetrics.bindTo(prometheusMeterRegistry);
        
        io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics jvmThreadMetrics = 
            new io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics();
        jvmThreadMetrics.bindTo(prometheusMeterRegistry);
        
        io.micrometer.core.instrument.binder.system.ProcessorMetrics processorMetrics = 
            new io.micrometer.core.instrument.binder.system.ProcessorMetrics();
        processorMetrics.bindTo(prometheusMeterRegistry);
        
        io.micrometer.core.instrument.binder.system.UptimeMetrics uptimeMetrics = 
            new io.micrometer.core.instrument.binder.system.UptimeMetrics();
        uptimeMetrics.bindTo(prometheusMeterRegistry);
    }

    /**
     * Get Prometheus metrics in text format
     * This should be exposed at /actuator/prometheus or /metrics
     */
    public String getMetrics() {
        return prometheusMeterRegistry.scrape();
    }

    /**
     * Get the underlying CollectorRegistry for advanced usage
     */
    public CollectorRegistry getCollectorRegistry() {
        return prometheusMeterRegistry.getPrometheusRegistry();
    }

    /**
     * Get the Cache Metrics Manager for recording custom metrics
     */
    public CacheMetricsManager getCacheMetricsManager() {
        return cacheMetricsManager;
    }

    /**
     * Log current metrics summary
     */
    public void logMetricsSummary() {
        cacheMetricsManager.logMetricsSummary();
    }
}
