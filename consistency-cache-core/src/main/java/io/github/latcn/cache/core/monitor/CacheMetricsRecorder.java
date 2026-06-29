package io.github.latcn.cache.core.monitor;

public interface CacheMetricsRecorder {

	void recordL1Request();

	void recordL1Hit();

	void recordL1Miss();

	void recordL2Operation(long startTimeMs, String operationType);

	void recordCircuitBreakerRejection();

	void recordDbOperation(long startTimeMs);

	void recordInvalidationPublish(boolean success);

	void recordInvalidationReceive(boolean success);

	void recordSingleFlightDeduplication(String type);

	boolean isEnabled();

	static CacheMetricsRecorder noOp() {
		return NoOpCacheMetricsRecorder.INSTANCE;
	}

	static CacheMetricsRecorder of(io.micrometer.core.instrument.MeterRegistry registry) {
		if (registry == null) {
			return noOp();
		}
		return new MicrometerCacheMetricsRecorder(registry);
	}

	static CacheMetricsRecorder of(io.micrometer.core.instrument.MeterRegistry registry, boolean enabled) {
		if (!enabled || registry == null) {
			return noOp();
		}
		return new MicrometerCacheMetricsRecorder(registry);
	}

}
