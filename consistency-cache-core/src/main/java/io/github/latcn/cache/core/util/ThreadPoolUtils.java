package io.github.latcn.cache.core.util;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadPoolUtils {

	public static ExecutorService getThreadPool(String threadPoolName, int cacheQueueSize) {
		return new ThreadPoolExecutor(1, Runtime.getRuntime().availableProcessors(), 30, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(cacheQueueSize), new ThreadFactory() {
					private static final AtomicInteger index = new AtomicInteger(0);

					@Override
					public Thread newThread(Runnable r) {
						Thread thread = new Thread(r);
						thread.setName(threadPoolName + ":" + index.incrementAndGet());
						return thread;
					}
				}, new ThreadPoolExecutor.CallerRunsPolicy());
	}

}
