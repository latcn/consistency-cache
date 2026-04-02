package com.consist.cache.spring.pubsub;

import com.consist.cache.core.model.NodeInstanceHolder;
import org.redisson.Redisson;
import org.redisson.api.RCuckooFilter;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.cuckoofilter.CuckooFilterAddArgs;
import org.redisson.api.cuckoofilter.CuckooFilterInitArgs;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class RedisScriptTest {

    private final static RedissonClient redissonClient = redissonClient();

    /**
     * Lua 脚本：添加标记并自动续期
     * 逻辑：
     * 1. 移除已过期的节点 (Score < 当前时间)
     * 2. 添加当前节点
     * 3. 获取当前最大的 Score
     * 4. 设置 Key 的过期时间为 (最大Score + 缓冲时间)
     */
    private static final String SCRIPT_ADD_AND_RENEW =
            "local key = KEYS[1]; " +
                    "local nodeId = ARGV[1]; " +
                    "local now = tonumber(ARGV[2]); " +
                    "local score = tonumber(ARGV[3]); " +
                    "local bufferTime = tonumber(ARGV[4]); " +
                    // 1. 惰性清理：移除 Score 小于当前时间戳的元素
                    "redis.call('ZREMRANGEBYSCORE', key, '-inf', now); " +
                    //"redis.call('ZREMRANGE', key, '-inf', score, 'BYSCORE')" +
                    // 2. 添加当前节点
                    "redis.call('ZADD', key, score, nodeId); " +
                    // 3. 获取集合中最大的 Score (最新的过期时间)
                    //"local res = redis.call('ZREVRANGE', key, 0, 0, 'WITHSCORES'); " +
                    "local res = redis.call('ZRANGE', key, 0, 0, 'REV', 'WITHSCORES');" +
                    // 4. 如果集合不为空，更新 Key 的 TTL
                    "if res and #res > 0 then " +
                    "    local maxScore = tonumber(res[2]); " +
                    // 设置过期时间点 = 最大分数 + 缓冲时间 " +
                    "    redis.call('PEXPIREAT', key, maxScore + bufferTime); " +
                    "end;" +
                    "return 1;";

    /**
     * Lua 脚本：删除标记
     * 逻辑：
     * 1. 移除已过期的节点 (Score < 当前时间)
     * 2. 删除当前节点
     * 3. 没有元素了，直接删除 Key 释放内存
     * 4. 重置整个key的过期时间，避免漏删，redis key自动过期
     */
    private static final String SCRIPT_REMOVE_AND_RENEW =
            "local key = KEYS[1]; " +
                    "local nodeId = ARGV[1]; " +
                    "local now = tonumber(ARGV[2]); " +
                    "local bufferTime = tonumber(ARGV[3]); " +
                    // 1. 惰性清理：移除 Score 小于当前时间戳的元素
                    "redis.call('ZREMRANGEBYSCORE', key, '-inf', now); " +
                    //"redis.call('ZREMRANGE', key, '-inf', score, 'BYSCORE')" +
                    // 2. 添加当前节点
                    "redis.call('zrem', key, nodeId); " +
                    // 2. 获取所有剩余节点
                    "local count = redis.call('ZCARD', key); " +
                    "if count == 0 then " +
                    //-- 没有元素了，直接删除 Key 释放内存
                    "    redis.call('DEL', key); " +
                    "    return 0; " +
                    "else " +
                    //"    local res = redis.call('ZREVRANGE', key, 0, 0, 'WITHSCORES'); " +
                    "    local res = redis.call('ZRANGE', key, 0, 0, 'REV', 'WITHSCORES');" +
                    "    if res and #res > 0 then " +
                    "        redis.call('PEXPIREAT', key, tonumber(res[2]) + bufferTime); " +
                    "    end; " +
                    "end; " +
                    "return count;";


    /**
     * Lua 脚本：主动清理过期节点并重置 TTL
     * 逻辑：
     * 1. 移除过期节点
     * 2. 如果集合为空，删除 Key
     * 3. 如果集合不为空，调整 TTL 为当前最大 Score + 缓冲时间
     */
    private static final String SCRIPT_CLEANUP =
            "local key = KEYS[1]; " +
                    "local now = tonumber(ARGV[1]); " +
                    "local bufferTime = tonumber(ARGV[2]); " +
                    // 1. 移除过期节点
                    "local removed = redis.call('ZREMRANGEBYSCORE', key, '-inf', now); " +
                    //"redis.call('ZREMRANGE', key, '-inf', score, 'BYSCORE')" +
                    // 2. 检查剩余元素数量
                    "local count = redis.call('ZCARD', key); " +
                    "if count == 0 then " +
                    // 没有元素了，直接删除 Key 释放内存 " +
                    "    redis.call('DEL', key); " +
                    "    return 0; " +
                    "else " +
                    //"    local res = redis.call('ZREVRANGE', key, 0, 0, 'WITHSCORES'); " +
                    "    local res = redis.call('ZRANGE', key, 0, 0, 'REV', 'WITHSCORES');" +
                    "    if res and #res > 0 then " +
                    "        redis.call('PEXPIREAT', key, tonumber(res[2]) + bufferTime); " +
                    "    end; " +
                    "end;" +
                    "return count;";

    /**
     * Lua 脚本：清理过期节点并返回活跃节点列表
     * 逻辑：
     * 1. 清理过期节点
     * 2. 如果集合为空，删除 Key
     * 3. 如果集合不为空，修正 TTL
     * 4. 返回当前所有活跃节点
     */
    private static final String SCRIPT_GET_ACTIVE_NODES =
            "local key = KEYS[1]; " +
                    "local now = tonumber(ARGV[1]); " +
                    "local bufferTime = tonumber(ARGV[2]); " +
                    // 1. 移除过期节点
                    "redis.call('ZREMRANGEBYSCORE', key, '-inf', now); " +
                    //"redis.call('ZREMRANGE', key, '-inf', score, 'BYSCORE')" +
                    // 2. 获取所有剩余节点
                    "local count = redis.call('ZCARD', key); " +
                    "if count == 0 then " +
                    //-- 没有元素了，直接删除 Key 释放内存
                    "    redis.call('DEL', key); " +
                    "    return {}; " +
                    "else " +
                    //"    local res = redis.call('ZREVRANGE', key, 0, 0, 'WITHSCORES'); " +
                    "    local res = redis.call('ZRANGE', key, 0, 0, 'REV', 'WITHSCORES');" +
                    "    if res and #res > 0 then " +
                    "        redis.call('PEXPIREAT', key, tonumber(res[2]) + bufferTime); " +
                    "    end; " +
                    "end; " +
                    "return redis.call('ZRANGE', key, 0, 1, 'REV');";

    public static RedissonClient redissonClient() {
        Config config = new Config();
        config.setCodec(new org.redisson.codec.JsonJacksonCodec());
        config.useClusterServers()
                .setNodeAddresses(
                        Arrays.asList(
                                "redis://127.0.0.1:7001",
                                "redis://127.0.0.1:7002",
                                "redis://127.0.0.1:7003",
                                "redis://127.0.0.1:7004",
                                "redis://127.0.0.1:7005",
                                "redis://127.0.0.1:7006"
                        ));
        return Redisson.create(config);
    }

    public static void testRedisLua(String scriptString, String key, Object[] args) {
        try {
            RScript script = redissonClient.getScript(StringCodec.INSTANCE);
            String cacheSha = script.scriptLoad(scriptString);
            Object result = script.evalSha(
                    RScript.Mode.READ_WRITE,
                    cacheSha,
                    RScript.ReturnType.VALUE,
                    Collections.singletonList(key),
                     args
            );
            if (result instanceof List) {
                List<Object> list = (List<Object>) result;
                System.out.println("-----------");
            }
            System.out.println(result);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
           // redissonClient.shutdown();
        }
    }

    public static void testAdd() {
        for (int i=0; i<10; i++) {
            String nodeId = NodeInstanceHolder.getNodeId();
            System.out.println("nodeId:"+nodeId);
            testRedisLua(SCRIPT_ADD_AND_RENEW, "k1", new Object[]{
                    nodeId+i,
                    System.currentTimeMillis(),
                    System.currentTimeMillis()+1000*1000,
                    100
            });
        }
    }

    public static void testRemove() {
        testRedisLua(SCRIPT_REMOVE_AND_RENEW ,"k1", new Object[]{
                "DESKTOP-7HCQ9A4-6ad95282-9ad0-426e-9171-924ccbd7fcd90",
                System.currentTimeMillis(),
                100
        });
    }

    public static void testCleanUp() {
        testRedisLua(SCRIPT_CLEANUP ,"k1", new Object[]{
                System.currentTimeMillis(),
                100
        });
    }

    public static void testGetActiveNodes() {
        testRedisLua(SCRIPT_GET_ACTIVE_NODES ,"k1", new Object[]{
                System.currentTimeMillis(),
                100
        });
    }

    public static void testRCuckooFilter() {
        RCuckooFilter<String> filter = redissonClient.getCuckooFilter("user:cf2", StringCodec.INSTANCE);
        // advanced initialization with detailed parameters
        filter.init(CuckooFilterInitArgs
                .capacity(100000)
                .bucketSize(4)
                .maxIterations(500)
                .expansion(2));
        // add a single element (allows duplicates)
        boolean added = filter.add("element1");
        // add element only if it does not already exist
        boolean addedNew = filter.addIfAbsent("element2");
        // bulk add with optional parameters
        Set<String> addedItems = filter.add(
                CuckooFilterAddArgs.<String>items(List.of("a", "b", "c"))
                        .capacity(50000)
                        .noCreate());
        // bulk add only absent elements
        Set<String> newItems = filter.addIfAbsent(
                CuckooFilterAddArgs.<String>items(List.of("d", "e", "f"))
                        .capacity(50000));
        // check multiple elements at once
        Set<String> existing = filter.exists(List.of("a", "b", "c", "d"));
        boolean removed = filter.remove("element1");
        System.out.println("--------------");

    }
    
    public static void main(String[] args) {
        //testAdd();
        //testRemove();
        //testCleanUp();
        testGetActiveNodes();
    }

}
