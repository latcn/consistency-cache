package io.github.latcn.cache.spring.pubsub;

import io.github.latcn.cache.core.model.NodeInstanceHolder;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RCuckooFilter;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.cuckoofilter.CuckooFilterAddArgs;
import org.redisson.api.cuckoofilter.CuckooFilterInitArgs;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

@Slf4j
public class RedisScriptTest {

	private final static RedissonClient redissonClient = redissonClient();

	private static final String SCRIPT_ADD_AND_RENEW = "local key = KEYS[1]; " + "local nodeId = ARGV[1]; "
			+ "local now = tonumber(ARGV[2]); " + "local score = tonumber(ARGV[3]); "
			+ "local bufferTime = tonumber(ARGV[4]); " +
			"redis.call('ZREMRANGEBYSCORE', key, '-inf', now); " +
			"redis.call('ZADD', key, score, nodeId); " +
			"local res = redis.call('ZRANGE', key, 0, 0, 'REV', 'WITHSCORES');" +
			"if res and #res > 0 then " + "    local maxScore = tonumber(res[2]); " +
			"    redis.call('PEXPIREAT', key, maxScore + bufferTime); " + "end;" + "return 1;";

	private static final String SCRIPT_REMOVE_AND_RENEW = "local key = KEYS[1]; " + "local nodeId = ARGV[1]; "
			+ "local now = tonumber(ARGV[2]); " + "local bufferTime = tonumber(ARGV[3]); " +
			"redis.call('ZREMRANGEBYSCORE', key, '-inf', now); " +
			"redis.call('zrem', key, nodeId); " +
			"local count = redis.call('ZCARD', key); " + "if count == 0 then " +
			"    redis.call('DEL', key); " + "    return 0; " + "else " +
			"    local res = redis.call('ZRANGE', key, 0, 0, 'REV', 'WITHSCORES');" + "    if res and #res > 0 then "
			+ "        redis.call('PEXPIREAT', key, tonumber(res[2]) + bufferTime); " + "    end; " + "end; "
			+ "return count;";

	private static final String SCRIPT_CLEANUP = "local key = KEYS[1]; " + "local now = tonumber(ARGV[1]); "
			+ "local bufferTime = tonumber(ARGV[2]); " +
			"local removed = redis.call('ZREMRANGEBYSCORE', key, '-inf', now); " +
			"local count = redis.call('ZCARD', key); " + "if count == 0 then " +
			"    redis.call('DEL', key); " + "    return 0; " + "else " +
			"    local res = redis.call('ZRANGE', key, 0, 0, 'REV', 'WITHSCORES');" + "    if res and #res > 0 then "
			+ "        redis.call('PEXPIREAT', key, tonumber(res[2]) + bufferTime); " + "    end; " + "end;"
			+ "return count;";

	private static final String SCRIPT_GET_ACTIVE_NODES = "local key = KEYS[1]; " + "local now = tonumber(ARGV[1]); "
			+ "local bufferTime = tonumber(ARGV[2]); " +
			"redis.call('ZREMRANGEBYSCORE', key, '-inf', now); " +
			"local count = redis.call('ZCARD', key); " + "if count == 0 then " +
			"    redis.call('DEL', key); " + "    return {}; " + "else " +
			"    local res = redis.call('ZRANGE', key, 0, 0, 'REV', 'WITHSCORES');" + "    if res and #res > 0 then "
			+ "        redis.call('PEXPIREAT', key, tonumber(res[2]) + bufferTime); " + "    end; " + "end; "
			+ "return redis.call('ZRANGE', key, 0, 1, 'REV');";

	public static RedissonClient redissonClient() {
		Config config = new Config();
		config.setCodec(new org.redisson.codec.JsonJacksonCodec());
		config.useClusterServers()
			.setNodeAddresses(
					Arrays.asList("redis://127.0.0.1:7001", "redis://127.0.0.1:7002", "redis://127.0.0.1:7003",
							"redis://127.0.0.1:7004", "redis://127.0.0.1:7005", "redis://127.0.0.1:7006"));
		return Redisson.create(config);
	}

	public static void testRedisLua(String scriptString, String key, Object[] args) {
		try {
			RScript script = redissonClient.getScript(StringCodec.INSTANCE);
			String cacheSha = script.scriptLoad(scriptString);
			Object result = script.evalSha(RScript.Mode.READ_WRITE, cacheSha, RScript.ReturnType.VALUE,
					Collections.singletonList(key), args);
			if (result instanceof List) {
				List<Object> list = (List<Object>) result;
				log.info("-----------");
			}
			log.info("{}", result);
		}
		catch (Exception e) {
			log.error("Redis Lua script execution failed", e);
		}
	}

	public static void testAdd() {
		for (int i = 0; i < 10; i++) {
			String nodeId = NodeInstanceHolder.getNodeId();
			log.info("nodeId:{}", nodeId);
			testRedisLua(SCRIPT_ADD_AND_RENEW, "k1", new Object[] { nodeId + i, System.currentTimeMillis(),
					System.currentTimeMillis() + 1000 * 1000, 100 });
		}
	}

	public static void testRemove() {
		testRedisLua(SCRIPT_REMOVE_AND_RENEW, "k1", new Object[] {
				"DESKTOP-7HCQ9A4-6ad95282-9ad0-426e-9171-924ccbd7fcd90", System.currentTimeMillis(), 100 });
	}

	public static void testCleanUp() {
		testRedisLua(SCRIPT_CLEANUP, "k1", new Object[] { System.currentTimeMillis(), 100 });
	}

	public static void testGetActiveNodes() {
		testRedisLua(SCRIPT_GET_ACTIVE_NODES, "k1", new Object[] { System.currentTimeMillis(), 100 });
	}

	public static void testRCuckooFilter() {
		RCuckooFilter<String> filter = redissonClient.getCuckooFilter("user:cf2", StringCodec.INSTANCE);
		filter.init(CuckooFilterInitArgs.capacity(100000).bucketSize(4).maxIterations(500).expansion(2));
		boolean added = filter.add("element1");
		boolean addedNew = filter.addIfAbsent("element2");
		Set<String> addedItems = filter
			.add(CuckooFilterAddArgs.<String>items(List.of("a", "b", "c")).capacity(50000).noCreate());
		Set<String> newItems = filter
			.addIfAbsent(CuckooFilterAddArgs.<String>items(List.of("d", "e", "f")).capacity(50000));
		Set<String> existing = filter.exists(List.of("a", "b", "c", "d"));
		boolean removed = filter.remove("element1");
		log.info("--------------");

	}

	public static void main(String[] args) {
		testGetActiveNodes();
	}

}