package com.consist.cache.spring.pubsub;

import com.consist.cache.core.executor.CacheExecutor;
import com.consist.cache.core.local.LocalCacheMarkerManager;
import com.consist.cache.core.model.CacheKey;
import com.consist.cache.core.pubsub.InvalidationMessage;
import com.consist.cache.spring.config.TestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.ContextConfiguration;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

@DisplayName("HccCacheInterceptor Spring Integration Tests")
@SpringBootTest
@EnableAspectJAutoProxy
@ContextConfiguration(classes = {
        TestConfig.class
}
)
public class RedisPubSub {

    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private CacheExecutor cacheExecutor;

    @Autowired
    private LocalCacheMarkerManager localCacheMarkerManager;

    private RTopicPublisher rTopicPublisher;
    private RTopicSubscriber rTopicSubscriber;
    private ReliablePublisher reliablePublisher;
    private ReliableSubscriber reliableSubscriber;
    private static final String channelName = "invalidmessage";

    @BeforeEach
    public void setUp() {
        this.rTopicPublisher = new RTopicPublisher(redissonClient);
        this.rTopicSubscriber = new RTopicSubscriber(redissonClient);
        this.reliablePublisher = new ReliablePublisher(redissonClient);
        this.reliableSubscriber = new ReliableSubscriber(redissonClient);
        for (int i=0; i<3; i++) {
            new Thread(
                    ()-> rTopicSubscriber.broadcastSubscribe(channelName, new InvalidationListener(
                            UUID.randomUUID().toString(), Arrays.asList(channelName), cacheExecutor
                    ))
            ).start();
        }
    }

    @Test
    public void publish() throws InterruptedException {
        setUp();
        InvalidationMessage invalidationMessage = new InvalidationMessage();
        for(int i=0; i<2;i++) {
            CacheKey cacheKey = CacheKey.builder()
                    .key(i+":"+UUID.randomUUID()).build();
            invalidationMessage.addKey(cacheKey);
        }
        rTopicPublisher.broadcastMessage(Set.of(channelName), invalidationMessage);
        Thread.sleep(100000);
       /* CacheKey cacheKey = CacheKey.builder()
                .key("0:"+UUID.randomUUID()).build();
        this.cacheExecutor.evict(cacheKey);*/
    }


    @Test
    public void testRedisClean() {
        localCacheMarkerManager.markLocalCacheUsage("k1", System.currentTimeMillis()+1000*1000);
        localCacheMarkerManager.markLocalCacheUsage("k2", System.currentTimeMillis()+1000*1000);
        localCacheMarkerManager.doCleanUp();
    }




}
