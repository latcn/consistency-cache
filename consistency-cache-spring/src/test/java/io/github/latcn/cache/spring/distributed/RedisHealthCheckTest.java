package io.github.latcn.cache.spring.distributed;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.latcn.cache.spring.distributed.RedisHealthCheck.RedisMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

@DisplayName("RedisHealthCheck Tests")
class RedisHealthCheckTest {

	@Mock
	private RedissonClient redissonClient;

	private AutoCloseable closeable;

	@BeforeEach
	void setUp() {
		closeable = MockitoAnnotations.openMocks(this);
	}

	@AfterEach
	void tearDown() throws Exception {
		closeable.close();
	}

	@Test
	@DisplayName("Should throw exception when RedissonClient is shutdown")
	void testGetRedisModeWhenClientShutdown() {
		when(redissonClient.isShutdown()).thenReturn(true);

		IllegalStateException exception = assertThrows(IllegalStateException.class,
				() -> RedisHealthCheck.getRedisMode(redissonClient));

		assertEquals("RedissonClient 已关闭", exception.getMessage());
	}

	@Test
	@DisplayName("Should return STANDALONE mode for single server config")
	void testGetRedisModeStandalone() {
		Config config = new Config();
		config.useSingleServer().setAddress("redis://localhost:6379");

		when(redissonClient.isShutdown()).thenReturn(false);
		when(redissonClient.getConfig()).thenReturn(config);

		RedisMode mode = RedisHealthCheck.getRedisMode(redissonClient);
		assertEquals(RedisMode.STANDALONE, mode);
	}

	@Test
	@DisplayName("Should return CLUSTER mode for cluster config")
	void testGetRedisModeCluster() {
		Config config = new Config();
		config.useClusterServers().addNodeAddress("redis://localhost:6379");

		when(redissonClient.isShutdown()).thenReturn(false);
		when(redissonClient.getConfig()).thenReturn(config);

		RedisMode mode = RedisHealthCheck.getRedisMode(redissonClient);
		assertEquals(RedisMode.CLUSTER, mode);
	}

	@Test
	@DisplayName("Should return SENTINEL mode for sentinel config")
	void testGetRedisModeSentinel() {
		Config config = new Config();
		config.useSentinelServers().setMasterName("mymaster").addSentinelAddress("redis://localhost:26379");

		when(redissonClient.isShutdown()).thenReturn(false);
		when(redissonClient.getConfig()).thenReturn(config);

		RedisMode mode = RedisHealthCheck.getRedisMode(redissonClient);
		assertEquals(RedisMode.SENTINEL, mode);
	}

	@Test
	@DisplayName("Should return MASTER_SLAVE mode for master-slave config")
	void testGetRedisModeMasterSlave() {
		Config config = new Config();
		config.useMasterSlaveServers().setMasterAddress("redis://localhost:6379");

		when(redissonClient.isShutdown()).thenReturn(false);
		when(redissonClient.getConfig()).thenReturn(config);

		RedisMode mode = RedisHealthCheck.getRedisMode(redissonClient);
		assertEquals(RedisMode.MASTER_SLAVE, mode);
	}

	@Test
	@DisplayName("Should return REPLICATED mode for replicated config")
	void testGetRedisModeReplicated() {
		Config config = new Config();
		config.useReplicatedServers().addNodeAddress("redis://localhost:6379");

		when(redissonClient.isShutdown()).thenReturn(false);
		when(redissonClient.getConfig()).thenReturn(config);

		RedisMode mode = RedisHealthCheck.getRedisMode(redissonClient);
		assertEquals(RedisMode.REPLICATED, mode);
	}

	@Test
	@DisplayName("Should return STANDALONE when config has no specific settings")
	void testGetRedisModeDefault() {
		Config config = new Config();

		when(redissonClient.isShutdown()).thenReturn(false);
		when(redissonClient.getConfig()).thenReturn(config);

		RedisMode mode = RedisHealthCheck.getRedisMode(redissonClient);
		assertEquals(RedisMode.STANDALONE, mode);
	}

	@Test
	@DisplayName("Should return false when mode detection fails in checkHealth")
	void testCheckHealthModeDetectionFailure() {
		when(redissonClient.isShutdown()).thenReturn(false);
		when(redissonClient.getConfig()).thenThrow(new RuntimeException("Config error"));

		boolean result = RedisHealthCheck.checkHealth(redissonClient);
		assertFalse(result);
	}

	@Test
	@DisplayName("Should return false when client is shutdown in checkHealth")
	void testCheckHealthWithShutdownClient() {
		when(redissonClient.isShutdown()).thenReturn(true);

		boolean result = RedisHealthCheck.checkHealth(redissonClient);
		assertFalse(result);
	}

	@Test
	@DisplayName("Should handle null config gracefully")
	void testGetRedisModeWithNullConfig() {
		when(redissonClient.isShutdown()).thenReturn(false);
		when(redissonClient.getConfig()).thenReturn(null);

		assertThrows(NullPointerException.class, () -> RedisHealthCheck.getRedisMode(redissonClient));
	}

	@Test
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