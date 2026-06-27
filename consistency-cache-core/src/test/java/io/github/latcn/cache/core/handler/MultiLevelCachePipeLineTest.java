package io.github.latcn.cache.core.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.latcn.cache.core.circuitbreaker.CacheCircuitBreaker;
import io.github.latcn.cache.core.distributed.DistributedCacheManager;
import io.github.latcn.cache.core.executor.CacheBloomFilter;
import io.github.latcn.cache.core.executor.CacheExecutorConfig;
import io.github.latcn.cache.core.hotspot.reads.ReadHotspotDetector;
import io.github.latcn.cache.core.hotspot.writes.WriteHotspotDetector;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.github.latcn.cache.core.local.LocalCacheMarkerManager;
import io.github.latcn.cache.core.model.CacheKey;
import io.github.latcn.cache.core.model.CacheLevel;
import io.github.latcn.cache.core.model.CacheValue;
import io.github.latcn.cache.core.model.ConsistencyLevel;
import io.github.latcn.cache.core.pubsub.Broadcaster;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@DisplayName("多级缓存管道测试")
class MultiLevelCachePipeLineTest {

	@Mock
	private LocalCacheManager localCacheManager;

	@Mock
	private DistributedCacheManager distributedCacheManager;

	@Mock
	private LocalCacheMarkerManager localCacheMarkerManager;

	@Mock
	private WriteHotspotDetector writeHotspotDetector;

	@Mock
	private ReadHotspotDetector readHotspotDetector;

	@Mock
	private CacheCircuitBreaker circuitBreaker;

	@Mock
	private CacheBloomFilter bloomFilter;

	@Mock
	private Broadcaster broadcaster;

	private CacheExecutorConfig config;

	private MultiLevelCachePipeLine pipeLine;

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

		pipeLine = new MultiLevelCachePipeLine(config, broadcaster);

		when(circuitBreaker.execute(any())).thenAnswer(invocation -> {
			java.util.function.Supplier<?> supplier = invocation.getArgument(0);
			return supplier.get();
		});

		System.out.println("执行测试: " + testInfo.getDisplayName());
	}

	@Test
	@DisplayName("PL-001: 同步获取缓存（本地缓存命中）")
	void testGetLocalCacheHit() {
		CacheKey cacheKey = createCacheKey(CacheLevel.LOCAL_CACHE, "test-key");
		CacheValue cacheValue = createCacheValue("test-value");

		when(localCacheManager.get(cacheKey)).thenReturn(cacheValue);
		when(bloomFilter.exists(any(), any())).thenReturn(true);

		Function<Object, Object> loader = key -> "loaded-value";
		CacheValue result = pipeLine.get(cacheKey, loader);

		assertNotNull(result);
		assertEquals("test-value", result.getValue());
		verify(localCacheManager).get(cacheKey);
		verify(distributedCacheManager, never()).get(any());
	}

	@Test
	@DisplayName("PL-002: 同步获取缓存（本地缓存未命中，分布式缓存命中）")
	void testGetDistributedCacheHit() {
		CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "test-key");
		CacheValue distributedValue = createCacheValue("distributed-value");

		when(localCacheManager.get(cacheKey)).thenReturn(null);
		when(distributedCacheManager.get(cacheKey)).thenReturn(distributedValue);
		when(bloomFilter.exists(any(), any())).thenReturn(true);
		when(writeHotspotDetector.shouldBypassL1(any())).thenReturn(false);
		when(readHotspotDetector.isHotKey(any())).thenReturn(false);

		Function<Object, Object> loader = key -> "loaded-value";
		CacheValue result = pipeLine.get(cacheKey, loader);

		assertNotNull(result);
		assertEquals("distributed-value", result.getValue());
		verify(localCacheManager).get(cacheKey);
		verify(distributedCacheManager).get(cacheKey);
	}

	@Test
	@DisplayName("PL-003: 同步获取缓存（两级都未命中，从DB加载）")
	void testGetFromDb() {
		CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "test-key");

		when(localCacheManager.get(cacheKey)).thenReturn(null);
		when(distributedCacheManager.get(cacheKey)).thenReturn(null);
		when(bloomFilter.exists(any(), any())).thenReturn(true);
		when(writeHotspotDetector.shouldBypassL1(any())).thenReturn(false);
		when(readHotspotDetector.isHotKey(any())).thenReturn(false);

		Function<Object, Object> loader = key -> "db-loaded-value";
		CacheValue result = pipeLine.get(cacheKey, loader);

		assertNotNull(result);
		assertEquals("db-loaded-value", result.getValue());
		verify(localCacheManager).get(cacheKey);
		verify(distributedCacheManager).get(cacheKey);
		verify(distributedCacheManager).put(any(), any());
	}

	@Test
	@DisplayName("PL-004: 异步获取缓存")
	void testGetAsync() {
		CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "test-key");
		CacheValue cacheValue = createCacheValue("async-value");

		when(localCacheManager.get(cacheKey)).thenReturn(null);
		when(distributedCacheManager.getInBatch(cacheKey)).thenReturn(CompletableFuture.completedFuture(cacheValue));
		when(bloomFilter.exists(any(), any())).thenReturn(true);
		when(writeHotspotDetector.shouldBypassL1(any())).thenReturn(false);
		when(readHotspotDetector.isHotKey(any())).thenReturn(false);

		Function<Object, Object> loader = key -> "loaded-value";
		CompletableFuture<CacheValue> future = pipeLine.getAsync(cacheKey, loader);

		assertNotNull(future);
		assertTrue(future.isDone());
	}

	@Test
	@DisplayName("PL-005: 同步失效缓存")
	void testEvict() {
		CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "test-key");

		pipeLine.evict(cacheKey);

		verify(localCacheManager).remove(cacheKey);
		verify(distributedCacheManager).remove(cacheKey);
	}

	@Test
	@DisplayName("PL-006: 异步失效缓存")
	void testEvictAsync() {
		CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "test-key");

		when(distributedCacheManager.removeInBatch(cacheKey)).thenReturn(CompletableFuture.completedFuture(true));

		CompletableFuture<Boolean> future = pipeLine.evictAsync(cacheKey);

		assertNotNull(future);
		assertTrue(future.isDone());
	}

	@Test
	@DisplayName("PL-007: 空CacheKey处理")
	void testNullCacheKey() {
		Function<Object, Object> loader = key -> "value";

		assertThrows(Exception.class, () -> {
			pipeLine.get(null, loader);
		});
	}

	@Test
	@DisplayName("PL-008: L2_CACHE级别获取")
	void testGetL2Cache() {
		CacheKey cacheKey = createCacheKey(CacheLevel.L2_CACHE, "test-key");
		CacheValue cacheValue = createCacheValue("l2-value");

		when(distributedCacheManager.get(cacheKey)).thenReturn(cacheValue);
		when(bloomFilter.exists(any(), any())).thenReturn(true);

		Function<Object, Object> loader = key -> "loaded-value";
		CacheValue result = pipeLine.get(cacheKey, loader);

		assertNotNull(result);
		assertEquals("l2-value", result.getValue());
		verify(localCacheManager, never()).get(any());
		verify(distributedCacheManager).get(cacheKey);
	}

	@Test
	@DisplayName("PL-009: 本地缓存过期处理")
	void testExpiredLocalCache() {
		CacheKey cacheKey = createCacheKey(CacheLevel.LOCAL_CACHE, "test-key");
		CacheValue expiredValue = CacheValue.builder()
			.value("expired-value")
			.expireTime(System.currentTimeMillis() - 1000)
			.createdAt(System.currentTimeMillis() - 2000)
			.build();

		when(localCacheManager.get(cacheKey)).thenReturn(expiredValue);
		when(bloomFilter.exists(any(), any())).thenReturn(true);

		Function<Object, Object> loader = key -> "loaded-value";
		CacheValue result = pipeLine.get(cacheKey, loader);

		assertNotNull(result);
		assertEquals("loaded-value", result.getValue());
	}

	private CacheKey createCacheKey(CacheLevel level, String key) {
		return CacheKey.builder()
			.key(key)
			.cacheLevel(level)
			.consistencyLevel(ConsistencyLevel.HIGH)
			.bloomFilterEnabled(false)
			.broadcastEnabled(false)
			.cacheNullValues(false)
			.expireTimeMs(60000)
			.build();
	}

	private CacheValue createCacheValue(Object value) {
		return CacheValue.builder()
			.value(value)
			.createdAt(System.currentTimeMillis())
			.expireTime(System.currentTimeMillis() + 60000)
			.build();
	}

}