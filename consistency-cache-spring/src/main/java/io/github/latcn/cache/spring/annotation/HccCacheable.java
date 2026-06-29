package io.github.latcn.cache.spring.annotation;

import io.github.latcn.cache.core.model.CacheLevel;
import io.github.latcn.cache.core.model.ConsistencyLevel;
import java.lang.annotation.*;

@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface HccCacheable {

	String key();

	/**
	 * seconds
	 * @return
	 */
	long ttl() default 0;

	CacheLevel cacheLevel() default CacheLevel.ADAPTIVE_CACHE;

	ConsistencyLevel consistencyLevel() default ConsistencyLevel.HIGH;

	boolean fallbackExecActual() default false;

	boolean bloomFilterEnabled() default false;

	boolean cacheNullValues() default true;

	boolean broadcastEnabled() default true;

	String bloomFilterName() default "";

}
