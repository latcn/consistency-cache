package io.github.latcn.cache.core.util;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ThreadUtils {

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

	public static ScheduledThreadPoolExecutor getScheduledThreadPoolExecutor(int corePoolSize, String scheduledName) {
		return new ScheduledThreadPoolExecutor(corePoolSize, r -> {
			Thread thread = new Thread(r);
			thread.setDaemon(true);
			thread.setName(scheduledName);
			thread.setUncaughtExceptionHandler((t, ex) -> log.error("{} ScheduledThread died", scheduledName, ex));
			return thread;
		});
	}

}
