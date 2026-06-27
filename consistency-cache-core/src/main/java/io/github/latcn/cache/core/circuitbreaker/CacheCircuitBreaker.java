package io.github.latcn.cache.core.circuitbreaker;

import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CacheCircuitBreaker {

	private static final int DEFAULT_FAILURE_THRESHOLD = 5;

	private static final int DEFAULT_SUCCESS_THRESHOLD = 3;

	private static final long DEFAULT_TIMEOUT_MS = 30000;

	private static final Set<Class<? extends Exception>> RETRYABLE_EXCEPTIONS = new HashSet<>(
			Arrays.asList(java.io.IOException.class, java.sql.SQLException.class));

	private static final Set<Class<? extends Exception>> NON_RETRYABLE_EXCEPTIONS = Set
		.of(IllegalArgumentException.class, NullPointerException.class, IllegalStateException.class);

	private final int failureThreshold;

	private final int successThreshold;

	private final long timeoutMs;

	private final AtomicReference<CircuitBreakerState> state = new AtomicReference<>(CircuitBreakerState.CLOSED);

	private final AtomicBoolean halfOpenProbeLock = new AtomicBoolean(false);

	private final AtomicInteger failureCount = new AtomicInteger(0);

	private final AtomicInteger successCount = new AtomicInteger(0);

	private final AtomicLong lastFailureTime = new AtomicLong(0);

	private final AtomicInteger totalCalls = new AtomicInteger(0);

	private final AtomicInteger rejectedCalls = new AtomicInteger(0);

	public CacheCircuitBreaker(Set<Class<? extends Exception>> customExceptions) {
		this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_SUCCESS_THRESHOLD, DEFAULT_TIMEOUT_MS, customExceptions);
	}

	public CacheCircuitBreaker(int failureThreshold, int successThreshold, long timeoutMs,
			Set<Class<? extends Exception>> customExceptions) {
		this.failureThreshold = failureThreshold;
		this.successThreshold = successThreshold;
		this.timeoutMs = timeoutMs;
		if (customExceptions != null && !customExceptions.isEmpty()) {
			this.RETRYABLE_EXCEPTIONS.addAll(customExceptions);
		}
		log.info("Initialized CacheCircuitBreaker: failureThreshold={}, successThreshold={}, timeout={}ms",
				failureThreshold, successThreshold, timeoutMs);
	}

	public <T> T execute(Supplier<T> supplier) throws CacheException {
		totalCalls.incrementAndGet();

		CircuitBreakerState currentState = state.get();
		if (currentState == CircuitBreakerState.OPEN) {
			if (shouldTryHalfOpen()) {
				if (halfOpenProbeLock.compareAndSet(false, true)) {
					state.compareAndSet(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN);
				}
			}
			if (state.get() == CircuitBreakerState.OPEN) {
				rejectedCalls.incrementAndGet();
				throw new CacheException(CacheError.CIRCUIT_BREAKER_OPEN);
			}
		}

		try {
			T result = supplier.get();
			recordSuccess();
			return result;
		}
		catch (CacheException e) {
			throw e;
		}
		catch (Exception e) {
			if (isRetryable(e)) {
				recordFailure();
			}
			throw CacheException.wrap(e, CacheError.CIRCUIT_BREAKER_ERROR);
		}
	}

	private boolean isRetryable(Exception e) {
		if (NON_RETRYABLE_EXCEPTIONS.stream().anyMatch(clazz -> clazz.isInstance(e))) {
			return false;
		}
		return RETRYABLE_EXCEPTIONS.stream().anyMatch(clazz -> clazz.isInstance(e) || clazz.isInstance(e.getCause()));
	}

	private void recordSuccess() {
		if (state.get() == CircuitBreakerState.HALF_OPEN) {
			int count = successCount.incrementAndGet();
			if (count >= successThreshold) {
				transitionToClosed();
			}
		}
		else if (state.get() == CircuitBreakerState.CLOSED) {
			failureCount.set(0);
		}
	}

	private void recordFailure() {
		lastFailureTime.set(System.currentTimeMillis());

		if (state.compareAndSet(CircuitBreakerState.HALF_OPEN, CircuitBreakerState.OPEN)) {
			return;
		}

		if (state.get() == CircuitBreakerState.CLOSED) {
			int count = failureCount.incrementAndGet();
			if (count >= failureThreshold) {
				state.compareAndSet(CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN);
			}
		}
	}

	private boolean shouldTryHalfOpen() {
		long elapsed = System.currentTimeMillis() - lastFailureTime.get();
		return elapsed >= timeoutMs;
	}

	private void transitionToClosed() {
		state.set(CircuitBreakerState.CLOSED);
		halfOpenProbeLock.set(false);
		failureCount.set(0);
		successCount.set(0);

		log.info("Circuit breaker recovered to CLOSED state");
	}

	public CircuitBreakerStats getStats() {
		int total = totalCalls.get();
		return CircuitBreakerStats.builder()
			.state(state.get())
			.failureCount(failureCount.get())
			.successCount(successCount.get())
			.totalCalls(total)
			.rejectedCalls(rejectedCalls.get())
			.rejectionRate(total > 0 ? (double) rejectedCalls.get() / total : 0.0)
			.build();
	}

	public void reset() {
		state.set(CircuitBreakerState.CLOSED);
		halfOpenProbeLock.set(false);
		failureCount.set(0);
		successCount.set(0);
		lastFailureTime.set(0);

		log.info("Circuit breaker manually reset");
	}

	public void forceOpen() {
		state.set(CircuitBreakerState.OPEN);
		halfOpenProbeLock.set(false);
		failureCount.set(0);
		successCount.set(0);

		log.warn("Circuit breaker force to OPEN");
	}

	public static class CircuitBreakerOpenException extends RuntimeException {

		public CircuitBreakerOpenException(String message) {
			super(message);
		}

	}

}
