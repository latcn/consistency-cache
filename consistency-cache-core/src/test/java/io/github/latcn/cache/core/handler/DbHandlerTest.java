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

@DisplayName("数据库Handler测试")
class DbHandlerTest {

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

    private CacheExecutorConfig config;
    private DbHandler dbHandler;

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

        dbHandler = new DbHandler(null, config);

        System.out.println("执行测试: " + testInfo.getDisplayName());
    }

    @Test
    @DisplayName("DB-001: 从DB加载数据")
    void testLoadFromDb() {
        CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "test-key");
        Function<Object, Object> loader = key -> "db-loaded-value";
        CacheContext context = createCacheContext(cacheKey, loader);

        CacheValue result = dbHandler.get(context);

        assertNotNull(result);
        assertEquals("db-loaded-value", result.getValue());
        assertNotNull(result.getCreatedAt());
        assertTrue(result.getExpireTime() > System.currentTimeMillis());
        verify(distributedCacheManager).put(any(), any());
    }

    @Test
    @DisplayName("DB-002: DB返回null且允许缓存null")
    void testLoadNullWithCacheNullValues() {
        CacheKey cacheKey = createCacheKeyWithCacheNull(CacheLevel.ADAPTIVE_CACHE, "null-key");
        Function<Object, Object> loader = key -> null;
        CacheContext context = createCacheContext(cacheKey, loader);

        CacheValue result = dbHandler.get(context);

        assertNotNull(result);
        assertNull(result.getValue());
        verify(distributedCacheManager).put(any(), any());
    }

    @Test
    @DisplayName("DB-003: DB返回null且不允许缓存null")
    void testLoadNullWithoutCacheNullValues() {
        CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "null-key");
        Function<Object, Object> loader = key -> null;
        CacheContext context = createCacheContext(cacheKey, loader);

        CacheValue result = dbHandler.get(context);

        assertNull(result);
        verify(distributedCacheManager, never()).put(any(), any());
    }

    @Test
    @DisplayName("DB-004: LOCAL_CACHE级别回填本地缓存")
    void testLocalCacheLevelBackfill() {
        CacheKey cacheKey = createCacheKey(CacheLevel.LOCAL_CACHE, "local-key");
        Function<Object, Object> loader = key -> "local-loaded-value";
        CacheContext context = createCacheContext(cacheKey, loader);

        CacheValue result = dbHandler.get(context);

        assertNotNull(result);
        assertEquals("local-loaded-value", result.getValue());
        verify(localCacheManager).put(cacheKey, result);
        verify(distributedCacheManager, never()).put(any(), any());
    }

    @Test
    @DisplayName("DB-005: ADAPTIVE_CACHE级别回填分布式缓存")
    void testAdaptiveCacheLevelBackfill() {
        CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "adaptive-key");
        Function<Object, Object> loader = key -> "adaptive-loaded-value";
        CacheContext context = createCacheContext(cacheKey, loader);

        CacheValue result = dbHandler.get(context);

        assertNotNull(result);
        assertEquals("adaptive-loaded-value", result.getValue());
        verify(distributedCacheManager).put(cacheKey, result);
        verify(localCacheManager, never()).put(any(), any());
    }

    @Test
    @DisplayName("DB-006: 设置过期时间")
    void testSetExpireTime() {
        long customExpireTimeMs = 120000;
        CacheKey cacheKey = createCacheKeyWithExpireTime(CacheLevel.ADAPTIVE_CACHE, "expire-key", customExpireTimeMs);
        Function<Object, Object> loader = key -> "expire-value";
        CacheContext context = createCacheContext(cacheKey, loader);

        long startTime = System.currentTimeMillis();
        CacheValue result = dbHandler.get(context);

        assertNotNull(result);
        assertTrue(result.getExpireTime() >= startTime + customExpireTimeMs);
    }

    @Test
    @DisplayName("DB-007: 异步加载数据")
    void testLoadFromDbAsync() {
        CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "async-key");
        Function<Object, Object> loader = key -> "async-loaded-value";
        CacheContext context = createCacheContext(cacheKey, loader);

        when(distributedCacheManager.putInBatch(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(true));

        CompletableFuture<CacheValue> future = dbHandler.getAsync(context);

        assertNotNull(future);
        assertTrue(future.isDone());
        try {
            CacheValue result = future.get();
            assertNotNull(result);
            assertEquals("async-loaded-value", result.getValue());
        } catch (Exception e) {
            fail("异步加载失败: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("DB-008: 调用evict抛出异常")
    void testEvictThrowsException() {
        CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "test-key");
        CacheContext context = createCacheContext(cacheKey, key -> "value");

        CacheException exception = assertThrows(CacheException.class, () -> {
            dbHandler.evict(context);
        });

        assertEquals(CacheError.UNSUPPORTED_OPERATION.getErrorCode(), exception.getErrorCode());
    }

    @Test
    @DisplayName("DB-009: 调用evictAsync抛出异常")
    void testEvictAsyncThrowsException() {
        CacheKey cacheKey = createCacheKey(CacheLevel.ADAPTIVE_CACHE, "test-key");
        CacheContext context = createCacheContext(cacheKey, key -> "value");

        CacheException exception = assertThrows(CacheException.class, () -> {
            dbHandler.evictAsync(context);
        });

        assertEquals(CacheError.UNSUPPORTED_OPERATION.getErrorCode(), exception.getErrorCode());
    }

    @Test
    @DisplayName("DB-010: L2_CACHE级别回填分布式缓存")
    void testL2CacheLevelBackfill() {
        CacheKey cacheKey = createCacheKey(CacheLevel.L2_CACHE, "l2-key");
        Function<Object, Object> loader = key -> "l2-loaded-value";
        CacheContext context = createCacheContext(cacheKey, loader);

        CacheValue result = dbHandler.get(context);

        assertNotNull(result);
        assertEquals("l2-loaded-value", result.getValue());
        verify(distributedCacheManager).put(cacheKey, result);
        verify(localCacheManager, never()).put(any(), any());
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

    private CacheKey createCacheKeyWithCacheNull(CacheLevel level, String key) {
        return CacheKey.builder()
                .key(key)
                .cacheLevel(level)
                .consistencyLevel(ConsistencyLevel.HIGH)
                .bloomFilterEnabled(false)
                .broadcastEnabled(false)
                .cacheNullValues(true)
                .expireTimeMs(60000)
                .build();
    }

    private CacheKey createCacheKeyWithExpireTime(CacheLevel level, String key, long expireTimeMs) {
        return CacheKey.builder()
                .key(key)
                .cacheLevel(level)
                .consistencyLevel(ConsistencyLevel.HIGH)
                .bloomFilterEnabled(false)
                .broadcastEnabled(false)
                .cacheNullValues(false)
                .expireTimeMs(expireTimeMs)
                .build();
    }

    private CacheContext createCacheContext(CacheKey cacheKey, Function<Object, Object> loader) {
        return CacheContext.builder()
                .cacheKey(cacheKey)
                .doSingleFlightFun(loader)
                .metricsRecorder(CacheMetricsRecorder.noOp())
                .build();
    }
}