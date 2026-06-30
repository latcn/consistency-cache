package io.github.latcn.cache.spring.performance;

import io.github.latcn.cache.core.circuitbreaker.CacheCircuitBreaker;
import io.github.latcn.cache.core.executor.CacheExecutor;
import io.github.latcn.cache.core.executor.DefaultCacheExecutor;
import io.github.latcn.cache.core.hotspot.DefaultHotspotDetector;
import io.github.latcn.cache.core.local.LocalCacheFactory;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.github.latcn.cache.core.local.LocalCacheMarkerManager;
import io.github.latcn.cache.core.manager.SingleFlightExecutor;
import io.github.latcn.cache.core.manager.SingleFlightResult;
import io.github.latcn.cache.core.model.*;
import io.github.latcn.cache.core.pubsub.BroadcastPublisher;
import io.github.latcn.cache.core.pubsub.BroadcastSubscriber;
import io.github.latcn.cache.core.pubsub.InvalidationBroadcaster;
import io.github.latcn.cache.spring.distributed.RedisCacheManager;
import io.github.latcn.cache.spring.executor.EnhanceRCuckooFilter;
import io.github.latcn.cache.spring.local.LocalCacheMarkerManagerImpl;
import io.github.latcn.cache.spring.local.adapter.CaffeineCacheAdapter;
import io.github.latcn.cache.spring.pubsub.InvalidationListener;
import io.github.latcn.cache.spring.pubsub.RTopicPublisher;
import io.github.latcn.cache.spring.pubsub.RTopicSubscriber;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

@Slf4j
@DisplayName("Edge Case Stress Tests")
class EdgeCaseStressTest {

	private CacheExecutor cacheExecutor;

	private RedissonClient redissonClient;

	private LocalCacheManager localCacheManager;

	private InvalidationBroadcaster broadcaster;

	@BeforeEach
	void setUp() {
		LocalCacheFactory.registerCacheType("CAFFEINE", CaffeineCacheAdapter.class.getName());

		Config config = new Config();
		config.setCodec(new org.redisson.codec.JsonJacksonCodec());
		config.useClusterServers()
			.setNodeAddresses(
					Arrays.asList("redis://127.0.0.1:7001", "redis://127.0.0.1:7002", "redis://127.0.0.1:7003",
							"redis://127.0.0.1:7004", "redis://127.0.0.1:7005", "redis://127.0.0.1:7006"));
		redissonClient = Redisson.create(config);

		HccProperties properties = new HccProperties();
		properties.getLocal().setMaximumSize(100000);
		properties.getCacheEvict().setChannelNames("hcc-cache-channel");

		localCacheManager = new LocalCacheManager(properties.getLocal());
		LocalCacheMarkerManager markerManager = new LocalCacheMarkerManagerImpl(redissonClient, 1, 1000, 100);
		RedisCacheManager distributedCacheManager = new RedisCacheManager(redissonClient, 1000, 100, 10);
		EnhanceRCuckooFilter bloomFilter = new EnhanceRCuckooFilter(redissonClient);

		DefaultHotspotDetector writeHotspotDetector = new DefaultHotspotDetector(1000, 60000);
		DefaultHotspotDetector readHotspotDetector = new DefaultHotspotDetector(100, 20000);

		CacheCircuitBreaker circuitBreaker = new CacheCircuitBreaker(0.5, 30000,
				Set.of(org.redisson.client.RedisConnectionException.class));

		cacheExecutor = new DefaultCacheExecutor(localCacheManager, distributedCacheManager, markerManager,
				writeHotspotDetector, readHotspotDetector, circuitBreaker, bloomFilter);

		BroadcastPublisher publisher = new RTopicPublisher(redissonClient);
		BroadcastSubscriber subscriber = new RTopicSubscriber(redissonClient);
		Set<String> channelNames = Set.of(properties.getCacheEvict().getChannelNames().split(","));
		InvalidationListener listener = new InvalidationListener(NodeInstanceHolder.getNodeId(),
				new ArrayList<>(channelNames), cacheExecutor);

		broadcaster = new InvalidationBroadcaster(publisher, subscriber, Arrays.asList(listener), channelNames, 100,
				10);
		cacheExecutor.setBroadcaster(broadcaster);
	}

	@AfterEach
	void tearDown() {
		if (broadcaster != null) {
			broadcaster.preDestroy();
		}
		if (redissonClient != null) {
			redissonClient.shutdown();
		}
	}

	@DisplayName("Cache stampede test - thundering herd on expired key")
	void testCacheStampede() throws InterruptedException {
		log.info("=== Cache Stampede Test ===");

		String hotKey = "stampede-hot-key";
		CacheKey cacheKey = CacheKey.builder()
			.key(hotKey)
			.consistencyLevel(ConsistencyLevel.HIGH)
			.cacheLevel(CacheLevel.LOCAL_CACHE)
			.build();

		CacheValue<String> expiredValue = CacheValue.<String>builder()
			.value("expired")
			.expireTime(System.currentTimeMillis() - 1000)
			.build();
		localCacheManager.put(cacheKey, expiredValue);

		int threadCount = 100;
		AtomicInteger loadCount = new AtomicInteger(0);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);

		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				try {
					startLatch.await();
					CacheValue<?> result = cacheExecutor.get(cacheKey, (k) -> {
						int count = loadCount.incrementAndGet();
						try {
							Thread.sleep(50);
						}
						catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
						return "fresh-value-" + count;
					});
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				finally {
					doneLatch.countDown();
				}
			});
		}

		startLatch.countDown();
		doneLatch.await(30, TimeUnit.SECONDS);
		executor.shutdown();

		log.info("Results: Total threads: {} | Actual loads: {} | Reduction: {} ({:.1f}%)", threadCount,
				loadCount.get(), threadCount - loadCount.get(),
				((threadCount - loadCount.get()) * 100.0) / threadCount);
	}

	@DisplayName("SingleFlight thundering herd protection")
	void testSingleFlightProtection() throws InterruptedException {
		log.info("=== SingleFlight Thundering Herd Protection ===");

		SingleFlightExecutor singleFlight = new SingleFlightExecutor();
		String sharedKey = "singleflight-shared-key";
		AtomicInteger actualExecutions = new AtomicInteger(0);

		int threadCount = 200;
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);

		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				try {
					startLatch.await();
					SingleFlightResult<String> result = singleFlight.executeWithResult(sharedKey, k -> {
						actualExecutions.incrementAndGet();
						try {
							Thread.sleep(20);
						}
						catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
						return "result";
					});
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				finally {
					doneLatch.countDown();
				}
			});
		}

		startLatch.countDown();
		doneLatch.await(30, TimeUnit.SECONDS);
		executor.shutdown();

		log.info("Results: Total requests: {} | Actual executions: {} | Reduction: {} ({:.1f}%)", threadCount,
				actualExecutions.get(), threadCount - actualExecutions.get(),
				((threadCount - actualExecutions.get()) * 100.0) / threadCount);
	}

	@DisplayName("Hotspot detection under extreme data skew")
	void testHotspotDetectionWithDataSkew() throws InterruptedException {
		log.info("=== Hotspot Detection with Data Skew ===");

		DefaultHotspotDetector hotspotDetector = new DefaultHotspotDetector(100, 10000);
		int totalRequests = 100000;
		int hotKeyCount = 10;
		int coldKeyCount = 990;

		CountDownLatch latch = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(50);

		for (int i = 0; i < 50; i++) {
			executor.submit(() -> {
				try {
					latch.await();
					for (int j = 0; j < totalRequests / 50; j++) {
						double rand = ThreadLocalRandom.current().nextDouble();
						String key;
						if (rand < 0.8) {
							key = "hot-key-" + ThreadLocalRandom.current().nextInt(hotKeyCount);
						}
						else {
							key = "cold-key-" + ThreadLocalRandom.current().nextInt(coldKeyCount);
						}
						hotspotDetector.record(key);
					}
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
		}

		latch.countDown();
		executor.shutdown();
		executor.awaitTermination(60, TimeUnit.SECONDS);

		int detectedHotKeys = 0;
		int falsePositives = 0;

		for (int i = 0; i < hotKeyCount; i++) {
			if (hotspotDetector.isHotKey("hot-key-" + i)) {
				detectedHotKeys++;
			}
		}

		for (int i = 0; i < coldKeyCount; i++) {
			if (hotspotDetector.isHotKey("cold-key-" + i)) {
				falsePositives++;
			}
		}

		log.info("Results: Hot keys detected: {}/{} | False positives: {}/{}", detectedHotKeys, hotKeyCount,
				falsePositives, coldKeyCount);
	}

	@DisplayName("Memory pressure test with large cache size")
	void testMemoryPressure() throws InterruptedException {
		log.info("=== Memory Pressure Test ===");

		int entryCount = 200000;
		int threadCount = 50;
		int opsPerThread = entryCount / threadCount;

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch latch = new CountDownLatch(threadCount);

		long startMemory = Runtime.getRuntime().freeMemory();
		long startTime = System.currentTimeMillis();

		for (int i = 0; i < threadCount; i++) {
			final int threadId = i;
			executor.submit(() -> {
				try {
					for (int j = 0; j < opsPerThread; j++) {
						String key = "memory-test-" + threadId + "-" + j;
						CacheKey cacheKey = CacheKey.builder()
							.key(key)
							.consistencyLevel(ConsistencyLevel.HIGH)
							.cacheLevel(CacheLevel.LOCAL_CACHE)
							.build();
						CacheValue<String> value = CacheValue.<String>builder()
							.value("value-" + key)
							.expireTime(System.currentTimeMillis() + 300000)
							.build();
						localCacheManager.put(cacheKey, value);
					}
				}
				finally {
					latch.countDown();
				}
			});
		}

		latch.await(120, TimeUnit.SECONDS);
		long duration = System.currentTimeMillis() - startTime;
		executor.shutdown();

		System.gc();
		Thread.sleep(5000);
		long endMemory = Runtime.getRuntime().freeMemory();

		long memoryUsed = startMemory - endMemory;
		long heapSize = Runtime.getRuntime().totalMemory();

		log.info(
				"Results: Entries inserted: {} | Duration: {}ms | Memory used: {:.2} MB | Heap size: {:.2} MB | Memory per entry: {:.1} bytes",
				entryCount, duration, memoryUsed / (1024.0 * 1024.0), heapSize / (1024.0 * 1024.0),
				(double) memoryUsed / entryCount);
	}

	@DisplayName("Cache eviction under high write pressure")
	void testEvictionUnderPressure() throws InterruptedException {
		log.info("=== Cache Eviction Under High Pressure ===");

		HccProperties.LocalCacheProperties props = new HccProperties.LocalCacheProperties();
		props.setMaximumSize(5000);
		LocalCacheManager smallCache = new LocalCacheManager(props);

		int threadCount = 100;
		int totalInserts = 100000;
		int opsPerThread = totalInserts / threadCount;

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch latch = new CountDownLatch(threadCount);

		long startTime = System.currentTimeMillis();

		for (int i = 0; i < threadCount; i++) {
			final int threadId = i;
			executor.submit(() -> {
				try {
					for (int j = 0; j < opsPerThread; j++) {
						String key = "eviction-test-" + (threadId * opsPerThread + j);
						CacheKey cacheKey = CacheKey.builder()
							.key(key)
							.consistencyLevel(ConsistencyLevel.HIGH)
							.cacheLevel(CacheLevel.LOCAL_CACHE)
							.build();
						CacheValue<String> value = CacheValue.<String>builder()
							.value("value-" + key)
							.expireTime(System.currentTimeMillis() + 300000)
							.build();
						smallCache.put(cacheKey, value);
					}
				}
				finally {
					latch.countDown();
				}
			});
		}

		latch.await(120, TimeUnit.SECONDS);
		long duration = System.currentTimeMillis() - startTime;
		executor.shutdown();

		double qps = (totalInserts * 1000.0) / duration;
		log.info("Results: Total inserts: {} | Duration: {}ms | QPS: {:.} | Final cache size: {}", totalInserts,
				duration, qps, smallCache.getSize());
	}

	@DisplayName("Concurrent cache operations with mixed consistency levels")
	void testMixedConsistencyLevels() throws InterruptedException {
		log.info("=== Mixed Consistency Levels Test ===");

		int threadCount = 100;
		int operationsPerThread = 5000;

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch latch = new CountDownLatch(threadCount);
		AtomicLong highOps = new AtomicLong(0);
		AtomicLong lowOps = new AtomicLong(0);

		long startTime = System.currentTimeMillis();

		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				try {
					for (int j = 0; j < operationsPerThread; j++) {
						int rand = ThreadLocalRandom.current().nextInt(100);
						ConsistencyLevel level;
						if (rand < 60) {
							level = ConsistencyLevel.HIGH;
							highOps.incrementAndGet();
						}
						else {
							level = ConsistencyLevel.AVAILABLE;
							lowOps.incrementAndGet();
						}

						String key = "mixed-consistency-" + ThreadLocalRandom.current().nextInt(10000);
						CacheKey cacheKey = CacheKey.builder()
							.key(key)
							.consistencyLevel(level)
							.cacheLevel(CacheLevel.LOCAL_CACHE)
							.build();

						cacheExecutor.get(cacheKey,
								(k) -> CacheValue.<String>builder()
									.value("value-" + key)
									.expireTime(System.currentTimeMillis() + 300000)
									.build());
					}
				}
				finally {
					latch.countDown();
				}
			});
		}

		latch.await(120, TimeUnit.SECONDS);
		long duration = System.currentTimeMillis() - startTime;
		executor.shutdown();

		long totalOps = highOps.get() + lowOps.get();
		double qps = (totalOps * 1000.0) / duration;

		log.info("Results: Total ops: {} | QPS: {:.} | Duration: {}ms", totalOps, qps, duration);
		log.info("Consistency distribution: HIGH={} ({:.1f}%) | LOW={} ({:.1f}%)", highOps.get(),
				(highOps.get() * 100.0) / totalOps, lowOps.get(), (lowOps.get() * 100.0) / totalOps);
	}

}