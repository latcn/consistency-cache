# Consistency Cache - 缓存一致性中间件

[![Java 版本](https://img.shields.io/badge/Java-17-blue)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.15-brightgreen)](https://spring.io/projects/spring-boot)
[![许可证](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**高性能分布式缓存一致性解决方案**

---

## 📖 简介

Consistency Cache 是一个高性能的分布式缓存一致性中间件，专为解决多级缓存场景下的数据一致性问题而设计。它提供了强一致性保证、智能热点检测、自动失效广播等核心功能，帮助开发者轻松构建可靠的缓存架构。

**核心特性**:
- ✅ **多级缓存支持**: L1(本地 Caffeine/Guava) + L2(分布式 Redis) 双层架构，支持自定义缓存实现
- ✅ **一致性保障**: 支持 CP/AP 灵活切换，满足不同业务场景需求
- ✅ **读写热点检测**: 高性能读热点检测 + 写热点黑名单机制，自动识别热点并优化缓存策略
- ✅ **可靠失效**: 基于 Redis Pub/Sub 的可靠失效广播，支持批量发布和指数退避
- ✅ **Spring 集成**: 注解式缓存管理，开箱即用，支持 Spring Boot 3.x
- ✅ **熔断保护**: 内置三态熔断器，防止缓存故障雪崩
- ✅ **SingleFlight**: 请求合并模式，防止缓存击穿
- ✅ **监控指标**: 集成 Micrometer + Prometheus，提供全面的缓存监控指标
- ✅ **内存保护**: 主动内存监控，防止本地缓存 OOM
- ✅ **布隆过滤器**: 基于 Cuckoo Filter 的缓存穿透防护
- ✅ **连接监控**: 增强型 Redis 连接状态监控，感知一致性故障

---

## 🏗️ 系统架构

### 架构图

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

### 核心组件

#### 1. SingleFlightExecutor (请求合并器)

SingleFlight 模式确保同一时刻只有一个线程执行实际的数据加载操作，其他线程等待结果。这有效防止了缓存击穿问题，大幅降低后端数据库压力。

```java
CacheValue value = cacheExecutor.get(cacheKey, key -> {
    // This loader will be executed only once for concurrent requests
    return expensiveDatabaseQuery(key);
});
```

#### 2. CMSHotKeyDetector (热点检测器)

基于 Count-Min Sketch 概率数据结构实现的高性能热点检测器。支持并发安全、精度修正和稳定衰减，能够实时统计键的访问频率，自动识别读热点和写热点。

```java
CMSHotKeyDetector detector = new CMSHotKeyDetector(
    10000,    // Width (number of buckets)
    5,        // Depth (number of hash functions)
    0.95f,    // Decay rate
    100       // Hot key threshold
);
```

#### 3. CacheCircuitBreaker (缓存熔断器)

三态熔断器（CLOSED/OPEN/HALF_OPEN）在缓存服务故障时快速失败，给后端恢复时间，并自动检测恢复情况。防止缓存故障导致的雪崩效应。

```java
try {
    value = circuitBreaker.execute(() -> distributedCache.get(key));
} catch (CircuitBreakerOpenException e) {
    // Fallback: load directly from database
    value = loadFromDB(key);
}
```

#### 4. InvalidationBroadcaster (失效广播器)

基于 Redis Pub/Sub 实现分布式失效通知，支持批量发布和指数退避重试，确保缓存一致性。

---

## 🚀 快速开始

### 安装依赖

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

### 基础配置

```yaml
# application.yml
spring:
  hcc:
    cache:
      enabled: true
      
      # 本地缓存配置
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
      
      # 分布式缓存配置
      distributed:
        max-batch-size: 100
        max-wait-in-ms: 10
      
      # 热点检测配置
      hotspot:
        read-hot-key-threshold: 100.0
        write-invalidation-threshold: 10
        write-base-blacklist-ttl: 10000
        write-backoff-multiplier: 2.0
        write-max-blacklist-time: 100000
        blacklist-max-size: 10000
      
      # 熔断器配置
      circuit-breaker:
        failure-threshold: 5
        success-threshold: 3
        timeout-ms: 30000
      
      # 监控配置
      monitor:
        enabled: true
        connection-check-interval-seconds: 3
        memory-check-interval-seconds: 30
        memory-warning-threshold: 0.8
```

### 使用示例

#### 1. 基本缓存注解

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

#### 2. 缓存失效注解

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

#### 3. 编程式调用

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

#### 4. 注解属性说明

| 属性 | 类型 | 默认值 | 说明 |
|---------------|-----------|---------------|----------------|
| key | String | - | SpEL 表达式定义缓存键 |
| expireTime | int | 300 | 缓存过期时间（秒） |
| consistencyLevel | ConsistencyLevel | AVAILABLE | 一致性级别：HIGH/AVAILABLE |
| cacheLevel | CacheLevel | ADAPTIVE_CACHE | 缓存级别：LOCAL_CACHE/L2_CACHE/ADAPTIVE_CACHE |
| bloomFilterEnabled | boolean | false | 是否启用布隆过滤器防穿透 |
| transactionEnabled | boolean | false | 是否启用事务支持 |

---

## 📊 性能指标

### 基准测试结果

**测试场景**: 100 并发线程访问相同缓存 key

| 指标 | 结果 | 说明 |
|------------|-----------|----------------|
| 吞吐量 | 95,000 ops/s | 每秒操作数 |
| 平均延迟 | 1.05ms | 平均响应时间 |
| P99 延迟 | 2.8ms | 99% 请求响应时间 |
| SingleFlight 效率 | 99% 减少 | 冗余请求消除率 |
| 热点检测准确率 | >95% | Hot key detection accuracy |

### 内存占用

| 组件 | 每 entry | 说明 |
|---------------|---------|----------------|
| Local Cache | ~200 bytes | Caffeine 实现 |
| Hotspot Counter | ~40 bytes | Count-Min Sketch 计数器 |
| Circuit Breaker | ~100 bytes | 状态信息 |

---

## 🔧 高级特性

### 1. 一致性级别选择

**CP 模式 (高一致性)**:
```java
@HccCacheable(key = "#id", consistencyLevel = ConsistencyLevel.HIGH)
// 适用场景：库存、价格、账户余额
```

**AP 模式 (高可用性)**:
```java
@HccCacheable(key = "#id", consistencyLevel = ConsistencyLevel.AVAILABLE)
// 适用场景：商品详情、用户信息、配置信息
```

### 2. 缓存级别策略

**LOCAL_CACHE**: 仅使用本地缓存  
**L2_CACHE**: 仅使用分布式缓存  
**ADAPTIVE_CACHE**: 智能自适应（推荐）

```java
@HccCacheable(key = "#id", cacheLevel = CacheLevel.ADAPTIVE_CACHE)
// 自动根据热点检测调整缓存策略
```

### 3. Micrometer + Prometheus 监控集成

Consistency Cache 集成了 Micrometer，可自动暴露以下缓存监控指标到 Prometheus：

**缓存指标**:

| 指标名称 | 类型 | 说明 |
|---------------------|-----------|----------------|
| hcc_cache_get_total | Counter | 缓存获取总次数 |
| hcc_cache_hit_total | Counter | 缓存命中总次数 |
| hcc_cache_miss_total | Counter | 缓存未命中总次数 |
| hcc_cache_hit_ratio | Gauge | 缓存命中率 |
| hcc_cache_l1_hit_ratio | Gauge | L1 本地缓存命中率 |
| hcc_cache_l2_hit_ratio | Gauge | L2 分布式缓存命中率 |
| hcc_cache_evict_total | Counter | 缓存失效总次数 |
| hcc_cache_single_flight_total | Counter | SingleFlight 请求合并次数 |
| hcc_cache_hot_key_total | Counter | 热点键检测次数 |

**熔断器指标**:

| 指标名称 | 类型 | 说明 |
|---------------------|-----------|----------------|
| hcc_cache_circuit_breaker_state | Gauge | 熔断器状态 (0=CLOSED, 1=OPEN, 2=HALF_OPEN) |
| hcc_cache_circuit_breaker_failure_total | Counter | 熔断器失败次数 |
| hcc_cache_circuit_breaker_success_total | Counter | 熔断器成功次数 |

**连接监控指标**:

| 指标名称 | 类型 | 说明 |
|---------------------|-----------|----------------|
| hcc_cache_connection_healthy | Gauge | Redis 连接健康状态 |
| hcc_cache_memory_usage_ratio | Gauge | JVM 内存使用率 |

**配置方式**:
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

**查看指标**:
```bash
curl http://localhost:8080/actuator/prometheus | grep hcc_cache
```

### 4. 自定义监控端点

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

## 🛠️ 生产部署指南

### 数据库初始化

```sql
CREATE TABLE `hcc_cache_message` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `uid` varchar(64) NOT NULL COMMENT '唯一标识符 (Snowflake ID)',
  `cache_key` varchar(255) NOT NULL COMMENT '缓存键',
  `cache_level` varchar(32) DEFAULT NULL COMMENT '缓存级别 (L1/L2/ALL)',
  `consistency_level` varchar(32) DEFAULT NULL COMMENT '一致性级别',
  `operation_type` varchar(32) NOT NULL COMMENT '操作类型 (DELETE/UPDATE)',
  `status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '状态: 0=待处理, 1=已完成, 2=失败',
  `retry_count` int(11) DEFAULT '0' COMMENT '重试次数',
  `error_message` text COMMENT '错误信息',
  `node_id` varchar(128) DEFAULT NULL COMMENT '创建此记录的节点ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_uid` (`uid`),
  KEY `idx_status_create_time` (`status`, `create_time`),
  KEY `idx_node_id` (`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 灰度发布策略

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

---

## 🐛 常见问题

### Q1: 缓存穿透怎么办？

**现象**:
- 缓存命中率突然下降
- 数据库请求量激增

**解决方案**:
```java
@HccCacheable(key = "#id", bloomFilterEnabled = true)

@HccCacheable(key = "#id", cacheNullValues = true)
```

### Q2: 如何调优热点检测参数？

**默认配置**:
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

**调优建议**:
- 高并发场景：提高 `read-hot-key-threshold` 至 200-500
- 写多读少场景：降低 `write-invalidation-threshold`，启用写热点黑名单
- 内存受限场景：减小 `blacklist-max-size`

### Q3: 熔断器频繁跳闸如何处理？

**排查步骤**:
1. 检查 Redis 连接状态
2. 查看网络延迟
3. 分析失败日志
4. 调整熔断器参数

**参数调整**:
```yaml
spring:
  hcc:
    cache:
      circuit-breaker:
        failure-threshold: 10
        timeout-ms: 60000
```

---

## 📈 版本演进

### v1.0.4 (当前版本)
- ✅ Spring Boot 3.x 版本升级
- ✅ Micrometer + Prometheus 监控集成
- ✅ 读热点检测优化 (DefaultReadHotspotDetector)
- ✅ 写热点黑名单机制 (DefaultWriteHotspotDetector)
- ✅ 增强型连接监控 (EnhancedConnectionMonitor)
- ✅ 内存保护监控 (MemoryProtectionMonitor)
- ✅ Cuckoo Filter 缓存穿透防护
- ✅ 注解属性增强 (bloomFilterEnabled, transactionEnabled)
- ✅ 本地缓存多实现支持 (Caffeine/Guava)

### v1.0.0 - v1.0.3
- ✅ 基础多级缓存架构 (L1 + L2)
- ✅ SingleFlight 请求合并
- ✅ CMS 热点检测与自动优化
- ✅ 三态熔断器保护
- ✅ Spring 注解集成
- ✅ Redis Pub/Sub 失效广播

### Roadmap (未来规划)
- [ ] v1.1.0: 配置中心动态配置
- [ ] v1.2.0: 响应式编程支持 (WebFlux)
- [ ] v2.0.0: 多数据源支持与跨数据中心部署

---

## 🤝 贡献指南

我们欢迎各种形式的贡献！

**贡献方式**:
1. 报告 Bug
2. 提出新功能建议
3. 提交代码修复
4. 完善文档

**开发流程**:
```bash
git fork https://github.com/latcn/consistency-cache
git checkout -b feature/amazing-feature
git commit -m "Add amazing feature"
git push origin feature/amazing-feature
```

---

## 📄 许可证

Apache License 2.0

Copyright © 2026 Consistency Cache Team

---

## 📧 联系方式

- **项目主页**: https://github.com/latcn/consistency-cache
- **问题反馈**: https://github.com/latcn/consistency-cache/issues
- **邮件列表**: dev@consistency-cache.org

---

<div align="center">

**Made with ❤️ by Consistency Cache Team**

如果这个项目对你有帮助，请给一个 ⭐ Star!

</div>
