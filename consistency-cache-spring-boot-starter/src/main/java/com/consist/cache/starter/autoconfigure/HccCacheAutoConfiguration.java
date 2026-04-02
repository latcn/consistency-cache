package com.consist.cache.starter.autoconfigure;

import com.consist.cache.core.circuitbreaker.CacheCircuitBreaker;
import com.consist.cache.core.distributed.DistributedCacheManager;
import com.consist.cache.core.executor.CacheBloomFilter;
import com.consist.cache.core.executor.CacheEvictHandler;
import com.consist.cache.core.executor.CacheExecutor;
import com.consist.cache.core.executor.DefaultCacheExecutor;
import com.consist.cache.core.hotspot.reads.DefaultReadHotspotDetector;
import com.consist.cache.core.hotspot.reads.ReadHotspotDetector;
import com.consist.cache.core.hotspot.writes.DefaultWriteHotspotDetector;
import com.consist.cache.core.hotspot.writes.WriteHotspotDetector;
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
import com.consist.cache.spring.aspect.HccCacheAnnotationParser;
import com.consist.cache.spring.aspect.HccCacheInterceptor;
import com.consist.cache.spring.distributed.RedisCacheManager;
import com.consist.cache.spring.executor.EnhanceRCuckooFilter;
import com.consist.cache.spring.handler.SpringCacheEvictHandler;
import com.consist.cache.spring.local.LocalCacheMarkerManagerImpl;
import com.consist.cache.spring.local.adapter.CaffeineCacheAdapter;
import com.consist.cache.spring.local.adapter.GuavaCacheAdapter;
import com.consist.cache.spring.pubsub.InvalidationListener;
import com.consist.cache.spring.pubsub.RTopicPublisher;
import com.consist.cache.spring.pubsub.RTopicSubscriber;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.spring.starter.RedissonProperties;
import org.springframework.aop.support.AbstractBeanFactoryPointcutAdvisor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.interceptor.BeanFactoryCacheOperationSourceAdvisor;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.context.annotation.Bean;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Slf4j
@AutoConfiguration(after = {RedisAutoConfiguration.class})
@EnableConfigurationProperties({RedissonProperties.class, RedisProperties.class})
@ConditionalOnProperty(prefix = "spring.hcc.cache", name = "enabled", havingValue = "true")
public class HccCacheAutoConfiguration {

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
    public CacheBloomFilter cacheBloomFilter(RedissonClient redissonClient) {
        return new EnhanceRCuckooFilter(redissonClient);
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
    public WriteHotspotDetector writeHotspotDetector(HccProperties properties) {
        DefaultWriteHotspotDetector writeHotspotDetector = new DefaultWriteHotspotDetector(
                properties.getHotspot().getWriteWindowSeconds(),
                properties.getHotspot().getWriteInvalidationThreshold(),
                properties.getHotspot().getWriteBaseBlacklistTtl(),
                properties.getHotspot().getWriteBackoffMultiplier(),
                properties.getHotspot().getWriteMaxBlacklistTime()
        );
        return writeHotspotDetector;
    }

    @ConditionalOnMissingBean
    @Bean
    public ReadHotspotDetector readHotspotDetector(HccProperties properties) {
        DefaultReadHotspotDetector readHotspotDetector  = new DefaultReadHotspotDetector(
                properties.getHotspot().getReadHotKeyThreshold(),
                properties.getHotspot().getReadWindowSizeMs(),
                properties.getHotspot().getReadBucketCount());
        return readHotspotDetector;
    }

    @ConditionalOnMissingBean
    @Bean
    public CacheCircuitBreaker circuitBreaker(HccProperties properties) {
        CacheCircuitBreaker circuitBreaker = new CacheCircuitBreaker(
                properties.getCircuitBreaker().getFailureThreshold(),
                properties.getCircuitBreaker().getSuccessThreshold(),
                properties.getCircuitBreaker().getTimeoutMs()
        );
        return circuitBreaker;
    }

    @ConditionalOnMissingBean
    @Bean
    public CacheExecutor cacheExecutor(HccProperties properties,
                                       LocalCacheManager localCacheManager,
                                       LocalCacheMarkerManager localCacheMarkerManager,
                                       DistributedCacheManager distributedCacheManager,
                                       WriteHotspotDetector writeHotspotDetector,
                                       ReadHotspotDetector readHotspotDetector,
                                       CacheCircuitBreaker circuitBreaker,
                                       CacheBloomFilter cacheBloomFilter) {
        return new DefaultCacheExecutor(
                localCacheManager, distributedCacheManager,
                localCacheMarkerManager, writeHotspotDetector, readHotspotDetector, circuitBreaker, cacheBloomFilter);
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
