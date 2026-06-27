package io.github.latcn.cache.core.hotspot.reads;

import io.github.latcn.cache.core.hotspot.TwoLevelHotKeyDetector;
import io.github.latcn.cache.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DefaultReadHotspotDetector implements ReadHotspotDetector, AutoCloseable {

	private final TwoLevelHotKeyDetector hotKeyDetector;

	private final double hotKeyThreshold;

	public DefaultReadHotspotDetector(double hotKeyThreshold) {
		this.hotKeyThreshold = hotKeyThreshold;
		this.hotKeyDetector = TwoLevelHotKeyDetector.Builder.forHighQps()
			.hotKeyThreshold((long) hotKeyThreshold)
			.promotionThreshold((long) (hotKeyThreshold * 0.7))
			.build();
		log.info("Initialized DefaultReadHotspotDetector with threshold={} QPS", hotKeyThreshold);
	}

	@Override
	public <T> void recordRead(T key) {
		hotKeyDetector.record(StringUtil.toStringKey(key));
	}

	@Override
	public <T> boolean isHotKey(T key) {
		return hotKeyDetector.isHotKey(StringUtil.toStringKey(key));
	}

	@Override
	public long readHotKeyCount() {
		return hotKeyDetector.getExactSize();
	}

	public <T> double getQps(T key) {
		if (hotKeyDetector.isHotKey(StringUtil.toStringKey(key))) {
			return hotKeyThreshold;
		}
		return 0.0;
	}

	@Override
	public void close() {
		hotKeyDetector.close();
	}

}