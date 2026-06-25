package io.github.latcn.cache.core.pubsub;

import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import java.util.EventListener;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BroadcasterListener<T> implements EventListener {

	private static final int DEFAULT_CORE_POOL_SIZE = 4;

	private static final int DEFAULT_MAX_POOL_SIZE = 4;

	private static final int DEFAULT_KEEP_ALIVE_SECONDS = 30;

	private static final int DEFAULT_QUEUE_CAPACITY = 1024;

	protected final List<String> topics;

	private final ExecutorService businessExecutor;

	public BroadcasterListener(List<String> topics, ExecutorService businessExecutor) {
		if (topics == null || topics.isEmpty()) {
			throw CacheException.newInstance(CacheError.EMPTY_BROADCASTER_TOPIC);
		}
		this.topics = topics;
		if (businessExecutor == null) {
			this.businessExecutor = new ThreadPoolExecutor(DEFAULT_CORE_POOL_SIZE, DEFAULT_MAX_POOL_SIZE,
					DEFAULT_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, new LinkedBlockingQueue<>(DEFAULT_QUEUE_CAPACITY),
					new ThreadPoolExecutor.CallerRunsPolicy());
		}
		else {
			this.businessExecutor = businessExecutor;
		}
	}

	public void onMessage(CharSequence channel, T msg) {
		try {
			// 【广播逻辑】：每个实例都会执行到这里
			log.info("receive message:{}, {}, {}", channel, msg, msg.getClass().getCanonicalName());
			// 消息去重等检测 执行本地逻辑
			String topic = channel.toString();
			this.businessExecutor.execute(() -> doProcess(topic, msg));
		}
		catch (Exception e) {
			log.error("onMessage", e);
			throw CacheException.wrap(e, CacheError.SUBSCRIBE_FAILED);
		}
	}

	protected abstract void doProcess(String topic, T msg);

	public List<String> getTopics() {
		return this.topics;
	}

	public void shutdown() {
		this.businessExecutor.shutdown();
	}

}
