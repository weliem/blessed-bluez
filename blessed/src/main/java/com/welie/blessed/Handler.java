package com.welie.blessed;

import org.jetbrains.annotations.NotNull;
import java.util.Objects;
import java.util.concurrent.*;

public class Handler {

    private final ScheduledThreadPoolExecutor executor;

    public Handler(@NotNull String name) {
        Objects.requireNonNull(name, "name is null");

        executor = new ScheduledThreadPoolExecutor(1);
        executor.setRemoveOnCancelPolicy(true);
        executor.execute(() -> Thread.currentThread().setName(name));
    }

    public final void post(@NotNull final Runnable runnable) {
        executor.execute(runnable);
    }

    public final ScheduledFuture<?> postDelayed(@NotNull final Runnable runnable, long delayMillis) {
        return executor.schedule(runnable, delayMillis, TimeUnit.MILLISECONDS);
    }

    public final void shutdown() {
        executor.shutdown();
    }
}
