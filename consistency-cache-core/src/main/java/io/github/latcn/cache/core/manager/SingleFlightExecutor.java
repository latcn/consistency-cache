package io.github.latcn.cache.core.manager;

import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 线程leader模式获取数据，合并并发请求避免重复执行。
 *
 * <p>
 * 设计意图：当多个线程同时请求同一 key 的数据时，只让一个线程（leader） 执行实际的数据加载操作，其他线程等待结果。这有效防止了缓存击穿问题，
 * 大幅降低后端数据库或分布式缓存的压力。
 * </p>
 *
 * <p>
 * 监控支持：通过 executeWithResult 方法返回 SingleFlightResult， 调用方可以判断请求是否被去重，从而记录监控指标评估
 * SingleFlight 效果。
 * </p>
 */
public class SingleFlightExecutor {

	private final ConcurrentHashMap<Object, CompletableFuture<SingleFlightResult<Object>>> inflightCalls = new ConcurrentHashMap<>();

	/**
	 * 执行 SingleFlight 请求合并，返回结果和去重信息。
	 *
	 * <p>
	 * 设计意图：让调用方能够知道当前请求是否被合并，从而决定是否记录监控指标。 这是评估 SingleFlight 效果的关键信息。
	 * </p>
	 * @param key 请求的唯一标识 key
	 * @param doSingleFlightFun 实际执行数据加载的函数
	 * @return 包含结果值和去重信息的 SingleFlightResult
	 */
	public <K, V> SingleFlightResult<V> executeWithResult(K key, Function<K, V> doSingleFlightFun) {
		CompletableFuture<SingleFlightResult<Object>> future = this.inflightCalls.get(key);
		if (future != null) {
			return waitForResult(future, true);
		}

		CompletableFuture<SingleFlightResult<Object>> newFuture = new CompletableFuture<>();
		CompletableFuture<SingleFlightResult<Object>> existing = this.inflightCalls.putIfAbsent(key, newFuture);

		if (existing != null) {
			return waitForResult(existing, true);
		}

		try {
			V result = doSingleFlightFun.apply(key);
			SingleFlightResult<Object> successResult = SingleFlightResult.success(result, false);
			newFuture.complete(successResult);
			SingleFlightResult<V> typedResult = (SingleFlightResult<V>) successResult;
			return typedResult;
		}
		catch (Throwable t) {
			SingleFlightResult<Object> failureResult = SingleFlightResult.failure(t, false);
			newFuture.complete(failureResult);
			throw t;
		}
		finally {
			this.inflightCalls.remove(key);
		}
	}

	/**
	 * 等待 Future 结果，处理中断和执行异常。
	 * @param future 正在执行的 Future
	 * @param deduplicated 是否被去重
	 * @return 包含结果和去重信息的 SingleFlightResult
	 */
	private <V> SingleFlightResult<V> waitForResult(CompletableFuture<SingleFlightResult<Object>> future,
			boolean deduplicated) {
		try {
			SingleFlightResult<Object> result = future.get();
			if (result.isSuccess()) {
				return SingleFlightResult.success((V) result.getValue(), deduplicated);
			}
			else {
				return SingleFlightResult.failure(result.getException(), deduplicated);
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw CacheException.wrap(e, CacheError.SINGLE_FLIGHT_INTERRUPTED);
		}
		catch (Exception e) {
			throw CacheException.wrap(e.getCause(), CacheError.EXECUTION_FAILED);
		}
	}

}