package com.consist.cache.spring.local;

import com.consist.cache.core.local.LocalCacheMarkerManager;
import com.consist.cache.spring.model.RedisScriptCache;
import com.consist.cache.core.util.MapUtil;
import com.consist.cache.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBatch;
import org.redisson.api.RFuture;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public class LocalCacheMarkerManagerImpl extends LocalCacheMarkerManager {

    // 缓冲时间（毫秒），防止时钟偏移，建议 5-10 秒
    private static final long DEFAULT_BUFFER_TIME_MS = 10_000;
    private static final int MAX_EXPECTED_SIZE = 1000;
    private final RedissonClient redissonClient;
    private final RedisScriptCache redisScriptCache;
    private final long bufferTimeMs;

    public LocalCacheMarkerManagerImpl(RedissonClient redissonClient, long bufferTimeMs) {
        super(0);
        this.redissonClient = redissonClient;
        this.redisScriptCache = new RedisScriptCache(redissonClient.getScript());
        if (bufferTimeMs<=0) {
            this.bufferTimeMs = DEFAULT_BUFFER_TIME_MS;
        } else {
            this.bufferTimeMs = bufferTimeMs;
        }
    }

    /**
     * init
     */
    public void init() {
        this.redisScriptCache.registerScript(SCRIPT_ADD_AND_RENEW_NAME, SCRIPT_ADD_AND_RENEW);
        this.redisScriptCache.registerScript(SCRIPT_REMOVE_AND_RENEW_NAME, SCRIPT_REMOVE_AND_RENEW);
        this.redisScriptCache.registerScript(SCRIPT_CLEANUP_NAME, SCRIPT_CLEANUP);
        this.redisScriptCache.registerScript(SCRIPT_GET_ACTIVE_NODES_NAME, SCRIPT_GET_ACTIVE_NODES);
    }

    private static final String SCRIPT_ADD_AND_RENEW_NAME = "SCRIPT_ADD_AND_RENEW";
    private static final String SCRIPT_REMOVE_AND_RENEW_NAME = "SCRIPT_REMOVE_AND_RENEW";
    private static final String SCRIPT_CLEANUP_NAME = "SCRIPT_CLEANUP";
    private static final String SCRIPT_GET_ACTIVE_NODES_NAME = "SCRIPT_GET_ACTIVE_NODES";

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
                    "local score = tonumber(ARGV[2]); " +
                    "local bufferTime = tonumber(ARGV[3]); " +

                    // 1. 惰性清理：移除 Score 小于当前时间戳的元素
                    "redis.call('ZREMRANGEBYSCORE', key, '-inf', score); " +
                    //"redis.call('ZREMRANGE', key, '-inf', score, 'BYSCORE')" +

                    // 2. 添加当前节点
                    "redis.call('ZADD', key, score, nodeId); " +

                    // 3. 获取集合中最大的 Score (最新的过期时间)
                    //"local res = redis.call('ZREVRANGE', key, 0, 0, 'WITHSCORES'); " +
                    "local res = redis.call('ZRANGE', key, 0, 0, 'REV', 'WITHSCORES');" +

                    // 4. 如果集合不为空，更新 Key 的 TTL
                    "if res and #res > 0 then " +
                    "    local maxScore = tonumber(res[2]); " +
                    "    -- 设置过期时间点 = 最大分数 + 缓冲时间 " +
                    "    redis.call('PEXPIREAT', key, maxScore + bufferTime); " +
                    "end; " +

                    "return 1;";

    /**
     * Lua 脚本：添加标记并自动续期
     * 逻辑：
     * 1. 移除已过期的节点 (Score < 当前时间)
     * 2. 添加当前节点
     * 3. 获取当前最大的 Score
     * 4. 设置 Key 的过期时间为 (最大Score + 缓冲时间)
     */
    private static final String SCRIPT_REMOVE_AND_RENEW =
            "local key = KEYS[1]; " +
                    "local nodeId = ARGV[1]; " +
                    // 1. 惰性清理：移除 Score 小于当前时间戳的元素
                    "redis.call('ZREMRANGEBYSCORE', key, '-inf', score); " +
                    //"redis.call('ZREMRANGE', key, '-inf', score, 'BYSCORE')" +
                    // 2. 添加当前节点
                    "redis.call('zrem', key, nodeId); " +
                    // 2. 获取所有剩余节点
                    "local count = redis.call('ZCARD', key); " +
                    "if count == 0 then " +
                    //-- 没有元素了，直接删除 Key 释放内存
                    "    redis.call('DEL', key); " +
                    "    return 0; " +
                    "end; " +
                    "return 1;";


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
                    "    -- 没有元素了，直接删除 Key 释放内存 " +
                    "    redis.call('DEL', key); " +
                    "    return 0; " +
                    "else " +
                    "    -- 重新计算并设置 TTL，防止 TTL 只增不减 " +
                    //"    local res = redis.call('ZREVRANGE', key, 0, 0, 'WITHSCORES'); " +
                    "    local res = redis.call('ZRANGE', key, 0, 0, 'REV', 'WITHSCORES');" +
                    "    if res and #res > 0 then " +
                    "        local maxScore = tonumber(res[2]); " +
                    "        redis.call('PEXPIREAT', key, maxScore + bufferTime); " +
                    "    end; " +
                    "    return count; " +
                    "end;";

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
                    "    return 0; " +
                    "else " +
                    //"    local res = redis.call('ZREVRANGE', key, 0, 0, 'WITHSCORES'); " +
                    "    local res = redis.call('ZRANGE', key, 0, 0, 'REV', 'WITHSCORES');" +
                    "    if res and #res > 0 then " +
                    "        redis.call('PEXPIREAT', key, tonumber(res[2]) + bufferTime); " +
                    "    end; " +
                    "end; " +
                    "return members;";

    /**
     * 标记当前节点正在使用本地缓存
     * @param cacheKey 业务缓存Key
     */
    @Override
    public void markLocalCacheUsage(String cacheKey, long expireTime) {
        if (expireTime<System.currentTimeMillis()) {
            return;
        }
        // Redis Key 设计：使用前缀区分标记 Key
        String markerKey = MARKER_PREFIX + cacheKey;
        // 执行 Lua 脚本
        executeWithCache(SCRIPT_ADD_AND_RENEW_NAME, RScript.ReturnType.VALUE,
                Collections.singletonList(markerKey),
                new Object[]{
                        this.nodeId,
                        String.valueOf(expireTime),
                        String.valueOf(this.bufferTimeMs)}
                );
        this.useLocalCacheKey.add(cacheKey);
    }

    /**
     * 删除标记
     * @param cacheKey 业务缓存Key
     */
    @Override
    public void removeLocalCacheUsage(String cacheKey) {
        // Redis Key 设计：使用前缀区分标记 Key
        String markerKey = MARKER_PREFIX + cacheKey;
        try {
            this.redissonClient.getKeys().delete(markerKey);
        } catch (Exception e) {
            log.error("ex", e);
        }
        this.useLocalCacheKey.remove(cacheKey);
    }

    /**
     * 定时清理任务：随机选取扫描并清理过期的标记
     */
    @Override
    public void doCleanUp() {
        try {
            String cachedSha1 = this.redisScriptCache.getCachedSha1(SCRIPT_CLEANUP_NAME);
            if (StringUtil.isNullOrEmpty(cachedSha1)) {
                cachedSha1 = this.redisScriptCache.reloadCachedSha1(SCRIPT_CLEANUP_NAME);
            }
            List<RFutureWrapper<Integer>> useLocalCount = new ArrayList<>();
            Set<String> monitorKeys = new HashSet<>();
            MapUtil.randomSelection(monitorKeys, monitorKeys, MAX_EXPECTED_SIZE, true);
            RBatch batch = redissonClient.createBatch();
            for (String markerKey : monitorKeys) {
                long now = System.currentTimeMillis();
                RFuture<Integer> rFuture = batch.getScript().evalShaAsync(RScript.Mode.READ_WRITE,
                        cachedSha1,
                        RScript.ReturnType.VALUE,
                        Arrays.asList(markerKey),
                        String.valueOf(now),
                        String.valueOf(this.bufferTimeMs)
                );
                useLocalCount.add(new RFutureWrapper(markerKey, rFuture));
            }
            batch.execute();
            for (RFutureWrapper<Integer> rFuture: useLocalCount) {
                try {
                    int count = rFuture.get(1);
                    // 说明仍有节点使用本地缓存
                    if (count>0) {
                        this.useLocalCacheKey.add(rFuture.getCacheKey());
                    }
                } catch (Exception ex) {
                    log.error("cleanupExpiredMarkers get value ex", ex);
                }
            }
        } catch (Exception e) {
            this.redisScriptCache.reloadCachedSha1(SCRIPT_CLEANUP_NAME);
            throw e;
        }
    }

    /**
     * 获取活跃节点列表（带清理逻辑）
     */
    @Override
    public List<String> getActiveNodes(String cacheKey) {
        String markerKey = MARKER_PREFIX + cacheKey;
        long now = System.currentTimeMillis();
        // 执行脚本
        List<Object> result = executeWithCache(SCRIPT_GET_ACTIVE_NODES_NAME, RScript.ReturnType.LIST,
                Collections.singletonList(markerKey),
                new Object[]{
                        String.valueOf(now),
                        String.valueOf(this.bufferTimeMs)}
        );
        // 转换结果
        if (result == null) {
            return Collections.emptyList();
        }
        // Redisson 返回的是 List<Object>，需要转为 String
        // 在较新版本的 Redisson 中，如果返回值确定是 String，可能会直接是 String list，
        // 但通常 MULTI 返回的是 Object list
        return result.stream()
                .map(Object::toString)
                .collect(Collectors.toList());
    }


    /**
     * 执行脚本（带自动恢复机制）
     * @param scriptName
     * @param keys
     * @param args
     */
    private <R> R executeWithCache(String scriptName, RScript.ReturnType returnType, List<Object> keys, Object[] args) {
        RScript script = this.redissonClient.getScript();
        R result = null;
        try {
            // 优先使用 SHA1 执行（极省带宽）
            String cachedSha1 = this.redisScriptCache.getCachedSha1(scriptName);
            result = script.evalSha(
                    RScript.Mode.READ_WRITE,
                    cachedSha1,
                    returnType,
                    keys,
                    args
            );
        } catch (Exception e) {
            // 4. 容错处理：如果 Redis 重启或执行了 SCRIPT FLUSH，SHA1 会失效 捕获异常（Redis 通常返回 'NOSCRIPT' 错误），重新加载脚本
            log.warn("SHA1 执行失败，尝试重新加载脚本... 原因: {}", e.getMessage());
            // 重新加载并获取新的 SHA1
            try {
                String cachedSha1 = this.redisScriptCache.reloadCachedSha1(scriptName);
                // 再次尝试执行
                result = script.evalSha(
                        RScript.Mode.READ_WRITE,
                        cachedSha1,
                        RScript.ReturnType.VALUE,
                        keys,
                        args
                );
            } catch (Exception ex) {
                log.error("SHA1 执行失败", e);
            }
        }
        return result;
    }

    class RFutureWrapper<T> {
        private final String cacheKey;
        private final RFuture<T> rFuture;

        public RFutureWrapper(String cacheKey, RFuture<T> rFuture) {
            this.cacheKey = cacheKey;
            this.rFuture = rFuture;
        }

        public String getCacheKey() {
            return cacheKey;
        }

        public <T> T get(long timeout) throws Exception {
            return (T) this.rFuture.get(timeout, TimeUnit.SECONDS);
        }
    }
}
