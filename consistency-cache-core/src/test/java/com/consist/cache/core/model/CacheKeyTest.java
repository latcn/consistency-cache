package com.consist.cache.core.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CacheKey
 */
@DisplayName("CacheKey Tests")
class CacheKeyTest {

    @Test
    @DisplayName("Should create CacheKey with builder")
    void testBuilder() {
        // When
        CacheKey<String> cacheKey = CacheKey.<String>builder()
                .key("test-key")
                .expireTimeMs(60000)
                .cacheLevel(CacheLevel.LOCAL_CACHE)
                .consistencyLevel(ConsistencyLevel.HIGH)
                .build();

        // Then
        assertEquals("test-key", cacheKey.getKey());
        assertEquals(60000, cacheKey.getExpireTimeMs());
        assertEquals(CacheLevel.LOCAL_CACHE, cacheKey.getCacheLevel());
        assertEquals(ConsistencyLevel.HIGH, cacheKey.getConsistencyLevel());
    }

    @Test
    @DisplayName("Should create CacheKey with default values")
    void testDefaultValues() {
        // When
        CacheKey<String> cacheKey = CacheKey.<String>builder()
                .key("test-key")
                .build();

        // Then
        assertEquals("test-key", cacheKey.getKey());
        assertEquals(0, cacheKey.getExpireTimeMs());
        assertEquals(CacheLevel.ADAPTIVE_CACHE, cacheKey.getCacheLevel());
        assertEquals(ConsistencyLevel.HIGH, cacheKey.getConsistencyLevel());
    }

    @Test
    @DisplayName("Should support different key types")
    void testDifferentKeyTypes() {
        // Given
        CacheKey<Integer> intKey = CacheKey.<Integer>builder()
                .key(123)
                .build();
        
        CacheKey<Long> longKey = CacheKey.<Long>builder()
                .key(456789L)
                .build();
        
        CacheKey<String> stringKey = CacheKey.<String>builder()
                .key("string-key")
                .build();

        // Then
        assertEquals(123, intKey.getKey());
        assertEquals(456789L, longKey.getKey());
        assertEquals("string-key", stringKey.getKey());
    }

    @Test
    @DisplayName("Should handle equals and hashCode")
    void testEqualsAndHashCode() {
        // Given
        CacheKey<String> key1 = CacheKey.<String>builder()
                .key("same")
                .expireTimeMs(60000)
                .cacheLevel(CacheLevel.LOCAL_CACHE)
                .consistencyLevel(ConsistencyLevel.HIGH)
                .build();
        
        CacheKey<String> key2 = CacheKey.<String>builder()
                .key("same")
                .expireTimeMs(60000)
                .cacheLevel(CacheLevel.LOCAL_CACHE)
                .consistencyLevel(ConsistencyLevel.HIGH)
                .build();

        // Then
        assertEquals(key1, key2);
        assertEquals(key1.hashCode(), key2.hashCode());
    }

    @Test
    @DisplayName("Should handle toString")
    void testToString() {
        // Given
        CacheKey<String> cacheKey = CacheKey.<String>builder()
                .key("test")
                .expireTimeMs(60000)
                .cacheLevel(CacheLevel.LOCAL_CACHE)
                .consistencyLevel(ConsistencyLevel.HIGH)
                .build();

        // When
        String str = cacheKey.toString();

        // Then
        assertTrue(str.contains("key=test"));
        assertTrue(str.contains("expireTimeMs=60000"));
        assertTrue(str.contains("cacheLevel=LOCAL_CACHE"));
        assertTrue(str.contains("consistencyLevel=HIGH"));
    }
}
