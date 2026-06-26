package io.github.latcn.cache.core.util;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadPoolUtils {

	private static final int DEFAULT_CORE_POOL_SIZE = 1;

	private static final int DEFAULT_KEEP_ALIVE_SECONDS = 30;

	public static ExecutorService getThreadPool(String threadPoolName, boolean isDaemon, int cacheQueueSize) {
		return new ThreadPoolExecutor(DEFAULT_CORE_POOL_SIZE, Runtime.getRuntime().availableProcessors(),
				DEFAULT_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, new LinkedBlockingQueue<>(cacheQueueSize),
				new ThreadFactory() {
					private static final AtomicInteger index = new AtomicInteger(0);

					@Override
					public Thread newThread(Runnable r) {
						Thread thread = new Thread(r);
						thread.setName(threadPoolName + ":" + index.incrementAndGet());
						thread.setDaemon(isDaemon);
						return thread;
					}
				}, new ThreadPoolExecutor.CallerRunsPolicy());
	}

}
