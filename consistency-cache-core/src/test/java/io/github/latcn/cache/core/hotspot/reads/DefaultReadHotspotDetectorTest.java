package io.github.latcn.cache.core.hotspot.reads;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultReadHotspotDetector Tests")
class DefaultReadHotspotDetectorTest {

	private DefaultReadHotspotDetector detector;

	@BeforeEach
	void setUp() {
		detector = new DefaultReadHotspotDetector(100.0);
	}

	@AfterEach
	void tearDown() {
		if (detector != null) {
			detector.close();
		}
	}

	@Test
	@DisplayName("Should record read operation")
	void testRecordRead() {
		String key = "test-key";
		detector.recordRead(key);
		assertNotNull(detector.getQps(key));
	}

	@Test
	@DisplayName("Should detect hot key when QPS exceeds threshold")
	void testHotKeyDetection() {
		String hotKey = "hot-key";
		for (int i = 0; i < 200; i++) {
			detector.recordRead(hotKey);
		}
		assertTrue(detector.isHotKey(hotKey));
	}

	@Test
	@DisplayName("Should not detect cold key as hot")
	void testColdKeyNotDetectedAsHot() {
		String coldKey = "cold-key";
		for (int i = 0; i < 5; i++) {
			detector.recordRead(coldKey);
		}
		assertFalse(detector.isHotKey(coldKey));
	}

	@Test
	@DisplayName("Should return QPS for hot key")
	void testGetQps() {
		String key = "qps-test-key";
		for (int i = 0; i < 200; i++) {
			detector.recordRead(key);
		}
		double qps = detector.getQps(key);
		assertTrue(qps > 0);
	}

	@Test
	@DisplayName("Should return 0 QPS for non-existent key")
	void testGetQpsNonExistentKey() {
		double qps = detector.getQps("non-existent");
		assertEquals(0.0, qps, 0.01);
	}

	@Test
	@DisplayName("Should return 0 QPS for cold key")
	void testGetQpsColdKey() {
		String key = "cold-qps-key";
		for (int i = 0; i < 5; i++) {
			detector.recordRead(key);
		}
		double qps = detector.getQps(key);
		assertEquals(0.0, qps, 0.01);
	}

	@Test
	@DisplayName("Should handle multiple keys independently")
	void testMultipleKeys() {
		String key1 = "key-1";
		String key2 = "key-2";

		for (int i = 0; i < 200; i++) {
			detector.recordRead(key1);
		}

		for (int i = 0; i < 10; i++) {
			detector.recordRead(key2);
		}

		assertTrue(detector.isHotKey(key1));
		assertFalse(detector.isHotKey(key2));
	}

	@Test
	@DisplayName("Should increment hot key count when hot key detected")
	void testHotKeyCount() {
		String key = "count-test";
		long initialCount = detector.readHotKeyCount();

		for (int i = 0; i < 200; i++) {
			detector.recordRead(key);
		}

		assertTrue(detector.readHotKeyCount() >= initialCount);
	}

	@Test
	@DisplayName("Should handle concurrent access safely")
	void testConcurrentAccess() throws InterruptedException {
		String sharedKey = "concurrent-key";
		int threadCount = 10;
		Thread[] threads = new Thread[threadCount];

		for (int i = 0; i < threadCount; i++) {
			threads[i] = new Thread(() -> {
				for (int j = 0; j < 100; j++) {
					detector.recordRead(sharedKey);
				}
			});
			threads[i].start();
		}

		for (Thread thread : threads) {
			thread.join();
		}

		assertTrue(detector.isHotKey(sharedKey));
	}

	@Test
	@DisplayName("Should handle null key")
	void testNullKey() {
		assertDoesNotThrow(() -> detector.recordRead(null));
		assertFalse(detector.isHotKey(null));
	}

}