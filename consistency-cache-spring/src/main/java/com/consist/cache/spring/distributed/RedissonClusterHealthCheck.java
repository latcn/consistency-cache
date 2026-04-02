package com.consist.cache.spring.distributed;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.Arrays;

@Slf4j
public class RedissonClusterHealthCheck {

    /**
     * 检查集群健康状态
     */
    public static boolean checkClusterHealth(RedissonClient redissonClient) {
        RScript script = redissonClient.getScript(StringCodec.INSTANCE);
        // 第一步：执行 CLUSTER INFO 命令
        // ---------------------------------------------------------
        // Redisson 的 eval 需要传入 key 列表来进行分片路由。
        // 对于 CLUSTER INFO 这种全局命令，key 的内容不重要，只要不为空即可。
        // 这里使用一个 dummy key 来满足 API 要求。
        String clusterInfoScript = "return redis.call('CLUSTER', 'INFO')";
        String clusterInfo = script.eval(
                RScript.Mode.READ_ONLY,
                clusterInfoScript,
                RScript.ReturnType.VALUE,
                Arrays.asList("DUMMY")
        );
        log.info("========== CLUSTER INFO ==========\n{}", clusterInfo);
        // 解析 cluster_state
        if (clusterInfo.contains("cluster_state:ok")) {
            return true;
        }
        return false;
    }

    private static void checkNodes(RScript script, RedissonClient redissonClient) {
        // ---------------------------------------------------------
        // 第二步：执行 CLUSTER NODES 命令
        // ---------------------------------------------------------
        String clusterNodesScript = "return redis.call('CLUSTER', 'NODES')";
        String clusterNodes = script.eval(
                RScript.Mode.READ_ONLY,
                clusterNodesScript,
                RScript.ReturnType.VALUE
        );
        log.info("========== CLUSTER NODES ========== \n{}", clusterNodes);
        // 简单的节点状态解析
        String[] lines = clusterNodes.split("\n");
        int masterCount = 0;
        int slaveCount = 0;
        int failCount = 0;
        for (String line : lines) {
            if (line.contains("master")) {
                masterCount++;
            }
            if (line.contains("slave")) {
                slaveCount++;
            }
            if (line.contains("fail") || line.contains(" ?")) {
                failCount++;
            }
        }
        log.info(String.format("节点统计: 主节点=%d, 从节点=%d, 故障/未知节点=%d", masterCount, slaveCount, failCount));
        if (failCount > 0) {
            log.info("[检测警告] 发现 {} 个节点处于故障或未知状态！", failCount);
        }
    }


    public static void main(String[] args) {
        Config config = new Config();
        ObjectMapper objectMapper = new ObjectMapper();
        config.setCodec(new org.redisson.codec.JsonJacksonCodec(objectMapper));
        config.useClusterServers()
                .setNodeAddresses(
                        Arrays.asList(
                                "redis://127.0.0.1:7001",
                                "redis://127.0.0.1:7002",
                                "redis://127.0.0.1:7003",
                                "redis://127.0.0.1:7005",
                                "redis://127.0.0.1:7006"
                        ));
        RedissonClient redissonClient = Redisson.create(config);
        //System.out.println(checkClusterHealth(redissonClient));
    }
}
