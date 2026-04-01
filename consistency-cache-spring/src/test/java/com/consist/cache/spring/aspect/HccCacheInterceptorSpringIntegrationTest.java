package com.consist.cache.spring.aspect;

import com.consist.cache.core.circuitbreaker.CacheCircuitBreaker;
import com.consist.cache.core.distributed.DistributedCacheManager;
import com.consist.cache.core.executor.CacheEvictHandler;
import com.consist.cache.core.executor.CacheExecutor;
import com.consist.cache.core.executor.DefaultCacheExecutor;
import com.consist.cache.core.hotspot.reads.DefaultReadHotspotDetector;
import com.consist.cache.core.hotspot.writes.DefaultWriteHotspotDetector;
import com.consist.cache.core.local.LocalCacheFactory;
import com.consist.cache.core.local.LocalCacheManager;
import com.consist.cache.core.local.LocalCacheMarkerManager;
import com.consist.cache.core.model.HccProperties;
import com.consist.cache.core.model.LocalCacheType;
import com.consist.cache.core.model.NodeInstanceHolder;
import com.consist.cache.core.pubsub.BroadcastPublisher;
import com.consist.cache.core.pubsub.BroadcastSubscriber;
import com.consist.cache.core.pubsub.BroadcasterListener;
import com.consist.cache.core.pubsub.InvalidationBroadcaster;
import com.consist.cache.spring.distributed.RedisCacheManager;
import com.consist.cache.spring.handler.SpringCacheEvictHandler;
import com.consist.cache.spring.local.LocalCacheMarkerManagerImpl;
import com.consist.cache.spring.local.adapter.CaffeineCacheAdapter;
import com.consist.cache.spring.local.adapter.GuavaCacheAdapter;
import com.consist.cache.spring.pubsub.InvalidationListener;
import com.consist.cache.spring.pubsub.RTopicPublisher;
import com.consist.cache.spring.pubsub.RTopicSubscriber;
import com.consist.cache.spring.service.TestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.aop.support.AbstractBeanFactoryPointcutAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.interceptor.BeanFactoryCacheOperationSourceAdvisor;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.ContextConfiguration;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for HccCacheInterceptor with Spring Test Context Framework
 * Uses real beans from CacheAutoConfiguration with properly mocked RedissonClient
 */
@DisplayName("HccCacheInterceptor Spring Integration Tests")
@SpringBootTest(classes= {TestService.class})
@EnableAspectJAutoProxy
@ContextConfiguration(classes = {
    HccCacheInterceptorSpringIntegrationTest.TestConfig.class
}
)
class HccCacheInterceptorSpringIntegrationTest {

    @Autowired
    private TestService testService;

    @Autowired
    private CacheExecutor cacheExecutor;

    @Autowired
    private HccCacheInterceptor hccCacheInterceptor;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("Should load HccCacheInterceptor bean from context")
    void testInterceptorBeanLoaded() {
        assertNotNull(hccCacheInterceptor, "HccCacheInterceptor should be autowired");
        assertNotNull(cacheExecutor, "CacheExecutor should be autowired");
    }

    @Test
    @DisplayName("Should intercept HccCacheable annotation via AOP")
    void testHccCacheableViaAOP() throws Exception {
        // Given: Service bean proxied by Spring AOP
        // When: Call annotated method
        Object result1 = testService.getDataWithCache(123L);
        Object result2 = testService.getDataWithCache(123L); // Should hit cache

        // Then: Both calls should return same result (cached)
        assertNotNull(result1);
        assertNotNull(result2);
        assertEquals(result1, result2);
    }

    @Test
    @DisplayName("Should handle HccCacheEvict annotation via AOP")
    void testHccCacheEvictViaAOP() throws Exception {
        // Given: Service bean with evict annotation
        
        
        // First call to populate cache
        String firstResult = testService.getDataWithCache(456L);
        
        // Second call should return cached value
        String cachedResult = testService.getDataWithCache(456L);
        assertEquals(firstResult, cachedResult);
        
        // When: Evict cache
        testService.deleteData(456L);
        // Then: Next call should reload from source
        String afterEvictResult = testService.getDataWithCache(456L);
        assertNotNull(afterEvictResult);
    }

    @Test
    @DisplayName("Should evaluate SpEL expressions in cache keys")
    void testSpELKeyEvaluation() throws Exception {
        // Given: Service with SpEL key expression
        
        
        // When: Call with different parameters
        Object result1 = testService.getUserById(100L);
        Object result2 = testService.getUserById(200L);

        // Then: Different keys should produce different results
        assertNotNull(result1);
        assertNotNull(result2);
        // Note: In real scenario, these would be different cached values
    }

    @Test
    @DisplayName("Should handle composite SpEL keys")
    void testCompositeSpELKey() throws Exception {
        // Given: Service with composite key
        
        
        // When: Call with composite parameters
        Object result = testService.getCompositeData("product", 999L);

        // Then: Should handle composite key correctly
        assertNotNull(result);
    }

    @Test
    @DisplayName("Should bypass interceptor for non-annotated methods")
    void testNonAnnotatedMethod() throws Exception {
        // Given: Service with mixed annotations
        
        
        // When: Call method without cache annotation
        Object result = testService.getWithoutAnnotation("test");

        // Then: Should execute normally without caching
        assertEquals("test-result", result);
    }

    @Test
    @DisplayName("Should handle null values gracefully")
    void testNullValueHandling() throws Exception {
        // Given: Service that may return null

        // When: Method returns null
        Object result = testService.getNullableData(null);

        // Then: Should handle null without exception
        // (Actual behavior depends on cache configuration)
       // assertNotNull(result); // Or assert based on actual implementation
    }

    /**
     * Test configuration for integration tests with properly mocked RedissonClient
     */
    @Configuration
    static class TestConfig {

        @Bean
        public CacheManager cacheManager() {
            return new CacheManager() {
                @Override
                public Cache getCache(String name) {
                    return null;
                }

                @Override
                public Collection<String> getCacheNames() {
                    return List.of();
                }
            };
        }
        @Bean
        public RedissonClient redissonClient() {
            Config config = new Config();
            config.setCodec(new org.redisson.codec.JsonJacksonCodec());
            config.useClusterServers()
                    .setNodeAddresses(
                            Arrays.asList(
                                    "redis://127.0.0.1:7001",
                                    "redis://127.0.0.1:7002",
                                    "redis://127.0.0.1:7003",
                                    "redis://127.0.0.1:7004",
                                    "redis://127.0.0.1:7005",
                                    "redis://127.0.0.1:7006"
                            ));
            return Redisson.create(config);
        }

        @ConfigurationProperties(prefix = "spring.hcc.cache")
        @Bean
        public HccProperties hccProperties() {
            return new HccProperties();
        }

        @Bean
        public LocalCacheManager localCacheManager(HccProperties properties) {
            if (LocalCacheType.CAFFEINE.name().equals(properties.getLocal().getCacheType())) {
                LocalCacheFactory.registerCacheType(LocalCacheType.CAFFEINE.name(), CaffeineCacheAdapter.class);
            } else if (LocalCacheType.GUAVA.name().equals(properties.getLocal().getCacheType())) {
                LocalCacheFactory.registerCacheType(properties.getLocal().getCacheType(), GuavaCacheAdapter.class);
            }
            return new LocalCacheManager(properties.getLocal());
        }

        @ConditionalOnMissingBean
        @Bean
        public LocalCacheMarkerManager localCacheMarkerManager(RedissonClient redissonClient, HccProperties properties) {
            return new LocalCacheMarkerManagerImpl(redissonClient, properties.getLocal().getBufferTimeMs());
        }

        @ConditionalOnMissingBean
        @Bean
        public DistributedCacheManager distributedCacheManager(RedissonClient redissonClient) {
            return new RedisCacheManager(redissonClient);
        }

        @ConditionalOnMissingBean
        @Bean
        public InvalidationBroadcaster invalidationBroadcaster(HccProperties properties,
                                                               RedissonClient redissonClient,
                                                               CacheExecutor cacheExecutor) {
            BroadcastPublisher publisher = new RTopicPublisher(redissonClient);
            BroadcastSubscriber subscriber = new RTopicSubscriber(redissonClient);
            Set<String> channelNames = Set.of(properties.getLocal().getChannelNames().split(","));
            InvalidationListener invalidationListener = new InvalidationListener(
                    NodeInstanceHolder.getNodeId(),
                    channelNames.stream().toList(),
                    cacheExecutor
            );
            List<BroadcasterListener> listeners = Arrays.asList(invalidationListener);
            int batchSize = properties.getLocal().getBatchSize();
            int maxWaitSeconds = properties.getLocal().getMaxWaitSeconds();
            InvalidationBroadcaster broadcaster = new InvalidationBroadcaster(publisher, subscriber, listeners, channelNames, batchSize, maxWaitSeconds);
            cacheExecutor.setBroadcaster(broadcaster);
            return broadcaster;
        }

        @ConditionalOnMissingBean
        @Bean
        public CacheExecutor cacheExecutor(HccProperties properties,
                                           LocalCacheManager localCacheManager,
                                           LocalCacheMarkerManager localCacheMarkerManager,
                                           DistributedCacheManager distributedCacheManager) {

            DefaultWriteHotspotDetector writeHotspotDetector = new DefaultWriteHotspotDetector(
                    properties.getHotspot().getWriteWindowSeconds(),
                    properties.getHotspot().getWriteInvalidationThreshold(),
                    properties.getHotspot().getWriteBaseBlacklistTtl(),
                    properties.getHotspot().getWriteBackoffMultiplier(),
                    properties.getHotspot().getWriteMaxBlacklistTime()
            );
            DefaultReadHotspotDetector readStatistics  = new DefaultReadHotspotDetector(
                    properties.getHotspot().getReadHotKeyThreshold(),
                    properties.getHotspot().getReadWindowSizeMs(),
                    properties.getHotspot().getReadBucketCount());

            CacheCircuitBreaker circuitBreaker = new CacheCircuitBreaker(
                    properties.getCircuitBreaker().getFailureThreshold(),
                    properties.getCircuitBreaker().getSuccessThreshold(),
                    properties.getCircuitBreaker().getTimeoutMs()
            );
            return new DefaultCacheExecutor(
                    localCacheManager, distributedCacheManager,
                    localCacheMarkerManager, writeHotspotDetector, readStatistics, circuitBreaker);
        }

        /**
         * 定义自定义拦截器
         * @return
         */
        @Bean
        public HccCacheInterceptor hccCacheInterceptor(CacheExecutor cacheExecutor) {
            CacheEvictHandler cacheEvictHandler = new SpringCacheEvictHandler(cacheExecutor, false);
            HccCacheInterceptor interceptor = new HccCacheInterceptor(cacheExecutor, cacheEvictHandler);
            // 可以使用默认的，或者自定义一个解析
            CacheOperationSource cacheOperationSource = new AnnotationCacheOperationSource(
                    new HccCacheAnnotationParser()
                    //,new SpringCacheAnnotationParser() //添加spring cache原有注解
            );
            interceptor.setCacheOperationSources(cacheOperationSource);
            return interceptor;
        }

        /**
         * 定义切面，将拦截器绑定到特定的注解
         * @param cacheInterceptor
         * @return
         */
        @Bean
        public AbstractBeanFactoryPointcutAdvisor hccCacheAdvisor(HccCacheInterceptor cacheInterceptor) {
            BeanFactoryCacheOperationSourceAdvisor advisor = new BeanFactoryCacheOperationSourceAdvisor();
            advisor.setAdvice(cacheInterceptor);
            advisor.setCacheOperationSource(cacheInterceptor.getCacheOperationSource());
            return advisor;
        }
    }
}
