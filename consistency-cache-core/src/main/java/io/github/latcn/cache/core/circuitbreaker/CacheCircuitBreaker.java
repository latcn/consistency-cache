package io.github.latcn.cache.core.circuitbreaker;

import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CacheCircuitBreaker {

	private static final double DEFAULT_FAILURE_RATIO = 0.5;

	private static final long DEFAULT_TIMEOUT_MS = 30000;

	private static final Set<Class<? extends Exception>> RETRYABLE_EXCEPTIONS = new HashSet<>(
			Arrays.asList(java.io.IOException.class, java.sql.SQLException.class));

	private static final Set<Class<? extends Exception>> NON_RETRYABLE_EXCEPTIONS = Set
		.of(IllegalArgumentException.class, NullPointerException.class, IllegalStateException.class);

	private final double failRatio;

	private final long timeoutMs;

	private final AtomicReference<CircuitBreakerState> state = new AtomicReference<>(CircuitBreakerState.CLOSED);

	private final AtomicInteger failureCount = new AtomicInteger(0);

	private final AtomicInteger successCount = new AtomicInteger(0);

	private final AtomicLong lastFailureTime = new AtomicLong(0);

	private final AtomicInteger totalCalls = new AtomicInteger(0);

	private final AtomicInteger rejectedCalls = new AtomicInteger(0);

	public CacheCircuitBreaker(Set<Class<? extends Exception>> customExceptions) {
		this(DEFAULT_FAILURE_RATIO, DEFAULT_TIMEOUT_MS, customExceptions);
	}

	public CacheCircuitBreaker(double failRatio, long timeoutMs, Set<Class<? extends Exception>> customExceptions) {
		this.failRatio = failRatio;
		this.timeoutMs = timeoutMs;
		if (customExceptions != null && !customExceptions.isEmpty()) {
			this.RETRYABLE_EXCEPTIONS.addAll(customExceptions);
		}
		log.info("Initialized CacheCircuitBreaker: failRatio={}, timeout={}ms", failRatio, timeoutMs);
	}

	/**
	 * 熔断三个状态转换： closed：初始状态 或 由halfOpen转换 open: 失败比率大于预计值，则打开熔断器，拒绝服务； 由closed/halfOpen转换
	 * halfOpen: open状态 超过一段时间改成halfOpen状态; 进行检测,一次失败则变成open；halfOpen一次检测成功则转换成 closed
	 * @param supplier
	 * @return
	 * @param <T>
	 * @throws CacheException
	 */
	public <T> T execute(Supplier<T> supplier) throws CacheException {
		totalCalls.incrementAndGet();

		CircuitBreakerState currentState = state.get();
		if (currentState == CircuitBreakerState.OPEN) {
			if (shouldTryHalfOpen()) {
				state.compareAndSet(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN);
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
		successCount.incrementAndGet();
		if (state.get() == CircuitBreakerState.HALF_OPEN) {
			// half open 条件下， 探测成功，则直接关闭熔断
			transitionToClosed();
		}
	}

	private void recordFailure() {
		lastFailureTime.set(System.currentTimeMillis());
		failureCount.incrementAndGet();
		if (state.get() == CircuitBreakerState.HALF_OPEN) {
			// halfOpen 状态下，失败直接关闭，只重试一次
			forceOpen();
		}
		else if (shouldTryOpen()) {
			// closed 状态下，需要根据比例进行判断
			forceOpen();
		}
	}

	private boolean shouldTryOpen() {
		int curSuccessCount = successCount.get();
		int curFailureCount = failureCount.get();
		int totalCount = curSuccessCount + curFailureCount;
		if (totalCount == 0) {
			return false;
		}
		else {
			return curFailureCount * 1.0 / totalCount >= this.failRatio;
		}
	}

	private boolean shouldTryHalfOpen() {
		long elapsed = System.currentTimeMillis() - lastFailureTime.get();
		return elapsed >= timeoutMs;
	}

	private void transitionToClosed() {
		state.set(CircuitBreakerState.CLOSED);
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
		failureCount.set(0);
		successCount.set(0);
		lastFailureTime.set(0);

		log.info("Circuit breaker manually reset");
	}

	public void forceOpen() {
		state.set(CircuitBreakerState.OPEN);
		failureCount.set(0);
		successCount.set(0);

		log.warn("Circuit breaker force to OPEN");
	}

}
