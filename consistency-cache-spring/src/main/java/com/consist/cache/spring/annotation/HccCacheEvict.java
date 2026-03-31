package com.consist.cache.spring.annotation;

import java.lang.annotation.*;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface HccCacheEvict {

    String key();

    HccCacheBaseAnno baseCacheAnno() default @HccCacheBaseAnno;
}
