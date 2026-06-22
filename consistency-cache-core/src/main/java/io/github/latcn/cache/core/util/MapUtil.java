package io.github.latcn.cache.core.util;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class MapUtil {

	private static final int MAX_EXPECTED_SIZE = (1 << 28) * 3;

	private static final int MAX_SELECTION_SIZE = 100000;

	/**
	 * For hashMap
	 * @param expectedSize
	 * @return
	 */
	public static int capacity(int expectedSize) {
		if (expectedSize < 0) {
			throw new IllegalArgumentException("expectedSize cannot be negative but was:" + expectedSize);
		}
		return expectedSize < MAX_EXPECTED_SIZE ? (int) Math.ceil((double) expectedSize / 0.75) : MAX_EXPECTED_SIZE;
	}

	public static <T> void copy(Set<T> oldSet, Set<T> newSet, boolean isRemove) {
		copy(oldSet, newSet, oldSet.size(), isRemove);
	}

	public static <T> void copy(Set<T> oldSet, Set<T> newSet, int maxExpectedSize, boolean isRemove) {
		int expectedSize = maxExpectedSize > oldSet.size() ? oldSet.size() : maxExpectedSize;

		if (expectedSize == 0) {
			return;
		}

		Iterator<T> iterator = oldSet.iterator();
		int count = 0;

		while (iterator.hasNext() && count < expectedSize) {
			T ele = iterator.next();
			newSet.add(ele);
			if (isRemove) {
				iterator.remove();
			}
			count++;
		}
	}

	/**
	 * 随机选取元素
	 * @param oldSet
	 * @param newSet
	 * @param maxExpectedSize
	 * @param isRemove
	 * @param <T>
	 */
	public static <T> void randomSelection(Set<T> oldSet, Set<T> newSet, int maxExpectedSize, boolean isRemove) {
		int actualSize = Math.min(maxExpectedSize, oldSet.size());
		if (actualSize <= 0) {
			return;
		}

		if (actualSize == oldSet.size()) {
			copy(oldSet, newSet, actualSize, isRemove);
			return;
		}

		Object[] elements = oldSet.toArray();
		ThreadLocalRandom random = ThreadLocalRandom.current();

		Set<T> selected = new HashSet<>(capacity(actualSize));

		for (int i = 0; i < actualSize; i++) {
			int j = random.nextInt(i, elements.length);

			Object temp = elements[i];
			elements[i] = elements[j];
			elements[j] = temp;

			selected.add((T) elements[i]);
		}

		newSet.addAll(selected);

		if (isRemove) {
			oldSet.removeAll(selected);
		}
	}

}
