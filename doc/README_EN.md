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

High-performance hot key detector based on Count-Min Sketch probabilistic data structure. Supports thread-safe operations, precision correction, and stable decay for real-time access frequency tracking.

```java
CMSHotKeyDetector detector = new CMSHotKeyDetector(
    10000,    // Width (number of buckets)
    5,        // Depth (number of hash functions)
    0.95f,    // Decay rate
    100       // Hot key threshold
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
    <version>1.0.4</version>
</dependency>
```

**Gradle**:
```gradle
implementation 'io.github.latcn:consistency-cache-spring-boot-starter:1.0.4'
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
        initial-capacity: 100
        maximum-size: 10000
        expire-after-write: 600
        expire-after-access: 600
        buffer-time-ms: 1000
        channel-names: hcc_cache_evict
        batch-size: 100
        max-wait-seconds: 5

      # Distributed cache configuration
      distributed:
        max-batch-size: 100
        max-wait-in-ms: 10

      # Hotspot detection
      hotspot:
        read-hot-key-threshold: 100.0
        write-invalidation-threshold: 10
        write-base-blacklist-ttl: 10000
        write-backoff-multiplier: 2.0
        write-max-blacklist-time: 100000
        blacklist-max-size: 10000

      # Circuit breaker
      circuit-breaker:
        failure-threshold: 5
        success-threshold: 3
        timeout-ms: 30000

      # Monitor configuration
      monitor:
        enabled: true
        connection-check-interval-seconds: 3
        memory-check-interval-seconds: 30
        memory-warning-threshold: 0.8
```

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
                  bloomFilterEnabled = true,
                  transactionEnabled = false)
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
| expireTime | int | 300 | Cache expiration time (seconds) |
| consistencyLevel | ConsistencyLevel | AVAILABLE | Consistency level: HIGH/AVAILABLE |
| cacheLevel | CacheLevel | ADAPTIVE_CACHE | Cache level: LOCAL_CACHE/L2_CACHE/ADAPTIVE_CACHE |
| bloomFilterEnabled | boolean | false | Enable bloom filter for cache penetration prevention |
| transactionEnabled | boolean | false | Enable transaction support |

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
CREATE TABLE `hcc_cache_message` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `uid` varchar(64) NOT NULL COMMENT 'Unique identifier (Snowflake ID)',
  `cache_key` varchar(255) NOT NULL COMMENT 'Cache key to invalidate',
  `cache_level` varchar(32) DEFAULT NULL COMMENT 'Cache level (L1/L2/ALL)',
  `consistency_level` varchar(32) DEFAULT NULL COMMENT 'Consistency level',
  `operation_type` varchar(32) NOT NULL COMMENT 'Operation type (DELETE/UPDATE)',
  `status` tinyint(4) NOT NULL DEFAULT '0' COMMENT 'Status: 0=PENDING, 1=COMPLETED, 2=FAILED',
  `retry_count` int(11) DEFAULT '0' COMMENT 'Retry count',
  `error_message` text COMMENT 'Error message on failure',
  `node_id` varchar(128) DEFAULT NULL COMMENT 'Node ID that created this record',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_uid` (`uid`),
  KEY `idx_status_create_time` (`status`, `create_time`),
  KEY `idx_node_id` (`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
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
      hotspot:
        read-hot-key-threshold: 100.0
        write-invalidation-threshold: 10
        write-base-blacklist-ttl: 10000
        write-backoff-multiplier: 2.0
        write-max-blacklist-time: 100000
        blacklist-max-size: 10000
```

**Tuning Recommendations**:
- High concurrency scenarios: Increase `read-hot-key-threshold` to 200-500
- Write-heavy scenarios: Decrease `write-invalidation-threshold` and enable write hotspot blacklist
- Memory-constrained scenarios: Reduce `blacklist-max-size`

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
        failure-threshold: 10
        timeout-ms: 60000
```

---

## 📈 Version History

### v1.0.4 (Current)
- ✅ Spring Boot 3.x upgrade
- ✅ Micrometer + Prometheus monitoring integration
- ✅ Read hotspot detection optimization (DefaultReadHotspotDetector)
- ✅ Write hotspot blacklist mechanism (DefaultWriteHotspotDetector)
- ✅ Enhanced connection monitoring (EnhancedConnectionMonitor)
- ✅ Memory protection monitoring (MemoryProtectionMonitor)
- ✅ Cuckoo Filter cache penetration protection
- ✅ Annotation attribute enhancements (bloomFilterEnabled, transactionEnabled)
- ✅ Multiple local cache implementation support (Caffeine/Guava)

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
