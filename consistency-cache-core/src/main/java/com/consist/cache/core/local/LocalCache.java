package com.consist.cache.core.local;

import java.util.function.Function;

/**
 * 本地缓存统一接口
 */
public interface LocalCache<K, V> {

    /**
     * 获取缓存，若不存在返回null
     */
    V get(K key);

    /**
     * 获取缓存，若不存在则通过loader加载并写入
     */
    V get(K key, Function<K, V> loader);

    /**
     * 放入缓存
     */
    void put(K key, V value);

    /**
     * 删除缓存
     */
    void invalidate(K key);

    /**
     * 清空缓存
     */
    void invalidateAll();

    /**
     * 清空缓存 cleanUp() forces immediate processing
     */
    void cleanUp();

    /**
     * 获取当前缓存总量
     * @return
     */
    long getSize();

    /**
     * 获取最大容量
     * @return
     */
    long getMaxSize();
}
