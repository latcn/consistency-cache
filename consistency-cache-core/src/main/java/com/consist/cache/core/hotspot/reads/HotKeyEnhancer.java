package com.consist.cache.core.hotspot.reads;

import com.consist.cache.core.model.CacheValue;
import lombok.extern.slf4j.Slf4j;

/**
 * Hot key enhancer that automatically extends TTL and increases weight for hot keys.
 */
@Slf4j
public class HotKeyEnhancer {
    
    private final ReadQpsStatistics statistics;
    private final double ttlMultiplier;
    private final double weightMultiplier;
    
    /**
     * Create hot key enhancer.
     * @param statistics read QPS statistics
     * @param ttlMultiplier TTL extension multiplier (default: 2.0)
     * @param weightMultiplier weight increase multiplier (default: 1.5)
     */
    public HotKeyEnhancer(ReadQpsStatistics statistics, double ttlMultiplier, double weightMultiplier) {
        this.statistics = statistics;
        this.ttlMultiplier = ttlMultiplier;
        this.weightMultiplier = weightMultiplier;
        
        log.info("Initialized HotKeyEnhancer with ttlMultiplier={}, weightMultiplier={}", 
                ttlMultiplier, weightMultiplier);
    }
    
    /**
     * Enhance cache entry if key is detected as hot.
     * @param key cache key
     * @return enhanced entry or original if not hot
     */
    public CacheValue enhanceIfHot(Object key, CacheValue cacheValue) {
        if (this.statistics.isHotKey(key)) {
            // Create enhanced copy
            long newTtl = (long) (cacheValue.getTtl() * this.ttlMultiplier);
            long newExpireTime = System.currentTimeMillis() + newTtl;
            double newWeight = cacheValue.getWeight() * this.weightMultiplier;

            CacheValue enhanced = CacheValue.builder()
                .expireTime(newExpireTime)
                .createdAt(cacheValue.getCreatedAt())
                .weight(newWeight)
                .build();
            log.info("Hot key enhanced: key={}, originalTtl={}ms, newTtl={}ms, weight={}", 
                    key, cacheValue.getTtl(), newTtl, newWeight);
            
            return enhanced;
        }
        return null;
    }
}
