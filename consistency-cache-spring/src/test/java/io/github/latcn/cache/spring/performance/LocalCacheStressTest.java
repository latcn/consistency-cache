package io.github.latcn.cache.spring.performance;

import io.github.latcn.cache.core.local.LocalCacheFactory;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.github.latcn.cache.core.model.CacheKey;
import io.github.latcn.cache.core.model.CacheLevel;
import io.github.latcn.cache.core.model.ConsistencyLevel;
import io.github.latcn.cache.core.model.HccProperties;
import io.github.latcn.cache.spring.local.adapter.CaffeineCacheAdapter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

@Slf4j
@DisplayName("Local Cache Stress Tests")
class LocalCacheStressTest {

	private LocalCacheManager localCacheManager;

	@BeforeEach
	void setUp() {
		LocalCacheFactory.registerCacheType("CAFFEINE", CaffeineCacheAdapter.class);
		HccProperties.LocalCacheProperties properties = new HccProperties.LocalCacheProperties();
		properties.setMaximumSize(100000);
		properties.setExpireAfterWrite(300);
		localCacheManager = new LocalCacheManager(properties);
	}

	@DisplayName("Local cache write throughput test")
	void testLocalCacheWriteThroughput() throws InterruptedException {
		int[] threadCounts = { 10, 50, 100, 200 };
		int operationsPerThread = 10000;

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
							String key = "write-test-" + threadId + "-" + j;
							CacheKey cacheKey = CacheKey.builder()
								.key(key)
								.consistencyLevel(ConsistencyLevel.HIGH)
								.cacheLevel(CacheLevel.LOCAL_CACHE)
								.build();
							localCacheManager.put(cacheKey, createCacheValue(key));
							totalOps.incrementAndGet();
						}
					}
					finally {
						latch.countDown();
					}
				});
			}

			latch.await(60, TimeUnit.SECONDS);
			long duration = System.currentTimeMillis() - startTime;
			executor.shutdown();

			double qps = (totalOps.get() * 1000.0) / duration;
			log.info("Write Test | Threads: {} | Ops: {} | Duration: {}ms | QPS: {:.}", threadCount,
					totalOps.get(), duration, qps);
		}
	}

	@DisplayName("Local cache read throughput test")
	void testLocalCacheReadThroughput() throws InterruptedException {
		int warmupSize = 50000;
		for (int i = 0; i < warmupSize; i++) {
			CacheKey key = CacheKey.builder()
				.key("read-warmup-" + i)
				.consistencyLevel(ConsistencyLevel.HIGH)
				.cacheLevel(CacheLevel.LOCAL_CACHE)
				.build();
			localCacheManager.put(key, createCacheValue("read-warmup-" + i));
		}

		int[] threadCounts = { 10, 50, 100, 200, 500 };
		int operationsPerThread = 20000;

		for (int threadCount : threadCounts) {
			ExecutorService executor = Executors.newFixedThreadPool(threadCount);
			CountDownLatch latch = new CountDownLatch(threadCount);
			AtomicLong hits = new AtomicLong(0);
			AtomicLong misses = new AtomicLong(0);

			long startTime = System.currentTimeMillis();

			for (int i = 0; i < threadCount; i++) {
				executor.submit(() -> {
					try {
						for (int j = 0; j < operationsPerThread; j++) {
							int keyIdx = ThreadLocalRandom.current().nextInt(warmupSize);
							CacheKey key = CacheKey.builder()
								.key("read-warmup-" + keyIdx)
								.consistencyLevel(ConsistencyLevel.HIGH)
								.cacheLevel(CacheLevel.LOCAL_CACHE)
								.build();
							var result = localCacheManager.get(key);
							if (result != null)
								hits.incrementAndGet();
							else
								misses.incrementAndGet();
						}
					}
					finally {
						latch.countDown();
					}
				});
			}

			latch.await(60, TimeUnit.SECONDS);
			long duration = System.currentTimeMillis() - startTime;
			executor.shutdown();

			long total = hits.get() + misses.get();
			double qps = (total * 1000.0) / duration;
			double hitRate = (hits.get() * 100.0) / total;
			log.info("Read Test | Threads: {} | Hits: {} | Misses: {} | HitRate: {:.}%% | Duration: {}ms | QPS: {:.}",
					threadCount, hits.get(), misses.get(), hitRate, duration, qps);
		}
	}

	@DisplayName("Local cache read-write mix test")
	void testLocalCacheReadWriteMix() throws InterruptedException {
		int warmupSize = 20000;
		for (int i = 0; i < warmupSize; i++) {
			CacheKey key = CacheKey.builder()
				.key("mix-warmup-" + i)
				.consistencyLevel(ConsistencyLevel.HIGH)
				.cacheLevel(CacheLevel.LOCAL_CACHE)
				.build();
			localCacheManager.put(key, createCacheValue("mix-warmup-" + i));
		}

		int[] threadCounts = { 50, 100, 200 };
		int operationsPerThread = 10000;
		int[] readRatios = { 90, 70, 50 };

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
									CacheKey key = CacheKey.builder()
										.key("mix-warmup-" + keyIdx)
										.consistencyLevel(ConsistencyLevel.HIGH)
										.cacheLevel(CacheLevel.LOCAL_CACHE)
										.build();
									localCacheManager.get(key);
									readOps.incrementAndGet();
								}
								else {
									String newKey = "mix-write-" + ThreadLocalRandom.current().nextInt(100000);
									CacheKey key = CacheKey.builder()
										.key(newKey)
										.consistencyLevel(ConsistencyLevel.HIGH)
										.cacheLevel(CacheLevel.LOCAL_CACHE)
										.build();
									localCacheManager.put(key, createCacheValue(newKey));
									writeOps.incrementAndGet();
								}
							}
						}
						finally {
							latch.countDown();
						}
					});
				}

				latch.await(60, TimeUnit.SECONDS);
				long duration = System.currentTimeMillis() - startTime;
				executor.shutdown();

				long total = readOps.get() + writeOps.get();
				double qps = (total * 1000.0) / duration;
				log.info("Mix Test | ReadRatio: {}%% | Threads: {} | Reads: {} | Writes: {} | Duration: {}ms | QPS: {:.}",
						readRatio, threadCount, readOps.get(), writeOps.get(), duration, qps);
			}
		}
	}

	@DisplayName("Local cache eviction performance test")
	void testLocalCacheEvictionPerformance() throws InterruptedException {
		HccProperties.LocalCacheProperties smallProps = new HccProperties.LocalCacheProperties();
		smallProps.setMaximumSize(1000);
		smallProps.setExpireAfterWrite(300);
		LocalCacheManager smallCache = new LocalCacheManager(smallProps);

		int insertCount = 50000;
		int[] threadCounts = { 20, 50, 100 };

		for (int threadCount : threadCounts) {
			ExecutorService executor = Executors.newFixedThreadPool(threadCount);
			CountDownLatch latch = new CountDownLatch(threadCount);

			long startTime = System.currentTimeMillis();

			for (int i = 0; i < threadCount; i++) {
				final int threadId = i;
				executor.submit(() -> {
					try {
						int opsPerThread = insertCount / threadCount;
						for (int j = 0; j < opsPerThread; j++) {
							String key = "evict-" + threadId + "-" + j;
							CacheKey cacheKey = CacheKey.builder()
								.key(key)
								.consistencyLevel(ConsistencyLevel.HIGH)
								.cacheLevel(CacheLevel.LOCAL_CACHE)
								.build();
							smallCache.put(cacheKey, createCacheValue(key));
						}
					}
					finally {
						latch.countDown();
					}
				});
			}

			latch.await(60, TimeUnit.SECONDS);
			long duration = System.currentTimeMillis() - startTime;
			executor.shutdown();

			double qps = (insertCount * 1000.0) / duration;
			log.info("Eviction Test | Threads: {} | Inserts: {} | FinalSize: {} | Duration: {}ms | QPS: {:.}",
					threadCount, insertCount, smallCache.getSize(), duration, qps);
		}
	}

	private io.github.latcn.cache.core.model.CacheValue<String> createCacheValue(String value) {
		return io.github.latcn.cache.core.model.CacheValue.<String>builder()
			.value(value)
			.expireTime(System.currentTimeMillis() + 300000)
			.build();
	}

}