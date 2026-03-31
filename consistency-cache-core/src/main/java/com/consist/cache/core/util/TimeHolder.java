package com.consist.cache.core.util;

public class TimeHolder {

    private static final TimerWheel TIMER = new TimerWheel(100, 10);

    public static void addTask(TimerTask timerTask) {
        TIMER.addTask(timerTask);
    }

}
