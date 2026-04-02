package com.consist.cache.spring.annotation;

import com.consist.cache.core.model.CacheLevel;
import com.consist.cache.core.model.ConsistencyLevel;

import java.lang.annotation.*;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface HccCacheEvict {

    String key();

    CacheLevel cacheLevel() default CacheLevel.ADAPTIVE_CACHE;

    ConsistencyLevel consistencyLevel() default ConsistencyLevel.HIGH;

    boolean broadcastEnabled() default true;

    boolean bloomFilterEnabled() default false;

    String bloomFilterName() default "";
}
