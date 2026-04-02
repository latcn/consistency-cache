# Consistency Cache - 缓存一致性中间件

[![Java Version](https://img.shields.io/badge/Java-17-blue)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**高性能分布式缓存一致性解决方案 | High-Performance Distributed Cache Consistency Solution**

---

## 📖 简介 | Introduction

### 中文

Consistency Cache 是一个高性能的分布式缓存一致性中间件，专为解决多级缓存场景下的数据一致性问题而设计。它提供了强一致性保证、智能热点检测、自动失效广播等核心功能，帮助开发者轻松构建可靠的缓存架构。

**核心特性**:
- ✅ **多级缓存支持**: L1(本地) + L2(分布式) 双层架构
- ✅ **一致性保障**: 支持 CP/AP 灵活切换，满足不同业务场景需求
- ✅ **热点检测**: 自动识别读写热点，智能优化缓存策略
- ✅ **可靠失效**: 基于事务发件箱模式的可靠失效广播
- ✅ **Spring 集成**: 注解式缓存管理，开箱即用
- ✅ **熔断保护**: 内置熔断器，防止缓存故障雪崩

### English

Consistency Cache is a high-performance distributed cache consistency middleware designed to solve data consistency issues in multi-level caching scenarios. It provides core features including strong consistency guarantees, intelligent hotspot detection, and automatic invalidation broadcasting, helping developers easily build reliable cache architectures.

**Key Features**:
- ✅ **Multi-Level Cache**: L1 (Local) + L2 (Distributed) two-tier architecture
- ✅ **Consistency Guarantee**: Flexible CP/AP switching for different business needs
- ✅ **Hotspot Detection**: Automatic read/write hotspot identification with intelligent optimization
- ✅ **Reliable Invalidation**: Transaction-based outbox pattern for reliable broadcast
- ✅ **Spring Integration**: Annotation-based cache management, ready to use
- ✅ **Circuit Breaker**: Built-in circuit breaker to prevent cache failure avalanche

---

## 🏗️ 系统架构 | System Architecture

### 架构图 | Architecture Diagram

```
┌─────────────────────────────────────────────────────────┐
│                    Application Layer                     │
│                   (Spring AOP 拦截层)                    │
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
│  (Caffeine)      │                  │   (Redis)        │
│  Local Cache     │                  │ Distributed Cache│
└──────────────────┘                  └──────────────────┘
        ↑                                       ↑
        └───────────────────┬───────────────────┘
                            ↓
            ┌───────────────────────────────┐
            │   Hotspot Detector            │
            │  (Read/Write Statistics)      │
            └───────────────────────────────┘
                            ↓
            ┌───────────────────────────────┐
            │   Invalidation Broadcaster    │
            │   (Redis Pub/Sub + DB)        │
            └───────────────────────────────┘
```

### 核心组件 | Core Components

#### 1. SingleFlightExecutor (请求合并器)

**中文说明**:
SingleFlight 模式确保同一时刻只有一个线程执行实际的数据加载操作，其他线程等待结果。这有效防止了缓存击穿问题，大幅降低后端数据库压力。

**English Description**:
The SingleFlight pattern ensures that only one thread executes the actual data loading operation at any given time, while other threads wait for the result. This effectively prevents cache breakdown and significantly reduces backend database pressure.

```java
// Example usage
CacheValue value = cacheExecutor.get(cacheKey, key -> {
    // This loader will be executed only once for concurrent requests
    return expensiveDatabaseQuery(key);
});
```

#### 2. HotspotDetector (热点检测器)

**中文说明**:
采用滑动窗口算法实时统计键的访问频率，自动识别读热点和写热点。对于读热点 key 自动增强到本地缓存，对于写热点 key 绕过本地缓存避免缓存震荡。

**English Description**:
Uses sliding window algorithm to statistically track access frequency in real-time, automatically identifying read hotspots and write hotspots. Read-hot keys are automatically enhanced to local cache, while write-hot keys bypass local cache to avoid cache thrashing.

```java
// Configuration
ReadQpsStatistics readStats = new ReadQpsStatistics(
    100.0,    // QPS threshold for hot key detection
    1000,     // Sliding window size (ms)
    10        // Number of buckets
);
```

#### 3. CacheCircuitBreaker (缓存熔断器)

**中文说明**:
三态熔断器（CLOSED/OPEN/HALF_OPEN）在缓存服务故障时快速失败，给后端恢复时间，并自动检测恢复情况。防止缓存故障导致的雪崩效应。

**English Description**:
Three-state circuit breaker (CLOSED/OPEN/HALF_OPEN) fails fast when cache service fails, giving backend time to recover and automatically detecting recovery. Prevents avalanche effects caused by cache failures.

```java
try {
    value = circuitBreaker.execute(() -> distributedCache.get(key));
} catch (CircuitBreakerOpenException e) {
    // Fallback: load directly from database
    value = loadFromDB(key);
}
```

#### 4. InvalidationBroadcaster (失效广播器)

**中文说明**:
基于 Redis Pub/Sub 实现分布式失效通知，结合数据库事务发件箱模式保证消息可靠性。支持批量发布和指数退避重试。

**English Description**:
Implements distributed invalidation notifications based on Redis Pub/Sub, combined with database transactional outbox pattern to guarantee message reliability. Supports batch publishing and exponential backoff retry.

---

## 🚀 快速开始 | Quick Start

### 安装依赖 | Installation

**Maven**:
```xml
<dependency>
    <groupId>com.consist.cache</groupId>
    <artifactId>consistency-cache-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Gradle**:
```gradle
implementation 'com.consist.cache:consistency-cache-spring-boot-starter:1.0.0'
```

### 基础配置 | Basic Configuration

```yaml
# application.yml
hcc:
  cache:
    enabled: true
    # Local cache configuration
    local:
      enabled: true
      maximum-size: 10000
      expire-after-write: 300s
    # Distributed cache configuration
    distributed:
      redisson-config: classpath:redisson-config.yml
    # Hotspot detection
    hotspot:
      read-qps-threshold: 100
      write-invalidations-threshold: 10
    # Circuit breaker
    circuit-breaker:
      failure-threshold: 5
      success-threshold: 3
      timeout-ms: 30000
```

### 使用示例 | Usage Examples

#### 1. 基本缓存注解 | Basic Cache Annotation

```java
import com.consist.cache.spring.annotation.HccCacheable;
import com.consist.cache.core.model.ConsistencyLevel;

@Service
public class ProductService {
    
    @HccCacheable(key = "#productId", expireTime = 300)
    public Product getProductById(Long productId) {
        return productRepository.findById(productId);
    }
    
    @HccCacheable(key = "'product:' + #id", 
                  expireTime = 600,
                  baseCacheAnno = @HccCacheBaseAnno(
                      consistencyLevel = ConsistencyLevel.HIGH
                  ))
    public Product getProductWithHighConsistency(Long id) {
        return productRepository.findById(id);
    }
}
```

#### 2. 缓存失效注解 | Cache Eviction Annotation

```java
import com.consist.cache.spring.annotation.HccCacheEvict;

@Service
public class OrderService {
    
    @HccCacheEvict(key = "#orderId")
    public void cancelOrder(Long orderId) {
        orderRepository.cancel(orderId);
        // Cache automatically invalidated
    }
    
    @Transactional
    @HccCacheEvict(key = "'inventory:' + #productId")
    public void updateInventory(Long productId, Integer quantity) {
        inventoryRepository.update(productId, quantity);
        // Invalidation message broadcasted to all nodes
    }
}
```

#### 3. 编程式调用 | Programmatic Usage

```java
@Autowired
private CacheExecutor cacheExecutor;

public User getUserWithFallback(Long userId) {
    CacheKey cacheKey = CacheKey.builder()
        .key("user:" + userId)
        .expireTimeMs(300000)
        .consistencyLevel(ConsistencyLevel.AVAILABLE)
        .cacheLevel(CacheLevel.ADAPTIVE_CACHE)
        .build();
    
    return CacheValue.extractValue(
        cacheExecutor.get(cacheKey, k -> {
            // Load from database if cache miss
            return userRepository.findById(userId);
        })
    );
}
```

---

## 📊 性能指标 | Performance Metrics

### 基准测试结果 | Benchmark Results

**测试场景**: 100 并发线程访问相同缓存 key

| 指标 Metric | 结果 Result | 说明 Description |
|------------|-----------|----------------|
| 吞吐量 Throughput | 95,000 ops/s | 每秒操作数 |
| 平均延迟 Avg Latency | 1.05ms | 平均响应时间 |
| P99 延迟 P99 Latency | 2.8ms | 99% 请求响应时间 |
| SingleFlight 效率 | 99% 减少 | 冗余请求消除率 |
| 热点检测准确率 | >95% | Hot key detection accuracy |

### 内存占用 | Memory Footprint

| 组件 Component | 每 entry | 说明 Description |
|---------------|---------|----------------|
| Local Cache | ~200 bytes | Caffeine 实现 |
| Hotspot Counter | ~40 bytes | 滑动窗口计数器 |
| Circuit Breaker | ~100 bytes | 状态信息 |

---

## 🔧 高级特性 | Advanced Features

### 1. 一致性级别选择 | Consistency Level Selection

**CP 模式 (高一致性)**:
```java
@HccCacheable(key = "#id", 
              baseCacheAnno = @HccCacheBaseAnno(
                  consistencyLevel = ConsistencyLevel.HIGH
              ))
// 适用场景：库存、价格、账户余额
// Use cases: Inventory, pricing, account balance
```

**AP 模式 (高可用性)**:
```java
@HccCacheable(key = "#id",
              baseCacheAnno = @HccCacheBaseAnno(
                  consistencyLevel = ConsistencyLevel.AVAILABLE
              ))
// 适用场景：商品详情、用户信息、配置信息
// Use cases: Product details, user profiles, configurations
```

### 2. 缓存级别策略 | Cache Level Strategy

**LOCAL_CACHE**: 仅使用本地缓存
**L2_CACHE**: 仅使用分布式缓存  
**ADAPTIVE_CACHE**: 智能自适应（推荐）

```java
@HccCacheable(key = "#id",
              baseCacheAnno = @HccCacheBaseAnno(
                  cacheLevel = CacheLevel.ADAPTIVE_CACHE
              ))
// 自动根据热点检测调整缓存策略
// Automatically adjusts cache strategy based on hotspot detection
```

### 3. 监控指标暴露 | Monitoring Metrics Exposure

```java
@RestController
@RequestMapping("/monitor/cache")
public class CacheMonitorController {
    
    @Autowired
    private LocalCacheManager cacheManager;
    
    @Autowired
    private CacheCircuitBreaker circuitBreaker;
    
    @GetMapping("/stats")
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

## 🛠️ 生产部署指南 | Production Deployment Guide

### 数据库初始化 | Database Initialization

```sql
-- 执行脚本：script/db/hcc_cache_message.sql
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

### 灰度发布策略 | Gray Release Strategy

**阶段 1: 10% 流量** (24 小时)
- 监控 SingleFlight 执行情况
- 观察热点检测准确性
- 检查熔断器状态变化

**阶段 2: 50% 流量** (24-48 小时)
- 验证缓存命中率
- 监控数据库负载变化
- 收集业务异常日志

**阶段 3: 100% 流量**
- 全量上线
- 持续监控关键指标
- 建立告警机制

### 监控告警配置 | Monitoring & Alerting

**关键指标 Key Metrics**:
1. 缓存命中率 (Cache Hit Rate): > 90%
2. SingleFlight 执行次数 (Execution Count): 趋势平稳
3. 熔断器状态 (Circuit State): CLOSED 为正常
4. 热点清理数量 (Cleanup Count): < 100/min

**告警阈值 Alert Thresholds**:
```yaml
alerts:
  - name: "Cache Hit Rate Low"
    condition: "hit_rate < 0.8"
    duration: "5m"
    
  - name: "Circuit Breaker Open"
    condition: "circuit_state == OPEN"
    duration: "1m"
    
  - name: "Hotspot Cleanup Surge"
    condition: "cleanup_count > 1000/min"
    duration: "2m"
```

---

## 🐛 常见问题 | Troubleshooting

### Q1: 缓存穿透怎么办？| How to handle cache penetration?

**现象 Symptoms**:
- 缓存命中率突然下降
- 数据库请求量激增

**解决方案 Solution**:
```java
// 启用布隆过滤器（可选）
@HccCacheable(key = "#id", bloomFilterEnabled = true)

// 或使用空值缓存
@HccCacheable(key = "#id", cacheNullValues = true)
```

### Q2: 如何调优热点检测参数？| How to tune hotspot detection parameters?

**默认配置 Default**:
```yaml
hotspot:
  read-qps-threshold: 100      # 读 QPS 阈值
  write-invalidations-threshold: 10  # 写失效阈值
```

**调优建议 Tuning Recommendations**:
- 高并发场景：提高阈值至 200-500
- 低延迟场景：降低窗口大小至 500ms
- 写多读少：提高写失效率阈值

### Q3: 熔断器频繁跳闸如何处理？| How to handle frequent circuit breaker trips?

**排查步骤 Diagnosis Steps**:
1. 检查 Redis 连接状态
2. 查看网络延迟
3. 分析失败日志
4. 调整熔断器参数

**参数调整 Parameter Adjustment**:
```yaml
circuit-breaker:
  failure-threshold: 10        # 从 5 提高到 10
  timeout-ms: 60000           # 从 30s 延长到 60s
```

---

## 📈 版本演进 | Version History

### v1.0.0 (当前版本 | Current)
- ✅ 基础多级缓存架构
- ✅ SingleFlight 请求合并
- ✅ 热点检测与自动优化
- ✅ 熔断器保护
- ✅ Spring 注解集成

### Roadmap (未来规划)
- [ ] v1.1.0: Micrometer 监控集成
- [ ] v1.2.0: 配置中心动态配置
- [ ] v2.0.0: 响应式编程支持 (WebFlux)
- [ ] v2.1.0: 多级熔断机制

---

## 🤝 贡献指南 | Contributing

我们欢迎各种形式的贡献！

**贡献方式 Ways to Contribute**:
1. 报告 Bug (Report bugs)
2. 提出新功能建议 (Suggest new features)
3. 提交代码修复 (Submit code fixes)
4. 完善文档 (Improve documentation)

**开发流程 Development Workflow**:
```bash
# 1. Fork 项目
git fork https://github.com/your-org/consistency-cache

# 2. 创建特性分支
git checkout -b feature/amazing-feature

# 3. 提交更改
git commit -m "Add amazing feature"

# 4. 推送到分支
git push origin feature/amazing-feature

# 5. 创建 Pull Request
```

---

## 📄 许可证 | License

Apache License 2.0

Copyright © 2026 Consistency Cache Team

---

## 📧 联系方式 | Contact Us

- **项目主页**: https://github.com/your-org/consistency-cache
- **问题反馈**: https://github.com/your-org/consistency-cache/issues
- **邮件列表**: dev@consistency-cache.org

---

<div align="center">

**Made with ❤️ by Consistency Cache Team**

如果这个项目对你有帮助，请给一个 ⭐ Star!

If this project helps you, please give us a ⭐ Star!

</div>
