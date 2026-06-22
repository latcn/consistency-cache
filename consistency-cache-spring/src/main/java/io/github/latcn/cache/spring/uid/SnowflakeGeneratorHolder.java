package io.github.latcn.cache.spring.uid;

import cn.hutool.core.lang.generator.SnowflakeGenerator;
import java.util.concurrent.atomic.AtomicReference;
import org.redisson.api.RedissonClient;

public class SnowflakeGeneratorHolder {

	private static final String SNOWFLAKE_WORKER_ID = "SNOWFLAKE_WORKER_ID";

	private static final AtomicReference<SnowflakeGenerator> snowflakeGeneratorReference = new AtomicReference<>();

	public SnowflakeGeneratorHolder(RedissonClient redissonClient) {
		long workerId = redissonClient.getAtomicLong(SNOWFLAKE_WORKER_ID).addAndGet(1);
		snowflakeGeneratorReference.compareAndSet(null, new SnowflakeGenerator(workerId, 0));
	}

	public static SnowflakeGenerator getSnowflakeGenerator() {
		return snowflakeGeneratorReference.get();
	}

}
