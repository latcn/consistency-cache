package io.github.latcn.cache.core.manager;

public interface CacheManager<K,V> {

    V get(K key);

    void put(K key, V cacheValue);

    void remove(K key);

    boolean containKey(K key);

}
