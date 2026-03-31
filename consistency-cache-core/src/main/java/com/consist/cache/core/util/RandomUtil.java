package com.consist.cache.core.util;

import java.util.concurrent.ThreadLocalRandom;

public class RandomUtil {

    private static final ThreadLocal<ThreadLocalRandom> localRandom = ThreadLocal.withInitial(ThreadLocalRandom::current);

    public static int nextInt(int max) {
        return localRandom.get().nextInt(max);
    }

    public static int boundRandom(int min, int max) {
        return min + localRandom.get().nextInt(max-min);
    }

    public static int halfBoundRandom(int max) {
        return boundRandom(max/2, max);
    }
}
