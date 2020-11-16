package com.welie.blessed.internal;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class Handler {

    public static final String RUNNABLE_IS_NULL = "runnable is null";
    private final Executor executor ;
    private final Map<Runnable, Timer> delayedRunnables = new ConcurrentHashMap<>();

    public Handler(@NotNull String name) {
        Objects.requireNonNull(name, "name is null");

        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> Thread.currentThread().setName(name));
    }

    public final void post(@NotNull final Runnable runnable) {
        Objects.requireNonNull(runnable, RUNNABLE_IS_NULL);

        executor.execute(runnable);
    }

    public final void postDelayed(@NotNull final Runnable runnable, long delayMillis) {
        Objects.requireNonNull(runnable, RUNNABLE_IS_NULL);

       final Timer timer = new Timer();
       delayedRunnables.put(runnable, timer);
       timer.schedule(new TimerTask() {
           @Override
           public void run() {
               delayedRunnables.remove(runnable);
               post(runnable);
           }
       }, delayMillis);
    }

    public final void removeCallbacks(@NotNull final Runnable runnable) {
        Objects.requireNonNull(runnable, RUNNABLE_IS_NULL);

        final Timer timer = delayedRunnables.get(runnable);
        if (timer != null) {
            timer.cancel();
            delayedRunnables.remove(runnable);
        }
    }
}
