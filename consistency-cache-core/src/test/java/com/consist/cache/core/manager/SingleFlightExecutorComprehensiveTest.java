package com.consist.cache.core.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for SingleFlightExecutor improvements.
 */
@DisplayName("SingleFlightExecutor Comprehensive Tests")
class SingleFlightExecutorComprehensiveTest {

    private SingleFlightExecutor singleFlightExecutor;

    @BeforeEach
    void setUp() {
        singleFlightExecutor = new SingleFlightExecutor();
    }

    @Test
    @DisplayName("Should prevent race condition in leader election")
    void testLeaderElectionRaceCondition() throws InterruptedException {
        // Given
        String key = "race-test";
        AtomicInteger executionCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(3);
        
        Function<String, String> slowLoader = k -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executionCount.incrementAndGet();
            return "result";
        };

        // When - 3 threads start simultaneously
        for (int i = 0; i < 3; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    singleFlightExecutor.execute(key, slowLoader);
                } catch (Exception e) {
                    fail("Unexpected exception: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await(2, TimeUnit.SECONDS);

        // Then - Only one execution despite race
        assertEquals(1, executionCount.get(), 
            "Should execute only once despite concurrent access");
    }

    @Test
    @DisplayName("Should handle rapid successive calls correctly")
    void testRapidSuccessiveCalls() throws Exception {
        // Given
        String key = "rapid-test";
        AtomicInteger count = new AtomicInteger(0);
        
        Function<String, String> loader = k -> {
            count.incrementAndGet();
            return "value-" + count.get();
        };

        // When - First call completes before second starts
        String result1 = singleFlightExecutor.execute(key, loader);
        
        // Wait for cleanup
        Thread.sleep(50);
        
        String result2 = singleFlightExecutor.execute(key, loader);

        // Then - Should execute twice since first completed
        assertEquals(2, count.get());
        assertEquals("value-1", result1);
        assertEquals("value-2", result2);
    }

    @Test
    @DisplayName("Should preserve exception types through waitForResult")
    void testExceptionTypePreservation() {
        // Given
        String key = "exception-test";
        
        // When/Then - RuntimeException should be preserved
        CustomRuntimeException thrown = assertThrows(
            CustomRuntimeException.class,
            () -> singleFlightExecutor.execute(key, k -> {
                throw new CustomRuntimeException("test-error");
            })
        );
        
        assertEquals("test-error", thrown.getMessage());
    }

    @Test
    @DisplayName("Should provide informative interrupt message")
    void testInterruptMessage() throws Exception {
        // Given
        String key = "interrupt-test";
        CountDownLatch started = new CountDownLatch(1);
        Exception[] caught = new Exception[1];
        
        Thread thread = new Thread(() -> {
            try {
                singleFlightExecutor.execute(key, k -> {
                    started.countDown();
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    return null;
                });
            } catch (RuntimeException e) {
                caught[0] = e;
            }
        });

        // When
        thread.start();
        started.await();
        Thread.sleep(50);
        thread.interrupt();
        thread.join(1000);

        // Then
        assertNotNull(caught[0]);
        assertTrue(caught[0].getMessage().contains("interrupted"));
    }

    @Test
    @DisplayName("Should unwrap ExecutionException cause")
    void testExecutionExceptionUnwrap() {
        // Given
        String key = "unwrap-test";
        
        // When
        RuntimeException thrown = assertThrows(
            RuntimeException.class,
            () -> singleFlightExecutor.execute(key, k -> {
                throw new IllegalStateException("wrapped-cause");
            })
        );

        // Then - Should get original cause, not wrapped again
        assertTrue(thrown instanceof IllegalStateException);
        assertEquals("wrapped-cause", thrown.getMessage());
    }

    @Test
    @DisplayName("Should handle Error types correctly")
    void testErrorHandling() {
        // Given
        String key = "error-test";
        
        // When/Then - AssertionError should propagate
        AssertionError thrown = assertThrows(
            AssertionError.class,
            () -> singleFlightExecutor.execute(key, k -> {
                throw new AssertionError("assertion-failed");
            })
        );
        
        assertEquals("assertion-failed", thrown.getMessage());
    }

    @Test
    @DisplayName("Should cleanup map after successful execution")
    void testCleanupAfterSuccess() throws Exception {
        // Given
        String key = "cleanup-success";
        AtomicInteger executions = new AtomicInteger(0);
        
        Function<String, String> loader = k -> {
            executions.incrementAndGet();
            return "success";
        };

        // When
        String result = singleFlightExecutor.execute(key, loader);
        Thread.sleep(150); // Allow cleanup
        
        // Execute again - should create new future
        String result2 = singleFlightExecutor.execute(key, loader);

        // Then
        assertEquals("success", result);
        assertEquals("success", result2);
        assertEquals(2, executions.get());
    }

    @Test
    @DisplayName("Should cleanup map after failed execution")
    void testCleanupAfterFailure() {
        // Given
        String key = "cleanup-failure";
        
        // When - First execution fails
        assertThrows(CustomRuntimeException.class, () -> {
            singleFlightExecutor.execute(key, k -> {
                throw new CustomRuntimeException("fail");
            });
        });

        // Then - Second execution should work (map was cleaned up)
        String result = singleFlightExecutor.execute(key, k -> "recovered");
        assertEquals("recovered", result);
    }

    @Test
    @DisplayName("Should handle multiple keys concurrently")
    void testMultipleKeysConcurrency() throws InterruptedException {
        // Given
        int keyCount = 10;
        int threadsPerKey = 5;
        CountDownLatch latch = new CountDownLatch(keyCount * threadsPerKey);
        AtomicInteger[] executionCounts = new AtomicInteger[keyCount];
        
        for (int i = 0; i < keyCount; i++) {
            executionCounts[i] = new AtomicInteger(0);
        }

        // When
        for (int i = 0; i < keyCount; i++) {
            final int keyIndex = i;
            String key = "key-" + keyIndex;
            
            for (int j = 0; j < threadsPerKey; j++) {
                new Thread(() -> {
                    try {
                        singleFlightExecutor.execute(key, k -> {
                            executionCounts[keyIndex].incrementAndGet();
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return "value-" + keyIndex;
                        });
                    } catch (Exception e) {
                        fail("Unexpected exception: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }
        }

        latch.await(5, TimeUnit.SECONDS);

        // Then - Each key should execute exactly once
        for (int i = 0; i < keyCount; i++) {
            assertEquals(1, executionCounts[i].get(), 
                "Key " + i + " should execute only once");
        }
    }

    @Test
    @DisplayName("Should maintain type safety with generics")
    void testGenericTypesafety() {
        // Given
        String stringKey = "string-key";
        Integer integerKey = 123;
        
        // When - Different generic types
        String stringValue = singleFlightExecutor.execute(stringKey, k -> "hello");
        Integer integerValue = singleFlightExecutor.execute(integerKey, k -> 42);

        // Then - Type-safe without warnings
        assertEquals("hello", stringValue);
        assertEquals(Integer.valueOf(42), integerValue);
    }

    /**
     * Custom RuntimeException for testing.
     */
    static class CustomRuntimeException extends RuntimeException {
        public CustomRuntimeException(String message) {
            super(message);
        }
    }
}
