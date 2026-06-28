package io.github.latcn.cache.spring.distributed;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.*;
import org.redisson.api.redisnode.*;
import org.redisson.config.Config;

@Slf4j
public class RedisHealthCheck {

	public enum RedisMode {

		CLUSTER, SENTINEL, MASTER_SLAVE, REPLICATED, STANDALONE

	}

	private static final int PING_TIMEOUT_SECONDS = 3;

	// 模式识别
	public static RedisMode getRedisMode(RedissonClient redissonClient) {
		if (redissonClient.isShutdown()) {
			throw new IllegalStateException("RedissonClient 已关闭");
		}
		Config config = redissonClient.getConfig();

		try {
			Field clusterField = Config.class.getDeclaredField("clusterServersConfig");
			clusterField.setAccessible(true);
			if (clusterField.get(config) != null) {
				return RedisMode.CLUSTER;
			}

			Field sentinelField = Config.class.getDeclaredField("sentinelServersConfig");
			sentinelField.setAccessible(true);
			if (sentinelField.get(config) != null) {
				return RedisMode.SENTINEL;
			}

			Field masterSlaveField = Config.class.getDeclaredField("masterSlaveServersConfig");
			masterSlaveField.setAccessible(true);
			if (masterSlaveField.get(config) != null) {
				return RedisMode.MASTER_SLAVE;
			}

			Field replicatedField = Config.class.getDeclaredField("replicatedServersConfig");
			replicatedField.setAccessible(true);
			if (replicatedField.get(config) != null) {
				return RedisMode.REPLICATED;
			}

			Field singleField = Config.class.getDeclaredField("singleServerConfig");
			singleField.setAccessible(true);
			if (singleField.get(config) != null) {
				return RedisMode.STANDALONE;
			}
		}
		catch (NoSuchFieldException | IllegalAccessException e) {
			log.warn("通过反射读取Redis配置失败，将使用默认单机模式检查", e);
		}

		return RedisMode.STANDALONE;
	}

	// 统一入口
	public static boolean checkHealth(RedissonClient redissonClient) {
		RedisMode mode;
		try {
			mode = getRedisMode(redissonClient);
		}
		catch (Exception e) {
			log.error("Redis 模式识别失败", e);
			return false;
		}

		log.debug("Redis 健康检查 - 检测到模式: {}", mode);
		try {
			switch (mode) {
				case CLUSTER:
					return checkClusterHealth(redissonClient);
				case SENTINEL:
					return checkSentinelHealth(redissonClient);
				case MASTER_SLAVE:
				case REPLICATED:
					return checkMasterOrReplicatedHealth(redissonClient);
				default:
					return checkStandaloneHealth(redissonClient);
			}
		}
		catch (Exception e) {
			log.error("Redis 健康检查异常，模式: {}", mode, e);
			return false;
		}
	}

	// 单机模式
	private static boolean checkStandaloneHealth(RedissonClient redissonClient) {
		try {
			Redisson redisson = (Redisson) redissonClient;
			RedisSingle single = redisson.getRedisNodes(RedisNodes.SINGLE);
			return single.pingAll(PING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		catch (Exception e) {
			log.error("单机模式健康检查失败", e);
			return false;
		}
	}

	// 主从/复制模式（合并）
	private static boolean checkMasterOrReplicatedHealth(RedissonClient redissonClient) {
		try {
			Redisson redisson = (Redisson) redissonClient;
			RedisMasterSlave masterSlave = redisson.getRedisNodes(RedisNodes.MASTER_SLAVE);
			RedisMaster master = masterSlave.getMaster();
			if (master == null) {
				log.warn("未找到主节点");
				return false;
			}
			return master.ping(PING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		catch (Exception e) {
			log.error("主从/复制模式健康检查失败", e);
			return false;
		}
	}

	// 哨兵模式
	private static boolean checkSentinelHealth(RedissonClient redissonClient) {
		try {
			Redisson redisson = (Redisson) redissonClient;
			RedisSentinelMasterSlave sentinelMasterSlave = redisson.getRedisNodes(RedisNodes.SENTINEL_MASTER_SLAVE);
			RedisMaster master = sentinelMasterSlave.getMaster();
			if (master == null) {
				log.warn("哨兵模式未找到主节点");
				return false;
			}
			if (!master.ping(PING_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				log.warn("哨兵模式主节点不可达");
				return false;
			}
			return true;
		}
		catch (Exception e) {
			log.error("哨兵模式健康检查失败", e);
			return false;
		}
	}

	// 集群模式：仅检查主节点
	private static boolean checkClusterHealth(RedissonClient redissonClient) {
		try {
			Redisson redisson = (Redisson) redissonClient;
			RedisCluster cluster = redisson.getRedisNodes(RedisNodes.CLUSTER);
			Collection<RedisClusterMaster> masters = cluster.getMasters();
			if (masters.isEmpty()) {
				log.warn("集群未找到主节点");
				return false;
			}
			for (RedisClusterMaster master : masters) {
				if (!master.ping(PING_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
					log.warn("集群主节点 {} 不可达", master.getAddr());
					return false;
				}
			}
			return true;
		}
		catch (Exception e) {
			log.error("集群健康检查失败", e);
			return false;
		}
	}

}