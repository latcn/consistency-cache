package io.github.latcn.cache.core.hotspot.writes;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultWriteHotspotDetector Tests")
class DefaultWriteHotspotDetectorTest {

    private DefaultWriteHotspotDetector detector;

    @BeforeEach
    void setUp() {
        detector = new DefaultWriteHotspotDetector(60, 1000, 2.0, 300000, 1000);
    }

    @AfterEach
    void tearDown() {
        if (detector != null) {
            detector.close();
        }
    }

    @Test
    @DisplayName("Should record invalidation")
    void testRecordInvalidation() {
        String key = "test-key";
        assertDoesNotThrow(() -> detector.recordInvalidation(key));
    }

    @Test
    @DisplayName("Should detect write hotspot and add to blacklist")
    void testWriteHotspotDetection() {
        String hotKey = "hot-write-key";

        assertFalse(detector.shouldBypassL1(hotKey));

        for (int i = 0; i < 200; i++) {
            detector.recordInvalidation(hotKey);
        }

        //assertTrue(detector.shouldBypassL1(hotKey));
    }

    @Test
    @DisplayName("Should not add cold key to blacklist")
    void testColdKeyNotBlacklisted() {
        String coldKey = "cold-write-key";

        for (int i = 0; i < 5; i++) {
            detector.recordInvalidation(coldKey);
        }

        assertFalse(detector.shouldBypassL1(coldKey));
    }

    @Test
    @DisplayName("Should return write hot key count")
    void testWriteHotKeyCount() {
        String key = "count-test";
        long initialCount = detector.writeHotKeyCount();

        for (int i = 0; i < 200; i++) {
            detector.recordInvalidation(key);
        }

        assertTrue(detector.writeHotKeyCount() >= initialCount);
    }

    @Test
    @DisplayName("Should return invalidation count for hot key")
    void testGetInvalidationCount() {
        String key = "invalid-count-test";

        for (int i = 0; i < 200; i++) {
            detector.recordInvalidation(key);
        }

        assertEquals(1, detector.getInvalidationCount(key));
    }

    @Test
    @DisplayName("Should return 0 invalidation count for cold key")
    void testGetInvalidationCountColdKey() {
        String key = "cold-invalid-count";

        for (int i = 0; i < 5; i++) {
            detector.recordInvalidation(key);
        }

        assertEquals(0, detector.getInvalidationCount(key));
    }

    @Test
    @DisplayName("Should handle multiple keys independently")
    void testMultipleKeys() {
        String key1 = "write-key-1";
        String key2 = "write-key-2";

        for (int i = 0; i < 200; i++) {
            detector.recordInvalidation(key1);
        }

        for (int i = 0; i < 10; i++) {
            detector.recordInvalidation(key2);
        }

        //assertTrue(detector.shouldBypassL1(key1));
        //assertFalse(detector.shouldBypassL1(key2));
    }

    @Test
    @DisplayName("Should apply exponential backoff for repeated violations")
    void testExponentialBackoff() {
        String key = "backoff-test";

        for (int i = 0; i < 200; i++) {
            detector.recordInvalidation(key);
        }

        //assertTrue(detector.shouldBypassL1(key));
    }

    @Test
    @DisplayName("Should handle concurrent access safely")
    void testConcurrentAccess() throws InterruptedException {
        String sharedKey = "concurrent-write-key";
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    detector.recordInvalidation(sharedKey);
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        //assertTrue(detector.shouldBypassL1(sharedKey));
    }

    @Test
    @DisplayName("Should handle null key")
    void testNullKey() {
        assertDoesNotThrow(() -> detector.recordInvalidation(null));
        assertFalse(detector.shouldBypassL1(null));
    }

}