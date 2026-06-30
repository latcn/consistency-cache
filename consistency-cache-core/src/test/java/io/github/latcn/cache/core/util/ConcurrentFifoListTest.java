package io.github.latcn.cache.core.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConcurrentFifoList 测试")
class ConcurrentFifoListTest {

	private ConcurrentFifoList<String> list;

	@BeforeEach
	void setUp() {
		list = new ConcurrentFifoList<>(5, ConcurrentFifoList.OverflowStrategy.REMOVE_OLDEST);
	}

	@Test
	@DisplayName("CFL-001: 构造函数-正常参数")
	void testConstructorNormalParameters() {
		ConcurrentFifoList<Integer> boundedList = new ConcurrentFifoList<>(10,
				ConcurrentFifoList.OverflowStrategy.REJECT);
		assertEquals(0, boundedList.size());

		ConcurrentFifoList<Integer> unboundedList = new ConcurrentFifoList<>(0,
				ConcurrentFifoList.OverflowStrategy.REMOVE_OLDEST);
		assertEquals(0, unboundedList.size());

		ConcurrentFifoList<Integer> defaultList = new ConcurrentFifoList<>();
		assertEquals(0, defaultList.size());
	}

	@Test
	@DisplayName("CFL-002: 构造函数-负容量参数异常")
	void testConstructorNegativeCapacity() {
		assertThrows(IllegalArgumentException.class, () -> {
			new ConcurrentFifoList<>(-1, ConcurrentFifoList.OverflowStrategy.REMOVE_OLDEST);
		});
	}

	@Test
	@DisplayName("CFL-003: put-正常插入新元素")
	void testPutNewElement() {
		String result = list.put("A");
		assertNull(result);
		assertEquals(1, list.size());
		assertTrue(list.contains("A"));
	}

	@Test
	@DisplayName("CFL-004: put-重复插入返回旧值")
	void testPutDuplicateElement() {
		list.put("A");
		String result = list.put("A");
		assertEquals("A", result);
		assertEquals(1, list.size());
	}

	@Test
	@DisplayName("CFL-005: put-null参数异常")
	void testPutNullValue() {
		assertThrows(NullPointerException.class, () -> {
			list.put(null);
		});
	}

	@Test
	@DisplayName("CFL-006: REMOVE_OLDEST策略-容量满时自动淘汰")
	void testRemoveOldestStrategyOverflow() {
		list.put("A");
		list.put("B");
		list.put("C");
		list.put("D");
		list.put("E");
		assertEquals(5, list.size());

		list.put("F");
		assertEquals(5, list.size());
		assertFalse(list.contains("A"));
		assertTrue(list.contains("F"));

		List<String> values = list.values();
		assertEquals(List.of("B", "C", "D", "E", "F"), values);
	}

	@Test
	@DisplayName("CFL-007: REJECT策略-容量满时拒绝插入")
	void testRejectStrategyOverflow() {
		ConcurrentFifoList<String> rejectList = new ConcurrentFifoList<>(3, ConcurrentFifoList.OverflowStrategy.REJECT);

		rejectList.put("A");
		rejectList.put("B");
		rejectList.put("C");
		assertEquals(3, rejectList.size());

		assertThrows(IllegalStateException.class, () -> {
			rejectList.put("D");
		});

		assertEquals(3, rejectList.size());
		assertFalse(rejectList.contains("D"));
	}

	@Test
	@DisplayName("CFL-008: getKey-获取存在的键")
	void testGetKeyExists() {
		String obj1 = new String("A");
		String obj2 = new String("A");
		list.put(obj1);

		String result = list.getKey(obj2);
		assertSame(obj1, result);
	}

	@Test
	@DisplayName("CFL-009: getKey-获取不存在的键")
	void testGetKeyNotExists() {
		assertNull(list.getKey("A"));
	}

	@Test
	@DisplayName("CFL-010: getKey-null参数")
	void testGetKeyNull() {
		assertNull(list.getKey(null));
	}

	@Test
	@DisplayName("CFL-011: remove-移除存在的元素")
	void testRemoveExists() {
		list.put("A");
		list.put("B");
		assertEquals(2, list.size());

		assertTrue(list.remove("A"));
		assertEquals(1, list.size());
		assertFalse(list.contains("A"));
		assertTrue(list.contains("B"));
	}

	@Test
	@DisplayName("CFL-012: remove-移除不存在的元素")
	void testRemoveNotExists() {
		list.put("A");
		assertEquals(1, list.size());

		assertFalse(list.remove("B"));
		assertEquals(1, list.size());
	}

	@Test
	@DisplayName("CFL-013: remove-null参数异常")
	void testRemoveNull() {
		assertThrows(NullPointerException.class, () -> {
			list.remove(null);
		});
	}

	@Test
	@DisplayName("CFL-014: drain-正常批量取出")
	void testDrainNormal() {
		list.put("A");
		list.put("B");
		list.put("C");
		assertEquals(3, list.size());

		List<String> drained = list.drain(2);
		assertEquals(2, drained.size());
		assertEquals(List.of("A", "B"), drained);
		assertEquals(1, list.size());
		assertFalse(list.contains("A"));
		assertFalse(list.contains("B"));
		assertTrue(list.contains("C"));
	}

	@Test
	@DisplayName("CFL-015: drain-取出数量大于实际数量")
	void testDrainMoreThanSize() {
		list.put("A");
		list.put("B");
		assertEquals(2, list.size());

		List<String> drained = list.drain(10);
		assertEquals(2, drained.size());
		assertEquals(List.of("A", "B"), drained);
		assertEquals(0, list.size());
	}

	@Test
	@DisplayName("CFL-016: drain-空列表")
	void testDrainEmpty() {
		List<String> drained = list.drain(5);
		assertTrue(drained.isEmpty());
		assertEquals(0, list.size());
	}

	@Test
	@DisplayName("CFL-017: drain-无效参数")
	void testDrainInvalidParameter() {
		list.put("A");
		assertEquals(1, list.size());

		List<String> drained = list.drain(0);
		assertTrue(drained.isEmpty());
		assertEquals(1, list.size());

		drained = list.drain(-5);
		assertTrue(drained.isEmpty());
		assertEquals(1, list.size());
	}

	@Test
	@DisplayName("CFL-018: size-无锁读取")
	void testSizeLockFree() {
		assertEquals(0, list.size());

		list.put("A");
		assertEquals(1, list.size());

		list.put("B");
		assertEquals(2, list.size());

		list.remove("A");
		assertEquals(1, list.size());

		list.clear();
		assertEquals(0, list.size());
	}

	@Test
	@DisplayName("CFL-019: contains-检查存在")
	void testContainsExists() {
		list.put("A");
		assertTrue(list.contains("A"));
		assertFalse(list.contains("B"));
	}

	@Test
	@DisplayName("CFL-020: contains-null参数")
	void testContainsNull() {
		assertFalse(list.contains(null));
	}

	@Test
	@DisplayName("CFL-021: clear-清空列表")
	void testClear() {
		list.put("A");
		list.put("B");
		list.put("C");
		assertEquals(3, list.size());

		list.clear();
		assertEquals(0, list.size());
		assertFalse(list.contains("A"));
		assertFalse(list.contains("B"));
		assertFalse(list.contains("C"));
	}

	@Test
	@DisplayName("CFL-022: values-返回FIFO顺序快照")
	void testValuesSnapshot() {
		list.put("A");
		list.put("B");
		list.put("C");

		List<String> values = list.values();
		assertEquals(List.of("A", "B", "C"), values);

		List<String> values2 = list.values();
		assertNotSame(values, values2);

		values.add("D");
		assertEquals(3, list.size());
	}

	@Test
	@DisplayName("CFL-023: 无限容量模式-不淘汰")
	void testUnboundedCapacity() {
		ConcurrentFifoList<String> unboundedList = new ConcurrentFifoList<>(0,
				ConcurrentFifoList.OverflowStrategy.REMOVE_OLDEST);

		for (int i = 0; i < 100; i++) {
			unboundedList.put("Item" + i);
		}

		assertEquals(100, unboundedList.size());
		for (int i = 0; i < 100; i++) {
			assertTrue(unboundedList.contains("Item" + i));
		}
	}

	@Test
	@DisplayName("CFL-024: 并发写入测试")
	void testConcurrentWrites() throws InterruptedException {
		ConcurrentFifoList<String> concurrentList = new ConcurrentFifoList<>(100,
				ConcurrentFifoList.OverflowStrategy.REMOVE_OLDEST);
		int threadCount = 10;
		int itemsPerThread = 10;
		CountDownLatch latch = new CountDownLatch(threadCount);

		for (int t = 0; t < threadCount; t++) {
			final int threadIndex = t;
			new Thread(() -> {
				for (int i = 0; i < itemsPerThread; i++) {
					concurrentList.put("Thread" + threadIndex + "-Item" + i);
				}
				latch.countDown();
			}).start();
		}

		assertTrue(latch.await(5, TimeUnit.SECONDS));
		assertTrue(concurrentList.size() <= 100);
	}

	@Test
	@DisplayName("CFL-025: 并发读写测试")
	void testConcurrentReadWrite() throws InterruptedException {
		ConcurrentFifoList<String> concurrentList = new ConcurrentFifoList<>(100,
				ConcurrentFifoList.OverflowStrategy.REMOVE_OLDEST);
		int threadCount = 5;
		CountDownLatch latch = new CountDownLatch(threadCount * 2);
		AtomicInteger readCount = new AtomicInteger(0);

		for (int t = 0; t < threadCount; t++) {
			new Thread(() -> {
				for (int i = 0; i < 20; i++) {
					concurrentList.put("Write-Item" + i);
				}
				latch.countDown();
			}).start();

			new Thread(() -> {
				for (int i = 0; i < 50; i++) {
					concurrentList.contains("Read-Item" + i);
					concurrentList.size();
					readCount.incrementAndGet();
				}
				latch.countDown();
			}).start();
		}

		assertTrue(latch.await(5, TimeUnit.SECONDS));
		assertTrue(readCount.get() >= threadCount * 50);
	}

}