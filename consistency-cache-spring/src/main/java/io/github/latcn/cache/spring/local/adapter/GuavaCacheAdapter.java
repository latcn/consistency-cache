package io.github.latcn.cache.spring.local.adapter;

import com.google.common.cache.CacheBuilder;
import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import io.github.latcn.cache.core.local.LocalCache;
import io.github.latcn.cache.core.model.CacheValue;
import io.github.latcn.cache.core.model.HccProperties;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class GuavaCacheAdapter<K, V extends CacheValue> implements LocalCache<K, V> {

	private final com.google.common.cache.Cache<K, V> localCache;

	private final long maxSize;

	public GuavaCacheAdapter(HccProperties.LocalCacheProperties properties) {
		this.localCache = CacheBuilder.newBuilder()
			.initialCapacity(properties.getInitialCapacity())
			.maximumSize(properties.getMaximumSize())
			.expireAfterWrite(properties.getExpireAfterWrite(), TimeUnit.SECONDS)
			.expireAfterAccess(properties.getExpireAfterAccess(), TimeUnit.SECONDS)
			.build();
		this.maxSize = properties.getMaximumSize();
	}

	@Override
	public V get(K key) {
		return this.localCache.getIfPresent(key);
	}

	@Override
	public V get(K key, Function<K, V> loader) {
		try {
			return this.localCache.get(key, () -> loader.apply(key));
		}
		catch (Exception e) {
			throw CacheException.wrap(e, CacheError.LOCAL_CACHE_LOAD_FAILED);
		}
	}

	@Override
	public void put(K key, V value) {
		this.localCache.put(key, value);
	}

	@Override
	public void invalidate(K key) {
		this.localCache.invalidate(key);
	}

	@Override
	public void invalidateAll() {
		this.localCache.invalidateAll();
	}

	@Override
	public void cleanUp() {
		this.localCache.cleanUp();
	}

	@Override
	public long getSize() {
		return this.localCache.size();
	}

	@Override
	public long getMaxSize() {
		return this.maxSize;
	}

}
