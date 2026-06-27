package io.github.latcn.cache.core.hotspot.writes;

import io.github.latcn.cache.core.hotspot.TwoLevelHotKeyDetector;
import io.github.latcn.cache.core.util.StringUtil;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DefaultWriteHotspotDetector implements WriteHotspotDetector, AutoCloseable {

	private final TwoLevelHotKeyDetector hotKeyDetector;

	private final LocalCacheBlacklist blacklist;

	private final Duration baseBlacklistTtl;

	private final double backoffMultiplier;

	private final Duration maxBlacklistTime;

	private final ConcurrentHashMap<Object, WriteHotSpotInfo> hotSpotInfo = new ConcurrentHashMap<>();

	public DefaultWriteHotspotDetector(int invalidationThreshold, long baseBlacklistTtl, double backoffMultiplier,
			long maxBlacklistTime, int maxBlacklistSize) {
		this.baseBlacklistTtl = Duration.ofMillis(baseBlacklistTtl);
		this.backoffMultiplier = backoffMultiplier;
		this.maxBlacklistTime = Duration.ofMillis(maxBlacklistTime);
		this.blacklist = new LocalCacheBlacklist(maxBlacklistSize);
		this.hotKeyDetector = TwoLevelHotKeyDetector.Builder.forHighQps()
			.hotKeyThreshold(invalidationThreshold)
			.promotionThreshold((long) (invalidationThreshold * 0.7))
			.maxExactSize(maxBlacklistSize)
			.build();
		log.info("Initialized DefaultWriteHotspotDetector: threshold={}, baseTtl={}, backoff={}", invalidationThreshold,
				baseBlacklistTtl, backoffMultiplier);
	}

	@Override
	public <T> void recordInvalidation(T key) {
		String keyOfStr = StringUtil.toStringKey(key);
		hotKeyDetector.record(keyOfStr);
		if (hotKeyDetector.isHotKey(keyOfStr) && !blacklist.isBlacklisted(key)) {
			WriteHotSpotInfo info = hotSpotInfo.computeIfAbsent(key, k -> new WriteHotSpotInfo());
			Duration blacklistDuration = calculateBackoffDuration(info);
			blacklist.addToBlacklistWithDuration(key, blacklistDuration);
			info.violationCount++;
			log.warn("Write hotspot detected for key={}. Blacklisted for {}. Violation count: {}", key,
					blacklistDuration, info.violationCount);
		}
	}

	private Duration calculateBackoffDuration(WriteHotSpotInfo info) {
		if (info.violationCount == 0) {
			return this.baseBlacklistTtl;
		}
		long baseMillis = this.baseBlacklistTtl.toMillis();
		double multiplier = Math.pow(this.backoffMultiplier, info.violationCount);
		long calculatedMillis = (long) (baseMillis * multiplier);
		return Duration.ofMillis(Math.min(calculatedMillis, this.maxBlacklistTime.toMillis()));
	}

	@Override
	public <T> boolean shouldBypassL1(T key) {
		return blacklist.isBlacklisted(key);
	}

	@Override
	public long writeHotKeyCount() {
		return blacklist.size();
	}

	public <T> int getInvalidationCount(T key) {
		return hotKeyDetector.isHotKey(StringUtil.toStringKey(key)) ? 1 : 0;
	}

	@Override
	public void close() {
		hotKeyDetector.close();
		blacklist.shutdown();
	}

	@Data
	private static class WriteHotSpotInfo {

		int violationCount = 0;

	}

}