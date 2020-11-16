package com.welie.blessed.internal;

import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class PoolHandler {

    private final Executor executor ;
    private final Map<Runnable, Timer> delayedRunnables = new ConcurrentHashMap<>();

    public PoolHandler(String name) {
        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> Thread.currentThread().setName(name));
    }

    public final void post(Runnable runnable) {
        executor.execute(runnable);
    }

    public final void postDelayed(Runnable runnable, long delayMillis) {
       Timer timer = new Timer();
       delayedRunnables.put(runnable, timer);
       timer.schedule(new TimerTask() {
           @Override
           public void run() {
               delayedRunnables.remove(runnable);
               post(runnable);
           }
       }, delayMillis);
    }

    public final void removeCallbacks(Runnable runnable) {
        Timer timer = delayedRunnables.get(runnable);
        if (timer != null) {
            timer.cancel();
            delayedRunnables.remove(runnable);
        }
    }
}
