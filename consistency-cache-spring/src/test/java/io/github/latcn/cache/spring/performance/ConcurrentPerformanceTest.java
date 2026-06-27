package io.github.latcn.cache.spring.performance;

import static org.junit.jupiter.api.Assertions.*;

import io.github.latcn.cache.core.hotspot.reads.DefaultReadHotspotDetector;
import io.github.latcn.cache.core.local.LocalCacheFactory;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.github.latcn.cache.core.manager.SingleFlightExecutor;
import io.github.latcn.cache.core.manager.SingleFlightResult;
import io.github.latcn.cache.core.model.*;
import io.github.latcn.cache.spring.local.adapter.CaffeineCacheAdapter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@Slf4j
@DisplayName("Concurrent Performance Tests")
class ConcurrentPerformanceTest {

	private LocalCacheManager localCacheManager;

	private ExecutorService executorService;

	@BeforeEach
	void setUp() {
		LocalCacheFactory.registerCacheType(LocalCacheType.CAFFEINE.name(), CaffeineCacheAdapter.class);
		HccProperties.LocalCacheProperties properties = new HccProperties.LocalCacheProperties();
		properties.setMaximumSize(10000);
		properties.setExpireAfterWrite(300);
		localCacheManager = new LocalCacheManager(properties);
		executorService = Executors.newFixedThreadPool(20);
	}

	@Test
	@DisplayName("High concurrency read-write performance test")
	void testHighConcurrencyReadWrite() throws InterruptedException {
		int threadCount = 20;
		int operationsPerThread = 1000;
		CountDownLatch latch = new CountDownLatch(threadCount);
		AtomicLong successCount = new AtomicLong(0);
		AtomicLong failCount = new AtomicLong(0);

		long startTime = System.currentTimeMillis();

		for (int i = 0; i < threadCount; i++) {
			final int threadId = i;
			executorService.submit(() -> {
				try {
					for (int j = 0; j < operationsPerThread; j++) {
						String key = "key-" + (threadId * operationsPerThread + j);

						CacheKey cacheKey = CacheKey.builder()
							.key(key)
							.consistencyLevel(ConsistencyLevel.HIGH)
							.cacheLevel(CacheLevel.LOCAL_CACHE)
							.build();

						CacheValue<String> value = CacheValue.<String>builder()
							.value("value-" + key)
							.expireTime(System.currentTimeMillis() + 60000)
							.build();

						localCacheManager.put(cacheKey, value);

						CacheValue result = localCacheManager.get(cacheKey);
						if (result != null && result.getValue() != null) {
							successCount.incrementAndGet();
						}
						else {
							failCount.incrementAndGet();
						}
					}
				}
				finally {
					latch.countDown();
				}
			});
		}

		latch.await(30, TimeUnit.SECONDS);
		long endTime = System.currentTimeMillis();

		long totalOperations = (long) threadCount * operationsPerThread * 2;
		long duration = endTime - startTime;

		log.info("Total operations: {}", totalOperations);
		log.info("Duration: {}ms", duration);
		log.info("Ops/sec: {}", (totalOperations * 1000 / duration));
		log.info("Success rate: {}%", (successCount.get() * 100.0 / totalOperations));
	}

	@Test
	@DisplayName("Cache stampede prevention test")
	void testCacheStampedePrevention() throws InterruptedException {
		String hotKey = "hot-key";
		CacheKey cacheKey = CacheKey.builder()
			.key(hotKey)
			.consistencyLevel(ConsistencyLevel.HIGH)
			.cacheLevel(CacheLevel.LOCAL_CACHE)
			.build();

		AtomicInteger loadCount = new AtomicInteger(0);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(50);

		CacheValue<String> expiredValue = CacheValue.<String>builder()
			.value("expired")
			.expireTime(System.currentTimeMillis() - 1000)
			.build();
		localCacheManager.put(cacheKey, expiredValue);

		for (int i = 0; i < 50; i++) {
			executorService.submit(() -> {
				try {
					startLatch.await();

					CacheValue result = localCacheManager.get(cacheKey);
					if (result == null || result.isExpired()) {
						int count = loadCount.incrementAndGet();
						if (count == 1) {
							Thread.sleep(10);

							CacheValue<String> freshValue = CacheValue.<String>builder()
								.value("fresh-value")
								.expireTime(System.currentTimeMillis() + 60000)
								.build();
							localCacheManager.put(cacheKey, freshValue);
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

		startLatch.countDown();
		doneLatch.await(10, TimeUnit.SECONDS);

		log.info("Load function called {} times", loadCount.get());
	}

	@Test
	@DisplayName("Memory efficiency under high load")
	void testMemoryEfficiency() throws InterruptedException {
		int entryCount = 5000;
		CountDownLatch latch = new CountDownLatch(entryCount);

		long startTime = Runtime.getRuntime().freeMemory();

		for (int i = 0; i < entryCount; i++) {
			final int index = i;
			executorService.submit(() -> {
				try {
					CacheKey key = CacheKey.builder()
						.key("mem-test-" + index)
						.consistencyLevel(ConsistencyLevel.HIGH)
						.cacheLevel(CacheLevel.LOCAL_CACHE)
						.build();

					CacheValue<String> value = CacheValue.<String>builder()
						.value("value-" + index)
						.expireTime(System.currentTimeMillis() + 300000)
						.build();

					localCacheManager.put(key, value);
				}
				finally {
					latch.countDown();
				}
			});
		}

		latch.await(30, TimeUnit.SECONDS);
		long endTime = Runtime.getRuntime().freeMemory();

		System.gc();
		Thread.sleep(10000);
		long afterGcMemory = Runtime.getRuntime().freeMemory();

		long memoryUsed = startTime - afterGcMemory;
		long heapSize = Runtime.getRuntime().totalMemory();

		log.info("Entries stored: {}", entryCount);
		log.info("Memory used: {} MB", (memoryUsed / 1024 / 1024));
		log.info("Heap size: {} MB", (heapSize / 1024 / 1024));
		log.info("Memory per entry: {} bytes", (memoryUsed / entryCount));
		long actualSize = localCacheManager.getSize();
	}

	@Test
	@DisplayName("Eviction performance under pressure")
	void testEvictionPerformance() throws InterruptedException {
		HccProperties.LocalCacheProperties props = new HccProperties.LocalCacheProperties();
		props.setMaximumSize(1000);
		props.setExpireAfterWrite(1000);
		LocalCacheManager smallCache = new LocalCacheManager(props);

		int insertCount = 5000;
		CountDownLatch latch = new CountDownLatch(insertCount);

		long startTime = System.currentTimeMillis();

		for (int i = 0; i < insertCount; i++) {
			final int index = i;
			executorService.submit(() -> {
				try {
					CacheKey key = CacheKey.builder()
						.key("evict-test-" + index)
						.consistencyLevel(ConsistencyLevel.HIGH)
						.cacheLevel(CacheLevel.LOCAL_CACHE)
						.build();

					CacheValue<String> value = CacheValue.<String>builder()
						.value("value-" + index)
						.expireTime(System.currentTimeMillis() + 60000)
						.build();

					smallCache.put(key, value);
				}
				finally {
					latch.countDown();
				}
			});
		}

		latch.await(30, TimeUnit.SECONDS);
		long duration = System.currentTimeMillis() - startTime;

		log.info("Insertions: {}", insertCount);
		log.info("Final cache size: {}", smallCache.getSize());
		log.info("Duration: {} ms", duration);
		log.info("Insertions/sec: {}", (insertCount * 1000 / duration));

		assertTrue(smallCache.getSize() <= 1000, "Cache should respect max size limit");
		assertTrue(duration < 10000, "Should complete within reasonable time");
	}

	@Test
	@DisplayName("Hotspot detection accuracy under load")
	void testHotspotDetectionAccuracy() throws InterruptedException {
		DefaultReadHotspotDetector statistics = new DefaultReadHotspotDetector(100.0);
		String hotKey = "hot-key";
		String coldKey = "cold-key";

		CountDownLatch hotLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(20);

		for (int i = 0; i < 20; i++) {
			executorService.submit(() -> {
				try {
					hotLatch.await();

					for (int j = 0; j < 50; j++) {
						statistics.recordRead(hotKey);
					}

					statistics.recordRead(coldKey);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				finally {
					doneLatch.countDown();
				}
			});
		}

		hotLatch.countDown();
		doneLatch.await(10, TimeUnit.SECONDS);

		assertTrue(statistics.isHotKey(hotKey), "Should detect hot key accurately");
		assertFalse(statistics.isHotKey(coldKey), "Should not flag cold key as hot");

		double hotKeyQps = statistics.getQps(hotKey);
		double coldKeyQps = statistics.getQps(coldKey);

		log.info("Hot key QPS: {}", hotKeyQps);
		log.info("Cold key QPS: {}", coldKeyQps);

		assertTrue(hotKeyQps > coldKeyQps * 10, "Hot key QPS should be significantly higher");
	}

	@Test
	@DisplayName("SingleFlight performance under thundering herd")
	void testSingleFlightThunderingHerd() throws InterruptedException {
		SingleFlightExecutor singleFlight = new SingleFlightExecutor();
		String sharedKey = "shared-resource";
		AtomicInteger actualExecutions = new AtomicInteger(0);

		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(100);

		for (int i = 0; i < 100; i++) {
			executorService.submit(() -> {
				try {
					try {
						startLatch.await();
					}
					catch (InterruptedException e) {
						throw new RuntimeException(e);
					}

					SingleFlightResult<String> result = singleFlight.executeWithResult(sharedKey, k -> {
						actualExecutions.incrementAndGet();
						try {
							Thread.sleep(10);
						}
						catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
						return "result";
					});
				}
				finally {
					doneLatch.countDown();
				}
			});
		}

		startLatch.countDown();
		doneLatch.await(10, TimeUnit.SECONDS);

		log.info("Actual executions: {}", actualExecutions.get());
		log.info("Total requests: 100");
		log.info("Reduction: {} redundant calls prevented", (100 - actualExecutions.get()));

		assertTrue(actualExecutions.get() < 10, "SingleFlight should prevent duplicate executions");
	}

}