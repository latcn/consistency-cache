package com.consist.cache.spring.service;

import com.consist.cache.spring.annotation.HccCacheEvict;
import com.consist.cache.spring.annotation.HccCacheable;
import org.springframework.stereotype.Service;

/**
 * Test service with various cache annotations
 */
@Service
public class TestService {

    @HccCacheable(key = "#id", expireTime = 300)
    public String getDataWithCache(Long id) {
        return "actual-data-" + id;
    }

    @HccCacheEvict(key = "#id")
    public String deleteData(Long id) {
        return "deleted-" + id;
    }

    @HccCacheable(key = "#userId", expireTime = 600)
    public String getUserById(Long userId) {
        return "user-" + userId;
    }

    @HccCacheable(key = "#type + '-' + #id", expireTime = 300)
    public String getCompositeData(String type, Long id) {
        return type + "-data-" + id;
    }

    public String getWithoutAnnotation(String param) {
        return param + "-result";
    }

    @HccCacheable(key = "'nullable'", expireTime = 60)
    public String getNullableData(String data) {
        return data;
    }
}
