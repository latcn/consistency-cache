package io.github.latcn.cache.spring.performance;

import io.github.latcn.cache.core.circuitbreaker.CacheCircuitBreaker;
import io.github.latcn.cache.core.executor.CacheExecutor;
import io.github.latcn.cache.core.executor.DefaultCacheExecutor;
import io.github.latcn.cache.core.hotspot.DefaultHotspotDetector;
import io.github.latcn.cache.core.local.LocalCacheFactory;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.github.latcn.cache.core.local.LocalCacheMarkerManager;
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
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

@Slf4j
@DisplayName("Full Stack Integration Stress Tests")
class FullStackIntegrationTest {

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
		LocalCacheMarkerManager markerManager = new LocalCacheMarkerManagerImpl(redissonClient, 10000);
		RedisCacheManager distributedCacheManager = new RedisCacheManager(redissonClient, 200, 10);
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

	@Test
	void testFullStackReadPerformance1() throws InterruptedException {
		ConcurrentLinkedQueue<CompletableFuture<CacheValue>> list = new ConcurrentLinkedQueue<>();
		CountDownLatch countDownLatch = new CountDownLatch(10);
		for (int i = 0; i < 10; i++) {
			final int keyIdx = i;
			new Thread(() -> {
				String key = "fullstack-read0-" + keyIdx;
				CacheKey cacheKey = CacheKey.builder()
					.key(key)
					.consistencyLevel(ConsistencyLevel.AVAILABLE)
					.cacheLevel(CacheLevel.ADAPTIVE_CACHE)
					.build();

				CompletableFuture<CacheValue> result = cacheExecutor.getAsync(cacheKey, (k) -> "fallback-" + keyIdx);
				list.add(result);
				result.whenComplete((r, e) -> countDownLatch.countDown());
			}).start();
		}
		countDownLatch.await(100, TimeUnit.SECONDS);
	}

	@DisplayName("Full stack cache read performance")
	void testFullStackReadPerformance() throws InterruptedException {
		int warmupSize = 50000;
		for (int i = 0; i < warmupSize; i++) {
			String key = "fullstack-read-" + i;
			CacheKey cacheKey = CacheKey.builder()
				.key(key)
				.consistencyLevel(ConsistencyLevel.AVAILABLE)
				.cacheLevel(CacheLevel.ADAPTIVE_CACHE)
				.build();
			CacheValue<String> value = CacheValue.<String>builder()
				.value("value-" + i)
				.expireTime(System.currentTimeMillis() + 300000)
				.build();
			localCacheManager.put(cacheKey, value);
		}

		int[] threadCounts = { 20, 50, 100, 200 };
		int operationsPerThread = 10000;

		for (int threadCount : threadCounts) {
			ExecutorService executor = Executors.newFixedThreadPool(threadCount);
			CountDownLatch latch = new CountDownLatch(threadCount);
			AtomicLong hits = new AtomicLong(0);
			AtomicLong misses = new AtomicLong(0);

			long startTime = System.currentTimeMillis();
			for (int i = 0; i < threadCount; i++) {
				executor.submit(() -> {
					try {
						CountDownLatch latch1 = new CountDownLatch(operationsPerThread);
						List<String> keyList = new ArrayList<>();
						List<CompletableFuture> futureList = new ArrayList<>();
						for (int j = 0; j < operationsPerThread; j++) {
							int keyIdx = ThreadLocalRandom.current().nextInt(warmupSize);
							String key = "fullstack-read-" + keyIdx;
							CacheKey cacheKey = CacheKey.builder()
								.key(key)
								.consistencyLevel(ConsistencyLevel.AVAILABLE)
								.cacheLevel(CacheLevel.ADAPTIVE_CACHE)
								.build();
							CompletableFuture<CacheValue> result = cacheExecutor.getAsync(cacheKey,
									(k) -> "fallback-" + keyIdx);
							result.whenComplete((r, e) -> {
								latch1.countDown();
								if (r != null)
									hits.incrementAndGet();
								else
									misses.incrementAndGet();
							});
							futureList.add(result);
							keyList.add(key);
						}
						latch1.await();
					}
					catch (InterruptedException e) {
						throw new RuntimeException(e);
					}
					finally {
						latch.countDown();
					}
				});
			}

			latch.await();
			long duration = System.currentTimeMillis() - startTime;
			executor.shutdown();

			long total = hits.get() + misses.get();
			double qps = (total * 1000.0) / duration;
			double hitRate = (hits.get() * 100.0) / total;
			log.info(
					"FullStack Read | Threads: {} | Hits: {} | Misses: {} | HitRate: {:.}%% | Duration: {}ms | QPS: {:.}",
					threadCount, hits.get(), misses.get(), hitRate, duration, qps);
		}
	}

	@DisplayName("Full stack cache write performance")
	void testFullStackWritePerformance() throws InterruptedException {
		int[] threadCounts = { 10, 50, 100 };
		int operationsPerThread = 5000;

		for (int threadCount : threadCounts) {
			ExecutorService executor = Executors.newFixedThreadPool(threadCount);
			CountDownLatch latch = new CountDownLatch(threadCount);
			AtomicLong totalOps = new AtomicLong(0);

			long startTime = System.currentTimeMillis();

			for (int i = 0; i < threadCount; i++) {
				final int threadId = i;
				executor.submit(() -> {
					try {
						for (int j = 0; j < operationsPerThread; j++) {
							String key = "fullstack-write-" + threadId + "-" + j;
							CacheKey cacheKey = CacheKey.builder()
								.key(key)
								.consistencyLevel(ConsistencyLevel.AVAILABLE)
								.cacheLevel(CacheLevel.ADAPTIVE_CACHE)
								.build();
							CacheValue<String> value = CacheValue.<String>builder()
								.value("value-" + key)
								.expireTime(System.currentTimeMillis() + 300000)
								.build();
							long t1 = System.currentTimeMillis();
							cacheExecutor.get(cacheKey, k -> value);
							long t2 = System.currentTimeMillis();
							totalOps.incrementAndGet();
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

			double qps = (totalOps.get() * 1000.0) / duration;
			log.info("FullStack Write | Threads: {} | Ops: {} | Duration: {}ms | QPS: {:.}", threadCount,
					totalOps.get(), duration, qps);
		}
	}

	@DisplayName("Full stack cache evict performance")
	void testFullStackEvictPerformance() throws InterruptedException {
		int warmupSize = 20000;
		for (int i = 0; i < warmupSize; i++) {
			String key = "fullstack-evict-" + i;
			CacheKey cacheKey = CacheKey.builder()
				.key(key)
				.consistencyLevel(ConsistencyLevel.AVAILABLE)
				.cacheLevel(CacheLevel.ADAPTIVE_CACHE)
				.build();
			CacheValue<String> value = CacheValue.<String>builder()
				.value("value-" + i)
				.expireTime(System.currentTimeMillis() + 300000)
				.build();
			cacheExecutor.get(cacheKey, k -> value);
		}

		int[] threadCounts = { 10, 50, 100 };
		int operationsPerThread = 2000;

		for (int threadCount : threadCounts) {
			ExecutorService executor = Executors.newFixedThreadPool(threadCount);
			CountDownLatch latch = new CountDownLatch(threadCount);
			AtomicLong totalOps = new AtomicLong(0);

			long startTime = System.currentTimeMillis();

			for (int i = 0; i < threadCount; i++) {
				executor.submit(() -> {
					try {
						for (int j = 0; j < operationsPerThread; j++) {
							int keyIdx = ThreadLocalRandom.current().nextInt(warmupSize);
							String key = "fullstack-evict-" + keyIdx;
							CacheKey cacheKey = CacheKey.builder()
								.key(key)
								.consistencyLevel(ConsistencyLevel.AVAILABLE)
								.cacheLevel(CacheLevel.ADAPTIVE_CACHE)
								.build();

							cacheExecutor.evict(cacheKey);
							totalOps.incrementAndGet();
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

			double qps = (totalOps.get() * 1000.0) / duration;
			log.info("FullStack Evict | Threads: {} | Ops: {} | Duration: {}ms | QPS: {:.}", threadCount,
					totalOps.get(), duration, qps);
		}
	}

	@DisplayName("Full stack read-write mix performance")
	void testFullStackReadWriteMix() throws InterruptedException {
		int warmupSize = 30000;
		for (int i = 0; i < warmupSize; i++) {
			String key = "mix-warmup-" + i;
			CacheKey cacheKey = CacheKey.builder()
				.key(key)
				.consistencyLevel(ConsistencyLevel.AVAILABLE)
				.cacheLevel(CacheLevel.ADAPTIVE_CACHE)
				.build();
			CacheValue<String> value = CacheValue.<String>builder()
				.value("value-" + i)
				.expireTime(System.currentTimeMillis() + 300000)
				.build();
			localCacheManager.put(cacheKey, value);
		}

		int[] threadCounts = { 50, 100 };
		int operationsPerThread = 10000;
		int[] readRatios = { 90, 70 };

		for (int readRatio : readRatios) {
			for (int threadCount : threadCounts) {
				ExecutorService executor = Executors.newFixedThreadPool(threadCount);
				CountDownLatch latch = new CountDownLatch(threadCount);
				AtomicLong readOps = new AtomicLong(0);
				AtomicLong writeOps = new AtomicLong(0);

				long startTime = System.currentTimeMillis();

				for (int i = 0; i < threadCount; i++) {
					executor.submit(() -> {
						try {
							for (int j = 0; j < operationsPerThread; j++) {
								if (ThreadLocalRandom.current().nextInt(100) < readRatio) {
									int keyIdx = ThreadLocalRandom.current().nextInt(warmupSize);
									String key = "mix-warmup-" + keyIdx;
									CacheKey cacheKey = CacheKey.builder()
										.key(key)
										.consistencyLevel(ConsistencyLevel.AVAILABLE)
										.cacheLevel(CacheLevel.ADAPTIVE_CACHE)
										.build();
									cacheExecutor.get(cacheKey, (k) -> "fallback");
									readOps.incrementAndGet();
								}
								else {
									String newKey = "mix-write-" + ThreadLocalRandom.current().nextInt(100000);
									CacheKey cacheKey = CacheKey.builder()
										.key(newKey)
										.consistencyLevel(ConsistencyLevel.AVAILABLE)
										.cacheLevel(CacheLevel.ADAPTIVE_CACHE)
										.build();
									CacheValue<String> value = CacheValue.<String>builder()
										.value("value-" + newKey)
										.expireTime(System.currentTimeMillis() + 300000)
										.build();
									cacheExecutor.get(cacheKey, k -> value);
									writeOps.incrementAndGet();
								}
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

				long total = readOps.get() + writeOps.get();
				double qps = (total * 1000.0) / duration;
				log.info(
						"FullStack Mix | ReadRatio: {}%% | Threads: {} | Reads: {} | Writes: {} | Duration: {}ms | QPS: {:.}",
						readRatio, threadCount, readOps.get(), writeOps.get(), duration, qps);
			}
		}
	}

}