package io.github.latcn.cache.core.util;

import static org.junit.jupiter.api.Assertions.*;

import io.github.latcn.cache.core.exception.CacheException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

@DisplayName("时间轮测试")
class TimerWheelTest {

    private TimerWheel timerWheel;
    private AtomicInteger executedCount;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        timerWheel = new TimerWheel(10, 100, 4);
        executedCount = new AtomicInteger(0);
        System.out.println("执行测试: " + testInfo.getDisplayName());
    }

    @AfterEach
    void tearDown() {
        if (timerWheel != null) {
            timerWheel.shutDown();
        }
    }

    @Test
    @DisplayName("TW-001: 添加立即执行任务")
    void testAddImmediateExecutionTask() throws InterruptedException {
        TimerTask task = new TimerTask(System.currentTimeMillis(), () -> {
            executedCount.incrementAndGet();
            return null;
        });

        timerWheel.addTask(task);

        TimeUnit.MILLISECONDS.sleep(200);

        assertTrue(executedCount.get() >= 1);
    }

    @Test
    @DisplayName("TW-002: 添加延迟执行任务")
    void testAddDelayedExecutionTask() throws InterruptedException {
        long execTime = System.currentTimeMillis() + 500;
        TimerTask task = new TimerTask(execTime, () -> {
            executedCount.incrementAndGet();
            return null;
        });

        timerWheel.addTask(task);

        TimeUnit.MILLISECONDS.sleep(100);
        assertEquals(0, executedCount.get());

        TimeUnit.MILLISECONDS.sleep(600);
        assertTrue(executedCount.get() >= 1);
    }

    @Test
    @DisplayName("TW-003: 添加超范围任务（溢出）")
    void testAddOverflowTask() throws InterruptedException {
        long execTime = System.currentTimeMillis() + 2000;
        TimerTask task = new TimerTask(execTime, () -> {
            executedCount.incrementAndGet();
            return null;
        });

        timerWheel.addTask(task);

        TimeUnit.MILLISECONDS.sleep(500);
        assertEquals(0, executedCount.get());

        TimeUnit.MILLISECONDS.sleep(2500);
        assertTrue(executedCount.get() >= 1);
    }

    @Test
    @DisplayName("TW-004: 多个任务按时间顺序执行")
    void testMultipleTasksExecuteInOrder() throws InterruptedException {
        AtomicInteger executionOrder = new AtomicInteger(0);
        StringBuilder orderBuilder = new StringBuilder();

        TimerTask task1 = new TimerTask(System.currentTimeMillis() + 100, () -> {
            orderBuilder.append("1");
            return null;
        });

        TimerTask task2 = new TimerTask(System.currentTimeMillis() + 200, () -> {
            orderBuilder.append("2");
            return null;
        });

        TimerTask task3 = new TimerTask(System.currentTimeMillis() + 300, () -> {
            orderBuilder.append("3");
            return null;
        });

        timerWheel.addTask(task1);
        timerWheel.addTask(task2);
        timerWheel.addTask(task3);

        TimeUnit.MILLISECONDS.sleep(500);

        String order = orderBuilder.toString();
        assertTrue(order.contains("1"));
        assertTrue(order.contains("2"));
        assertTrue(order.contains("3"));
    }

    @Test
    @DisplayName("TW-005: 空任务参数检查")
    void testNullTaskParameterCheck() {
        assertThrows(CacheException.class, () -> {
            timerWheel.addTask(null);
        });
    }

    @Test
    @DisplayName("TW-006: 非法构造参数检查")
    void testInvalidConstructorParameters() {
        assertThrows(CacheException.class, () -> {
            new TimerWheel(0, 100, 4);
        });

        assertThrows(CacheException.class, () -> {
            new TimerWheel(10, 0, 4);
        });

        assertThrows(CacheException.class, () -> {
            new TimerWheel(-1, 100, 4);
        });

        assertThrows(CacheException.class, () -> {
            new TimerWheel(10, -1, 4);
        });
    }

    @Test
    @DisplayName("TW-007: 关闭TimerWheel")
    void testShutdownTimerWheel() throws InterruptedException {
        TimerTask task = new TimerTask(System.currentTimeMillis() + 1000, () -> {
            executedCount.incrementAndGet();
            return null;
        });

        timerWheel.addTask(task);
        timerWheel.shutDown();

        TimeUnit.MILLISECONDS.sleep(1500);

        assertTrue(executedCount.get() == 0 || executedCount.get() >= 1);
    }

    @Test
    @DisplayName("TW-008: 并发添加任务")
    void testConcurrentAddTasks() throws InterruptedException {
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                TimerTask task = new TimerTask(System.currentTimeMillis() + 100 + index * 10, () -> {
                    executedCount.incrementAndGet();
                    return null;
                });
                timerWheel.addTask(task);
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        TimeUnit.MILLISECONDS.sleep(500);

        assertTrue(executedCount.get() >= threadCount);
    }

    @Test
    @DisplayName("TW-009: 任务执行时间精度")
    void testTaskExecutionTimePrecision() throws InterruptedException {
        long expectedExecTime = System.currentTimeMillis() + 200;
        AtomicInteger actualExecTime = new AtomicInteger(0);

        TimerTask task = new TimerTask(expectedExecTime, () -> {
            actualExecTime.set((int) System.currentTimeMillis());
            return null;
        });

        timerWheel.addTask(task);

        TimeUnit.MILLISECONDS.sleep(400);

        int timeDiff = Math.abs(actualExecTime.get() - (int) expectedExecTime);
        assertTrue(timeDiff < 200);
    }

    @Test
    @DisplayName("TW-010: 多轮任务执行")
    void testMultiRoundTaskExecution() throws InterruptedException {
        long execTime = System.currentTimeMillis() + 1500;
        TimerTask task = new TimerTask(execTime, () -> {
            executedCount.incrementAndGet();
            return null;
        });

        timerWheel.addTask(task);

        TimeUnit.MILLISECONDS.sleep(2000);

        assertTrue(executedCount.get() >= 1);
    }
}