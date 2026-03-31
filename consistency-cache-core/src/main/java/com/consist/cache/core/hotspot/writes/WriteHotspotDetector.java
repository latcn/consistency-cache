package com.consist.cache.core.hotspot.writes;

public interface WriteHotspotDetector {

    <T> void recordInvalidation(T key);

    <T> boolean shouldBypassL1(T key);
}
