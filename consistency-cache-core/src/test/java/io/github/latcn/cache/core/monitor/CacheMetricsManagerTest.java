package io.github.latcn.cache.core.monitor;

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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@DisplayName("CacheMetricsManager Tests")
class CacheMetricsManagerTest {

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

	private CacheMetricsManager metricsManager;

	private MeterRegistry meterRegistry;

	private AutoCloseable closeable;

	@BeforeEach
	void setUp() {
		closeable = MockitoAnnotations.openMocks(this);
		meterRegistry = new SimpleMeterRegistry();

		when(localCacheManager.getStats()).thenReturn(localCacheStats);
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
	}

	@AfterEach
	void tearDown() throws Exception {
		closeable.close();
	}

	@Test
	@DisplayName("Should create CacheMetricsManager successfully")
	void testCreation() {
		metricsManager = new CacheMetricsManager(meterRegistry, localCacheManager, distributedCacheManager,
				circuitBreaker, readHotspotDetector, writeHotspotDetector);

		assertNotNull(metricsManager);
		assertNotNull(metricsManager.getMeterRegistry());
		assertNotNull(metricsManager.getMetricsBinder());
	}

	@Test
	@DisplayName("Should get MeterRegistry")
	void testGetMeterRegistry() {
		metricsManager = new CacheMetricsManager(meterRegistry, localCacheManager, distributedCacheManager,
				circuitBreaker, readHotspotDetector, writeHotspotDetector);

		MeterRegistry registry = metricsManager.getMeterRegistry();

		assertNotNull(registry);
		assertSame(meterRegistry, registry);
	}

	@Test
	@DisplayName("Should get CacheMetricsBinder")
	void testGetMetricsBinder() {
		metricsManager = new CacheMetricsManager(meterRegistry, localCacheManager, distributedCacheManager,
				circuitBreaker, readHotspotDetector, writeHotspotDetector);

		assertNotNull(metricsManager.getMetricsBinder());
	}

	@Test
	@DisplayName("Should bind all cache metrics")
	void testMetricsBound() {
		metricsManager = new CacheMetricsManager(meterRegistry, localCacheManager, distributedCacheManager,
				circuitBreaker, readHotspotDetector, writeHotspotDetector);

		assertNotNull(meterRegistry.find("hcc_cache_hit_ratio").gauge());
		assertNotNull(meterRegistry.find("hcc_cache_size").gauge());
		assertNotNull(meterRegistry.find("hcc_cache_max_size").gauge());
		assertNotNull(meterRegistry.find("hcc_cache_evictions_total").gauge());
		assertNotNull(meterRegistry.find("hcc_distributed_cache_connected").gauge());
		assertNotNull(meterRegistry.find("hcc_circuit_breaker_state").gauge());
	}

	@Test
	@DisplayName("Should get cache size")
	void testGetCacheSize() {
		metricsManager = new CacheMetricsManager(meterRegistry, localCacheManager, distributedCacheManager,
				circuitBreaker, readHotspotDetector, writeHotspotDetector);

		long cacheSize = metricsManager.getCacheSize();

		assertNotNull(cacheSize);
	}

	@Test
	@DisplayName("Should handle null LocalCacheManager gracefully")
	void testNullLocalCacheManager() {
		assertDoesNotThrow(() -> new CacheMetricsManager(meterRegistry, null, distributedCacheManager, circuitBreaker,
				readHotspotDetector, writeHotspotDetector));

		assertNull(meterRegistry.find("hcc_cache_hit_ratio").gauge());
	}

	@Test
	@DisplayName("Should handle null DistributedCacheManager gracefully")
	void testNullDistributedCacheManager() {
		assertDoesNotThrow(() -> new CacheMetricsManager(meterRegistry, localCacheManager, null, circuitBreaker,
				readHotspotDetector, writeHotspotDetector));

		assertNull(meterRegistry.find("hcc_distributed_cache_connected").gauge());
	}

}