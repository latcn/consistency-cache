package io.github.latcn.cache.core.circuitbreaker;

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
			Arrays.asList(java.net.SocketTimeoutException.class, java.net.ConnectException.class));

	private static final Set<Class<? extends Exception>> NON_RETRYABLE_EXCEPTIONS = Set
		.of(IllegalArgumentException.class, NullPointerException.class, IllegalStateException.class);

	private enum State {

		CLOSED, OPEN, HALF_OPEN

	}

	private final int failureThreshold;

	private final int successThreshold;

	private final long timeoutMs;

	private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

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

	public <T> T execute(Supplier<T> supplier) throws CircuitBreakerOpenException {
		totalCalls.incrementAndGet();

		State currentState = state.get();
		if (currentState == State.OPEN) {
			if (shouldTryHalfOpen()) {
				if (halfOpenProbeLock.compareAndSet(false, true)) {
					state.compareAndSet(State.OPEN, State.HALF_OPEN);
				}
			}
			if (state.get() == State.OPEN) {
				rejectedCalls.incrementAndGet();
				throw new CircuitBreakerOpenException("Cache circuit breaker is OPEN");
			}
		}

		try {
			T result = supplier.get();
			recordSuccess();
			return result;
		}
		catch (Exception e) {
			if (isRetryable(e)) {
				recordFailure();
			}
			throw e;
		}
	}

	private boolean isRetryable(Exception e) {
		if (NON_RETRYABLE_EXCEPTIONS.stream().anyMatch(clazz -> clazz.isInstance(e))) {
			return false;
		}
		return RETRYABLE_EXCEPTIONS.stream().anyMatch(clazz -> clazz.isInstance(e));
	}

	private void recordSuccess() {
		if (state.get() == State.HALF_OPEN) {
			int count = successCount.incrementAndGet();
			if (count >= successThreshold) {
				transitionToClosed();
			}
		}
		else if (state.get() == State.CLOSED) {
			failureCount.set(0);
		}
	}

	private void recordFailure() {
		lastFailureTime.set(System.currentTimeMillis());

		if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
			return;
		}

		if (state.get() == State.CLOSED) {
			int count = failureCount.incrementAndGet();
			if (count >= failureThreshold) {
				state.compareAndSet(State.CLOSED, State.OPEN);
			}
		}
	}

	private boolean shouldTryHalfOpen() {
		long elapsed = System.currentTimeMillis() - lastFailureTime.get();
		return elapsed >= timeoutMs;
	}

	private void transitionToClosed() {
		state.set(State.CLOSED);
		halfOpenProbeLock.set(false);
		failureCount.set(0);
		successCount.set(0);

		log.info("Circuit breaker recovered to CLOSED state");
	}

	public CircuitStats getStats() {
		return CircuitStats.builder()
			.state(state.get().name())
			.failureCount(failureCount.get())
			.successCount(successCount.get())
			.totalCalls(totalCalls.get())
			.rejectedCalls(rejectedCalls.get())
			.build();
	}

	public void reset() {
		state.set(State.CLOSED);
		halfOpenProbeLock.set(false);
		failureCount.set(0);
		successCount.set(0);
		lastFailureTime.set(0);

		log.info("Circuit breaker manually reset");
	}

	public void forceOpen() {
		state.set(State.OPEN);
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
