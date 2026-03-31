package com.consist.cache.spring.pubsub;

import com.consist.cache.core.pubsub.BroadcastMessage;
import com.consist.cache.core.pubsub.BroadcastSubscriber;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

public class RTopicSubscriber implements BroadcastSubscriber<Integer, InvalidationListener> {

    private final RedissonClient redissonClient;

    public RTopicSubscriber(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public Integer broadcastSubscribe(String channelName, InvalidationListener listener) {
        RTopic rTopic = redissonClient.getTopic(channelName);
        // 注册监听器
        return rTopic.addListener(BroadcastMessage.class, listener);
    }

    @Override
    public void removeSubscribe(String channelName, Integer listenerId) {
        // 销毁时移除监听器
        RTopic topic = redissonClient.getTopic(channelName);
        topic.removeListener(listenerId);
    }
}
