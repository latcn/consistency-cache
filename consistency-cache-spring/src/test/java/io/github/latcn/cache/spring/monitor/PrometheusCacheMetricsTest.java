package io.github.latcn.cache.spring.monitor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.latcn.cache.core.circuitbreaker.CacheCircuitBreaker;
import io.github.latcn.cache.core.circuitbreaker.CircuitBreakerState;
import io.github.latcn.cache.core.circuitbreaker.CircuitBreakerStats;
import io.github.latcn.cache.core.distributed.DistributedCacheManager;
import io.github.latcn.cache.core.hotspot.reads.ReadHotspotDetector;
import io.github.latcn.cache.core.hotspot.writes.WriteHotspotDetector;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.github.latcn.cache.core.local.LocalCacheStats;
import io.github.latcn.cache.core.monitor.CacheMetricsManager;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@DisplayName("PrometheusCacheMetrics Tests")
class PrometheusCacheMetricsTest {

	@Mock
	private LocalCacheManager localCacheManager;

	@Mock
	private DistributedCacheManager distributedCacheManager;

	@Mock
	private CacheCircuitBreaker circuitBreaker;

	@Mock
	private ReadHotspotDetector readHotspotDetector;

	@Mock
	private WriteHotspotDetector writeHotspotDetector;

	@Mock
	private LocalCacheStats localCacheStats;

	@Mock
	private CircuitBreakerStats circuitBreakerStats;

	private PrometheusCacheMetrics prometheusCacheMetrics;

	private AutoCloseable closeable;

	@BeforeEach
	void setUp() {
		closeable = MockitoAnnotations.openMocks(this);

		when(localCacheManager.getStats()).thenReturn(localCacheStats);
		when(localCacheStats.getHitCount()).thenReturn(100L);
		when(localCacheStats.getMissCount()).thenReturn(20L);
		when(localCacheStats.getHitRate()).thenReturn(0.833);
		when(localCacheStats.getSize()).thenReturn(500L);
		when(localCacheStats.getMaxSize()).thenReturn(1000L);
		when(localCacheStats.getEvictionCount()).thenReturn(50L);

		when(distributedCacheManager.isHealthy()).thenReturn(true);

		when(circuitBreaker.getStats()).thenReturn(circuitBreakerStats);
		when(circuitBreakerStats.getState()).thenReturn(CircuitBreakerState.CLOSED);
		when(circuitBreakerStats.getFailureCount()).thenReturn(0L);
		when(circuitBreakerStats.getSuccessCount()).thenReturn(10L);

		when(readHotspotDetector.readHotKeyCount()).thenReturn(5L);
		when(writeHotspotDetector.writeHotKeyCount()).thenReturn(2L);

		prometheusCacheMetrics = new PrometheusCacheMetrics(localCacheManager, distributedCacheManager, circuitBreaker,
				readHotspotDetector, writeHotspotDetector);
	}

	@AfterEach
	void tearDown() throws Exception {
		closeable.close();
	}

	@Test
	@DisplayName("Should create PrometheusCacheMetrics successfully")
	void testCreation() {
		assertNotNull(prometheusCacheMetrics);
		assertNotNull(prometheusCacheMetrics.getCacheMetricsManager());
		assertNotNull(prometheusCacheMetrics.getCollectorRegistry());
	}

	@Test
	@DisplayName("Should get metrics in text format")
	void testGetMetrics() {
		String metrics = prometheusCacheMetrics.getMetrics();

		assertNotNull(metrics);
		assertTrue(metrics.length() > 0);
		assertTrue(metrics.contains("hcc_cache_hit_ratio"));
		assertTrue(metrics.contains("hcc_cache_size"));
	}

	@Test
	@DisplayName("Should get CacheMetricsManager")
	void testGetCacheMetricsManager() {
		CacheMetricsManager manager = prometheusCacheMetrics.getCacheMetricsManager();

		assertNotNull(manager);
		assertNotNull(manager.getMeterRegistry());
	}

	@Test
	@DisplayName("Should get CollectorRegistry")
	void testGetCollectorRegistry() {
		io.prometheus.client.CollectorRegistry registry = prometheusCacheMetrics.getCollectorRegistry();

		assertNotNull(registry);
	}

	@Test
	@DisplayName("Should expose MeterRegistry through CacheMetricsManager")
	void testGetMeterRegistry() {
		MeterRegistry meterRegistry = prometheusCacheMetrics.getCacheMetricsManager().getMeterRegistry();

		assertNotNull(meterRegistry);
		assertNotNull(meterRegistry.find("hcc_cache_hit_ratio").gauge());
	}

	@Test
	@DisplayName("Should include JVM metrics")
	void testJvmMetricsIncluded() {
		String metrics = prometheusCacheMetrics.getMetrics();

		assertTrue(metrics.contains("jvm_memory_used_bytes"));
		assertTrue(metrics.contains("jvm_threads_live"));
		assertTrue(metrics.contains("process_cpu_usage"));
	}

	@Test
	@DisplayName("Should include circuit breaker metrics")
	void testCircuitBreakerMetricsIncluded() {
		String metrics = prometheusCacheMetrics.getMetrics();

		assertTrue(metrics.contains("hcc_circuit_breaker_state"));
		assertTrue(metrics.contains("hcc_circuit_breaker_failures_total"));
	}

	@Test
	@DisplayName("Should include hotspot metrics")
	void testHotspotMetricsIncluded() {
		String metrics = prometheusCacheMetrics.getMetrics();

		assertTrue(metrics.contains("hcc_hotspot_read_hotkeys_count"));
		assertTrue(metrics.contains("hcc_hotspot_write_hotkeys_count"));
	}

}