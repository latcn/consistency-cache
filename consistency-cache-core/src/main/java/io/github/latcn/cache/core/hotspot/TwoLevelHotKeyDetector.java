package io.github.latcn.cache.core.hotspot;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;

/**
 * 两级热点检测器（最终优化版）
 *
 * <h2>算法设计思路</h2>
 * 采用“粗筛 + 精确跟踪”的两级架构，兼顾内存与准确性：
 * <ul>
 *   <li><b>第一级（CMS 层）</b>：使用 Count-Min Sketch 存储所有 key 的粗略计数，内存固定。
 *       作为“过滤器”，快速识别出可能成为热点的候选 key。</li>
 *   <li><b>第二级（精确层）</b>：使用 ConcurrentHashMap 存储候选 key 的精确计数（double 精度），
 *       并采用惰性衰减策略，实时反映访问热度。</li>
 * </ul>
 *
 * <p>工作流程：
 * <ol>
 *   <li>每次访问 key 时，先检查精确层是否存在，若存在则更新精确计数。</li>
 *   <li>若精确层不存在，则在 CMS 中记录，并查询 CMS 估计值。</li>
 *   <li>若 CMS 估计值超过 {@code promotionThreshold}，则将该 key 提升到精确层，并立即计入本次访问。</li>
 *   <li>精确层定期清理低于 {@code hotKeyThreshold} 且过期的 key。</li>
 *   <li>当精确层容量满时，触发强制清理，淘汰计数最低的 key。</li>
 * </ol>
 *
 * <h2>参数计算与预估</h2>
 * <ul>
 *   <li><b>promotionThreshold</b>：建议设为 hotKeyThreshold 的 0.7～0.8，避免热点漏判，
 *       同时防止非热点频繁晋升浪费内存。</li>
 *   <li><b>hotKeyThreshold</b>：业务定义的热点阈值（如每秒访问次数）。需结合业务流量预估。</li>
 *   <li><b>exactCounterDecayRate</b>：精确层每秒衰减率，建议 0.01～0.05。
 *       越大则历史计数衰减越快，热点判定更偏向近期。</li>
 *   <li><b>maxExactSize</b>：精确层最大容量，根据内存和热点数量设置，一般设为热点数量的 2～3 倍。</li>
 *   <li><b>expirationTimeMs</b>：key 过期时间，超过此时间且计数低于阈值的 key 将被清理。</li>
 *   <li><b>forceCleanupCooldownMs</b>：强制清理冷却时间，防止频繁触发排序，影响性能。</li>
 * </ul>
 *
 * <h2>调优建议</h2>
 * <ul>
 *   <li>若热点 Key 数量已知，可精确设置 maxExactSize 和 promotionThreshold。</li>
 *   <li>若业务流量波动大，可动态调整 promotionThreshold 和 hotKeyThreshold。</li>
 *   <li>监控精确层大小和晋升/淘汰次数，调整容量和阈值。</li>
 * </ul>
 */
@Slf4j
public class TwoLevelHotKeyDetector implements AutoCloseable {

    // 第一级：CMS 粗筛层
    private final CMSHotKeyDetector cmsFilter;

    // 第二级：精确计数器（ConcurrentHashMap 存储 KeyStats）
    private final ConcurrentHashMap<String, KeyStats> exactCounter;

    // 将 key 从 CMS 提升到精确层的阈值（建议 hotKeyThreshold 的 70%～80%）
    private volatile long promotionThreshold;

    // 判断是否为热点 key 的阈值（业务定义，如 100 次/秒）
    private volatile long hotKeyThreshold;

    // 精确层每秒衰减率（例如 0.02 表示每秒衰减 2%）
    private final double exactCounterDecayRate;

    // key 过期时间（毫秒），超过此时间且计数低于 hotKeyThreshold 则被清理
    private final long expirationTimeMs;

    // 精确层常规清理任务执行间隔（毫秒）
    private final long cleanupIntervalMs;

    // 精确层最大容量，超出时触发强制清理
    private final int maxExactSize;

    // 强制清理冷却时间（毫秒），防止频繁排序
    private volatile long forceCleanupCooldownMs;

    // 精确层清理调度器
    private final ScheduledExecutorService cleanupScheduler;

    // 上次强制清理的纳秒时间戳，用于冷却控制
    private final AtomicLong lastForceCleanupTime = new AtomicLong(System.nanoTime());

    // 强制清理锁，防止并发执行
    private final ReentrantLock forceCleanupLock = new ReentrantLock();

    // 关闭标志
    private volatile boolean closed = false;

    // ========== 监控指标 ==========
    private final AtomicLong promotedCount = new AtomicLong(0);           // 晋升到精确层的 key 数量
    private final AtomicLong evictedCount = new AtomicLong(0);            // 被强制淘汰的 key 数量
    private final AtomicLong hotHitCount = new AtomicLong(0);             // isHotKey 返回 true 的次数
    private final AtomicLong hotMissCount = new AtomicLong(0);            // isHotKey 返回 false 的次数
    private final AtomicLong forceCleanupCount = new AtomicLong(0);       // 强制清理执行次数
    private final AtomicLong forceCleanupSkipCooldown = new AtomicLong(0); // 因冷却而跳过的强制清理次数

    // ===================== Builder =====================
    /**
     * 构建器，提供所有可配置参数，并提供预设推荐配置（forHighQps）。
     *
     * <p>参数默认值（用户未设置时）：
     * <ul>
     *   <li>cmsWidth = 1,000,000</li>
     *   <li>cmsDepth = 3</li>
     *   <li>cmsDecayRate = 0.15</li>
     *   <li>cmsDecayIntervalMs = 500</li>
     *   <li>cmsSegmentCount = 4</li>
     *   <li>exactCounterDecayRate = 0.02</li>
     *   <li>expirationTimeMs = 30000</li>
     *   <li>cleanupIntervalMs = 5000</li>
     *   <li>maxExactSize = 20000</li>
     *   <li>forceCleanupCooldownMs = 10000</li>
     * </ul>
     */
    public static class Builder {
        // CMS 参数
        private Integer cmsWidth = 1_000_000;
        private Integer cmsDepth = 3;
        private Double cmsDecayRate = 0.15;
        private Long cmsDecayIntervalMs = 500L;
        private Integer cmsSegmentCount = 4;

        // 两级协同参数（必须由用户设置）
        private Long promotionThreshold = null;
        private Long hotKeyThreshold = null;

        // 精确层参数
        private Double exactCounterDecayRate = 0.02;
        private Long expirationTimeMs = 30000L;
        private Long cleanupIntervalMs = 5000L;
        private Integer maxExactSize = 20000;
        private Long forceCleanupCooldownMs = 10000L;

        /** 预设高 QPS 推荐配置（适用于热 key 1万，QPS 10万，误差 1%） */
        public static Builder forHighQps() {
            return new Builder()
                    .cmsWidth(1_000_000)
                    .cmsDepth(3)
                    .cmsDecayRate(0.15)
                    .cmsDecayIntervalMs(500L)
                    .cmsSegmentCount(4)
                    .exactCounterDecayRate(0.02)
                    .expirationTimeMs(30000L)
                    .cleanupIntervalMs(5000L)
                    .maxExactSize(20000)
                    .forceCleanupCooldownMs(10000L);
        }

        public Builder cmsWidth(int width) { this.cmsWidth = width; return this; }
        public Builder cmsDepth(int depth) { this.cmsDepth = depth; return this; }
        public Builder cmsDecayRate(double rate) { this.cmsDecayRate = rate; return this; }
        public Builder cmsDecayIntervalMs(long interval) { this.cmsDecayIntervalMs = interval; return this; }
        public Builder cmsSegmentCount(int count) { this.cmsSegmentCount = count; return this; }
        public Builder promotionThreshold(long threshold) { this.promotionThreshold = threshold; return this; }
        public Builder hotKeyThreshold(long threshold) { this.hotKeyThreshold = threshold; return this; }
        public Builder exactCounterDecayRate(double rate) { this.exactCounterDecayRate = rate; return this; }
        public Builder expirationTimeMs(long time) { this.expirationTimeMs = time; return this; }
        public Builder cleanupIntervalMs(long interval) { this.cleanupIntervalMs = interval; return this; }
        public Builder maxExactSize(int size) { this.maxExactSize = size; return this; }
        public Builder forceCleanupCooldownMs(long cooldown) { this.forceCleanupCooldownMs = cooldown; return this; }

        public TwoLevelHotKeyDetector build() {
            if (promotionThreshold == null || hotKeyThreshold == null) {
                throw new IllegalStateException("promotionThreshold and hotKeyThreshold must be set");
            }
            if (promotionThreshold <= 0 || hotKeyThreshold <= 0 || promotionThreshold >= hotKeyThreshold) {
                throw new IllegalArgumentException("Invalid thresholds");
            }
            return new TwoLevelHotKeyDetector(this);
        }
    }

    private TwoLevelHotKeyDetector(Builder b) {
        this.cmsFilter = new CMSHotKeyDetector(
                b.cmsWidth, b.cmsDepth, b.cmsDecayRate, b.cmsDecayIntervalMs, b.cmsSegmentCount);
        this.exactCounter = new ConcurrentHashMap<>();
        this.promotionThreshold = b.promotionThreshold;
        this.hotKeyThreshold = b.hotKeyThreshold;
        this.exactCounterDecayRate = b.exactCounterDecayRate;
        this.expirationTimeMs = b.expirationTimeMs;
        this.cleanupIntervalMs = b.cleanupIntervalMs;
        this.maxExactSize = b.maxExactSize;
        this.forceCleanupCooldownMs = b.forceCleanupCooldownMs;
        // 初始化冷却时间为过去，确保第一次可执行
        this.lastForceCleanupTime.set(System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(forceCleanupCooldownMs));

        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("HotKey-Cleanup");
            t.setUncaughtExceptionHandler((thread, ex) ->
                    log.error("Cleanup thread died", ex));
            return t;
        });
        this.cleanupScheduler.scheduleAtFixedRate(this::safeCleanup, cleanupIntervalMs, cleanupIntervalMs, TimeUnit.MILLISECONDS);
    }

    // ========== 动态调整接口 ==========

    /** 动态调整晋升阈值（必须小于 hotKeyThreshold 且大于 0） */
    public void setPromotionThreshold(long threshold) {
        if (threshold <= 0 || threshold >= hotKeyThreshold) {
            throw new IllegalArgumentException("Invalid promotionThreshold");
        }
        this.promotionThreshold = threshold;
    }

    /** 动态调整热点阈值（必须大于 promotionThreshold 且大于 0） */
    public void setHotKeyThreshold(long threshold) {
        if (threshold <= 0 || promotionThreshold >= threshold) {
            throw new IllegalArgumentException("Invalid hotKeyThreshold");
        }
        this.hotKeyThreshold = threshold;
    }

    /** 动态调整强制清理冷却时间（毫秒） */
    public void setForceCleanupCooldownMs(long cooldownMs) {
        if (cooldownMs <= 0) throw new IllegalArgumentException("cooldown must be positive");
        this.forceCleanupCooldownMs = cooldownMs;
    }

    // ========== 核心业务方法 ==========

    /**
     * 记录一次访问。
     * 若 key 在精确层，直接更新精确计数（惰性衰减 +1）。
     * 否则在 CMS 中记录，若 CMS 估计值达到晋升阈值，则尝试提升到精确层。
     * 若精确层已满，尝试强制清理释放空间。
     */
    public void record(String key) {
        if (closed) throw new IllegalStateException("Closed");
        Objects.requireNonNull(key, "key cannot be null");
        long nowNano = System.nanoTime();

        KeyStats stats = exactCounter.get(key);
        if (stats != null) {
            stats.record(exactCounterDecayRate, nowNano);
            return;
        }

        cmsFilter.record(key);
        if (cmsFilter.estimateCount(key) >= promotionThreshold) {
            if (exactCounter.size() >= maxExactSize) {
                forceCleanupIfNeeded(nowNano);
                if (exactCounter.size() >= maxExactSize) {
                    return; // 容量仍然满，放弃晋升
                }
            }
            KeyStats newStats = new KeyStats(0, nowNano);
            KeyStats existing = exactCounter.putIfAbsent(key, newStats);
            if (existing == null) {
                // 成功放入，计入本次访问
                newStats.record(exactCounterDecayRate, nowNano);
                promotedCount.incrementAndGet();
            } else {
                // 其他线程已放入，直接计入
                existing.record(exactCounterDecayRate, nowNano);
            }
        }
    }

    /**
     * 判断 key 是否为热点。
     * 仅查询精确层，若不存在则返回 false。
     * 查询时先对计数进行惰性衰减（但不增加），再与 hotKeyThreshold 比较。
     */
    public boolean isHotKey(String key) {
        if (closed) throw new IllegalStateException("Closed");
        Objects.requireNonNull(key, "key cannot be null");
        KeyStats stats = exactCounter.get(key);
        if (stats == null) {
            hotMissCount.incrementAndGet();
            return false;
        }
        long nowNano = System.nanoTime();
        long current = stats.getCount(exactCounterDecayRate, nowNano);
        boolean hot = current >= hotKeyThreshold;
        if (hot) hotHitCount.incrementAndGet();
        else hotMissCount.incrementAndGet();
        return hot;
    }

    // ========== 监控指标 ==========
    public long getPromotedCount() { return promotedCount.get(); }
    public long getEvictedCount() { return evictedCount.get(); }
    public long getHotHitCount() { return hotHitCount.get(); }
    public long getHotMissCount() { return hotMissCount.get(); }
    public int getExactSize() { return exactCounter.size(); }
    public long getForceCleanupCount() { return forceCleanupCount.get(); }
    public long getForceCleanupSkipCooldown() { return forceCleanupSkipCooldown.get(); }

    // ========== 强制清理（带冷却、锁、采样） ==========

    /** 尝试执行强制清理，若满足冷却条件且未锁，则执行 */
    private void forceCleanupIfNeeded(long nowNano) {
        if (!forceCleanupLock.tryLock()) return;
        try {
            long last = lastForceCleanupTime.get();
            long cooldownNanos = TimeUnit.MILLISECONDS.toNanos(forceCleanupCooldownMs);
            if (nowNano - last < cooldownNanos) {
                forceCleanupSkipCooldown.incrementAndGet();
                return;
            }
            if (!lastForceCleanupTime.compareAndSet(last, nowNano)) return;
            forceCleanup(nowNano);
        } finally {
            forceCleanupLock.unlock();
        }
    }

    /**
     * 强制清理：先执行常规清理，若仍超容量，则淘汰最低计数的 key。
     * 为防止全量排序开销过大，当精确层条目 > 5000 时，随机采样 2000 个进行排序淘汰。
     * 采样淘汰可能不是最优，但在容量紧急时能快速释放空间。
     */
    private void forceCleanup(long nowNano) {
        doCleanup(nowNano);
        if (exactCounter.size() < maxExactSize) return;

        // 计算需要删除的数量：超出部分 + 额外 100 或 10% 缓冲
        int toRemove = exactCounter.size() - maxExactSize + Math.min(100, maxExactSize / 10);
        if (toRemove <= 0) return;

        // 采样：若条目 > 5000，随机采样 2000 个；否则全量
        List<Map.Entry<String, KeyStats>> entries = new ArrayList<>(exactCounter.entrySet());
        if (entries.size() > 5000) {
            Collections.shuffle(entries);
            entries = entries.subList(0, Math.min(entries.size(), 2000));
        }

        // 构建可排序的 Pair 列表（预计算计数，避免重复计算）
        List<Pair> pairs = new ArrayList<>(entries.size());
        for (Map.Entry<String, KeyStats> entry : entries) {
            long cnt = entry.getValue().getCount(exactCounterDecayRate, nowNano);
            long time = entry.getValue().getLastAccessNano();
            pairs.add(new Pair(entry.getKey(), entry.getValue(), cnt, time));
        }
        // 按计数升序，再按访问时间升序（越旧越优先淘汰）
        pairs.sort(Comparator.comparingLong((Pair a) -> a.count).thenComparingLong(a -> a.lastAccessNano));

        int removed = 0;
        for (Pair p : pairs) {
            if (removed >= toRemove) break;
            if (exactCounter.remove(p.key, p.stats)) {
                removed++;
                evictedCount.incrementAndGet();
            }
        }
        forceCleanupCount.incrementAndGet();
        log.info("Force cleanup removed {} keys, current size={}", removed, exactCounter.size());
    }

    // 辅助 Pair 类
    private static class Pair {
        final String key;
        final KeyStats stats;
        final long count;
        final long lastAccessNano;
        Pair(String key, KeyStats stats, long count, long lastAccessNano) {
            this.key = key; this.stats = stats; this.count = count; this.lastAccessNano = lastAccessNano;
        }
    }

    // ========== 常规清理（定期执行） ==========

    private void safeCleanup() {
        if (closed) return;
        try {
            doCleanup(System.nanoTime());
        } catch (Throwable t) {
            log.error("Cleanup task failed", t);
        }
    }

    /** 遍历精确层，删除计数低于 hotKeyThreshold 且过期的 key */
    private void doCleanup(long nowNano) {
        exactCounter.entrySet().removeIf(entry -> {
            KeyStats stats = entry.getValue();
            long current = stats.getCount(exactCounterDecayRate, nowNano);
            return current < hotKeyThreshold && (nowNano - stats.getLastAccessNano() > TimeUnit.MILLISECONDS.toNanos(expirationTimeMs));
        });
    }

    @Override
    public void close() {
        closed = true;
        // 先关闭清理器，再关闭 CMS（避免 CMS 阻塞影响清理器关闭）
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
                if (!cleanupScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    log.warn("Cleanup executor did not terminate");
                }
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        cmsFilter.close();
        exactCounter.clear();
    }

    // ========== 精确层 KeyStats（double 计数，nano 时间） ==========

    /**
     * 精确层每个 key 的状态：包含 double 计数和上次访问的纳秒时间戳。
     * 采用无锁 AtomicReference<State> 实现原子更新。
     *
     * <p>衰减策略：每次访问或查询时，根据上次衰减时间计算该段时间的衰减因子，
     * 将历史计数乘以因子，再加上本次访问（+1）得到新计数。
     * 使用 double 存储以避免小计数截断误差。
     */
    private static class KeyStats {
        private static class State {
            final double count;
            final long lastDecayNano;
            State(double count, long nano) {
                this.count = count;
                this.lastDecayNano = nano;
            }
        }

        private final AtomicReference<State> stateRef;

        KeyStats(double initialCount, long nowNano) {
            this.stateRef = new AtomicReference<>(new State(initialCount, nowNano));
        }

        /**
         * 记录一次访问：读取旧状态，计算衰减，加 1，CAS 更新。
         * 若间隔 > 1000 秒，直接归零（避免大数 Math.pow 开销）。
         */
        void record(double decayRate, long nowNano) {
            while (true) {
                State old = stateRef.get();
                long elapsedNano = nowNano - old.lastDecayNano;
                if (elapsedNano < 0) elapsedNano = 0;
                double seconds = elapsedNano / 1_000_000_000.0;
                double factor;
                if (seconds > 1000) {
                    factor = 0;
                } else if (seconds < 1.0) {
                    factor = 1 - decayRate * seconds;
                    if (factor < 0) factor = 0;
                } else {
                    factor = Math.pow(1 - decayRate, seconds);
                }
                double newCount = old.count * factor + 1.0;
                State newState = new State(newCount, nowNano);
                if (stateRef.compareAndSet(old, newState)) return;
            }
        }

        /**
         * 仅获取当前衰减后的计数值（不加 1），用于查询和清理。
         * 逻辑与 record 相同，但不加 1。
         */
        long getCount(double decayRate, long nowNano) {
            State state = stateRef.get();
            long elapsedNano = nowNano - state.lastDecayNano;
            if (elapsedNano < 0) elapsedNano = 0;
            double seconds = elapsedNano / 1_000_000_000.0;
            double factor;
            if (seconds > 1000) {
                factor = 0;
            } else if (seconds < 1.0) {
                factor = 1 - decayRate * seconds;
                if (factor < 0) factor = 0;
            } else {
                factor = Math.pow(1 - decayRate, seconds);
            }
            double value = state.count * factor;
            return Math.round(value);
        }

        long getLastAccessNano() {
            return stateRef.get().lastDecayNano;
        }
    }
}
