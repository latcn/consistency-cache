package io.github.latcn.cache.spring.config;

import io.github.latcn.cache.core.circuitbreaker.CacheCircuitBreaker;
import io.github.latcn.cache.core.distributed.DistributedCacheManager;
import io.github.latcn.cache.core.executor.CacheBloomFilter;
import io.github.latcn.cache.core.executor.CacheEvictHandler;
import io.github.latcn.cache.core.executor.CacheExecutor;
import io.github.latcn.cache.core.executor.DefaultCacheExecutor;
import io.github.latcn.cache.core.hotspot.reads.DefaultReadHotspotDetector;
import io.github.latcn.cache.core.hotspot.writes.DefaultWriteHotspotDetector;
import io.github.latcn.cache.core.local.LocalCacheFactory;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.github.latcn.cache.core.local.LocalCacheMarkerManager;
import io.github.latcn.cache.core.model.HccProperties;
import io.github.latcn.cache.core.model.LocalCacheType;
import io.github.latcn.cache.core.model.NodeInstanceHolder;
import io.github.latcn.cache.core.pubsub.BroadcastPublisher;
import io.github.latcn.cache.core.pubsub.BroadcastSubscriber;
import io.github.latcn.cache.core.pubsub.BroadcasterListener;
import io.github.latcn.cache.core.pubsub.InvalidationBroadcaster;
import io.github.latcn.cache.spring.aspect.HccCacheAnnotationParser;
import io.github.latcn.cache.spring.aspect.HccCacheInterceptor;
import io.github.latcn.cache.spring.distributed.RedisCacheManager;
import io.github.latcn.cache.spring.executor.EnhanceRCuckooFilter;
import io.github.latcn.cache.spring.handler.SpringCacheEvictHandler;
import io.github.latcn.cache.spring.local.LocalCacheMarkerManagerImpl;
import io.github.latcn.cache.spring.local.adapter.CaffeineCacheAdapter;
import io.github.latcn.cache.spring.local.adapter.GuavaCacheAdapter;
import io.github.latcn.cache.spring.pubsub.InvalidationListener;
import io.github.latcn.cache.spring.pubsub.RTopicPublisher;
import io.github.latcn.cache.spring.pubsub.RTopicSubscriber;
import io.github.latcn.cache.spring.uid.SnowflakeGeneratorHolder;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.aop.support.AbstractBeanFactoryPointcutAdvisor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.interceptor.BeanFactoryCacheOperationSourceAdvisor;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TestConfig {

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
					Arrays.asList("redis://127.0.0.1:7001", "redis://127.0.0.1:7002", "redis://127.0.0.1:7003",
							"redis://127.0.0.1:7004", "redis://127.0.0.1:7005", "redis://127.0.0.1:7006"));
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
		}
		else if (LocalCacheType.GUAVA.name().equals(properties.getLocal().getCacheType())) {
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
		return new RedisCacheManager(redissonClient, 100, 10);
	}

	@ConditionalOnMissingBean
	@Bean
	public InvalidationBroadcaster invalidationBroadcaster(HccProperties properties, RedissonClient redissonClient,
			CacheExecutor cacheExecutor) {
		BroadcastPublisher publisher = new RTopicPublisher(redissonClient);
		BroadcastSubscriber subscriber = new RTopicSubscriber(redissonClient);
		Set<String> channelNames = Set.of(properties.getLocal().getChannelNames().split(","));
		InvalidationListener invalidationListener = new InvalidationListener(NodeInstanceHolder.getNodeId(),
				channelNames.stream().toList(), cacheExecutor);
		List<BroadcasterListener> listeners = Arrays.asList(invalidationListener);
		int batchSize = properties.getLocal().getBatchSize();
		int maxWaitSeconds = properties.getLocal().getMaxWaitSeconds();
		InvalidationBroadcaster broadcaster = new InvalidationBroadcaster(publisher, subscriber, listeners,
				channelNames, batchSize, maxWaitSeconds);
		cacheExecutor.setBroadcaster(broadcaster);
		return broadcaster;
	}

	@ConditionalOnMissingBean
	@Bean
	public CacheBloomFilter cacheBloomFilter(RedissonClient redissonClient) {
		return new EnhanceRCuckooFilter(redissonClient);
	}

	@ConditionalOnMissingBean
	@Bean
	public CacheExecutor cacheExecutor(HccProperties properties, LocalCacheManager localCacheManager,
			LocalCacheMarkerManager localCacheMarkerManager, DistributedCacheManager distributedCacheManager,
			CacheBloomFilter cacheBloomFilter) {

		DefaultWriteHotspotDetector writeHotspotDetector = new DefaultWriteHotspotDetector(
				properties.getHotspot().getWriteInvalidationThreshold(),
				properties.getHotspot().getWriteBaseBlacklistTtl(), properties.getHotspot().getWriteBackoffMultiplier(),
				properties.getHotspot().getWriteMaxBlacklistTime(), properties.getHotspot().getBlacklistMaxSize());
		DefaultReadHotspotDetector readStatistics = new DefaultReadHotspotDetector(properties.getHotspot().getReadHotKeyThreshold());

		CacheCircuitBreaker circuitBreaker = new CacheCircuitBreaker(
				properties.getCircuitBreaker().getFailureThreshold(),
				properties.getCircuitBreaker().getSuccessThreshold(), properties.getCircuitBreaker().getTimeoutMs(),
				Set.of(org.redisson.client.RedisConnectionException.class));
		return new DefaultCacheExecutor(localCacheManager, distributedCacheManager, localCacheMarkerManager,
				writeHotspotDetector, readStatistics, circuitBreaker, cacheBloomFilter);
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
		CacheOperationSource cacheOperationSource = new AnnotationCacheOperationSource(new HccCacheAnnotationParser()
		// ,new SpringCacheAnnotationParser() //添加spring cache原有注解
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

	@Bean
	public SnowflakeGeneratorHolder snowflakeGeneratorHolder(RedissonClient redissonClient) {
		return new SnowflakeGeneratorHolder(redissonClient);
	}

}
