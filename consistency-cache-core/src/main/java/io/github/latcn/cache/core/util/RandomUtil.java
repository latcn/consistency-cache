package io.github.latcn.cache.core.util;

import java.util.concurrent.ThreadLocalRandom;

public class RandomUtil {

	public static int nextInt(int max) {
		return ThreadLocalRandom.current().nextInt(max);
	}

	public static int boundRandom(int min, int max) {
		return min + ThreadLocalRandom.current().nextInt(max - min);
	}

	public static int halfBoundRandom(int max) {
		return boundRandom(max / 2, max);
	}

}
