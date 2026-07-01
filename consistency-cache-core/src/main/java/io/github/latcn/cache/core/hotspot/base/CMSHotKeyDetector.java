package io.github.latcn.cache.core.hotspot.base;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLongArray;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于惰性衰减的 Count-Min Sketch 热点检测器（无全局扫描，无后台线程）
 */
@Slf4j
public class CMSHotKeyDetector implements AutoCloseable {

	private static final int MIN_SAMPLE_SIZE = 1024;

	private static final int MAX_AUTO_WIDTH = 16384;

	private static final double TARGET_ERROR_RATIO = 0.1;

	private static final long GOLDEN_RATIO_64 = 0x9e3779b97f4a7c15L;

	private static final long DEFAULT_WINDOW_NANOS = 500_000_000L;

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

	public CMSHotKeyDetector(long totalQps, int targetHotQps, int depth) {
		if (totalQps <= 0 || targetHotQps <= 0) {
			throw new IllegalArgumentException("totalQps and targetHotQps must be positive");
		}
		this.totalQps = totalQps;
		this.depth = depth;

		long rawSample = totalQps / 2;
		if (rawSample < MIN_SAMPLE_SIZE)
			rawSample = MIN_SAMPLE_SIZE;
		if (rawSample > Integer.MAX_VALUE)
			rawSample = Integer.MAX_VALUE;
		this.sampleSize = (int) rawSample;

		double maxAllowedError = Math.max(targetHotQps * TARGET_ERROR_RATIO, 1.0);
		double widthDouble = Math.E * 2.0 * this.sampleSize / maxAllowedError;
		int width = ceilNextPowerOfTwo((int) Math.ceil(widthDouble));
		if (width < 1024)
			width = 1024;
		if (width > MAX_AUTO_WIDTH)
			width = MAX_AUTO_WIDTH;
		this.width = width;
		this.widthMask = this.width - 1;

		this.internalScale = 2.0 * this.sampleSize / (double) this.totalQps;
		this.windowNanos = (long) ((double) this.sampleSize / (double) this.totalQps * 1_000_000_000L);
		// 上下限保护
		if (this.windowNanos > MAX_WINDOW_NANOS)
			this.windowNanos = MAX_WINDOW_NANOS;
		if (this.windowNanos < MIN_WINDOW_NANOS)
			this.windowNanos = MIN_WINDOW_NANOS;

		initSeedsAndCounters();

		log.info(
				"CMS auto-configured: totalQps={}, targetHotQps={}, sampleSize={}, width={}, depth={}, "
						+ "windowNanos={}ns, internalScale={}",
				totalQps, targetHotQps, sampleSize, width, depth, windowNanos, internalScale);
	}

	public CMSHotKeyDetector(int width, int depth, int sampleSize) {
		if (width <= 0 || depth <= 0 || sampleSize <= 0) {
			throw new IllegalArgumentException("width, depth and sampleSize must be positive");
		}
		if (width > (1 << 30)) {
			throw new IllegalArgumentException("width too large (max 1<<30)");
		}
		this.totalQps = -1;
		this.internalScale = 0.0;
		this.sampleSize = Math.max(sampleSize, MIN_SAMPLE_SIZE);
		this.width = ceilNextPowerOfTwo(width);
		this.widthMask = this.width - 1;
		this.depth = depth;
		// 手动构造也应用上下限
		this.windowNanos = DEFAULT_WINDOW_NANOS;
		if (this.windowNanos > MAX_WINDOW_NANOS)
			this.windowNanos = MAX_WINDOW_NANOS;
		if (this.windowNanos < MIN_WINDOW_NANOS)
			this.windowNanos = MIN_WINDOW_NANOS;

		initSeedsAndCounters();

		double stableError = Math.E * 2L * this.sampleSize / (double) this.width;
		log.info("CMS manual-configured: width={}, depth={}, sampleSize={}, windowNanos={}ns, stableMaxError≈{}",
				this.width, this.depth, this.sampleSize, windowNanos, String.format("%.2f", stableError));
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