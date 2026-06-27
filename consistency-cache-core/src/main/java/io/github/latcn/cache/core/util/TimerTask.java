package io.github.latcn.cache.core.util;

import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TimerTask implements Callable {

	private final long execTime;

	private final long timeout;

	private Callable taskCallable;

	private Runnable taskRunnable;

	public TimerTask(long execTime, Callable task) {
		this(execTime, -1, task);
	}

	public TimerTask(long execTime, Runnable task) {
		this(execTime, -1, task);
	}

	public TimerTask(long execTime, long timeout, Runnable task) {
		if (task == null || execTime < 0) {
			throw new CacheException(CacheError.INVALID_PARAMETER,
					"timerTask param is not valid: task=" + task + ", execTime=" + execTime);
		}
		this.execTime = System.currentTimeMillis() + execTime;
		this.timeout = timeout;
		this.taskRunnable = task;
	}

	public TimerTask(long execTime, long timeout, Callable task) {
		if (task == null || execTime < 0) {
			throw new CacheException(CacheError.INVALID_PARAMETER,
					"timerTask param is not valid: task=" + task + ", execTime=" + execTime);
		}
		this.execTime = execTime;
		this.timeout = timeout;
		this.taskCallable = task;
	}

	@Override
	public Object call() throws Exception {
		log.debug("exec time: {}, actual execTime:{}", execTime, System.currentTimeMillis());
		if (timeout > 0 && execTime + timeout < System.currentTimeMillis()) {
			return null;
		}
		try {
			if (taskCallable != null) {
				return taskCallable.call();
			}
			else if (taskRunnable != null) {
				taskRunnable.run();
			}
		}
		catch (Exception e) {
			log.error("exec task ex:", e);
		}
		return null;
	}

	public long getExecTime() {
		return execTime;
	}

	@Override
	public String toString() {
		return "TimerTask{" + "execTime=" + execTime + ", timeout=" + timeout + '}';
	}

}
