package io.github.latcn.cache.core.handler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 空操作的缓存指标记录器实现。
 *
 * <p>
 * 设计意图：提供一个无任何实际操作的指标记录器实现，用于在不需要监控或MeterRegistry不可用时使用。 这样可以避免在代码中进行null检查，遵循空对象模式（Null
 * Object Pattern）。
 * </p>
 *
 * <p>
 * 使用场景：
 * <ul>
 * <li>用户未配置监控功能时</li>
 * <li>MeterRegistry为null时</li>
 * <li>测试环境中不需要监控指标时</li>
 * </ul>
 * </p>
 */
enum NoOpCacheMetricsRecorder implements CacheMetricsRecorder {

	INSTANCE;

	/**
	 * 记录L1缓存请求次数（空实现）。
	 */
	@Override
	public void recordL1Request() {
	}

	/**
	 * 记录L1缓存命中次数（空实现）。
	 */
	@Override
	public void recordL1Hit() {
	}

	/**
	 * 记录L1缓存未命中次数（空实现）。
	 */
	@Override
	public void recordL1Miss() {
	}

	/**
	 * 记录L2分布式缓存操作（空实现）。
	 * @param startTimeMs 操作开始时间戳（毫秒），用于计算操作耗时
	 * @param operationType 操作类型，如"get"、"put"、"delete"等
	 */
	@Override
	public void recordL2Operation(long startTimeMs, String operationType) {
	}

	/**
	 * 记录熔断器拒绝次数（空实现）。
	 */
	@Override
	public void recordCircuitBreakerRejection() {
	}

	/**
	 * 记录数据库操作（空实现）。
	 * @param startTimeMs 操作开始时间戳（毫秒），用于计算操作耗时
	 */
	@Override
	public void recordDbOperation(long startTimeMs) {
	}

	/**
	 * 记录缓存失效消息发布结果（空实现）。
	 * @param success 是否成功发布
	 */
	@Override
	public void recordInvalidationPublish(boolean success) {
	}

	/**
	 * 记录缓存失效消息接收结果（空实现）。
	 * @param success 是否成功接收
	 */
	@Override
	public void recordInvalidationReceive(boolean success) {
	}

	/**
	 * 记录SingleFlight去重次数（空实现）。
	 * @param type 去重类型，如"db"、"cache"等
	 */
	@Override
	public void recordSingleFlightDeduplication(String type) {
	}

	/**
	 * 判断指标记录器是否启用。
	 * @return 始终返回false，表示此记录器未启用
	 */
	@Override
	public boolean isEnabled() {
		return false;
	}

}

/**
 * 基于Micrometer的缓存指标记录器实现。
 *
 * <p>
 * 设计意图：提供完整的缓存性能监控能力，通过Micrometer抽象层支持多种监控系统
 * （如Prometheus、InfluxDB、Graphite等），实现对缓存系统的全面可观测性。
 * </p>
 *
 * <p>
 * 监控指标包括：
 * <ul>
 * <li>L1本地缓存：请求数、命中数、未命中数</li>
 * <li>L2分布式缓存：操作次数、操作延迟</li>
 * <li>数据库操作：操作次数、操作延迟</li>
 * <li>熔断器：拒绝次数</li>
 * <li>缓存失效：消息发布/接收的成功和失败次数</li>
 * <li>SingleFlight：请求去重次数</li>
 * </ul>
 * </p>
 *
 * <p>
 * 线程安全：此类是线程安全的，所有计数器和计时器都是线程安全的Micrometer组件。
 * </p>
 */
class MicrometerCacheMetricsRecorder implements CacheMetricsRecorder {

	/** Micrometer注册表，用于注册和管理所有监控指标 */
	private final MeterRegistry registry;

	/** L1缓存请求总数计数器 */
	private final Counter l1RequestsCounter;

	/** L1缓存命中总数计数器 */
	private final Counter l1HitsCounter;

	/** L1缓存未命中总数计数器 */
	private final Counter l1MissesCounter;

	/** L2缓存操作计数器映射，按操作类型分类（key为操作类型，value为计数器） */
	private final ConcurrentHashMap<String, Counter> l2OperationsCounters;

	/** L2缓存延迟计时器映射，按操作类型分类（key为操作类型，value为计时器） */
	private final ConcurrentHashMap<String, Timer> l2LatencyTimers;

	/** 熔断器拒绝总数计数器 */
	private final Counter circuitBreakerRejectionCounter;

	/** 数据库操作总数计数器 */
	private final Counter dbOperationsCounter;

	/** 数据库操作延迟计时器 */
	private final Timer dbLatencyTimer;

	/** 缓存失效消息发布成功计数器 */
	private final Counter invalidationPublishSuccessCounter;

	/** 缓存失效消息发布失败计数器 */
	private final Counter invalidationPublishFailureCounter;

	/** 缓存失效消息接收成功计数器 */
	private final Counter invalidationReceiveSuccessCounter;

	/** 缓存失效消息接收失败计数器 */
	private final Counter invalidationReceiveFailureCounter;

	/** SingleFlight去重计数器映射，按类型分类（key为类型，value为计数器） */
	private final ConcurrentHashMap<String, Counter> singleFlightDeduplicationCounters;

	/**
	 * 构造函数：初始化所有监控指标。
	 *
	 * <p>
	 * 设计意图：在构造时预先注册所有指标到MeterRegistry，避免运行时动态注册带来的性能开销。 所有指标都带有描述性信息和标签，便于在监控系统中进行查询和聚合。
	 * </p>
	 * @param registry Micrometer注册表，用于注册和管理监控指标
	 */
	MicrometerCacheMetricsRecorder(MeterRegistry registry) {
		this.registry = registry;
		this.l1RequestsCounter = Counter.builder("hcc_cache_requests_total")
			.description("Total number of L1 cache requests")
			.tag("cache_level", "L1")
			.register(registry);

		this.l1HitsCounter = Counter.builder("hcc_cache_hits_total")
			.description("Total number of L1 cache hits")
			.tag("cache_level", "L1")
			.register(registry);

		this.l1MissesCounter = Counter.builder("hcc_cache_misses_total")
			.description("Total number of L1 cache misses")
			.tag("cache_level", "L1")
			.register(registry);

		this.l2OperationsCounters = new ConcurrentHashMap<>();
		this.l2LatencyTimers = new ConcurrentHashMap<>();

		this.circuitBreakerRejectionCounter = Counter.builder("hcc_circuit_breaker_rejected_total")
			.description("Total number of calls rejected when circuit is OPEN")
			.tag("component", "circuitbreaker")
			.register(registry);

		this.dbOperationsCounter = Counter.builder("hcc_db_operations_total")
			.description("Total number of database operations")
			.register(registry);

		this.dbLatencyTimer = Timer.builder("hcc_db_latency_seconds")
			.description("Database operation latency")
			.publishPercentiles(0.5, 0.9, 0.95, 0.99)
			.register(registry);

		this.invalidationPublishSuccessCounter = Counter.builder("hcc_invalidation_publish_total")
			.description("Total number of invalidation messages published")
			.tag("result", "success")
			.register(registry);

		this.invalidationPublishFailureCounter = Counter.builder("hcc_invalidation_publish_total")
			.description("Total number of invalidation messages published")
			.tag("result", "failure")
			.register(registry);

		this.invalidationReceiveSuccessCounter = Counter.builder("hcc_invalidation_receive_total")
			.description("Total number of invalidation messages received")
			.tag("result", "success")
			.register(registry);

		this.invalidationReceiveFailureCounter = Counter.builder("hcc_invalidation_receive_total")
			.description("Total number of invalidation messages received")
			.tag("result", "failure")
			.register(registry);

		this.singleFlightDeduplicationCounters = new ConcurrentHashMap<>();
	}

	/**
	 * 获取或创建L2缓存操作计数器。
	 *
	 * <p>
	 * 设计意图：使用懒加载方式创建计数器，避免预先创建大量可能用不到的计数器。 使用ConcurrentHashMap保证线程安全。
	 * </p>
	 * @param operationType 操作类型，如"get"、"put"、"delete"等
	 * @return 对应操作类型的计数器
	 */
	private Counter getL2OperationsCounter(String operationType) {
		return l2OperationsCounters.computeIfAbsent(operationType,
				key -> Counter.builder("hcc_distributed_cache_operations_total")
					.description("Total number of distributed cache operations")
					.tag("cache_level", "L2")
					.tag("operation", key)
					.register(registry));
	}

	/**
	 * 获取或创建L2缓存延迟计时器。
	 *
	 * <p>
	 * 设计意图：使用懒加载方式创建计时器，避免预先创建大量可能用不到的计时器。 配置了常用的百分位数（0.5, 0.9, 0.95, 0.99）用于延迟分析。
	 * </p>
	 * @param operationType 操作类型，如"get"、"put"、"delete"等
	 * @return 对应操作类型的计时器
	 */
	private Timer getL2LatencyTimer(String operationType) {
		return l2LatencyTimers.computeIfAbsent(operationType,
				key -> Timer.builder("hcc_distributed_cache_latency_seconds")
					.description("Distributed cache operation latency")
					.tag("cache_level", "L2")
					.tag("operation", key)
					.publishPercentiles(0.5, 0.9, 0.95, 0.99)
					.register(registry));
	}

	/**
	 * 获取或创建SingleFlight去重计数器。
	 *
	 * <p>
	 * 设计意图：使用懒加载方式创建计数器，按类型统计去重效果， 帮助识别哪些场景下SingleFlight机制最有效。
	 * </p>
	 * @param type 去重类型，如"db"表示数据库查询去重，"cache"表示缓存查询去重
	 * @return 对应类型的计数器
	 */
	private Counter getSingleFlightDeduplicationCounter(String type) {
		return singleFlightDeduplicationCounters.computeIfAbsent(type,
				key -> Counter.builder("hcc_singleflight_deduplicated_total")
					.description("Total number of deduplicated requests")
					.tag("type", key)
					.register(registry));
	}

	/**
	 * 记录L1缓存请求次数。
	 *
	 * <p>
	 * 每次访问L1缓存时调用，用于计算缓存命中率。
	 * </p>
	 */
	@Override
	public void recordL1Request() {
		l1RequestsCounter.increment();
	}

	/**
	 * 记录L1缓存命中次数。
	 *
	 * <p>
	 * 当数据在L1缓存中找到时调用，配合recordL1Request可计算命中率。
	 * </p>
	 */
	@Override
	public void recordL1Hit() {
		l1HitsCounter.increment();
	}

	/**
	 * 记录L1缓存未命中次数。
	 *
	 * <p>
	 * 当数据在L1缓存中未找到时调用，表示需要从L2缓存或数据库获取数据。
	 * </p>
	 */
	@Override
	public void recordL1Miss() {
		l1MissesCounter.increment();
	}

	/**
	 * 记录L2分布式缓存操作。
	 *
	 * <p>
	 * 同时记录操作次数和操作延迟，用于监控分布式缓存（如Redis）的性能。 通过operationType标签可以区分不同类型的操作。
	 * </p>
	 * @param startTimeMs 操作开始时间戳（毫秒），用于计算操作耗时 在操作开始前记录System.currentTimeMillis()，
	 * 方法内部通过(当前时间 - startTimeMs)计算耗时
	 * @param operationType 操作类型，如"get"、"put"、"delete"、"batch_put"等， 用于分类统计不同操作的性能指标
	 */
	@Override
	public void recordL2Operation(long startTimeMs, String operationType) {
		getL2OperationsCounter(operationType).increment();
		getL2LatencyTimer(operationType).record(System.currentTimeMillis() - startTimeMs, TimeUnit.MILLISECONDS);
	}

	/**
	 * 记录熔断器拒绝次数。
	 *
	 * <p>
	 * 当熔断器处于OPEN状态并拒绝请求时调用，用于监控分布式缓存的健康状态。 高拒绝率可能表示分布式缓存出现故障或响应缓慢。
	 * </p>
	 */
	@Override
	public void recordCircuitBreakerRejection() {
		circuitBreakerRejectionCounter.increment();
	}

	/**
	 * 记录数据库操作。
	 *
	 * <p>
	 * 同时记录数据库操作次数和操作延迟，用于监控数据库查询性能。 高延迟可能表示数据库负载过高或查询需要优化。
	 * </p>
	 * @param startTimeMs 操作开始时间戳（毫秒），用于计算操作耗时 在操作开始前记录System.currentTimeMillis()，
	 * 方法内部通过(当前时间 - startTimeMs)计算耗时
	 */
	@Override
	public void recordDbOperation(long startTimeMs) {
		dbOperationsCounter.increment();
		dbLatencyTimer.record(System.currentTimeMillis() - startTimeMs, TimeUnit.MILLISECONDS);
	}

	/**
	 * 记录缓存失效消息发布结果。
	 *
	 * <p>
	 * 用于监控缓存失效消息的发布成功率，失败率高可能表示消息队列或Redis Pub/Sub出现问题。
	 * </p>
	 * @param success 是否成功发布消息
	 */
	@Override
	public void recordInvalidationPublish(boolean success) {
		if (success) {
			invalidationPublishSuccessCounter.increment();
		}
		else {
			invalidationPublishFailureCounter.increment();
		}
	}

	/**
	 * 记录缓存失效消息接收结果。
	 *
	 * <p>
	 * 用于监控缓存失效消息的接收成功率，失败率高可能影响多节点间的缓存一致性。
	 * </p>
	 * @param success 是否成功接收消息
	 */
	@Override
	public void recordInvalidationReceive(boolean success) {
		if (success) {
			invalidationReceiveSuccessCounter.increment();
		}
		else {
			invalidationReceiveFailureCounter.increment();
		}
	}

	/**
	 * 记录SingleFlight去重次数。
	 *
	 * <p>
	 * SingleFlight机制用于合并并发请求，避免对同一资源的重复查询。 通过此指标可以评估SingleFlight的效果，高去重率表示存在大量并发重复请求。
	 * </p>
	 * @param type 去重类型，如"db"表示数据库查询去重，"cache"表示缓存查询去重
	 */
	@Override
	public void recordSingleFlightDeduplication(String type) {
		getSingleFlightDeduplicationCounter(type).increment();
	}

	/**
	 * 判断指标记录器是否启用。
	 *
	 * <p>
	 * 用于在运行时判断是否需要记录指标，避免在禁用状态下产生不必要的性能开销。
	 * </p>
	 * @return 始终返回true，表示此记录器已启用
	 */
	@Override
	public boolean isEnabled() {
		return true;
	}

}
