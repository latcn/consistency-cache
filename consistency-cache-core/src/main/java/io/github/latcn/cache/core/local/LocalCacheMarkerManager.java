package io.github.latcn.cache.core.local;

import io.github.latcn.cache.core.model.NodeInstanceHolder;
import io.github.latcn.cache.core.util.ConcurrentFifoList;
import io.github.latcn.cache.core.util.ThreadUtils;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class LocalCacheMarkerManager {

	private static final int CACHE_MARKER_INTERVAL_SECOND = 5;

	protected static final String MARKER_PREFIX = "local_cache_marker:";

	protected final String nodeId;

	protected final ConcurrentFifoList<String> useLocalCacheKey;

	private final ScheduledExecutorService scheduledExecutor;

	private final AtomicBoolean isExecClean = new AtomicBoolean(false);

	public LocalCacheMarkerManager(int cleanPeriodSeconds, int markerMaxSize) {
		this.nodeId = NodeInstanceHolder.getNodeId();
		this.useLocalCacheKey = new ConcurrentFifoList<>(markerMaxSize,
				ConcurrentFifoList.OverflowStrategy.REMOVE_OLDEST);
		this.scheduledExecutor = ThreadUtils.getScheduledThreadPoolExecutor(1,
				"LocalCacheMarkerManager-Clean-scheduledExecutor");
		if (cleanPeriodSeconds <= 0) {
			cleanPeriodSeconds = CACHE_MARKER_INTERVAL_SECOND;
		}
		this.scheduledExecutor.scheduleAtFixedRate(this::cleanupExpiredMarkers, cleanPeriodSeconds, cleanPeriodSeconds,
				TimeUnit.SECONDS);
	}

	/**
	 * 标记当前节点正在使用本地缓存
	 * @param cacheKey 业务缓存Key
	 */
	public abstract void markLocalCacheUsage(String cacheKey, long expireTime);

	/**
	 * 删除标记
	 * @param cacheKey 业务缓存Key
	 */
	public abstract void removeLocalCacheUsage(String cacheKey);

	/**
	 * 定时清理
	 */
	public abstract void doCleanUp();

	/**
	 * 获取活跃节点列表（带清理逻辑）
	 */
	public abstract List<String> getActiveNodes(String cacheKey);

	/**
	 * 定时清理任务：随机选取扫描并清理过期的标记
	 */
	public void cleanupExpiredMarkers() {
		if (!this.isExecClean.compareAndSet(false, true)) {
			return;
		}
		try {
			doCleanUp();
		}
		catch (Exception e) {
			log.error("ex", e);
		}
		finally {
			this.isExecClean.compareAndSet(true, false);
		}
	}

}
