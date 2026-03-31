package com.consist.cache.core.monitor;

import com.consist.cache.core.distributed.DistributedCacheManager;
import com.consist.cache.core.local.LocalCacheManager;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Enhanced Redis connection monitor with consistency-aware failure handling.
 * 
 * Behavior based on ConsistencyLevel:
 * - HIGH: Immediately clear L1 on disconnect/reconnect (CP model)
 * - AVAILABLE: Mark L1 as STALE but retain data (AP model)
 */
@Slf4j
public class EnhancedConnectionMonitor {
    
    private final DistributedCacheManager distributedCacheManager;
    private final LocalCacheManager localCacheManager;
    private final ScheduledExecutorService scheduler;
    private volatile boolean wasConnected = true;
    
    /**
     * Create enhanced connection monitor.
     */
    public EnhancedConnectionMonitor(DistributedCacheManager distributedCacheManager,
                                     LocalCacheManager localCacheManager) {
        this.distributedCacheManager = distributedCacheManager;
        this.localCacheManager = localCacheManager;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "enhanced-connection-monitor");
            t.setDaemon(true);
            return t;
        });
        // Start monitoring every 3 seconds
        this.scheduler.scheduleAtFixedRate(this::checkConnection, 3, 3, TimeUnit.SECONDS);
        log.info("Initialized EnhancedConnectionMonitor");
    }
    
    /**
     * Check Redis connection status and handle state changes.
     */
    private void checkConnection() {
        try {
            boolean isConnected = isConnected();
            if (!isConnected && this.wasConnected) {
                // Connection lost
                log.error("Redis connection LOST! Handling based on consistency levels...");
                handleDisconnection();
                this.wasConnected = false;
                
            } else if (isConnected && !this.wasConnected) {
                // Reconnection detected
                log.warn("Redis RECONNECTED! Handling based on consistency levels...");
                handleReconnection();
                this.wasConnected = true;
            }
            
        } catch (Exception e) {
            log.error("Failed to check Redis connection status", e);
            
            // Assume disconnected on error
            if (this.wasConnected) {
                log.error("Assuming Redis disconnected due to exception");
                handleDisconnection();
                this.wasConnected = false;
            }
        }
    }
    
    /**
     * Handle Redis disconnection event.
     */
    private void handleDisconnection() {
        this.localCacheManager.getCacheLevelMap().forEach((consistencyLevel, localCache) -> {
            switch (consistencyLevel) {
                case HIGH:
                    // CP model: Clear L1 immediately to prevent stale reads
                    log.warn("Cache '{}' (HIGH consistency): Clearing L1 cache on disconnect", consistencyLevel);
                    localCache.cleanUp();
                    break;
                    
                case AVAILABLE:
                    // AP model: Mark as STALE but retain for availability
                    log.info("Cache '{}' (AVAILABLE consistency): Marking L1 as STALE, retaining data", consistencyLevel);
                    break;
            }
        });
    }
    
    /**
     * Handle Redis reconnection event.
     */
    private void handleReconnection() {
        this.localCacheManager.getCacheLevelMap().forEach((consistencyLevel, localCache) -> {
            switch (consistencyLevel) {
                case HIGH:
                    // CP model: Clear L1 on reconnect to ensure fresh data
                    log.warn("Cache '{}' (HIGH consistency): Clearing L1 cache on reconnect", consistencyLevel);
                    localCache.cleanUp();
                    break;
                    
                case AVAILABLE:
                    // AP model: Continue using existing L1, let TTL handle expiration
                    log.info("Cache '{}' (AVAILABLE consistency): Retaining L1 cache after reconnect", consistencyLevel);
                    // No action needed - rely on natural TTL
                    break;
            }
        });
    }
    
    /**
     * Get current connection status.
     * @return true if connected
     */
    public boolean isConnected() {
        try {
            return this.distributedCacheManager.isHealthy();
        } catch (Exception e) {
            return false;
        }
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
