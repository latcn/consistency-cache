package io.github.latcn.cache.spring.annotation;

import io.github.latcn.cache.core.model.CacheLevel;
import io.github.latcn.cache.core.model.ConsistencyLevel;
import io.github.latcn.cache.core.model.InvalidationRecord;
import java.lang.annotation.*;

@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface HccCacheEvict {

	String key();

	CacheLevel cacheLevel() default CacheLevel.ADAPTIVE_CACHE;

	ConsistencyLevel consistencyLevel() default ConsistencyLevel.HIGH;

	boolean transactionEnabled() default false;

	boolean fallbackExecActual() default false;

	boolean broadcastEnabled() default true;

	boolean bloomFilterEnabled() default false;

	String bloomFilterName() default "";

	InvalidationRecord.EvictPolicy evictPolicy() default InvalidationRecord.EvictPolicy.DELAYED;

}
