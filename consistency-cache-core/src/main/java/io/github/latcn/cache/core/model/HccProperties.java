package io.github.latcn.cache.core.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
public class HccProperties {

	private LocalCacheProperties local;

	private DistributedProperties distributed;

	private HotspotProperties hotspot;

	private CircuitBreakerProperties circuitBreaker;

	private MonitorProperties monitor;

	private CacheEvictProperties cacheEvict;

	public HccProperties() {
		this.local = new LocalCacheProperties();
		this.distributed = new DistributedProperties();
		this.hotspot = new HotspotProperties();
		this.circuitBreaker = new CircuitBreakerProperties();
		this.monitor = new MonitorProperties();
		this.cacheEvict = new CacheEvictProperties();
	}

	@Data
	@NoArgsConstructor
	public static class LocalCacheProperties {

		/**
		 * 缓存实现类型：GUAVA, CAFFEINE, CUSTOM
		 */
		private String cacheType = LocalCacheType.CAFFEINE.name();

		private String cacheClz = "io.github.latcn.cache.spring.local.adapter.CaffeineCacheAdapter";

		/**
		 * 初始容量
		 */
		private int initialCapacity = 100;

		/**
		 * 最大容量
		 */
		private long maximumSize = 100000;

		/**
		 * 漂移
		 */
		private long bufferTimeMs = 1000;

	}

	@Data
	@NoArgsConstructor
	public static class DistributedProperties {

		private int maxBatchSize = 100;

		private int maxWaitMs = 10;

	}

	@Data
	@NoArgsConstructor
	public static class HotspotProperties {

		/**
		 * 读热点检测
		 */
		private int readHotKeyThreshold = 100;

		/**
		 * 读热点key最大数量， 实际热点数小于此值
		 */
		private int readHotKeyMaxSize = 10000;

		/**
		 * 写热点检测
		 */
		private int writeHotKeyThreshold = 10;

		/**
		 * 写热点key最大数量， 实际热点数小于此值
		 */
		private int writeHotKeyMaxSize = 10000;

	}

	@Data
	@NoArgsConstructor
	public static class CircuitBreakerProperties {

		private double failRatio = 0.5;

		// timeout before half-open
		private int timeoutMs = 30000;

	}

	@Data
	@NoArgsConstructor
	public static class MonitorProperties {

		private boolean enabled = true;

		private int connectionCheckIntervalSeconds = 3;

		private int memoryCheckIntervalSeconds = 30;

		private double memoryWarningThreshold = 0.8;

	}

	@Data
	@NoArgsConstructor
	public static class CacheEvictProperties {

		/**
		 * 缓存失效广播topic
		 */
		private String channelNames = "hcc_cache_evict";

		private int batchSize = 100;

		private int maxWaitSeconds = 5;

		private int invalidationQueueCapacity = 1000;

		private int cleanCachePeriodSeconds = 1;

		private long baseDelayMs = 1000;

		private int compensationBatchSize = 50;

		private int compensationPeriodSeconds = 10;

		private int maxRetryCount = 5;

	}

}
