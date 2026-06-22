package io.github.latcn.cache.spring.executor;

import io.github.latcn.cache.core.executor.CacheBloomFilter;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RCuckooFilter;
import org.redisson.api.RedissonClient;
import org.redisson.api.cuckoofilter.CuckooFilterAddArgs;
import org.redisson.api.cuckoofilter.CuckooFilterInitArgs;
import org.redisson.client.codec.StringCodec;

@Slf4j
public class EnhanceRCuckooFilter implements CacheBloomFilter {

	private final RedissonClient redissonClient;

	public EnhanceRCuckooFilter(RedissonClient redissonClient) {
		this.redissonClient = redissonClient;
	}

	public void initFilter(String filterName, CuckooFilterInitArgs initArgs) {
		getRCuckooFilter(filterName).init(initArgs);
	}

	@Override
	public <T> boolean exists(String filterName, T cacheKey) {
		return getRCuckooFilter(filterName).exists(cacheKey.toString());
	}

	@Override
	public <T> void add(String filterName, T cacheKey) {
		getRCuckooFilter(filterName).addIfAbsent(cacheKey.toString());
	}

	@Override
	public <T extends List> void addList(String filterName, T cacheKeys) {
		getRCuckooFilter(filterName)
			.addIfAbsent(CuckooFilterAddArgs.<String>items(cacheKeys).capacity(cacheKeys.size()));
	}

	@Override
	public <T> void remove(String filterName, T cacheKey) {
		getRCuckooFilter(filterName).remove(cacheKey.toString());
	}

	@Override
	public <T extends List> void removeList(String filterName, T cacheKeys) {
		for (Object element : cacheKeys) {
			getRCuckooFilter(filterName).remove(element.toString());
		}
	}

	private RCuckooFilter<String> getRCuckooFilter(String filterName) {
		return redissonClient.getCuckooFilter(filterName, StringCodec.INSTANCE);
	}

}
