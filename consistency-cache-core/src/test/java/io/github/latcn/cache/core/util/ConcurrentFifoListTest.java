package io.github.latcn.cache.core.util;

import static org.junit.jupiter.api.Assertions.*;

import io.github.latcn.cache.core.exception.CacheException;
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

@DisplayName("并发FIFO列表测试")
class ConcurrentFifoListTest {

    private ConcurrentFifoList<String> list;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        list = new ConcurrentFifoList<>(10);
        System.out.println("执行测试: " + testInfo.getDisplayName());
    }

    @Test
    @DisplayName("CF-001: 插入新元素")
    void testInsertNewElement() {
        list.put("element1");

        assertEquals(1, list.size());
        assertTrue(list.getMap().containsKey("element1"));
        assertTrue(list.getInsertionOrder().contains("element1"));
    }

    @Test
    @DisplayName("CF-002: 插入重复元素")
    void testInsertDuplicateElement() {
        list.put("element1");
        String oldValue = list.put("element1");

        assertEquals("element1", oldValue);
        assertEquals(1, list.size());
    }

    @Test
    @DisplayName("CF-003: 达到容量后插入")
    void testInsertWhenCapacityReached() {
        for (int i = 0; i < 10; i++) {
            list.put("element-" + i);
        }

        assertEquals(10, list.size());

        assertThrows(CacheException.class, () -> {
            list.put("element-11");
        });
    }

    @Test
    @DisplayName("CF-004: 批量取出元素")
    void testDrainAllElements() {
        for (int i = 0; i < 5; i++) {
            list.put("element-" + i);
        }

        List<String> elements = list.drainAll(3);

        assertNotNull(elements);
        assertEquals(3, elements.size());
        assertEquals(2, list.size());
    }

    @Test
    @DisplayName("CF-005: 获取列表大小")
    void testGetListSize() {
        assertEquals(0, list.size());

        list.put("element1");
        assertEquals(1, list.size());

        list.put("element2");
        assertEquals(2, list.size());
    }

    @Test
    @DisplayName("CF-006: 非法容量参数")
    void testInvalidCapacityParameter() {
        assertThrows(CacheException.class, () -> {
            new ConcurrentFifoList<>(0);
        });

        assertThrows(CacheException.class, () -> {
            new ConcurrentFifoList<>(-1);
        });
    }

    @Test
    @DisplayName("CF-007: drainAll空列表返回null")
    void testDrainAllEmptyList() {
        List<String> elements = list.drainAll(5);

        assertNull(elements);
        assertEquals(0, list.size());
    }

    @Test
    @DisplayName("CF-008: drainAll数量大于列表大小")
    void testDrainAllMoreThanListSize() {
        for (int i = 0; i < 3; i++) {
            list.put("element-" + i);
        }

        List<String> elements = list.drainAll(10);

        assertNotNull(elements);
        assertEquals(3, elements.size());
        assertEquals(0, list.size());
    }

    @Test
    @DisplayName("CF-009: FIFO顺序验证")
    void testFIFOOrder() {
        list.put("element1");
        list.put("element2");
        list.put("element3");

        List<String> elements = list.drainAll(3);

        assertNotNull(elements);
        assertEquals("element1", elements.get(0));
        assertEquals("element2", elements.get(1));
        assertEquals("element3", elements.get(2));
    }

    @Test
    @DisplayName("CF-010: 重复元素更新位置")
    void testDuplicateElementUpdatePosition() {
        list.put("element1");
        list.put("element2");
        list.put("element1");

        List<String> elements = list.drainAll(2);

        assertNotNull(elements);
        assertEquals("element2", elements.get(0));
        assertEquals("element1", elements.get(1));
    }

    @Test
    @DisplayName("CF-011: 并发插入测试")
    void testConcurrentInsert() throws InterruptedException {
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    list.put("element-" + index);
                    successCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(threadCount, successCount.get());
        assertEquals(threadCount, list.size());
    }

    @Test
    @DisplayName("CF-012: 并发取出测试")
    void testConcurrentDrainAll() throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            list.put("element-" + i);
        }

        int threadCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger totalDrained = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    List<String> elements = list.drainAll(3);
                    if (elements != null) {
                        totalDrained.addAndGet(elements.size());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(totalDrained.get() <= 10);
        assertEquals(0, list.size());
    }

    @Test
    @DisplayName("CF-013: 统计计数器测试")
    void testCounterStatistics() {
        for (int i = 0; i < 5; i++) {
            list.put("element-" + i);
        }

        assertEquals(5, list.getAddCounter());

        list.drainAll(3);

        assertEquals(3, list.getRemoveCounter());
    }

    @Test
    @DisplayName("CF-014: 容量边界测试")
    void testCapacityBoundary() {
        ConcurrentFifoList<String> smallList = new ConcurrentFifoList<>(1);

        smallList.put("element1");
        assertEquals(1, smallList.size());

        assertThrows(CacheException.class, () -> {
            smallList.put("element2");
        });
    }

    @Test
    @DisplayName("CF-015: 部分drainAll测试")
    void testPartialDrainAll() {
        for (int i = 0; i < 10; i++) {
            list.put("element-" + i);
        }

        List<String> firstBatch = list.drainAll(5);
        List<String> secondBatch = list.drainAll(5);

        assertNotNull(firstBatch);
        assertNotNull(secondBatch);
        assertEquals(5, firstBatch.size());
        assertEquals(5, secondBatch.size());
        assertEquals(0, list.size());
    }

    @Test
    @DisplayName("CF-016: Map和Queue一致性测试")
    void testMapQueueConsistency() {
        list.put("element1");
        list.put("element2");
        list.put("element3");

        assertEquals(list.getMap().size(), list.getInsertionOrder().size());
        assertEquals(3, list.getMap().size());
        assertEquals(3, list.getInsertionOrder().size());

        assertTrue(list.getMap().containsKey("element1"));
        assertTrue(list.getMap().containsKey("element2"));
        assertTrue(list.getMap().containsKey("element3"));

        assertTrue(list.getInsertionOrder().contains("element1"));
        assertTrue(list.getInsertionOrder().contains("element2"));
        assertTrue(list.getInsertionOrder().contains("element3"));
    }

    @Test
    @DisplayName("CF-017: 删除后一致性测试")
    void testConsistencyAfterDrain() {
        for (int i = 0; i < 5; i++) {
            list.put("element-" + i);
        }

        list.drainAll(3);

        assertEquals(list.getMap().size(), list.getInsertionOrder().size());
        assertEquals(2, list.getMap().size());
        assertEquals(2, list.getInsertionOrder().size());
    }

    @Test
    @DisplayName("CF-018: 重复元素计数测试")
    void testDuplicateElementCount() {
        list.put("element1");
        list.put("element1");
        list.put("element2");

        assertEquals(2, list.size());
        assertEquals(3, list.getAddCounter());
    }
}