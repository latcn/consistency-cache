package com.consist.cache.spring.executor;

import com.consist.cache.core.executor.CacheBloomFilter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RCuckooFilter;
import org.redisson.api.RedissonClient;
import org.redisson.api.cuckoofilter.CuckooFilterAddArgs;
import org.redisson.api.cuckoofilter.CuckooFilterInitArgs;
import org.redisson.client.codec.StringCodec;

import java.util.List;

@Slf4j
public class EnhanceRCuckooFilter implements CacheBloomFilter {

    private final RedissonClient redissonClient;

    public EnhanceRCuckooFilter(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public void initFilter(String filterName, CuckooFilterInitArgs initArgs) {
        getRCuckooFilter(filterName).init(initArgs);
    }

    @Override
    public <T> boolean exists(String filterName, T cacheKey) {
        try {
            return getRCuckooFilter(filterName).exists(cacheKey.toString());
        } catch (Exception e) {
            log.error("exists {}, {}", filterName, cacheKey);
        }
        return false;
    }

    @Override
    public <T> void add(String filterName, T cacheKey) {
        try {
             getRCuckooFilter(filterName).addIfAbsent(cacheKey.toString());
        } catch (Exception e) {
            log.error("add {}, {}", filterName, cacheKey);
        }
    }

    @Override
    public <T extends List> void addList(String filterName, T cacheKeys) {
        try {
            getRCuckooFilter(filterName).addIfAbsent(
                    CuckooFilterAddArgs.<String>items(cacheKeys)
                            .capacity(cacheKeys.size())
            );
        } catch (Exception e) {
            log.error("addList {}, {}", filterName, cacheKeys);
        }
    }

    @Override
    public <T> void remove(String filterName, T cacheKey) {
        try {
            getRCuckooFilter(filterName).remove(cacheKey.toString());
        } catch (Exception e) {
            log.error("remove {}, {}", filterName, cacheKey);
        }
    }

    @Override
    public <T extends List> void removeList(String filterName, T cacheKeys) {
        try {
            for (Object element: cacheKeys) {
                getRCuckooFilter(filterName).remove(element.toString());
            }
        } catch (Exception e) {
            log.error("removeList {}, {}", filterName, cacheKeys);
        }
    }

    private RCuckooFilter<String> getRCuckooFilter(String filterName) {
        return redissonClient.getCuckooFilter(filterName, StringCodec.INSTANCE);
    }
}
