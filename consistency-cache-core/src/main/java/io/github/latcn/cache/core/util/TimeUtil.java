package io.github.latcn.cache.core.util;

public class TimeUtil {

    public static final int NANO_TO_MIL = 1000_000;

    public static long currentNanoToMil() {
        return System.nanoTime()/NANO_TO_MIL;
    }
}
