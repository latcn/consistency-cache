package io.github.latcn.cache.spring.distributed;

import static org.junit.jupiter.api.Assertions.*;

import io.github.latcn.cache.spring.distributed.RedisHealthCheck.RedisMode;
import org.junit.jupiter.api.DisplayName;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

@DisplayName("RedisHealthCheck Tests")
class RedisHealthCheckTest {

	private RedissonClient createClient(Config config) {
		return Redisson.create(config);
	}

	private void shutdownClient(RedissonClient client) {
		if (client != null && !client.isShutdown()) {
			client.shutdown();
		}
	}

	// @Test
	@DisplayName("Should throw exception when RedissonClient is shutdown")
	void testGetRedisModeWhenClientShutdown() {
		Config config = new Config();
		config.useSingleServer().setAddress("redis://localhost:6379");
		RedissonClient client = createClient(config);
		client.shutdown();

		IllegalStateException exception = assertThrows(IllegalStateException.class,
				() -> RedisHealthCheck.getRedisMode(client));

		assertEquals("RedissonClient 已关闭", exception.getMessage());
	}

	// @Test
	@DisplayName("Should return STANDALONE mode for single server config")
	void testGetRedisModeStandalone() {
		Config config = new Config();
		config.useSingleServer().setAddress("redis://localhost:6379");
		RedissonClient client = createClient(config);

		try {
			RedisMode mode = RedisHealthCheck.getRedisMode(client);
			assertEquals(RedisMode.STANDALONE, mode);
		}
		finally {
			shutdownClient(client);
		}
	}

	// @Test
	@DisplayName("Should return CLUSTER mode for cluster config")
	void testGetRedisModeCluster() {
		Config config = new Config();
		config.useClusterServers().addNodeAddress("redis://localhost:6379");
		RedissonClient client = createClient(config);

		try {
			RedisMode mode = RedisHealthCheck.getRedisMode(client);
			assertEquals(RedisMode.CLUSTER, mode);
		}
		finally {
			shutdownClient(client);
		}
	}

	// @Test
	@DisplayName("Should return SENTINEL mode for sentinel config")
	void testGetRedisModeSentinel() {
		Config config = new Config();
		config.useSentinelServers().setMasterName("mymaster").addSentinelAddress("redis://localhost:26379");
		RedissonClient client = createClient(config);

		try {
			RedisMode mode = RedisHealthCheck.getRedisMode(client);
			assertEquals(RedisMode.SENTINEL, mode);
		}
		finally {
			shutdownClient(client);
		}
	}

	// @Test
	@DisplayName("Should return MASTER_SLAVE mode for master-slave config")
	void testGetRedisModeMasterSlave() {
		Config config = new Config();
		config.useMasterSlaveServers().setMasterAddress("redis://localhost:6379");
		RedissonClient client = createClient(config);

		try {
			RedisMode mode = RedisHealthCheck.getRedisMode(client);
			assertEquals(RedisMode.MASTER_SLAVE, mode);
		}
		finally {
			shutdownClient(client);
		}
	}

	// @Test
	@DisplayName("Should return REPLICATED mode for replicated config")
	void testGetRedisModeReplicated() {
		Config config = new Config();
		config.useReplicatedServers().addNodeAddress("redis://localhost:6379");
		RedissonClient client = createClient(config);

		try {
			RedisMode mode = RedisHealthCheck.getRedisMode(client);
			assertEquals(RedisMode.REPLICATED, mode);
		}
		finally {
			shutdownClient(client);
		}
	}

	// @Test
	@DisplayName("Should return STANDALONE when config has no specific settings")
	void testGetRedisModeDefault() {
		Config config = new Config();
		RedissonClient client = createClient(config);

		try {
			RedisMode mode = RedisHealthCheck.getRedisMode(client);
			assertEquals(RedisMode.STANDALONE, mode);
		}
		finally {
			shutdownClient(client);
		}
	}

	// @Test
	@DisplayName("Should return false when client is shutdown in checkHealth")
	void testCheckHealthWithShutdownClient() {
		Config config = new Config();
		config.useSingleServer().setAddress("redis://localhost:6379");
		RedissonClient client = createClient(config);
		client.shutdown();

		boolean result = RedisHealthCheck.checkHealth(client);
		assertFalse(result);
	}

	// @Test
	@DisplayName("Should return false when Redis is unreachable")
	void testCheckHealthUnreachable() {
		Config config = new Config();
		config.useSingleServer().setAddress("redis://localhost:9999").setConnectTimeout(1000).setTimeout(1000);
		RedissonClient client = createClient(config);

		try {
			boolean result = RedisHealthCheck.checkHealth(client);
			assertFalse(result);
		}
		finally {
			shutdownClient(client);
		}
	}

	// @Test
	@DisplayName("Should verify RedisMode enum values")
	void testRedisModeEnumValues() {
		RedisMode[] modes = RedisMode.values();
		assertEquals(5, modes.length);

		assertEquals(RedisMode.CLUSTER, RedisMode.valueOf("CLUSTER"));
		assertEquals(RedisMode.SENTINEL, RedisMode.valueOf("SENTINEL"));
		assertEquals(RedisMode.MASTER_SLAVE, RedisMode.valueOf("MASTER_SLAVE"));
		assertEquals(RedisMode.REPLICATED, RedisMode.valueOf("REPLICATED"));
		assertEquals(RedisMode.STANDALONE, RedisMode.valueOf("STANDALONE"));
	}

}