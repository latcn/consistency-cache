package io.github.latcn.cache.core.monitor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.latcn.cache.core.distributed.DistributedCacheManager;
import io.github.latcn.cache.core.local.LocalCache;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.github.latcn.cache.core.model.CacheValue;
import io.github.latcn.cache.core.model.ConsistencyLevel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@DisplayName("EnhancedConnectionMonitor Tests")
class EnhancedConnectionMonitorTest {

	@Mock
	private DistributedCacheManager distributedCacheManager;

	@Mock
	private LocalCacheManager localCacheManager;

	@Mock
	private LocalCache highCache;

	@Mock
	private LocalCache availableCache;

	private EnhancedConnectionMonitor monitor;

	private MeterRegistry meterRegistry;

	private AutoCloseable closeable;

	@BeforeEach
	void setUp() {
		closeable = MockitoAnnotations.openMocks(this);
		meterRegistry = new SimpleMeterRegistry();
		ConcurrentHashMap<ConsistencyLevel, LocalCache<Object, CacheValue>> cacheLevelMap = new ConcurrentHashMap<>();
		cacheLevelMap.put(ConsistencyLevel.HIGH, highCache);
		cacheLevelMap.put(ConsistencyLevel.AVAILABLE, availableCache);
		when(localCacheManager.getCacheLevelMap()).thenReturn(cacheLevelMap);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (monitor != null) {
			monitor.shutdown();
		}
		closeable.close();
	}

	@Test
	@DisplayName("Should initialize with default check interval")
	void testDefaultConstructor() {
		when(distributedCacheManager.isHealthy()).thenReturn(true);
		monitor = new EnhancedConnectionMonitor(distributedCacheManager, localCacheManager);
		assertEquals(3, monitor.getCheckIntervalSeconds());
		assertTrue(monitor.isWasConnected());
	}

	@Test
	@DisplayName("Should initialize with custom check interval")
	void testCustomConstructor() {
		when(distributedCacheManager.isHealthy()).thenReturn(true);
		monitor = new EnhancedConnectionMonitor(distributedCacheManager, localCacheManager, 10);
		assertEquals(10, monitor.getCheckIntervalSeconds());
		assertTrue(monitor.isWasConnected());
	}

	@Test
	@DisplayName("Should initialize with MeterRegistry")
	void testConstructorWithMeterRegistry() {
		when(distributedCacheManager.isHealthy()).thenReturn(true);
		monitor = new EnhancedConnectionMonitor(distributedCacheManager, localCacheManager, 3, meterRegistry);

		assertNotNull(meterRegistry.find("hcc_connection_status").gauge());
		assertNotNull(meterRegistry.find("hcc_connection_disconnections_total").counter());
		assertNotNull(meterRegistry.find("hcc_connection_reconnections_total").counter());
	}

	@Test
	@DisplayName("Should return connected status when distributed cache is healthy")
	void testIsConnectedHealthy() {
		when(distributedCacheManager.isHealthy()).thenReturn(true);
		monitor = new EnhancedConnectionMonitor(distributedCacheManager, localCacheManager);
		assertTrue(monitor.isConnected());
	}

	@Test
	@DisplayName("Should return disconnected status when distributed cache is unhealthy")
	void testIsConnectedUnhealthy() {
		when(distributedCacheManager.isHealthy()).thenReturn(false);
		monitor = new EnhancedConnectionMonitor(distributedCacheManager, localCacheManager);
		assertFalse(monitor.isConnected());
	}

	@Test
	@DisplayName("Should return disconnected when health check throws exception")
	void testIsConnectedException() {
		when(distributedCacheManager.isHealthy()).thenThrow(new RuntimeException("Connection failed"));
		monitor = new EnhancedConnectionMonitor(distributedCacheManager, localCacheManager);
		assertFalse(monitor.isConnected());
	}

	@Test
	@DisplayName("Should clear HIGH consistency cache on disconnection")
	void testHandleDisconnectionHighConsistency() {
		when(distributedCacheManager.isHealthy()).thenReturn(true);
		monitor = new EnhancedConnectionMonitor(distributedCacheManager, localCacheManager, 100);

		when(distributedCacheManager.isHealthy()).thenReturn(false);
		monitor.checkConnection();

		verify(highCache, atLeastOnce()).invalidateAll();
		verify(availableCache, never()).invalidateAll();
	}

	@Test
	@DisplayName("Should clear HIGH consistency cache on reconnection")
	void testHandleReconnectionHighConsistency() {
		when(distributedCacheManager.isHealthy()).thenReturn(true);
		monitor = new EnhancedConnectionMonitor(distributedCacheManager, localCacheManager, 100);

		when(distributedCacheManager.isHealthy()).thenReturn(false);
		monitor.checkConnection();

		when(distributedCacheManager.isHealthy()).thenReturn(true);
		monitor.checkConnection();

		verify(highCache, atLeast(2)).invalidateAll();
	}

	@Test
	@DisplayName("Should handle exception during connection check gracefully")
	void testCheckConnectionException() {
		when(distributedCacheManager.isHealthy()).thenReturn(true);
		monitor = new EnhancedConnectionMonitor(distributedCacheManager, localCacheManager, 100);

		when(distributedCacheManager.isHealthy()).thenThrow(new RuntimeException("Check failed"));
		monitor.checkConnection();

		assertFalse(monitor.isWasConnected());

		verify(highCache, atLeastOnce()).invalidateAll();
	}

	@Test
	@DisplayName("Should record disconnection metrics")
	void testDisconnectionMetrics() {
		when(distributedCacheManager.isHealthy()).thenReturn(true);
		monitor = new EnhancedConnectionMonitor(distributedCacheManager, localCacheManager, 100, meterRegistry);

		when(distributedCacheManager.isHealthy()).thenReturn(false);
		monitor.checkConnection();

		assertEquals(0.0, meterRegistry.find("hcc_connection_status").gauge().value(), 0.001);
		assertEquals(1.0, meterRegistry.find("hcc_connection_disconnections_total").counter().count(), 0.001);
	}

	@Test
	@DisplayName("Should record reconnection metrics")
	void testReconnectionMetrics() {
		when(distributedCacheManager.isHealthy()).thenReturn(true);
		monitor = new EnhancedConnectionMonitor(distributedCacheManager, localCacheManager, 100, meterRegistry);

		when(distributedCacheManager.isHealthy()).thenReturn(false);
		monitor.checkConnection();

		when(distributedCacheManager.isHealthy()).thenReturn(true);
		monitor.checkConnection();

		assertEquals(1.0, meterRegistry.find("hcc_connection_status").gauge().value(), 0.001);
		assertEquals(1.0, meterRegistry.find("hcc_connection_reconnections_total").counter().count(), 0.001);
	}

	@Test
	@DisplayName("Should shutdown gracefully")
	void testShutdown() {
		when(distributedCacheManager.isHealthy()).thenReturn(true);
		monitor = new EnhancedConnectionMonitor(distributedCacheManager, localCacheManager);

		assertDoesNotThrow(() -> monitor.shutdown());
	}

	@Test
	@DisplayName("Should handle null distributed cache manager")
	void testNullDistributedCacheManager() {
		assertThrows(NullPointerException.class, () -> new EnhancedConnectionMonitor(null, localCacheManager));
	}

	@Test
	@DisplayName("Should handle null local cache manager")
	void testNullLocalCacheManager() {
		assertThrows(NullPointerException.class, () -> new EnhancedConnectionMonitor(distributedCacheManager, null));
	}

}