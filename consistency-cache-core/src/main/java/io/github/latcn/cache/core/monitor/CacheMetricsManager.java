package io.github.latcn.cache.core.monitor;

import io.github.latcn.cache.core.circuitbreaker.CacheCircuitBreaker;
import io.github.latcn.cache.core.distributed.DistributedCacheManager;
import io.github.latcn.cache.core.hotspot.reads.ReadHotspotDetector;
import io.github.latcn.cache.core.hotspot.writes.WriteHotspotDetector;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 缓存指标管理器，负责创建和管理缓存监控指标。
 * 
 * <p>设计意图：作为缓存监控系统的核心管理类，整合Micrometer注册表和CacheMetricsBinder，
 * 提供统一的指标管理入口。在Spring环境中，通过此类可以方便地获取MeterRegistry实例
 * 和查询特定的监控指标。</p>
 * 
 * <p>主要职责：
 * <ul>
 *   <li>初始化并配置Micrometer注册表</li>
 *   <li>创建并绑定CacheMetricsBinder到注册表</li>
 *   <li>提供便捷的指标查询方法</li>
 * </ul>
 * </p>
 * 
 * <p>使用场景：通常由Spring配置类创建，作为Bean注入到需要监控功能的地方。</p>
 */
@Slf4j
public class CacheMetricsManager {

	/** Micrometer注册表，用于存储和查询所有监控指标 */
	private final MeterRegistry meterRegistry;

	/** 缓存指标绑定器，用于绑定缓存组件的状态指标 */
	private final CacheMetricsBinder metricsBinder;

	/**
	 * 构造函数：初始化缓存指标管理器。
	 * 
	 * <p>设计意图：在构造时立即创建CacheMetricsBinder并绑定到MeterRegistry，
	 * 确保所有缓存组件的状态指标在系统启动时就已经可用。</p>
	 * 
	 * @param meterRegistry Micrometer注册表，用于注册和管理监控指标
	 * @param localCacheManager 本地缓存管理器，用于绑定L1缓存指标
	 * @param distributedCacheManager 分布式缓存管理器，用于绑定L2缓存指标
	 * @param circuitBreaker 熔断器，用于绑定熔断器指标
	 * @param readHotspotDetector 读热点检测器，用于绑定读热点指标
	 * @param writeHotspotDetector 写热点检测器，用于绑定写热点指标
	 */
	public CacheMetricsManager(MeterRegistry meterRegistry, LocalCacheManager localCacheManager,
			DistributedCacheManager distributedCacheManager, CacheCircuitBreaker circuitBreaker,
			ReadHotspotDetector readHotspotDetector, WriteHotspotDetector writeHotspotDetector) {
		this.meterRegistry = meterRegistry;

		this.metricsBinder = new CacheMetricsBinder(localCacheManager, distributedCacheManager, circuitBreaker,
				readHotspotDetector, writeHotspotDetector);
		this.metricsBinder.bindTo(meterRegistry);

		log.info("Cache Metrics Manager initialized with Micrometer registry: {}",
				meterRegistry.getClass().getSimpleName());
	}

	/**
	 * 获取Micrometer注册表。
	 * 
	 * <p>用于需要直接操作MeterRegistry的场景，如注册自定义指标或查询特定指标。</p>
	 * 
	 * @return Micrometer注册表实例
	 */
	public MeterRegistry getMeterRegistry() {
		return meterRegistry;
	}

	/**
	 * 获取缓存指标绑定器。
	 * 
	 * <p>用于需要访问CacheMetricsBinder的场景，如动态添加新的指标绑定。</p>
	 * 
	 * @return CacheMetricsBinder实例
	 */
	public CacheMetricsBinder getMetricsBinder() {
		return metricsBinder;
	}

	/**
	 * 获取当前缓存大小。
	 * 
	 * <p>设计意图：提供便捷方法直接获取L1缓存的当前条目数量，
	 * 避免调用方需要了解Micrometer的查询API。</p>
	 * 
	 * <p>实现方式：通过MeterRegistry查找名为"hcc_cache_size"的Gauge指标并获取其当前值。</p>
	 * 
	 * @return 当前缓存条目数量，如果指标不存在则返回0
	 */
	public long getCacheSize() {
		if (metricsBinder != null && meterRegistry != null) {
			io.micrometer.core.instrument.Gauge gauge = meterRegistry.find("hcc_cache_size").gauge();
			if (gauge != null) {
				return (long) gauge.value();
			}
		}
		return 0L;
	}

}