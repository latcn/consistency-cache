package com.consist.cache.core.hotspot.reads;

public interface ReadHotspotDetector {

    <T> void recordRead(T key);

    <T> boolean isHotKey(T key);

    long readHotKeyCount();
}
