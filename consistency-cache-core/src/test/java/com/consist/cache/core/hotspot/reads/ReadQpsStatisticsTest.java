package com.consist.cache.core.hotspot.reads;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ReadQpsStatistics
 */
@DisplayName("ReadQpsStatistics Tests")
class ReadQpsStatisticsTest {

    private ReadQpsStatistics statistics;

    @BeforeEach
    void setUp() {
        // Threshold: 100 QPS, Window: 1000ms, Buckets: 10
        statistics = new ReadQpsStatistics(100.0, 1000, 10);
    }

    @Test
    @DisplayName("Should record read operation")
    void testRecordRead() {
        // Given
        String key = "test-key";

        // When
        statistics.recordRead(key);

        // Then - Should not throw exception
        assertNotNull(statistics.getQps(key));
    }

    @Test
    @DisplayName("Should detect hot key when QPS exceeds threshold")
    void testHotKeyDetection() throws InterruptedException {
        // Given
        String hotKey = "hot-key";
        
        // Simulate high QPS by recording many reads quickly
        for (int i = 0; i < 200; i++) {
            statistics.recordRead(hotKey);
        }

        // Then
        assertTrue(statistics.isHotKey(hotKey));
    }

    @Test
    @DisplayName("Should not detect cold key as hot")
    void testColdKeyNotDetectedAsHot() {
        // Given
        String coldKey = "cold-key";
        
        // Record only a few reads
        for (int i = 0; i < 5; i++) {
            statistics.recordRead(coldKey);
        }

        // Then
        assertFalse(statistics.isHotKey(coldKey));
    }

    @Test
    @DisplayName("Should return current QPS for key")
    void testGetQps() throws InterruptedException {
        // Given
        String key = "qps-test-key";
        
        // Record 50 reads
        for (int i = 0; i < 50; i++) {
            statistics.recordRead(key);
        }

        // When
        double qps = statistics.getQps(key);

        // Then - Should have some QPS value
        assertTrue(qps > 0);
    }

    @Test
    @DisplayName("Should return 0 QPS for non-existent key")
    void testGetQpsNonExistentKey() {
        // When
        double qps = statistics.getQps("non-existent");

        // Then
        assertEquals(0.0, qps, 0.01);
    }

    @Test
    @DisplayName("Should cleanup old entries")
    void testCleanup() throws InterruptedException {
        // Given
        String oldKey = "old-key";
        statistics.recordRead(oldKey);
        
        // Wait for entry to become old (but we'll manually trigger cleanup)
        Thread.sleep(100);

        // When
        statistics.cleanup();

        // Then - Should not throw exception
        // Note: Actual cleanup depends on lastAccessTime which is 5 minutes
        // This test mainly ensures the method doesn't crash
    }

    @Test
    @DisplayName("Should handle sliding window rotation")
    void testSlidingWindowRotation() throws InterruptedException {
        // Given
        String key = "rotation-test";
        
        // Record reads in first bucket
        for (int i = 0; i < 50; i++) {
            statistics.recordRead(key);
        }
        
        double initialQps = statistics.getQps(key);
        
        // Wait for buckets to rotate (each bucket is 100ms)
        Thread.sleep(150);
        
        // Record more reads
        for (int i = 0; i < 30; i++) {
            statistics.recordRead(key);
        }
        
        double laterQps = statistics.getQps(key);

        // Then
        assertTrue(initialQps > 0);
        assertTrue(laterQps > 0);
    }

    @Test
    @DisplayName("Should handle multiple keys independently")
    void testMultipleKeys() {
        // Given
        String key1 = "key-1";
        String key2 = "key-2";
        
        // Record different amounts for each key
        for (int i = 0; i < 100; i++) {
            statistics.recordRead(key1);
        }
        
        for (int i = 0; i < 10; i++) {
            statistics.recordRead(key2);
        }

        // Then
        assertTrue(statistics.isHotKey(key1));
        assertFalse(statistics.isHotKey(key2));
        assertTrue(statistics.getQps(key1) > statistics.getQps(key2));
    }

    @Test
    @DisplayName("Should reset counters after full window expiration")
    void testCounterReset() throws InterruptedException {
        // Given
        String key = "reset-test";
        
        // Record reads
        for (int i = 0; i < 200; i++) {
            statistics.recordRead(key);
        }
        
        assertTrue(statistics.isHotKey(key));
        
        // Wait for full window to expire (window is 1000ms)
        Thread.sleep(1100);
        
        // Access again to trigger rotation
        statistics.getQps(key);

        // Then - QPS should be much lower or zero
        // Note: May not be exactly zero due to timing
        double qps = statistics.getQps(key);
        assertTrue(qps < 100); // Should be below threshold now
    }

    @Test
    @DisplayName("Should use constructor parameters correctly")
    void testConstructorParameters() {
        // Given
        ReadQpsStatistics customStats = new ReadQpsStatistics(50.0, 2000, 20);
        
        // Then
        assertEquals(2000, customStats.getWindowSizeMs());
    }

    @Test
    @DisplayName("Should handle concurrent access safely")
    void testConcurrentAccess() throws InterruptedException {
        // Given
        String sharedKey = "concurrent-key";
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        
        // When - Multiple threads recording reads simultaneously
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    statistics.recordRead(sharedKey);
                }
            });
            threads[i].start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Then - Should not throw exception and QPS should be recorded
        assertTrue(statistics.getQps(sharedKey) > 0);
    }
}
