package io.github.latcn.cache.core.hotspot;

import io.github.latcn.cache.core.hotspot.base.TwoLevelHotKeyDetector;
import io.github.latcn.cache.core.model.HccProperties;
import io.github.latcn.cache.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DefaultHotspotDetector implements HotspotDetector, AutoCloseable {

	private final TwoLevelHotKeyDetector hotKeyDetector;

	public DefaultHotspotDetector(HccProperties.HotspotProperties hotspotProperties) {
		this.hotKeyDetector = new TwoLevelHotKeyDetector(hotspotProperties.getTotalQps(), hotspotProperties.getHotQps(),
				hotspotProperties.getMaxAbsError(), hotspotProperties.getWindowMs(), hotspotProperties.getDepth(),
				hotspotProperties.getPromotionRatio(), hotspotProperties.getMaxExactSize(),
				hotspotProperties.getExpirationTimeMs(), hotspotProperties.getCleanupIntervalMs());
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
		return hotKeyDetector.getCurrentQps(StringUtil.toStringKey(key));
	}

	@Override
	public void close() {
		hotKeyDetector.close();
	}

}
