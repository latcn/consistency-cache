package io.github.latcn.cache.core.hotspot.base;

import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import io.github.latcn.cache.core.util.ThreadUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import lombok.extern.slf4j.Slf4j;

/**
 * 两级热点检测器（惰性CMS + 精确滑动窗口）
 * <p>
 * 所有时间均使用 {@link System#nanoTime()} 单调时钟，确保无时钟回拨问题。 精确层窗口长度为 100ms，基于纳秒计算。
 */
@Slf4j
public class TwoLevelHotKeyDetector implements AutoCloseable {

	private final CMSHotKeyDetector cmsFilter;

	private final ConcurrentHashMap<String, KeyStats> exactCounter;

	private final AtomicInteger exactSize = new AtomicInteger(0);

	private volatile long cmsPromotionThreshold;

	private final int hotQps;

	private volatile double promotionRatio;

	private final long expirationTimeNanos; // 过期时间（纳秒）

	private final int maxExactSize;

	private final ScheduledExecutorService cleanupScheduler;

	private volatile boolean closed = false;

	// 监控
	private final AtomicLong promotedCount = new AtomicLong(0);

	private final AtomicLong evictedCount = new AtomicLong(0);

	private final AtomicLong hotHitCount = new AtomicLong(0);

	private final AtomicLong hotMissCount = new AtomicLong(0);

	// 触发全量排序驱逐的阈值（容量超出 110% 时触发）
	private static final double EVICT_TRIGGER_RATIO = 1.1;

	public TwoLevelHotKeyDetector(long totalQps, int hotQps, int depth, double promotionRatio, int maxExactSize,
			long expirationTimeMs, long cleanupIntervalMs) {
		this.cmsFilter = new CMSHotKeyDetector(totalQps, hotQps, depth);
		this.exactCounter = new ConcurrentHashMap<>();
		this.hotQps = hotQps;
		this.promotionRatio = promotionRatio;
		this.expirationTimeNanos = expirationTimeMs * 1_000_000L; // 毫秒转纳秒
		this.maxExactSize = maxExactSize;

		long hotInternalCms = cmsFilter.convertQpsToInternal(hotQps);
		this.cmsPromotionThreshold = calculatePromotionThreshold(hotInternalCms, promotionRatio);

		this.cleanupScheduler = ThreadUtils.getScheduledThreadPoolExecutor(1, "HotKey-Cleanup");
		this.cleanupScheduler.scheduleAtFixedRate(this::safeCleanup, cleanupIntervalMs, cleanupIntervalMs,
				TimeUnit.MILLISECONDS);

		log.info(
				"TwoLevelHotKeyDetector initialized: totalQps={}, hotQps={}, "
						+ "cmsPromotionThreshold={}, maxExactSize={}, expirationTimeNanos={}",
				totalQps, hotQps, cmsPromotionThreshold, maxExactSize, expirationTimeNanos);
	}

	private long calculatePromotionThreshold(long hotInternal, double ratio) {
		long promotion = Math.round(hotInternal * ratio);
		if (promotion < 1)
			promotion = 1;
		if (promotion >= hotInternal) {
			promotion = Math.max(1, hotInternal - 1);
		}
		return promotion;
	}

	public void setPromotionRatio(double newRatio) {
		if (newRatio <= 0 || newRatio >= 1) {
			throw new CacheException(CacheError.INVALID_PARAMETER, "ratio must be between 0 and 1");
		}
		this.promotionRatio = newRatio;
		long hotInternalCms = cmsFilter.convertQpsToInternal(hotQps);
		this.cmsPromotionThreshold = calculatePromotionThreshold(hotInternalCms, newRatio);
		log.info("Promotion ratio updated to {}, cmsPromotionThreshold={}", newRatio, cmsPromotionThreshold);
	}

	/**
	 * 记录访问（内部获取当前纳秒时间）
	 */
	public void record(String key) {
		if (closed || key == null)
			return;
		record(key, System.nanoTime());
	}

	/**
	 * 记录访问（使用外部传入的纳秒时间戳，保证时序一致性）
	 */
	public void record(String key, long nowNanos) {
		if (closed || key == null)
			return;

		// 1. 精确层命中
		KeyStats stats = exactCounter.get(key);
		if (stats != null) {
			stats.record(nowNanos);
			return;
		}

		// 2. 未命中，记录 CMS
		cmsFilter.record(key, nowNanos);

		// 3. 判断是否达到晋升阈值
		if (cmsFilter.estimateCount(key, nowNanos) >= cmsPromotionThreshold) {
			// 容量检查：已满则拒绝晋升（允许瞬时超出，由后台清理修正）
			if (exactSize.get() >= maxExactSize) {
				return;
			}

			KeyStats newStats = new KeyStats(nowNanos);
			KeyStats existing = exactCounter.putIfAbsent(key, newStats);
			if (existing == null) {
				// 插入成功，记录本次访问（newStats 已包含当前 tick，但需累加计数）
				newStats.record(nowNanos);
				exactSize.incrementAndGet();
				promotedCount.incrementAndGet();
			}
			else {
				// 其他线程已插入，直接记录
				existing.record(nowNanos);
			}
		}
	}

	public boolean isHotKey(String key) {
		if (closed || key == null)
			return false;
		KeyStats stats = exactCounter.get(key);
		if (stats == null) {
			hotMissCount.incrementAndGet();
			return false;
		}
		int count = stats.getTotalCount();
		boolean hot = count >= hotQps;
		if (hot)
			hotHitCount.incrementAndGet();
		else
			hotMissCount.incrementAndGet();
		return hot;
	}

	public int getCurrentQps(String key) {
		KeyStats stats = exactCounter.get(key);
		return stats == null ? 0 : stats.getTotalCount();
	}

	// ==================== 后台清理 ====================
	private void safeCleanup() {
		if (closed)
			return;
		try {
			long nowNanos = System.nanoTime();

			// 1. 过期清理
			for (Iterator<Map.Entry<String, KeyStats>> it = exactCounter.entrySet().iterator(); it.hasNext();) {
				Map.Entry<String, KeyStats> entry = it.next();
				KeyStats stats = entry.getValue();
				if (stats.getTotalCount() >= hotQps) {
					continue;
				}
				if (nowNanos - stats.getLastAccessNanos() > expirationTimeNanos) {
					it.remove();
					exactSize.decrementAndGet();
				}
			}

			// 2. 若容量仍严重超限，触发全量遍历驱逐
			if (exactSize.get() > maxExactSize * EVICT_TRIGGER_RATIO) {
				evictLowCountKeys(nowNanos);
			}
		}
		catch (Throwable t) {
			log.error("Cleanup task failed", t);
		}
	}

	/**
	 * 全量遍历精确层，驱逐低计数 Key，直到容量 ≤ maxExactSize
	 */
	private void evictLowCountKeys(long nowNanos) {
		int currentSize = exactSize.get();
		if (currentSize <= maxExactSize)
			return;

		// 收集所有 Entry（拷贝一份，避免并发修改）
		List<Map.Entry<String, KeyStats>> entries = new ArrayList<>(exactCounter.entrySet());
		// 按计数升序排序
		entries.sort(Comparator.comparingInt(e -> e.getValue().getTotalCount()));

		int toRemove = currentSize - maxExactSize;
		int removed = 0;
		for (Map.Entry<String, KeyStats> entry : entries) {
			if (removed >= toRemove)
				break;
			KeyStats stats = entry.getValue();
			// 二次校验：重新获取计数，若已变成热点则跳过
			if (stats.getTotalCount() < hotQps) {
				if (exactCounter.remove(entry.getKey(), stats)) {
					exactSize.decrementAndGet();
					evictedCount.incrementAndGet();
					removed++;
				}
			}
			else {
				// 若当前最小计数 >= hotQps，说明剩余全是热点，停止驱逐
				break;
			}
		}
	}

	// ==================== 监控 ====================
	public long getPromotedCount() {
		return promotedCount.get();
	}

	public long getEvictedCount() {
		return evictedCount.get();
	}

	public long getHotHitCount() {
		return hotHitCount.get();
	}

	public long getHotMissCount() {
		return hotMissCount.get();
	}

	public int getExactSize() {
		return exactSize.get();
	}

	public long getCmsPromotionThreshold() {
		return cmsPromotionThreshold;
	}

	@Override
	public void close() {
		if (closed)
			return;
		closed = true;
		cleanupScheduler.shutdownNow();
		try {
			if (!cleanupScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
				log.error("close in 3 seconds fail...");
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		cmsFilter.close();
		exactCounter.clear();
		exactSize.set(0);
		log.info("TwoLevelHotKeyDetector closed.");
	}

	// ==================== 内部类：KeyStats（无锁环形缓冲区，完全基于纳秒） ====================
	private static class KeyStats {

		private static final int BUCKET_COUNT = 10;

		private static final long BUCKET_MS = 100;

		// 每个桶存储打包值：高32位为tick，低32位为无符号计数
		private final AtomicLongArray buckets = new AtomicLongArray(BUCKET_COUNT);

		// 最后访问时间（纳秒），用于定时清理判断
		private volatile long lastAccessNanos;

		KeyStats(long nowNanos) {
			long currentTick = (nowNanos / 1_000_000) / BUCKET_MS; // 毫秒级 tick
			for (int i = 0; i < BUCKET_COUNT; i++) {
				buckets.set(i, currentTick << 32);
			}
			this.lastAccessNanos = nowNanos;
		}

		/**
		 * 记录访问（使用外部传入的纳秒时间）
		 */
		void record(long nowNanos) {
			long currentTick = (nowNanos / 1_000_000) / BUCKET_MS;
			// 先更新最后访问时间，避免清理线程误判过期
			this.lastAccessNanos = nowNanos;

			int idx = (int) (currentTick % BUCKET_COUNT);
			while (true) {
				long packed = buckets.get(idx);
				long oldTick = packed >>> 32;
				int oldCount = (int) (packed & 0xFFFFFFFFL);

				long newPacked;
				if (currentTick == oldTick) {
					int newCount = oldCount + 1;
					newPacked = (currentTick << 32) | (newCount & 0xFFFFFFFFL);
				}
				else {
					newPacked = (currentTick << 32) | 1L;
				}

				if (buckets.compareAndSet(idx, packed, newPacked)) {
					return;
				}
				// CAS 失败则重试
			}
		}

		/**
		 * 获取当前窗口总计数（使用快照时间，保证一致性）
		 */
		int getTotalCount() {
			long nowNanos = System.nanoTime();
			long currentTick = (nowNanos / 1_000_000) / BUCKET_MS;
			int sum = 0;
			for (int i = 0; i < BUCKET_COUNT; i++) {
				long packed = buckets.get(i);
				long tick = packed >>> 32;
				if (currentTick - tick < BUCKET_COUNT) {
					sum += (int) (packed & 0xFFFFFFFFL);
				}
			}
			return sum;
		}

		long getLastAccessNanos() {
			return lastAccessNanos;
		}

	}

}