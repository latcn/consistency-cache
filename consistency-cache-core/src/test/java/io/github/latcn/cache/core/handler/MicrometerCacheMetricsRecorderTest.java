package io.github.latcn.cache.core.handler;

import static io.github.latcn.cache.core.handler.CacheMetricsConstants.L2OperationType.GET;
import static io.github.latcn.cache.core.handler.CacheMetricsConstants.SingleFlightDeduplicationType.DB;
import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MicrometerCacheMetricsRecorder Tests")
class MicrometerCacheMetricsRecorderTest {

	private MeterRegistry meterRegistry;
	private MicrometerCacheMetricsRecorder recorder;

	@BeforeEach
	void setUp() {
		meterRegistry = new SimpleMeterRegistry();
		recorder = new MicrometerCacheMetricsRecorder(meterRegistry);
	}

	@Test
	@DisplayName("Should record L1 request")
	void testRecordL1Request() {
		recorder.recordL1Request();
		
		Counter counter = meterRegistry.find("hcc_cache_requests_total").counter();
		assertNotNull(counter);
		assertEquals(1.0, counter.count());
	}

	@Test
	@DisplayName("Should record L1 hit")
	void testRecordL1Hit() {
		recorder.recordL1Hit();
		
		Counter counter = meterRegistry.find("hcc_cache_hits_total").counter();
		assertNotNull(counter);
		assertEquals(1.0, counter.count());
	}

	@Test
	@DisplayName("Should record L1 miss")
	void testRecordL1Miss() {
		recorder.recordL1Miss();
		
		Counter counter = meterRegistry.find("hcc_cache_misses_total").counter();
		assertNotNull(counter);
		assertEquals(1.0, counter.count());
	}

	@Test
	@DisplayName("Should record L2 operation")
	void testRecordL2Operation() {
		long startTime = System.currentTimeMillis();
		
		recorder.recordL2Operation(startTime, GET);
		
		Counter counter = meterRegistry.find("hcc_distributed_cache_operations_total").tag("operation", "get").counter();
		assertNotNull(counter);
		assertEquals(1.0, counter.count());
		
		Timer timer = meterRegistry.find("hcc_distributed_cache_latency_seconds").tag("operation", "get").timer();
		assertNotNull(timer);
		assertTrue(timer.count() > 0);
	}

	@Test
	@DisplayName("Should record circuit breaker rejection")
	void testRecordCircuitBreakerRejection() {
		recorder.recordCircuitBreakerRejection();
		
		Counter counter = meterRegistry.find("hcc_circuit_breaker_rejected_total").counter();
		assertNotNull(counter);
		assertEquals(1.0, counter.count());
	}

	@Test
	@DisplayName("Should record db operation")
	void testRecordDbOperation() {
		long startTime = System.currentTimeMillis();
		
		recorder.recordDbOperation(startTime);
		
		Counter counter = meterRegistry.find("hcc_db_operations_total").counter();
		assertNotNull(counter);
		assertEquals(1.0, counter.count());
		
		Timer timer = meterRegistry.find("hcc_db_latency_seconds").timer();
		assertNotNull(timer);
		assertTrue(timer.count() > 0);
	}

	@Test
	@DisplayName("Should record invalidation publish success")
	void testRecordInvalidationPublishSuccess() {
		recorder.recordInvalidationPublish(true);
		
		Counter counter = meterRegistry.find("hcc_invalidation_publish_total").tag("result", "success").counter();
		assertNotNull(counter);
		assertEquals(1.0, counter.count());
	}

	@Test
	@DisplayName("Should record invalidation publish failure")
	void testRecordInvalidationPublishFailure() {
		recorder.recordInvalidationPublish(false);
		
		Counter counter = meterRegistry.find("hcc_invalidation_publish_total").tag("result", "failure").counter();
		assertNotNull(counter);
		assertEquals(1.0, counter.count());
	}

	@Test
	@DisplayName("Should record invalidation receive success")
	void testRecordInvalidationReceiveSuccess() {
		recorder.recordInvalidationReceive(true);
		
		Counter counter = meterRegistry.find("hcc_invalidation_receive_total").tag("result", "success").counter();
		assertNotNull(counter);
		assertEquals(1.0, counter.count());
	}

	@Test
	@DisplayName("Should record invalidation receive failure")
	void testRecordInvalidationReceiveFailure() {
		recorder.recordInvalidationReceive(false);
		
		Counter counter = meterRegistry.find("hcc_invalidation_receive_total").tag("result", "failure").counter();
		assertNotNull(counter);
		assertEquals(1.0, counter.count());
	}

	@Test
	@DisplayName("Should record single flight deduplication")
	void testRecordSingleFlightDeduplication() {
		recorder.recordSingleFlightDeduplication(DB);
		
		Counter counter = meterRegistry.find("hcc_singleflight_deduplicated_total").tag("type", "db").counter();
		assertNotNull(counter);
		assertEquals(1.0, counter.count());
	}

	@Test
	@DisplayName("Should be enabled")
	void testIsEnabled() {
		assertTrue(recorder.isEnabled());
	}

	@Test
	@DisplayName("Should calculate db latency correctly")
	void testDbLatencyCalculation() {
		long startTime = System.currentTimeMillis();
		
		try {
			Thread.sleep(10);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		
		recorder.recordDbOperation(startTime);
		
		Timer timer = meterRegistry.find("hcc_db_latency_seconds").timer();
		assertNotNull(timer);
		assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS) >= 0.01);
	}

	@Test
	@DisplayName("NoOp recorder should have no effect")
	void testNoOpRecorder() {
		CacheMetricsRecorder noOp = CacheMetricsRecorder.noOp();
		
		MeterRegistry testRegistry = new SimpleMeterRegistry();
		noOp.recordL1Request();
		noOp.recordL1Hit();
		noOp.recordL1Miss();
		noOp.recordL2Operation(System.currentTimeMillis(), "get");
		noOp.recordDbOperation(System.currentTimeMillis());
		
		assertNull(testRegistry.find("hcc_cache_requests_total").counter());
		assertFalse(noOp.isEnabled());
	}

}