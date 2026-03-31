package com.consist.cache.core.hotspot.writes;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enhanced write hotspot detector with exponential backoff.
 * 
 * Features:
 * - Sliding window invalidation counting
 * - Automatic blacklist on threshold breach
 * - Exponential backoff for repeated violations
 * - Auto-recovery with TTL
 */
@Slf4j
public class EnhancedWriteHotspotDetector implements WriteHotspotDetector{
    
    private final int windowSeconds;
    private final int invalidationThreshold;
    private final Duration baseBlacklistTtl;
    private final double backoffMultiplier;
    private final Duration maxBlacklistTime;
    
    private final ConcurrentHashMap<Object, WriteHotSpotInfo> hotSpotInfo = new ConcurrentHashMap<>();
    private final LocalCacheBlacklist blacklist;
    
    /**
     * Create enhanced write hotspot detector.
     */
    public EnhancedWriteHotspotDetector(int windowSeconds,
                                       int invalidationThreshold,
                                       long baseBlacklistTtl,
                                       double backoffMultiplier,
                                       long maxBlacklistTime) {
        this.windowSeconds = windowSeconds;
        this.invalidationThreshold = invalidationThreshold;
        this.baseBlacklistTtl = Duration.ofMillis(baseBlacklistTtl);
        this.backoffMultiplier = backoffMultiplier;
        this.maxBlacklistTime = Duration.ofMillis(maxBlacklistTime);
        this.blacklist = new LocalCacheBlacklist();
        
        log.info("Initialized EnhancedWriteHotspotDetector: window={}s, threshold={}, baseTtl={}, backoff={}",
                windowSeconds, invalidationThreshold, baseBlacklistTtl, backoffMultiplier);
    }
    
    /**
     * Record an L1 cache invalidation.
     * Automatically detects and handles write hotspots.
     * @param key cache key being invalidated
     */
    public <T> void recordInvalidation(T key) {
        WriteHotSpotInfo info = this.hotSpotInfo.computeIfAbsent(key, k -> new WriteHotSpotInfo());
        
        synchronized (info) {
            // Reset if window expired 简单统计
            long now = System.currentTimeMillis();
            if (now - info.windowStartTime > this.windowSeconds * 1000L) {
                info.invalidationCount.set(0);
                info.windowStartTime = now;
            }
            
            // Increment counter
            int count = info.incrementAndGet();
            
            log.trace("Key {} invalidation count: {} / {}", key, count, this.invalidationThreshold);
            
            // Check if threshold exceeded
            if (count >= this.invalidationThreshold && !this.blacklist.isBlacklisted(key)) {
                handleWriteHotspotDetected(key, info);
            }
        }
    }
    
    /**
     * Handle write hotspot detection.
     */
    private <T> void handleWriteHotspotDetected(T key, WriteHotSpotInfo info) {
        // Calculate blacklist duration with exponential backoff
        Duration blacklistDuration = calculateBackoffDuration(info);
        
        // Add to blacklist
        this.blacklist.addToBlacklistWithDuration(key, blacklistDuration);
        
        // Reset counter
        info.invalidationCount.set(0);
        info.violationCount++;
        
        log.warn("Write hotspot detected for key={}. Blacklisted for {}. Violation count: {}",
                key, blacklistDuration, info.violationCount);
    }
    
    /**
     * Calculate blacklist duration with exponential backoff.
     */
    private Duration calculateBackoffDuration(WriteHotSpotInfo info) {
        if (info.violationCount == 0) {
            return this.baseBlacklistTtl;
        }
        
        // Exponential backoff: base * multiplier^violations
        long baseMillis = this.baseBlacklistTtl.toMillis();
        double multiplier = Math.pow(this.backoffMultiplier, info.violationCount);
        long calculatedMillis = (long) (baseMillis * multiplier);
        
        // Cap at maximum
        Duration result = Duration.ofMillis(Math.min(calculatedMillis, this.maxBlacklistTime.toMillis()));
        
        log.debug("Calculated backoff duration: {}ms (violations={}, multiplier={})",
                result.toMillis(), info.violationCount, multiplier);
        
        return result;
    }
    
    /**
     * Check if a key should bypass L1 cache.
     * @param key cache key
     * @return true if in blacklist (write-hot)
     */
    public <T> boolean shouldBypassL1(T key) {
        return this.blacklist.isBlacklisted(key);
    }
    
    /**
     * Get current invalidation count for a key.
     * @param key cache key
     * @return count in current window
     */
    public <T> int getInvalidationCount(T key) {
        WriteHotSpotInfo info = this.hotSpotInfo.get(key);
        return info != null ? info.invalidationCount.get() : 0;
    }
    
    /**
     * Cleanup old entries to prevent memory leak.
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        long windowMillis = this.windowSeconds * 1000L;

        this.hotSpotInfo.entrySet().removeIf(entry -> {
            // Remove if no activity in last 2 windows
            return now - entry.getValue().windowStartTime > windowMillis * 2;
        });
    }
    
    /**
     * Write hotspot information container.
     */
    @Data
    private static class WriteHotSpotInfo {
        volatile long windowStartTime;
        AtomicInteger invalidationCount = new AtomicInteger(0);
        int violationCount = 0;
        
        WriteHotSpotInfo() {
            this.windowStartTime = System.currentTimeMillis();
        }
        
        int incrementAndGet() {
            return invalidationCount.incrementAndGet();
        }
    }
}
