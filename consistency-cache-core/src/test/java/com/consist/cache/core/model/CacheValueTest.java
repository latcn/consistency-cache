package com.consist.cache.core.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CacheValue
 */
@DisplayName("CacheValue Tests")
class CacheValueTest {

    @Test
    @DisplayName("Should create CacheValue with default values")
    void testDefaultConstructor() {
        // When
        CacheValue<String> cacheValue = new CacheValue<>();

        // Then
        assertNotNull(cacheValue);
        assertEquals(CacheValue.MAX_EXPIRE_TIME, cacheValue.getExpireTime());
        assertEquals(0, cacheValue.getCreatedAt());
        assertEquals(1.0, cacheValue.getWeight(), 0.01);
    }

    @Test
    @DisplayName("Should create CacheValue with builder")
    void testBuilder() {
        // Given
        long expireTime = System.currentTimeMillis() + 60000;
        long createdAt = System.currentTimeMillis();

        // When
        CacheValue<String> cacheValue = CacheValue.<String>builder()
                .value("test-value")
                .expireTime(expireTime)
                .createdAt(createdAt)
                .weight(2.0)
                .build();

        // Then
        assertEquals("test-value", cacheValue.getValue());
        assertEquals(expireTime, cacheValue.getExpireTime());
        assertEquals(createdAt, cacheValue.getCreatedAt());
        assertEquals(2.0, cacheValue.getWeight(), 0.01);
    }

    @Test
    @DisplayName("Should check if cache value is not expired")
    void testIsNotExpired() {
        // Given
        CacheValue<String> cacheValue = CacheValue.<String>builder()
                .value("valid")
                .expireTime(System.currentTimeMillis() + 60000)
                .build();

        // Then
        assertFalse(cacheValue.isExpired());
    }

    @Test
    @DisplayName("Should check if cache value is expired")
    void testIsExpired() {
        // Given
        CacheValue<String> cacheValue = CacheValue.<String>builder()
                .value("expired")
                .expireTime(System.currentTimeMillis() - 1000)
                .build();

        // Then
        assertTrue(cacheValue.isExpired());
    }

    @Test
    @DisplayName("Should check if value is null")
    void testNotExist() {
        // Given
        CacheValue<String> cacheValue1 = CacheValue.<String>builder()
                .value(null)
                .build();
        
        CacheValue<String> cacheValue2 = CacheValue.<String>builder()
                .value("present")
                .build();

        // Then
        assertTrue(cacheValue1.notExist());
        assertFalse(cacheValue2.notExist());
    }

    @Test
    @DisplayName("Should calculate TTL correctly")
    void testGetTtl() {
        // Given
        long createdAt = System.currentTimeMillis();
        long expireTime = createdAt + 30000; // 30 seconds

        CacheValue<String> cacheValue = CacheValue.<String>builder()
                .value("test")
                .createdAt(createdAt)
                .expireTime(expireTime)
                .build();

        // When
        long ttl = cacheValue.getTtl();

        // Then
        assertEquals(30000, ttl, 100); // Allow 100ms tolerance
    }

    @Test
    @DisplayName("Should extract value from CacheValue wrapper")
    void testExtractValueFromCacheValue() {
        // Given
        CacheValue<String> cacheValue = CacheValue.<String>builder()
                .value("wrapped-value")
                .expireTime(System.currentTimeMillis() + 60000)
                .build();

        // When
        String extracted = CacheValue.extractValue(cacheValue);

        // Then
        assertEquals("wrapped-value", extracted);
    }

    @Test
    @DisplayName("Should return null when extracting from expired CacheValue")
    void testExtractValueFromExpiredCacheValue() {
        // Given
        CacheValue<String> cacheValue = CacheValue.<String>builder()
                .value("expired-value")
                .expireTime(System.currentTimeMillis() - 1000)
                .build();

        // When
        String extracted = CacheValue.extractValue(cacheValue);

        // Then
        assertNull(extracted);
    }

    @Test
    @DisplayName("Should return null when extracting from CacheValue with null value")
    void testExtractValueWithNullValue() {
        // Given
        CacheValue<String> cacheValue = CacheValue.<String>builder()
                .value(null)
                .expireTime(System.currentTimeMillis() + 60000)
                .build();

        // When
        String extracted = CacheValue.extractValue(cacheValue);

        // Then
        assertNull(extracted);
    }

    @Test
    @DisplayName("Should extract value directly if not CacheValue")
    void testExtractValueNonCacheValue() {
        // Given
        String directValue = "direct-value";

        // When
        String extracted = CacheValue.extractValue(directValue);

        // Then
        assertEquals("direct-value", extracted);
    }

    @Test
    @DisplayName("Should handle null input in extractValue")
    void testExtractValueNull() {
        // When
        String extracted = CacheValue.extractValue(null);

        // Then
        assertNull(extracted);
    }

    @Test
    @DisplayName("Should generate proper toString representation")
    void testToString() {
        // Given
        CacheValue<String> cacheValue = CacheValue.<String>builder()
                .value("test")
                .expireTime(1234567890L)
                .createdAt(1234567800L)
                .weight(1.5)
                .build();

        // When
        String str = cacheValue.toString();

        // Then
        assertTrue(str.contains("value=test"));
        assertTrue(str.contains("expireTime=1234567890"));
        assertTrue(str.contains("createdAt=1234567800"));
        assertTrue(str.contains("weight=1.5"));
    }
}
