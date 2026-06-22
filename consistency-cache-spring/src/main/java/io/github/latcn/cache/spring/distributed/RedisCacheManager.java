package io.github.latcn.cache.spring.distributed;

import io.github.latcn.cache.core.distributed.DistributedCacheManager;
import io.github.latcn.cache.core.model.CacheKey;
import io.github.latcn.cache.core.model.CacheValue;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

@Slf4j
public class RedisCacheManager implements DistributedCacheManager {

	private final RedissonClient redissonClient;

	public RedisCacheManager(RedissonClient redissonClient) {
		this.redissonClient = redissonClient;
	}

	@Override
	public boolean isHealthy() {
		return RedissonClusterHealthCheck.checkClusterHealth(redissonClient);
	}

	@Override
	public CacheValue get(CacheKey key) {
		RBucket<CacheValue> valueRBucket = redissonClient.getBucket(actualKey(key));
		return valueRBucket.get();
	}

	@Override
	public void put(CacheKey key, CacheValue cacheValue) {
		if (cacheValue.getExpireTime() >= CacheValue.MAX_EXPIRE_TIME) {
			redissonClient.getBucket(actualKey(key)).set(cacheValue);
		}
		else {
			if (cacheValue.isExpired()) {
				return;
			}
			long duration = cacheValue.getExpireTime() - System.currentTimeMillis();
			if (duration <= 0) {
				return;
			}
			redissonClient.getBucket(actualKey(key)).set(cacheValue, Duration.of(duration, ChronoUnit.MILLIS));
		}
	}

	@Override
	public void remove(CacheKey key) {
		redissonClient.getBucket(actualKey(key)).delete();
	}

	@Override
	public boolean containKey(CacheKey key) {
		return redissonClient.getBucket(actualKey(key)).isExists();
	}

	private String actualKey(CacheKey cacheKey) {
		return cacheKey.getKey().toString();
	}

}
