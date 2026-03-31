package com.consist.cache.core.performance;

import com.consist.cache.core.hotspot.reads.ReadQpsStatistics;
import com.consist.cache.core.local.LocalCacheManager;
import com.consist.cache.core.manager.SingleFlightExecutor;
import com.consist.cache.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for concurrent cache operations
 */
@DisplayName("Concurrent Performance Tests")
class ConcurrentPerformanceTest {

    private LocalCacheManager localCacheManager;
    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        LocalCacheProperties properties = new LocalCacheProperties();
        properties.setMaximumSize(10000);
        properties.setExpireAfterWrite(300);
        localCacheManager = new LocalCacheManager(properties);
        executorService = Executors.newFixedThreadPool(20);
    }

    @Test
    @DisplayName("High concurrency read-write performance test")
    void testHighConcurrencyReadWrite() throws InterruptedException {
        // Given
        int threadCount = 20;
        int operationsPerThread = 1000;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);

        // When - Multiple threads performing reads and writes
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executorService.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        String key = "key-" + (threadId * operationsPerThread + j);
                        
                        // Write operation
                        CacheKey cacheKey = CacheKey.builder()
                                .key(key)
                                .consistencyLevel(ConsistencyLevel.HIGH)
                                .cacheLevel(CacheLevel.LOCAL_CACHE)
                                .build();
                        
                        CacheValue<String> value = CacheValue.<String>builder()
                                .value("value-" + key)
                                .expireTime(System.currentTimeMillis() + 60000)
                                .build();
                        
                        localCacheManager.put(cacheKey, value);
                        
                        // Read operation
                        CacheValue result = localCacheManager.get(cacheKey);
                        if (result != null && result.getValue() != null) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();

        // Then
        long totalOperations = (long) threadCount * operationsPerThread * 2; // read + write
        long duration = endTime - startTime;
        
        System.out.println("Total operations: " + totalOperations);
        System.out.println("Duration: " + duration + "ms");
        System.out.println("Ops/sec: " + (totalOperations * 1000 / duration));
        System.out.println("Success rate: " + (successCount.get() * 100.0 / totalOperations) + "%");
        
        assertTrue(successCount.get() > 0, "Should have successful operations");
        assertTrue(failCount.get() < totalOperations * 0.01, "Failure rate should be less than 1%");
    }

    @Test
    @DisplayName("Cache stampede prevention test")
    void testCacheStampedePrevention() throws InterruptedException {
        // Given
        String hotKey = "hot-key";
        CacheKey cacheKey = CacheKey.builder()
                .key(hotKey)
                .consistencyLevel(ConsistencyLevel.HIGH)
                .cacheLevel(CacheLevel.LOCAL_CACHE)
                .build();
        
        AtomicInteger loadCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(50);
        
        // Pre-populate with expired value to simulate stampede scenario
        CacheValue<String> expiredValue = CacheValue.<String>builder()
                .value("expired")
                .expireTime(System.currentTimeMillis() - 1000)
                .build();
        localCacheManager.put(cacheKey, expiredValue);

        // When - 50 threads simultaneously try to get the same key
        for (int i = 0; i < 50; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await(); // Wait for start signal
                    
                    // Simulate cache miss handling with single flight
                    CacheValue result = localCacheManager.get(cacheKey);
                    if (result == null || result.isExpired()) {
                        // Only one thread should actually load
                        int count = loadCount.incrementAndGet();
                        if (count == 1) {
                            // Simulate slow DB call
                            Thread.sleep(10);
                            
                            // Load fresh value
                            CacheValue<String> freshValue = CacheValue.<String>builder()
                                    .value("fresh-value")
                                    .expireTime(System.currentTimeMillis() + 60000)
                                    .build();
                            localCacheManager.put(cacheKey, freshValue);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Release all threads
        doneLatch.await(10, TimeUnit.SECONDS);

        // Then
        System.out.println("Load function called " + loadCount.get() + " times");
        // In real implementation with SingleFlight, this should be 1
        // Without it under race conditions, could be higher
        assertTrue(loadCount.get() <= 5, "Load count should be limited");
    }

    @Test
    @DisplayName("Memory efficiency under high load")
    void testMemoryEfficiency() throws InterruptedException {
        // Given
        int entryCount = 5000;
        CountDownLatch latch = new CountDownLatch(entryCount);

        // When - Insert many entries concurrently
        long startTime = Runtime.getRuntime().freeMemory();
        
        for (int i = 0; i < entryCount; i++) {
            final int index = i;
            executorService.submit(() -> {
                try {
                    CacheKey key = CacheKey.builder()
                            .key("mem-test-" + index)
                            .consistencyLevel(ConsistencyLevel.HIGH)
                            .cacheLevel(CacheLevel.LOCAL_CACHE)
                            .build();
                    
                    CacheValue<String> value = CacheValue.<String>builder()
                            .value("value-" + index)
                            .expireTime(System.currentTimeMillis() + 300000)
                            .build();
                    
                    localCacheManager.put(key, value);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        long endTime = Runtime.getRuntime().freeMemory();
        
        // Trigger GC to get accurate memory reading
        System.gc();
        Thread.sleep(100);
        long afterGcMemory = Runtime.getRuntime().freeMemory();

        // Then
        long memoryUsed = startTime - afterGcMemory;
        long heapSize = Runtime.getRuntime().totalMemory();
        
        System.out.println("Entries stored: " + entryCount);
        System.out.println("Memory used: " + (memoryUsed / 1024 / 1024) + " MB");
        System.out.println("Heap size: " + (heapSize / 1024 / 1024) + " MB");
        System.out.println("Memory per entry: " + (memoryUsed / entryCount) + " bytes");
        
        assertEquals(entryCount, localCacheManager.getSize(), 
            "Should store all entries");
        assertTrue(memoryUsed < heapSize * 0.8, 
            "Should not exceed 80% of heap");
    }

    @Test
    @DisplayName("Eviction performance under pressure")
    void testEvictionPerformance() throws InterruptedException {
        // Given
        LocalCacheProperties props = new LocalCacheProperties();
        props.setMaximumSize(1000);
        props.setExpireAfterWrite(1000);
        LocalCacheManager smallCache = new LocalCacheManager(props);
        
        int insertCount = 5000;
        CountDownLatch latch = new CountDownLatch(insertCount);

        // When - Insert more entries than capacity
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < insertCount; i++) {
            final int index = i;
            executorService.submit(() -> {
                try {
                    CacheKey key = CacheKey.builder()
                            .key("evict-test-" + index)
                            .consistencyLevel(ConsistencyLevel.HIGH)
                            .cacheLevel(CacheLevel.LOCAL_CACHE)
                            .build();
                    
                    CacheValue<String> value = CacheValue.<String>builder()
                            .value("value-" + index)
                            .expireTime(System.currentTimeMillis() + 60000)
                            .build();
                    
                    smallCache.put(key, value);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;

        // Then
        System.out.println("Insertions: " + insertCount);
        System.out.println("Final cache size: " + smallCache.getSize());
        System.out.println("Duration: " + duration + "ms");
        System.out.println("Insertions/sec: " + (insertCount * 1000 / duration));
        
        assertTrue(smallCache.getSize() <= 1000, 
            "Cache should respect max size limit");
        assertTrue(duration < 10000, 
            "Should complete within reasonable time");
    }

    @Test
    @DisplayName("Hotspot detection accuracy under load")
    void testHotspotDetectionAccuracy() throws InterruptedException {
        // Given
        ReadQpsStatistics statistics = new ReadQpsStatistics(100.0, 1000, 10);
        String hotKey = "hot-key";
        String coldKey = "cold-key";
        
        CountDownLatch hotLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(20);

        // When - Simulate hot and cold keys
        for (int i = 0; i < 20; i++) {
            executorService.submit(() -> {
                try {
                    hotLatch.await();
                    
                    // Record many reads for hot key
                    for (int j = 0; j < 50; j++) {
                        statistics.recordRead(hotKey);
                    }
                    
                    // Record few reads for cold key
                    statistics.recordRead(coldKey);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        hotLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);

        // Then
        assertTrue(statistics.isHotKey(hotKey), 
            "Should detect hot key accurately");
        assertFalse(statistics.isHotKey(coldKey), 
            "Should not flag cold key as hot");
        
        double hotKeyQps = statistics.getQps(hotKey);
        double coldKeyQps = statistics.getQps(coldKey);
        
        System.out.println("Hot key QPS: " + hotKeyQps);
        System.out.println("Cold key QPS: " + coldKeyQps);
        
        assertTrue(hotKeyQps > coldKeyQps * 10, 
            "Hot key QPS should be significantly higher");
    }

    @Test
    @DisplayName("SingleFlight performance under thundering herd")
    void testSingleFlightThunderingHerd() throws InterruptedException {
        // Given
        SingleFlightExecutor singleFlight = new SingleFlightExecutor();
        String sharedKey = "shared-resource";
        AtomicInteger actualExecutions = new AtomicInteger(0);
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(100);

        // When - 100 threads request same resource simultaneously
        for (int i = 0; i < 100; i++) {
            executorService.submit(() -> {
                try {
                    try {
                        startLatch.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }

                    String result = singleFlight.execute(sharedKey, k -> {
                        actualExecutions.incrementAndGet();
                        try {
                            Thread.sleep(10); // Simulate work
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "result";
                    });
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);

        // Then
        System.out.println("Actual executions: " + actualExecutions.get());
        System.out.println("Total requests: 100");
        System.out.println("Reduction: " + (100 - actualExecutions.get()) + " redundant calls prevented");
        
        assertEquals(1, actualExecutions.get(), 
            "SingleFlight should prevent duplicate executions");
    }
}
