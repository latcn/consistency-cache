package com.consist.cache.spring.pubsub;

import com.consist.cache.core.executor.CacheExecutor;
import com.consist.cache.core.model.CacheKey;
import com.consist.cache.core.pubsub.BroadcasterListener;
import com.consist.cache.core.pubsub.InvalidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.listener.MessageListener;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class InvalidationListener extends BroadcasterListener<InvalidationMessage> implements MessageListener<InvalidationMessage> {

    private final ConcurrentHashMap<String,Long> pendingKeys = new ConcurrentHashMap<>();
    private final String selfNodeId;
    private final CacheExecutor cacheExecutor;
    private static final long DUP_WINDOWS_MS = 5000;

    public InvalidationListener(String selfNodeId, List<String> topics, CacheExecutor cacheExecutor) {
        super(topics, null);
        this.selfNodeId = selfNodeId;
        this.cacheExecutor = cacheExecutor;
    }

    @Override
    public void doProcess(String topic, InvalidationMessage msg) {
        try {
            log.info("do process {},{}", topic, msg);
            if (checkIfDuplicate(msg.getMessageId())) {
                return;
            }
            // check if self node listener
            if (Objects.equals(this.selfNodeId, msg.getNodeId())) {
                return;
            }
            // delete localCache
            for(Object key: msg.getKeys()) {
                log.info("{}", key);
                if (key instanceof CacheKey) {
                    this.cacheExecutor.evict((CacheKey) key);
                } else {
                    //this.localCacheManager.removeByActualKey(key);
                }
            }
        } catch (Exception e) {
            log.error("doProcess ex", e);
        } finally {
            cleanDuplicateKeys(msg.getMessageId());
        }
    }

    /**
     * checkIfDuplicate
     * @param messageId
     * @return
     */
    private boolean checkIfDuplicate(String messageId) {
        long currentTime = System.currentTimeMillis();
        Long oldTime = this.pendingKeys.putIfAbsent(messageId, currentTime);
        // check if duplicate
        if (oldTime!=null) {
            if(oldTime - System.currentTimeMillis() < DUP_WINDOWS_MS) {
                return true;
            } else {
                this.pendingKeys.remove(messageId);
                this.pendingKeys.putIfAbsent(messageId, currentTime);
            }
        }
        return false;
    }

    private void cleanDuplicateKeys(String messageId) {
        try {
            this.pendingKeys.remove(messageId);
        } catch (Exception e) {
            log.error("cleanDuplicateKeys ex", e);
        }
    }

}
