package io.github.latcn.cache.core.util;

import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TimerWheel {

	private static final int THREAD_POOL_DIVISOR = 3;

	private static final int KEEP_ALIVE_SECONDS = 60;

	private static final int QUEUE_CAPACITY = 1024;

	private static final long NO_WAIT_MILLISECONDS = 0;

	private final Object[] wheels;

	private final int interval;

	private final int num;

	private final long createTime;

	private volatile long currentTime;

	private final AtomicReference<TimerWheel> overflowTimer = new AtomicReference<>();

	private final AtomicBoolean isStop = new AtomicBoolean(false);

	private final AtomicInteger threadIdx = new AtomicInteger(0);

	private int ticksTimes;

	private Thread tickThread;

	private ExecutorService executor;

	private TimerWheel lowerWheel;

	private final int maxThreadCount;

	public TimerWheel(int num, int interval, int maxThreadCount) {
		this(num, interval, maxThreadCount, null);
	}

	private TimerWheel(int num, int interval, int maxThreadCount, TimerWheel lowerWheel) {
		if (num <= 0 || interval <= 0) {
			throw new CacheException(CacheError.INVALID_PARAMETER,
					"TimerWheel: num and interval must be positive, got num=" + num + ", interval=" + interval);
		}
		long now = TimeUtil.currentNanoToMil();
		this.num = num;
		this.interval = interval;
		this.maxThreadCount = maxThreadCount;
		this.wheels = new Object[num];
		this.createTime = now - now % interval;
		this.currentTime = createTime;
		this.lowerWheel = lowerWheel;
		init();
	}

	private void init() {
		for (int i = 0; i < num; i++) {
			this.wheels[i] = new LinkedBlockingQueue<TimerTask>();
		}
		if (lowerWheel != null) {
			return;
		}
		this.tickThread = new Thread(() -> {
			while (!isStop.get()) {
				long deadline = currentTime + interval;
				long now = TimeUtil.currentNanoToMil();
				while (deadline > now) {
					long remaining = deadline - now;
					LockSupport.parkNanos(remaining * TimeUtil.NANO_TO_MIL);
					now = TimeUtil.currentNanoToMil();
				}
				if (isStop.get()) {
					break;
				}
				ticksTimes++;
				processTask(TimerWheel.this);
				if (ticksTimes == num) {
					ticksTimes = 0;
					processOverFlowTimerWheel(overflowTimer.get());
				}
			}
		}, "TimerWheel-Timer-TickThread");
		int corePoolSize = maxThreadCount > 2 ? maxThreadCount / THREAD_POOL_DIVISOR : maxThreadCount;
		this.executor = new ThreadPoolExecutor(corePoolSize, maxThreadCount, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(QUEUE_CAPACITY), r -> {
					Thread thread = new Thread(r);
					thread.setName("TimerWheel-Timer-Executor-" + threadIdx.incrementAndGet());
					thread.setDaemon(true);
					return thread;
				}, new ThreadPoolExecutor.CallerRunsPolicy());
		this.tickThread.setDaemon(true);
		this.tickThread.start();
	}

	public void shutDown() {
		if (isStop.compareAndSet(false, true)) {
			this.executor.shutdown();
		}
	}

	public void processOverFlowTimerWheel(TimerWheel overFlowTimerWheel) {
		if (overFlowTimerWheel == null) {
			return;
		}
		overFlowTimerWheel.ticksTimes++;
		if (overFlowTimerWheel.ticksTimes == overFlowTimerWheel.num) {
			processOverFlowTimerWheel(overFlowTimerWheel.overflowTimer.get());
		}
		overFlowTimerWheel.currentTime += overFlowTimerWheel.interval;
		int wheelIndex = (int) (overFlowTimerWheel.currentTime - overFlowTimerWheel.createTime)
				/ overFlowTimerWheel.interval;
		LinkedBlockingQueue<TimerTask> tasks = (LinkedBlockingQueue<TimerTask>) overFlowTimerWheel.wheels[wheelIndex
				% num];
		TimerTask timerTask = null;
		try {
			while ((timerTask = tasks.poll(NO_WAIT_MILLISECONDS, TimeUnit.MILLISECONDS)) != null) {
				overFlowTimerWheel.lowerWheel.addTask(timerTask);
			}
		}
		catch (InterruptedException e) {
			log.error("processOverFlowTimerWheel ex", e);
		}
	}

	public void processTask(TimerWheel timerWheel) {
		long nextCurrentTime = timerWheel.currentTime + interval;
		int nextIndex = (int) ((nextCurrentTime - createTime) / interval) % num;
		timerWheel.currentTime = nextCurrentTime;
		LinkedBlockingQueue<TimerTask> tasks = (LinkedBlockingQueue<TimerTask>) this.wheels[nextIndex];
		TimerTask timerTask = null;
		try {
			while ((timerTask = tasks.poll(NO_WAIT_MILLISECONDS, TimeUnit.MILLISECONDS)) != null) {
				timerWheel.executor.submit(timerTask);
			}
		}
		catch (InterruptedException e) {
			log.error("processOverFlowTimerWheel ex", e);
		}
	}

	public void addTask(TimerTask timerTask) {
		if (timerTask == null) {
			throw new CacheException(CacheError.INVALID_PARAMETER, "timerTask can't be null");
		}
		long calCurrentTime = currentTime;
		long execTime = timerTask.getExecTime() - timerTask.getExecTime() % interval;
		if (execTime <= calCurrentTime) {
			// exec now
			executor.submit(timerTask);
			return;
		}
		int wheelsIndex = (int) (execTime - calCurrentTime) / interval;
		int currentIndex = (int) ((calCurrentTime - createTime) / interval) % num;
		if (wheelsIndex < num) {
			wheelsIndex = (wheelsIndex + currentIndex) % num;
			((LinkedBlockingQueue<TimerTask>) this.wheels[wheelsIndex]).add(timerTask);
		}
		else {
			overflowTimer.compareAndSet(null, new TimerWheel(num, interval * num, 0, this));
			TimerWheel overFlowTimer = overflowTimer.get();
			overFlowTimer.addTask(timerTask);
		}
	}

}
