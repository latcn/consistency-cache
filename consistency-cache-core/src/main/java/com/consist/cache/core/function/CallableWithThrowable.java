package com.consist.cache.core.function;

@FunctionalInterface
public interface CallableWithThrowable<T> {

    T apply() throws Throwable;
}
