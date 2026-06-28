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
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@DisplayName("本地缓存Handler测试")
class LocalCacheHandlerTest {

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
	private CacheBloomFilter bloomFilter;

	@Mock
	private Broadcaster broadcaster;

	@Mock
	private CacheHandler nextHandler;

	private CacheExecutorConfig config;

	private LocalCacheHandler localCacheHandler;

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
			.cacheCircuitBreaker(new CacheCircuitBreaker(null))
			.cacheBloomFilter(bloomFilter)
			.build();
		distributedCacheHandler = new DistributedCacheHandler(nextHandler, config, broadcaster);
		localCacheHandler = new LocalCacheHandler(distributedCacheHandler, config, broadcaster);

		System.out.println("执行测试: " + testInfo.getDisplayName());
	}

	@Test
	@DisplayName("LC-001: LOCAL_CACHE级别获取")
	void testGetLocalCacheLevel() {
		CacheKey cacheKey = createCacheKey(CacheLevel.LOCAL_CACHE, "test-key");
		CacheValue cacheValue = createCacheValue("local-value");
		CacheContext context = createCacheContext(cacheKey);

		when(localCacheManager.get(cacheKey)).thenReturn(cacheValue);

		CacheValue result = localCacheHandler.get(context);

		assertNotNull(result);
		assertEquals("local-value", result.getValue());
		assertTrue(context.isL1Hit());
		verify(localCacheManager).get(cacheKey);
		verify(nextHandler, never()).get(any());
	}

	@Test
	@DisplayName("LC-002: LOCAL_CACHE级别未命中")
	void testGetLocalCacheLevelMiss() {
		CacheKey cacheKey = createCacheKey(CacheLevel.LOCAL_CACHE, "test-key");
		CacheValue nextValue = createCacheValue("next-value");
		CacheContext context = createCacheContext(cacheKey);

		when(localCacheManager.get(cacheKey)).thenReturn(null);
		when(nextHandler.get(context)).thenReturn(nextValue);

		CacheValue result = localCacheHandler.get(context);

		assertNotNull(result);
		assertEquals("next-value", result.getValue());
		assertFalse(context.isL1Hit());
		verify(localCacheManager).get(cacheKey);
		verify(nextHandler).get(context);
	}

	@Test
	@DisplayName("LC-003: ADAPTIVE_CACHE级别获取（非写热点）")
	void testGetAdaptiveCacheNonWriteHotKey() {
		CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "test-key");
		CacheValue cacheValue = createCacheValue("adaptive-value");
		CacheContext context = createCacheContext(cacheKey);

		when(localCacheManager.get(cacheKey)).thenReturn(cacheValue);
		when(writeHotspotDetector.shouldBypassL1(any())).thenReturn(false);

		CacheValue result = localCacheHandler.get(context);

		assertNotNull(result);
		assertEquals("adaptive-value", result.getValue());
		assertTrue(context.isL1Hit());
		verify(localCacheManager).get(cacheKey);
		verify(nextHandler, never()).get(any());
	}

	@Test
	@DisplayName("LC-004: ADAPTIVE_CACHE级别获取（写热点）")
	void testGetAdaptiveCacheWriteHotKey() {
		CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "test-key");
		CacheValue nextValue = createCacheValue("next-value");
		CacheContext context = createCacheContext(cacheKey);

		when(localCacheManager.get(cacheKey)).thenReturn(createCacheValue("local-value"));
		when(writeHotspotDetector.shouldBypassL1(any())).thenReturn(true);
		when(nextHandler.get(context)).thenReturn(nextValue);

		CacheValue result = localCacheHandler.get(context);

		assertNotNull(result);
		assertEquals("next-value", result.getValue());
		assertFalse(context.isL1Hit());
		verify(localCacheManager, never()).get(any());
		verify(nextHandler).get(context);
	}

	@Test
	@DisplayName("LC-005: L2_CACHE级别获取")
	void testGetL2CacheLevel() {
		CacheKey cacheKey = createCacheKey(CacheLevel.L2_CACHE, "test-key");
		CacheValue nextValue = createCacheValue("l2-value");
		CacheContext context = createCacheContext(cacheKey);

		when(distributedCacheManager.get(cacheKey)).thenReturn(null);
		when(nextHandler.get(context)).thenReturn(nextValue);

		CacheValue result = localCacheHandler.get(context);

		assertNotNull(result);
		assertEquals("l2-value", result.getValue());
		assertFalse(context.isL1Hit());
		verify(localCacheManager, never()).get(any());
		verify(nextHandler).get(context);
	}

	@Test
	@DisplayName("LC-006: LOCAL_CACHE级别失效")
	void testEvictLocalCacheLevel() {
		CacheKey cacheKey = createCacheKey(CacheLevel.LOCAL_CACHE, "test-key");
		CacheContext context = createCacheContext(cacheKey);

		localCacheHandler.evict(context);

		verify(localCacheManager).remove(cacheKey);
		verify(writeHotspotDetector).recordInvalidation(any());
		verify(nextHandler, never()).evict(any());
	}

	@Test
	@DisplayName("LC-007: ADAPTIVE_CACHE级别失效（广播启用）")
	void testEvictAdaptiveCacheWithBroadcast() {
		CacheKey cacheKey = createCacheKeyWithBroadcast(CacheLevel.ADAPTIVE_CACHE, "test-key");
		CacheContext context = createCacheContext(cacheKey);

		when(writeHotspotDetector.shouldBypassL1(any())).thenReturn(false);
		when(localCacheMarkerManager.getActiveNodes(any())).thenReturn(Arrays.asList("1", "2"));
		localCacheHandler.evict(context);

		verify(localCacheManager).remove(cacheKey);
		verify(writeHotspotDetector).recordInvalidation(any());
		verify(broadcaster).addKey(cacheKey);
	}

	@Test
	@DisplayName("LC-008: ADAPTIVE_CACHE级别失效（写热点跳过广播）")
	void testEvictAdaptiveCacheWriteHotKeySkipBroadcast() {
		CacheKey cacheKey = createCacheKeyWithBroadcast(CacheLevel.ADAPTIVE_CACHE, "test-key");
		CacheContext context = createCacheContext(cacheKey);

		when(writeHotspotDetector.shouldBypassL1(any())).thenReturn(true);

		localCacheHandler.evict(context);

		verify(localCacheManager).remove(cacheKey);
		verify(writeHotspotDetector).recordInvalidation(any());
		verify(broadcaster, never()).addKey(any());
	}

	@Test
	@DisplayName("LC-009: 异步获取本地缓存")
	void testGetAsyncLocalCache() throws ExecutionException, InterruptedException {
		CacheKey cacheKey = createCacheKey(CacheLevel.LOCAL_CACHE, "test-key");
		CacheValue cacheValue = createCacheValue("async-local-value");
		CacheContext context = createCacheContext(cacheKey);

		when(localCacheManager.get(cacheKey)).thenReturn(cacheValue);

		CompletableFuture<CacheValue> future = localCacheHandler.getAsync(context);

		assertNotNull(future);
		assertTrue(future.isDone());
		assertEquals("async-local-value", future.get().getValue());
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

	private CacheKey createCacheKeyWithBroadcast(CacheLevel level, String key) {
		return CacheKey.builder()
			.key(key)
			.cacheLevel(level)
			.consistencyLevel(ConsistencyLevel.HIGH)
			.bloomFilterEnabled(false)
			.broadcastEnabled(true)
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

	private CacheContext createCacheContext(CacheKey cacheKey) {
		Function<Object, Object> loader = key -> "loaded-value";
		return CacheContext.builder()
			.cacheKey(cacheKey)
			.doSingleFlightFun(loader)
			.metricsRecorder(CacheMetricsRecorder.noOp())
			.build();
	}

}