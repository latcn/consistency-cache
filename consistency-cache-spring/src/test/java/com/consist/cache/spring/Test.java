package com.consist.cache.spring;

import com.consist.cache.core.model.CacheValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.concurrent.ExecutionException;

public class Test {

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        // 配置 RedissonClient 使用 Jackson 进行序列化

        System.out.println(System.currentTimeMillis());
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379");

        ObjectMapper objectMapper = new ObjectMapper();
        config.setCodec(new org.redisson.codec.JsonJacksonCodec(objectMapper));

        // 创建 RedissonClient 实例
        RedissonClient redisson = Redisson.create(config);

        // 使用 RMap 存储对象
        RBucket<CacheValue> bucket = redisson.getBucket("user");
        CacheValue cacheValue = new CacheValue();
        cacheValue.setValue("test");
        cacheValue.setCreatedAt(System.currentTimeMillis());
        bucket.set(cacheValue, Duration.ofMillis(100000));

        // 关闭 RedissonClient
        redisson.shutdown();
    }
}
