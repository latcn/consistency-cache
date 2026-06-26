package io.github.latcn.cache.core.util;

public class StringUtil {

	public static boolean isNullOrEmpty(String s) {
		return s == null || s.isEmpty();
	}

	public static <T> String toStringKey(T key) {
		return key == null ? "null" : key.getClass().getName()+":"+ key;
	}

}
