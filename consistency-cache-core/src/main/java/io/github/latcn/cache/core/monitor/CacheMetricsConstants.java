package io.github.latcn.cache.core.monitor;

/**
 * 缓存监控指标常量定义。
 *
 * <p>
 * 设计意图：统一管理监控指标中使用的字符串常量，避免魔法值， 提高代码的可维护性和一致性。
 * </p>
 */
public final class CacheMetricsConstants {

	private CacheMetricsConstants() {
	}

	/**
	 * SingleFlight 去重类型常量
	 */
	public static class SingleFlightDeduplicationType {

		private SingleFlightDeduplicationType() {
		}

		/** 缓存查询去重 */
		public static final String CACHE = "cache";

		/** 数据库查询去重 */
		public static final String DB = "db";

	}

	/**
	 * L2 分布式缓存操作类型常量
	 */
	public static class L2OperationType {

		private L2OperationType() {
		}

		/** 获取操作 */
		public static final String GET = "get";

		/** 删除操作 */
		public static final String DELETE = "delete";

		/** 写入操作 */
		public static final String PUT = "put";

		/** 批量写入操作 */
		public static final String BATCH_PUT = "batch_put";

		/** 批量删除操作 */
		public static final String BATCH_DELETE = "batch_delete";

	}

	/**
	 * 缓存失效操作类型常量
	 */
	public static class InvalidationOperation {

		private InvalidationOperation() {
		}

		/** 成功 */
		public static final boolean SUCCESS = true;

		/** 失败 */
		public static final boolean FAILURE = false;

	}

	/**
	 * 缓存级别标签常量
	 */
	public static class CacheLevelTag {

		private CacheLevelTag() {
		}

		/** 本地缓存 L1 */
		public static final String L1 = "L1";

		/** 分布式缓存 L2 */
		public static final String L2 = "L2";

	}

	/**
	 * 组件标签常量
	 */
	public static class ComponentTag {

		private ComponentTag() {
		}

		/** 熔断器组件 */
		public static final String CIRCUIT_BREAKER = "circuitbreaker";

	}

}