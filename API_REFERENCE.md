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
     * Cache level strategy
     */
    CacheLevel cacheLevel() default CacheLevel.ADAPTIVE_CACHE;

    /**
     * Consistency level (CP/AP)
     */
    ConsistencyLevel consistencyLevel() default ConsistencyLevel.HIGH;

    /**
     * Enable Bloom filter for cache penetration prevention
     */
    boolean bloomFilterEnabled() default false;

    /**
     * Allow caching null values
     */
    boolean cacheNullValues() default true;

    /**
     * Enable broadcast invalidation to other nodes
     */
    boolean broadcastEnabled() default true;

    /**
     * Custom Bloom filter name (if multiple filters configured)
     */
    String bloomFilterName() default "";
}
```

**Usage Examples**:
```java
// Simple caching
@HccCacheable(key = "#id", expireTime = 300)
public User getUser(Long id) { ... }

// With Bloom filter and null value caching
@HccCacheable(key = "#id", bloomFilterEnabled = true, cacheNullValues = true)
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
     * Cache level strategy
     */
    CacheLevel cacheLevel() default CacheLevel.ADAPTIVE_CACHE;

    /**
     * Consistency level (CP/AP)
     */
    ConsistencyLevel consistencyLevel() default ConsistencyLevel.HIGH;

    /**
     * Enable broadcast invalidation to other nodes
     */
    boolean broadcastEnabled() default true;

    /**
     * Enable Bloom filter removal
     */
    boolean bloomFilterEnabled() default false;

    /**
     * Custom Bloom filter name
     */
    String bloomFilterName() default "";
}
```

**Usage Examples**:
```java
// Simple eviction
@HccCacheEvict(key = "#orderId")
public void cancelOrder(Long orderId) { ... }

// Without broadcasting (local only)
@HccCacheEvict(key = "#id", broadcastEnabled = false)
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
    private long expireTimeMs;        // Expiration time in milliseconds
    private CacheLevel cacheLevel;    // L1/L2/Adaptive
    private ConsistencyLevel consistencyLevel; // CP/AP
    
    // Additional configuration
    private boolean bloomFilterEnabled;    // Enable Bloom filter check
    private boolean cacheNullValues;       // Cache null values
    private boolean broadcastEnabled;      // Broadcast invalidation
    private String bloomFilterName;        // Custom filter name
}
```

**Usage**:
```java
CacheKey<String> key = CacheKey.<String>builder()
    .key("user:123")
    .expireTimeMs(300000)
    .consistencyLevel(ConsistencyLevel.AVAILABLE)
    .cacheLevel(CacheLevel.ADAPTIVE_CACHE)
    .bloomFilterEnabled(true)
    .cacheNullValues(true)
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
    public static final long MAX_EXPIRE_TIME = Long.MAX_VALUE >> 1;
    
    private V value;              // Actual cached value
    private long expireTime;      // Absolute expiration timestamp (ms since epoch)
    private long createdAt;       // Creation timestamp
    private double weight;        // Weight for LRU prioritization
    
    // Utility methods
    public boolean isExpired();
    public boolean notExist();
    public long getTtl();
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

// Extract actual value (handles null, expired, and non-CacheValue inputs)
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
    /**
     * LOCAL_CACHE: Only L1 (Caffeine)
     * Use for: Low update frequency data like system dictionaries
     */
    LOCAL_CACHE,
    
    /**
     * ADAPTIVE_CACHE: Smart adaptive (recommended)
     * Auto-enhance hot keys to L1, bypass L1 for write-hot keys
     */
    ADAPTIVE_CACHE,
    
    /**
     * L2_CACHE: Only L2 (Redis)
     * Use for: High consistency requirements
     */
    L2_CACHE
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
     * Set broadcaster for invalidation notifications
     */
    void setBroadcaster(Broadcaster broadcaster);
    
    /**
     * Delete local cache entry (used for broadcast invalidation)
     * @param cacheKey Cache key to delete
     */
    void deleteLocalCache(CacheKey cacheKey);
    
    /**
     * Invalidate cache entry (both L1 and L2)
     * @param cacheKey Key to invalidate
     */
    void evict(CacheKey cacheKey);
    
    /**
     * Check if key exists (via Bloom filter if enabled)
     * @param cacheKey Key to check
     * @return true if exists
     */
    boolean exists(CacheKey cacheKey);
    
    /**
     * Get value from cache with loader fallback
     * @param cacheKey Cache key configuration
     * @param doSingleFlightFun Loader function if cache miss
     * @return Cached or loaded value
     */
    CacheValue get(CacheKey cacheKey, Function<Object, Object> doSingleFlightFun);
}
```

**Usage Example**:
```java
@Autowired
private CacheExecutor cacheExecutor;

public Data getData(Long id) {
    CacheKey<String> key = CacheKey.<String>builder()
        .key("data:" + id)
        .expireTimeMs(300000)
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
    
    /**
     * Get current inflight call count
     */
    public int getInflightCount();
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

**Constructor**:
```java
public CacheCircuitBreaker()  // Default settings
public CacheCircuitBreaker(int failureThreshold, int successThreshold, long timeoutMs)
```

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
private enum State {
    CLOSED,     // Normal operation
    OPEN,       // Tripped, failing fast
    HALF_OPEN   // Testing recovery
}
```

**CircuitStats**:
```java
@Data
@Builder
public static class CircuitStats {
    private String state;         // CLOSED/OPEN/HALF_OPEN
    private int failureCount;     // Current failure count
    private int successCount;     // Current success count
    private int totalCalls;       // Total calls made
    private int rejectedCalls;    // Calls rejected when OPEN
    
    public double getRejectionRate();
}
```

---

## Hotspot Detectors | 热点检测器

### CMSHotKeyDetector

Count-Min Sketch based hot key detector. High-performance, concurrent-safe implementation.

**Constructor**:
```java
/**
 * @param width Number of buckets (suggested: 10000-1000000)
 * @param depth Number of hash functions (suggested: 5-10)
 * @param decayRate Decay rate (0.90 ~ 0.99)
 * @param threshold Hot key threshold
 */
public CMSHotKeyDetector(int width, int depth, float decayRate, int threshold)
```

**Methods**:
```java
// Record a read/write operation
public void record(String key);

// Get estimated frequency
public long estimateCount(String key);

// Check if key is hot
public boolean isHotKey(String key);

// Get configuration
public int getWidth();
public int getDepth();
```

**Usage**:
```java
CMSHotKeyDetector detector = new CMSHotKeyDetector(10000, 5, 0.95f, 100);

// On each read
detector.record(cacheKey);

// Check before caching
if (detector.isHotKey(cacheKey)) {
    localCache.put(cacheKey, value);
}
```

---

## Statistics & Monitoring | 统计监控

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
- Checks if value exists and returns `null` if not

**Examples**:
```java
// From CacheValue
CacheValue<String> cv = CacheValue.<String>builder().value("hello").build();
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

**CacheError Values**:
```java
public enum CacheError {
    EMPTY_KEY(100001, "key can't be null"),
    ERROR_KEY_TYPE(100002, "key type is not supported"),
    EMPTY_BROADCASTER_TOPIC(100003, "BroadcasterListener topic can't be empty"),
    NOT_EXISTS_LOCAL_CACHE_CLASS(100004, "not exists local cache class")
}
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
} catch (CacheCircuitBreaker.CircuitBreakerOpenException e) {
    // Fallback strategy
    value = loadFromDatabase(key);
}
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

---

*API Reference Version: 1.0.0*  
*Last Updated: June 2026*