package io.github.latcn.cache.core.monitor;

import io.github.latcn.cache.core.distributed.DistributedCacheManager;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EnhancedConnectionMonitor {

	private final DistributedCacheManager distributedCacheManager;

	private final LocalCacheManager localCacheManager;

	private final ScheduledExecutorService scheduler;

	private volatile boolean wasConnected = true;

	private final int checkIntervalSeconds;

	private final MeterRegistry meterRegistry;

	private final boolean enabled;

	private Counter disconnectionCounter;

	private Counter reconnectionCounter;

	public EnhancedConnectionMonitor(DistributedCacheManager distributedCacheManager,
			LocalCacheManager localCacheManager) {
		this(distributedCacheManager, localCacheManager, 3, null, true);
	}

	public EnhancedConnectionMonitor(DistributedCacheManager distributedCacheManager,
			LocalCacheManager localCacheManager, int checkIntervalSeconds) {
		this(distributedCacheManager, localCacheManager, checkIntervalSeconds, null, true);
	}

	public EnhancedConnectionMonitor(DistributedCacheManager distributedCacheManager,
			LocalCacheManager localCacheManager, int checkIntervalSeconds, MeterRegistry meterRegistry) {
		this(distributedCacheManager, localCacheManager, checkIntervalSeconds, meterRegistry, true);
	}

	public EnhancedConnectionMonitor(DistributedCacheManager distributedCacheManager,
			LocalCacheManager localCacheManager, int checkIntervalSeconds, MeterRegistry meterRegistry,
			boolean enabled) {
		if (distributedCacheManager == null) {
			throw new NullPointerException("distributedCacheManager cannot be null");
		}
		if (localCacheManager == null) {
			throw new NullPointerException("localCacheManager cannot be null");
		}
		this.distributedCacheManager = distributedCacheManager;
		this.localCacheManager = localCacheManager;
		this.checkIntervalSeconds = checkIntervalSeconds;
		this.meterRegistry = meterRegistry;
		this.enabled = enabled;
		this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "enhanced-connection-monitor");
			t.setDaemon(true);
			return t;
		});
		initMetrics();
		this.scheduler.scheduleAtFixedRate(this::checkConnection, checkIntervalSeconds, checkIntervalSeconds,
				TimeUnit.SECONDS);
		log.info("Initialized EnhancedConnectionMonitor with check interval: {}s, enabled: {}", checkIntervalSeconds,
				enabled);
	}

	private void initMetrics() {
		if (meterRegistry == null) {
			return;
		}

		Gauge.builder("hcc_connection_status", () -> wasConnected ? 1.0 : 0.0)
			.description("Current connection status (0=disconnected, 1=connected)")
			.register(meterRegistry);

		disconnectionCounter = Counter.builder("hcc_connection_disconnections_total")
			.description("Total number of disconnection events")
			.register(meterRegistry);

		reconnectionCounter = Counter.builder("hcc_connection_reconnections_total")
			.description("Total number of reconnection events")
			.register(meterRegistry);
	}

	public void checkConnection() {
		if (!enabled) {
			return;
		}
		try {
			boolean isConnected = isConnected();
			if (!isConnected && this.wasConnected) {
				log.error("Redis connection LOST! Handling based on consistency levels...");
				recordDisconnection();
				handleDisconnection();
				this.wasConnected = false;

			}
			else if (isConnected && !this.wasConnected) {
				log.warn("Redis RECONNECTED! Handling based on consistency levels...");
				recordReconnection();
				handleReconnection();
				this.wasConnected = true;
			}

		}
		catch (Exception e) {
			log.error("Failed to check Redis connection status", e);

			if (this.wasConnected) {
				log.error("Assuming Redis disconnected due to exception");
				recordDisconnection();
				handleDisconnection();
				this.wasConnected = false;
			}
		}
	}

	private void recordDisconnection() {
		if (disconnectionCounter != null) {
			disconnectionCounter.increment();
		}
	}

	private void recordReconnection() {
		if (reconnectionCounter != null) {
			reconnectionCounter.increment();
		}
	}

	private void handleDisconnection() {
		this.localCacheManager.getCacheLevelMap().forEach((consistencyLevel, localCache) -> {
			switch (consistencyLevel) {
				case HIGH:
					log.warn("Cache '{}' (HIGH consistency): Clearing L1 cache on disconnect", consistencyLevel);
					localCache.invalidateAll();
					break;

				case AVAILABLE:
					log.info("Cache '{}' (AVAILABLE consistency): Marking L1 as STALE, retaining data",
							consistencyLevel);
					break;
			}
		});
	}

	private void handleReconnection() {
		this.localCacheManager.getCacheLevelMap().forEach((consistencyLevel, localCache) -> {
			switch (consistencyLevel) {
				case HIGH:
					log.warn("Cache '{}' (HIGH consistency): Clearing L1 cache on reconnect", consistencyLevel);
					localCache.invalidateAll();
					break;

				case AVAILABLE:
					log.info("Cache '{}' (AVAILABLE consistency): Retaining L1 cache after reconnect",
							consistencyLevel);
					break;
			}
		});
	}

	public boolean isConnected() {
		try {
			return this.distributedCacheManager.isHealthy();
		}
		catch (Exception e) {
			return false;
		}
	}

	public boolean isWasConnected() {
		return wasConnected;
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