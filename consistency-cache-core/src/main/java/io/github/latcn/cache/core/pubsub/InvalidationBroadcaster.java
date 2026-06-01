package io.github.latcn.cache.core.pubsub;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class InvalidationBroadcaster<T, S extends BroadcasterListener> extends Broadcaster<T, S> {

    private static final int MAX_RETRY_TIMES = 3;
    private static final long INITIAL_RETRY_DELAY_MS = 1000;

    private final Set<String> channelNames;
    private final AtomicBoolean isSending = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<Object> sendKeys = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService retryExecutor = Executors.newScheduledThreadPool(1, r -> {
        Thread thread = new Thread(r);
        thread.setDaemon(true);
        thread.setName("InvalidationBroadcaster-RetryExecutor-Thread");
        return thread;
    });

    public InvalidationBroadcaster(BroadcastPublisher publisher,
                                   BroadcastSubscriber<T, S> subscriber,
                                   List<BroadcasterListener> listeners,
                                   Set<String> channelNames,
                                   int batchSize, int maxWaitSeconds) {
        super(publisher, subscriber, listeners, batchSize, maxWaitSeconds);
        this.channelNames = channelNames;
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
        if (sendKeys.isEmpty()) {
            return;
        }

        List<Object> keysToSend = new ArrayList<>();
        if (isSending.compareAndSet(false, true)) {
            keysToSend.addAll(sendKeys);
            this.sendKeys.removeAll(keysToSend);
            isSending.compareAndSet(true, false);
        } else {
            return;
        }
        if (!keysToSend.isEmpty()) {
            InvalidationMessage message = new InvalidationMessage();
            message.setKeys(new HashSet<>(keysToSend));
            executeBroadcastWithRetry(message, 0);
        }
    }

    private void executeBroadcastWithRetry(InvalidationMessage message, int retryTimes) {
        try {
            this.publisher.broadcastMessage(channelNames, message);
        } catch (Exception e) {
            if (retryTimes < MAX_RETRY_TIMES) {
                long actualDelay = INITIAL_RETRY_DELAY_MS * (1 << retryTimes);
                retryExecutor.schedule(
                        () -> executeBroadcastWithRetry(message, retryTimes + 1),
                        actualDelay,
                        TimeUnit.MILLISECONDS
                );
            } else {
                log.error("Failed to broadcast after {} retries: {}", MAX_RETRY_TIMES, message, e);
            }
        }
    }

    @Override
    public void preDestroy() {
        retryExecutor.shutdown();
        try {
            if (!retryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                retryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            retryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        super.preDestroy();
    }
}