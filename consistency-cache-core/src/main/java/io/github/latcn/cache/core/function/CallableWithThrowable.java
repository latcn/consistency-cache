package io.github.latcn.cache.core.function;

@FunctionalInterface
public interface CallableWithThrowable<T> {

    T apply() throws Throwable;
}
