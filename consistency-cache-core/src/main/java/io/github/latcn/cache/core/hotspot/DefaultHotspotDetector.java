package io.github.latcn.cache.core.hotspot;

import io.github.latcn.cache.core.hotspot.base.TwoLevelHotKeyDetector;
import io.github.latcn.cache.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DefaultHotspotDetector implements HotspotDetector, AutoCloseable {

	private final TwoLevelHotKeyDetector hotKeyDetector;

	public DefaultHotspotDetector(int hotKeyThreshold, int maxExactSize) {
		this.hotKeyDetector = TwoLevelHotKeyDetector.Builder.forHighQps()
			.hotKeyThreshold(hotKeyThreshold)
			.promotionThreshold((long) (hotKeyThreshold * 0.7))
			.maxExactSize(maxExactSize)
			.build();
		log.info("Initialized DefaultHotspotDetector: threshold={}, maxExactSize={}", hotKeyThreshold, maxExactSize);
	}

	@Override
	public <T> void record(T key) {
		hotKeyDetector.record(StringUtil.toStringKey(key));
	}

	@Override
	public <T> boolean isHotKey(T key) {
		return hotKeyDetector.isHotKey(StringUtil.toStringKey(key));
	}

	@Override
	public long getHotKeyCount() {
		return hotKeyDetector.getExactSize();
	}

	@Override
	public <T> double getHotKeyQps(T key) {
		return hotKeyDetector.getQps(StringUtil.toStringKey(key));
	}

	@Override
	public void close() {
		hotKeyDetector.close();
	}

}
