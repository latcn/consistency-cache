package com.consist.cache.core.util;

public class TimeHolder {

    private static final TimerWheel TIMER = new TimerWheel(600, 100,  Runtime.getRuntime().availableProcessors()*2);

    public static void addTask(TimerTask timerTask) {
        TIMER.addTask(timerTask);
    }

    public static void addTask(TimerWheel timer, TimerTask timerTask) {
        if (timer==null || timerTask==null) {
            return;
        }
        timer.addTask(timerTask);
    }

}
