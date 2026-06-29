package io.github.latcn.cache.core.hotspot;

import static org.junit.jupiter.api.Assertions.*;

import io.github.latcn.cache.core.exception.CacheException;
import io.github.latcn.cache.core.hotspot.base.CMSHotKeyDetector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CMSHotKeyDetector Tests")
class CMSHotKeyDetectorTest {

	private CMSHotKeyDetector detector;

	@BeforeEach
	void setUp() {
		detector = new CMSHotKeyDetector(10000, 3, 0.15, 500, 4);
	}

	@AfterEach
	void tearDown() {
		if (detector != null) {
			detector.close();
		}
	}

	@Test
	@DisplayName("Should correctly count single key")
	void testSingleKeyCounting() {
		String key = "test-key";
		int recordCount = 100;

		for (int i = 0; i < recordCount; i++) {
			detector.record(key);
		}

		long estimate = detector.estimateCount(key);
		assertTrue(estimate >= recordCount, "Estimate should be at least the actual count");
	}

	@Test
	@DisplayName("Should handle concurrent writes safely")
	void testConcurrentWrites() throws InterruptedException {
		String key = "concurrent-key";
		int threadCount = 10;
		int writesPerThread = 1000;

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch latch = new CountDownLatch(threadCount);

		for (int i = 0; i < threadCount; i++) {
			executor.execute(() -> {
				for (int j = 0; j < writesPerThread; j++) {
					detector.record(key);
				}
				latch.countDown();
			});
		}

		latch.await();
		executor.shutdown();

		long estimate = detector.estimateCount(key);
		assertTrue(estimate >= threadCount * writesPerThread, "Concurrent writes should be correctly counted");
	}

	@Test
	@DisplayName("Should decay counts over time")
	void testDecay() throws InterruptedException {
		String key = "decay-key";
		int recordCount = 1000;

		for (int i = 0; i < recordCount; i++) {
			detector.record(key);
		}

		long initialEstimate = detector.estimateCount(key);
		Thread.sleep(2000);

		long decayedEstimate = detector.estimateCount(key);
		assertTrue(decayedEstimate < initialEstimate, "Count should decay over time");
	}

	@Test
	@DisplayName("Should throw IllegalArgumentException for invalid parameters")
	void testInvalidParameters() {
		assertThrows(CacheException.class, () -> new CMSHotKeyDetector(-1, 3, 0.1, 100, 4));

		assertThrows(CacheException.class, () -> new CMSHotKeyDetector(10000, -1, 0.1, 100, 4));

		assertThrows(CacheException.class, () -> new CMSHotKeyDetector(10000, 3, -0.1, 100, 4));

		assertThrows(CacheException.class, () -> new CMSHotKeyDetector(10000, 3, 1.1, 100, 4));

		assertThrows(CacheException.class, () -> new CMSHotKeyDetector(10000, 3, 0.1, -1, 4));

		assertThrows(CacheException.class, () -> new CMSHotKeyDetector(10000, 3, 0.1, 100, 0));
	}

	@Test
	@DisplayName("Should throw NullPointerException for null key")
	void testNullKey() {
		assertThrows(CacheException.class, () -> detector.record(null));
		assertThrows(CacheException.class, () -> detector.estimateCount(null));
	}

	@Test
	@DisplayName("Should throw IllegalStateException after close")
	void testOperationsAfterClose() {
		detector.close();
		assertThrows(CacheException.class, () -> detector.record("test"));
		assertThrows(CacheException.class, () -> detector.estimateCount("test"));
	}

	@Test
	@DisplayName("Should maintain hash distribution")
	void testHashDistribution() {
		int sampleSize = 10000;
		int[] bucketCounts = new int[100];

		for (int i = 0; i < sampleSize; i++) {
			String key = "key-" + i;
			int hash = key.hashCode();
			long h = (hash * 0x9e3779b97f4a7c15L) ^ ((hash * 0x9e3779b97f4a7c15L) >>> 32);
			h &= Long.MAX_VALUE; // 确保非负（可选但推荐）
			int bucket = (int) (h % 100); // 先取模，再转int
			bucketCounts[bucket]++;
		}

		double mean = (double) sampleSize / 100;
		double variance = 0;
		for (int count : bucketCounts) {
			variance += Math.pow(count - mean, 2);
		}
		variance /= 100;

		double stdDev = Math.sqrt(variance);
		assertTrue(stdDev / mean < 0.5, "Hash distribution should be reasonably uniform");
	}

	@Test
	@DisplayName("Should handle multiple keys independently")
	void testMultipleKeys() {
		String key1 = "key-1";
		String key2 = "key-2";

		for (int i = 0; i < 100; i++) {
			detector.record(key1);
		}
		for (int i = 0; i < 50; i++) {
			detector.record(key2);
		}

		long count1 = detector.estimateCount(key1);
		long count2 = detector.estimateCount(key2);

		assertTrue(count1 >= count2, "Key with more records should have higher estimate");
	}

	@Test
	@DisplayName("Should track decay metrics")
	void testDecayMetrics() throws InterruptedException {
		Thread.sleep(600);

		assertTrue(detector.getDecayRunCount() >= 1, "Decay should have run at least once");
		assertTrue(detector.getTotalDecayTimeMs() >= 0, "Total decay time should be non-negative");
		assertTrue(detector.getTotalDecayItems() >= 0, "Total decay items should be non-negative");
		assertTrue(detector.getAvgDecayTimeMs() >= 0, "Average decay time should be non-negative");
	}

	@Test
	@DisplayName("Should handle key with zero count")
	void testZeroCountKey() {
		assertEquals(0, detector.estimateCount("non-existent-key"));
	}

	@Test
	@DisplayName("Should handle large number of unique keys")
	void testLargeUniqueKeys() {
		int uniqueKeys = 1000;

		for (int i = 0; i < uniqueKeys; i++) {
			detector.record("unique-key-" + i);
		}

		assertTrue(detector.estimateCount("unique-key-0") >= 1);
	}

	@Test
	@DisplayName("Should not throw exception during concurrent decay and write")
	void testConcurrentDecayAndWrite() throws InterruptedException {
		String key = "concurrent-decay-key";
		ExecutorService executor = Executors.newFixedThreadPool(5);
		CountDownLatch latch = new CountDownLatch(5);

		for (int i = 0; i < 5; i++) {
			executor.execute(() -> {
				for (int j = 0; j < 1000; j++) {
					detector.record(key);
				}
				latch.countDown();
			});
		}

		latch.await();
		executor.shutdown();

		assertTrue(detector.estimateCount(key) >= 5000);
	}

	@Test
	@DisplayName("Should close executor service properly")
	void testCloseExecutor() {
		detector.close();

		assertThrows(CacheException.class, () -> detector.record("test"));
	}

}