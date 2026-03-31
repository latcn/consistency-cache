package com.consist.cache.spring.pubsub;

import com.consist.cache.core.pubsub.BroadcastMessage;
import com.consist.cache.core.pubsub.BroadcastPublisher;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RFuture;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import java.util.Set;

/**
 * RTopic (原生 Pub/Sub)
 * 1. 消息持久性： 消息不落盘，直接推送给在线客户端，客户端断线时消息会丢失
 * 2. 性能： 极高，无磁盘io，纯内存推送
 * 3. 广播： 天然支持，所有订阅者都会收到消息
 * 4. 适用场景： 实时心跳、缓存失效通知，即时通信、配置开关切换等
 */
@Slf4j
public class RTopicPublisher implements BroadcastPublisher {

    private final RedissonClient redissonClient;

    public RTopicPublisher(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 异步广播消息
     */
    @Override
    public void broadcastMessage(Set<String> channelNames, BroadcastMessage message) {
        if (channelNames==null || channelNames.size() == 0) {
            return;
        }
        for (String channelName: channelNames) {
            RTopic topic = redissonClient.getTopic(channelName);
            // 【最佳实践】使用异步发布，避免阻塞主线程
            // 返回的 Future 可以做简单的日志记录，不要空着
            RFuture<Long> future = topic.publishAsync(message);
            future.whenComplete((receiverCount, ex) -> {
                if (ex != null) {
                    log.error("广播发送失败: {}", ex.getMessage());
                } else {
                    // receiverCount 表示当前有多少个在线客户端收到了消息
                    // 如果为 0，说明当前没有订阅者，但消息已发出（且丢失）
                    log.info("广播成功，在线接收者数量: {}", receiverCount);
                }
            });
        }
    }
}
