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
        // Fast path: check existing future
        CompletableFuture<Object> future = this.inflightCalls.get(key);
        if (future != null) {
            return waitForResult(future);
        }
        
        // Slow path: attempt to become leader
        CompletableFuture<Object> newFuture = new CompletableFuture<>();
        CompletableFuture<Object> existing = this.inflightCalls.putIfAbsent(key, newFuture);
        
        if (existing != null) {
            return waitForResult(existing);
        }
        
        // Leader execution
        try {
            V result = (V) doSingleFlightFun.apply(key);
            newFuture.complete(result);
            return result;
        } catch (Throwable t) {
            newFuture.completeExceptionally(t);
            throw t;
        } finally {
            this.inflightCalls.remove(key);
        }
    }
    
    /**
     * Wait for result from a future, handling interruptions and execution exceptions.
     */
    @SuppressWarnings("unchecked")
    private <V> V waitForResult(CompletableFuture<Object> future) {
        try {
            return (V) future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("SingleFlight interrupted while waiting for result", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            } else if (cause instanceof Error) {
                throw (Error) cause;
            } else {
                throw new RuntimeException("SingleFlight execution failed", cause);
            }
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
