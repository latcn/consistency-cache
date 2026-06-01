package io.github.latcn.cache.core.hotspot;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CMSHotKeyDetectorTest {

    @Test
    public void hotKeyDetector() throws InterruptedException {
        // 配置参数
        int width = 10000;
        int depth = 5;
        float decayRate = 0.90f; // 衰减较快，便于观察效果
        int threshold = 500;

        CMSHotKeyDetector hotKeyDetector = new CMSHotKeyDetector(width, depth, decayRate, threshold);

        System.out.println("========== 并发安全与精度测试 ==========\n");

        // 1. 初始冷 Key 测试 (验证初始化为0)
        System.out.println("--- 测试冷 Key (未访问过的 Key) ---");
        System.out.println("Key-Null 估算值: " + hotKeyDetector.estimateCount("Key-Null")); // 应该是 0
        System.out.println("Key-Null 是否热点: " + hotKeyDetector.isHotKey("Key-Null"));    // false

        // 2. 并发写入测试 (验证原子性)
        System.out.println("\n--- 并发写入 10000 次 Key-A (10个线程) ---");
        int threadCount = 10;
        int writePerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        long start = System.currentTimeMillis();
        for (int i = 0; i < threadCount; i++) {
            executor.execute(() -> {
                for (int j = 0; j < writePerThread; j++) {
                    hotKeyDetector.record("Key-A");
                }
                latch.countDown();
            });
        }
        latch.await(); // 等待所有线程完成
        long end = System.currentTimeMillis();
        executor.shutdown();

        long finalCount = hotKeyDetector.estimateCount("Key-A");
        System.out.println("Key-A 最终估算值: " + finalCount);
        System.out.println("预期值: " + (threadCount * writePerThread));
        System.out.println("误差率: " + (Math.abs(finalCount - threadCount * writePerThread) * 100.0 / (threadCount * writePerThread)) + "%");
        System.out.println("线程安全校验: " + (finalCount == threadCount * writePerThread ? "通过" : "失败"));

        // 3. 衰减测试 (模拟时间流逝)
        System.out.println("\n--- 衰减测试 (时间流逝 20 单位) ---");
        System.out.println("T-0 时刻: " + hotKeyDetector.estimateCount("Key-A"));

        for (int t = 0; t < 20; t++) {
            // 保持写入以模拟持续流量，观察 T-20 时刻的存量
            hotKeyDetector.record("Key-A");
            long current = hotKeyDetector.estimateCount("Key-A");
            if (t % 5 == 0) {
                System.out.printf("T-%d 时刻: %d (衰减率: %.2f)\n", t, current, Math.pow(decayRate, t));
            }
        }

        System.out.println("\n--- 衰减测试结束 ---");
        System.out.println("T-20 时刻: " + hotKeyDetector.estimateCount("Key-A"));

        // 4. 热点判定测试
        System.out.println("\n--- 热点判定 ---");
        System.out.println("Key-A (频繁写入) 是否热点: " + hotKeyDetector.isHotKey("Key-A")); // true
        System.out.println("Key-Null (未访问) 是否热点: " + hotKeyDetector.isHotKey("Key-Null")); // false
    }
}
