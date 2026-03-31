package com.consist.cache.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

public class TimerTask implements Callable {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimerTask.class);
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
        if (task == null || execTime<0 ) {
            throw new RuntimeException("timerTask param is not valid");
        }
        this.execTime = System.currentTimeMillis() + execTime;
        this.timeout = timeout;
        this.taskRunnable = task;
    }

    public TimerTask(long execTime, long timeout, Callable task) {
        if (task == null || execTime<0 ) {
            throw new RuntimeException("timerTask param is not valid");
        }
        this.execTime = System.currentTimeMillis() + execTime;
        this.timeout = timeout;
        this.taskCallable = task;
    }

    @Override
    public Object call() throws Exception {
        LOGGER.info("exec time: {}, actual execTime:{}", execTime, System.currentTimeMillis());
        if (timeout >0 && execTime + timeout < System.currentTimeMillis()) {
            return null;
        }
        try {
            if (taskCallable!=null) {
                return taskCallable.call();
            }else if (taskRunnable!= null) {
                taskRunnable.run();
            }
        } catch (Exception e) {
            LOGGER.error("exec task ex:", e);
        }
        return null;
    }

    public long getExecTime() {
        return execTime;
    }

    @Override
    public String toString() {
        return "TimerTask{" +
                "execTime=" + execTime +
                ", timeout=" + timeout +
                '}';
    }
}
