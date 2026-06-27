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
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@DisplayName("前置检查Handler测试")
class PreCheckHandlerTest {

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
    private CacheHandler nextHandler;

    private CacheExecutorConfig config;
    private PreCheckHandler preCheckHandler;

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

        preCheckHandler = new PreCheckHandler(nextHandler, config);

        System.out.println("执行测试: " + testInfo.getDisplayName());
    }

    @Test
    @DisplayName("PC-001: 正常请求通过检查")
    void testNormalRequestPassCheck() {
        CacheKey cacheKey = createCacheKey(CacheLevel.LOCAL_CACHE, "test-key");
        CacheValue nextValue = createCacheValue("next-value");
        CacheContext context = createCacheContext(cacheKey);

        when(bloomFilter.exists(any(), any())).thenReturn(true);
        when(nextHandler.get(context)).thenReturn(nextValue);

        CacheValue result = preCheckHandler.get(context);

        assertNotNull(result);
        assertEquals("next-value", result.getValue());
        verify(readHotspotDetector).recordRead(any());
        verify(nextHandler).get(context);
    }

    @Test
    @DisplayName("PC-002: 布隆过滤器过滤无效key")
    void testBloomFilterFilterInvalidKey() {
        CacheKey cacheKey = createCacheKeyWithBloomFilter(CacheLevel.LOCAL_CACHE, "invalid-key");
        CacheContext context = createCacheContext(cacheKey);

        when(bloomFilter.exists(any(), any())).thenReturn(false);

        CacheValue result = preCheckHandler.get(context);

        assertNull(result);
        verify(readHotspotDetector).recordRead(any());
        verify(nextHandler, never()).get(any());
    }

    @Test
    @DisplayName("PC-003: 空CacheKey检查失败")
    void testNullCacheKeyCheckFailed() {
        CacheContext context = createCacheContext(null);

        assertThrows(Exception.class, () -> {
            preCheckHandler.get(context);
        });
    }

    @Test
    @DisplayName("PC-004: 记录读操作")
    void testRecordReadOperation() {
        CacheKey cacheKey = createCacheKey(CacheLevel.LOCAL_CACHE, "test-key");
        CacheValue nextValue = createCacheValue("next-value");
        CacheContext context = createCacheContext(cacheKey);

        when(bloomFilter.exists(any(), any())).thenReturn(true);
        when(nextHandler.get(context)).thenReturn(nextValue);

        preCheckHandler.get(context);

        verify(readHotspotDetector).recordRead(cacheKey.getKey());
    }

    @Test
    @DisplayName("PC-005: 布隆过滤器未启用时通过")
    void testBloomFilterDisabled() {
        CacheKey cacheKey = createCacheKey(CacheLevel.LOCAL_CACHE, "test-key");
        CacheValue nextValue = createCacheValue("next-value");
        CacheContext context = createCacheContext(cacheKey);

        when(nextHandler.get(context)).thenReturn(nextValue);

        CacheValue result = preCheckHandler.get(context);

        assertNotNull(result);
        assertEquals("next-value", result.getValue());
        verify(bloomFilter, never()).exists(any(), any());
        verify(nextHandler).get(context);
    }

    @Test
    @DisplayName("PC-006: 异步请求通过检查")
    void testAsyncRequestPassCheck() {
        CacheKey cacheKey = createCacheKey(CacheLevel.LOCAL_CACHE, "async-key");
        CacheValue asyncValue = createCacheValue("async-value");
        CacheContext context = createCacheContext(cacheKey);

        when(bloomFilter.exists(any(), any())).thenReturn(true);
        when(nextHandler.getAsync(context)).thenReturn(CompletableFuture.completedFuture(asyncValue));

        CompletableFuture<CacheValue> future = preCheckHandler.getAsync(context);

        assertNotNull(future);
        assertTrue(future.isDone());
        verify(readHotspotDetector).recordRead(any());
        verify(nextHandler).getAsync(context);
    }

    @Test
    @DisplayName("PC-007: 失效请求通过检查")
    void testEvictRequestPassCheck() {
        CacheKey cacheKey = createCacheKey(CacheLevel.LOCAL_CACHE, "evict-key");
        CacheContext context = createCacheContext(cacheKey);

        preCheckHandler.evict(context);

        verify(nextHandler).evict(context);
    }

    @Test
    @DisplayName("PC-008: 异步失效请求通过检查")
    void testEvictAsyncRequestPassCheck() {
        CacheKey cacheKey = createCacheKey(CacheLevel.LOCAL_CACHE, "async-evict-key");
        CacheContext context = createCacheContext(cacheKey);

        when(nextHandler.evictAsync(context)).thenReturn(CompletableFuture.completedFuture(true));

        CompletableFuture<Boolean> future = preCheckHandler.evictAsync(context);

        assertNotNull(future);
        assertTrue(future.isDone());
        verify(nextHandler).evictAsync(context);
    }

    @Test
    @DisplayName("PC-009: 布隆过滤器异常时继续执行")
    void testBloomFilterExceptionContinue() {
        CacheKey cacheKey = createCacheKeyWithBloomFilter(CacheLevel.LOCAL_CACHE, "exception-key");
        CacheValue nextValue = createCacheValue("next-value");
        CacheContext context = createCacheContext(cacheKey);

        when(bloomFilter.exists(any(), any())).thenThrow(new RuntimeException("Bloom filter error"));
        when(nextHandler.get(context)).thenReturn(nextValue);

        CacheValue result = preCheckHandler.get(context);

        assertNotNull(result);
        assertEquals("next-value", result.getValue());
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
                .expireTimeMs(60000)
                .build();
    }

    private CacheKey createCacheKeyWithBloomFilter(CacheLevel level, String key) {
        return CacheKey.builder()
                .key(key)
                .cacheLevel(level)
                .consistencyLevel(ConsistencyLevel.HIGH)
                .bloomFilterEnabled(true)
                .bloomFilterName("default-filter")
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

    private CacheContext createCacheContext(CacheKey cacheKey) {
        Function<Object, Object> loader = key -> "loaded-value";
        return CacheContext.builder()
                .cacheKey(cacheKey)
                .doSingleFlightFun(loader)
                .metricsRecorder(CacheMetricsRecorder.noOp())
                .build();
    }
}