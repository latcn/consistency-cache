package io.github.latcn.cache.spring.performance;

import io.github.latcn.cache.core.circuitbreaker.CacheCircuitBreaker;
import io.github.latcn.cache.core.executor.CacheExecutor;
import io.github.latcn.cache.core.executor.DefaultCacheExecutor;
import io.github.latcn.cache.core.hotspot.reads.DefaultReadHotspotDetector;
import io.github.latcn.cache.core.hotspot.writes.DefaultWriteHotspotDetector;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

@DisplayName("Progressive Load Stress Tests")
class ProgressiveLoadTest {

	private CacheExecutor cacheExecutor;

	private RedissonClient redissonClient;

	private LocalCacheManager localCacheManager;

	private InvalidationBroadcaster broadcaster;

	private static final int WARMUP_SIZE = 50000;

	private static final int STABILITY_DURATION_SECONDS = 60;

	@BeforeEach
	void setUp() {
		LocalCacheFactory.registerCacheType("CAFFEINE", CaffeineCacheAdapter.class);

		Config config = new Config();
		config.setCodec(new org.redisson.codec.JsonJacksonCodec());
		config.useClusterServers()
			.setNodeAddresses(
					Arrays.asList("redis://127.0.0.1:7001", "redis://127.0.0.1:7002", "redis://127.0.0.1:7003",
							"redis://127.0.0.1:7004", "redis://127.0.0.1:7005", "redis://127.0.0.1:7006"));
		redissonClient = Redisson.create(config);

		HccProperties properties = new HccProperties();
		properties.getLocal().setMaximumSize(100000);
		properties.getLocal().setExpireAfterWrite(300);
		properties.getLocal().setChannelNames("hcc-cache-channel");

		localCacheManager = new LocalCacheManager(properties.getLocal());
		LocalCacheMarkerManager markerManager = new LocalCacheMarkerManagerImpl(redissonClient, 10000);
		RedisCacheManager distributedCacheManager = new RedisCacheManager(redissonClient, 100, 10);
		EnhanceRCuckooFilter bloomFilter = new EnhanceRCuckooFilter(redissonClient);

		DefaultWriteHotspotDetector writeHotspotDetector = new DefaultWriteHotspotDetector(60, 1000, 60000, 2.0, 300000,
				1000);
		DefaultReadHotspotDetector readHotspotDetector = new DefaultReadHotspotDetector(100.0, 1000, 10);

		CacheCircuitBreaker circuitBreaker = new CacheCircuitBreaker(50, 10, 30000,
				Set.of(org.redisson.client.RedisConnectionException.class));

		cacheExecutor = new DefaultCacheExecutor(localCacheManager, distributedCacheManager, markerManager,
				writeHotspotDetector, readHotspotDetector, circuitBreaker, bloomFilter);

		BroadcastPublisher publisher = new RTopicPublisher(redissonClient);
		BroadcastSubscriber subscriber = new RTopicSubscriber(redissonClient);
		Set<String> channelNames = Set.of(properties.getLocal().getChannelNames().split(","));
		InvalidationListener listener = new InvalidationListener(NodeInstanceHolder.getNodeId(),
				new ArrayList<>(channelNames), cacheExecutor);

		broadcaster = new InvalidationBroadcaster(publisher, subscriber, Arrays.asList(listener), channelNames, 100,
				10);
		cacheExecutor.setBroadcaster(broadcaster);
		broadcaster.preDestroy();

		warmupCache();
	}

	private void warmupCache() {
		for (int i = 0; i < WARMUP_SIZE; i++) {
			String key = "progressive-key-" + i;
			CacheKey cacheKey = CacheKey.builder()
				.key(key)
				.consistencyLevel(ConsistencyLevel.HIGH)
				.cacheLevel(CacheLevel.LOCAL_CACHE)
				.build();
			CacheValue<String> value = CacheValue.<String>builder()
				.value("value-" + i)
				.expireTime(System.currentTimeMillis() + 300000)
				.build();
			localCacheManager.put(cacheKey, value);
		}
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

	// @Test
	@DisplayName("Progressive load test - find breaking point")
	void testProgressiveLoadFindBreakingPoint() throws InterruptedException {
		System.out.println("=== Progressive Load Test - Finding Breaking Point ===");
		System.out.println("Warmup entries: " + WARMUP_SIZE);
		System.out.println("Stability duration per level: " + STABILITY_DURATION_SECONDS + "s");
		System.out.println("==================================================");

		int[] threadLevels = { 10, 50, 100, 200, 300, 500, 800, 1000 };
		int operationsPerThread = 5000;
		double maxErrorRate = 1.0;
		boolean breakingPointReached = false;

		for (int threadCount : threadLevels) {
			if (breakingPointReached) {
				System.out.println("Breaking point already reached. Stopping test.");
				break;
			}

			System.out.printf("%n--- Testing with %d threads ---%n", threadCount);

			ExecutorService executor = Executors.newFixedThreadPool(threadCount);
			CountDownLatch startLatch = new CountDownLatch(1);
			CountDownLatch doneLatch = new CountDownLatch(threadCount);
			AtomicLong totalOps = new AtomicLong(0);
			AtomicLong errors = new AtomicLong(0);
			List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

			for (int i = 0; i < threadCount; i++) {
				executor.submit(() -> {
					try {
						startLatch.await();
						long ops = 0;
						long startTime = System.currentTimeMillis();

						while (System.currentTimeMillis() - startTime < STABILITY_DURATION_SECONDS * 1000) {
							try {
								int keyIdx = ThreadLocalRandom.current().nextInt(WARMUP_SIZE);
								String key = "progressive-key-" + keyIdx;
								CacheKey cacheKey = CacheKey.builder()
									.key(key)
									.consistencyLevel(ConsistencyLevel.HIGH)
									.cacheLevel(CacheLevel.LOCAL_CACHE)
									.build();

								long opStart = System.currentTimeMillis();
								CacheValue<?> result = cacheExecutor.get(cacheKey, (k) -> "fallback");
								long opEnd = System.currentTimeMillis();
								latencies.add(opEnd - opStart);

								if (result == null) {
									errors.incrementAndGet();
								}
								ops++;
							}
							catch (Exception e) {
								errors.incrementAndGet();
							}
						}
						totalOps.addAndGet(ops);
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					finally {
						doneLatch.countDown();
					}
				});
			}

			long testStartTime = System.currentTimeMillis();
			startLatch.countDown();
			doneLatch.await(STABILITY_DURATION_SECONDS + 30, TimeUnit.SECONDS);
			long testDuration = System.currentTimeMillis() - testStartTime;
			executor.shutdown();

			double qps = (totalOps.get() * 1000.0) / testDuration;
			double errorRate = (errors.get() * 100.0) / (totalOps.get() + errors.get());

			long p95 = calculatePercentile(latencies, 95);
			long p99 = calculatePercentile(latencies, 99);

			System.out.printf("Results: QPS=%.2f | Errors=%d (%.2f%%) | P95=%dms | P99=%dms%n", qps, errors.get(),
					errorRate, p95, p99);

			if (errorRate > maxErrorRate) {
				System.out.printf("ALERT: Error rate (%.2f%%) exceeded threshold (%.2f%%)%n", errorRate, maxErrorRate);
				System.out.printf("Breaking point reached at %d threads%n", threadCount);
				breakingPointReached = true;
			}

			Thread.sleep(5000);
		}
	}

	// @Test
	@DisplayName("Progressive load test - read-write mix with increasing load")
	void testProgressiveReadWriteMix() throws InterruptedException {
		System.out.println("=== Progressive Read-Write Mix Test ===");
		System.out.println("Read:Write ratio: 90:10");
		System.out.println("====================================");

		int[] threadLevels = { 10, 50, 100, 200, 300 };
		int readRatio = 90;

		for (int threadCount : threadLevels) {
			System.out.printf("%n--- Testing with %d threads ---%n", threadCount);

			ExecutorService executor = Executors.newFixedThreadPool(threadCount);
			CountDownLatch startLatch = new CountDownLatch(1);
			CountDownLatch doneLatch = new CountDownLatch(threadCount);
			AtomicLong readOps = new AtomicLong(0);
			AtomicLong writeOps = new AtomicLong(0);
			AtomicLong errors = new AtomicLong(0);

			for (int i = 0; i < threadCount; i++) {
				executor.submit(() -> {
					try {
						startLatch.await();
						long startTime = System.currentTimeMillis();

						while (System.currentTimeMillis() - startTime < STABILITY_DURATION_SECONDS * 1000) {
							try {
								if (ThreadLocalRandom.current().nextInt(100) < readRatio) {
									int keyIdx = ThreadLocalRandom.current().nextInt(WARMUP_SIZE);
									String key = "progressive-key-" + keyIdx;
									CacheKey cacheKey = CacheKey.builder()
										.key(key)
										.consistencyLevel(ConsistencyLevel.HIGH)
										.cacheLevel(CacheLevel.LOCAL_CACHE)
										.build();
									cacheExecutor.get(cacheKey, (k) -> "fallback");
									readOps.incrementAndGet();
								}
								else {
									String newKey = "progressive-write-" + System.nanoTime();
									CacheKey cacheKey = CacheKey.builder()
										.key(newKey)
										.consistencyLevel(ConsistencyLevel.HIGH)
										.cacheLevel(CacheLevel.LOCAL_CACHE)
										.build();
									CacheValue<String> value = CacheValue.<String>builder()
										.value("value-" + newKey)
										.expireTime(System.currentTimeMillis() + 300000)
										.build();
									cacheExecutor.get(cacheKey, k -> value);
									writeOps.incrementAndGet();
								}
							}
							catch (Exception e) {
								errors.incrementAndGet();
							}
						}
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					finally {
						doneLatch.countDown();
					}
				});
			}

			long testStartTime = System.currentTimeMillis();
			startLatch.countDown();
			doneLatch.await(STABILITY_DURATION_SECONDS + 30, TimeUnit.SECONDS);
			long testDuration = System.currentTimeMillis() - testStartTime;
			executor.shutdown();

			long totalOps = readOps.get() + writeOps.get();
			double qps = (totalOps * 1000.0) / testDuration;
			double errorRate = (errors.get() * 100.0) / (totalOps + errors.get());

			System.out.printf("Results: QPS=%.2f | Reads=%d | Writes=%d | Errors=%d (%.2f%%)%n", qps, readOps.get(),
					writeOps.get(), errors.get(), errorRate);

			Thread.sleep(3000);
		}
	}

	// @Test
	@DisplayName("Progressive load test - resource monitoring")
	void testProgressiveResourceMonitoring() throws InterruptedException {
		System.out.println("=== Progressive Resource Monitoring ===");
		System.out.println("Monitoring CPU and Memory usage");
		System.out.println("=================================");

		int[] threadLevels = { 50, 100, 200 };

		for (int threadCount : threadLevels) {
			System.out.printf("%n--- Testing with %d threads ---%n", threadCount);

			ExecutorService executor = Executors.newFixedThreadPool(threadCount);
			CountDownLatch startLatch = new CountDownLatch(1);
			CountDownLatch doneLatch = new CountDownLatch(threadCount);
			AtomicLong totalOps = new AtomicLong(0);

			for (int i = 0; i < threadCount; i++) {
				executor.submit(() -> {
					try {
						startLatch.await();
						long startTime = System.currentTimeMillis();

						while (System.currentTimeMillis() - startTime < STABILITY_DURATION_SECONDS * 1000) {
							int keyIdx = ThreadLocalRandom.current().nextInt(WARMUP_SIZE);
							String key = "progressive-key-" + keyIdx;
							CacheKey cacheKey = CacheKey.builder()
								.key(key)
								.consistencyLevel(ConsistencyLevel.HIGH)
								.cacheLevel(CacheLevel.LOCAL_CACHE)
								.build();
							cacheExecutor.get(cacheKey, (k) -> "fallback");
							totalOps.incrementAndGet();
						}
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					finally {
						doneLatch.countDown();
					}
				});
			}

			ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
			List<Double> cpuUsages = new ArrayList<>();
			List<Long> memoryUsages = new ArrayList<>();

			monitor.scheduleAtFixedRate(() -> {
				double cpuUsage = getProcessCpuUsage();
				long memoryUsage = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
				cpuUsages.add(cpuUsage);
				memoryUsages.add(memoryUsage);
			}, 0, 1000, TimeUnit.MILLISECONDS);

			long testStartTime = System.currentTimeMillis();
			startLatch.countDown();
			doneLatch.await(STABILITY_DURATION_SECONDS + 30, TimeUnit.SECONDS);
			long testDuration = System.currentTimeMillis() - testStartTime;

			monitor.shutdown();
			monitor.awaitTermination(2, TimeUnit.SECONDS);
			executor.shutdown();

			double avgCpu = cpuUsages.stream().mapToDouble(Double::doubleValue).average().orElse(0);
			double maxCpu = cpuUsages.stream().mapToDouble(Double::doubleValue).max().orElse(0);
			long avgMemory = (long) memoryUsages.stream().mapToLong(Long::longValue).average().orElse(0);

			double qps = (totalOps.get() * 1000.0) / testDuration;

			System.out.printf("Results: QPS=%.2f | Avg CPU=%.1f%% | Max CPU=%.1f%% | Avg Memory=%.2f MB%n", qps, avgCpu,
					maxCpu, avgMemory / (1024.0 * 1024.0));

			Thread.sleep(3000);
		}
	}

	private long calculatePercentile(List<Long> latencies, int percentile) {
		if (latencies.isEmpty())
			return 0;
		Collections.sort(latencies);
		int index = (int) Math.ceil(percentile / 100.0 * latencies.size()) - 1;
		return latencies.get(Math.max(0, index));
	}

	private double getProcessCpuUsage() {
		try {
			com.sun.management.OperatingSystemMXBean osBean = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory
				.getOperatingSystemMXBean();
			return osBean.getProcessCpuLoad() * 100;
		}
		catch (Exception e) {
			return 0;
		}
	}

}
