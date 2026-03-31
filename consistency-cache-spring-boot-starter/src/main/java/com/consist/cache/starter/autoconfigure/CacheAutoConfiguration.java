package com.consist.cache.starter.autoconfigure;

import com.consist.cache.core.distributed.DistributedCacheManager;
import com.consist.cache.core.executor.CacheEvictHandler;
import com.consist.cache.core.executor.CacheExecutor;
import com.consist.cache.core.executor.DefaultCacheExecutor;
import com.consist.cache.core.hotspot.reads.ReadQpsStatistics;
import com.consist.cache.core.hotspot.writes.EnhancedWriteHotspotDetector;
import com.consist.cache.core.local.LocalCacheManager;
import com.consist.cache.core.local.LocalCacheMarkerManager;
import com.consist.cache.core.model.LocalCacheProperties;
import com.consist.cache.core.model.NodeInstanceHolder;
import com.consist.cache.core.pubsub.BroadcastPublisher;
import com.consist.cache.core.pubsub.BroadcastSubscriber;
import com.consist.cache.core.pubsub.BroadcasterListener;
import com.consist.cache.core.pubsub.InvalidationBroadcaster;
import com.consist.cache.spring.aspect.HccCacheAnnotationParser;
import com.consist.cache.spring.aspect.HccCacheInterceptor;
import com.consist.cache.spring.distributed.RedisCacheManager;
import com.consist.cache.spring.handler.SpringCacheEvictHandler;
import com.consist.cache.spring.local.LocalCacheMarkerManagerImpl;
import com.consist.cache.spring.pubsub.InvalidationListener;
import com.consist.cache.spring.pubsub.RTopicPublisher;
import com.consist.cache.spring.pubsub.RTopicSubscriber;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.starter.RedissonProperties;
import org.springframework.aop.support.AbstractBeanFactoryPointcutAdvisor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
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
public class CacheAutoConfiguration {

    @ConfigurationProperties(prefix = "spring.cache.local")
    @Bean
    public LocalCacheProperties localCacheProperties() {
        return new LocalCacheProperties();
    }

    @Bean
    public LocalCacheManager localCacheManager(LocalCacheProperties properties) {
        return new LocalCacheManager(properties);
    }

    //@Bean(destroyMethod = "shutdown")
    public RedissonClient redisson(RedissonProperties redissonProperties) {
        Config config = new Config();
        config.setCodec(new org.redisson.codec.JsonJacksonCodec());
        config.useClusterServers()
                .setNodeAddresses(
                        Arrays.asList(
                                "redis://127.0.0.1:7001",
                                "redis://127.0.0.1:7002",
                                "redis://127.0.0.1:7003"
                        ));
        return Redisson.create(config);
    }

    @Bean
    public LocalCacheMarkerManager localCacheMarkerManager(RedissonClient redissonClient, LocalCacheProperties properties) {
        return new LocalCacheMarkerManagerImpl(redissonClient, properties.getBufferTimeMs());
    }

    @Bean
    public DistributedCacheManager distributedCacheManager(RedissonClient redissonClient) {
        return new RedisCacheManager(redissonClient);
    }

    @Bean
    public InvalidationBroadcaster invalidationBroadcaster(LocalCacheProperties properties,
                                                           RedissonClient redissonClient,
                                                           CacheExecutor cacheExecutor) {
        BroadcastPublisher publisher = new RTopicPublisher(redissonClient);
        BroadcastSubscriber subscriber = new RTopicSubscriber(redissonClient);
        Set<String> channelNames = Set.of(properties.getChannelNames().split(","));
        InvalidationListener invalidationListener = new InvalidationListener(
                NodeInstanceHolder.getNodeId(),
                channelNames.stream().toList(),
                cacheExecutor
                );
        List<BroadcasterListener> listeners = Arrays.asList(invalidationListener);
        int batchSize = properties.getBatchSize();
        int maxWaitSeconds = properties.getMaxWaitSeconds();
        InvalidationBroadcaster broadcaster = new InvalidationBroadcaster(publisher, subscriber, listeners, channelNames, batchSize, maxWaitSeconds);
        cacheExecutor.setBroadcaster(broadcaster);
        return broadcaster;
    }

    @Bean
    public CacheExecutor cacheExecutor(LocalCacheProperties properties,
                                       LocalCacheManager localCacheManager,
                                       LocalCacheMarkerManager localCacheMarkerManager,
                                       DistributedCacheManager distributedCacheManager) {

        EnhancedWriteHotspotDetector writeHotspotDetector = new EnhancedWriteHotspotDetector(
                properties.getWriteWindowSeconds(),
                properties.getWriteInvalidationThreshold(),
                properties.getWriteBaseBlacklistTtl(),
                properties.getWriteBackoffMultiplier(),
                properties.getWriteMaxBlacklistTime()
        );
        ReadQpsStatistics readStatistics  = new ReadQpsStatistics(properties.getReadHotKeyThreshold(),
                properties.getReadWindowSizeMs(), properties.getReadBucketCount());

        return new DefaultCacheExecutor(
                localCacheManager, distributedCacheManager,
                localCacheMarkerManager, writeHotspotDetector, readStatistics);
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
