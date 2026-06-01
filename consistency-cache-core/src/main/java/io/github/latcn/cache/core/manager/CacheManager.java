package io.github.latcn.cache.core.manager;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public interface CacheManager<K, V> {

    V get(K key);

    void put(K key, V cacheValue);

    void remove(K key);

    boolean containKey(K key);

    default V getIfAbsent(K key, Supplier<V> factory) {
        V value = get(key);
        if (value == null) {
            value = factory.get();
            if (value != null) {
                put(key, value);
            }
        }
        return value;
    }

    default Map<K, V> getAll(Collection<K> keys) {
        throw new UnsupportedOperationException("getAll not supported");
    }

    default CompletableFuture<V> getAsync(K key) {
        return CompletableFuture.supplyAsync(() -> get(key));
    }

    default void putAll(Map<K, V> entries) {
        entries.forEach(this::put);
    }

    default V putIfAbsent(K key, V value) {
        if (!containKey(key)) {
            put(key, value);
            return null;
        }
        return get(key);
    }

    default V putIfAbsent(K key, V value, Duration ttl) {
        throw new UnsupportedOperationException("putIfAbsent with TTL not supported");
    }

    default boolean expire(K key, Duration ttl) {
        throw new UnsupportedOperationException("expire not supported");
    }

    default Duration getExpire(K key) {
        throw new UnsupportedOperationException("getExpire not supported");
    }

    default long size() {
        throw new UnsupportedOperationException("size not supported");
    }

    default void clear() {
        throw new UnsupportedOperationException("clear not supported");
    }
}