package io.github.latcn.cache.spring.distributed;

import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

@Slf4j
public class RedisHealthCheck {

	public enum RedisMode {

		CLUSTER, SENTINEL, STANDALONE

	}

	public static RedisMode getRedisMode(RedissonClient redissonClient) {
		Config config = redissonClient.getConfig();
		if (config.isClusterConfig()) {
			return RedisMode.CLUSTER;
		}
		else if (config.isSentinelConfig()) {
			return RedisMode.SENTINEL;
		}
		return RedisMode.STANDALONE;
	}

	public static boolean checkHealth(RedissonClient redissonClient) {
		RedisMode mode = getRedisMode(redissonClient);
		log.info("检测到 Redis 模式: {}", mode);

		switch (mode) {
			case CLUSTER:
				return checkClusterHealth(redissonClient);
			case SENTINEL:
				return checkSentinelHealth(redissonClient);
			default:
				log.warn("未知的 Redis 模式，执行基本连接检查");
				return checkStandaloneHealth(redissonClient);
		}
	}

	private static boolean checkClusterHealth(RedissonClient redissonClient) {
		try {
			RScript script = redissonClient.getScript(StringCodec.INSTANCE);
			String clusterInfo = script.eval(RScript.Mode.READ_ONLY, "return redis.call('CLUSTER', 'INFO')",
					RScript.ReturnType.VALUE, Arrays.asList("DUMMY"));

			if (!clusterInfo.contains("cluster_state:ok")) {
				log.warn("集群状态异常");
				return false;
			}

			String clusterNodes = script.eval(RScript.Mode.READ_ONLY, "return redis.call('CLUSTER', 'NODES')",
					RScript.ReturnType.VALUE, Arrays.asList("DUMMY"));

			int failCount = 0;
			for (String line : clusterNodes.split("\n")) {
				if (line.contains("fail") || line.contains("fail?")) {
					failCount++;
				}
			}

			if (failCount > 0) {
				log.warn("集群中有 {} 个故障节点", failCount);
				return false;
			}
			return true;
		}
		catch (Exception e) {
			log.error("集群健康检查失败", e);
			return false;
		}
	}

	private static boolean checkSentinelHealth(RedissonClient redissonClient) {
		try {
			RScript script = redissonClient.getScript(StringCodec.INSTANCE);
			String result = script.eval(RScript.Mode.READ_ONLY, "return redis.call('PING')", RScript.ReturnType.VALUE,
					Arrays.asList("DUMMY"));

			if (!"PONG".equalsIgnoreCase(result)) {
				return false;
			}

			return checkSentinelMasters(script);
		}
		catch (Exception e) {
			log.error("哨兵健康检查失败", e);
			return false;
		}
	}

	private static boolean checkSentinelMasters(RScript script) {
		try {
			String mastersScript = "local masters = redis.call('SENTINEL', 'MASTERS'); " + "local result = ''; "
					+ "for i = 1, #masters do " + "local master = masters[i]; " + "local name = ''; "
					+ "local flags = ''; " + "for j = 1, #master, 2 do "
					+ "if master[j] == 'name' then name = master[j+1] end "
					+ "if master[j] == 'flags' then flags = master[j+1] end " + "end "
					+ "result = result .. name .. ':' .. flags .. '\\n'; " + "end " + "return result;";

			String mastersInfo = script.eval(RScript.Mode.READ_ONLY, mastersScript, RScript.ReturnType.VALUE,
					Arrays.asList("DUMMY"));

			if (mastersInfo == null || mastersInfo.isEmpty()) {
				log.warn("未找到哨兵监控的主节点");
				return false;
			}

			int totalCount = 0;
			int failCount = 0;
			for (String line : mastersInfo.split("\n")) {
				if (line.trim().isEmpty()) {
					continue;
				}
				totalCount++;
				String[] parts = line.split(":");
				if (parts.length >= 2) {
					String name = parts[0];
					String flags = parts[1];
					if (flags.contains("s_down") || flags.contains("o_down")) {
						failCount++;
						log.warn("主节点 {} 状态异常: {}", name, flags);
					}
				}
			}

			log.info("哨兵主节点统计: 总数={}, 异常={}", totalCount, failCount);
			return failCount == 0;
		}
		catch (Exception e) {
			log.error("检查哨兵主节点失败", e);
			return false;
		}
	}

	private static boolean checkStandaloneHealth(RedissonClient redissonClient) {
		try {
			RScript script = redissonClient.getScript(StringCodec.INSTANCE);
			String result = script.eval(RScript.Mode.READ_ONLY, "return redis.call('PING')", RScript.ReturnType.VALUE,
					Arrays.asList("DUMMY"));
			return "PONG".equalsIgnoreCase(result);
		}
		catch (Exception e) {
			log.error("单机健康检查失败", e);
			return false;
		}
	}

}
