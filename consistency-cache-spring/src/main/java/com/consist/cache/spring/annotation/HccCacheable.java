package com.consist.cache.spring.annotation;

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

    HccCacheBaseAnno baseCacheAnno() default @HccCacheBaseAnno;
}
