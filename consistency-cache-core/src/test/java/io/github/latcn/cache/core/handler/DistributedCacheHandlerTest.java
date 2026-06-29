package io.github.latcn.cache.core.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.latcn.cache.core.circuitbreaker.CacheCircuitBreaker;
import io.github.latcn.cache.core.distributed.DistributedCacheManager;
import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import io.github.latcn.cache.core.executor.CacheBloomFilter;
import io.github.latcn.cache.core.executor.CacheExecutorConfig;
import io.github.latcn.cache.core.hotspot.HotspotDetector;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.github.latcn.cache.core.local.LocalCacheMarkerManager;
import io.github.latcn.cache.core.model.CacheKey;
import io.github.latcn.cache.core.model.CacheLevel;
import io.github.latcn.cache.core.model.CacheValue;
import io.github.latcn.cache.core.model.ConsistencyLevel;
import io.github.latcn.cache.core.monitor.CacheMetricsRecorder;
import io.github.latcn.cache.core.pubsub.Broadcaster;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@DisplayName("分布式缓存Handler测试")
class DistributedCacheHandlerTest {

	@Mock
	private LocalCacheManager localCacheManager;

	@Mock
	private DistributedCacheManager distributedCacheManager;

	@Mock
	private LocalCacheMarkerManager localCacheMarkerManager;

	@Mock
	private HotspotDetector writeHotspotDetector;

	@Mock
	private HotspotDetector readHotspotDetector;

	@Mock
	private CacheCircuitBreaker circuitBreaker;

	@Mock
	private CacheBloomFilter bloomFilter;

	@Mock
	private Broadcaster broadcaster;

	@Mock
	private CacheHandler nextHandler;

	private CacheExecutorConfig config;

	private DistributedCacheHandler distributedCacheHandler;

	@BeforeEach
	void setUp(TestInfo testInfo) {
		MockitoAnnotations.openMocks(this);

		config = CacheExecutorConfig.builder()
			.localCacheManager(localCacheManager)
			.distributedCacheManager(distributedCacheManager)
			.localCacheMarkerManager(localCacheMarkerManager)
			.writeHotspotDetector(writeHotspotDetector)
			.readStatistics(readHotspotDetector)
			.cacheCircuitBreaker(circuitBreaker)
			.cacheBloomFilter(bloomFilter)
			.build();

		distributedCacheHandler = new DistributedCacheHandler(nextHandler, config, broadcaster);

		System.out.println("执行测试: " + testInfo.getDisplayName());
	}

	@Test
	@DisplayName("DC-001: 获取分布式缓存值")
	void testGetDistributedCacheValue() {
		CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "test-key");
		CacheValue cacheValue = createCacheValue("distributed-value");
		CacheContext context = createCacheContext(cacheKey);

		when(circuitBreaker.execute(any())).thenAnswer(invocation -> {
			java.util.function.Supplier<?> supplier = invocation.getArgument(0);
			return supplier.get();
		});
		when(distributedCacheManager.get(cacheKey)).thenReturn(cacheValue);
		when(writeHotspotDetector.isHotKey(any())).thenReturn(false);
		when(readHotspotDetector.isHotKey(any())).thenReturn(false);

		CacheValue result = distributedCacheHandler.get(context);

		assertNotNull(result);
		assertEquals("distributed-value", result.getValue());
		verify(distributedCacheManager).get(cacheKey);
		verify(nextHandler, never()).get(any());
	}

	@Test
	@DisplayName("DC-002: 分布式缓存未命中从DB加载")
	void testGetDistributedCacheMissLoadFromDb() {
		CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "test-key");
		CacheValue dbValue = createCacheValue("db-value");
		CacheContext context = createCacheContext(cacheKey);

		when(circuitBreaker.execute(any())).thenAnswer(invocation -> {
			java.util.function.Supplier<?> supplier = invocation.getArgument(0);
			return supplier.get();
		});
		when(distributedCacheManager.get(cacheKey)).thenReturn(null);
		when(nextHandler.get(context)).thenReturn(dbValue);
		when(writeHotspotDetector.isHotKey(any())).thenReturn(false);
		when(readHotspotDetector.isHotKey(any())).thenReturn(false);

		CacheValue result = distributedCacheHandler.get(context);

		assertNotNull(result);
		assertEquals("db-value", result.getValue());
		verify(distributedCacheManager).get(cacheKey);
		verify(nextHandler).get(context);
	}

	@Test
	@DisplayName("DC-003: 读热点key回填本地缓存")
	void testReadHotKeyBackfillLocalCache() {
		CacheKey cacheKey = createCacheKeyWithBroadcast(CacheLevel.ADAPTIVE_CACHE, "hot-key");
		CacheValue cacheValue = createCacheValue("hot-value");
		CacheContext context = createCacheContext(cacheKey);

		when(circuitBreaker.execute(any())).thenAnswer(invocation -> {
			java.util.function.Supplier<?> supplier = invocation.getArgument(0);
			return supplier.get();
		});
		when(distributedCacheManager.get(cacheKey)).thenReturn(null);
		when(nextHandler.get(context)).thenReturn(cacheValue);
		when(writeHotspotDetector.isHotKey(any())).thenReturn(false);
		when(readHotspotDetector.isHotKey(any())).thenReturn(true);

		CacheValue result = distributedCacheHandler.get(context);

		assertNotNull(result);
		assertEquals("hot-value", result.getValue());
		verify(localCacheManager).put(cacheKey, cacheValue);
		verify(localCacheMarkerManager).markLocalCacheUsage(any(), anyLong());
	}

	@Test
	@DisplayName("DC-004: 写热点key不回填本地缓存")
	void testWriteHotKeyNoBackfillLocalCache() {
		CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "write-hot-key");
		CacheValue cacheValue = createCacheValue("write-hot-value");
		CacheContext context = createCacheContext(cacheKey);

		when(circuitBreaker.execute(any())).thenAnswer(invocation -> {
			java.util.function.Supplier<?> supplier = invocation.getArgument(0);
			return supplier.get();
		});
		when(distributedCacheManager.get(cacheKey)).thenReturn(cacheValue);
		when(writeHotspotDetector.isHotKey(any())).thenReturn(true);
		when(readHotspotDetector.isHotKey(any())).thenReturn(true);

		CacheValue result = distributedCacheHandler.get(context);

		assertNotNull(result);
		assertEquals("write-hot-value", result.getValue());
		verify(localCacheManager, never()).put(any(), any());
	}

	@Test
	@DisplayName("DC-005: L2_CACHE级别不回填本地缓存")
	void testL2CacheLevelNoBackfill() {
		CacheKey cacheKey = createCacheKey(CacheLevel.L2_CACHE, "l2-key");
		CacheValue cacheValue = createCacheValue("l2-value");
		CacheContext context = createCacheContext(cacheKey);

		when(circuitBreaker.execute(any())).thenAnswer(invocation -> {
			java.util.function.Supplier<?> supplier = invocation.getArgument(0);
			return supplier.get();
		});
		when(distributedCacheManager.get(cacheKey)).thenReturn(cacheValue);

		CacheValue result = distributedCacheHandler.get(context);

		assertNotNull(result);
		assertEquals("l2-value", result.getValue());
		verify(localCacheManager, never()).put(any(), any());
	}

	@Test
	@DisplayName("DC-006: 熔断器打开时跳过分布式缓存")
	void testCircuitBreakerOpenBypassDistributedCache() {
		CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "test-key");
		CacheValue dbValue = createCacheValue("db-value");
		CacheContext context = createCacheContext(cacheKey);

		when(circuitBreaker.execute(any())).thenThrow(new CacheException(CacheError.CIRCUIT_BREAKER_OPEN));
		when(nextHandler.get(context)).thenReturn(dbValue);

		CacheValue result = distributedCacheHandler.get(context);

		assertNotNull(result);
		assertEquals("db-value", result.getValue());
		verify(distributedCacheManager, never()).get(any());
		verify(nextHandler).get(context);
	}

	@Test
	@DisplayName("DC-007: 失效分布式缓存")
	void testEvictDistributedCache() {
		CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "test-key");
		CacheContext context = createCacheContext(cacheKey);

		distributedCacheHandler.evict(context);

		verify(distributedCacheManager).remove(cacheKey);
		verify(localCacheManager).remove(cacheKey);
		verify(writeHotspotDetector).record(any());
	}

	@Test
	@DisplayName("DC-008: 失效时广播本地缓存标记节点")
	void testEvictWithBroadcastMarkedNodes() {
		CacheKey cacheKey = createCacheKeyWithBroadcast(CacheLevel.ADAPTIVE_CACHE, "test-key");
		CacheContext context = createCacheContext(cacheKey);

		List<String> nodeIds = new ArrayList<>();
		nodeIds.add("node-1");
		nodeIds.add("node-2");
		when(localCacheMarkerManager.getActiveNodes(any())).thenReturn(nodeIds);

		distributedCacheHandler.evict(context);

		verify(distributedCacheManager).remove(cacheKey);
		verify(localCacheMarkerManager).removeLocalCacheUsage(any());
		verify(broadcaster).addKey(cacheKey);
	}

	@Test
	@DisplayName("DC-009: LOCAL_CACHE级别跳过分布式缓存")
	void testLocalCacheLevelSkipDistributedCache() {
		CacheKey cacheKey = createCacheKey(CacheLevel.LOCAL_CACHE, "local-key");
		CacheValue nextValue = createCacheValue("next-value");
		CacheContext context = createCacheContext(cacheKey);

		when(nextHandler.get(context)).thenReturn(nextValue);

		CacheValue result = distributedCacheHandler.get(context);

		assertNotNull(result);
		assertEquals("next-value", result.getValue());
		verify(distributedCacheManager, never()).get(any());
		verify(nextHandler).get(context);
	}

	private CacheKey createCacheKey(CacheLevel level, String key) {
		return CacheKey.builder()
			.key(key)
			.cacheLevel(level)
			.consistencyLevel(ConsistencyLevel.HIGH)
			.bloomFilterEnabled(false)
			.broadcastEnabled(false)
			.cacheNullValues(false)
			.ttlMs(60000)
			.build();
	}

	private CacheKey createCacheKeyWithBroadcast(CacheLevel level, String key) {
		return CacheKey.builder()
			.key(key)
			.cacheLevel(level)
			.consistencyLevel(ConsistencyLevel.HIGH)
			.bloomFilterEnabled(false)
			.broadcastEnabled(true)
			.cacheNullValues(false)
			.ttlMs(60000)
			.build();
	}

	private CacheValue createCacheValue(Object value) {
		return CacheValue.builder()
			.value(value)
			.createdAt(System.currentTimeMillis())
			.expireTime(System.currentTimeMillis() + 60000)
			.build();
	}

	private CacheContext createCacheContext(CacheKey cacheKey) {
		Function<Object, Object> loader = key -> "loaded-value";
		return CacheContext.builder()
			.cacheKey(cacheKey)
			.doSingleFlightFun(loader)
			.metricsRecorder(CacheMetricsRecorder.noOp())
			.build();
	}

}