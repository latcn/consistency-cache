package com.consist.cache.spring.annotation;

import com.consist.cache.core.model.CacheLevel;
import com.consist.cache.core.model.ConsistencyLevel;

import java.lang.annotation.*;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface HccCacheable {

    String key();

    /**
     * seconds
     * @return
     */
    long expireTime() default 0;

    CacheLevel cacheLevel() default CacheLevel.ADAPTIVE_CACHE;

    ConsistencyLevel consistencyLevel() default ConsistencyLevel.HIGH;

    boolean bloomFilterEnabled() default false;

    boolean cacheNullValues() default true;
}
