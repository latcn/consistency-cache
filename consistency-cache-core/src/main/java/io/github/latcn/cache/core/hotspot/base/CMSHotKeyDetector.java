package io.github.latcn.cache.core.hotspot.base;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLongArray;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于惰性衰减的 Count-Min Sketch 热点检测器（无全局扫描，无后台线程）
 */
@Slf4j
public class CMSHotKeyDetector implements AutoCloseable {

	// 硬边界常量（定义在类中）
	// 宽度下限，确保哈希均匀性
	private static final int MIN_SAMPLE_SIZE = 1024;

	private static final int MIN_WIDTH = 64;

	// 1,048,576，对应内存约 4MB/行
	private static final int MAX_WIDTH = 1 << 20;

	private static final int MIN_DEPTH = 1;

	// 失败概率 e^-15 ≈ 3e-7，已足够低
	private static final int MAX_DEPTH = 15;

	private static final long GOLDEN_RATIO_64 = 0x9e3779b97f4a7c15L;

	private static final int MAX_SHIFT = 31;

	// 窗口时长上下限（1ms ~ 1s）
	private static final long MIN_WINDOW_NANOS = 1_000_000L; // 1ms

	private static final long MAX_WINDOW_NANOS = 1_000_000_000L; // 1s

	private final int width;

	private final int widthMask;

	private final int depth;

	private final int sampleSize;

	private long windowNanos;

	private final long totalQps;

	private final double internalScale;

	private int[] seeds;

	private AtomicLongArray[] counters;

	private volatile boolean closed = false;

	/**
	 * 构造 CMS 热点检测器（纯数学计算，无隐式截断，超出边界直接报错）。
	 * @param totalQps 系统总流量（QPS）
	 * @param targetHotQps 热点判定阈值（QPS）
	 * @param maxAbsError 允许的最大绝对误差（QPS），必须小于 targetHotQps
	 * @param windowMs 统计窗口时长（毫秒）
	 */
	public CMSHotKeyDetector(long totalQps, int targetHotQps, int maxAbsError, int windowMs, int depth) {
		// ---------- 1. 参数基础校验 ----------
		if (totalQps <= 0 || targetHotQps <= 0 || maxAbsError <= 0 || windowMs <= 0) {
			throw new IllegalArgumentException("所有参数必须为正数");
		}
		if (maxAbsError >= targetHotQps) {
			throw new IllegalArgumentException(
					"maxAbsError(" + maxAbsError + ") 必须小于 targetHotQps(" + targetHotQps + ")，否则信号将被噪声淹没");
		}
		// ---------- 2. 计算采样窗口 (sampleSize) ----------
		double windowSeconds = windowMs / 1000.0;
		double sampleSizeDouble = totalQps * windowSeconds;
		long sampleSize = (long) Math.ceil(sampleSizeDouble);
		// sampleSize 必须为正且不超出 int 范围（AtomicIntegerArray 长度限制）
		if (sampleSize <= 0) {
			throw new IllegalArgumentException("计算出的 sampleSize 必须为正数");
		}
		if (sampleSize > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("sampleSize(" + sampleSize + ") 超过 Integer.MAX_VALUE，请减小窗口或降低 QPS");
		}
		this.totalQps = totalQps;
		this.sampleSize = (int) sampleSize;
		// ---------- 3. 计算宽度 (width) ----------
		double widthDouble = Math.E * 2.0 * this.sampleSize / maxAbsError;
		// 防止 double 溢出 int
		if (widthDouble > MAX_WIDTH) {
			throw new IllegalArgumentException(
					"计算出的理论宽度 " + widthDouble + " 远超最大支持宽度 " + MAX_WIDTH + "，请增大 maxAbsError 或减小窗口");
		}
		int theoreticalWidth = (int) Math.ceil(widthDouble);
		int width = ceilNextPowerOfTwo(theoreticalWidth);

		// 硬边界校验（无截断，不合格即报错）
		if (width < MIN_WIDTH) {
			throw new IllegalArgumentException(
					"计算出的宽度 " + width + " 小于最小宽度 " + MIN_WIDTH + "，这会导致哈希分布极差，请减小 maxAbsError");
		}
		if (width > MAX_WIDTH) {
			throw new IllegalArgumentException(
					"计算出的宽度 " + width + " 超过最大支持宽度 " + MAX_WIDTH + "，请增大 maxAbsError 或增大内存限制");
		}
		this.width = width;
		this.widthMask = this.width - 1;
		// ---------- 4. 计算实际物理误差 (基于最终 width) ----------
		double actualMaxError = Math.E * 2.0 * this.sampleSize / width;
		// ---------- 5. 计算深度 (depth) ----------
		double safetyRatio = (double) targetHotQps / actualMaxError;
		int calDepth = (int) Math.ceil(Math.log(safetyRatio));
		if (calDepth > depth) {
			depth = calDepth;
		}
		// 硬边界校验（无保底，不合格即报错）
		if (depth < MIN_DEPTH) {
			throw new IllegalArgumentException("计算出的深度 " + depth + " 小于最小深度 " + MIN_DEPTH + "，信噪比过高 (" + safetyRatio
					+ ")，请减小 maxAbsError 或放宽 targetHotQps");
		}
		if (depth > MAX_DEPTH) {
			throw new IllegalArgumentException(
					"计算出的深度 " + depth + " 超过最大深度 " + MAX_DEPTH + "，失败概率已极度保守，可尝试放宽 maxAbsError");
		}
		this.depth = depth;
		// ---------- 6. 衍生参数计算 (保留 internalScale 和 windowNanos) ----------
		this.internalScale = this.sampleSize / (double) this.totalQps;
		// windowNanos 与 sampleSize 严格对齐（用实际 sampleSize 反推）
		this.windowNanos = (long) ((this.sampleSize / (double) totalQps) * 1_000_000_000.0);
		// 仅做极端物理边界保护（防止纳秒溢出，但不改动业务语义）
		if (this.windowNanos > Long.MAX_VALUE / 2) {
			throw new IllegalStateException("计算出的 windowNanos 超出合理范围");
		}
		// ---------- 7. 计算实际失败概率（用于日志） ----------
		double actualFailProb = Math.pow(Math.E, -depth);
		// ---------- 8. 初始化底层数据结构 ----------
		initSeedsAndCounters();
		// ---------- 9. 日志输出 ----------
		log.info(
				"CMS 初始化: totalQps={}, targetHotQps={}, maxAbsError={}, window={}ms, "
						+ "sampleSize={}, width={}, depth={}, 实际失败概率={}%, 实际误差上界≈{}, internalScale={}",
				totalQps, targetHotQps, maxAbsError, windowMs, this.sampleSize, this.width, this.depth,
				String.format("%.2f", actualFailProb * 100), String.format("%.2f", actualMaxError), internalScale);
	}

	private void initSeedsAndCounters() {
		this.seeds = new int[depth];
		long seedBase = ThreadLocalRandom.current().nextLong();
		for (int i = 0; i < depth; i++) {
			this.seeds[i] = (int) (seedBase & 0xFFFFFFFFL);
			seedBase += GOLDEN_RATIO_64;
		}

		this.counters = new AtomicLongArray[depth];
		for (int i = 0; i < depth; i++) {
			counters[i] = new AtomicLongArray(this.width);
		}
	}

	private static int mixHash(int base, int seed, int mask) {
		long h = (base ^ seed) * GOLDEN_RATIO_64;
		h ^= h >>> 32;
		return (int) (h & mask);
	}

	public void record(String key) {
		if (closed || key == null)
			return;
		record(key, System.nanoTime());
	}

	public void record(String key, long nowNanos) {
		if (closed || key == null)
			return;
		final int hc = key.hashCode();
		final long currentTick = nowNanos / windowNanos;
		final long lowTick = currentTick & 0xFFFFFFFFL;

		final int d = this.depth;
		final AtomicLongArray[] cs = this.counters;
		final int[] sds = this.seeds;
		final int mask = this.widthMask;

		for (int i = 0; i < d; i++) {
			int idx = mixHash(hc, sds[i], mask);
			updateSlot(cs[i], idx, lowTick);
		}
	}

	private void updateSlot(AtomicLongArray row, int idx, long tick) {
		while (true) {
			long packed = row.get(idx);
			long lastTick = packed >>> 32;
			int count = (int) (packed & 0xFFFFFFFFL);

			long elapsed = tick - lastTick;
			if (elapsed < 0) {
				long newPacked = (tick << 32) | 1L;
				if (row.compareAndSet(idx, packed, newPacked)) {
					return;
				}
				continue;
			}
			if (elapsed > MAX_SHIFT)
				elapsed = MAX_SHIFT;

			int decayed = (elapsed == 0) ? count : (count >>> (int) elapsed);
			int newCount = decayed + 1;
			long newPacked = (tick << 32) | (newCount & 0xFFFFFFFFL);

			if (row.compareAndSet(idx, packed, newPacked)) {
				return;
			}
		}
	}

	public int estimateCount(String key) {
		if (closed || key == null)
			return 0;
		return estimateCount(key, System.nanoTime());
	}

	public int estimateCount(String key, long nowNanos) {
		if (closed || key == null)
			return 0;
		final int hc = key.hashCode();
		final long nowTick = (nowNanos / windowNanos) & 0xFFFFFFFFL;

		int min = Integer.MAX_VALUE;
		final int d = this.depth;
		final AtomicLongArray[] cs = this.counters;
		final int[] sds = this.seeds;
		final int mask = this.widthMask;

		for (int i = 0; i < d; i++) {
			int idx = mixHash(hc, sds[i], mask);
			long packed = cs[i].get(idx);
			long lastTick = packed >>> 32;
			int count = (int) (packed & 0xFFFFFFFFL);

			long elapsed = nowTick - lastTick;
			if (elapsed < 0)
				elapsed = 0;
			if (elapsed > MAX_SHIFT)
				elapsed = MAX_SHIFT;

			int decayed = (elapsed == 0) ? count : (count >>> (int) elapsed);
			// 防止由于 count 溢出导致的负数
			if (decayed < 0)
				decayed = 0;
			if (decayed < min)
				min = decayed;
		}
		return min;
	}

	public boolean isHot(String key, int internalThreshold) {
		return estimateCount(key) > internalThreshold;
	}

	public int convertQpsToInternal(int targetHotQps) {
		if (totalQps <= 0) {
			throw new UnsupportedOperationException("Manual mode does not support QPS conversion.");
		}
		return (int) Math.round(targetHotQps * internalScale);
	}

	public int convertInternalToQps(long internalValue) {
		if (totalQps <= 0) {
			throw new UnsupportedOperationException("Manual mode does not support QPS conversion.");
		}
		return (int) Math.round(internalValue / internalScale);
	}

	public double getInternalScale() {
		if (totalQps <= 0) {
			throw new UnsupportedOperationException("Manual mode has no internal scale.");
		}
		return internalScale;
	}

	private static int ceilNextPowerOfTwo(int x) {
		if (x < 2)
			return 2;
		if (x > (1 << 30))
			return 1 << 30;
		int highest = Integer.highestOneBit(x);
		return (highest == x) ? x : highest << 1;
	}

	public int getWidth() {
		return width;
	}

	public int getDepth() {
		return depth;
	}

	public int getSampleSize() {
		return sampleSize;
	}

	public long getTotalQps() {
		return totalQps;
	}

	@Override
	public void close() {
		if (closed)
			return;
		closed = true;
		log.info("CMSHotKeyDetector closed.");
	}

}