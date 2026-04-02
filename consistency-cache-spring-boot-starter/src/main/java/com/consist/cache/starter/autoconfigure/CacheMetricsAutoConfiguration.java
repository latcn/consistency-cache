package com.consist.cache.starter.autoconfigure;

import com.consist.cache.core.circuitbreaker.CacheCircuitBreaker;
import com.consist.cache.core.distributed.DistributedCacheManager;
import com.consist.cache.core.hotspot.reads.ReadHotspotDetector;
import com.consist.cache.core.hotspot.writes.WriteHotspotDetector;
import com.consist.cache.core.local.LocalCacheManager;
import com.consist.cache.spring.monitor.CacheMetricsManager;
import com.consist.cache.spring.monitor.PrometheusCacheMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto Configuration for Micrometer metrics and Prometheus monitoring
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(name = {"io.micrometer.core.instrument.MeterRegistry", "io.micrometer.prometheus.PrometheusMeterRegistry"})
public class CacheMetricsAutoConfiguration {

    /**
     * Create Prometheus metrics for cache monitoring
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "spring.hcc.cache.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
    public PrometheusCacheMetrics prometheusCacheMetrics(LocalCacheManager localCacheManager,
                                                         DistributedCacheManager distributedCacheManager,
                                                         CacheCircuitBreaker circuitBreaker,
                                                         ReadHotspotDetector readHotspotDetector,
                                                         WriteHotspotDetector writeHotspotDetector) {
        log.info("Initializing Prometheus Cache Metrics...");
        return new PrometheusCacheMetrics(
            localCacheManager,
            distributedCacheManager,
            circuitBreaker,
            readHotspotDetector,
            writeHotspotDetector
        );
    }

    /**
     * Expose MeterRegistry for other components to use
     */
    @Bean
    @ConditionalOnMissingBean
    public MeterRegistry meterRegistry(PrometheusCacheMetrics prometheusCacheMetrics) {
        return prometheusCacheMetrics.getCacheMetricsManager().getMeterRegistry();
    }

    /**
     * Optional: Expose CacheMetricsManager as a bean for direct usage
     */
    @Bean
    @ConditionalOnMissingBean
    public CacheMetricsManager cacheMetricsManager(PrometheusCacheMetrics prometheusCacheMetrics) {
        return prometheusCacheMetrics.getCacheMetricsManager();
    }
}
