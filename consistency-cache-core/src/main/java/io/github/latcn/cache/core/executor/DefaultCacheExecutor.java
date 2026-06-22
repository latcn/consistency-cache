package io.github.latcn.cache.core.executor;

import io.github.latcn.cache.core.circuitbreaker.CacheCircuitBreaker;
import io.github.latcn.cache.core.distributed.DistributedCacheManager;
import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import io.github.latcn.cache.core.hotspot.reads.ReadHotspotDetector;
import io.github.latcn.cache.core.hotspot.writes.WriteHotspotDetector;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.github.latcn.cache.core.local.LocalCacheMarkerManager;
import io.github.latcn.cache.core.manager.CacheManager;
import io.github.latcn.cache.core.manager.SingleFlightExecutor;
import io.github.latcn.cache.core.model.CacheKey;
import io.github.latcn.cache.core.model.CacheLevel;
import io.github.latcn.cache.core.model.CacheValue;
import io.github.latcn.cache.core.model.ConsistencyLevel;
import io.github.latcn.cache.core.model.NodeInstanceHolder;
import io.github.latcn.cache.core.pubsub.Broadcaster;
import java.util.List;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * DefaultCacheExecutor with circuit breaker protection.
 */
@Slf4j
public class DefaultCacheExecutor implements CacheExecutor {

	private final LocalCacheManager localCacheManager;

	private final DistributedCacheManager distributedCacheManager;

	private final LocalCacheMarkerManager localCacheMarkerManager;

	private final SingleFlightExecutor dbSingleFlightExecutor;

	private final SingleFlightExecutor distributedCacheSingleFlightExecutor;

	private final ReadHotspotDetector readStatistics;

	private final WriteHotspotDetector writeHotspotDetector;

	private final CacheCircuitBreaker cacheCircuitBreaker;

	private final CacheBloomFilter cacheBloomFilter;

	private Broadcaster broadcaster;

	public DefaultCacheExecutor(LocalCacheManager localCacheManager, DistributedCacheManager distributedCacheManager,
			LocalCacheMarkerManager localCacheMarkerManager, WriteHotspotDetector writeHotspotDetector,
			ReadHotspotDetector readStatistics, CacheCircuitBreaker cacheCircuitBreaker,
			CacheBloomFilter cacheBloomFilter) {
		this(CacheExecutorConfig.builder()
			.localCacheManager(localCacheManager)
			.distributedCacheManager(distributedCacheManager)
			.localCacheMarkerManager(localCacheMarkerManager)
			.writeHotspotDetector(writeHotspotDetector)
			.readStatistics(readStatistics)
			.cacheCircuitBreaker(cacheCircuitBreaker)
			.cacheBloomFilter(cacheBloomFilter)
			.build());
	}

	public DefaultCacheExecutor(CacheExecutorConfig config) {
		this.localCacheManager = config.getLocalCacheManager();
		this.distributedCacheManager = config.getDistributedCacheManager();
		this.localCacheMarkerManager = config.getLocalCacheMarkerManager();
		this.dbSingleFlightExecutor = new SingleFlightExecutor();
		this.distributedCacheSingleFlightExecutor = new SingleFlightExecutor();
		this.writeHotspotDetector = config.getWriteHotspotDetector();
		this.readStatistics = config.getReadStatistics();
		this.cacheCircuitBreaker = config.getCacheCircuitBreaker();
		this.cacheBloomFilter = config.getCacheBloomFilter();
		log.info("Initialized DefaultCacheExecutor with circuit breaker protection");
	}

	public void setBroadcaster(Broadcaster broadcaster) {
		this.broadcaster = broadcaster;
	}

	/**
	 * 数据变更，失效L1/L2缓存，本地缓存需要广播失效消息
	 * @param cacheKey
	 */
	@Override
	public void evict(CacheKey cacheKey) {
		checkCacheKey(cacheKey);
		if (cacheKey.getCacheLevel() == CacheLevel.LOCAL_CACHE) {
			evictLocalCache(cacheKey);
		}
		else {
			evictDistributedCache(cacheKey);
		}
	}

	private void evictLocalCache(CacheKey cacheKey) {
		deleteLocalCache(cacheKey);
		// 判断是否写热key， 写热key且不要求较强一致性的数据 禁用广播
		if (cacheKey.isBroadcastEnabled()) {
			if (cacheKey.getConsistencyLevel() == ConsistencyLevel.HIGH
					|| !this.writeHotspotDetector.shouldBypassL1(cacheKey.getKey())) {
				this.broadcaster.addKey(cacheKey);
			}
		}
	}

	private void evictDistributedCache(CacheKey cacheKey) {
		this.distributedCacheManager.remove(cacheKey);
		if (cacheKey.getCacheLevel() == CacheLevel.ADAPTIVE_CACHE) {
			if (cacheKey.isBroadcastEnabled()) {
				List<String> nodeIds = this.localCacheMarkerManager.getActiveNodes(cacheKey.getKey().toString());
				if (nodeIds.size() > 0) {
					this.localCacheMarkerManager.removeLocalCacheUsage(cacheKey.getKey().toString());
					if (!(nodeIds.size() == 1 && NodeInstanceHolder.getNodeId().equals(nodeIds.get(0)))) {
						this.broadcaster.addKey(cacheKey);
					}
					deleteLocalCache(cacheKey);
				}
			}
			else {
				deleteLocalCache(cacheKey);
			}
		}
	}

	/**
	 * 只有get miss时 才调用
	 * @param cacheKey
	 * @param cacheValue
	 */
	private void add(CacheKey cacheKey, CacheValue cacheValue) {
		checkCacheKey(cacheKey);
		if (cacheKey.getCacheLevel() == CacheLevel.LOCAL_CACHE) {
			this.localCacheManager.put(cacheKey, cacheValue);
			return;
		}
		// ADAPTIVE_CACHE 本地缓存 由本地节点判断是否读热key, 读热key才缓存到本地
		if (cacheKey.getCacheLevel() == CacheLevel.ADAPTIVE_CACHE || cacheKey.getCacheLevel() == CacheLevel.L2_CACHE) {
			this.distributedCacheManager.put(cacheKey, cacheValue);
		}
	}

	/**
	 * deleteLocalCache
	 * @param cacheKey
	 */
	@Override
	public void deleteLocalCache(CacheKey cacheKey) {
		this.localCacheManager.remove(cacheKey);
		// Record invalidation for hotspot detection (write operation)
		this.writeHotspotDetector.recordInvalidation(cacheKey.getKey());
	}

	@Override
	public boolean exists(CacheKey cacheKey) {
		checkCacheKey(cacheKey);
		if (cacheKey.getCacheLevel() == CacheLevel.LOCAL_CACHE) {
			return this.localCacheManager.containKey(cacheKey);
		}
		// ADAPTIVE_CACHE key是否存在以L2为主
		if (cacheKey.getCacheLevel() == CacheLevel.ADAPTIVE_CACHE || cacheKey.getCacheLevel() == CacheLevel.L2_CACHE) {
			return this.distributedCacheManager.containKey(cacheKey);
		}
		return false;
	}

	@Override
	public CacheValue get(CacheKey cacheKey, Function<Object, Object> doSingleFlightFun) {
		checkCacheKey(cacheKey);
		this.readStatistics.recordRead(cacheKey.getKey());
		// 布隆过滤器校验
		if (!existsInBloomFilter(cacheKey)) {
			return null;
		}
		CacheLevel level = cacheKey.getCacheLevel();
		if (level == CacheLevel.LOCAL_CACHE) {
			return getFromLocalCache(cacheKey, doSingleFlightFun);
		}
		else if (level == CacheLevel.ADAPTIVE_CACHE) {
			return getFromAdaptiveCache(cacheKey, doSingleFlightFun);
		}
		else if (level == CacheLevel.L2_CACHE) {
			return getFromL2Cache(cacheKey, doSingleFlightFun);
		}
		return null;
	}

	private CacheValue getFromLocalCache(CacheKey cacheKey, Function<Object, Object> doSingleFlightFun) {
		CacheValue cacheValue = this.localCacheManager.get(cacheKey);
		if (cacheValue == null) {
			cacheValue = loadFromDb(this.localCacheManager, cacheKey, doSingleFlightFun);
		}
		return cacheValue;
	}

	private CacheValue getFromAdaptiveCache(CacheKey cacheKey, Function<Object, Object> doSingleFlightFun) {
		CacheValue cacheValue = null;
		// 写热 key 直接从 L2 获取
		boolean isWriteHotKey = this.writeHotspotDetector.shouldBypassL1(cacheKey.getKey());
		if (isWriteHotKey) {
			cacheValue = this.localCacheManager.get(cacheKey);
		}
		if (cacheValue == null) {
			cacheValue = getValueFromDistributedCache(cacheKey);
		}
		// 布隆过滤器判断是否存在，统计是否热 key
		if (cacheValue == null) {
			// L2 from db
			cacheValue = loadFromDb(this.distributedCacheManager, cacheKey, doSingleFlightFun);
			// Enhance if hot key
			if (!isWriteHotKey && this.readStatistics.isHotKey(cacheKey.getKey())) {
				// 向 redis 上报使用本地缓存的热 key, 更新使用本地缓存的数量
				if (cacheKey.isBroadcastEnabled()) {
					this.localCacheMarkerManager.markLocalCacheUsage(cacheKey.getKey().toString(),
							cacheValue.getExpireTime());
				}
				this.localCacheManager.put(cacheKey, cacheValue);
			}
		}
		return cacheValue;
	}

	private CacheValue getFromL2Cache(CacheKey cacheKey, Function<Object, Object> doSingleFlightFun) {
		CacheValue cacheValue = getValueFromDistributedCache(cacheKey);
		if (cacheValue == null) {
			cacheValue = loadFromDb(this.distributedCacheManager, cacheKey, doSingleFlightFun);
		}
		return cacheValue;
	}

	private CacheValue getValueFromDistributedCache(CacheKey cacheKey) {
		CacheValue cacheValue;
		try {
			cacheValue = cacheCircuitBreaker.execute(() -> this.distributedCacheSingleFlightExecutor.execute(cacheKey,
					(k) -> this.distributedCacheManager.get(k)));
		}
		catch (CacheCircuitBreaker.CircuitBreakerOpenException e) {
			log.warn("Circuit breaker OPEN, bypassing distributed cache for key: {}", cacheKey.getKey());
			cacheValue = null;
		}
		return cacheValue;
	}

	private CacheValue loadFromDb(CacheManager cacheManager, CacheKey cacheKey, Function doSingleFlightFun) {
		CacheValue value = this.dbSingleFlightExecutor.execute(cacheKey, (k) -> {
			Object result = doSingleFlightFun.apply(k.getKey());
			if (result == null) {
				if (!k.isCacheNullValues()) {
					return null;
				}
			}
			CacheValue cacheValue = CacheValue.builder().value(result).createdAt(System.currentTimeMillis()).build();
			if (cacheKey.getExpireTimeMs() > 0) {
				cacheValue.setExpireTime(System.currentTimeMillis() + cacheKey.getExpireTimeMs());
			}
			cacheManager.put(k, cacheValue);
			return cacheValue;
		});
		return value;
	}

	/**
	 * 布隆过滤器 existsInDb
	 * @param cacheKey
	 * @return
	 */
	private boolean existsInBloomFilter(CacheKey cacheKey) {
		if (cacheKey.isBloomFilterEnabled()) {
			try {
				return this.cacheBloomFilter.exists(cacheKey.getBloomFilterName(), cacheKey.getKey().toString());
			}
			catch (Exception e) {
				log.error("existsInBloomFilter", e);
			}
		}
		return true;
	}

	private void checkCacheKey(CacheKey cacheKey) {
		if (cacheKey == null || cacheKey.getKey() == null) {
			throw CacheException.newInstance(CacheError.EMPTY_KEY);
		}
	}

}
