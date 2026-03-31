package com.consist.cache.spring.pubsub;

import com.consist.cache.core.pubsub.BroadcastMessage;
import com.consist.cache.core.pubsub.BroadcastSubscriber;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RReliableTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;

@Slf4j
public class ReliableSubscriber implements BroadcastSubscriber<String, InvalidationListener> {

    private final RedissonClient redissonClient;

    private ReliableSubscriber(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * application start 时调用
     * @param channelName
     * @param listener
     */
    @Override
    public String broadcastSubscribe(String channelName, InvalidationListener listener) {
        RReliableTopic topic = redissonClient.getReliableTopic(channelName);
        // 添加监听器 Redisson 内部会维护当前客户端的消费进度
        return topic.addListener(BroadcastMessage.class, (MessageListener)listener);
    }

    /**
     * removeSubscribe
     * @param channelName
     * @param listenerId
     */
    @Override
    public void removeSubscribe(String channelName, String listenerId) {
        RReliableTopic topic = redissonClient.getReliableTopic(channelName);
        topic.removeListener(listenerId);
    }
}
