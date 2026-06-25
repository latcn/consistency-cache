package io.github.latcn.cache.spring.pubsub;

import io.github.latcn.cache.core.executor.CacheExecutor;
import io.github.latcn.cache.core.model.CacheKey;
import io.github.latcn.cache.core.pubsub.BroadcasterListener;
import io.github.latcn.cache.core.pubsub.InvalidationMessage;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.listener.MessageListener;

@Slf4j
public class InvalidationListener extends BroadcasterListener<InvalidationMessage>
		implements MessageListener<InvalidationMessage> {

	private final ConcurrentHashMap<String, Long> pendingKeys = new ConcurrentHashMap<>();

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
			for (Object key : msg.getKeys()) {
				log.info("{}", key);
				if (key instanceof CacheKey) {
					this.cacheExecutor.evict((CacheKey) key);
				}
				else {
					// this.localCacheManager.removeByActualKey(key);
				}
			}
		}
		catch (Exception e) {
			log.error("doProcess ex", e);
		}
		finally {
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
		if (oldTime != null) {
			if (System.currentTimeMillis() - oldTime < DUP_WINDOWS_MS) {
				return true;
			}
			else {
				this.pendingKeys.computeIfPresent(messageId, (k, v)->currentTime);
			}
		}
		return false;
	}

	private void cleanDuplicateKeys(String messageId) {
		try {
			this.pendingKeys.remove(messageId);
		}
		catch (Exception e) {
			log.error("cleanDuplicateKeys ex", e);
		}
	}

}
