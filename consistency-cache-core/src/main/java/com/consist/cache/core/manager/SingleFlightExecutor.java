package com.consist.cache.core.manager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * 线程leader模式获取数据
 */
public class SingleFlightExecutor {

    private final ConcurrentHashMap<Object, CompletableFuture<Object>>  inflightCalls = new ConcurrentHashMap<>();

    public <K,V> V execute(K key, Function<K, V> doSingleFlightFun) {
        CompletableFuture<Object> future = this.inflightCalls.get(key);
        if (future == null) {
            CompletableFuture<Object> newFuture = new CompletableFuture<>();
            future = this.inflightCalls.putIfAbsent(key, newFuture);
            // old future is null
            if (future==null) {
                future = newFuture;
                try {
                    V result = doSingleFlightFun.apply(key);
                    future.complete(result);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                    throw new RuntimeException(e);
                } finally {
                    this.inflightCalls.remove(key);
                }
            }
        }
        try {
            return (V) future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }


    public static void main(String[] args) {
        Thread t1 = new Thread(()->{
            try {
                Thread.sleep(1000);
                System.out.println("sub 1:"+Thread.currentThread().isInterrupted());
            } catch (InterruptedException e) {
                System.out.println("sub 2:"+Thread.currentThread().isInterrupted());
                Thread.currentThread().interrupt();
                System.out.println("sub 3:"+Thread.currentThread().isInterrupted());
                throw new RuntimeException(e);
            }
        });
        t1.start();
        t1.interrupt();
        System.out.println("main:"+t1.isInterrupted());
    }
}
