package io.github.latcn.cache.spring.monitor;

import io.github.latcn.cache.core.circuitbreaker.CacheCircuitBreaker;
import io.github.latcn.cache.core.circuitbreaker.CircuitBreakerState;
import io.github.latcn.cache.core.distributed.DistributedCacheManager;
import io.github.latcn.cache.core.hotspot.reads.ReadHotspotDetector;
import io.github.latcn.cache.core.hotspot.writes.WriteHotspotDetector;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;

/**
 * 缓存指标绑定器，用于将缓存组件的状态指标绑定到Micrometer注册表。
 * 
 * <p>设计意图：实现MeterBinder接口，将缓存系统的运行状态以Gauge（瞬时值）形式暴露给监控系统。
 * 与MicrometerCacheMetricsRecorder不同，后者记录的是操作过程中的动态指标（计数器、计时器），
 * 而本类主要绑定的是缓存组件的当前状态信息（如缓存大小、命中率、熔断器状态等）。</p>
 * 
 * <p>绑定的指标包括：
 * <ul>
 *   <li>L1本地缓存：命中率、缓存大小、最大容量、驱逐次数</li>
 *   <li>L2分布式缓存：连接状态</li>
 *   <li>熔断器：状态、失败次数、成功次数</li>
 *   <li>热点检测：读热点key数量、写热点key数量</li>
 * </ul>
 * </p>
 * 
 * <p>使用方式：通过CacheMetricsManager创建并绑定到MeterRegistry。</p>
 */
@Slf4j
public class CacheMetricsBinder implements MeterBinder {

	/** 本地缓存管理器，用于获取L1缓存的状态信息 */
	private final LocalCacheManager localCacheManager;

	/** 分布式缓存管理器，用于获取L2缓存的连接状态 */
	private final DistributedCacheManager distributedCacheManager;

	/** 熔断器，用于获取熔断器的状态和统计数据 */
	private final CacheCircuitBreaker circuitBreaker;

	/** 读热点检测器，用于获取读热点key的数量 */
	private final ReadHotspotDetector readHotspotDetector;

	/** 写热点检测器，用于获取写热点key的数量 */
	private final WriteHotspotDetector writeHotspotDetector;

	/** Micrometer注册表，用于注册指标 */
	private MeterRegistry meterRegistry;

	/**
	 * 构造函数：初始化缓存指标绑定器。
	 * 
	 * <p>设计意图：接收各个缓存组件的实例，用于后续绑定状态指标。
	 * 允许某些组件为null，绑定时会自动跳过null组件的指标。</p>
	 * 
	 * @param localCacheManager 本地缓存管理器，用于绑定L1缓存指标，可为null
	 * @param distributedCacheManager 分布式缓存管理器，用于绑定L2缓存指标，可为null
	 * @param circuitBreaker 熔断器，用于绑定熔断器指标，可为null
	 * @param readHotspotDetector 读热点检测器，用于绑定读热点指标，可为null
	 * @param writeHotspotDetector 写热点检测器，用于绑定写热点指标，可为null
	 */
	public CacheMetricsBinder(LocalCacheManager localCacheManager, DistributedCacheManager distributedCacheManager,
			CacheCircuitBreaker circuitBreaker, ReadHotspotDetector readHotspotDetector,
			WriteHotspotDetector writeHotspotDetector) {
		this.localCacheManager = localCacheManager;
		this.distributedCacheManager = distributedCacheManager;
		this.circuitBreaker = circuitBreaker;
		this.readHotspotDetector = readHotspotDetector;
		this.writeHotspotDetector = writeHotspotDetector;
	}

	/**
	 * 将缓存指标绑定到Micrometer注册表。
	 * 
	 * <p>设计意图：实现MeterBinder接口的核心方法，在Spring Boot Actuator自动配置时被调用。
	 * 使用try-catch包裹每个绑定过程，确保单个组件绑定失败不影响其他组件的指标绑定。</p>
	 * 
	 * @param registry Micrometer注册表，用于注册所有指标
	 */
	@Override
	public void bindTo(MeterRegistry registry) {
		this.meterRegistry = registry;

		try {
			bindLocalCacheMetrics();
		}
		catch (Exception e) {
			log.error("Failed to bind local cache metrics", e);
		}
		try {
			bindDistributedCacheMetrics();
		}
		catch (Exception e) {
			log.error("Failed to bind distributed cache metrics", e);
		}
		try {
			bindCircuitBreakerMetrics();
		}
		catch (Exception e) {
			log.error("Failed to bind circuit breaker metrics", e);
		}
		try {
			bindSystemPerformanceMetrics();
		}
		catch (Exception e) {
			log.error("Failed to bind system performance metrics", e);
		}
	}

	/**
	 * 绑定本地缓存（L1）相关指标。
	 * 
	 * <p>绑定的指标包括：
	 * <ul>
	 *   <li>hcc_cache_hit_ratio：缓存命中率（0.0-1.0）</li>
	 *   <li>hcc_cache_size：当前缓存条目数量</li>
	 *   <li>hcc_cache_max_size：最大配置容量</li>
	 *   <li>hcc_cache_evictions_total：驱逐次数</li>
	 * </ul>
	 * </p>
	 */
	private void bindLocalCacheMetrics() {
		if (localCacheManager == null) {
			return;
		}

		Gauge.builder("hcc_cache_hit_ratio", localCacheManager, manager -> manager.getStats().getHitRate())
			.description("Cache hit rate (0.0-1.0)")
			.tag("cache_level", "L1")
			.register(meterRegistry);

		Gauge.builder("hcc_cache_size", localCacheManager, manager -> manager.getStats().getSize())
			.description("Current number of entries in cache")
			.tag("cache_level", "L1")
			.baseUnit("entries")
			.register(meterRegistry);

		Gauge.builder("hcc_cache_max_size", localCacheManager, manager -> manager.getStats().getMaxSize())
			.description("Maximum configured cache size")
			.tag("cache_level", "L1")
			.baseUnit("entries")
			.register(meterRegistry);

		Gauge.builder("hcc_cache_evictions_total", localCacheManager, manager -> manager.getStats().getEvictionCount())
			.description("Total number of cache evictions")
			.tag("cache_level", "L1")
			.baseUnit("evictions")
			.register(meterRegistry);
	}

	/**
	 * 绑定分布式缓存（L2）相关指标。
	 * 
	 * <p>绑定的指标包括：
	 * <ul>
	 *   <li>hcc_distributed_cache_connected：Redis连接状态（1=已连接，0=未连接）</li>
	 * </ul>
	 * </p>
	 */
	private void bindDistributedCacheMetrics() {
		if (distributedCacheManager == null) {
			return;
		}

		Gauge
			.builder("hcc_distributed_cache_connected", distributedCacheManager,
					manager -> distributedCacheManager.isHealthy() ? 1 : 0)
			.description("Redis connection status (1=connected, 0=disconnected)")
			.tag("cache_level", "L2")
			.register(meterRegistry);
	}

	/**
	 * 绑定熔断器相关指标。
	 * 
	 * <p>绑定的指标包括：
	 * <ul>
	 *   <li>hcc_circuit_breaker_state：熔断器状态（0=CLOSED, 1=OPEN, 2=HALF_OPEN）</li>
	 *   <li>hcc_circuit_breaker_failures_total：当前失败次数</li>
	 *   <li>hcc_circuit_breaker_successes_total：当前成功次数</li>
	 * </ul>
	 * </p>
	 */
	private void bindCircuitBreakerMetrics() {
		if (circuitBreaker == null) {
			return;
		}

		Gauge.builder("hcc_circuit_breaker_state", circuitBreaker, breaker -> {
			CircuitBreakerState state = breaker.getStats().getState();
			switch (state) {
				case CLOSED:
					return 0.0;
				case OPEN:
					return 1.0;
				case HALF_OPEN:
					return 2.0;
				default:
					return 0.0;
			}
		})
			.description("Circuit breaker state (0=CLOSED, 1=OPEN, 2=HALF_OPEN)")
			.tag("component", "circuitbreaker")
			.baseUnit("state")
			.register(meterRegistry);

		Gauge
			.builder("hcc_circuit_breaker_failures_total", circuitBreaker,
					breaker -> breaker.getStats().getFailureCount())
			.description("Current failure count")
			.tag("component", "circuitbreaker")
			.baseUnit("failures")
			.register(meterRegistry);

		Gauge
			.builder("hcc_circuit_breaker_successes_total", circuitBreaker,
					breaker -> breaker.getStats().getSuccessCount())
			.description("Current success count")
			.tag("component", "circuitbreaker")
			.baseUnit("successes")
			.register(meterRegistry);
	}

	/**
	 * 绑定系统性能相关指标（热点检测）。
	 * 
	 * <p>绑定的指标包括：
	 * <ul>
	 *   <li>hcc_hotspot_read_hotkeys_count：读热点key数量</li>
	 *   <li>hcc_hotspot_write_hotkeys_count：写热点key数量</li>
	 * </ul>
	 * </p>
	 * 
	 * <p>热点key数量过多可能表示存在缓存穿透风险或需要优化访问模式。</p>
	 */
	private void bindSystemPerformanceMetrics() {
		if (readHotspotDetector != null) {
			Gauge.builder("hcc_hotspot_read_hotkeys_count", readHotspotDetector, readKey -> readKey.readHotKeyCount())
				.description("Number of read hotspot keys detected")
				.tag("hotspot_type", "read")
				.baseUnit("keys")
				.register(meterRegistry);
		}
		if (writeHotspotDetector != null) {
			Gauge
				.builder("hcc_hotspot_write_hotkeys_count", writeHotspotDetector,
						writeKey -> writeKey.writeHotKeyCount())
				.description("Number of write hotspot keys detected")
				.tag("hotspot_type", "write")
				.baseUnit("keys")
				.register(meterRegistry);
		}
	}

}