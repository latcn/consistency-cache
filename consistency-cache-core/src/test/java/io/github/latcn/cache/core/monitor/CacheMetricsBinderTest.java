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

@DisplayName("CacheMetricsBinder Tests")
class CacheMetricsBinderTest {

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

	private CacheMetricsBinder binder;

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
	@DisplayName("Should bind all metrics successfully")
	void testBindTo() {
		binder = new CacheMetricsBinder(localCacheManager, distributedCacheManager, circuitBreaker, readHotspotDetector,
				writeHotspotDetector);

		binder.bindTo(meterRegistry);

		assertNotNull(meterRegistry.find("hcc_cache_hit_ratio").gauge());
		assertNotNull(meterRegistry.find("hcc_cache_size").gauge());
		assertNotNull(meterRegistry.find("hcc_cache_max_size").gauge());
		assertNotNull(meterRegistry.find("hcc_cache_evictions_total").gauge());
		assertNotNull(meterRegistry.find("hcc_distributed_cache_connected").gauge());
		assertNotNull(meterRegistry.find("hcc_circuit_breaker_state").gauge());
		assertNotNull(meterRegistry.find("hcc_hotspot_read_hotkeys_count").gauge());
		assertNotNull(meterRegistry.find("hcc_hotspot_write_hotkeys_count").gauge());
	}

	@Test
	@DisplayName("Should handle null LocalCacheManager")
	void testBindToNullLocalCacheManager() {
		binder = new CacheMetricsBinder(null, distributedCacheManager, circuitBreaker, readHotspotDetector,
				writeHotspotDetector);

		assertDoesNotThrow(() -> binder.bindTo(meterRegistry));

		assertNull(meterRegistry.find("hcc_cache_hit_ratio").gauge());
	}

	@Test
	@DisplayName("Should handle null DistributedCacheManager")
	void testBindToNullDistributedCacheManager() {
		binder = new CacheMetricsBinder(localCacheManager, null, circuitBreaker, readHotspotDetector,
				writeHotspotDetector);

		assertDoesNotThrow(() -> binder.bindTo(meterRegistry));

		assertNull(meterRegistry.find("hcc_distributed_cache_connected").gauge());
	}

	@Test
	@DisplayName("Should handle null CircuitBreaker")
	void testBindToNullCircuitBreaker() {
		binder = new CacheMetricsBinder(localCacheManager, distributedCacheManager, null, readHotspotDetector,
				writeHotspotDetector);

		assertDoesNotThrow(() -> binder.bindTo(meterRegistry));

		assertNull(meterRegistry.find("hcc_circuit_breaker_state").gauge());
	}

	@Test
	@DisplayName("Should handle null hotspot detectors")
	void testNullHotspotDetectors() {
		binder = new CacheMetricsBinder(localCacheManager, distributedCacheManager, circuitBreaker, null, null);

		assertDoesNotThrow(() -> binder.bindTo(meterRegistry));

		assertNull(meterRegistry.find("hcc_hotspot_read_hotkeys_count").gauge());
		assertNull(meterRegistry.find("hcc_hotspot_write_hotkeys_count").gauge());
	}

	@Test
	@DisplayName("Should handle bindTo errors gracefully")
	void testBindToWithErrors() {
		when(localCacheManager.getStats()).thenThrow(new RuntimeException("Simulated error"));

		binder = new CacheMetricsBinder(localCacheManager, distributedCacheManager, circuitBreaker, readHotspotDetector,
				writeHotspotDetector);

		assertDoesNotThrow(() -> binder.bindTo(meterRegistry));
	}

}