package com.consist.cache.spring.pubsub;

import com.consist.cache.core.pubsub.BroadcastMessage;
import com.consist.cache.core.pubsub.BroadcastPublisher;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RReliableTopic;
import org.redisson.api.RedissonClient;

import java.util.Set;

/**
 * Config config = new Config();
 * config.useSingleServer().setAddress("redis://127.0.0.1:6379");
 *
 * // 针对 ReliableTopic 的配置
 * config.setReliableTopicWatchdogTimeout(30 * 60 * 1000); // 看门狗超时时间（毫秒），默认30分钟
 * // 如果客户端在此时间内未心跳，Redis会认为其离线，可能会清理其消费组（视具体策略而定）
 *
 * RedissonClient redisson = Redisson.create(config);
 *
 *  RReliableTopic (基于 Stream)
 *  1. 消息持久性：支持，消息存储在 Redis Stream 中，重连后可消费离线消息
 *  2. 性能： 较高，涉及磁盘写入和读取
 *  3. 广播消息：支持，但每个实例需维护独立消费组
 *  4. 适用场景: 订单处理、重要事件通知、需要可靠性的场景
 */
@Slf4j
public class ReliablePublisher implements BroadcastPublisher {

    private final RedissonClient redissonClient;

    public ReliablePublisher(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 本地缓存失效发布变更广播消息
     */
    @Override
    public void broadcastMessage(Set<String> channelNames, BroadcastMessage message) {
        if (channelNames==null || channelNames.size() == 0) {
            return;
        }
        for (String channelName: channelNames) {
            // 获取 Reliable Topic
            RReliableTopic topic = redissonClient.getReliableTopic(channelName);
            // 异步发布，避免阻塞主线程
            // 消息发布成功后，会返回消息ID
            topic.publishAsync(message).thenAccept(id -> {
                log.info("缓存失效广播消息已发布，id:{}, message:{}", id, message);
            }).exceptionally(ex -> {
                log.info("缓存失效广播失败: {}, {}", ex.getMessage(), message);
                return null;
            });
        }
    }
}
