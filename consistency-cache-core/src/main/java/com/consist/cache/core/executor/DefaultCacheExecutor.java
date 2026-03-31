package com.consist.cache.core.executor;

import com.consist.cache.core.circuitbreaker.CacheCircuitBreaker;
import com.consist.cache.core.distributed.DistributedCacheManager;
import com.consist.cache.core.exception.CacheError;
import com.consist.cache.core.exception.CacheException;
import com.consist.cache.core.hotspot.reads.ReadQpsStatistics;
import com.consist.cache.core.hotspot.writes.EnhancedWriteHotspotDetector;
import com.consist.cache.core.local.LocalCacheManager;
import com.consist.cache.core.local.LocalCacheMarkerManager;
import com.consist.cache.core.manager.*;
import com.consist.cache.core.model.*;
import com.consist.cache.core.pubsub.Broadcaster;
import com.consist.cache.core.pubsub.InvalidationBroadcaster;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Function;

/**
 * DefaultCacheExecutor with circuit breaker protection.
 */
@Slf4j
public class DefaultCacheExecutor implements CacheExecutor {

    private final LocalCacheManager localCacheManager;
    private final DistributedCacheManager distributedCacheManager;
    private final LocalCacheMarkerManager localCacheMarkerManager;
    private final SingleFlightExecutor fromDb;
    private final SingleFlightExecutor fromDistributedCache;
    private final ReadQpsStatistics readStatistics;
    private final EnhancedWriteHotspotDetector writeHotspotDetector;
    private final CacheCircuitBreaker circuitBreaker;
    private Broadcaster broadcaster;

    public DefaultCacheExecutor(LocalCacheManager localCacheManager,
                                DistributedCacheManager distributedCacheManager,
                                LocalCacheMarkerManager localCacheMarkerManager,
                                EnhancedWriteHotspotDetector writeHotspotDetector,
                                ReadQpsStatistics readStatistics) {
        this(localCacheManager, distributedCacheManager, localCacheMarkerManager, 
             writeHotspotDetector, readStatistics, new CacheCircuitBreaker());
    }
    
    public DefaultCacheExecutor(LocalCacheManager localCacheManager,
                                DistributedCacheManager distributedCacheManager,
                                LocalCacheMarkerManager localCacheMarkerManager,
                                EnhancedWriteHotspotDetector writeHotspotDetector,
                                ReadQpsStatistics readStatistics,
                                CacheCircuitBreaker circuitBreaker) {
        this.localCacheManager = localCacheManager;
        this.distributedCacheManager = distributedCacheManager;
        this.localCacheMarkerManager = localCacheMarkerManager;
        this.fromDb = new SingleFlightExecutor();
        this.fromDistributedCache = new SingleFlightExecutor();
        this.writeHotspotDetector = writeHotspotDetector;
        this.readStatistics = readStatistics;
        this.circuitBreaker = circuitBreaker;
        
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
            deleteLocalCache(cacheKey);
            // 判断是否写热key， 写热key且不要求较强一致性的数据 禁用广播
            if (cacheKey.getConsistencyLevel() == ConsistencyLevel.HIGH
                    || !this.writeHotspotDetector.shouldBypassL1(cacheKey.getKey())) {
                this.broadcaster.addKey(cacheKey);
            }
        } else {
            this.distributedCacheManager.remove(cacheKey);
            if (cacheKey.getCacheLevel() == CacheLevel.ADAPTIVE_CACHE) {
                // todo 假定key.toString 不重复
                List<String> nodeIds = this.localCacheMarkerManager.getActiveNodes(cacheKey.getKey().toString());
                if(nodeIds.size()>0){
                    this.localCacheMarkerManager.removeLocalCacheUsage(cacheKey.getKey().toString());
                    if (!(nodeIds.size()==1 && NodeInstanceHolder.getNodeId().equals(nodeIds.get(0)))) {
                        this.broadcaster.addKey(cacheKey);
                    }
                    deleteLocalCache(cacheKey);
                }
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
        if (cacheKey.getCacheLevel() == CacheLevel.ADAPTIVE_CACHE
                || cacheKey.getCacheLevel() == CacheLevel.L2_CACHE) {
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
        if (cacheKey.getCacheLevel() == CacheLevel.ADAPTIVE_CACHE
                || cacheKey.getCacheLevel() == CacheLevel.L2_CACHE) {
            return this.distributedCacheManager.containKey(cacheKey);
        }
        return false;
    }

    @Override
    public CacheValue get(CacheKey cacheKey, Function doSingleFlightFun) {
        checkCacheKey(cacheKey);
        this.readStatistics.recordRead(cacheKey.getKey());
            
        if (cacheKey.getCacheLevel() == CacheLevel.LOCAL_CACHE) {
            CacheValue cacheValue = this.localCacheManager.get(cacheKey);
            if (cacheValue == null) {
                cacheValue = loadFromDb(this.localCacheManager, cacheKey, doSingleFlightFun);
            }
            return cacheValue;
        }
        if (cacheKey.getCacheLevel() == CacheLevel.ADAPTIVE_CACHE) {
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
                    this.localCacheMarkerManager.markLocalCacheUsage(cacheKey.getKey().toString(), cacheValue.getExpireTime());
                    this.localCacheManager.put(cacheKey, cacheValue);
                }
            }
            return cacheValue;
        }
        if (cacheKey.getCacheLevel() == CacheLevel.L2_CACHE) {
            CacheValue cacheValue = getValueFromDistributedCache(cacheKey);
            if (cacheValue==null) {
                cacheValue = loadFromDb(this.distributedCacheManager, cacheKey, doSingleFlightFun);
            }
            return cacheValue;
        }
        return null;
    }

    private CacheValue getValueFromDistributedCache(CacheKey cacheKey) {
        CacheValue cacheValue;
        try {
            cacheValue = circuitBreaker.execute(() ->
                    this.fromDistributedCache.execute(cacheKey, (k)->this.distributedCacheManager.get(k))
            );
        } catch (CacheCircuitBreaker.CircuitBreakerOpenException e) {
            log.warn("Circuit breaker OPEN, bypassing distributed cache for key: {}", cacheKey.getKey());
            cacheValue = null;
        }
        return cacheValue;
    }

    private CacheValue loadFromDb(CacheManager cacheManager, CacheKey cacheKey, Function doSingleFlightFun) {
        CacheValue value = this.fromDb.execute(cacheKey,
                (k)->{
                     Object result = doSingleFlightFun.apply(k.getKey());
                     CacheValue cacheValue = CacheValue.builder()
                             .value(result)
                             //.expireTime(System.currentTimeMillis()+cacheKey.getExpireTimeMs())
                             .createdAt(System.currentTimeMillis())
                             .build();
                     if (cacheKey.getExpireTimeMs()>0) {
                         cacheValue.setExpireTime(System.currentTimeMillis()+cacheKey.getExpireTimeMs());
                     }
                     cacheManager.put(k, cacheValue);
                     return cacheValue;
                });
        return value;
    }

    private void checkCacheKey(CacheKey cacheKey) {
        if (cacheKey==null || cacheKey.getKey()==null) {
            throw CacheException.newInstance(CacheError.EMPTY_KEY);
        }
    }
}
