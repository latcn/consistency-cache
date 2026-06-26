package io.github.latcn.cache.core.manager;

/**
 * SingleFlight 执行结果包装类，包含执行结果和是否被去重的信息。
 * 
 * <p>设计意图：让调用方能够知道当前请求是否被 SingleFlight 合并，
 * 从而决定是否记录监控指标。这对于评估 SingleFlight 的效果至关重要。</p>
 * 
 * <p>使用场景：
 * <ul>
 *   <li>在 Handler 层判断是否需要记录去重监控指标</li>
 *   <li>统计 SingleFlight 的去重效果</li>
 * </ul>
 * </p>
 */
public class SingleFlightResult<V> {

	/** 执行结果值 */
	private final V value;

	/** 是否被去重（合并到已有请求中） */
	private final boolean deduplicated;

	/** 执行过程中发生的异常，如果有的话 */
	private final Throwable exception;

	private SingleFlightResult(V value, boolean deduplicated, Throwable exception) {
		this.value = value;
		this.deduplicated = deduplicated;
		this.exception = exception;
	}

	/**
	 * 创建成功的执行结果。
	 * 
	 * @param value 执行结果值
	 * @param deduplicated 是否被去重
	 * @return 成功的结果包装
	 */
	public static <V> SingleFlightResult<V> success(V value, boolean deduplicated) {
		return new SingleFlightResult<>(value, deduplicated, null);
	}

	/**
	 * 创建失败的执行结果。
	 * 
	 * @param exception 执行过程中发生的异常
	 * @param deduplicated 是否被去重
	 * @return 失败的结果包装
	 */
	public static <V> SingleFlightResult<V> failure(Throwable exception, boolean deduplicated) {
		return new SingleFlightResult<>(null, deduplicated, exception);
	}

	/**
	 * 获取执行结果值。
	 * 
	 * @return 执行结果值，如果执行失败则返回 null
	 */
	public V getValue() {
		return value;
	}

	/**
	 * 判断是否被去重。
	 * 
	 * <p>当多个并发请求访问同一 key 时，只有第一个请求会实际执行 loader，
	 * 其他请求会被合并等待结果，这些被合并的请求就是"被去重"的。</p>
	 * 
	 * @return true 表示被去重，false 表示是新请求并实际执行了 loader
	 */
	public boolean isDeduplicated() {
		return deduplicated;
	}

	/**
	 * 判断执行是否成功。
	 * 
	 * @return true 表示执行成功，false 表示执行过程中发生异常
	 */
	public boolean isSuccess() {
		return exception == null;
	}

	/**
	 * 获取执行过程中发生的异常。
	 * 
	 * @return 异常对象，如果执行成功则返回 null
	 */
	public Throwable getException() {
		return exception;
	}

	/**
	 * 获取结果值或抛出异常。
	 * 
	 * <p>如果执行成功则返回结果值，如果执行失败则抛出原始异常。
	 * 对于 RuntimeException 和 Error 直接抛出，对于其他异常包装为 RuntimeException。</p>
	 * 
	 * @return 执行结果值
	 * @throws RuntimeException 如果执行过程中发生异常
	 * @throws Error 如果执行过程中发生 Error
	 */
	public V getValueOrThrow() {
		if (exception != null) {
			if (exception instanceof RuntimeException) {
				throw (RuntimeException) exception;
			}
			else if (exception instanceof Error) {
				throw (Error) exception;
			}
			else {
				throw new RuntimeException(exception);
			}
		}
		return value;
	}

}