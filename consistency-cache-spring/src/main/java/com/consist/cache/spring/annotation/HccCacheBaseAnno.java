package com.consist.cache.spring.annotation;

import com.consist.cache.core.model.CacheLevel;
import com.consist.cache.core.model.ConsistencyLevel;

import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface HccCacheBaseAnno {

    ConsistencyLevel consistencyLevel() default ConsistencyLevel.HIGH;

    CacheLevel cacheLevel() default CacheLevel.ADAPTIVE_CACHE;

}
