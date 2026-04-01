package com.consist.cache.spring.local;

import com.consist.cache.core.local.LocalCacheFactory;
import com.consist.cache.core.local.LocalCacheManager;
import com.consist.cache.core.model.*;
import com.consist.cache.spring.local.adapter.CaffeineCacheAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LocalCacheManager
 */
@DisplayName("LocalCacheManager Tests")
class LocalCacheManagerTest {

    private LocalCacheManager localCacheManager;
    private HccProperties.LocalCacheProperties properties;

    @BeforeEach
    void setUp() {
        properties = new HccProperties.LocalCacheProperties();
        LocalCacheFactory.registerCacheType(LocalCacheType.CAFFEINE.name(), CaffeineCacheAdapter.class);
        localCacheManager = new LocalCacheManager(properties);
    }

    @Test
    @DisplayName("Should put and get cache value successfully")
    void testPutAndGet() {
        // Given
        CacheKey cacheKey = CacheKey.builder()
                .key("test-key")
                .consistencyLevel(ConsistencyLevel.HIGH)
                .cacheLevel(CacheLevel.LOCAL_CACHE)
                .expireTimeMs(60000)
                .build();
        
        CacheValue<String> cacheValue = CacheValue.<String>builder()
                .value("test-value")
                .expireTime(System.currentTimeMillis() + 60000)
                .createdAt(System.currentTimeMillis())
                .build();

        // When
        localCacheManager.put(cacheKey, cacheValue);
        CacheValue result = localCacheManager.get(cacheKey);

        // Then
        assertNotNull(result);
        assertEquals("test-value", result.getValue());
        assertFalse(result.isExpired());
    }

    @Test
    @DisplayName("Should return null for non-existent key")
    void testGetNonExistentKey() {
        // Given
        CacheKey cacheKey = CacheKey.builder()
                .key("non-existent")
                .consistencyLevel(ConsistencyLevel.HIGH)
                .cacheLevel(CacheLevel.LOCAL_CACHE)
                .build();

        // When
        CacheValue result = localCacheManager.get(cacheKey);

        // Then
        assertNull(result);
    }

    @Test
    @DisplayName("Should return null for expired cache value")
    void testGetExpiredCache() {
        // Given
        CacheKey cacheKey = CacheKey.builder()
                .key("expired-key")
                .consistencyLevel(ConsistencyLevel.HIGH)
                .cacheLevel(CacheLevel.LOCAL_CACHE)
                .build();
        
        CacheValue<String> cacheValue = CacheValue.<String>builder()
                .value("expired-value")
                .expireTime(System.currentTimeMillis() - 1000) // Already expired
                .createdAt(System.currentTimeMillis() - 2000)
                .build();

        // When
        localCacheManager.put(cacheKey, cacheValue);
        CacheValue result = localCacheManager.get(cacheKey);

        // Then
        assertNull(result);
    }

    @Test
    @DisplayName("Should remove cache entry successfully")
    void testRemove() {
        // Given
        CacheKey cacheKey = CacheKey.builder()
                .key("to-remove")
                .consistencyLevel(ConsistencyLevel.HIGH)
                .cacheLevel(CacheLevel.LOCAL_CACHE)
                .build();
        
        CacheValue<String> cacheValue = CacheValue.<String>builder()
                .value("remove-me")
                .expireTime(System.currentTimeMillis() + 60000)
                .createdAt(System.currentTimeMillis())
                .build();

        localCacheManager.put(cacheKey, cacheValue);
        assertTrue(localCacheManager.containKey(cacheKey));

        // When
        localCacheManager.remove(cacheKey);

        // Then
        assertFalse(localCacheManager.containKey(cacheKey));
    }

    @Test
    @DisplayName("Should clear all cache entries")
    void testClear() {
        // Given
        CacheKey key1 = CacheKey.builder()
                .key("key1")
                .consistencyLevel(ConsistencyLevel.HIGH)
                .cacheLevel(CacheLevel.LOCAL_CACHE)
                .build();
        
        CacheKey key2 = CacheKey.builder()
                .key("key2")
                .consistencyLevel(ConsistencyLevel.AVAILABLE)
                .cacheLevel(CacheLevel.LOCAL_CACHE)
                .build();

        CacheValue<String> value1 = CacheValue.<String>builder()
                .value("value1")
                .expireTime(System.currentTimeMillis() + 60000)
                .build();
        
        CacheValue<String> value2 = CacheValue.<String>builder()
                .value("value2")
                .expireTime(System.currentTimeMillis() + 60000)
                .build();

        localCacheManager.put(key1, value1);
        localCacheManager.put(key2, value2);

        // When
        localCacheManager.clear();

        // Then
        assertFalse(localCacheManager.containKey(key1));
        assertFalse(localCacheManager.containKey(key2));
        assertEquals(0, localCacheManager.getSize());
    }

    @Test
    @DisplayName("Should track hit and miss statistics")
    void testStatistics() {
        // Given
        CacheKey existingKey = CacheKey.builder()
                .key("existing")
                .consistencyLevel(ConsistencyLevel.HIGH)
                .cacheLevel(CacheLevel.LOCAL_CACHE)
                .build();
        
        CacheValue<String> value = CacheValue.<String>builder()
                .value("data")
                .expireTime(System.currentTimeMillis() + 60000)
                .build();
        
        localCacheManager.put(existingKey, value);

        // When - Generate some hits and misses
        localCacheManager.get(existingKey); // Hit
        localCacheManager.get(existingKey); // Hit
        localCacheManager.get(CacheKey.builder().key("miss1").consistencyLevel(ConsistencyLevel.HIGH).cacheLevel(CacheLevel.LOCAL_CACHE).build()); // Miss
        localCacheManager.get(CacheKey.builder().key("miss2").consistencyLevel(ConsistencyLevel.HIGH).cacheLevel(CacheLevel.LOCAL_CACHE).build()); // Miss

        // Then
        LocalCacheManager.CacheStats stats = localCacheManager.getStats();
        assertEquals(2, stats.getHitCount());
        assertEquals(2, stats.getMissCount());
        assertEquals(0.5, stats.getHitRate(), 0.01);
        assertEquals("50.00%", stats.getFormattedHitRate());
    }

    @Test
    @DisplayName("Should handle different consistency levels")
    void testDifferentConsistencyLevels() {
        localCacheManager.clear();
        // Given
        CacheKey highConsistencyKey = CacheKey.builder()
                .key("high-consistency")
                .consistencyLevel(ConsistencyLevel.HIGH)
                .cacheLevel(CacheLevel.LOCAL_CACHE)
                .build();
        
        CacheKey availableKey = CacheKey.builder()
                .key("available")
                .consistencyLevel(ConsistencyLevel.AVAILABLE)
                .cacheLevel(CacheLevel.LOCAL_CACHE)
                .build();

        CacheValue<String> value1 = CacheValue.<String>builder()
                .value("high-consistency-value")
                .expireTime(System.currentTimeMillis() + 60000)
                .build();
        
        CacheValue<String> value2 = CacheValue.<String>builder()
                .value("available-value")
                .expireTime(System.currentTimeMillis() + 60000)
                .build();

        // When
        localCacheManager.put(highConsistencyKey, value1);
        localCacheManager.put(availableKey, value2);

        // Then
        assertTrue(localCacheManager.containKey(highConsistencyKey));
        assertTrue(localCacheManager.containKey(availableKey));
        //assertEquals(2L, localCacheManager.getSize());
    }

    @Test
    @DisplayName("Should throw exception for null key")
    void testNullKey() {
        assertThrows(RuntimeException.class, () -> {
            localCacheManager.get(null);
        });
    }

    @Test
    @DisplayName("Should throw exception for unsupported key type")
    void testUnsupportedKeyType() {
        // Given
        CacheKey cacheKey = CacheKey.builder()
                .key(123.45) // Double is not supported
                .consistencyLevel(ConsistencyLevel.HIGH)
                .cacheLevel(CacheLevel.LOCAL_CACHE)
                .build();

        // When/Then
        assertThrows(RuntimeException.class, () -> {
            localCacheManager.get(cacheKey);
        });
    }

    @Test
    @DisplayName("Should remove by actual key across all consistency levels")
    void testRemoveByActualKey() {
        // Given
        CacheKey key1 = CacheKey.builder()
                .key("actual-key")
                .consistencyLevel(ConsistencyLevel.HIGH)
                .cacheLevel(CacheLevel.LOCAL_CACHE)
                .build();
        
        CacheKey key2 = CacheKey.builder()
                .key("actual-key")
                .consistencyLevel(ConsistencyLevel.AVAILABLE)
                .cacheLevel(CacheLevel.LOCAL_CACHE)
                .build();

        CacheValue<String> value1 = CacheValue.<String>builder()
                .value("value1")
                .expireTime(System.currentTimeMillis() + 60000)
                .build();
        
        CacheValue<String> value2 = CacheValue.<String>builder()
                .value("value2")
                .expireTime(System.currentTimeMillis() + 60000)
                .build();

        localCacheManager.put(key1, value1);
        localCacheManager.put(key2, value2);

        // When
        localCacheManager.removeByActualKey("actual-key");

        // Then
        assertFalse(localCacheManager.containKey(key1));
        assertFalse(localCacheManager.containKey(key2));
    }

    @Test
    @DisplayName("Should run manual eviction")
    void testRunEviction() {
        // Given
        CacheKey cacheKey = CacheKey.builder()
                .key("evict-test")
                .consistencyLevel(ConsistencyLevel.HIGH)
                .cacheLevel(CacheLevel.LOCAL_CACHE)
                .build();
        
        CacheValue<String> value = CacheValue.<String>builder()
                .value("test")
                .expireTime(System.currentTimeMillis() + 60000)
                .build();

        // Given
        CacheKey cacheKey1 = CacheKey.builder()
                .key("evict-test1")
                .consistencyLevel(ConsistencyLevel.HIGH)
                .cacheLevel(CacheLevel.LOCAL_CACHE)
                .build();

        CacheValue<String> value1 = CacheValue.<String>builder()
                .value("test1")
                .expireTime(System.currentTimeMillis() + 1000)
                .build();

        localCacheManager.put(cacheKey, value);
        localCacheManager.put(cacheKey1, value1);
         // Then - Should not throw exception
        assertTrue(localCacheManager.containKey(cacheKey1));
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        // When
        localCacheManager.runEviction();

        // Then - Should not throw exception
        assertTrue(localCacheManager.containKey(cacheKey));
        assertFalse(localCacheManager.containKey(cacheKey1));
    }
}
