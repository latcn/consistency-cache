package io.github.latcn.cache.core.pubsub;

import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import io.github.latcn.cache.core.monitor.CacheMetricsRecorder;
import io.github.latcn.cache.core.util.ThreadUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InvalidationBroadcaster<T, S extends BroadcasterListener> extends Broadcaster<T, S> {

	private static final int MAX_RETRY_TIMES = 3;

	private static final long INITIAL_RETRY_DELAY_MS = 1000;

	private static final int SHUTDOWN_AWAIT_SECONDS = 5;

	private final Set<String> channelNames;

	private final AtomicBoolean isSending = new AtomicBoolean(false);

	private final ConcurrentLinkedQueue<Object> sendKeys = new ConcurrentLinkedQueue<>();

	private final ScheduledExecutorService retryExecutor;

	private final CacheMetricsRecorder metricsRecorder;

	public InvalidationBroadcaster(BroadcastPublisher publisher, BroadcastSubscriber<T, S> subscriber,
			List<BroadcasterListener> listeners, Set<String> channelNames, int batchSize, int maxWaitSeconds) {
		this(publisher, subscriber, listeners, channelNames, batchSize, maxWaitSeconds, CacheMetricsRecorder.noOp());
	}

	public InvalidationBroadcaster(BroadcastPublisher publisher, BroadcastSubscriber<T, S> subscriber,
			List<BroadcasterListener> listeners, Set<String> channelNames, int batchSize, int maxWaitSeconds,
			CacheMetricsRecorder metricsRecorder) {
		super(publisher, subscriber, listeners, batchSize, maxWaitSeconds);
		this.channelNames = channelNames;
		this.metricsRecorder = metricsRecorder != null ? metricsRecorder : CacheMetricsRecorder.noOp();
		this.retryExecutor = ThreadUtils.getScheduledThreadPoolExecutor(1, "InvalidationBroadcaster-RetryExecutor");
	}

	@Override
	public void addKey(Object invalidationKey) {
		if (invalidationKey == null) {
			return;
		}
		sendKeys.add(invalidationKey);
		if (sendKeys.size() >= this.batchSize) {
			publish();
		}
	}

	@Override
	public void publish() {
		if (sendKeys.isEmpty() || !isSending.compareAndSet(false, true)) {
			return;
		}
		try {
			List<Object> keysToSend = new ArrayList<>();
			Object key = null;
			while ((key = this.sendKeys.poll()) != null && keysToSend.size() < this.batchSize) {
				keysToSend.add(key);
			}
			if (!keysToSend.isEmpty()) {
				InvalidationMessage message = new InvalidationMessage();
				message.setKeys(new HashSet<>(keysToSend));
				executeBroadcastWithRetry(message, 0);
			}
		}
		catch (Exception e) {
			throw CacheException.wrap(e, CacheError.BROADCAST_FAILED);
		}
		finally {
			isSending.compareAndSet(true, false);
		}
	}

	private void executeBroadcastWithRetry(InvalidationMessage message, int retryTimes) {
		try {
			this.publisher.broadcastMessage(channelNames, message);
			metricsRecorder.recordInvalidationPublish(true);
		}
		catch (Exception e) {
			if (retryTimes < MAX_RETRY_TIMES) {
				long actualDelay = INITIAL_RETRY_DELAY_MS * (1 << retryTimes);
				retryExecutor.schedule(() -> executeBroadcastWithRetry(message, retryTimes + 1), actualDelay,
						TimeUnit.MILLISECONDS);
			}
			else {
				log.error("Failed to broadcast after {} retries: {}", MAX_RETRY_TIMES, message, e);
				metricsRecorder.recordInvalidationPublish(false);
			}
		}
	}

	@Override
	public void preDestroy() {
		retryExecutor.shutdown();
		try {
			if (!retryExecutor.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
				retryExecutor.shutdownNow();
			}
		}
		catch (InterruptedException e) {
			retryExecutor.shutdownNow();
			Thread.currentThread().interrupt();
		}
		super.preDestroy();
	}

}
