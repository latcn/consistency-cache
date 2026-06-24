package io.github.latcn.cache.core.handler;

import io.github.latcn.cache.core.executor.CacheExecutorConfig;
import java.util.HashMap;
import java.util.Map;

public abstract class BaseCacheHandler implements CacheHandler {

	protected static final String IS_WRITE_HOT_KEY = "isWriteHotKey";

	protected CacheHandler next;

	protected CacheExecutorConfig cacheExecutorConfig;

	public BaseCacheHandler(CacheHandler next, CacheExecutorConfig cacheExecutorConfig) {
		this.next = next;
		this.cacheExecutorConfig = cacheExecutorConfig;
	}

	protected boolean isWriteHotKey(CacheContext cacheContext) {
		Map<String, Object> params = cacheContext.getParams();
		if (params == null) {
			return false;
		}
		return (boolean) params.getOrDefault(IS_WRITE_HOT_KEY, false);
	}

	protected void setIsWriteHotKey(CacheContext cacheContext, boolean isWriteHotKey) {
		Map<String, Object> params = cacheContext.getParams();
		if (params == null) {
			params = new HashMap<>();
			cacheContext.setParams(params);
		}
		params.put(IS_WRITE_HOT_KEY, isWriteHotKey);
	}

}
