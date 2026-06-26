package io.github.latcn.cache.starter.autoconfigure;

import io.github.latcn.cache.core.circuitbreaker.CacheCircuitBreaker;
import io.github.latcn.cache.core.distributed.DistributedCacheManager;
import io.github.latcn.cache.core.hotspot.reads.ReadHotspotDetector;
import io.github.latcn.cache.core.hotspot.writes.WriteHotspotDetector;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.github.latcn.cache.core.model.HccProperties;
import io.github.latcn.cache.core.monitor.EnhancedConnectionMonitor;
import io.github.latcn.cache.core.monitor.MemoryProtectionMonitor;
import io.github.latcn.cache.spring.monitor.CacheMetricsManager;
import io.github.latcn.cache.spring.monitor.PrometheusCacheMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto Configuration for Micrometer metrics, Prometheus monitoring and cache monitors
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(
		name = { "io.micrometer.core.instrument.MeterRegistry", "io.micrometer.prometheus.PrometheusMeterRegistry" })
@ConditionalOnProperty(prefix = "spring.hcc.cache.monitor", name = "enabled", havingValue = "true",
		matchIfMissing = true)
public class CacheMetricsAutoConfiguration {

	/**
	 * Create Prometheus metrics for cache monitoring
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "spring.hcc.cache.monitor", name = "prometheus-enabled", havingValue = "true",
			matchIfMissing = true)
	public PrometheusCacheMetrics prometheusCacheMetrics(LocalCacheManager localCacheManager,
			DistributedCacheManager distributedCacheManager, CacheCircuitBreaker circuitBreaker,
			ReadHotspotDetector readHotspotDetector, WriteHotspotDetector writeHotspotDetector) {
		log.info("Initializing Prometheus Cache Metrics...");
		return new PrometheusCacheMetrics(localCacheManager, distributedCacheManager, circuitBreaker,
				readHotspotDetector, writeHotspotDetector);
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

	/**
	 * Enhanced Redis connection monitor with consistency-aware failure handling
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "spring.hcc.cache.monitor", name = "connection-monitor-enabled",
			havingValue = "true", matchIfMissing = true)
	@ConditionalOnBean({ DistributedCacheManager.class, LocalCacheManager.class })
	public EnhancedConnectionMonitor enhancedConnectionMonitor(DistributedCacheManager distributedCacheManager,
			LocalCacheManager localCacheManager, HccProperties properties) {
		log.info("Initializing EnhancedConnectionMonitor with check interval: {}s",
				properties.getMonitor().getConnectionCheckIntervalSeconds());
		return new EnhancedConnectionMonitor(distributedCacheManager, localCacheManager,
				properties.getMonitor().getConnectionCheckIntervalSeconds());
	}

	/**
	 * Memory protection monitor that proactively manages L1 cache memory usage
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "spring.hcc.cache.monitor", name = "memory-protection-enabled",
			havingValue = "true", matchIfMissing = true)
	@ConditionalOnBean(LocalCacheManager.class)
	public MemoryProtectionMonitor memoryProtectionMonitor(LocalCacheManager localCacheManager,
			HccProperties properties) {
		log.info("Initializing MemoryProtectionMonitor with maxSize: {}, warningThreshold: {}%, interval: {}s",
				properties.getLocal().getMaximumSize(),
				(int) (properties.getMonitor().getMemoryWarningThreshold() * 100),
				properties.getMonitor().getMemoryCheckIntervalSeconds());
		return new MemoryProtectionMonitor(localCacheManager, properties.getLocal().getMaximumSize(),
				properties.getMonitor().getMemoryWarningThreshold(),
				properties.getMonitor().getMemoryCheckIntervalSeconds());
	}

}
