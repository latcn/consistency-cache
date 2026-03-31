package com.consist.cache.core.hotspot.reads;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Sliding window QPS statistics for read hotspot detection.
 */
@Slf4j
public class ReadQpsStatistics implements ReadHotspotDetector {
    
    private final ConcurrentHashMap<Object, SlidingWindowCounter> counters = new ConcurrentHashMap<>();
    private final double hotKeyThreshold; // QPS threshold
    private final int windowSizeMs;
    private final int bucketCount;
    
    /**
     * Create read QPS statistics tracker.
     * @param hotKeyThreshold QPS threshold for hot key detection (default: 100)
     * @param windowSizeMs sliding window size in milliseconds (default: 1000)
     * @param bucketCount number of buckets in window (default: 10)
     */
    public ReadQpsStatistics(double hotKeyThreshold, int windowSizeMs, int bucketCount) {
        this.hotKeyThreshold = hotKeyThreshold;
        this.windowSizeMs = windowSizeMs;
        this.bucketCount = bucketCount;
        
        log.info("Initialized ReadQpsStatistics with threshold={} QPS, window={}ms, buckets={}", 
                hotKeyThreshold, windowSizeMs, bucketCount);
    }
    
    /**
     * Record a read operation for given key.
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
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        this.counters.entrySet().removeIf(entry -> {
            // Remove if no activity in last 5 minutes
            return now - entry.getValue().lastAccessTime > 300000;
        });
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
