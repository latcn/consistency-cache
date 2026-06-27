package io.github.latcn.cache.core.hotspot;


import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import io.github.latcn.cache.core.util.NumberUtil;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import lombok.extern.slf4j.Slf4j;

/**
 * Count-Min Sketch 热点检测器（分片优化版）
 *
 * <h2>算法设计思路</h2>
 * Count-Min Sketch (CMS) 是一种概率性数据结构，用于在有限内存下统计海量数据流中元素的频率。
 * 核心思想：使用 d 个独立的哈希函数，将每个元素映射到 d 个计数桶中，记录时所有对应桶 +1，
 * 查询时取 d 个桶的最小值作为估计频率。
 *
 * <p>为什么取最小值？因为哈希碰撞只会导致计数偏高，取最小值能最大程度抵消碰撞影响。
 *
 * <p>本实现专为热点检测优化：
 * <ul>
 *   <li><b>分片衰减</b>：将宽度分成 segmentCount 片，每次只衰减一片，降低单次 CPU 峰值。</li>
 *   <li><b>惰性衰减</b>：由后台线程定时执行，不阻塞业务线程。</li>
 *   <li><b>异常回滚</b>：若衰减失败，回滚时间戳和分片指针，保证一致性。</li>
 *   <li><b>哈希优化</b>：采用乘法混合（与黄金分割常数相乘），大幅降低碰撞概率。</li>
 * </ul>
 *
 * <h2>参数计算与预估</h2>
 * <ul>
 *   <li><b>宽度 (width)</b>：决定相对误差上限。误差率 ε = 1/width，因此 width = ceil(1/ε)。
 *   若要求 1% 误差，width ≥ 1000,000。</li>
 *   <li><b>深度 (depth)</b>：决定误差概率。单次查询误差超过 ε 的概率 ≤ e^{-depth}。
 *   depth=3 时概率约 4.98%，depth=5 时约 0.67%。</li>
 *   <li><b>衰减率 (decayRate)</b>：每次衰减时，计数乘以 (1 - decayRate)。
 *   若间隔为 interval (秒)，则半衰期 = -ln(2) / ln(1 - decayRate) * interval。
 *   建议 decayRate 0.1~0.2，使计数在几秒内遗忘旧数据。</li>
 *   <li><b>衰减间隔 (decayIntervalMs)</b>：决定 CPU 开销与实时性。间隔越短，计数越实时但 CPU 越高。
 *   典型值 100~500ms。</li>
 * </ul>
 *
 * <h2>调优建议</h2>
 * <ul>
 *   <li>若 QPS 高、热 key 多，可增大 width 以降低误差，但内存线性增加。</li>
 *   <li>若 CPU 紧张，可增大 decayIntervalMs 或减小 segmentCount（减少分片数）。</li>
 *   <li>若业务对冷热判别敏感，可适当增大 depth 以降低误差概率。</li>
 * </ul>
 */
@Slf4j
public class CMSHotKeyDetector implements AutoCloseable {

	private static final String DECAY_THREAD_NAME = "CMS-Decay";
	private static final int WARN_LOG_INTERVAL = 10;

	// 每行桶的数量（列数），决定相对误差上限 ε = 1/width
	private final int width;

	// 哈希函数数量（行数），决定误差概率 P ≤ e^{-depth}
	private final int depth;

	// 二维计数器数组，每个元素为 AtomicLongArray，线程安全
	private final AtomicLongArray[] counters;

	// 每行独立的哈希种子，保证不同行使用不同哈希函数
	private final int[] seeds;

	// 每次衰减的比率，值域 [0,1]，例如 0.15 表示衰减 15%
	private final double decayRate;

	// 衰减执行间隔（毫秒），后台线程按此频率执行衰减
	private final long decayIntervalMs;

	// 分片数量，宽度被分成 segmentCount 片，每次只衰减一片
	private final int segmentCount;

	// 每个分片的列数（最后一片可能小于此值）
	private final int segmentSize;

	// 上次成功衰减的纳秒时间戳，用于防重叠和异常回滚
	private final AtomicLong lastDecayTime = new AtomicLong(System.nanoTime());

	// 后台衰减调度器
	private final ScheduledExecutorService decayExecutor;

	// 关闭标志，防止关闭后继续操作
	private volatile boolean closed = false;

	// 当前正在衰减的分片索引（0 ~ segmentCount-1），volatile 保证可见性
	private volatile int currentSegment = 0;

	// ========== 监控指标 ==========
	private final AtomicLong decayRunCount = new AtomicLong(0);       // 成功衰减次数
	private final AtomicLong decaySkipCount = new AtomicLong(0);      // 因并发而跳过的衰减次数
	private final AtomicLong totalDecayTimeMs = new AtomicLong(0);    // 累计衰减耗时（毫秒）
	private final AtomicLong totalDecayItems = new AtomicLong(0);     // 累计衰减的桶数（仅非零）
	private final AtomicLong warnCount = new AtomicLong(0);           // 超时警告计数

	/**
	 * 构造 CMS 热点检测器（分片衰减）。
	 *
	 * @param widthInput            桶宽度（列数），建议 100,000 ~ 1,000,000
	 * @param depth            哈希函数数量（行数），建议 3 ~ 5
	 * @param decayRate        衰减率，建议 0.1 ~ 0.2
	 * @param decayIntervalMs  衰减间隔（毫秒），建议 100 ~ 1000
	 * @param segmentCount     分片数，建议 4 ~ 8（单次扫描量 = width/segmentCount）
	 */
	public CMSHotKeyDetector(int widthInput, int depth, double decayRate, long decayIntervalMs, int segmentCount) {
		if (widthInput <= 0 || depth <= 0 || decayRate < 0 || decayRate > 1 || decayIntervalMs <= 0 || segmentCount <= 0) {
			throw new CacheException(CacheError.INVALID_PARAMETER, "CMSHotKeyDetector parameters invalid");
		}
		this.width = NumberUtil.nextPowerOfTwo(widthInput);
		this.depth = depth;
		this.decayRate = decayRate;
		this.decayIntervalMs = decayIntervalMs;
		this.segmentCount = segmentCount;
		this.segmentSize = (width + segmentCount - 1) / segmentCount;

		this.counters = new AtomicLongArray[depth];
		this.seeds = new int[depth];
		for (int i = 0; i < depth; i++) {
			counters[i] = new AtomicLongArray(width);
			seeds[i] = ThreadLocalRandom.current().nextInt();
		}

		// 启动单个后台线程定时衰减
		this.decayExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r);
			t.setDaemon(true);
			t.setName(DECAY_THREAD_NAME);
			t.setUncaughtExceptionHandler((thread, ex) ->
					log.error("CMS decay thread died", ex));
			return t;
		});
		this.decayExecutor.scheduleAtFixedRate(this::safeDecay, decayIntervalMs, decayIntervalMs, TimeUnit.MILLISECONDS);
	}

	/** 安全衰减入口，捕获所有异常防止线程中断 */
	private void safeDecay() {
		if (closed) return;
		try {
			decayPartial();
		} catch (Throwable t) {
			log.error("Decay task failed, will retry", t);
		}
	}

	/**
	 * 执行一次分片衰减。
	 * 使用 CAS 尝试抢占衰减机会，若失败则跳过（说明其他线程已执行）。
	 * 若成功，则对当前分片进行衰减，并移动到下一个分片。
	 * 若衰减过程异常，回滚时间戳和分片指针，以便下次重试。
	 */
	private void decayPartial() {
		long nowNano = System.nanoTime();
		long expected = lastDecayTime.get();
		if (!lastDecayTime.compareAndSet(expected, nowNano)) {
			decaySkipCount.incrementAndGet();
			return; // 其他线程已更新，跳过本次
		}

		int seg = currentSegment;
		try {
			doDecaySegment(seg, nowNano);
		} catch (Throwable t) {
			// 回滚时间戳和分片指针，保持一致性
			lastDecayTime.set(expected);
			currentSegment = seg;
            log.error("Decay failed for segment {}", seg, t);
			throw t;
		}
		currentSegment = (seg + 1) % segmentCount;
	}

	/**
	 * 衰减指定分片的所有桶。
	 * 只处理计数 > 0 的桶，减少无效操作。
	 * 记录耗时，若超过间隔则输出警告（每10次输出一次 Warn，其余 Fine）。
	 */
	private void doDecaySegment(int seg, long nowNano) {
		int start = seg * segmentSize;
		int end = Math.min(start + segmentSize, width);
		long startMs = System.currentTimeMillis();
		long itemCount = 0;
		for (int i = 0; i < depth; i++) {
			AtomicLongArray row = counters[i];
			for (int col = start; col < end; col++) {
				long current = row.get(col);
				if (current > 0) {
					long decayed = (long) (current * (1 - decayRate));
					row.set(col, Math.max(0, decayed));
					itemCount++;
				}
			}
		}
		long duration = System.currentTimeMillis() - startMs;
		totalDecayTimeMs.addAndGet(duration);
		decayRunCount.incrementAndGet();
		totalDecayItems.addAndGet(itemCount);

		if (duration > decayIntervalMs) {
			long w = warnCount.incrementAndGet();
			if (w % WARN_LOG_INTERVAL == 0) {
                log.debug("Decay segment {} took {}ms, exceeding interval {}ms", seg, duration, decayIntervalMs);
			} else {
                log.debug("Decay segment {} took {}ms (exceeded)", seg, duration);
			}
		}
	}

	/** 记录一次访问（增加计数） */
	public void record(String key) {
		if (closed) throw new CacheException(CacheError.HOT_KEY_DETECTION_FAILED, "CMSHotKeyDetector already closed");
		if (key == null) throw new CacheException(CacheError.EMPTY_KEY, "key cannot be null");
		int h = key.hashCode();
		for (int i = 0; i < depth; i++) {
			int col = NumberUtil.hash(h, seeds[i], width);
			counters[i].getAndIncrement(col);
		}
	}

	/** 估算 key 的访问频率（取所有行对应桶的最小值） */
	public long estimateCount(String key) {
		if (closed) throw new CacheException(CacheError.HOT_KEY_DETECTION_FAILED, "CMSHotKeyDetector already closed");
		if (key == null) throw new CacheException(CacheError.EMPTY_KEY, "key cannot be null");
		int h = key.hashCode();
		long min = Long.MAX_VALUE;
		for (int i = 0; i < depth; i++) {
			int col = NumberUtil.hash(h, seeds[i], width);
			long v = counters[i].get(col);
			if (v < min) min = v;
		}
		return min;
	}

	// ========== 监控指标 ==========
	public long getDecayRunCount() { return decayRunCount.get(); }
	public long getDecaySkipCount() { return decaySkipCount.get(); }
	public long getTotalDecayTimeMs() { return totalDecayTimeMs.get(); }
	public long getTotalDecayItems() { return totalDecayItems.get(); }
	public double getAvgDecayTimeMs() {
		long runs = decayRunCount.get();
		return runs == 0 ? 0 : totalDecayTimeMs.get() / (double) runs;
	}

	@Override
	public void close() {
		closed = true;
		decayExecutor.shutdown();
		try {
			if (!decayExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
				decayExecutor.shutdownNow();
				if (!decayExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
					log.warn("CMS decay executor did not terminate");
				}
			}
		} catch (InterruptedException e) {
			decayExecutor.shutdownNow();
			Thread.currentThread().interrupt();
		}
		// 释放数组引用，帮助 GC
		for (int i = 0; i < depth; i++) counters[i] = null;
	}
}
