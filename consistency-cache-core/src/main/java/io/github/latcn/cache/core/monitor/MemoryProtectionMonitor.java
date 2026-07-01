package io.github.latcn.cache.core.monitor;

import io.github.latcn.cache.core.local.LocalCacheManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MemoryProtectionMonitor {

	private final LocalCacheManager localCacheManager;

	private final long maxSize;

	private final double warningThreshold;

	private final ScheduledExecutorService scheduler;

	private final int checkIntervalSeconds;

	private final MeterRegistry meterRegistry;

	private final boolean enabled;

	private Counter evictionCounter;

	private Counter warningCounter;

	private volatile double currentUsageRatio = 0.0;

	public MemoryProtectionMonitor(LocalCacheManager localCacheManager, MeterRegistry meterRegistry, long maxSize,
			double warningThreshold, int checkIntervalSeconds) {
		this(localCacheManager, meterRegistry, maxSize, warningThreshold, checkIntervalSeconds, true);
	}

	public MemoryProtectionMonitor(LocalCacheManager localCacheManager, MeterRegistry meterRegistry, long maxSize,
			double warningThreshold, int checkIntervalSeconds, boolean enabled) {
		if (localCacheManager == null) {
			throw new NullPointerException("localCacheManager cannot be null");
		}
		this.localCacheManager = localCacheManager;
		this.maxSize = maxSize;
		this.warningThreshold = validateThreshold(warningThreshold);
		this.checkIntervalSeconds = checkIntervalSeconds;
		this.meterRegistry = meterRegistry;
		this.enabled = enabled;
		this.currentUsageRatio = calculateUsageRatio();
		this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "memory-protection-monitor");
			t.setDaemon(true);
			return t;
		});
		initMetrics();
		this.scheduler.scheduleAtFixedRate(this::checkMemoryUsage, checkIntervalSeconds, checkIntervalSeconds,
				TimeUnit.SECONDS);
		log.info("Initialized MemoryProtectionMonitor with max={}, warning={}%, interval={}s, enabled={}", maxSize,
				(int) (this.warningThreshold * 100), checkIntervalSeconds, enabled);
	}

	private double validateThreshold(double threshold) {
		if (threshold < 0 || threshold > 1.0) {
			log.warn("Invalid threshold {} (must be 0.0-1.0), disabling eviction", threshold);
			return Double.MAX_VALUE;
		}
		return threshold;
	}

	private void initMetrics() {
		if (meterRegistry == null) {
			return;
		}

		Gauge.builder("hcc_memory_usage_ratio", () -> currentUsageRatio)
			.description("L1 cache memory usage ratio (0.0-1.0)")
			.register(meterRegistry);

		Gauge.builder("hcc_memory_max_size_bytes", () -> (double) maxSize)
			.description("Configured maximum memory capacity")
			.baseUnit("entries")
			.register(meterRegistry);

		evictionCounter = Counter.builder("hcc_memory_evictions_total")
			.description("Total number of evictions triggered by memory protection")
			.register(meterRegistry);

		warningCounter = Counter.builder("hcc_memory_warnings_total")
			.description("Total number of memory warning events")
			.register(meterRegistry);
	}

	private void checkMemoryUsage() {
		if (!enabled) {
			return;
		}
		long currentSize = this.localCacheManager.getSize();
		double usageRatio = (double) currentSize / maxSize;
		this.currentUsageRatio = usageRatio;

		if (usageRatio >= warningThreshold) {
			log.warn("WARNING: L1 cache at {}% capacity ({} / {})", (int) (usageRatio * 100), currentSize, maxSize);
			recordWarning();
			this.localCacheManager.runEviction();
			recordEviction();
		}
	}

	private double calculateUsageRatio() {
		try {
			long currentSize = this.localCacheManager.getSize();
			return maxSize > 0 ? (double) currentSize / maxSize : 0.0;
		}
		catch (Exception e) {
			return 0.0;
		}
	}

	private void recordWarning() {
		if (warningCounter != null) {
			warningCounter.increment();
		}
	}

	private void recordEviction() {
		if (evictionCounter != null) {
			evictionCounter.increment();
		}
	}

	public double getUsageRatio() {
		return calculateUsageRatio();
	}

	public String getFormattedUsage() {
		return String.format("%.1f%%", getUsageRatio() * 100);
	}

	public long getMaxSize() {
		return maxSize;
	}

	public double getWarningThreshold() {
		return warningThreshold;
	}

	public int getCheckIntervalSeconds() {
		return checkIntervalSeconds;
	}

	public void shutdown() {
		this.scheduler.shutdown();
		try {
			if (!this.scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
				this.scheduler.shutdownNow();
			}
		}
		catch (InterruptedException e) {
			this.scheduler.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

}