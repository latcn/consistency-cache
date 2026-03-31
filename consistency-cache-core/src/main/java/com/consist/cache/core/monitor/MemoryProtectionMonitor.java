package com.consist.cache.core.monitor;

import com.consist.cache.core.local.LocalCacheManager;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Memory protection monitor that proactively manages L1 cache memory usage.
 */
@Slf4j
public class MemoryProtectionMonitor {
    
    private final LocalCacheManager localCacheManager;
    private final long maxSize;
    private final double warningThreshold;
    private final ScheduledExecutorService scheduler;
    
    /**
     * Create memory protection monitor.
     * @param localCacheManager local cache manager
     * @param maxSize maximum cache size
     * @param warningThreshold warning threshold (0.0-1.0, default: 0.8)
     */
    public MemoryProtectionMonitor(LocalCacheManager localCacheManager,
                                   long maxSize, double warningThreshold) {
        this.localCacheManager = localCacheManager;
        this.maxSize = maxSize;
        this.warningThreshold = warningThreshold;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "memory-protection-monitor");
            t.setDaemon(true);
            return t;
        });
        
        // Monitor every 30 seconds
        this.scheduler.scheduleAtFixedRate(this::checkMemoryUsage, 30, 30, TimeUnit.SECONDS);
        log.info("Initialized MemoryProtectionMonitor with max={}, warning={}%",
                maxSize, (int)(warningThreshold * 100));
    }
    
    /**
     * Check current memory usage and take action if needed.
     */
    private void checkMemoryUsage() {
        long currentSize = this.localCacheManager.getSize();
        double usageRatio = (double) currentSize / maxSize;
        if (usageRatio >= warningThreshold) {
            log.warn("WARNING: L1 cache at {}% capacity ({} / {})",
                    (int)(usageRatio * 100), currentSize, maxSize);
            // Gentle eviction
            this.localCacheManager.runEviction();
        }
    }
    
    /**
     * Get current memory usage ratio.
     * @return usage ratio (0.0-1.0)
     */
    public double getUsageRatio() {
        return (double) this.localCacheManager.getSize() / this.maxSize;
    }
    
    /**
     * Get formatted usage percentage.
     * @return usage as percentage string
     */
    public String getFormattedUsage() {
        return String.format("%.1f%%", getUsageRatio() * 100);
    }
    
    /**
     * Shutdown monitor.
     */
    public void shutdown() {
        this.scheduler.shutdown();
        try {
            if (!this.scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                this.scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
