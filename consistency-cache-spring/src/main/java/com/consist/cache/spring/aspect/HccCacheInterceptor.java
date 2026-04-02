package com.consist.cache.spring.aspect;

import com.consist.cache.core.exception.CacheError;
import com.consist.cache.core.exception.CacheException;
import com.consist.cache.core.executor.CacheEvictHandler;
import com.consist.cache.core.executor.CacheExecutor;
import com.consist.cache.core.model.CacheKey;
import com.consist.cache.core.model.CacheValue;
import com.consist.cache.core.model.InvalidationRecord;
import com.consist.cache.core.model.NodeInstanceHolder;
import com.consist.cache.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class HccCacheInterceptor extends CacheInterceptor {

    private final CacheExecutor cacheExecutor;
    private final CacheEvictHandler cacheEvictHandler;
    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private final ConcurrentHashMap<String, Expression> expressionCache = new ConcurrentHashMap<>();
    private final Map<String, ActionWrapper> actionWrapperMap;

    public HccCacheInterceptor(CacheExecutor cacheExecutor, CacheEvictHandler cacheEvictHandler) {
        this.cacheExecutor = cacheExecutor;
        this.cacheEvictHandler = cacheEvictHandler;
        this.actionWrapperMap = Map.ofEntries(
                Map.entry("HccCacheable", this::handleHccCacheable),
                Map.entry("HccCacheEvict", this::handleHccCacheEvict)
        );
    }

    /**
     * 重写拦截逻辑，支持解析自定义注解
     */
    @Override
    public Object invoke(org.aopalliance.intercept.MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Object target = invocation.getThis();
        Assert.state(target != null, "Target must not be null");
        Class<?> targetClass =  AopProxyUtils.ultimateTargetClass(target);
        CacheOperationSource cacheOperationSource = getCacheOperationSource();
        if (cacheOperationSource != null) {
            Collection<CacheOperation> operations = cacheOperationSource.getCacheOperations(method, targetClass);
            if (!CollectionUtils.isEmpty(operations)) {
                Optional<CacheOperation> cacheOperation =
                         operations.stream().filter(a->a instanceof HccCacheAnnotationParser.CacheableOperationExt).findFirst();
                if (cacheOperation.isPresent()) {
                    HccCacheAnnotationParser.CacheableOperationExt cacheableOperationExt = (HccCacheAnnotationParser.CacheableOperationExt) cacheOperation.get();
                    ActionWrapper actionWrapper = actionWrapperMap.get(cacheableOperationExt.getName());
                    if (actionWrapper!=null) {
                        return actionWrapper.accept(invocation, cacheableOperationExt);
                    }
                }
            }
        }
        return super.invoke(invocation);
    }

    /**
     * @param invocation
     * @return
     * @throws Throwable
     */
    private Object handleHccCacheable(org.aopalliance.intercept.MethodInvocation invocation, HccCacheAnnotationParser.CacheableOperationExt cacheableOperationExt) throws Throwable {
        Method method = invocation.getMethod();
        CacheKey cacheKey = parseKey(method, invocation.getArguments(), cacheableOperationExt);
        try {
            return CacheValue.extractValue(cacheExecutor.get(cacheKey, (k) -> {
                try {
                    return invocation.proceed();
                } catch (Throwable e) {
                    // Unwrap and rethrow original exception
                    if (e instanceof RuntimeException) {
                        throw (RuntimeException) e;
                    }
                    throw new RuntimeException(e);
                }
            }));
        } catch (RuntimeException e) {
            // Rethrow business exceptions directly
            throw e;
        } catch (Exception e) {
            // 降级保护：缓存出错不影响业务
            log.warn("HCC Cache error, fallback to method execution. cacheKey: {}", cacheKey, e);
            return invocation.proceed();
        }
    }

    /**
     * 处理缓存失效
     * @param invocation
     * @param cacheableOperationExt
     * @return
     * @throws Throwable
     */
    private Object handleHccCacheEvict(org.aopalliance.intercept.MethodInvocation invocation, HccCacheAnnotationParser.CacheableOperationExt cacheableOperationExt) throws Throwable {
        InvalidationRecord invalidationRecord = new InvalidationRecord();
        CacheKey cacheKey = parseKey(invocation.getMethod(), invocation.getArguments(), cacheableOperationExt);
        invalidationRecord.setCacheKey(cacheKey.getKey().toString());
        invalidationRecord.setUid(NodeInstanceHolder.getSnowflakeGenerator().next().toString());
        invalidationRecord.setCacheLevel(cacheKey.getCacheLevel().toString());
        invalidationRecord.setConsistencyLevel(cacheKey.getConsistencyLevel().toString());
        invalidationRecord.setNodeId(NodeInstanceHolder.getNodeId());
        invalidationRecord.setOperationType(InvalidationRecord.OperationType.DELETE.toString());
        Object result = null;
        try {
            result = cacheEvictHandler.startInvalidate(invalidationRecord, ()->invocation.proceed());
            cacheEvictHandler.addToSuccess(invalidationRecord);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    private CacheKey parseKey(Method method, Object[] args, HccCacheAnnotationParser.CacheableOperationExt cacheableOperationExt) {
        ParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();
        String[] paramNames = discoverer.getParameterNames(method);
        String spELString = cacheableOperationExt.getKey();
        // 获取缓存的表达式对象，避免重复编译
        Expression expression = expressionCache.computeIfAbsent(spELString, PARSER::parseExpression);
        // 创建上下文
        EvaluationContext context = new StandardEvaluationContext();
        for (int i = 0; i < paramNames.length; i++) {
            context.setVariable(paramNames[i], args[i]);
        }
        String actualKey = expression.getValue(context, String.class);
        if (StringUtil.isNullOrEmpty(actualKey)) {
            throw CacheException.newInstance(CacheError.EMPTY_KEY);
        }
        return CacheKey.builder()
                .key(actualKey)
                .expireTimeMs(cacheableOperationExt.getExpireTime())
                .consistencyLevel(cacheableOperationExt.getConsistencyLevel())
                .cacheLevel(cacheableOperationExt.getCacheLevel())
                .bloomFilterEnabled(cacheableOperationExt.isBloomFilterEnabled())
                .cacheNullValues(cacheableOperationExt.isCacheNullValues())
                .broadcastEnabled(cacheableOperationExt.isBroadcastEnabled())
                .bloomFilterEnabled(cacheableOperationExt.isBloomFilterEnabled())
                .bloomFilterName(cacheableOperationExt.getBloomFilterName())
                .build();
    }

    @FunctionalInterface
    interface ActionWrapper {
        Object accept(MethodInvocation invocation, HccCacheAnnotationParser.CacheableOperationExt cacheableOperationExt) throws Throwable;
    }
}
