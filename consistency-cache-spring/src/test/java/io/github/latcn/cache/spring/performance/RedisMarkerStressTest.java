package io.github.latcn.cache.spring.performance;

import io.github.latcn.cache.spring.local.LocalCacheMarkerManagerImpl;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

@Slf4j
@DisplayName("Redis Marker Stress Tests")
class RedisMarkerStressTest {

	private LocalCacheMarkerManagerImpl markerManager;

	private RedissonClient redissonClient;

	@BeforeEach
	void setUp() {
		Config config = new Config();
		config.setCodec(new org.redisson.codec.JsonJacksonCodec());
		config.useClusterServers()
			.setNodeAddresses(java.util.Arrays.asList("redis://127.0.0.1:7001", "redis://127.0.0.1:7002",
					"redis://127.0.0.1:7003", "redis://127.0.0.1:7004", "redis://127.0.0.1:7005",
					"redis://127.0.0.1:7006"));
		redissonClient = Redisson.create(config);
		markerManager = new LocalCacheMarkerManagerImpl(redissonClient, 10000);
	}

	@DisplayName("Redis marker concurrent mark test")
	void testMarkerConcurrentMark() throws InterruptedException {
		int[] threadCounts = { 20, 50, 100, 200 };
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
							String cacheKey = "mark-test-" + threadId + "-" + j;
							long expireTime = System.currentTimeMillis() + 300000;
							markerManager.markLocalCacheUsage(cacheKey, expireTime);
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
			log.info("Mark Test | Threads: {} | Ops: {} | Duration: {}ms | QPS: {:.}", threadCount,
					totalOps.get(), duration, qps);
		}
	}

	@DisplayName("Redis marker concurrent remove test")
	void testMarkerConcurrentRemove() throws InterruptedException {
		int warmupCount = 50000;
		for (int i = 0; i < warmupCount; i++) {
			markerManager.markLocalCacheUsage("remove-warmup-" + i, System.currentTimeMillis() + 300000);
		}

		int[] threadCounts = { 20, 50, 100 };
		int operationsPerThread = 5000;

		for (int threadCount : threadCounts) {
			ExecutorService executor = Executors.newFixedThreadPool(threadCount);
			CountDownLatch latch = new CountDownLatch(threadCount);
			AtomicLong totalOps = new AtomicLong(0);

			long startTime = System.currentTimeMillis();

			for (int i = 0; i < threadCount; i++) {
				executor.submit(() -> {
					try {
						for (int j = 0; j < operationsPerThread; j++) {
							int keyIdx = ThreadLocalRandom.current().nextInt(warmupCount);
							markerManager.removeLocalCacheUsage("remove-warmup-" + keyIdx);
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
			log.info("Remove Test | Threads: {} | Ops: {} | Duration: {}ms | QPS: {:.}", threadCount,
					totalOps.get(), duration, qps);
		}
	}

	@DisplayName("Redis marker multi-node simulation test")
	void testMarkerMultiNodeSimulation() throws InterruptedException {
		int nodeCount = 10;
		int operationsPerNode = 1000;
		int keyCount = 100;

		ExecutorService executor = Executors.newFixedThreadPool(nodeCount);
		CountDownLatch latch = new CountDownLatch(nodeCount);
		AtomicLong totalOps = new AtomicLong(0);

		long startTime = System.currentTimeMillis();

		for (int nodeId = 0; nodeId < nodeCount; nodeId++) {
			final int finalNodeId = nodeId;
			executor.submit(() -> {
				try {
					LocalCacheMarkerManagerImpl nodeMarker = new LocalCacheMarkerManagerImpl(redissonClient, 10000);
					for (int j = 0; j < operationsPerNode; j++) {
						int keyIdx = j % keyCount;
						String cacheKey = "multi-node-key-" + keyIdx;
						long expireTime = System.currentTimeMillis() + 300000;

						if (j % 2 == 0) {
							nodeMarker.markLocalCacheUsage(cacheKey, expireTime);
						}
						else {
							nodeMarker.removeLocalCacheUsage(cacheKey);
						}
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
		log.info("Multi-Node Test | Nodes: {} | Ops: {} | Duration: {}ms | QPS: {:.}", nodeCount,
				totalOps.get(), duration, qps);
	}

	@DisplayName("Redis marker cleanup performance test")
	void testMarkerCleanupPerformance() throws InterruptedException {
		int warmupCount = 100000;
		for (int i = 0; i < warmupCount; i++) {
			long expireTime = System.currentTimeMillis() - 1000;
			markerManager.markLocalCacheUsage("cleanup-warmup-" + i, expireTime);
		}

		int[] batchSizes = { 100, 500, 1000, 5000 };

		for (int batchSize : batchSizes) {
			long startTime = System.currentTimeMillis();
			markerManager.doCleanUp();
			long duration = System.currentTimeMillis() - startTime;
			log.info("Cleanup Test | WarmupEntries: {} | Duration: {}ms", warmupCount, duration);
		}
	}

	@DisplayName("Redis marker get active nodes performance")
	void testGetActiveNodesPerformance() throws InterruptedException {
		int keyCount = 1000;
		for (int i = 0; i < keyCount; i++) {
			markerManager.markLocalCacheUsage("active-node-key-" + i, System.currentTimeMillis() + 300000);
		}

		int[] threadCounts = { 10, 50, 100 };
		int operationsPerThread = 5000;

		for (int threadCount : threadCounts) {
			ExecutorService executor = Executors.newFixedThreadPool(threadCount);
			CountDownLatch latch = new CountDownLatch(threadCount);
			AtomicLong totalOps = new AtomicLong(0);

			long startTime = System.currentTimeMillis();

			for (int i = 0; i < threadCount; i++) {
				executor.submit(() -> {
					try {
						for (int j = 0; j < operationsPerThread; j++) {
							int keyIdx = ThreadLocalRandom.current().nextInt(keyCount);
							markerManager.getActiveNodes("active-node-key-" + keyIdx);
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
			log.info("GetActiveNodes Test | Threads: {} | Ops: {} | Duration: {}ms | QPS: {:.}", threadCount,
					totalOps.get(), duration, qps);
		}
	}

}