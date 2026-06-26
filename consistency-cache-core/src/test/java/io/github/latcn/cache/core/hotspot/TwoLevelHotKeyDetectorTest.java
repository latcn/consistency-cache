package io.github.latcn.cache.core.hotspot;

import static org.junit.jupiter.api.Assertions.*;

import io.github.latcn.cache.core.exception.CacheException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TwoLevelHotKeyDetector Tests")
class TwoLevelHotKeyDetectorTest {

    private TwoLevelHotKeyDetector detector;

    @BeforeEach
    void setUp() {
        detector = TwoLevelHotKeyDetector.Builder.forHighQps()
                .hotKeyThreshold(100)
                .promotionThreshold(70)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (detector != null) {
            detector.close();
        }
    }

    @Test
    @DisplayName("Should detect hot key when count exceeds threshold")
    void testHotKeyDetection() {
        String hotKey = "hot-key";
        
        for (int i = 0; i < 200; i++) {
            detector.record(hotKey);
        }
        
        assertTrue(detector.isHotKey(hotKey), "Key with high count should be detected as hot");
    }

    @Test
    @DisplayName("Should not detect cold key as hot")
    void testColdKeyNotDetected() {
        String coldKey = "cold-key";
        
        for (int i = 0; i < 50; i++) {
            detector.record(coldKey);
        }
        
        assertFalse(detector.isHotKey(coldKey), "Key with low count should not be detected as hot");
    }

    @Test
    @DisplayName("Should promote key from CMS to exact counter")
    void testKeyPromotion() {
        String key = "promotion-key";
        long initialPromoted = detector.getPromotedCount();
        
        for (int i = 0; i < 200; i++) {
            detector.record(key);
        }
        
        assertTrue(detector.getPromotedCount() > initialPromoted, "Key should be promoted to exact counter");
    }

    //@Test
    @DisplayName("Should decay counts over time")
    void testDecay() throws InterruptedException {
        String key = "decay-key";
        
        for (int i = 0; i < 200; i++) {
            detector.record(key);
        }
        
        assertTrue(detector.isHotKey(key));
        
        Thread.sleep(30000);
        
        assertFalse(detector.isHotKey(key), "Key should no longer be hot after decay");
    }

    @Test
    @DisplayName("Should handle forced cleanup when capacity exceeded")
    void testForceCleanup() {
        TwoLevelHotKeyDetector smallDetector = TwoLevelHotKeyDetector.Builder.forHighQps()
                .hotKeyThreshold(10)
                .promotionThreshold(7)
                .maxExactSize(10)
                .build();
        
        try {
            for (int i = 0; i < 20; i++) {
                String key = "key-" + i;
                for (int j = 0; j < 20; j++) {
                    smallDetector.record(key);
                }
            }
            
            assertTrue(smallDetector.getForceCleanupCount() >= 1, "Force cleanup should have been triggered");
            assertTrue(smallDetector.getEvictedCount() > 0, "Some keys should have been evicted");
        } finally {
            smallDetector.close();
        }
    }

    @Test
    @DisplayName("Should respect force cleanup cooldown")
    void testCleanupCooldown() throws InterruptedException {
        TwoLevelHotKeyDetector smallDetector = TwoLevelHotKeyDetector.Builder.forHighQps()
                .hotKeyThreshold(10)
                .promotionThreshold(7)
                .maxExactSize(5)
                .forceCleanupCooldownMs(5000)
                .build();
        
        try {
            for (int i = 0; i < 20; i++) {
                String key = "key-" + i;
                for (int j = 0; j < 20; j++) {
                    smallDetector.record(key);
                }
            }
            
            long firstCleanupCount = smallDetector.getForceCleanupCount();
            
            for (int i = 0; i < 20; i++) {
                String key = "key-new-" + i;
                for (int j = 0; j < 20; j++) {
                    smallDetector.record(key);
                }
            }
            
            assertTrue(smallDetector.getForceCleanupSkipCooldown() > 0, 
                    "Some cleanup attempts should have been skipped due to cooldown");
        } finally {
            smallDetector.close();
        }
    }

    @Test
    @DisplayName("Should handle concurrent promotions safely")
    void testConcurrentPromotions() throws InterruptedException {
        String key = "concurrent-promotion-key";
        int threadCount = 10;
        int writesPerThread = 100;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
            executor.execute(() -> {
                for (int j = 0; j < writesPerThread; j++) {
                    detector.record(key);
                }
                latch.countDown();
            });
        }
        
        latch.await();
        executor.shutdown();
        
        assertTrue(detector.isHotKey(key), "Key should be detected as hot after concurrent writes");
    }

    @Test
    @DisplayName("Should handle concurrent updates to exact counter")
    void testConcurrentExactCounterUpdates() throws InterruptedException {
        String key = "concurrent-update-key";
        
        for (int i = 0; i < 100; i++) {
            detector.record(key);
        }
        
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
            executor.execute(() -> {
                for (int j = 0; j < 100; j++) {
                    detector.record(key);
                }
                latch.countDown();
            });
        }
        
        latch.await();
        executor.shutdown();
        
        assertTrue(detector.isHotKey(key), "Key should still be hot after concurrent updates");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for invalid thresholds")
    void testInvalidThresholds() {
        assertThrows(CacheException.class, () ->
                TwoLevelHotKeyDetector.Builder.forHighQps()
                        .hotKeyThreshold(100)
                        .promotionThreshold(100)
                        .build());
        
        assertThrows(CacheException.class, () ->
                TwoLevelHotKeyDetector.Builder.forHighQps()
                        .hotKeyThreshold(100)
                        .promotionThreshold(150)
                        .build());
        
        assertThrows(CacheException.class, () ->
                TwoLevelHotKeyDetector.Builder.forHighQps()
                        .hotKeyThreshold(-1)
                        .promotionThreshold(50)
                        .build());
        
        assertThrows(CacheException.class, () ->
                TwoLevelHotKeyDetector.Builder.forHighQps()
                        .hotKeyThreshold(100)
                        .promotionThreshold(-1)
                        .build());
    }

    @Test
    @DisplayName("Should throw NullPointerException for null key")
    void testNullKey() {
        assertThrows(CacheException.class, () -> detector.record(null));
        assertThrows(CacheException.class, () -> detector.isHotKey(null));
    }

    @Test
    @DisplayName("Should throw IllegalStateException after close")
    void testOperationsAfterClose() {
        detector.close();
        assertThrows(CacheException.class, () -> detector.record("test"));
        assertThrows(CacheException.class, () -> detector.isHotKey("test"));
    }

    @Test
    @DisplayName("Should handle dynamic threshold adjustment")
    void testDynamicThresholdAdjustment() {
        String key = "dynamic-threshold-key";
        
        for (int i = 0; i < 200; i++) {
            detector.record(key);
        }
        
        assertTrue(detector.isHotKey(key));
        
        detector.setHotKeyThreshold(200);
        
        assertFalse(detector.isHotKey(key), "Key should no longer be hot after threshold increase");
        
        detector.setHotKeyThreshold(100);
        
        assertTrue(detector.isHotKey(key), "Key should be hot again after threshold decrease");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for invalid dynamic thresholds")
    void testInvalidDynamicThresholds() {
        assertThrows(CacheException.class, () ->
                detector.setPromotionThreshold(150));
        
        assertThrows(CacheException.class, () ->
                detector.setPromotionThreshold(-1));
        
        assertThrows(CacheException.class, () ->
                detector.setHotKeyThreshold(50));
        
        assertThrows(CacheException.class, () ->
                detector.setHotKeyThreshold(-1));
    }

    @Test
    @DisplayName("Should update hot hit and miss counts")
    void testHotHitMissCounts() {
        String hotKey = "hot-count-key";
        String coldKey = "cold-count-key";
        
        for (int i = 0; i < 200; i++) {
            detector.record(hotKey);
        }
        
        detector.isHotKey(hotKey);
        detector.isHotKey(coldKey);
        
        assertTrue(detector.getHotHitCount() >= 1, "Hot hit count should be incremented");
        assertTrue(detector.getHotMissCount() >= 1, "Hot miss count should be incremented");
    }

    @Test
    @DisplayName("Should return correct exact size")
    void testExactSize() {
        String key1 = "size-key-1";
        String key2 = "size-key-2";
        
        for (int i = 0; i < 200; i++) {
            detector.record(key1);
            detector.record(key2);
        }
        
        assertTrue(detector.getExactSize() >= 2, "Exact counter should contain both keys");
    }

    @Test
    @DisplayName("Should handle cleanup during concurrent access")
    void testCleanupDuringAccess() throws InterruptedException {
        String key = "cleanup-access-key";
        
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch latch = new CountDownLatch(3);
        
        executor.execute(() -> {
            for (int i = 0; i < 1000; i++) {
                detector.record(key);
            }
            latch.countDown();
        });
        
        executor.execute(() -> {
            for (int i = 0; i < 100; i++) {
                detector.isHotKey(key);
            }
            latch.countDown();
        });
        
        executor.execute(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            latch.countDown();
        });
        
        latch.await();
        executor.shutdown();
        
        assertTrue(detector.isHotKey(key));
    }

    @Test
    @DisplayName("Should handle promotion when exact counter is full")
    void testPromotionWhenFull() {
        TwoLevelHotKeyDetector smallDetector = TwoLevelHotKeyDetector.Builder.forHighQps()
                .hotKeyThreshold(10)
                .promotionThreshold(7)
                .maxExactSize(2)
                .build();
        
        try {
            for (int i = 0; i < 3; i++) {
                String key = "full-key-" + i;
                for (int j = 0; j < 20; j++) {
                    smallDetector.record(key);
                }
            }
            
            assertTrue(smallDetector.getExactSize() <= 2, "Exact counter should not exceed max size");
        } finally {
            smallDetector.close();
        }
    }

    //@Test
    @DisplayName("Should reset count after long idle period")
    void testLongIdleReset() throws InterruptedException {
        String key = "idle-reset-key";
        
        for (int i = 0; i < 200; i++) {
            detector.record(key);
        }
        
        assertTrue(detector.isHotKey(key));
        
        Thread.sleep(60000);
        
        assertFalse(detector.isHotKey(key), "Key should not be hot after long idle");
    }

    @Test
    @DisplayName("Should correctly close all resources")
    void testCloseResources() {
        detector.close();
        
        assertThrows(CacheException.class, () -> detector.record("test"));
        assertThrows(CacheException.class, () -> detector.isHotKey("test"));
    }

    @Test
    @DisplayName("Should handle multiple keys independently")
    void testMultipleKeysIndependent() {
        String hotKey = "multi-hot-key";
        String coldKey = "multi-cold-key";
        
        for (int i = 0; i < 200; i++) {
            detector.record(hotKey);
        }
        
        for (int i = 0; i < 50; i++) {
            detector.record(coldKey);
        }
        
        assertTrue(detector.isHotKey(hotKey));
        assertFalse(detector.isHotKey(coldKey));
    }

}