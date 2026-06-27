package io.github.latcn.cache.core.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

@DisplayName("安全FIFO队列测试")
class SafeFifoQueueTest {

	private SafeFifoQueue<String> queue;

	@BeforeEach
	void setUp(TestInfo testInfo) {
		queue = new SafeFifoQueue<>();
		System.out.println("执行测试: " + testInfo.getDisplayName());
	}

	@Test
	@DisplayName("SF-001: 插入新元素")
	void testInsertNewElement() {
		queue.put("element1");

		assertEquals(1, queue.size());
		assertTrue(queue.contains("element1"));
		assertEquals("element1", queue.peek());
	}

	@Test
	@DisplayName("SF-002: 插入重复元素")
	void testInsertDuplicateElement() {
		queue.put("element1");
		String oldValue = queue.put("element1");

		assertEquals("element1", oldValue);
		assertEquals(1, queue.size());
		assertEquals("element1", queue.peek());
	}

	@Test
	@DisplayName("SF-003: 删除元素")
	void testRemoveElement() {
		queue.put("element1");
		queue.put("element2");

		queue.remove("element1");

		assertEquals(1, queue.size());
		assertFalse(queue.contains("element1"));
		assertTrue(queue.contains("element2"));
	}

	@Test
	@DisplayName("SF-004: 删除不存在的元素")
	void testRemoveNonExistentElement() {
		queue.put("element1");

		queue.remove("element3");

		assertEquals(1, queue.size());
		assertTrue(queue.contains("element1"));
	}

	@Test
	@DisplayName("SF-005: 批量取出元素")
	void testDrainElements() {
		queue.put("element1");
		queue.put("element2");
		queue.put("element3");

		List<String> elements = queue.drain(2);

		assertEquals(2, elements.size());
		assertEquals("element1", elements.get(0));
		assertEquals("element2", elements.get(1));
		assertEquals(1, queue.size());
		assertTrue(queue.contains("element3"));
	}

	@Test
	@DisplayName("SF-006: 检查元素是否存在")
	void testCheckElementExists() {
		queue.put("element1");

		assertTrue(queue.contains("element1"));
		assertFalse(queue.contains("element2"));
	}

	@Test
	@DisplayName("SF-007: 检查不存在的元素")
	void testCheckNonExistentElement() {
		assertFalse(queue.contains("element1"));
	}

	@Test
	@DisplayName("SF-008: 获取队列大小")
	void testGetQueueSize() {
		assertEquals(0, queue.size());

		queue.put("element1");
		assertEquals(1, queue.size());

		queue.put("element2");
		assertEquals(2, queue.size());

		queue.remove("element1");
		assertEquals(1, queue.size());
	}

	@Test
	@DisplayName("SF-009: 清空队列")
	void testClearQueue() {
		queue.put("element1");
		queue.put("element2");
		queue.put("element3");

		queue.clear();

		assertEquals(0, queue.size());
		assertFalse(queue.contains("element1"));
		assertFalse(queue.contains("element2"));
		assertFalse(queue.contains("element3"));
	}

	@Test
	@DisplayName("SF-010: 获取队头元素")
	void testPeekHeadElement() {
		queue.put("element1");
		queue.put("element2");

		String head = queue.peek();

		assertEquals("element1", head);
		assertEquals(2, queue.size());
	}

	@Test
	@DisplayName("SF-011: 取出队头元素")
	void testPollHeadElement() {
		queue.put("element1");
		queue.put("element2");

		String head = queue.poll();

		assertEquals("element1", head);
		assertEquals(1, queue.size());
		assertFalse(queue.contains("element1"));
	}

	@Test
	@DisplayName("SF-012: 空队列peek返回null")
	void testPeekEmptyQueue() {
		assertNull(queue.peek());
	}

	@Test
	@DisplayName("SF-013: 空队列poll返回null")
	void testPollEmptyQueue() {
		assertNull(queue.poll());
	}

	@Test
	@DisplayName("SF-014: drain数量为0返回空列表")
	void testDrainZeroElements() {
		queue.put("element1");

		List<String> elements = queue.drain(0);

		assertEquals(0, elements.size());
		assertEquals(1, queue.size());
	}

	@Test
	@DisplayName("SF-015: drain数量大于队列大小")
	void testDrainMoreThanQueueSize() {
		queue.put("element1");
		queue.put("element2");

		List<String> elements = queue.drain(10);

		assertEquals(2, elements.size());
		assertEquals(0, queue.size());
	}

	@Test
	@DisplayName("SF-016: 并发插入测试")
	void testConcurrentInsert() throws InterruptedException {
		int threadCount = 10;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch latch = new CountDownLatch(threadCount);
		AtomicInteger successCount = new AtomicInteger(0);

		for (int i = 0; i < threadCount; i++) {
			final int index = i;
			executor.submit(() -> {
				try {
					queue.put("element-" + index);
					successCount.incrementAndGet();
				}
				finally {
					latch.countDown();
				}
			});
		}

		latch.await(5, TimeUnit.SECONDS);
		executor.shutdown();

		assertEquals(threadCount, successCount.get());
		assertEquals(threadCount, queue.size());
	}

	@Test
	@DisplayName("SF-017: 并发取出测试")
	void testConcurrentDrain() throws InterruptedException {
		for (int i = 0; i < 20; i++) {
			queue.put("element-" + i);
		}

		int threadCount = 5;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch latch = new CountDownLatch(threadCount);
		AtomicInteger totalDrained = new AtomicInteger(0);

		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				try {
					List<String> elements = queue.drain(4);
					if (elements != null) {
						totalDrained.addAndGet(elements.size());
					}
				}
				finally {
					latch.countDown();
				}
			});
		}

		latch.await(5, TimeUnit.SECONDS);
		executor.shutdown();

		assertTrue(totalDrained.get() <= 20);
		assertEquals(0, queue.size());
	}

	@Test
	@DisplayName("SF-018: FIFO顺序验证")
	void testFIFOOrder() {
		queue.put("element1");
		queue.put("element2");
		queue.put("element3");

		assertEquals("element1", queue.poll());
		assertEquals("element2", queue.poll());
		assertEquals("element3", queue.poll());
	}

	@Test
	@DisplayName("SF-019: 重复元素更新位置")
	void testDuplicateElementUpdatePosition() {
		queue.put("element1");
		queue.put("element2");
		queue.put("element1");

		assertEquals("element2", queue.poll());
		assertEquals("element1", queue.poll());
	}

	@Test
	@DisplayName("SF-020: 统计计数器测试")
	void testCounterStatistics() {
		queue.put("element1");
		queue.put("element2");
		queue.put("element3");

		assertEquals(3, queue.getAddCounter());

		queue.drain(2);

		assertEquals(2, queue.getRemoveCounter());
	}

}