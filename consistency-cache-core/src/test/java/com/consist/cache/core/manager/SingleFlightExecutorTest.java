package com.consist.cache.core.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SingleFlightExecutor
 */
@DisplayName("SingleFlightExecutor Tests")
class SingleFlightExecutorTest {

    private SingleFlightExecutor singleFlightExecutor;

    @BeforeEach
    void setUp() {
        singleFlightExecutor = new SingleFlightExecutor();
    }

    @Test
    @DisplayName("Should execute function only once for concurrent requests with same key")
    void testSingleFlightConcurrent() throws InterruptedException {
        // Given
        String key = "shared-key";
        AtomicInteger executionCount = new AtomicInteger(0);
        
        Function<String, String> loader = k -> {
            executionCount.incrementAndGet();
            try {
                Thread.sleep(100); // Simulate slow operation
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "result-for-" + k;
        };

        // When - Multiple threads request the same key concurrently
        int threadCount = 5;
        Thread[] threads = new Thread[threadCount];
        CountDownLatch latch = new CountDownLatch(threadCount);
        String[] results = new String[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    results[index] = singleFlightExecutor.execute(key, loader);
                } finally {
                    latch.countDown();
                }
            });
            threads[i].start();
        }

        // Wait for all threads to complete
        latch.await();

        // Then
        assertEquals(1, executionCount.get(), "Function should only execute once");
        for (String result : results) {
            assertEquals("result-for-shared-key", result);
        }
    }

    @Test
    @DisplayName("Should execute function separately for different keys")
    void testDifferentKeys() {
        // Given
        AtomicInteger executionCount = new AtomicInteger(0);
        
        Function<String, String> loader = k -> {
            executionCount.incrementAndGet();
            return "result-for-" + k;
        };

        // When
        String result1 = singleFlightExecutor.execute("key1", loader);
        String result2 = singleFlightExecutor.execute("key2", loader);

        // Then
        assertEquals(2, executionCount.get());
        assertEquals("result-for-key1", result1);
        assertEquals("result-for-key2", result2);
    }

    @Test
    @DisplayName("Should handle sequential requests for same key")
    void testSequentialRequests() {
        // Given
        AtomicInteger executionCount = new AtomicInteger(0);
        
        Function<String, String> loader = k -> {
            executionCount.incrementAndGet();
            return "result-" + executionCount.get();
        };

        // When
        String result1 = singleFlightExecutor.execute("key", loader);
        String result2 = singleFlightExecutor.execute("key", loader);

        // Then
        assertEquals(2, executionCount.get(), "Each sequential request should execute the function");
        assertEquals("result-1", result1);
        assertEquals("result-2", result2);
    }

    @Test
    @DisplayName("Should propagate exceptions from loader function")
    void testExceptionPropagation() {
        // Given
        Function<String, String> failingLoader = k -> {
            throw new RuntimeException("Test exception");
        };

        // When/Then
        assertThrows(RuntimeException.class, () -> {
            singleFlightExecutor.execute("failing-key", failingLoader);
        });
    }

    @Test
    @DisplayName("Should clean up inflight calls after completion")
    void testCleanupAfterCompletion() throws Exception {
        // Given
        String key = "cleanup-test";
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(1);
        
        Function<String, String> loader = k -> {
            try {
                startLatch.countDown();
                endLatch.await(); // Wait until signaled
                return "result";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        };

        // When - Start async execution
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> 
            singleFlightExecutor.execute(key, loader)
        );

        // Wait for execution to start
        startLatch.await();
        
        // The inflight call should exist now
        // (We can't directly verify this as inflightCalls is private)
        
        // Signal completion
        endLatch.countDown();
        future.get();

        // Then - Should complete without hanging
        assertTrue(future.isDone());
        assertEquals("result", future.get());
    }

    @Test
    @DisplayName("Should handle interruption gracefully")
    void testInterruption() throws InterruptedException {
        // Given
        String key = "interrupt-test";
        CountDownLatch startLatch = new CountDownLatch(1);
        
        Function<String, String> slowLoader = k -> {
            startLatch.countDown();
            try {
                Thread.sleep(10000); // Long sleep
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return "result";
        };

        // When - Start async execution and interrupt
        Thread workerThread = new Thread(() -> {
            try {
                singleFlightExecutor.execute(key, slowLoader);
            } catch (RuntimeException e) {
                // Expected
            }
        });
        
        workerThread.start();
        startLatch.await(); // Wait for execution to start
        workerThread.interrupt();
        workerThread.join(1000); // Wait for thread to finish

        // Then
        assertFalse(workerThread.isAlive(), "Thread should have finished");
    }

    @Test
    @DisplayName("Should handle null values from loader")
    void testNullValue() {
        // Given
        Function<String, String> nullLoader = k -> null;

        // When
        String result = singleFlightExecutor.execute("null-key", nullLoader);

        // Then
        assertNull(result);
    }

    @Test
    @DisplayName("Should support different value types")
    void testDifferentValueTypes() {
        // Given
        Function<String, Integer> intLoader = k -> 42;
        Function<String, String> stringLoader = k -> "hello";

        // When
        Integer intValue = singleFlightExecutor.execute("int-key", intLoader);
        String stringValue = singleFlightExecutor.execute("string-key", stringLoader);

        // Then
        assertEquals(42, intValue);
        assertEquals("hello", stringValue);
    }

    @Test
    @DisplayName("Should prevent cache stampede under high concurrency")
    void testCacheStampedePrevention() throws InterruptedException {
        // Given
        String hotKey = "hot-key";
        AtomicInteger actualExecutions = new AtomicInteger(0);
        AtomicInteger totalRequests = new AtomicInteger(0);
        
        Function<String, String> loader = k -> {
            actualExecutions.incrementAndGet();
            try {
                Thread.sleep(50); // Simulate database call
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "cached-value";
        };

        int concurrentThreads = 20;
        Thread[] threads = new Thread[concurrentThreads];
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrentThreads);

        // When - All threads start simultaneously
        for (int i = 0; i < concurrentThreads; i++) {
            threads[i] = new Thread(() -> {
                try {
                    startLatch.await(); // Wait for signal to start
                    totalRequests.incrementAndGet();
                    singleFlightExecutor.execute(hotKey, loader);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
            threads[i].start();
        }

        // Release all threads at once
        startLatch.countDown();
        doneLatch.await(2, TimeUnit.SECONDS);

        // Then - Only one execution should occur despite many concurrent requests
        assertEquals(concurrentThreads, totalRequests.get());
        assertEquals(1, actualExecutions.get(), 
            "SingleFlight should prevent cache stampede by executing only once");
    }
}
