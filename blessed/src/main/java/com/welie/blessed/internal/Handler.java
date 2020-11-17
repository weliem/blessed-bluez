package com.welie.blessed.internal;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

public class Handler {

    @NotNull
    private final ScheduledExecutorService executor;

    @NotNull
    private final Map<Runnable, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public static final String RUNNABLE_IS_NULL = "runnable is null";

    public Handler(@NotNull String name) {
        Objects.requireNonNull(name, "name is null");

        executor = Executors.newSingleThreadScheduledExecutor();
        executor.execute(() -> Thread.currentThread().setName(name));
    }

    public final void post(@NotNull final Runnable runnable) {
        Objects.requireNonNull(runnable, RUNNABLE_IS_NULL);

        executor.execute(runnable);
    }

    public final void postDelayed(@NotNull final Runnable runnable, long delayMillis) {
        Objects.requireNonNull(runnable, RUNNABLE_IS_NULL);

        ScheduledFuture<?> scheduledFuture = executor.schedule(runnable, delayMillis, TimeUnit.MILLISECONDS);
        futures.put(runnable,scheduledFuture);
    }

    public final void removeCallbacks(@NotNull final Runnable runnable) {
        Objects.requireNonNull(runnable, RUNNABLE_IS_NULL);

        ScheduledFuture<?> scheduledFuture = futures.get(runnable);
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            futures.remove(runnable);
        }
    }
}
