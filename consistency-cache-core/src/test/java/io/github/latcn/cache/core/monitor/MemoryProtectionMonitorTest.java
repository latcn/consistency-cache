package io.github.latcn.cache.core.monitor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.latcn.cache.core.local.LocalCacheManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@DisplayName("MemoryProtectionMonitor Tests")
class MemoryProtectionMonitorTest {

	@Mock
	private LocalCacheManager localCacheManager;

	private MemoryProtectionMonitor monitor;

	private MeterRegistry meterRegistry;

	private AutoCloseable closeable;

	@BeforeEach
	void setUp() {
		closeable = MockitoAnnotations.openMocks(this);
		meterRegistry = new SimpleMeterRegistry();
	}

	@AfterEach
	void tearDown() throws Exception {
		if (monitor != null) {
			monitor.shutdown();
		}
		closeable.close();
	}

	@Test
	@DisplayName("Should initialize with default parameters")
	void testDefaultConstructor() {
		when(localCacheManager.getSize()).thenReturn(0L);
		monitor = new MemoryProtectionMonitor(localCacheManager, 1000, 0.8);
		
		assertEquals(1000, monitor.getMaxSize());
		assertEquals(0.8, monitor.getWarningThreshold(), 0.001);
		assertEquals(30, monitor.getCheckIntervalSeconds());
	}

	@Test
	@DisplayName("Should initialize with custom check interval")
	void testCustomConstructor() {
		when(localCacheManager.getSize()).thenReturn(0L);
		monitor = new MemoryProtectionMonitor(localCacheManager, 2000, 0.9, 15);
		
		assertEquals(2000, monitor.getMaxSize());
		assertEquals(0.9, monitor.getWarningThreshold(), 0.001);
		assertEquals(15, monitor.getCheckIntervalSeconds());
	}

	@Test
	@DisplayName("Should initialize with MeterRegistry")
	void testConstructorWithMeterRegistry() {
		when(localCacheManager.getSize()).thenReturn(500L);
		monitor = new MemoryProtectionMonitor(localCacheManager, 1000, 0.8, 30, meterRegistry);
		
		assertNotNull(meterRegistry.find("hcc_memory_usage_ratio").gauge());
		assertNotNull(meterRegistry.find("hcc_memory_max_size_bytes").gauge());
		assertNotNull(meterRegistry.find("hcc_memory_evictions_total").counter());
		assertNotNull(meterRegistry.find("hcc_memory_warnings_total").counter());
	}

	@Test
	@DisplayName("Should calculate usage ratio correctly")
	void testGetUsageRatio() {
		when(localCacheManager.getSize()).thenReturn(500L);
		monitor = new MemoryProtectionMonitor(localCacheManager, 1000, 0.8);
		
		assertEquals(0.5, monitor.getUsageRatio(), 0.001);
	}

	@Test
	@DisplayName("Should return correct formatted usage")
	void testGetFormattedUsage() {
		when(localCacheManager.getSize()).thenReturn(750L);
		monitor = new MemoryProtectionMonitor(localCacheManager, 1000, 0.8);
		
		assertEquals("75.0%", monitor.getFormattedUsage());
	}

	@Test
	@DisplayName("Should not trigger eviction when below warning threshold")
	@Timeout(10)
	void testNoEvictionBelowThreshold() throws InterruptedException {
		when(localCacheManager.getSize()).thenReturn(500L);
		monitor = new MemoryProtectionMonitor(localCacheManager, 1000, 0.8, 1);
		
		Thread.sleep(2000);
		
		verify(localCacheManager, never()).runEviction();
	}

	@Test
	@DisplayName("Should trigger eviction when above warning threshold")
	@Timeout(10)
	void testEvictionAboveThreshold() throws InterruptedException {
		when(localCacheManager.getSize()).thenReturn(850L);
		monitor = new MemoryProtectionMonitor(localCacheManager, 1000, 0.8, 1);
		
		Thread.sleep(2000);
		
		verify(localCacheManager, atLeastOnce()).runEviction();
	}

	@Test
	@DisplayName("Should trigger eviction when above warning threshold with metrics")
	@Timeout(10)
	void testEvictionAboveThresholdWithMetrics() throws InterruptedException {
		when(localCacheManager.getSize()).thenReturn(850L);
		monitor = new MemoryProtectionMonitor(localCacheManager, 1000, 0.8, 1, meterRegistry);
		
		Thread.sleep(2000);
		
		verify(localCacheManager, atLeastOnce()).runEviction();
		assertTrue(meterRegistry.find("hcc_memory_warnings_total").counter().count() >= 1);
		assertTrue(meterRegistry.find("hcc_memory_evictions_total").counter().count() >= 1);
	}

	@Test
	@DisplayName("Should handle exact warning threshold")
	@Timeout(10)
	void testEvictionAtThreshold() throws InterruptedException {
		when(localCacheManager.getSize()).thenReturn(800L);
		monitor = new MemoryProtectionMonitor(localCacheManager, 1000, 0.8, 1);
		
		Thread.sleep(2000);
		
		verify(localCacheManager, atLeastOnce()).runEviction();
	}

	@Test
	@DisplayName("Should handle zero max size gracefully")
	void testZeroMaxSize() {
		when(localCacheManager.getSize()).thenReturn(0L);
		monitor = new MemoryProtectionMonitor(localCacheManager, 0, 0.8);
		
		assertDoesNotThrow(() -> monitor.getUsageRatio());
	}

	@Test
	@DisplayName("Should handle negative cache size")
	void testNegativeCacheSize() {
		when(localCacheManager.getSize()).thenReturn(-100L);
		monitor = new MemoryProtectionMonitor(localCacheManager, 1000, 0.8);
		
		assertTrue(monitor.getUsageRatio() <= 0);
	}

	@Test
	@DisplayName("Should shutdown gracefully")
	void testShutdown() {
		when(localCacheManager.getSize()).thenReturn(0L);
		monitor = new MemoryProtectionMonitor(localCacheManager, 1000, 0.8);
		
		assertDoesNotThrow(() -> monitor.shutdown());
	}

	@Test
	@DisplayName("Should handle null local cache manager")
	void testNullLocalCacheManager() {
		assertThrows(NullPointerException.class,
				() -> new MemoryProtectionMonitor(null, 1000, 0.8));
	}

	@Test
	@DisplayName("Should handle negative warning threshold")
	void testNegativeThreshold() {
		when(localCacheManager.getSize()).thenReturn(500L);
		monitor = new MemoryProtectionMonitor(localCacheManager, 1000, -0.5);
		
		assertFalse(monitor.getUsageRatio() >= monitor.getWarningThreshold());
	}

	@Test
	@DisplayName("Should handle threshold greater than 1")
	void testThresholdGreaterThanOne() {
		when(localCacheManager.getSize()).thenReturn(1500L);
		monitor = new MemoryProtectionMonitor(localCacheManager, 1000, 1.5);
		
		assertFalse(monitor.getUsageRatio() >= monitor.getWarningThreshold());
	}

	@Test
	@DisplayName("Should record memory usage ratio in metrics")
	void testMemoryUsageRatioMetrics() {
		when(localCacheManager.getSize()).thenReturn(600L);
		monitor = new MemoryProtectionMonitor(localCacheManager, 1000, 0.8, 30, meterRegistry);
		
		assertEquals(0.6, meterRegistry.find("hcc_memory_usage_ratio").gauge().value(), 0.001);
		assertEquals(1000.0, meterRegistry.find("hcc_memory_max_size_bytes").gauge().value(), 0.001);
	}

}