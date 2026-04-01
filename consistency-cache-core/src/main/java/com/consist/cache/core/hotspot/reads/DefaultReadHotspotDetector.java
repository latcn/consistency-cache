package com.consist.cache.core.hotspot.reads;

import com.consist.cache.core.util.RandomUtil;
import com.consist.cache.core.util.TimeHolder;
import com.consist.cache.core.util.TimerTask;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Sliding window QPS statistics for read hotspot detection.
 */
@Slf4j
public class DefaultReadHotspotDetector implements ReadHotspotDetector {
    
    private static final int CLEANUP_INTERVAL_MS = 60000; // 1 minute
    private static final long COUNTER_TTL_MS = 300000; // 5 minutes

    private final ConcurrentHashMap<Object, SlidingWindowCounter> counters = new ConcurrentHashMap<>();
    private final double hotKeyThreshold; // QPS threshold
    private final int windowSizeMs;
    private final int bucketCount;
    private volatile long lastCleanupTime = System.currentTimeMillis();
    
    /**
     * Create read QPS statistics tracker.
     * @param hotKeyThreshold QPS threshold for hot key detection (default: 100)
     * @param windowSizeMs sliding window size in milliseconds (default: 1000)
     * @param bucketCount number of buckets in window (default: 10)
     */
    public DefaultReadHotspotDetector(double hotKeyThreshold, int windowSizeMs, int bucketCount) {
        this.hotKeyThreshold = hotKeyThreshold;
        this.windowSizeMs = windowSizeMs;
        this.bucketCount = bucketCount;
        log.info("Initialized DefaultReadHotspotDetector with threshold={} QPS, window={}ms, buckets={}",
                hotKeyThreshold, windowSizeMs, bucketCount);
        // 定时扫描
        TimeHolder.addTask(new TimerTask(RandomUtil.halfBoundRandom(CLEANUP_INTERVAL_MS), this::cleanup));
    }
    
    /**
     * Record a read operation for given key.
     * Automatically triggers cleanup if interval exceeded.
     * @param key cache key
     */
    public <T> void recordRead(T key) {
        this.counters.computeIfAbsent(key, k -> new SlidingWindowCounter(windowSizeMs, bucketCount))
                .increment();
    }
    
    /**
     * Check if key is currently hot.
     * @param key cache key
     * @return true if QPS exceeds threshold
     */
    public <T> boolean isHotKey(T key) {
        SlidingWindowCounter counter = this.counters.get(key);
        if (counter == null) {
            return false;
        }
        
        double qps = counter.getQps();
        boolean isHot = qps > this.hotKeyThreshold;
        
        if (isHot && log.isDebugEnabled()) {
            log.debug("Hot key detected: key={}, qps={}, threshold={}", key, qps, this.hotKeyThreshold);
        }
        
        return isHot;
    }
    
    /**
     * Get current QPS for key.
     * @param key cache key
     * @return current QPS value
     */
    public <T> double getQps(T key) {
        SlidingWindowCounter counter = this.counters.get(key);
        return counter != null ? counter.getQps() : 0.0;
    }
    
    /**
     * Cleanup old entries to prevent memory leak.
     * Automatically triggered during recordRead if interval exceeded.
     */
    public void cleanup() {
        try {
            long now = System.currentTimeMillis();

            // Only cleanup if interval has passed (reduces overhead)
            if (now - lastCleanupTime < CLEANUP_INTERVAL_MS) {
                return;
            }

            int originalSize = this.counters.size();
            synchronized (this) {
                // Double-check after acquiring lock
                if (now - lastCleanupTime < CLEANUP_INTERVAL_MS) {
                    return;
                }

                // Remove stale entries
                this.counters.entrySet().removeIf(entry ->
                    now - entry.getValue().lastAccessTime > COUNTER_TTL_MS
                );

                lastCleanupTime = now;
            }

            // Calculate removed count for logging
            int removedCount = originalSize - this.counters.size();

            if (log.isDebugEnabled() && removedCount > 0) {
                log.debug("Cleaned up {} stale hotspot counters", removedCount);
            }
        } catch (Exception e) {
            log.error("DefaultReadHotspotDetector", e);
        } finally {
            TimeHolder.addTask(new TimerTask(RandomUtil.halfBoundRandom(CLEANUP_INTERVAL_MS), this::cleanup));
        }
    }

    public int getWindowSizeMs() {
        return this.windowSizeMs;
    }

    /**
     * Sliding window counter implementation.
     */
    private static class SlidingWindowCounter {
        private final AtomicLongArray buckets;
        private final int bucketCount;
        private final long bucketSizeMs;
        private volatile int currentIndex = 0;
        private volatile long lastRotateTime;
        volatile long lastAccessTime;
        
        SlidingWindowCounter(int windowSizeMs, int bucketCount) {
            this.bucketCount = bucketCount;
            this.buckets = new AtomicLongArray(bucketCount);
            this.bucketSizeMs = windowSizeMs / bucketCount;
            this.lastRotateTime = System.currentTimeMillis();
            this.lastAccessTime = System.currentTimeMillis();
        }
        
        public void increment() {
            rotate();
            this.buckets.incrementAndGet(currentIndex);
            this.lastAccessTime = System.currentTimeMillis();
        }
        
        public double getQps() {
            rotate();
            
            long total = 0;
            for (int i = 0; i < this.bucketCount; i++) {
                total += this.buckets.get(i);
            }
            
            // QPS = total requests in window / window size in seconds
            return (double) total / (this.bucketCount * this.bucketSizeMs / 1000.0);
        }
        
        private synchronized void rotate() {
            long now = System.currentTimeMillis();
            long elapsed = now - this.lastRotateTime;
            int bucketsToRotate = (int) (elapsed / this.bucketSizeMs);
            
            if (bucketsToRotate >= this.bucketCount) {
                // All buckets expired, reset all
                for (int i = 0; i < this.bucketCount; i++) {
                    this.buckets.set(i, 0);
                }
                this.currentIndex = 0;
            } else if (bucketsToRotate > 0) {
                // Rotate specific buckets
                for (int i = 0; i < bucketsToRotate; i++) {
                    int indexToClear = (this.currentIndex + 1) % this.bucketCount;
                    this.buckets.set(indexToClear, 0);
                    this.currentIndex = indexToClear;
                }
            }
            this.lastRotateTime = now;
        }
    }
}
