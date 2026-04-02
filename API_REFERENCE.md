# Consistency Cache API Reference

## Core Annotations | 核心注解

### @HccCacheable

**Purpose**: Mark a method as cacheable with automatic SingleFlight and hotspot detection.

**Attributes**:
```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface HccCacheable {
    
    /**
     * SpEL expression for cache key
     * Example: "#userId", "'product:' + #id"
     */
    String key();
    
    /**
     * Cache expiration time in seconds
     * Default: 0 (use global default)
     */
    long expireTime() default 0;

    /**
     * cacheLevel
     * how to use L1 or L2 
     */
    CacheLevel cacheLevel() default CacheLevel.ADAPTIVE_CACHE;

    /**
     * if use L1, different consistencyLevel use different local cache instance
     * high must clean if cache invalid
     */
    ConsistencyLevel consistencyLevel() default ConsistencyLevel.HIGH;
}
```

**Usage Examples**:
```java
// Simple caching
@HccCacheable(key = "#id", expireTime = 300)
public User getUser(Long id) { ... }

// With consistency level
@HccCacheable(key = "#id")
public Product getProduct(Long id) { ... }
```

---

### @HccCacheEvict

**Purpose**: Invalidate cache entries after method execution.

**Attributes**:
```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface HccCacheEvict {
    
    /**
     * SpEL expression for cache key to invalidate
     */
    String key();

    /**
     * cacheLevel
     * how to use L1 or L2 
     */
    CacheLevel cacheLevel() default CacheLevel.ADAPTIVE_CACHE;

    /**
     * if use L1, different consistencyLevel use different local cache instance
     * high must clean if cache invalid
     */
    ConsistencyLevel consistencyLevel() default ConsistencyLevel.HIGH;
}
```

**Usage Examples**:
```java
// Simple eviction
@HccCacheEvict(key = "#orderId")
public void cancelOrder(Long orderId) { ... }

// Without broadcasting (local only)
@HccCacheEvict(key = "#id")
public void updateLocalCache(Long id) { ... }
```

---

## Model Classes | 模型类

### CacheKey

Represents a cache key with full configuration.

**Fields**:
```java
@Data
@Builder
public class CacheKey<K> {
    private K key;                    // Actual key value
    private long expireTimeMs;        // Expiration time in ms
    private CacheLevel cacheLevel;    // L1/L2/Adaptive
    private ConsistencyLevel consistencyLevel; // CP/AP
}
```

**Usage**:
```java
CacheKey<String> key = CacheKey.<String>builder()
    .key("user:123")
    .expireTimeMs(300000)
    .consistencyLevel(ConsistencyLevel.AVAILABLE)
    .cacheLevel(CacheLevel.ADAPTIVE_CACHE)
    .build();
```

---

### CacheValue

Wrapper for cached values with metadata.

**Fields**:
```java
@Data
@Builder
public class CacheValue<V> {
    private V value;              // Actual cached value
    private long expireTime;      // Absolute expiration timestamp
    private long createdAt;       // Creation timestamp
    private double weight;        // Weight for LRU (hot key enhancement)
    
    // Utility methods
    public boolean isExpired();
    public boolean notExist();
    public long getTtl();
    
    // Extract value from wrapper
    public static <V> V extractValue(Object value);
}
```

**Usage**:
```java
// Create cache value
CacheValue<User> value = CacheValue.<User>builder()
    .value(user)
    .expireTime(System.currentTimeMillis() + 300000)
    .createdAt(System.currentTimeMillis())
    .build();

// Extract actual value
User user = CacheValue.extractValue(cacheValue);
```

---

### ConsistencyLevel

Enum defining cache consistency guarantees.

**Values**:
```java
public enum ConsistencyLevel {
    /**
     * HIGH (CP Pattern)
     * - Clear L1 on Redis disconnect
     * - Prevents stale data during partition
     * - Use for: Inventory, pricing, sensitive state
     */
    HIGH,
    
    /**
     * AVAILABLE (AP Pattern)
     * - Retain L1 on Redis disconnect
     * - Mark as STALE but continue serving
     * - Use for: Product details, configs, eventually consistent data
     */
    AVAILABLE
}
```

---

### CacheLevel

Enum defining which cache tier to use.

**Values**:
```java
public enum CacheLevel {
    LOCAL_CACHE,      // Only L1 (Caffeine)
    L2_CACHE,         // Only L2 (Redis)
    ADAPTIVE_CACHE    // Smart adaptive (recommended)
}
```

---

## Core Executors | 核心执行器

### CacheExecutor

Main interface for cache operations.

**Methods**:
```java
public interface CacheExecutor {
    
    /**
     * Get value from cache with loader fallback
     * @param cacheKey Cache key configuration
     * @param doSingleFlightFun Loader function if cache miss
     * @return Cached or loaded value
     */
    CacheValue get(CacheKey cacheKey, Function doSingleFlightFun);
    
    /**
     * Invalidate cache entry
     * @param cacheKey Key to invalidate
     */
    void evict(CacheKey cacheKey);
    
    /**
     * Delete local cache only
     * @param cacheKey Key to delete
     */
    void deleteLocalCache(CacheKey cacheKey);
    
    /**
     * Check if key exists in cache
     * @param cacheKey Key to check
     * @return true if exists
     */
    boolean exists(CacheKey cacheKey);
}
```

**Usage Example**:
```java
@Autowired
private CacheExecutor cacheExecutor;

public Data getData(Long id) {
    CacheKey key = CacheKey.builder()
        .key("data:" + id)
        .build();
    
    return CacheValue.extractValue(
        cacheExecutor.get(key, k -> loadFromDB(k))
    );
}
```

---

### SingleFlightExecutor

Request collapser ensuring single execution per key.

**Methods**:
```java
public class SingleFlightExecutor {
    
    /**
     * Execute function with SingleFlight guarantee
     * @param key Unique identifier
     * @param doSingleFlightFun Function to execute
     * @return Result (shared among concurrent callers)
     */
    public <K,V> V execute(K key, Function<K, V> doSingleFlightFun);
}
```

**Thread Safety**:
- ✅ Multiple threads can safely call same key
- ✅ Only one thread executes the loader
- ✅ All threads receive same result or exception
- ✅ Automatic cleanup after completion

---

### CacheCircuitBreaker

Three-state circuit breaker for fault tolerance.

**Methods**:
```java
public class CacheCircuitBreaker {
    
    /**
     * Execute operation with circuit breaker protection
     * @param supplier Operation to execute
     * @return Result from operation
     * @throws CircuitBreakerOpenException if circuit is OPEN
     */
    public <T> T execute(Supplier<T> supplier);
    
    /**
     * Get current circuit state
     */
    public State getState();
    
    /**
     * Get statistics
     */
    public CircuitStats getStats();
    
    /**
     * Manually reset circuit breaker
     */
    public void reset();
    
    /**
     * Force circuit to open
     */
    public void forceOpen();
}
```

**State Enum**:
```java
public enum State {
    CLOSED,     // Normal operation
    OPEN,       // Tripped, failing fast
    HALF_OPEN   // Testing recovery
}
```

---

## Hotspot Detectors | 热点检测器

### ReadQpsStatistics

Sliding window QPS tracker for read operations.

**Constructor**:
```java
/**
 * @param hotKeyThreshold QPS threshold (default: 100)
 * @param windowSizeMs Window size in ms (default: 1000)
 * @param bucketCount Number of buckets (default: 10)
 */
public ReadQpsStatistics(double hotKeyThreshold, 
                         int windowSizeMs, 
                         int bucketCount)
```

**Methods**:
```java
// Record a read operation
public <T> void recordRead(T key);

// Check if key is hot
public <T> boolean isHotKey(T key);

// Get current QPS
public <T> double getQps(T key);

// Cleanup stale counters
public void cleanup();
```

**Usage**:
```java
ReadQpsStatistics stats = new ReadQpsStatistics(100.0, 1000, 10);

// On each read
stats.recordRead(cacheKey);

// Check before caching
if (stats.isHotKey(cacheKey)) {
    // Enhance to local cache
    localCache.put(cacheKey, value);
}
```

---

### EnhancedWriteHotspotDetector

Write hotspot detector with exponential backoff.

**Constructor**:
```java
/**
 * @param windowSeconds Sliding window size in seconds
 * @param invalidationThreshold Invalidations before blacklisting
 * @param baseBlacklistTtl Initial blacklist duration (ms)
 * @param backoffMultiplier Exponential backoff multiplier
 * @param maxBlacklistTime Maximum blacklist duration (ms)
 */
public EnhancedWriteHotspotDetector(int windowSeconds,
                                    int invalidationThreshold,
                                    long baseBlacklistTtl,
                                    double backoffMultiplier,
                                    long maxBlacklistTime)
```

**Methods**:
```java
// Record invalidation
public <T> void recordInvalidation(T key);

// Check if should bypass L1
public <T> boolean shouldBypassL1(T key);

// Get invalidation count
public <T> int getInvalidationCount(T key);

// Cleanup old entries
public void cleanup();
```

---

## Statistics & Monitoring | 统计监控

### LocalCacheManager.CacheStats

Cache statistics container.

**Fields**:
```java
@Data
@Builder
public static class CacheStats {
    private long hitCount;        // Total cache hits
    private long missCount;       // Total cache misses
    private double hitRate;       // Hit rate (0.0-1.0)
    private long size;            // Current cache size
    private long maxSize;         // Maximum configured size
    private long evictionCount;   // Total evictions
    
    // Formatted hit rate for display
    public String getFormattedHitRate() {
        return String.format("%.2f%%", hitRate * 100);
    }
}
```

**Usage**:
```java
@Autowired
private LocalCacheManager cacheManager;

@GetMapping("/cache/stats")
public CacheStats getStats() {
    CacheStats stats = cacheManager.getStats();
    
    log.info("Hit Rate: {}", stats.getFormattedHitRate());
    log.info("Cache Size: {}/{}", stats.getSize(), stats.getMaxSize());
    
    return stats;
}
```

---

### CacheCircuitBreaker.CircuitStats

Circuit breaker statistics.

**Fields**:
```java
@Data
@Builder
public static class CircuitStats {
    private String state;         // CLOSED/OPEN/HALF_OPEN
    private int failureCount;     // Current failure count
    private int successCount;     // Current success count
    private int totalCalls;       // Total calls made
    private int rejectedCalls;    // Calls rejected when OPEN
    
    // Derived metric
    public double getRejectionRate() {
        return totalCalls > 0 ? (double) rejectedCalls / totalCalls : 0.0;
    }
}
```

---

## Utility Methods | 工具方法

### CacheValue.extractValue()

Safely extract value from CacheValue wrapper.

**Signature**:
```java
public static <V> V extractValue(Object value)
```

**Behavior**:
- Returns `null` if input is `null`
- Returns input directly if not CacheValue instance
- Checks expiration and returns `null` if expired
- Extracts actual value from CacheValue

**Examples**:
```java
// From CacheValue
CacheValue<String> cv = CacheValue.<String>builder()
    .value("hello")
    .build();
String v1 = CacheValue.extractValue(cv);  // "hello"

// From raw value
String v2 = CacheValue.extractValue("world");  // "world"

// From null
String v3 = CacheValue.extractValue(null);  // null

// From expired CacheValue
cv.setExpireTime(System.currentTimeMillis() - 1000);
String v4 = CacheValue.extractValue(cv);  // null
```

---

## Exception Handling | 异常处理

### CacheException

Custom exception for cache operations.

**Factory Method**:
```java
public static CacheException newInstance(CacheError error)
```

**Usage**:
```java
if (key == null) {
    throw CacheException.newInstance(CacheError.EMPTY_KEY);
}
```

### CircuitBreakerOpenException

Thrown when circuit breaker is OPEN.

**Handling**:
```java
try {
    value = circuitBreaker.execute(() -> cache.get(key));
} catch (CircuitBreakerOpenException e) {
    // Fallback strategy
    value = loadFromDatabase(key);
}
```

---

## Configuration Properties | 配置属性

### LocalCacheProperties

Configuration for local cache behavior.

**Fields**:
```java
@Data
public class LocalCacheProperties {
    private boolean enabled;          // Enable local cache
    private long maximumSize;         // Max entries
    private long expireAfterWrite;    // TTL in seconds
    // ... additional settings
}
```

**YAML Configuration**:
```yaml
hcc:
  cache:
    local:
      enabled: true
      maximum-size: 10000
      expire-after-write: 300s
```

---

## Best Practices | 最佳实践

### 1. Key Design

**DO**:
```java
// Use descriptive prefixes
@HccCacheable(key = "'user:' + #id")
@HccCacheable(key = "'product:inventory:' + #productId")

// Include version for schema changes
@HccCacheable(key = "'user:v2:' + #id")
```

**DON'T**:
```java
// Avoid generic keys
@HccCacheable(key = "#id")  // Too vague!

// Avoid concatenation without separator
@HccCacheable(key = "'user' + #id")  // user1 vs user:1
```

### 2. Expiration Strategy

**Recommended TTLs**:
```java
// Frequently changing data: 5-10 minutes
@HccCacheable(key = "#id", expireTime = 300)

// Static data: 1-24 hours
@HccCacheable(key = "#id", expireTime = 3600)

// Configuration data: 24 hours or more
@HccCacheable(key = "#id", expireTime = 86400)
```

### 3. Consistency Selection

**Choose HIGH for**:
- Financial transactions
- Inventory counts
- Pricing information
- Account status

**Choose AVAILABLE for**:
- Product descriptions
- User profiles
- System configurations
- Analytics data

### 4. Error Handling

**Recommended Pattern**:
```java
@HccCacheable(key = "#id")
public Data getData(Long id) {
    try {
        return repository.findById(id);
    } catch (DataNotFoundException e) {
        // Return null to cache miss
        return null;
    } catch (Exception e) {
        // Let exception propagate for logging
        throw e;
    }
}
```

---

## Performance Tips | 性能提示

### 1. Minimize Key Serialization

Use simple key types (String, Long, Integer) instead of complex objects.

```java
// GOOD: Simple key
@HccCacheable(key = "#userId")

// AVOID: Complex key requiring serialization
@HccCacheable(key = "#userQueryObject.toString()")
```

### 2. Batch Operations

For multiple keys, consider batch loading:

```java
// Instead of N individual calls
List<Data> results = keys.stream()
    .map(key -> cacheExecutor.get(key, loader))
    .collect(Collectors.toList());

// Consider implementing batch loader
```

### 3. Monitor Hot Keys

Regularly check hotspot detection logs:

```bash
# Look for these patterns in logs
grep "Hot key detected" application.log
grep "Write hotspot detected" application.log
```

---

*API Reference Version: 1.0.0*  
*Last Updated: March 31, 2026*
