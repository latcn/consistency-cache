package com.consist.cache.core.pubsub;


import com.consist.cache.core.exception.CacheError;
import com.consist.cache.core.exception.CacheException;
import lombok.extern.slf4j.Slf4j;

import java.util.EventListener;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
public abstract class BroadcasterListener<T> implements EventListener {

    protected final List<String> topics;
    private final ExecutorService businessExecutor;

    public BroadcasterListener(List<String> topics, ExecutorService businessExecutor) {
        if (topics==null || topics.isEmpty()) {
            throw CacheException.newInstance(CacheError.EMPTY_BROADCASTER_TOPIC);
        }
        this.topics = topics;
        if (businessExecutor==null) {
            this.businessExecutor = new ThreadPoolExecutor(4, 4, 30,
                    TimeUnit.SECONDS, new LinkedBlockingQueue<>(1024), new ThreadPoolExecutor.CallerRunsPolicy());
        } else {
            this.businessExecutor = businessExecutor;
        }
    }

    public void onMessage(CharSequence channel, T msg) {
        // 【广播逻辑】：每个实例都会执行到这里
        log.info("receive message:{}", msg);
        // 消息去重等检测 执行本地逻辑
        this.businessExecutor.submit(() -> doProcess((String)channel, msg));
    }

    protected abstract void doProcess(String topic, T msg);

    public List<String> getTopics() {
        return this.topics;
    }
}
