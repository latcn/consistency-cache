package io.github.latcn.cache.spring.monitor;

import io.github.latcn.cache.core.circuitbreaker.CacheCircuitBreaker;
import io.github.latcn.cache.core.distributed.DistributedCacheManager;
import io.github.latcn.cache.core.hotspot.HotspotDetector;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.github.latcn.cache.core.monitor.CacheMetricsManager;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.CollectorRegistry;

/**
 * Prometheus缓存指标配置类，提供Prometheus兼容的监控指标端点。
 *
 * <p>
 * 设计意图：专门为Prometheus监控系统提供指标导出能力。Prometheus是目前最流行的
 * 云原生监控系统，通过此类可以方便地将缓存指标集成到Prometheus生态中。
 * </p>
 *
 * <p>
 * 主要功能：
 * <ul>
 * <li>创建并配置PrometheusMeterRegistry</li>
 * <li>集成CacheMetricsManager管理缓存指标</li>
 * <li>绑定JVM和系统性能指标（GC、内存、线程、CPU等）</li>
 * <li>提供Prometheus文本格式的指标导出接口</li>
 * </ul>
 * </p>
 *
 * <p>
 * 使用场景：
 * <ul>
 * <li>在Spring Boot应用中通过/actuator/prometheus端点暴露指标</li>
 * <li>在非Spring环境中通过自定义端点暴露指标</li>
 * <li>与Prometheus Server集成进行定期指标采集</li>
 * </ul>
 * </p>
 *
 * <p>
 * 指标格式：遵循Prometheus文本格式规范，可通过HTTP GET请求获取。
 * </p>
 */
public class PrometheusCacheMetrics {

	/** Prometheus Meter Registry，用于注册和导出Prometheus格式的指标 */
	private final PrometheusMeterRegistry prometheusMeterRegistry;

	/** 缓存指标管理器，用于管理缓存相关的监控指标 */
	private final CacheMetricsManager cacheMetricsManager;

	/**
	 * 构造函数：初始化Prometheus缓存指标配置。
	 *
	 * <p>
	 * 设计意图：在构造时完成以下初始化工作：
	 * <ol>
	 * <li>创建PrometheusMeterRegistry作为指标注册表</li>
	 * <li>创建CacheMetricsManager并绑定缓存组件指标</li>
	 * <li>绑定JVM性能指标（GC、内存、线程）用于系统监控</li>
	 * <li>绑定系统性能指标（CPU、运行时间）用于资源监控</li>
	 * </ol>
	 * </p>
	 *
	 * <p>
	 * JVM和系统指标的绑定使得监控系统能够全面了解应用的运行状态， 不仅包括缓存性能，还包括整体系统健康状况。
	 * </p>
	 * @param localCacheManager 本地缓存管理器，用于绑定L1缓存指标
	 * @param distributedCacheManager 分布式缓存管理器，用于绑定L2缓存指标
	 * @param circuitBreaker 熔断器，用于绑定熔断器指标
	 * @param readHotspotDetector 读热点检测器，用于绑定读热点指标
	 * @param writeHotspotDetector 写热点检测器，用于绑定写热点指标
	 */
	public PrometheusCacheMetrics(LocalCacheManager localCacheManager, DistributedCacheManager distributedCacheManager,
			CacheCircuitBreaker circuitBreaker, HotspotDetector readHotspotDetector,
			HotspotDetector writeHotspotDetector) {

		// Configure Prometheus registry
		this.prometheusMeterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

		// Create metrics manager with Prometheus registry
		this.cacheMetricsManager = new CacheMetricsManager(prometheusMeterRegistry, localCacheManager,
				distributedCacheManager, circuitBreaker, readHotspotDetector, writeHotspotDetector);

		// Add JVM metrics for system performance monitoring
		io.micrometer.core.instrument.binder.jvm.JvmGcMetrics jvmGcMetrics = new io.micrometer.core.instrument.binder.jvm.JvmGcMetrics();
		jvmGcMetrics.bindTo(prometheusMeterRegistry);

		io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics jvmMemoryMetrics = new io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics();
		jvmMemoryMetrics.bindTo(prometheusMeterRegistry);

		io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics jvmThreadMetrics = new io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics();
		jvmThreadMetrics.bindTo(prometheusMeterRegistry);

		io.micrometer.core.instrument.binder.system.ProcessorMetrics processorMetrics = new io.micrometer.core.instrument.binder.system.ProcessorMetrics();
		processorMetrics.bindTo(prometheusMeterRegistry);

		io.micrometer.core.instrument.binder.system.UptimeMetrics uptimeMetrics = new io.micrometer.core.instrument.binder.system.UptimeMetrics();
		uptimeMetrics.bindTo(prometheusMeterRegistry);
	}

	/**
	 * 获取Prometheus格式的指标数据。
	 *
	 * <p>
	 * 设计意图：提供Prometheus文本格式的指标导出接口，供Prometheus Server定期采集。
	 * 此方法应通过HTTP端点暴露，通常配置在/actuator/prometheus或/metrics路径。
	 * </p>
	 *
	 * <p>
	 * 返回格式：遵循Prometheus文本格式规范，包含所有已注册的指标及其当前值。
	 * </p>
	 * @return Prometheus文本格式的指标数据字符串
	 */
	public String getMetrics() {
		return prometheusMeterRegistry.scrape();
	}

	/**
	 * 获取底层的Prometheus CollectorRegistry。
	 *
	 * <p>
	 * 设计意图：提供对底层CollectorRegistry的直接访问，用于高级使用场景。 CollectorRegistry是Prometheus
	 * Java客户端的核心组件，可以用于：
	 * <ul>
	 * <li>注册自定义的Prometheus Collector</li>
	 * <li>与第三方Prometheus库集成</li>
	 * <li>进行高级的指标查询和操作</li>
	 * </ul>
	 * </p>
	 * @return Prometheus CollectorRegistry实例
	 */
	public CollectorRegistry getCollectorRegistry() {
		return prometheusMeterRegistry.getPrometheusRegistry();
	}

	/**
	 * 获取缓存指标管理器。
	 *
	 * <p>
	 * 设计意图：提供对CacheMetricsManager的访问，用于：
	 * <ul>
	 * <li>获取MeterRegistry进行自定义指标注册</li>
	 * <li>查询特定的缓存指标值</li>
	 * <li>动态管理缓存监控配置</li>
	 * </ul>
	 * </p>
	 * @return CacheMetricsManager实例
	 */
	public CacheMetricsManager getCacheMetricsManager() {
		return cacheMetricsManager;
	}

}
