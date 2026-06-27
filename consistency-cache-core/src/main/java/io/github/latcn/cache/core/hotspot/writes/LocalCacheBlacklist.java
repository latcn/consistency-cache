package io.github.latcn.cache.core.hotspot.writes;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Dynamic blacklist for write hotspot keys. Keys with high L1 invalidation frequency are
 * blacklisted to bypass L1 cache.
 */
@Slf4j
public class LocalCacheBlacklist {

	private static final long CLEANUP_INTERVAL_SECONDS = 1L;
	private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

	private final ConcurrentHashMap<Object, Long> blacklist = new ConcurrentHashMap<>();

	private final int maxSize;

	private final ScheduledExecutorService scheduler;

	/**
	 * Create local cache blacklist.
	 */
	public LocalCacheBlacklist(int maxSize) {
		this.maxSize = maxSize;
		this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "blacklist-cleanup");
			t.setDaemon(true);
			return t;
		});

		// Schedule periodic cleanup
		this.scheduler.scheduleAtFixedRate(this::autoCleanup, CLEANUP_INTERVAL_SECONDS, CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS);

		log.info("Initialized LocalCacheBlacklist");
	}

	/**
	 * Add key to blacklist with custom duration.
	 * @param key cache key
	 * @param duration blacklist duration
	 */
	public <T> void addToBlacklistWithDuration(T key, Duration duration) {
		if (this.blacklist.size()>=this.maxSize) {
			return;
		}
		long expireTime = System.currentTimeMillis() + duration.toMillis();
		this.blacklist.put(key, expireTime);
		log.warn("Key {} added to L1 blacklist for {} minutes", key, duration.toMinutes());
	}

	/**
	 * Check if key is blacklisted.
	 * @param key cache key
	 * @return true if blacklisted and not expired
	 */
	public <T> boolean isBlacklisted(T key) {
		if (key==null) {
			return false;
		}
		Long expireTime = this.blacklist.get(key);
		if (expireTime == null) {
			return false;
		}

		// Auto-remove if expired
		if (System.currentTimeMillis() > expireTime) {
			this.blacklist.remove(key);
			log.info("Key {} auto-recovered from L1 blacklist", key);
			return false;
		}

		return true;
	}

	/**
	 * Remove key from blacklist manually.
	 * @param key cache key
	 */
	public <T> void removeFromBlacklist(T key) {
		this.blacklist.remove(key);
		log.info("Key {} manually removed from L1 blacklist", key);
	}

	/**
	 * Get blacklist size.
	 * @return number of blacklisted keys
	 */
	public int size() {
		return this.blacklist.size();
	}

	/**
	 * Cleanup expired entries.
	 */
	public void autoCleanup() {
		long now = System.currentTimeMillis();
		this.blacklist.entrySet().removeIf(e -> now > e.getValue());
	}

	/**
	 * Shutdown scheduler.
	 */
	public void shutdown() {
		this.scheduler.shutdown();
		try {
			if (!this.scheduler.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				this.scheduler.shutdownNow();
			}
		}
		catch (InterruptedException e) {
			this.scheduler.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

}
