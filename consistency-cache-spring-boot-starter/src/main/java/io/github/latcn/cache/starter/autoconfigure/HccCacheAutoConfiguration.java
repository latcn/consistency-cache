package io.github.latcn.cache.starter.autoconfigure;

import io.github.latcn.cache.core.circuitbreaker.CacheCircuitBreaker;
import io.github.latcn.cache.core.distributed.DistributedCacheManager;
import io.github.latcn.cache.core.executor.CacheBloomFilter;
import io.github.latcn.cache.core.executor.CacheExecutor;
import io.github.latcn.cache.core.executor.DefaultCacheExecutor;
import io.github.latcn.cache.core.hotspot.DefaultHotspotDetector;
import io.github.latcn.cache.core.hotspot.HotspotDetector;
import io.github.latcn.cache.core.local.LocalCacheFactory;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.github.latcn.cache.core.local.LocalCacheMarkerManager;
import io.github.latcn.cache.core.model.HccProperties;
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
import io.github.latcn.cache.spring.pubsub.InvalidationListener;
import io.github.latcn.cache.spring.pubsub.RTopicPublisher;
import io.github.latcn.cache.spring.pubsub.RTopicSubscriber;
import io.github.latcn.cache.spring.uid.SnowflakeGeneratorHolder;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.spring.starter.RedissonProperties;
import org.springframework.aop.support.AbstractBeanFactoryPointcutAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.interceptor.BeanFactoryCacheOperationSourceAdvisor;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

@Slf4j
@AutoConfiguration(after = { RedisAutoConfiguration.class, DataSourceAutoConfiguration.class })
@EnableConfigurationProperties({ RedissonProperties.class, RedisProperties.class })
@ConditionalOnProperty(prefix = "spring.hcc.cache", name = "enabled", havingValue = "true")
public class HccCacheAutoConfiguration {

	@ConfigurationProperties(prefix = "spring.hcc.cache")
	@Bean
	public HccProperties hccProperties() {
		return new HccProperties();
	}

	@Bean
	public LocalCacheManager localCacheManager(HccProperties properties) {
		LocalCacheFactory.registerCacheType(properties.getLocal().getCacheType(), properties.getLocal().getCacheClz());
		return new LocalCacheManager(properties.getLocal());
	}

	@ConditionalOnMissingBean
	@Bean
	public LocalCacheMarkerManager localCacheMarkerManager(RedissonClient redissonClient, HccProperties properties) {
		return new LocalCacheMarkerManagerImpl(redissonClient, properties.getLocal().getCleanPeriodSeconds(),
				properties.getLocal().getMarkerMaxSize(), properties.getLocal().getBufferTimeMs());
	}

	@ConditionalOnMissingBean
	@Bean
	public DistributedCacheManager distributedCacheManager(RedissonClient redissonClient, HccProperties properties) {
		return new RedisCacheManager(redissonClient, properties.getDistributed().getCacheOperationSize(),
				properties.getDistributed().getMaxBatchSize(), properties.getDistributed().getMaxWaitMs());
	}

	@ConditionalOnMissingBean
	@Bean
	public CacheBloomFilter cacheBloomFilter(RedissonClient redissonClient) {
		return new EnhanceRCuckooFilter(redissonClient);
	}

	@ConditionalOnMissingBean
	@Bean
	public InvalidationBroadcaster invalidationBroadcaster(HccProperties properties, RedissonClient redissonClient,
			CacheExecutor cacheExecutor) {
		BroadcastPublisher publisher = new RTopicPublisher(redissonClient);
		BroadcastSubscriber subscriber = new RTopicSubscriber(redissonClient);
		Set<String> channelNames = Set.of(properties.getCacheEvict().getChannelNames().split(","));
		InvalidationListener invalidationListener = new InvalidationListener(NodeInstanceHolder.getNodeId(),
				channelNames.stream().toList(), cacheExecutor);
		List<BroadcasterListener> listeners = Arrays.asList(invalidationListener);
		int batchSize = properties.getCacheEvict().getBatchSize();
		int maxWaitSeconds = properties.getCacheEvict().getMaxWaitSeconds();
		InvalidationBroadcaster broadcaster = new InvalidationBroadcaster(publisher, subscriber, listeners,
				channelNames, batchSize, maxWaitSeconds);
		cacheExecutor.setBroadcaster(broadcaster);
		return broadcaster;
	}

	@ConditionalOnMissingBean
	@Bean
	public HotspotDetector writeHotspotDetector(HccProperties properties) {
		DefaultHotspotDetector writeHotspotDetector = new DefaultHotspotDetector(properties.getWriteHot());
		return writeHotspotDetector;
	}

	@ConditionalOnMissingBean
	@Bean
	public HotspotDetector readHotspotDetector(HccProperties properties) {
		DefaultHotspotDetector readHotspotDetector = new DefaultHotspotDetector(properties.getReadHot());
		return readHotspotDetector;
	}

	@ConditionalOnMissingBean
	@Bean
	public CacheCircuitBreaker circuitBreaker(HccProperties properties) {
		CacheCircuitBreaker circuitBreaker = new CacheCircuitBreaker(properties.getCircuitBreaker().getFailRatio(),
				properties.getCircuitBreaker().getTimeoutMs(),
				Set.of(org.redisson.client.RedisConnectionException.class));
		return circuitBreaker;
	}

	@ConditionalOnMissingBean
	@Bean
	public CacheExecutor cacheExecutor(LocalCacheManager localCacheManager,
			LocalCacheMarkerManager localCacheMarkerManager, DistributedCacheManager distributedCacheManager,
			HotspotDetector writeHotspotDetector, HotspotDetector readHotspotDetector,
			CacheCircuitBreaker circuitBreaker, CacheBloomFilter cacheBloomFilter) {
		return new DefaultCacheExecutor(localCacheManager, distributedCacheManager, localCacheMarkerManager,
				writeHotspotDetector, readHotspotDetector, circuitBreaker, cacheBloomFilter);
	}

	/**
	 * 处理缓存失效
	 * @param cacheExecutor
	 * @param dataSource
	 * @param platformTransactionManager
	 * @return
	 */
	@Bean
	public SpringCacheEvictHandler cacheEvictHandler(CacheExecutor cacheExecutor, HccProperties properties,
			@Autowired(required = false) DataSource dataSource,
			@Autowired(required = false) PlatformTransactionManager platformTransactionManager) {
		return new SpringCacheEvictHandler(cacheExecutor, properties.getCacheEvict(), dataSource,
				platformTransactionManager);
	}

	/**
	 * 定义自定义拦截器
	 * @return
	 */
	@Bean
	public HccCacheInterceptor hccCacheInterceptor(CacheExecutor cacheExecutor,
			SpringCacheEvictHandler cacheEvictHandler) {
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
