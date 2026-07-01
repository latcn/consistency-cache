package io.github.latcn.cache.core.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
public class HccProperties {

	private LocalCacheProperties local;

	private DistributedProperties distributed;

	private HotspotProperties readHot;

	private HotspotProperties writeHot;

	private CircuitBreakerProperties circuitBreaker;

	private MonitorProperties monitor;

	private CacheEvictProperties cacheEvict;

	public HccProperties() {
		this.local = new LocalCacheProperties();
		this.distributed = new DistributedProperties();
		this.readHot = new HotspotProperties();
		this.writeHot = new HotspotProperties();
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

		private int cleanPeriodSeconds = 5;

		private int markerMaxSize = 100000;

	}

	@Data
	@NoArgsConstructor
	public static class DistributedProperties {

		private int cacheOperationSize = 1000;

		private int maxBatchSize = 100;

		private int maxWaitMs = 10;

	}

	@Data
	@NoArgsConstructor
	public static class HotspotProperties {

		// cms 系统预估的总请求量（每秒总请求数）系统峰值平均QPS（如日高峰时段的平均值）
		private long totalQps = 10000;

		// cms 业务上定义的热点阈值（每秒请求数），例如“每秒超过100次访问即视为热点”
		private int hotQps = 100;

		// cms 允许的最大绝对误差 整个采样窗口（sampleSize）内的总计数误差
		private int maxAbsError = 10;

		// cms 统计窗口时长（毫秒）
		private int windowMs = 1000;

		// cms depth
		private int depth = 4;

		// 晋升阈值占热点阈值的比例（0~1）
		private double promotionRatio = 0.7;

		// 精确层最大容量（允许跟踪的key数量上限）热点key预估数量 × (2 ~ 3)
		private int maxExactSize = 2000;

		// key在精确层的过期时间（毫秒）。若 key 最后一次访问距今超过该时间，且当前计数值低于 hotKeyThreshold，则在常规清理中被淘汰
		private long expirationTimeMs = 30_000;

		// 常规清理任务执行间隔（毫秒）。后台线程定期遍历精确层，淘汰过期且计数低于阈值的key
		private long cleanupIntervalMs = 5000;

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
