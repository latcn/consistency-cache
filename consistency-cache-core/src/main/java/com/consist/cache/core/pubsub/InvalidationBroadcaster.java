package com.consist.cache.core.pubsub;

import com.consist.cache.core.util.MapUtil;
import com.consist.cache.core.util.TimeHolder;
import com.consist.cache.core.util.TimerTask;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class InvalidationBroadcaster<T,S extends BroadcasterListener> extends Broadcaster<T,S> {

    private final Set<String> channelNames;
    private final Set<Object> sendKeys = ConcurrentHashMap.newKeySet();


    public InvalidationBroadcaster(BroadcastPublisher publisher,
                                   BroadcastSubscriber<T,S> subscriber,
                                   List<BroadcasterListener> listeners,
                                   Set<String> channelNames,
                                   int batchSize, int maxWaitSeconds) {
        super(publisher, subscriber, listeners, batchSize, maxWaitSeconds);
        this.channelNames = channelNames;
    }

    /**
     * 生产失效的key, 异步消费
     * @param invalidationKey
     */
    public void addKey(Object invalidationKey) {
        if (invalidationKey==null) {
            return;
        }
        this.sendKeys.add(invalidationKey);
        // 缓冲大小
        if (this.sendKeys.size() >=this.batchSize) {
            this.publish();
        }
    }

    @Override
    public void publish() {
        if (this.sendKeys.isEmpty()) {
            return;
        }
        InvalidationMessage invalidationMessage = new InvalidationMessage();
        Set<Object> sendKeys = ConcurrentHashMap.newKeySet();
        // copy
        MapUtil.copy(this.sendKeys, sendKeys, true);
        invalidationMessage.setKeys(sendKeys);
        try {
            this.publisher.broadcastMessage(channelNames, invalidationMessage);
        } catch (Exception e) {
            // 重试延迟1秒执行
            TimeHolder.addTask(new TimerTask(1000, ()->broadcastWithRetry(invalidationMessage, 0)));
            log.error("publish message:{},ex", invalidationMessage, e);
        }
    }

    /**
     * 重试2次 1秒后 3秒后
     * @param invalidationMessage
     */
    public void broadcastWithRetry(InvalidationMessage invalidationMessage, int retryTimes) {
        retryTimes++;
        if (retryTimes>=3) {
            return;
        }
        try {
            this.publisher.broadcastMessage(channelNames, invalidationMessage);
        } catch (Exception e) {
            long actualDelay = 1000 * (1<<retryTimes);
            int finalRetryTimes = retryTimes;
            TimeHolder.addTask(new TimerTask(actualDelay, ()->broadcastWithRetry(invalidationMessage, finalRetryTimes)));
        }
    }
}
