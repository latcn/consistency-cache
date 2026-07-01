package io.github.latcn.cache.spring.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.hutool.core.util.EnumUtil;
import io.github.latcn.cache.core.circuitbreaker.CacheCircuitBreaker;
import io.github.latcn.cache.core.executor.CacheBloomFilter;
import io.github.latcn.cache.core.executor.CacheExecutor;
import io.github.latcn.cache.core.executor.DefaultCacheExecutor;
import io.github.latcn.cache.core.hotspot.DefaultHotspotDetector;
import io.github.latcn.cache.core.local.LocalCacheFactory;
import io.github.latcn.cache.core.local.LocalCacheManager;
import io.github.latcn.cache.core.local.LocalCacheMarkerManager;
import io.github.latcn.cache.core.model.*;
import io.github.latcn.cache.core.pubsub.BroadcastPublisher;
import io.github.latcn.cache.core.pubsub.BroadcastSubscriber;
import io.github.latcn.cache.core.pubsub.Broadcaster;
import io.github.latcn.cache.core.pubsub.BroadcasterListener;
import io.github.latcn.cache.core.repository.InvalidationRecordDAO;
import io.github.latcn.cache.spring.handler.SpringCacheEvictHandler;
import io.github.latcn.cache.spring.local.adapter.CaffeineCacheAdapter;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@DisplayName("SpringCacheEvictHandler Stress Tests with Real Components")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SpringCacheEvictHandlerStressTest {

	private static final String H2_URL = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=MySQL;LOCK_TIMEOUT=10000";

	private static final String H2_USER = "sa";

	private static final String H2_PASSWORD = "";

	private DataSource dataSource;

	private PlatformTransactionManager transactionManager;

	private TransactionTemplate transactionTemplate;

	private InvalidationRecordDAO invalidationRecordDAO;

	private CacheExecutor cacheExecutor;

	private SpringCacheEvictHandler handler;

	private HccProperties.CacheEvictProperties evictProperties;

	private LocalCacheManager localCacheManager;

	// @BeforeEach
	void setUp() throws SQLException {
		dataSource = createH2DataSource();
		initDatabase(dataSource);
		transactionManager = createTransactionManager(dataSource);
		transactionTemplate = new TransactionTemplate(transactionManager);
		invalidationRecordDAO = InvalidationRecordDAO.getInstance();
		cacheExecutor = createRealCacheExecutor();
		evictProperties = new HccProperties.CacheEvictProperties();
		evictProperties.setBaseDelayMs(1000);
		evictProperties.setCompensationBatchSize(50);
		evictProperties.setMaxRetryCount(5);
		handler = new SpringCacheEvictHandler(cacheExecutor, evictProperties, dataSource, transactionManager);
	}

	// @AfterEach
	void tearDown() throws SQLException {
		try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
			stmt.execute("DROP TABLE IF EXISTS invalidation_record");
		}
		if (localCacheManager != null) {
			localCacheManager.clear();
		}
	}

	private DataSource createH2DataSource() {
		DriverManagerDataSource ds = new DriverManagerDataSource();
		ds.setDriverClassName("org.h2.Driver");
		ds.setUrl(H2_URL);
		ds.setUsername(H2_USER);
		ds.setPassword(H2_PASSWORD);
		return ds;
	}

	private PlatformTransactionManager createTransactionManager(DataSource dataSource) {
		return new DataSourceTransactionManager(dataSource);
	}

	private CacheExecutor createRealCacheExecutor() {
		HccProperties.LocalCacheProperties localProps = new HccProperties.LocalCacheProperties();
		localProps.setCacheType(LocalCacheType.CAFFEINE.name());
		localProps.setCacheClz(CaffeineCacheAdapter.class.getName());
		localProps.setInitialCapacity(1000);
		localProps.setMaximumSize(100000);
		LocalCacheFactory.registerCacheType(localProps.getCacheType(), localProps.getCacheClz());
		localCacheManager = new LocalCacheManager(localProps);

		DefaultHotspotDetector writeHotspotDetector = new DefaultHotspotDetector(new HccProperties.HotspotProperties());

		DefaultHotspotDetector readHotspotDetector = new DefaultHotspotDetector(new HccProperties.HotspotProperties());

		CacheCircuitBreaker circuitBreaker = new CacheCircuitBreaker(0.5, 30000, Set.of());

		CacheBloomFilter mockBloomFilter = new CacheBloomFilter() {
			@Override
			public <T> boolean exists(String filterName, T cacheKey) {
				return true;
			}

			@Override
			public <T> void add(String filterName, T cacheKey) {
			}

			@Override
			public <T extends List> void addList(String filterName, T cacheKeys) {
			}

			@Override
			public <T> void remove(String filterName, T cacheKey) {
			}

			@Override
			public <T extends List> void removeList(String filterName, T cacheKeys) {
			}
		};

		LocalCacheMarkerManager mockMarkerManager = new LocalCacheMarkerManager(5, 100) {
			@Override
			public void markLocalCacheUsage(String cacheKey, long expireTime) {
			}

			@Override
			public void removeLocalCacheUsage(String cacheKey) {
			}

			@Override
			public void doCleanUp() {
			}

			@Override
			public List<String> getActiveNodes(String cacheKey) {
				return List.of();
			}
		};

		DefaultCacheExecutor executor = new DefaultCacheExecutor(localCacheManager, new MockDistributedCacheManager(),
				mockMarkerManager, writeHotspotDetector, readHotspotDetector, circuitBreaker, mockBloomFilter);

		executor.setBroadcaster(new MockBroadcaster());

		return executor;
	}

	private void initDatabase(DataSource ds) throws SQLException {
		try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
			stmt.execute("CREATE TABLE IF NOT EXISTS invalidation_record (" + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
					+ "uid VARCHAR(64) NOT NULL, " + "cache_key VARCHAR(256) NOT NULL, " + "cache_level VARCHAR(32), "
					+ "consistency_level VARCHAR(32), " + "operation_type VARCHAR(32), " + "node_id VARCHAR(128), "
					+ "status INT DEFAULT 0, " + "retry_count INT DEFAULT 0, " + "error_message TEXT, "
					+ "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
					+ "update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
					+ "next_execution_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
					+ "UNIQUE KEY uk_uid_cache_key (uid, cache_key)" + ")");
		}
	}

	private InvalidationRecord createRecord(String uidSuffix) {
		Timestamp timestamp = Timestamp.from(Instant.now());
		return InvalidationRecord.builder()
			.uid("uid-" + uidSuffix + "-" + System.nanoTime())
			.cacheKey("cacheKey:" + uidSuffix + ":" + ThreadLocalRandom.current().nextInt(100000))
			.cacheLevel(CacheLevel.LOCAL_CACHE.name())
			.consistencyLevel(ConsistencyLevel.HIGH.name())
			.operationType("DELETE")
			.nodeId("test-node-1")
			.status(0)
			.retryCount(0)
			.transactionEnabled(true)
			.createTime(timestamp)
			.updateTime(timestamp)
			.build();
	}

	// @Test
	@Order(1)
	@DisplayName("TC-1: startInvalidate with transaction - high concurrency")
	void testStartInvalidateWithTransaction() throws InterruptedException {
		int[] threadCounts = { 10, 50, 100, 200 };
		int opsPerThread = 500;

		for (int threadCount : threadCounts) {
			ExecutorService executor = Executors.newFixedThreadPool(threadCount);
			CountDownLatch latch = new CountDownLatch(threadCount);
			AtomicLong successCount = new AtomicLong(0);
			AtomicLong failCount = new AtomicLong(0);
			AtomicLong totalLatency = new AtomicLong(0);

			long startTime = System.currentTimeMillis();

			for (int i = 0; i < threadCount; i++) {
				final int threadId = i;
				executor.submit(() -> {
					try {
						for (int j = 0; j < opsPerThread; j++) {
							InvalidationRecord record = createRecord("tx-" + threadId + "-" + j);
							record.setTransactionEnabled(true);
							long opStart = System.nanoTime();
							try {
								handler.startInvalidate(record, () -> {
									sleep(0);
									return null;
								});
								successCount.incrementAndGet();
								totalLatency.addAndGet(System.nanoTime() - opStart);
							}
							catch (Exception e) {
								log.error("testStartInvalidateWithTransaction thread:{}, op:{}", threadId, j, e);
								failCount.incrementAndGet();
							}
						}
					}
					finally {
						latch.countDown();
					}
				});
			}

			latch.await(180, TimeUnit.SECONDS);
			long duration = System.currentTimeMillis() - startTime;
			executor.shutdown();
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

			double qps = (successCount.get() * 1000.0) / duration;
			double avgLatencyMs = totalLatency.get() / (successCount.get() * 1_000_000.0);

			log.info(
					"startInvalidate TX | Threads: {} | Success: {} | Failed: {} | Duration: {}ms | QPS: {} | AvgLatency: {}ms",
					threadCount, successCount.get(), failCount.get(), duration, qps, avgLatencyMs);

			assertEquals(0, failCount.get(), "There should be no failures");
			assertEquals(threadCount * opsPerThread, successCount.get(), "All operations should succeed");
		}
	}

	// @Test
	@Order(2)
	@DisplayName("TC-2: compensatePendingRecords query performance")
	void testCompensatePendingRecordsQueryPerformance() throws InterruptedException {
		int[] recordCounts = { 100, 500, 1000, 5000 };

		for (int recordCount : recordCounts) {
			insertTestRecords(recordCount);

			ExecutorService executor = Executors.newFixedThreadPool(5);
			CountDownLatch latch = new CountDownLatch(5);
			AtomicLong totalProcessed = new AtomicLong(0);
			long startTime = System.nanoTime();

			for (int i = 0; i < 5; i++) {
				executor.submit(() -> {
					try {
						for (int j = 0; j < 10; j++) {
							List<InvalidationRecord> records = handler.getPendingRecords(
									evictProperties.getMaxRetryCount(), evictProperties.getCompensationBatchSize());
							totalProcessed.addAndGet(records.size());
							sleep(10);
						}
					}
					finally {
						latch.countDown();
					}
				});
			}

			latch.await(60, TimeUnit.SECONDS);
			long duration = System.nanoTime() - startTime;
			executor.shutdown();

			double qps = (totalProcessed.get() * 1_000_000_000.0) / duration;
			double avgQueryTimeMs = duration / (totalProcessed.get() * 1_000_000.0);

			log.info("Compensate Query | Records: {} | Processed: {} | Duration: {}ms | QPS: {} | AvgQueryTime: {}ms",
					recordCount, totalProcessed.get(), duration / 1_000_000.0, qps, avgQueryTimeMs);
		}
	}

	// @Test
	@Order(3)
	@DisplayName("TC-3: markFailed exponential backoff verification")
	void testMarkFailedExponentialBackoff() {
		InvalidationRecord record = createRecord("backoff-test");
		long baseDelay = evictProperties.getBaseDelayMs();

		long[] expectedDelays = { baseDelay, baseDelay * 2, baseDelay * 4, baseDelay * 8, baseDelay * 16, };

		for (int i = 0; i < expectedDelays.length; i++) {
			long beforeCalc = System.currentTimeMillis();
			long beforeNextTime = record.getNextExecutionTime() == null ? beforeCalc : record.getCreateTime().getTime();
			record.setRetryCount(i);
			record.calculateNextExecutionTime(baseDelay);
			long afterCalc = System.currentTimeMillis();

			Timestamp nextTime = record.getNextExecutionTime();
			long expectedDelay = expectedDelays[i];

			long actualDelay = nextTime.getTime() - beforeNextTime;

			double tolerance = 100 + (afterCalc - beforeCalc);
			assertTrue(Math.abs(actualDelay - expectedDelay) <= tolerance,
					String.format("Failure #%d: expected ~%dms, got %dms (tolerance: %.0fms)", i + 1, expectedDelay,
							actualDelay, tolerance));

			log.info("Backoff #{}: expected={}ms, actual={}ms, diff={}ms", i + 1, expectedDelay, actualDelay,
					Math.abs(actualDelay - expectedDelay));
		}
	}

	// @Test
	@Order(4)
	@DisplayName("TC-4: markCompleted transaction update stress")
	void testMarkCompletedTransactionUpdate() throws InterruptedException {
		int[] threadCounts = { 10, 50, 100 };
		int opsPerThread = 500;

		for (int threadCount : threadCounts) {
			List<String> uids = insertAndReturnUids(threadCount * opsPerThread);

			ExecutorService executor = Executors.newFixedThreadPool(threadCount);
			CountDownLatch latch = new CountDownLatch(threadCount);
			AtomicLong successCount = new AtomicLong(0);
			AtomicLong failCount = new AtomicLong(0);

			long startTime = System.currentTimeMillis();

			for (int i = 0; i < threadCount; i++) {
				final int threadId = i;
				executor.submit(() -> {
					try {
						for (int j = 0; j < opsPerThread; j++) {
							String uid = uids.get(threadId * opsPerThread + j);
							String cacheKey = "cacheKey:markComplete:" + j;
							try {
								InvalidationRecord record = InvalidationRecord.builder()
									.uid(uid)
									.cacheKey(cacheKey)
									.build();
								handler.markCompleted(null, record);
								successCount.incrementAndGet();
							}
							catch (Exception e) {
								failCount.incrementAndGet();
							}
						}
					}
					finally {
						latch.countDown();
					}
				});
			}

			latch.await(120, TimeUnit.SECONDS);
			long duration = System.currentTimeMillis() - startTime;
			executor.shutdown();

			double qps = (successCount.get() * 1000.0) / duration;

			log.info("markCompleted TX | Threads: {} | Success: {} | Failed: {} | Duration: {}ms | QPS: {}",
					threadCount, successCount.get(), failCount.get(), duration, qps);

			assertEquals(threadCount * opsPerThread, successCount.get() + failCount.get());
		}
	}

	// @Test
	@Order(5)
	@DisplayName("TC-5: concurrent contention with SKIP LOCKED")
	void testConcurrentContentionSkipLocked() throws InterruptedException {
		int schedulers = 3;
		int threadsPerScheduler = 10;
		int opsPerThread = 1;
		int totalRecords = 1000;

		insertTestRecordsWithSameCacheKey(totalRecords, "shared-key");

		AtomicLong totalProcessed = new AtomicLong(0);
		AtomicLong duplicateProcessed = new AtomicLong(0);
		List<AtomicLong> schedulerCounts = new ArrayList<>();
		ConcurrentHashMap<String, Long> recordMap = new ConcurrentHashMap<>();

		ExecutorService executor = Executors.newFixedThreadPool(schedulers * threadsPerScheduler);
		CountDownLatch latch = new CountDownLatch(schedulers * threadsPerScheduler);

		for (int s = 0; s < schedulers; s++) {
			AtomicLong schedulerCount = new AtomicLong(0);
			schedulerCounts.add(schedulerCount);

			for (int t = 0; t < threadsPerScheduler; t++) {
				executor.submit(() -> {
					try {
						for (int i = 0; i < opsPerThread; i++) {
							this.transactionTemplate.execute(status -> {
								Connection conn = DataSourceUtils.getConnection(dataSource);
								List<InvalidationRecord> pendingRecords = invalidationRecordDAO.findPendingRecords(conn,
										3, 100);
								if (pendingRecords != null && !pendingRecords.isEmpty()) {
									for (InvalidationRecord record : pendingRecords) {
										try {
											recordMap.compute(record.getCacheKey(), (k, v) -> v == null ? 1 : v + 1);
											doClean(conn, record);
										}
										catch (Exception e) {
											log.error("Compensation task failed for record uid: {}, cacheKey: {}",
													record.getUid(), record.getCacheKey(), e);
										}
									}
								}
								return null;
							});
							sleep(5);
						}
					}
					finally {
						latch.countDown();
					}
				});
			}
		}

		latch.await(120, TimeUnit.SECONDS);
		ConcurrentHashMap<String, Long> dupMap = recordMap.entrySet()
			.stream()
			.filter((entry) -> entry.getValue() > 1)
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1, ConcurrentHashMap::new));
		executor.shutdown();

		log.info(
				"SKIP LOCKED Contention | Schedulers: {} | ThreadsPerScheduler: {} | TotalProcessed: {} | Duplicates: {}",
				schedulers, threadsPerScheduler, totalProcessed.get(), duplicateProcessed.get());

		assertEquals(0, dupMap.size(), "No duplicate processing should occur with SKIP LOCKED");
	}

	private void doClean(Connection conn, InvalidationRecord invalidationRecord) {
		try {
			CacheKey cacheKey = CacheKey.builder()
				.key(invalidationRecord.getCacheKey())
				.cacheLevel(EnumUtil.fromString(CacheLevel.class, invalidationRecord.getCacheLevel(),
						CacheLevel.ADAPTIVE_CACHE))
				.consistencyLevel(EnumUtil.fromString(ConsistencyLevel.class, invalidationRecord.getConsistencyLevel(),
						ConsistencyLevel.HIGH))
				.build();
			this.cacheExecutor.evict(cacheKey);
			if (invalidationRecord.isTransactionEnabled()) {
				markCompleted(conn, invalidationRecord);
			}
		}
		catch (Exception e) {
			if (invalidationRecord.isTransactionEnabled()) {
				invalidationRecord.calculateNextExecutionTime(1000);
				markFailed(conn, invalidationRecord);
			}
			log.error("doClean", e);
		}
	}

	private boolean markFailed(Connection conn, InvalidationRecord invalidationRecord) {
		if (conn == null) {
			return this.transactionTemplate.execute(status -> {
				try {
					Connection newConn = DataSourceUtils.getConnection(dataSource);
					boolean result = this.invalidationRecordDAO.markFailed(newConn, invalidationRecord.getUid(),
							invalidationRecord.getCacheKey(), invalidationRecord.getErrorMessage(),
							invalidationRecord.getNextExecutionTime());
					log.info("markFailed uid:{}, cacheKey:{}", invalidationRecord.getUid(),
							invalidationRecord.getCacheKey());
					return result;
				}
				catch (Throwable t) {
					status.setRollbackOnly();
					log.error("markFailed failed uid:{}, cacheKey:{}", invalidationRecord.getUid(),
							invalidationRecord.getCacheKey(), t);
				}
				return false;
			});
		}
		else {
			boolean result = this.invalidationRecordDAO.markFailed(conn, invalidationRecord.getUid(),
					invalidationRecord.getCacheKey(), invalidationRecord.getErrorMessage(),
					invalidationRecord.getNextExecutionTime());
			log.info("markFailed conn uid:{}, cacheKey:{}", invalidationRecord.getUid(),
					invalidationRecord.getCacheKey());
			return result;
		}
	}

	public boolean markCompleted(Connection conn, InvalidationRecord invalidationRecord) {

		if (conn == null) {
			return this.transactionTemplate.execute(status -> {
				try {
					Connection newConn = DataSourceUtils.getConnection(dataSource);
					boolean result = this.invalidationRecordDAO.markCompleted(newConn, invalidationRecord.getUid(),
							invalidationRecord.getCacheKey());
					log.info("markCompleted uid:{}, cacheKey:{}", invalidationRecord.getUid(),
							invalidationRecord.getCacheKey());
					return result;
				}
				catch (Throwable t) {
					status.setRollbackOnly();
					log.error("markCompleted uid:{}, cacheKey:{}", invalidationRecord.getUid(),
							invalidationRecord.getCacheKey(), t);
				}
				return false;
			});
		}
		else {
			boolean result = this.invalidationRecordDAO.markCompleted(conn, invalidationRecord.getUid(),
					invalidationRecord.getCacheKey());
			log.info("markCompleted conn uid:{}, cacheKey:{}", invalidationRecord.getUid(),
					invalidationRecord.getCacheKey());
			return result;
		}
	}

	// @Test
	@Order(6)
	@DisplayName("TC-6: queue overflow degradation test")
	void testQueueOverflowDegradation() throws InterruptedException {
		int writeRate = 5000;
		int durationSeconds = 10;

		ExecutorService writer = Executors.newFixedThreadPool(10);
		AtomicLong writeCount = new AtomicLong(0);
		AtomicLong degradedCount = new AtomicLong(0);
		CountDownLatch writeLatch = new CountDownLatch(10);

		long startTime = System.currentTimeMillis();

		for (int i = 0; i < 10; i++) {
			writer.submit(() -> {
				try {
					long written = 0;
					while ((System.currentTimeMillis() - startTime) < durationSeconds * 1000) {
						InvalidationRecord record = createRecord("queue-overflow-" + written);
						record.setEvictPolicy(InvalidationRecord.EvictPolicy.DELAYED);
						handler.addToSuccess(record);
						writeCount.incrementAndGet();
						if (handler.getQueueSize() >= 1000) {
							degradedCount.incrementAndGet();
						}
						written++;
						if (writeRate > 0) {
							sleep(writeRate / 10);
						}
					}
				}
				finally {
					writeLatch.countDown();
				}
			});
		}

		writeLatch.await();
		long actualDuration = System.currentTimeMillis() - startTime;
		writer.shutdown();

		double actualQps = (writeCount.get() * 1000.0) / actualDuration;
		double degradationRate = (degradedCount.get() * 100.0) / writeCount.get();

		log.info(
				"Queue Overflow | WriteCount: {} | DegradedWrites: {} | Duration: {}ms | QPS: {} | DegradationRate: {}%",
				writeCount.get(), degradedCount.get(), actualDuration, actualQps, degradationRate);

		assertTrue(writeCount.get() > 0, "Should have written some records");
	}

	// @Test
	@Order(7)
	@DisplayName("TC-7: backpressure when compensation is slow")
	void testBackpressureWhenCompensationSlow() throws InterruptedException {
		insertTestRecords(500);

		AtomicLong processedCount = new AtomicLong(0);
		ExecutorService processor = Executors.newFixedThreadPool(3);

		long startTime = System.currentTimeMillis();

		for (int i = 0; i < 3; i++) {
			processor.submit(() -> {
				while ((System.currentTimeMillis() - startTime) < 30000) {
					List<InvalidationRecord> records = handler.getPendingRecords(evictProperties.getMaxRetryCount(),
							evictProperties.getCompensationBatchSize());
					if (records.isEmpty()) {
						sleep(100);
						continue;
					}
					for (InvalidationRecord record : records) {
						handler.processRecord(record);
						processedCount.incrementAndGet();
					}
				}
			});
		}

		Thread.sleep(10000);

		processor.shutdown();
		processor.awaitTermination(5, TimeUnit.SECONDS);

		long pendingAfter = handler.getPendingRecordCount(dataSource);
		double throughput = processedCount.get() / 10.0;

		log.info("Backpressure Test | Processed: {} | PendingAfter: {} | Throughput: {}/s", processedCount.get(),
				pendingAfter, throughput);

		assertTrue(pendingAfter >= 0, "Pending count should be tracked correctly");
	}

	// @Test
	@Order(8)
	@DisplayName("TC-8: real cache eviction verification")
	void testRealCacheEvictionVerification() throws InterruptedException {
		int threadCount = 50;
		int opsPerThread = 100;
		int totalRecords = threadCount * opsPerThread;

		ConcurrentHashMap<String, Integer> cacheKeyCounter = new ConcurrentHashMap<>();

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch latch = new CountDownLatch(threadCount);

		for (int i = 0; i < threadCount; i++) {
			final int threadId = i;
			executor.submit(() -> {
				try {
					for (int j = 0; j < opsPerThread; j++) {
						String cacheKey = "real-cache-key:" + threadId + ":" + j;
						CacheKey key = CacheKey.builder()
							.key(cacheKey)
							.cacheLevel(CacheLevel.LOCAL_CACHE)
							.consistencyLevel(ConsistencyLevel.HIGH)
							.build();

						CacheValue value = CacheValue.builder()
							.value("value-" + threadId + "-" + j)
							.expireTime(System.currentTimeMillis() + 3600000)
							.build();

						localCacheManager.put(key, value);
						cacheKeyCounter.put(cacheKey, 1);

						InvalidationRecord record = InvalidationRecord.builder()
							.uid("uid-real-" + threadId + "-" + j)
							.cacheKey(cacheKey)
							.cacheLevel(CacheLevel.LOCAL_CACHE.name())
							.consistencyLevel(ConsistencyLevel.HIGH.name())
							.operationType("DELETE")
							.nodeId("test-node-1")
							.status(0)
							.retryCount(0)
							.transactionEnabled(false)
							.evictPolicy(InvalidationRecord.EvictPolicy.IMMEDIATE)
							.build();

						handler.addToSuccess(record);
					}
				}
				finally {
					latch.countDown();
				}
			});
		}

		latch.await(60, TimeUnit.SECONDS);
		executor.shutdown();

		sleep(2000);

		int remainingKeys = 0;
		for (String cacheKey : cacheKeyCounter.keySet()) {
			CacheKey key = CacheKey.builder()
				.key(cacheKey)
				.cacheLevel(CacheLevel.LOCAL_CACHE)
				.consistencyLevel(ConsistencyLevel.HIGH)
				.build();
			if (localCacheManager.get(key) != null) {
				remainingKeys++;
			}
		}

		log.info("Real Cache Eviction | TotalKeys: {} | RemainingKeys: {} | Evicted: {}", totalRecords, remainingKeys,
				totalRecords - remainingKeys);

		assertEquals(0, remainingKeys, "All cache entries should be evicted");
	}

	// @Test
	@Order(9)
	@DisplayName("TC-9: mixed transaction and non-transaction stress")
	void testMixedTransactionAndNonTransactionStress() throws InterruptedException {
		int threadCount = 100;
		int opsPerThread = 200;

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch latch = new CountDownLatch(threadCount);
		AtomicLong txSuccessCount = new AtomicLong(0);
		AtomicLong nonTxSuccessCount = new AtomicLong(0);
		AtomicLong failCount = new AtomicLong(0);

		long startTime = System.currentTimeMillis();

		for (int i = 0; i < threadCount; i++) {
			final int threadId = i;
			executor.submit(() -> {
				try {
					for (int j = 0; j < opsPerThread; j++) {
						boolean useTransaction = (threadId % 2 == 0);
						InvalidationRecord record = createRecord("mixed-" + threadId + "-" + j);
						record.setTransactionEnabled(useTransaction);
						try {
							handler.startInvalidate(record, () -> null);
							if (useTransaction) {
								txSuccessCount.incrementAndGet();
							}
							else {
								nonTxSuccessCount.incrementAndGet();
							}
						}
						catch (Exception e) {
							failCount.incrementAndGet();
						}
					}
				}
				finally {
					latch.countDown();
				}
			});
		}

		latch.await(120, TimeUnit.SECONDS);
		long duration = System.currentTimeMillis() - startTime;
		executor.shutdown();

		double qps = ((txSuccessCount.get() + nonTxSuccessCount.get()) * 1000.0) / duration;

		log.info(
				"Mixed TX/Non-TX | Threads: {} | TXSuccess: {} | NonTXSuccess: {} | Failed: {} | Duration: {}ms | QPS: {}",
				threadCount, txSuccessCount.get(), nonTxSuccessCount.get(), failCount.get(), duration, qps);

		assertEquals(0, failCount.get(), "There should be no failures");
	}

	private void insertTestRecords(int count) {
		try (Connection conn = dataSource.getConnection()) {
			String sql = "INSERT INTO invalidation_record (uid, cache_key, cache_level, consistency_level, operation_type, node_id, status, retry_count, create_time, update_time, next_execution_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
			long now = System.currentTimeMillis();
			for (int i = 0; i < count; i++) {
				try (PreparedStatement ps = conn.prepareStatement(sql)) {
					ps.setString(1, "uid-" + System.nanoTime() + "-" + i);
					ps.setString(2, "cacheKey:test:" + i);
					ps.setString(3, "LOCAL_CACHE");
					ps.setString(4, "HIGH");
					ps.setString(5, "DELETE");
					ps.setString(6, "test-node");
					ps.setInt(7, 0);
					ps.setInt(8, 0);
					ps.setTimestamp(9, new Timestamp(now - 10000));
					ps.setTimestamp(10, new Timestamp(now - 5000));
					ps.setTimestamp(11, new Timestamp(now - 1000));
					ps.executeUpdate();
				}
			}
		}
		catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	private void insertTestRecordsWithSameCacheKey(int count, String sharedKey) {
		try (Connection conn = dataSource.getConnection()) {
			String sql = "INSERT INTO invalidation_record (uid, cache_key, cache_level, consistency_level, operation_type, node_id, status, retry_count, create_time, update_time, next_execution_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
			long now = System.currentTimeMillis();
			for (int i = 0; i < count; i++) {
				try (PreparedStatement ps = conn.prepareStatement(sql)) {
					ps.setString(1, "uid-" + System.nanoTime() + "-" + i);
					ps.setString(2, "cacheKey:" + sharedKey + ":" + i);
					ps.setString(3, "LOCAL_CACHE");
					ps.setString(4, "HIGH");
					ps.setString(5, "DELETE");
					ps.setString(6, "test-node");
					ps.setInt(7, 0);
					ps.setInt(8, 0);
					ps.setTimestamp(9, new Timestamp(now - 10000));
					ps.setTimestamp(10, new Timestamp(now - 5000));
					ps.setTimestamp(11, new Timestamp(now - 1000));
					ps.executeUpdate();
				}
			}
		}
		catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	private List<String> insertAndReturnUids(int count) {
		List<String> uids = new ArrayList<>();
		try (Connection conn = dataSource.getConnection()) {
			String sql = "INSERT INTO invalidation_record (uid, cache_key, cache_level, consistency_level, operation_type, node_id, status, retry_count, create_time, update_time, next_execution_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
			long now = System.currentTimeMillis();
			for (int i = 0; i < count; i++) {
				String uid = "uid-" + System.nanoTime() + "-" + i;
				uids.add(uid);
				try (PreparedStatement ps = conn.prepareStatement(sql)) {
					ps.setString(1, uid);
					ps.setString(2, "cacheKey:markComplete:" + i);
					ps.setString(3, "LOCAL_CACHE");
					ps.setString(4, "HIGH");
					ps.setString(5, "DELETE");
					ps.setString(6, "test-node");
					ps.setInt(7, 0);
					ps.setInt(8, 0);
					ps.setTimestamp(9, new Timestamp(now));
					ps.setTimestamp(10, new Timestamp(now));
					ps.setTimestamp(11, new Timestamp(now + 1000));
					ps.executeUpdate();
				}
			}
		}
		catch (SQLException e) {
			throw new RuntimeException(e);
		}
		return uids;
	}

	private void sleep(long millis) {
		try {
			if (millis > 0) {
				Thread.sleep(millis);
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static class MockDistributedCacheManager
			implements io.github.latcn.cache.core.distributed.DistributedCacheManager {

		private final ConcurrentHashMap<String, io.github.latcn.cache.core.model.CacheValue> cache = new ConcurrentHashMap<>();

		@Override
		public io.github.latcn.cache.core.model.CacheValue get(io.github.latcn.cache.core.model.CacheKey cacheKey) {
			return cache.get(cacheKey.getKey());
		}

		@Override
		public void put(io.github.latcn.cache.core.model.CacheKey cacheKey,
				io.github.latcn.cache.core.model.CacheValue cacheValue) {
			cache.put(cacheKey.getKey().toString(), cacheValue);
		}

		@Override
		public void remove(io.github.latcn.cache.core.model.CacheKey cacheKey) {
			cache.remove(cacheKey.getKey());
		}

		@Override
		public boolean containKey(io.github.latcn.cache.core.model.CacheKey cacheKey) {
			return cache.containsKey(cacheKey.getKey());
		}

		@Override
		public void clear() {
			cache.clear();
		}

		@Override
		public boolean isHealthy() {
			return true;
		}

		@Override
		public CompletableFuture<io.github.latcn.cache.core.model.CacheValue> getInBatch(
				io.github.latcn.cache.core.model.CacheKey key) {
			return CompletableFuture.completedFuture(get(key));
		}

		@Override
		public CompletableFuture<Boolean> putInBatch(io.github.latcn.cache.core.model.CacheKey key,
				io.github.latcn.cache.core.model.CacheValue cacheValue) {
			put(key, cacheValue);
			return CompletableFuture.completedFuture(true);
		}

		@Override
		public CompletableFuture<Boolean> removeInBatch(io.github.latcn.cache.core.model.CacheKey key) {
			remove(key);
			return CompletableFuture.completedFuture(true);
		}

	}

	private static class MockBroadcaster extends Broadcaster<String, BroadcasterListener> {

		public MockBroadcaster() {
			super(new MockBroadcastPublisher(), new MockBroadcastSubscriber(), List.of(), 100, 5);
		}

		@Override
		public void addKey(Object key) {
		}

		@Override
		public void publish() {
		}

		private static class MockBroadcastPublisher implements BroadcastPublisher {

			@Override
			public void broadcastMessage(java.util.Set<String> channelNames,
					io.github.latcn.cache.core.pubsub.BroadcastMessage message) {
			}

		}

		private static class MockBroadcastSubscriber implements BroadcastSubscriber<String, BroadcasterListener> {

			@Override
			public String broadcastSubscribe(String channel, BroadcasterListener listener) {
				return "mock-subscriber-id";
			}

			@Override
			public void removeSubscribe(String channel, String listenerId) {
			}

		}

	}

}