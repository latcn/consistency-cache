package io.github.latcn.cache.spring.distributed;

import io.github.latcn.cache.core.distributed.CacheOperation;
import io.github.latcn.cache.core.distributed.CacheOperationType;
import io.github.latcn.cache.core.distributed.DistributedCacheManager;
import io.github.latcn.cache.core.model.CacheKey;
import io.github.latcn.cache.core.model.CacheValue;
import io.github.latcn.cache.core.util.ConcurrentFifoList;
import io.github.latcn.cache.core.util.ThreadUtils;
import io.github.latcn.cache.core.util.TimeUtil;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;

@Slf4j
public class RedisCacheManager implements DistributedCacheManager {

	private static final int SCHEDULED_THREAD_POOL_SIZE = 1;

	private static final int EMPTY_POLL_SLEEP_MILLISECONDS = 1;

	private final RedissonClient redissonClient;

	private final int maxBatchSize;

	private final int maxWaitMs;

	private final ScheduledExecutorService scheduledExecutorService;

	private final ConcurrentFifoList<CacheOperation> cacheOperations;

	private final AtomicBoolean batchExecuteIsRunning = new AtomicBoolean(false);

	private final AtomicInteger handlerSucCount = new AtomicInteger(0);

	private final AtomicInteger batchExecCount = new AtomicInteger(0);

	public RedisCacheManager(RedissonClient redissonClient, int cacheOperationSize, int maxBatchSize, int maxWaitMs) {
		this.redissonClient = redissonClient;
		this.scheduledExecutorService = ThreadUtils.getScheduledThreadPoolExecutor(1,
				"RedisCacheManagerBulkOperationThread");
		this.maxBatchSize = maxBatchSize;
		this.maxWaitMs = maxWaitMs;
		this.cacheOperations = new ConcurrentFifoList(cacheOperationSize, ConcurrentFifoList.OverflowStrategy.REJECT);
		this.scheduledExecutorService.scheduleAtFixedRate(this::batchExecute, maxWaitMs, maxWaitMs,
				TimeUnit.MILLISECONDS);
	}

	@Override
	public boolean isHealthy() {
		return RedisHealthCheck.checkHealth(redissonClient);
	}

	@Override
	public CompletableFuture<CacheValue> getInBatch(CacheKey key) {
		return addToCacheOperations(CacheOperationType.GET, key, null);
	}

	@Override
	public CompletableFuture<Boolean> putInBatch(CacheKey key, CacheValue cacheValue) {
		return addToCacheOperations(CacheOperationType.PUT, key, cacheValue);
	}

	@Override
	public CompletableFuture<Boolean> removeInBatch(CacheKey key) {
		return addToCacheOperations(CacheOperationType.REMOVE, key, null);
	}

	private CompletableFuture addToCacheOperations(CacheOperationType cacheOperationType, CacheKey cacheKey,
			CacheValue cacheValue) {
		CompletableFuture completableFuture = new CompletableFuture<>();
		ConcurrentLinkedQueue linkedQueue = new ConcurrentLinkedQueue<>();
		linkedQueue.add(completableFuture);
		CacheOperation cacheOperation = CacheOperation.builder()
			.operationType(cacheOperationType)
			.key(cacheKey)
			.cacheValue(cacheValue)
			.results(linkedQueue)
			.createTime(System.currentTimeMillis())
			.build();
		CacheOperation oldCacheOperation = this.cacheOperations.put(cacheOperation);
		if (oldCacheOperation != null) {
			cacheOperation.getResults().addAll(oldCacheOperation.getResults());
		}
		return completableFuture;
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

	/**
	 * redisson 批量执行
	 */
	private void batchExecute() {
		if (!batchExecuteIsRunning.compareAndSet(false, true)) {
			return;
		}
		long startTime = TimeUtil.currentNanoToMil();
		final List<CacheOperation> batchOperations = new ArrayList<>();
		try {
			batchExecCount.incrementAndGet();
			BatchOptions options = BatchOptions.defaults();
			RBatch batch = redissonClient.createBatch(options);

			while (maxBatchSize > batchOperations.size() && (TimeUtil.currentNanoToMil() - startTime) < maxWaitMs) {
				List<CacheOperation> operations = cacheOperations.drain(maxBatchSize - batchOperations.size());
				if (operations == null || operations.size() == 0) {
					Thread.sleep(EMPTY_POLL_SLEEP_MILLISECONDS);
					continue;
				}
				for (CacheOperation cacheOperation : operations) {
					if (cacheOperation.getOperationType() == CacheOperationType.GET) {
						batch.getBucket(actualKey(cacheOperation.getKey())).getAsync();
					}
					else if (cacheOperation.getOperationType() == CacheOperationType.PUT) {
						CacheValue cacheValue = cacheOperation.getCacheValue();
						if (cacheValue.getExpireTime() >= CacheValue.MAX_EXPIRE_TIME) {
							batch.getBucket(actualKey(cacheOperation.getKey())).setAsync(cacheValue);
						}
						else {
							if (cacheValue.isExpired()) {
								completeCacheOperation(cacheOperation, false);
								continue;
							}
							long duration = cacheValue.getExpireTime() - System.currentTimeMillis();
							if (duration <= 0) {
								completeCacheOperation(cacheOperation, false);
								continue;
							}
							batch.getBucket(actualKey(cacheOperation.getKey()))
								.setAsync(cacheValue, Duration.of(duration, ChronoUnit.MILLIS));
						}
					}
					else if (cacheOperation.getOperationType() == CacheOperationType.REMOVE) {
						batch.getBucket(actualKey(cacheOperation.getKey())).deleteAsync();
					}
					batchOperations.add(cacheOperation);
				}
			}
			if (batchOperations.size() > 0) {
				log.debug("============batchExecute-{}  batch-size {}", batchExecCount.get(), batchOperations.size());
				BatchResult<?> batchResult = batch.execute();
				List<?> responses = batchResult.getResponses();
				log.debug("============batchExecute-{} responses {}", batchExecCount.get(), responses.size());
				completeCacheOperation(batchOperations, responses);
				log.debug("============batchExecute-{} batch already get success: {}", batchExecCount.get(),
						handlerSucCount.get());
			}
		}
		catch (Exception e) {
			completeCacheOperationException(batchOperations);
			log.error("batchExecute ex", e);
		}
		finally {
			batchExecuteIsRunning.compareAndSet(true, false);
		}
	}

	private void completeCacheOperationException(List<CacheOperation> cacheOperations) {
		if (cacheOperations == null || cacheOperations.size() == 0) {
			return;
		}
		for (CacheOperation cacheOperation : cacheOperations) {
			completeCacheOperation(cacheOperation, null);
		}
	}

	private void completeCacheOperation(List<CacheOperation> cacheOperations, List<?> responses) {
		if (cacheOperations == null || cacheOperations.size() == 0) {
			log.error("completeCacheOperation cacheOperations is empty");
			return;
		}
		for (int i = 0; i < cacheOperations.size(); i++) {
			CacheOperation cacheOperation = cacheOperations.get(i);
			try {
				Object response = responses.get(i);
				if (cacheOperation.getOperationType() == CacheOperationType.GET) {
					if (response instanceof CacheValue) {
						completeCacheOperation(cacheOperation, response);
					}
					else {
						completeCacheOperation(cacheOperation, null);
					}
				}
				else {
					if (response instanceof Exception) {
						completeCacheOperation(cacheOperation, false);
					}
					else {
						completeCacheOperation(cacheOperation, true);
					}
				}
			}
			catch (Exception e) {
				log.error("completeCacheOperation", e);
			}
		}
	}

	private void completeCacheOperation(CacheOperation cacheOperation, Object result) {
		try {
			ConcurrentLinkedQueue<CompletableFuture> concurrentLinkedQueue = cacheOperation.getResults();
			concurrentLinkedQueue.forEach(cf -> {
				if (cf.isDone()) {
					return;
				}
				cf.complete(result);
				handlerSucCount.incrementAndGet();
			});
		}
		catch (Exception e) {
			log.error("completeCacheOperation", e);
		}
	}

}
