package io.github.latcn.cache.core.executor;

import io.github.latcn.cache.core.model.CacheKey;
import io.github.latcn.cache.core.model.CacheValue;
import io.github.latcn.cache.core.pubsub.Broadcaster;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 缓存处理器 根据key的cacheLevel来确定哪个执行器获取key
 */
public interface CacheExecutor {

	void setBroadcaster(Broadcaster broadcaster);

	/**
	 * 当持久数据变更时，失效L2/L1缓存
	 * @param cacheKey
	 */
	void evict(CacheKey cacheKey);

	/**
	 * 校验key是否存在，布隆过滤器等
	 * @param cacheKey
	 * @return
	 */
	boolean exists(CacheKey cacheKey);

	/**
	 * get
	 * @param cacheKey
	 * @param doSingleFlightFun if get is null then do the function
	 * @return
	 */
	CacheValue get(CacheKey cacheKey, Function<Object, Object> doSingleFlightFun);

	CompletableFuture<Boolean> evictAsync(CacheKey cacheKey);

	CompletableFuture<CacheValue> getAsync(CacheKey cacheKey, Function<Object, Object> doSingleFlightFun);

}
