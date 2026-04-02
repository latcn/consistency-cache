package com.consist.cache.core.executor;

import java.util.List;

public interface CacheBloomFilter {

    <T> boolean exists(String filterName, T cacheKey);

    <T> void add(String filterName, T cacheKey);

    <T extends List> void addList(String filterName, T cacheKeys);

    <T> void remove(String filterName, T cacheKey);

    <T extends List> void removeList(String filterName, T cacheKeys);
}
