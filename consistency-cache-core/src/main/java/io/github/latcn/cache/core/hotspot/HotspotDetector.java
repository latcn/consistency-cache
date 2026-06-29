package io.github.latcn.cache.core.hotspot;

public interface HotspotDetector {

	<T> void record(T key);

	<T> boolean isHotKey(T key);

	long getHotKeyCount();

	<T> double getHotKeyQps(T key);

}
