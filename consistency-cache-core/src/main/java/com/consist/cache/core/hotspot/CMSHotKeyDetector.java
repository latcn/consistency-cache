package com.consist.cache.core.hotspot;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Count-Min Sketch 是一种概率型数据结构，专门用于稀疏数据流的频率统计。
 * 核心思想是：用空间换时间，且用极小的空间换取可接受的误差。它非常适合在 Java 内存中存储海量 Key 的计数，
 *  而不需要为每个 Key 单独开辟一个对象。
 * CMS 的内部结构是一个二维矩阵（网格），由两部分参数决定：
 *   a. 深度 (d)：矩阵的行数。代表使用了多少个不同的哈希函数。
 *   b. 宽度 (w)：矩阵的列数。代表哈希后的桶的数量。
 * 写入操作：
 *   假设我们要统计 Key "Apple" 的出现次数，系统会使用d个不同的哈希函数，
 *   例如将 "Apple" 哈希成d个索引位置, 将这 d 个位置的计数器都加1。
 * 读取操作：
 *   当我们需要查询 "Apple" 的频率时，同样的d个哈希函数映射到位置，关键取这d个值中的最小值；
 *   在写入时，不同的 Key 可能会哈希到同一个位置（发生碰撞，导致计数偏大），
 *   为了修正误差，我们取最小值。这个最小值代表：只有在这d个哈希函数中，"Apple" 都落在了"最空闲"或"最少碰撞"的位置上，
 *   实际上，因为hash碰撞，这个桶最小值总是 >= 这个key的真实值
 * 误差分析：
 *    CMS 的最大误差概率（误判率）取决于宽度 w: error Probability = 1/(e*w)。
 *
 * 高性能、并发安全的 Count-Min Sketch3 热点检测器
 * 特性：
 * 1. 并发安全：基于 AtomicLongArray，无锁高性能。
 * 2. 精度修正：修复了初始化偏差，支持冷 Key 识别。
 * 3. 稳定衰减：使用 Math.round 确保衰减精度。
 * 4. Hash 优化：结合种子和位运算，分布更均匀。
 */
public class CMSHotKeyDetector {

    /**
     * CMS 参数
     * width: 桶数量
     * depth: 哈希函数数量
     * seeds: 哈希种子
     */
    private final int width;
    private final int depth;
    private final AtomicLongArray[] counters;
    private final int[] seeds;
    /**
     * 滑动窗口参数
     *  decayRate: 衰减率 (0.90 ~ 0.99)
     *  threshold: 热点阈值
     *  batchSize: 每次随机衰减的桶数量
     */
    private final float decayRate;
    private final int threshold;
    private final int batchSize;

    // 随机数生成器 (使用 ThreadLocalRandom 提高多线程性能)
    private final ThreadLocalRandom random = ThreadLocalRandom.current();

    /**
     * 构造函数
     * @param width 宽度 (建议 10000-1000000)
     * @param depth 深度 (建议 5-10)
     * @param decayRate 衰减率 (0.0-1.0)，例如 0.90 表示每次衰减 10%
     * @param threshold 热点阈值
     */
    public CMSHotKeyDetector(int width, int depth, float decayRate, int threshold) {
        this.width = width;
        this.depth = depth;
        this.decayRate = decayRate;
        this.threshold = threshold;
        this.batchSize = Math.max(1, width / 100); // 每次衰减约 1% 的桶
        // 初始化种子
        this.seeds = new int[depth];
        for (int i = 0; i < depth; i++) {
            this.seeds[i] = (int) (Math.random() * Integer.MAX_VALUE);
        }
        // 初始化计数器数组 (使用 AtomicLongArray 保证并发安全)
        this.counters = new AtomicLongArray[depth];
        for (int i = 0; i < depth; i++) {
            this.counters[i] = new AtomicLongArray(width);
        }
    }
    /**
     * 写入 Key (记录一次读写)
     * 时间复杂度: O(d) 其中 d 是深度
     */
    public void record(String key) {
        // 1. 增加计数
        long[] indices = hash(key);
        for (int i = 0; i < depth; i++) {
            int index = (int) indices[i];
            // 原子自增
            counters[i].getAndAdd(index, 1);
        }
        // 2. 批量衰减 (模拟时间流逝)
        decay();
    }
    /**
     * 获取 Key 的估算频率
     * 时间复杂度: O(d)
     */
    public long estimateCount(String key) {
        long[] indices = hash(key);
        long min = Long.MAX_VALUE;
        for (int i = 0; i < depth; i++) {
            int index = (int) indices[i];
            long val = counters[i].get(index);
            min = Math.min(min, val);
        }
        // min == Long.MAX_VALUE 极其罕见 (除非初始化错误)，返回 0 表示未见
        return (min == Long.MAX_VALUE) ? 0 : min;
    }
    /**
     * 判断是否为热点 Key
     */
    public boolean isHotKey(String key) {
        return estimateCount(key) >= threshold;
    }
    /**
     * 哈希函数：生成 d 个索引
     * 优化点：结合种子和位运算扰动
     */
    private long[] hash(String key) {
        long[] indices = new long[depth];
        int h = key.hashCode();
        for (int i = 0; i < depth; i++) {
            // 关键优化：将种子加入哈希计算，保证不同种子的结果不同
            h ^= seeds[i];
            // 位运算扰动，分散哈希值
            h ^= (h >>> 20) ^ (h >>> 12);
            h = h ^ (h >>> 7) ^ (h >>> 4);
            // 取模并转为正数
            indices[i] = (h & 0x7fffffffL) % width;
        }
        return indices;
    }
    /**
     * 批量衰减计数器
     * 逻辑：随机选取 batchSize 个桶，进行指数衰减
     */
    private void decay() {
        for (int k = 0; k < batchSize; k++) {
            // 随机选取一个桶
            int row = random.nextInt(depth);
            int col = random.nextInt(width);
            // 原子读取
            long current = counters[row].get(col);
            // 防止溢出和无效计算
            if (current <= 0) continue;
            // 指数衰减：使用 Math.round 保证精度
            // 例如：100 * 0.95 = 95.0 -> 95
            //      1 * 0.95 = 0.95 -> 1
            long nextVal = Math.round(current * decayRate);
            // 优化：如果衰减后小于等于 0，直接归零，减少后续计算量
            if (nextVal <= 0) {
                counters[row].set(col, 0);
            } else {
                counters[row].set(col, nextVal);
            }
        }
    }
    /**
     * 获取当前内部计数器大小 (仅供测试或监控使用)
     */
    public int getWidth() {
        return width;
    }
    public int getDepth() {
        return depth;
    }
}
