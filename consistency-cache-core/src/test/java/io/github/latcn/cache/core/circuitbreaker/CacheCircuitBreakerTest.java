package io.github.latcn.cache.core.circuitbreaker;

import static org.junit.jupiter.api.Assertions.*;

import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

@DisplayName("熔断器测试")
class CacheCircuitBreakerTest {

	private CacheCircuitBreaker circuitBreaker;

	@BeforeEach
	void setUp(TestInfo testInfo) {
		Set<Class<? extends Exception>> customExceptions = new HashSet<>();
		customExceptions.add(SocketTimeoutException.class);
		circuitBreaker = new CacheCircuitBreaker(0.5, 30000, customExceptions);
		System.out.println("执行测试: " + testInfo.getDisplayName());
	}

	@Test
	@DisplayName("CB-001: 正常状态下请求通过")
	void testNormalStateRequestsPass() {
		for (int i = 0; i < 5; i++) {
			final int index = i;
			String result = circuitBreaker.execute(() -> "success-" + index);
			assertEquals("success-" + i, result);
		}

		CircuitBreakerStats stats = circuitBreaker.getStats();
		assertEquals(CircuitBreakerState.CLOSED, stats.getState());
		assertEquals(0, stats.getFailureCount());
	}

	@Test
	@DisplayName("CB-002: 失败次数达到阈值触发熔断")
	void testFailureThresholdTriggersCircuitBreaker() {
		for (int i = 0; i < 5; i++) {
			try {
				circuitBreaker.execute(() -> {
					throw new RuntimeException(new SocketTimeoutException("timeout"));
				});
			}
			catch (CacheException e) {
			}
		}

		CircuitBreakerStats stats = circuitBreaker.getStats();
		assertEquals(CircuitBreakerState.OPEN, stats.getState());
	}

	@Test
	@DisplayName("CB-003: 熔断状态下请求被拒绝")
	void testCircuitBreakerOpenRejectsRequests() {
		circuitBreaker.forceOpen();
		assertThrows(CacheException.class, () -> {
			circuitBreaker.execute(() -> {
				throw new RuntimeException(new SocketTimeoutException("timeout"));
			});
		});

		CacheException exception = assertThrows(CacheException.class, () -> {
			circuitBreaker.execute(() -> "test");
		});

		assertEquals(CacheError.CIRCUIT_BREAKER_OPEN.getErrorCode(), exception.getErrorCode());
	}

	@Test
	@DisplayName("CB-004: 超时后自动进入半开状态")
	void testTimeoutAutoHalfOpen() throws InterruptedException {
		circuitBreaker = new CacheCircuitBreaker(0.5, 1000, null);
		circuitBreaker.forceOpen();

		TimeUnit.MILLISECONDS.sleep(1100);

		String result = circuitBreaker.execute(() -> "success");
		assertEquals("success", result);

		CircuitBreakerStats stats = circuitBreaker.getStats();
		assertTrue(stats.getState() == CircuitBreakerState.HALF_OPEN || stats.getState() == CircuitBreakerState.CLOSED);
	}

	@Test
	@DisplayName("CB-005: 半开状态成功恢复")
	void testHalfOpenSuccessRecovery() throws InterruptedException {
		circuitBreaker = new CacheCircuitBreaker(0.5, 1000, null);
		circuitBreaker.forceOpen();

		TimeUnit.MILLISECONDS.sleep(1100);

		for (int i = 0; i < 3; i++) {
			final int index = i;
			String result = circuitBreaker.execute(() -> "success-" + index);
			assertEquals("success-" + i, result);
		}

		CircuitBreakerStats stats = circuitBreaker.getStats();
		assertEquals(CircuitBreakerState.CLOSED, stats.getState());
	}

	@Test
	@DisplayName("CB-006: 半开状态失败重回熔断")
	void testHalfOpenFailureReopensCircuitBreaker() throws InterruptedException {
		circuitBreaker = new CacheCircuitBreaker(0.5, 1000, null);
		circuitBreaker.forceOpen();

		TimeUnit.MILLISECONDS.sleep(1100);

		try {
			circuitBreaker.execute(() -> {
				throw new RuntimeException(new SocketTimeoutException("timeout"));
			});
		}
		catch (CacheException e) {
		}

		CircuitBreakerStats stats = circuitBreaker.getStats();
		assertEquals(CircuitBreakerState.OPEN, stats.getState());
	}

	@Test
	@DisplayName("CB-007: 非重试异常不计数")
	void testNonRetryableExceptionNotCounted() {
		long initialFailureCount = circuitBreaker.getStats().getFailureCount();

		try {
			circuitBreaker.execute(() -> {
				throw new IllegalArgumentException("invalid argument");
			});
		}
		catch (CacheException e) {
		}

		CircuitBreakerStats stats = circuitBreaker.getStats();
		assertEquals(initialFailureCount, stats.getFailureCount());
		assertEquals(CircuitBreakerState.CLOSED, stats.getState());
	}

	@Test
	@DisplayName("CB-008: 重试异常计数")
	void testRetryableExceptionCounted() {
		long initialFailureCount = circuitBreaker.getStats().getFailureCount();

		try {
			circuitBreaker.execute(() -> {
				return 1 / 2;
			});
			circuitBreaker.execute(() -> {
				return 1 / 2;
			});
			circuitBreaker.execute(() -> {
				throw new RuntimeException(new SocketTimeoutException("timeout"));
			});
		}
		catch (CacheException e) {
		}

		CircuitBreakerStats stats = circuitBreaker.getStats();
		assertEquals(initialFailureCount + 1, stats.getFailureCount());
	}

	@Test
	@DisplayName("CB-009: 获取统计信息")
	void testGetStats() {
		circuitBreaker.execute(() -> "success1");
		circuitBreaker.execute(() -> "success2");

		try {
			circuitBreaker.execute(() -> {
				throw new RuntimeException(new SocketTimeoutException("timeout"));
			});
		}
		catch (CacheException e) {
		}

		CircuitBreakerStats stats = circuitBreaker.getStats();
		assertEquals(3, stats.getTotalCalls());
		assertEquals(1, stats.getFailureCount());
		assertEquals(0, stats.getRejectedCalls());
		assertTrue(stats.getRejectionRate() >= 0.0 && stats.getRejectionRate() <= 1.0);
	}

	@Test
	@DisplayName("CB-010: 手动重置熔断器")
	void testManualReset() {
		circuitBreaker.forceOpen();

		circuitBreaker.reset();

		CircuitBreakerStats stats = circuitBreaker.getStats();
		assertEquals(CircuitBreakerState.CLOSED, stats.getState());
		assertEquals(0, stats.getFailureCount());
		assertEquals(0, stats.getSuccessCount());

		String result = circuitBreaker.execute(() -> "test");
		assertEquals("test", result);
	}

	@Test
	@DisplayName("CB-011: 强制打开熔断器")
	void testForceOpen() {
		circuitBreaker.forceOpen();
		CircuitBreakerStats stats = circuitBreaker.getStats();
		assertEquals(CircuitBreakerState.OPEN, stats.getState());
		assertThrows(CacheException.class, () -> {
			circuitBreaker.execute(() -> {
				throw new RuntimeException("", new SocketTimeoutException(""));
			});
		});
		assertThrows(CacheException.class, () -> {
			circuitBreaker.execute(() -> "test");
		});
	}

	@Test
	@DisplayName("CB-012: 并发请求测试")
	void testConcurrentRequests() throws InterruptedException {
		int threadCount = 10;
		Thread[] threads = new Thread[threadCount];
		int[] successCount = { 0 };
		int[] failureCount = { 0 };

		for (int i = 0; i < threadCount; i++) {
			threads[i] = new Thread(() -> {
				try {
					circuitBreaker.execute(() -> {
						successCount[0]++;
						return "success";
					});
				}
				catch (CacheException e) {
					failureCount[0]++;
				}
			});
		}

		for (Thread thread : threads) {
			thread.start();
		}

		for (Thread thread : threads) {
			thread.join();
		}

		assertTrue(successCount[0] > 0);
		assertEquals(0, failureCount[0]);
		assertEquals(CircuitBreakerState.CLOSED, circuitBreaker.getStats().getState());
	}

}