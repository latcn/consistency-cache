# Consistency Cache - Cache Consistency Middleware

[![Java Version](https://img.shields.io/badge/Java-17-blue)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.15-brightgreen)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**High-Performance Distributed Cache Consistency Solution**

---

## 📖 Introduction

Consistency Cache is a high-performance distributed cache consistency middleware designed to solve data consistency issues in multi-level caching scenarios. It provides core features including strong consistency guarantees, intelligent hotspot detection, and automatic invalidation broadcasting, helping developers easily build reliable cache architectures.

**Key Features**:
- ✅ **Multi-Level Cache**: L1 (Local Caffeine/Guava) + L2 (Distributed Redis) two-tier architecture with custom cache support
- ✅ **Consistency Guarantee**: Flexible CP/AP switching for different business needs
- ✅ **Read/Write Hotspot Detection**: High-performance read hotspot detection + write hotspot blacklist mechanism
- ✅ **Reliable Invalidation**: Redis Pub/Sub based reliable invalidation broadcasting with batch publishing
- ✅ **Spring Integration**: Annotation-based cache management, ready to use with Spring Boot 3.x
- ✅ **Circuit Breaker**: Built-in three-state circuit breaker to prevent cache failure avalanche
- ✅ **SingleFlight**: Request collapsing to prevent cache breakdown
- ✅ **Monitoring Metrics**: Integrated Micrometer + Prometheus for comprehensive cache monitoring
- ✅ **Memory Protection**: Proactive memory monitoring to prevent local cache OOM
- ✅ **Bloom Filter**: Cuckoo Filter based cache penetration protection
- ✅ **Connection Monitor**: Enhanced Redis connection monitoring with consistency-aware failure handling

---

## 🏗️ System Architecture

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────┐
│                    Application Layer                     │
│                   (Spring AOP Interceptor)              │
└─────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────┐
│              Cache Execution Layer                       │
│          (SingleFlight + Circuit Breaker)                │
└─────────────────────────────────────────────────────────┘
                            ↓
        ┌───────────────────┴───────────────────┐
        ↓                                       ↓
┌──────────────────┐                  ┌──────────────────┐
│   L1 Cache       │                  │   L2 Cache       │
│  (Caffeine/Guava)│                  │   (Redis)        │
│  Local Cache     │                  │ Distributed Cache│
└──────────────────┘                  └──────────────────┘
        ↑                                       ↑
        └───────────────────┬───────────────────┘
                            ↓
            ┌───────────────────────────────┐
            │   CMS Hotspot Detector        │
            │  (Count-Min Sketch)           │
            └───────────────────────────────┘
                            ↓
            ┌───────────────────────────────┐
            │   Invalidation Broadcaster    │
            │   (Redis Pub/Sub)             │
            └───────────────────────────────┘
                            ↓
            ┌───────────────────────────────┐
            │   EnhancedConnectionMonitor   │
            │   MemoryProtectionMonitor     │
            └───────────────────────────────┘
```

### Core Components

#### 1. SingleFlightExecutor

The SingleFlight pattern ensures that only one thread executes the actual data loading operation at any given time, while other threads wait for the result. This effectively prevents cache breakdown and significantly reduces backend database pressure.

```java
CacheValue value = cacheExecutor.get(cacheKey, key -> {
    // This loader will be executed only once for concurrent requests
    return expensiveDatabaseQuery(key);
});
```

#### 2. CMSHotKeyDetector

High-performance hot key detector based on lazy-decay Count-Min Sketch probabilistic data structure. No global scan, no background thread, supports thread-safe operations and stable decay for real-time access frequency tracking.

**Auto-configuration Mode (Recommended)**：
```java
CMSHotKeyDetector detector = new CMSHotKeyDetector(
    10000L,   // totalQps: Estimated total request volume (per second)
    100       // targetHotQps: Hot key threshold (requests per second)
);
```

**Manual Configuration Mode**：
```java
CMSHotKeyDetector detector = new CMSHotKeyDetector(
    10000,    // width: Number of hash buckets
    4,        // depth: Number of hash functions
    5000      // sampleSize: Sample size
);
```

#### 3. CacheCircuitBreaker

Three-state circuit breaker (CLOSED/OPEN/HALF_OPEN) fails fast when cache service fails, giving backend time to recover and automatically detecting recovery. Prevents avalanche effects caused by cache failures.

```java
try {
    value = circuitBreaker.execute(() -> distributedCache.get(key));
} catch (CircuitBreakerOpenException e) {
    // Fallback: load directly from database
    value = loadFromDB(key);
}
```

#### 4. InvalidationBroadcaster

Implements distributed invalidation notifications based on Redis Pub/Sub, supporting batch publishing and exponential backoff retry for cache consistency.

---

## 🚀 Quick Start

### Installation

**Maven**:
```xml
<dependency>
    <groupId>io.github.latcn</groupId>
    <artifactId>consistency-cache-spring-boot-starter</artifactId>
    <version>1.0.5</version>
</dependency>
```

**Gradle**:
```gradle
implementation 'io.github.latcn:consistency-cache-spring-boot-starter:1.0.5'
```

### Basic Configuration

```yaml
# application.yml
spring:
  hcc:
    cache:
      enabled: true

      # Local cache configuration
      local:
        cache-type: CAFFEINE
        cache-clz: io.github.latcn.cache.spring.local.adapter.CaffeineCacheAdapter
        initial-capacity: 100
        maximum-size: 100000
        buffer-time-ms: 1000
        clean-period-seconds: 5
        marker-max-size: 100000

      # Distributed cache configuration
      distributed:
        cache-operation-size: 1000
        max-batch-size: 100
        max-wait-ms: 10

      # Read hotspot detection configuration
      read-hot:
        total-qps: 10000
        hot-qps: 100
        promotion-ratio: 0.7
        max-exact-size: 2000
        expiration-time-ms: 30000
        cleanup-interval-ms: 5000

      # Write hotspot detection configuration
      write-hot:
        total-qps: 10000
        hot-qps: 100
        promotion-ratio: 0.7
        max-exact-size: 2000
        expiration-time-ms: 30000
        cleanup-interval-ms: 5000

      # Circuit breaker
      circuit-breaker:
        fail-ratio: 0.5
        timeout-ms: 30000

      # Monitor configuration
      monitor:
        enabled: true
        connection-check-interval-seconds: 3
        memory-check-interval-seconds: 30
        memory-warning-threshold: 0.8

      # Cache eviction configuration
      cache-evict:
        channel-names: hcc_cache_evict
        batch-size: 100
        max-wait-seconds: 5
        invalidation-queue-capacity: 1000
        clean-cache-period-seconds: 1
        base-delay-ms: 1000
        compensation-batch-size: 50
        compensation-period-seconds: 10
        max-retry-count: 5
```

### Configuration Parameter Description

#### Local Cache Configuration (local)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| cache-type | String | CAFFEINE | Cache implementation type: GUAVA, CAFFEINE, CUSTOM |
| cache-clz | String | CaffeineCacheAdapter | Fully qualified name of custom cache implementation class |
| initial-capacity | int | 100 | Initial capacity |
| maximum-size | long | 100000 | Maximum capacity |
| buffer-time-ms | long | 1000 | Buffer time (milliseconds) |
| clean-period-seconds | int | 5 | Cleanup period (seconds) |
| marker-max-size | int | 100000 | Maximum marker size |

#### Distributed Cache Configuration (distributed)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| cache-operation-size | int | 1000 | Cache operation queue size |
| max-batch-size | int | 100 | Maximum batch operation size |
| max-wait-ms | int | 10 | Maximum wait time (milliseconds) |

#### Read Hotspot Detection Configuration (read-hot)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| total-qps | long | 10000 | Estimated total request volume (requests per second), system peak average QPS |
| hot-qps | int | 100 | Business-defined hot key threshold (requests per second), e.g., "more than 100 accesses per second is considered hot" |
| promotion-ratio | double | 0.7 | Promotion threshold ratio to hot threshold (0~1) |
| max-exact-size | int | 2000 | Maximum exact layer capacity (upper limit of tracked keys), recommended to be estimated hot key count × (2 ~ 3) |
| expiration-time-ms | long | 30000 | Key expiration time in exact layer (milliseconds). If the key's last access time exceeds this and count is below hotQps, it will be evicted |
| cleanup-interval-ms | long | 5000 | Regular cleanup task interval (milliseconds). Background thread periodically traverses exact layer to evict expired keys below threshold |

#### Write Hotspot Detection Configuration (write-hot)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| total-qps | long | 10000 | Estimated total write request volume (requests per second) |
| hot-qps | int | 100 | Business-defined write hot key threshold (requests per second) |
| promotion-ratio | double | 0.7 | Promotion threshold ratio to hot threshold (0~1) |
| max-exact-size | int | 2000 | Maximum exact layer capacity |
| expiration-time-ms | long | 30000 | Key expiration time in exact layer (milliseconds) |
| cleanup-interval-ms | long | 5000 | Regular cleanup task interval (milliseconds) |

#### Circuit Breaker Configuration (circuit-breaker)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| fail-ratio | double | 0.5 | Failure ratio threshold (triggers circuit break when exceeded) |
| timeout-ms | int | 30000 | Circuit break timeout (milliseconds), enters half-open state after timeout |

#### Monitor Configuration (monitor)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| enabled | boolean | true | Whether to enable monitoring |
| connection-check-interval-seconds | int | 3 | Connection check interval (seconds) |
| memory-check-interval-seconds | int | 30 | Memory check interval (seconds) |
| memory-warning-threshold | double | 0.8 | Memory warning threshold (0-1) |

#### Cache Eviction Configuration (cache-evict)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| channel-names | String | hcc_cache_evict | Cache invalidation broadcast topic name |
| batch-size | int | 100 | Batch eviction size |
| max-wait-seconds | int | 5 | Maximum wait time (seconds) |
| invalidation-queue-capacity | int | 1000 | Invalidation queue capacity |
| clean-cache-period-seconds | int | 1 | Cache cleanup period (seconds) |
| base-delay-ms | long | 1000 | Base delay time (milliseconds) |
| compensation-batch-size | int | 50 | Compensation batch size |
| compensation-period-seconds | int | 10 | Compensation period (seconds) |
| max-retry-count | int | 5 | Maximum retry count |

### Usage Examples

#### 1. Basic Cache Annotation

```java
import io.github.latcn.cache.spring.annotation.HccCacheable;
import io.github.latcn.cache.core.model.ConsistencyLevel;

@Service
public class ProductService {
    
    @HccCacheable(key = "#productId", expireTime = 300)
    public Product getProductById(Long productId) {
        return productRepository.findById(productId);
    }
    
    @HccCacheable(key = "'product:' + #id", 
                  expireTime = 600,
                  consistencyLevel = ConsistencyLevel.HIGH)
    public Product getProductWithHighConsistency(Long id) {
        return productRepository.findById(id);
    }
    
    @HccCacheable(key = "#userId",
                  expireTime = 600,
                  bloomFilterEnabled = true)
    public User getUserById(Long userId) {
        return userRepository.findById(userId);
    }
}
```

#### 2. Cache Eviction Annotation

```java
import io.github.latcn.cache.spring.annotation.HccCacheEvict;

@Service
public class OrderService {
    
    @HccCacheEvict(key = "#orderId")
    public void cancelOrder(Long orderId) {
        orderRepository.cancel(orderId);
    }
    
    @Transactional
    @HccCacheEvict(key = "'inventory:' + #productId")
    public void updateInventory(Long productId, Integer quantity) {
        inventoryRepository.update(productId, quantity);
    }
}
```

#### 3. Programmatic Usage

```java
@Autowired
private CacheExecutor cacheExecutor;

public User getUserWithFallback(Long userId) {
    CacheKey<String> cacheKey = CacheKey.<String>builder()
        .key("user:" + userId)
        .expireTimeMs(300000)
        .consistencyLevel(ConsistencyLevel.AVAILABLE)
        .cacheLevel(CacheLevel.ADAPTIVE_CACHE)
        .build();
    
    return CacheValue.extractValue(
        cacheExecutor.get(cacheKey, k -> {
            return userRepository.findById(userId);
        })
    );
}
```

#### 4. Annotation Attributes

| Attribute | Type | Default | Description |
|---------------|-----------|---------------|----------------|
| key | String | - | SpEL expression for cache key |
| expireTime | int | 0 | Cache expiration time (seconds), 0 means use global config |
| consistencyLevel | ConsistencyLevel | HIGH | Consistency level: HIGH/AVAILABLE |
| cacheLevel | CacheLevel | ADAPTIVE_CACHE | Cache level: LOCAL_CACHE/L2_CACHE/ADAPTIVE_CACHE |
| bloomFilterEnabled | boolean | false | Enable bloom filter for cache penetration prevention |
| cacheNullValues | boolean | true | Whether to cache null values |
| broadcastEnabled | boolean | true | Enable invalidation broadcast |
| bloomFilterName | String | "" | Custom bloom filter name |
| fallbackExecActual | boolean | false | Execute actual method on cache miss |

---

## 📊 Performance Metrics

### Benchmark Results

**Test Scenario**: 100 concurrent threads accessing the same cache key

| Metric | Result | Description |
|------------|-----------|----------------|
| Throughput | 95,000 ops/s | Operations per second |
| Avg Latency | 1.05ms | Average response time |
| P99 Latency | 2.8ms | 99th percentile response time |
| SingleFlight Efficiency | 99% reduction | Redundant request elimination rate |
| Hot Key Detection Accuracy | >95% | Hot key detection accuracy |

### Memory Footprint

| Component | Per Entry | Description |
|---------------|---------|----------------|
| Local Cache | ~200 bytes | Caffeine implementation |
| Hotspot Counter | ~40 bytes | Count-Min Sketch counter |
| Circuit Breaker | ~100 bytes | State information |

---

## 🔧 Advanced Features

### 1. Consistency Level Selection

**CP Mode (High Consistency)**:
```java
@HccCacheable(key = "#id", consistencyLevel = ConsistencyLevel.HIGH)
// Use cases: Inventory, pricing, account balance
```

**AP Mode (High Availability)**:
```java
@HccCacheable(key = "#id", consistencyLevel = ConsistencyLevel.AVAILABLE)
// Use cases: Product details, user profiles, configurations
```

### 2. Cache Level Strategy

**LOCAL_CACHE**: Local cache only  
**L2_CACHE**: Distributed cache only  
**ADAPTIVE_CACHE**: Intelligent adaptive (recommended)

```java
@HccCacheable(key = "#id", cacheLevel = CacheLevel.ADAPTIVE_CACHE)
// Automatically adjusts cache strategy based on hotspot detection
```

### 3. Micrometer + Prometheus Monitoring Integration

Consistency Cache integrates with Micrometer and automatically exposes the following cache monitoring metrics to Prometheus:

**Cache Metrics**:

| Metric Name | Type | Description |
|---------------------|-----------|----------------|
| hcc_cache_get_total | Counter | Total cache get operations |
| hcc_cache_hit_total | Counter | Total cache hits |
| hcc_cache_miss_total | Counter | Total cache misses |
| hcc_cache_hit_ratio | Gauge | Cache hit ratio |
| hcc_cache_l1_hit_ratio | Gauge | L1 local cache hit ratio |
| hcc_cache_l2_hit_ratio | Gauge | L2 distributed cache hit ratio |
| hcc_cache_evict_total | Counter | Total cache evictions |
| hcc_cache_single_flight_total | Counter | SingleFlight request merging count |
| hcc_cache_hot_key_total | Counter | Hot key detection count |

**Circuit Breaker Metrics**:

| Metric Name | Type | Description |
|---------------------|-----------|----------------|
| hcc_cache_circuit_breaker_state | Gauge | Circuit breaker state (0=CLOSED, 1=OPEN, 2=HALF_OPEN) |
| hcc_cache_circuit_breaker_failure_total | Counter | Circuit breaker failure count |
| hcc_cache_circuit_breaker_success_total | Counter | Circuit breaker success count |

**Connection Metrics**:

| Metric Name | Type | Description |
|---------------------|-----------|----------------|
| hcc_cache_connection_healthy | Gauge | Redis connection health status |
| hcc_cache_memory_usage_ratio | Gauge | JVM memory usage ratio |

**Configuration**:
```yaml
spring:
  application:
    name: consistency-cache-demo
  hcc:
    cache:
      monitor:
        enabled: true

management:
  endpoints:
    web:
      exposure:
        include: metrics, prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

**Access Metrics**:
```bash
curl http://localhost:8080/actuator/prometheus | grep hcc_cache
```

### 4. Custom Monitoring Endpoints

```java
@RestController
@RequestMapping("/monitor/cache")
public class CacheMonitorController {
    
    @Autowired
    private LocalCacheManager cacheManager;
    
    @Autowired
    private CacheCircuitBreaker circuitBreaker;
    
    @GetMapping("/localCacheStats")
    public CacheStats getCacheStats() {
        return cacheManager.getStats();
    }
    
    @GetMapping("/circuit-breaker")
    public CircuitStats getCircuitBreakerStats() {
        return circuitBreaker.getStats();
    }
}
```

---

## 🛠️ Production Deployment Guide

### Database Initialization

```sql
CREATE TABLE `invalidation_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `uid` varchar(64) NOT NULL COMMENT 'Unique identifier (Snowflake ID)',
  `cache_key` varchar(255) NOT NULL COMMENT 'Cache key to invalidate, format: cacheName:key',
  `cache_level` varchar(32) DEFAULT NULL COMMENT 'Cache level (L1/L2/ALL)',
  `consistency_level` varchar(32) DEFAULT NULL COMMENT 'Consistency level (HIGH/AVAILABLE)',
  `operation_type` varchar(32) NOT NULL COMMENT 'Operation type (DELETE/UPDATE)',
  `node_id` varchar(128) DEFAULT NULL COMMENT 'Node ID that created this record (hostname-pid)',
  `status` tinyint(4) NOT NULL DEFAULT '0' COMMENT 'Status: 0=PENDING, 1=COMPLETED, 2=FAILED',
  `retry_count` int(11) DEFAULT '0' COMMENT 'Retry count',
  `error_message` text COMMENT 'Error message on failure',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
  `next_execution_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT 'Next execution time',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_uid` (`uid`),
  KEY `idx_status_execution_time` (`status`, `next_execution_time`),
  KEY `idx_node_id` (`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Cache invalidation message table (Transactional Outbox Pattern)';
```

### Gray Release Strategy

**Phase 1: 10% Traffic** (24 hours)
- Monitor SingleFlight execution
- Observe hotspot detection accuracy
- Check circuit breaker state changes

**Phase 2: 50% Traffic** (24-48 hours)
- Verify cache hit ratio
- Monitor database load changes
- Collect business exception logs

**Phase 3: 100% Traffic**
- Full deployment
- Continuous monitoring of key metrics
- Establish alerting mechanisms

---

## 🐛 Troubleshooting

### Q1: How to handle cache penetration?

**Symptoms**:
- Cache hit ratio suddenly drops
- Database request volume surges

**Solution**:
```java
@HccCacheable(key = "#id", bloomFilterEnabled = true)

@HccCacheable(key = "#id", cacheNullValues = true)
```

### Q2: How to tune hotspot detection parameters?

**Default Configuration**:
```yaml
spring:
  hcc:
    cache:
      read-hot:
        total-qps: 10000
        hot-qps: 100
        promotion-ratio: 0.7
        max-exact-size: 2000
        expiration-time-ms: 30000
        cleanup-interval-ms: 5000

      write-hot:
        total-qps: 10000
        hot-qps: 100
        promotion-ratio: 0.7
        max-exact-size: 2000
        expiration-time-ms: 30000
        cleanup-interval-ms: 5000
```

**Tuning Recommendations**:
- High concurrency scenarios: Increase `total-qps` and `hot-qps` parameters based on actual system peak
- Write-heavy scenarios: Decrease `write-hot.hot-qps` threshold
- Memory-constrained scenarios: Reduce `max-exact-size` to limit tracked keys in exact layer
- High business fluctuation scenarios: Reduce `expiration-time-ms` to speed up expired key cleanup

### Q3: How to handle frequent circuit breaker trips?

**Diagnosis Steps**:
1. Check Redis connection status
2. Check network latency
3. Analyze failure logs
4. Adjust circuit breaker parameters

**Parameter Adjustment**:
```yaml
spring:
  hcc:
    cache:
      circuit-breaker:
        fail-ratio: 0.8
        timeout-ms: 60000
```

---

## 📈 Version History

### v1.0.5 (Current)
- ✅ Spring Boot 3.x upgrade
- ✅ Micrometer + Prometheus monitoring integration
- ✅ Lazy-decay CMS hot key detector refactoring (no global scan, no background thread)
- ✅ Hotspot detection configuration refactoring (read-hot / write-hot separate config)
- ✅ Enhanced connection monitoring (EnhancedConnectionMonitor)
- ✅ Memory protection monitoring (MemoryProtectionMonitor)
- ✅ Cuckoo Filter cache penetration protection
- ✅ Annotation attribute enhancements (bloomFilterEnabled, transactionEnabled)
- ✅ Multiple local cache implementation support (Caffeine/Guava)

### v1.0.4
- ✅ Read hotspot detection optimization (DefaultReadHotspotDetector)
- ✅ Write hotspot blacklist mechanism (DefaultWriteHotspotDetector)

### v1.0.0 - v1.0.3
- ✅ Basic multi-level cache architecture (L1 + L2)
- ✅ SingleFlight request merging
- ✅ CMS hotspot detection and automatic optimization
- ✅ Three-state circuit breaker protection
- ✅ Spring annotation integration
- ✅ Redis Pub/Sub invalidation broadcasting

### Roadmap
- [ ] v1.1.0: Configuration center dynamic configuration
- [ ] v1.2.0: Reactive programming support (WebFlux)
- [ ] v2.0.0: Multi-datasource support and cross-datacenter deployment

---

## 🤝 Contributing

We welcome contributions in all forms!

**Ways to Contribute**:
1. Report bugs
2. Suggest new features
3. Submit code fixes
4. Improve documentation

**Development Workflow**:
```bash
git fork https://github.com/latcn/consistency-cache
git checkout -b feature/amazing-feature
git commit -m "Add amazing feature"
git push origin feature/amazing-feature
```

---

## 📄 License

Apache License 2.0

Copyright © 2026 Consistency Cache Team

---

## 📧 Contact Us

- **Homepage**: https://github.com/latcn/consistency-cache
- **Issues**: https://github.com/latcn/consistency-cache/issues
- **Mailing List**: dev@consistency-cache.org

---

<div align="center">

**Made with ❤️ by Consistency Cache Team**

If this project helps you, please give us a ⭐ Star!

</div>
