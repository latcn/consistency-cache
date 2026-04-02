package com.consist.cache.core.circuitbreaker;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Circuit breaker for cache operations.
 * 
 * States:
 * - CLOSED: Normal operation, failures tracked
 * - OPEN: Cache disabled, all calls fail fast
 * - HALF_OPEN: Testing if cache recovered
 */
@Slf4j
public class CacheCircuitBreaker {
    
    private static final int DEFAULT_FAILURE_THRESHOLD = 5;
    private static final int DEFAULT_SUCCESS_THRESHOLD = 3;
    // 30 seconds
    private static final long DEFAULT_TIMEOUT_MS = 30000;
    
    private enum State {
        // Normal operation
        CLOSED,
        // Circuit tripped
        OPEN,
        // Testing recovery
        HALF_OPEN
    }
    
    private final int failureThreshold;
    private final int successThreshold;
    private final long timeoutMs;
    
    private volatile State state = State.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicInteger totalCalls = new AtomicInteger(0);
    private final AtomicInteger rejectedCalls = new AtomicInteger(0);
    
    public CacheCircuitBreaker() {
        this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_SUCCESS_THRESHOLD, DEFAULT_TIMEOUT_MS);
    }
    
    public CacheCircuitBreaker(int failureThreshold, int successThreshold, long timeoutMs) {
        this.failureThreshold = failureThreshold;
        this.successThreshold = successThreshold;
        this.timeoutMs = timeoutMs;
        
        log.info("Initialized CacheCircuitBreaker: failureThreshold={}, successThreshold={}, timeout={}ms",
                failureThreshold, successThreshold, timeoutMs);
    }
    
    /**
     * Execute cache operation with circuit breaker protection.
     * @param supplier cache operation to execute
     * @return result from cache
     * @throws CircuitBreakerOpenException if circuit is open
     */
    public <T> T execute(Supplier<T> supplier) throws CircuitBreakerOpenException {
        totalCalls.incrementAndGet();
        
        if (state == State.OPEN) {
            if (shouldTryHalfOpen()) {
                transitionToHalfOpen();
            } else {
                rejectedCalls.incrementAndGet();
                throw new CircuitBreakerOpenException("Cache circuit breaker is OPEN");
            }
        }
        
        try {
            T result = supplier.get();
            recordSuccess();
            return result;
        } catch (Exception e) {
            recordFailure();
            throw e;
        }
    }
    
    /**
     * Record successful cache operation.
     */
    private void recordSuccess() {
        if (state == State.HALF_OPEN) {
            int count = successCount.incrementAndGet();
            if (count >= successThreshold) {
                transitionToClosed();
            }
        } else if (state == State.CLOSED) {
            // Reset failure count on success
            failureCount.set(0);
        }
    }
    
    /**
     * Record failed cache operation.
     */
    private void recordFailure() {
        lastFailureTime.set(System.currentTimeMillis());
        
        if (state == State.HALF_OPEN) {
            // Immediately trip back to open
            transitionToOpen();
            return;
        }
        
        if (state == State.CLOSED) {
            int count = failureCount.incrementAndGet();
            
            // Only transition if we haven't already tripped
            if (count >= failureThreshold && state == State.CLOSED) {
                transitionToOpen();
            }
        }
    }
    
    /**
     * Check if circuit should transition from OPEN to HALF_OPEN.
     */
    private boolean shouldTryHalfOpen() {
        long elapsed = System.currentTimeMillis() - lastFailureTime.get();
        return elapsed >= timeoutMs;
    }
    
    /**
     * Transition from CLOSED to OPEN state.
     */
    private void transitionToOpen() {
        State oldState = state;
        state = State.OPEN;
        
        if (oldState != State.OPEN) {
            log.warn("Circuit breaker transitioned from {} to OPEN (failures={})", 
                    oldState, failureCount.get());
        }
        
        failureCount.set(0);
        successCount.set(0);
    }
    
    /**
     * Transition from OPEN to HALF_OPEN state.
     */
    private void transitionToHalfOpen() {
        state = State.HALF_OPEN;
        successCount.set(0);
        failureCount.set(0);
        
        log.info("Circuit breaker transitioned to HALF_OPEN, testing recovery...");
    }
    
    /**
     * Transition from HALF_OPEN to CLOSED state.
     */
    private void transitionToClosed() {
        state = State.CLOSED;
        failureCount.set(0);
        successCount.set(0);
        
        log.info("Circuit breaker recovered to CLOSED state");
    }

    /**
     * Get circuit breaker statistics.
     * @return stats object
     */
    public CircuitStats getStats() {
        return CircuitStats.builder()
                .state(state.name())
                .failureCount(failureCount.get())
                .successCount(successCount.get())
                .totalCalls(totalCalls.get())
                .rejectedCalls(rejectedCalls.get())
                .build();
    }
    
    /**
     * Manually reset circuit breaker.
     */
    public void reset() {
        state = State.CLOSED;
        failureCount.set(0);
        successCount.set(0);
        lastFailureTime.set(0);
        
        log.info("Circuit breaker manually reset");
    }
    
    /**
     * Force circuit to open state.
     */
    public void forceOpen() {
        transitionToOpen();
    }
    
    /**
     * Exception thrown when circuit is open.
     */
    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }
    
    /**
     * Circuit breaker statistics.
     */
    @lombok.Data
    @lombok.Builder
    public static class CircuitStats {
        private String state;
        private int failureCount;
        private int successCount;
        private int totalCalls;
        private int rejectedCalls;
        
        public double getRejectionRate() {
            return totalCalls > 0 ? (double) rejectedCalls / totalCalls : 0.0;
        }
    }
}
