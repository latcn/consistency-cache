package com.consist.cache.core.pubsub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 广播、订阅初始化处理
 * 生产者消费者模型：
 *    为避免广播消息爆炸，间隔时间或累积一定量的消息后才统一发送
 *    累积keys的消息大小对发送速率的影响
 */
public abstract class Broadcaster<T,S extends BroadcasterListener> {

    protected final BroadcastPublisher publisher;
    private final BroadcastSubscriber subscriber;
    private final List<BroadcasterListener> listeners;
    private final Map<String,List<T>> topicListenerIds = new HashMap<>();
    private final ScheduledThreadPoolExecutor scheduledExecutor;

    /**
     * 累积未发送数量和最大等待时间，两者满足一个，则进行广播消息
     */
    protected final int batchSize;
    private final int maxWaitSeconds;

    public Broadcaster(BroadcastPublisher publisher,
                       BroadcastSubscriber<T,S> subscriber,
                       List<BroadcasterListener> listeners,
                       int batchSize,
                       int maxWaitSeconds) {
        this.publisher = publisher;
        this.subscriber = subscriber;
        this.listeners = listeners;
        this.batchSize = batchSize;
        this.maxWaitSeconds = maxWaitSeconds;
        this.scheduledExecutor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("Broadcaster-ScheduledExecutor-Thread");
            return new Thread(r);
        });
        // 定时扫描
        scheduledExecutor.scheduleWithFixedDelay(
                this::publish,
                this.maxWaitSeconds,
                this.maxWaitSeconds,
                TimeUnit.SECONDS
        );
    }

    /**
     * 启动时 add listener
     */
    public void init() {
        for (BroadcasterListener listener: this.listeners) {
            for (String topic: (List<String>)listener.getTopics()) {
                T listenerId = (T)this.subscriber.broadcastSubscribe(topic, listener);
                List<T> listenerIds = this.topicListenerIds.get(topic);
                if (listenerIds==null) {
                    listenerIds = new ArrayList<>();
                    List<T> oldListenerIds = this.topicListenerIds.putIfAbsent(topic, listenerIds);
                    if(oldListenerIds!=null) {
                        listenerIds = oldListenerIds;
                    }
                }
                listenerIds.add(listenerId);
            }
        }
    }

    public abstract void addKey(Object key);
    public abstract void publish();

    /**
     * shutdown时，remove listener
     */
    public void preDestroy() {
        shutdown();
        for (Map.Entry<String,List<T>> entry: this.topicListenerIds.entrySet()) {
            for (T listenerId: entry.getValue()) {
                this.subscriber.removeSubscribe(entry.getKey(), listenerId);
            }
        }
    }

    /**
     * Shutdown
     */
    private void shutdown() {
        // Final flush before shutdown
        publish();
        this.scheduledExecutor.shutdown();
        try {
            if (!this.scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                this.scheduledExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.scheduledExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
