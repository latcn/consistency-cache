package io.github.latcn.cache.core.hotspot;

import static org.junit.jupiter.api.Assertions.*;

import io.github.latcn.cache.core.hotspot.base.TwoLevelHotKeyDetector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TwoLevelHotKeyDetectorTest {

	private TwoLevelHotKeyDetector detector;

	// 使用较低的 hotQps 和较高的 promotionRatio，减少晋升所需记录次数
	private static final long TOTAL_QPS = 10000;

	private static final int HOT_QPS = 10;

	private static final double PROMOTION_RATIO = 0.8;

	private static final int MAX_EXACT_SIZE = 100;

	private static final long EXPIRATION_MS = 1000;

	private static final long CLEANUP_INTERVAL_MS = 500;

	@BeforeEach
	void setUp() {
		detector = new TwoLevelHotKeyDetector(TOTAL_QPS, HOT_QPS, 2, 1000, 4, PROMOTION_RATIO, MAX_EXACT_SIZE,
				EXPIRATION_MS, CLEANUP_INTERVAL_MS);
	}

	@Test
	void testPromotionAndExactCount() throws InterruptedException {
		String hotKey = "hotKey";
		long fixedNow = System.nanoTime();
		int count = 0;
		// 记录 200 次足够晋升（因为 hotQps=10，promotionRatio=0.8，阈值约 8，CMS 计数会很快达到）
		for (int i = 0; i < 200; i++) {
			detector.record(hotKey, fixedNow);
			count++;
		}
		// 等待可能的后台清理（不必须）
		Thread.sleep(10);
		// 验证晋升
		assertTrue(detector.getExactSize() > 0, "Key should be promoted");
		int qps = detector.getCurrentQps(hotKey);
		assertTrue(qps > 0, "QPS should > 0, got " + qps);
		assertTrue(detector.isHotKey(hotKey), "Should be hot");
	}

	// 其他测试方法保持不变，但注意也要使用固定时间戳或调整参数
	@Test
	void testCapacityEviction() throws InterruptedException {
		TwoLevelHotKeyDetector testDetector = new TwoLevelHotKeyDetector(1000, 5, 1, 1000, 4, 0.8, 10, 5000, 1000);
		long now = System.nanoTime();
		for (int i = 0; i < 20; i++) {
			String key = "key" + i;
			for (int j = 0; j < 30; j++) {
				testDetector.record(key, now);
			}
		}
		Thread.sleep(1500);
		int size = testDetector.getExactSize();
		assertTrue(size <= 10, "After cleanup, size should be <= 10, but was " + size);
	}

	@Test
	void testHotKeyDetection() throws InterruptedException {
		String hot = "hot";
		String cold = "cold";
		long now = System.nanoTime();
		for (int i = 0; i < 100; i++) {
			detector.record(hot, now);
		}
		for (int i = 0; i < 5; i++) {
			detector.record(cold, now);
		}
		Thread.sleep(10);
		assertTrue(detector.isHotKey(hot), "Hot key should be detected");
		assertFalse(detector.isHotKey(cold), "Cold key should not be detected");
	}

	@Test
	void testExpirationCleanup() throws InterruptedException {
		String key = "expireKey";
		long now = System.nanoTime();
		for (int i = 0; i < 100; i++) {
			detector.record(key, now);
		}
		assertTrue(detector.isHotKey(key), "Key should be hot initially");
		Thread.sleep(EXPIRATION_MS + 1000);
		assertFalse(detector.isHotKey(key), "Key should have expired");
		assertEquals(0, detector.getExactSize(), "Exact size should be 0");
	}

	@Test
	void testConcurrentRecordDifferentKeys() throws InterruptedException {
		int threadCount = 20;
		int recordsPerThread = 50;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch latch = new CountDownLatch(threadCount);
		long now = System.nanoTime();

		for (int i = 0; i < threadCount; i++) {
			final int index = i;
			executor.submit(() -> {
				try {
					String key = "key" + (index % 10);
					for (int j = 0; j < recordsPerThread; j++) {
						detector.record(key, now);
					}
				}
				finally {
					latch.countDown();
				}
			});
		}
		latch.await(5, TimeUnit.SECONDS);
		executor.shutdown();
		executor.awaitTermination(1, TimeUnit.SECONDS);

		for (int i = 0; i < 10; i++) {
			String key = "key" + i;
			int qps = detector.getCurrentQps(key);
			assertTrue(qps > 0, "Key " + key + " qps=" + qps);
		}
		int size = detector.getExactSize();
		assertTrue(size <= MAX_EXACT_SIZE * 1.5, "Size too large: " + size);
	}

	@Test
	void testDynamicPromotionRatioChange() {
		String key = "dynamic";
		long now = System.nanoTime();
		for (int i = 0; i < 30; i++) {
			detector.record(key, now);
		}
		assertTrue(detector.getExactSize() > 0);
		detector.setPromotionRatio(0.9);
		assertTrue(detector.isHotKey(key));
	}

}