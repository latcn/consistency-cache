package io.github.latcn.cache.core.executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.latcn.cache.core.circuitbreaker.CacheCircuitBreaker;
import io.github.latcn.cache.core.distributed.DistributedCacheManager;
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

@DisplayName("默认缓存执行器测试")
class DefaultCacheExecutorTest {

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
    private DefaultCacheExecutor executor;

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

        executor = new DefaultCacheExecutor(config);

        when(circuitBreaker.execute(any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> supplier = invocation.getArgument(0);
            return supplier.get();
        });

        System.out.println("执行测试: " + testInfo.getDisplayName());
    }

    @Test
    @DisplayName("EX-001: 检查本地缓存key是否存在")
    void testExistsLocalCacheKey() {
        CacheKey cacheKey = createCacheKey(CacheLevel.LOCAL_CACHE, "local-key");

        when(localCacheManager.containKey(cacheKey)).thenReturn(true);

        boolean exists = executor.exists(cacheKey);

        assertTrue(exists);
        verify(localCacheManager).containKey(cacheKey);
        verify(distributedCacheManager, never()).containKey(any());
    }

    @Test
    @DisplayName("EX-002: 检查分布式缓存key是否存在")
    void testExistsDistributedCacheKey() {
        CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "distributed-key");

        when(distributedCacheManager.containKey(cacheKey)).thenReturn(true);

        boolean exists = executor.exists(cacheKey);

        assertTrue(exists);
        verify(distributedCacheManager).containKey(cacheKey);
        verify(localCacheManager, never()).containKey(any());
    }

    @Test
    @DisplayName("EX-003: 检查不存在的key")
    void testExistsNonExistentKey() {
        CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "non-existent-key");

        when(distributedCacheManager.containKey(cacheKey)).thenReturn(false);

        boolean exists = executor.exists(cacheKey);

        assertFalse(exists);
        verify(distributedCacheManager).containKey(cacheKey);
    }

    @Test
    @DisplayName("EX-004: 设置Broadcaster")
    void testSetBroadcaster() {
        executor.setBroadcaster(broadcaster);

        assertNotNull(executor);
    }

    @Test
    @DisplayName("EX-005: 获取缓存值")
    void testGetCacheValue() {
        executor.setBroadcaster(broadcaster);

        CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "test-key");
        CacheValue cacheValue = createCacheValue("test-value");

        when(localCacheManager.get(cacheKey)).thenReturn(null);
        when(distributedCacheManager.get(cacheKey)).thenReturn(cacheValue);
        when(bloomFilter.exists(any(), any())).thenReturn(true);
        when(writeHotspotDetector.shouldBypassL1(any())).thenReturn(false);
        when(readHotspotDetector.isHotKey(any())).thenReturn(false);

        Function<Object, Object> loader = key -> "loaded-value";
        CacheValue result = executor.get(cacheKey, loader);

        assertNotNull(result);
        assertEquals("test-value", result.getValue());
    }

    @Test
    @DisplayName("EX-006: 失效缓存")
    void testEvictCache() {
        executor.setBroadcaster(broadcaster);

        CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "evict-key");

        executor.evict(cacheKey);

        verify(localCacheManager).remove(cacheKey);
        verify(distributedCacheManager).remove(cacheKey);
    }

    @Test
    @DisplayName("EX-007: 异步获取缓存值")
    void testGetAsyncCacheValue() {
        executor.setBroadcaster(broadcaster);

        CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "async-key");
        CacheValue cacheValue = createCacheValue("async-value");

        when(localCacheManager.get(cacheKey)).thenReturn(null);
        when(distributedCacheManager.getInBatch(cacheKey))
                .thenReturn(CompletableFuture.completedFuture(cacheValue));
        when(bloomFilter.exists(any(), any())).thenReturn(true);
        when(writeHotspotDetector.shouldBypassL1(any())).thenReturn(false);
        when(readHotspotDetector.isHotKey(any())).thenReturn(false);

        Function<Object, Object> loader = key -> "loaded-value";
        CompletableFuture<CacheValue> future = executor.getAsync(cacheKey, loader);

        assertNotNull(future);
        assertTrue(future.isDone());
    }

    @Test
    @DisplayName("EX-008: 异步失效缓存")
    void testEvictAsyncCache() {
        executor.setBroadcaster(broadcaster);

        CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "async-evict-key");

        when(distributedCacheManager.removeInBatch(cacheKey))
                .thenReturn(CompletableFuture.completedFuture(true));

        CompletableFuture<Boolean> future = executor.evictAsync(cacheKey);

        assertNotNull(future);
        assertTrue(future.isDone());
    }

    @Test
    @DisplayName("EX-009: L2_CACHE级别key存在检查")
    void testExistsL2CacheKey() {
        CacheKey cacheKey = createCacheKey(CacheLevel.L2_CACHE, "l2-key");

        when(distributedCacheManager.containKey(cacheKey)).thenReturn(true);

        boolean exists = executor.exists(cacheKey);

        assertTrue(exists);
        verify(distributedCacheManager).containKey(cacheKey);
        verify(localCacheManager, never()).containKey(any());
    }

    @Test
    @DisplayName("EX-010: 空CacheKey检查")
    void testNullCacheKeyCheck() {
        assertThrows(Exception.class, () -> {
            executor.exists(null);
        });
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