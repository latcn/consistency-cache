package com.consist.cache.spring.aspect;

import com.consist.cache.core.model.CacheLevel;
import com.consist.cache.core.model.CacheValue;
import com.consist.cache.core.model.ConsistencyLevel;
import com.consist.cache.spring.annotation.HccCacheEvict;
import com.consist.cache.spring.annotation.HccCacheable;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.springframework.cache.annotation.CacheAnnotationParser;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CacheableOperation;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * from SpringCacheAnnotationParser
 */
public class HccCacheAnnotationParser implements CacheAnnotationParser {

    private static final Set<Class<? extends Annotation>> CACHE_OPERATION_ANNOTATIONS = new LinkedHashSet<>(8);

    static {
        CACHE_OPERATION_ANNOTATIONS.add(HccCacheable.class);
        CACHE_OPERATION_ANNOTATIONS.add(HccCacheEvict.class);
    }

    @Override
    public Collection<CacheOperation> parseCacheAnnotations(Class<?> type) {
        return parseCacheAnnotations(type, false);
    }

    @Override
    public Collection<CacheOperation> parseCacheAnnotations(Method method) {
        return parseCacheAnnotations(method, false);
    }

    static Set<Class<? extends Annotation>> supportAnnotation() {
        return CACHE_OPERATION_ANNOTATIONS;
    }


    private Collection<CacheOperation> parseCacheAnnotations(AnnotatedElement ae, boolean localOnly) {
        Collection<? extends Annotation> anns = (localOnly ?
                AnnotatedElementUtils.getAllMergedAnnotations(ae, CACHE_OPERATION_ANNOTATIONS) :
                AnnotatedElementUtils.findAllMergedAnnotations(ae, CACHE_OPERATION_ANNOTATIONS));
        if (anns.isEmpty()) {
            return null;
        }
        final Collection<CacheOperation> ops = new ArrayList<>(1);
        anns.stream().filter(ann -> ann instanceof HccCacheable).forEach(
                ann -> ops.add(parseCacheableAnnotation(ae, (HccCacheable) ann)));
        anns.stream().filter(ann -> ann instanceof HccCacheEvict).forEach(
                ann -> ops.add(parseCacheableAnnotation(ae, (HccCacheEvict) ann)));
        return ops;
    }

    private CacheableOperationExt parseCacheableAnnotation(AnnotatedElement ae, HccCacheable hccCacheable) {
        CacheableOperationExt op = new CacheableOperationExt
                .Builder()
                .name(hccCacheable.annotationType().getSimpleName())
                .key(hccCacheable.key())
                .consistencyLevel(hccCacheable.consistencyLevel())
                .cacheLevel(hccCacheable.cacheLevel())
                .bloomFilterEnabled(hccCacheable.bloomFilterEnabled())
                .cacheNullValues(hccCacheable.cacheNullValues())
                .broadcastEnabled(hccCacheable.broadcastEnabled())
                .bloomFilterEnabled(hccCacheable.bloomFilterEnabled())
                .bloomFilterName(hccCacheable.bloomFilterName())
                .build();
        if (hccCacheable.expireTime()==0) {
            op.setExpireTime(CacheValue.MAX_EXPIRE_TIME);
        } else {
            op.setExpireTime(hccCacheable.expireTime()*1000);
        }
        return op;
    }

    private CacheableOperationExt parseCacheableAnnotation(AnnotatedElement ae, HccCacheEvict hccCacheEvict) {
        CacheableOperationExt op = new CacheableOperationExt
                .Builder()
                .name(hccCacheEvict.annotationType().getSimpleName())
                .key(hccCacheEvict.key())
                .consistencyLevel(hccCacheEvict.consistencyLevel())
                .cacheLevel(hccCacheEvict.cacheLevel())
                .broadcastEnabled(hccCacheEvict.broadcastEnabled())
                .bloomFilterEnabled(hccCacheEvict.bloomFilterEnabled())
                .bloomFilterName(hccCacheEvict.bloomFilterName())
                .build();
        return op;
    }

    @Getter
    @Setter
    static class CacheableOperationExt extends CacheableOperation {

        private String name;
        private String key;
        private long expireTime;
        private ConsistencyLevel consistencyLevel;
        private CacheLevel cacheLevel;
        private boolean bloomFilterEnabled;
        private boolean cacheNullValues;
        private boolean broadcastEnabled;
        private String bloomFilterName;

        public CacheableOperationExt() {
            super(new CacheableOperation.Builder());
        }

        public static class Builder extends CacheOperation.Builder {
            private String name;
            private String key;
            private long expireTime;
            private ConsistencyLevel consistencyLevel;
            private CacheLevel cacheLevel;
            private boolean bloomFilterEnabled;
            private boolean cacheNullValues;
            private boolean broadcastEnabled;
            private String bloomFilterName;

            public Builder() {
            }

            public Builder name(String name) {
                this.name = name;
                return this;
            }

            public Builder key(String key) {
                this.key = key;
                return this;
            }

            public Builder expireTime(long expireTime) {
                this.expireTime = expireTime;
                return this;
            }

            public Builder consistencyLevel(ConsistencyLevel consistencyLevel) {
                this.consistencyLevel = consistencyLevel;
                return this;
            }

            public Builder cacheLevel(CacheLevel cacheLevel) {
                this.cacheLevel = cacheLevel;
                return this;
            }

            public Builder bloomFilterEnabled(boolean bloomFilterEnabled) {
                this.bloomFilterEnabled = bloomFilterEnabled;
                return this;
            }

            public Builder cacheNullValues(boolean cacheNullValues) {
                this.cacheNullValues = cacheNullValues;
                return this;
            }

            public Builder broadcastEnabled(boolean broadcastEnabled) {
                this.broadcastEnabled = broadcastEnabled;
                return this;
            }

            public Builder bloomFilterName(String bloomFilterName) {
                this.bloomFilterName = bloomFilterName;
                return this;
            }

            @Override
            public CacheableOperationExt build() {
                CacheableOperationExt cacheableOperationExt = new CacheableOperationExt();
                cacheableOperationExt.setName(this.name);
                cacheableOperationExt.setKey(this.key);
                cacheableOperationExt.setCacheLevel(this.cacheLevel);
                cacheableOperationExt.setConsistencyLevel(this.consistencyLevel);
                cacheableOperationExt.setExpireTime(this.expireTime);
                cacheableOperationExt.setBloomFilterEnabled(this.bloomFilterEnabled);
                cacheableOperationExt.setCacheNullValues(this.cacheNullValues);
                cacheableOperationExt.setBroadcastEnabled(this.broadcastEnabled);
                cacheableOperationExt.setBloomFilterName(this.bloomFilterName);
                return cacheableOperationExt;
            }
        }
    }

}