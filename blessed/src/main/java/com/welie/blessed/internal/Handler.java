package com.welie.blessed.internal;

public class Handler {

    private final MessageQueue messageQueue = new MessageQueue();
    private final Looper looper;

    public Handler(String name) {
        looper = new Looper(messageQueue,name);
        looper.run();
    }

    public void stop() {
        if (looper != null) {
            looper.stop();
        }
    }

    @SuppressWarnings("UnusedReturnValue")
    public final boolean post(Runnable r) {
        return this.postDelayed(r, 0);
    }

    public final boolean postDelayed(Runnable r, long delayMillis) {
        if (delayMillis < 0) {
            delayMillis = 0;
        }
        return sendRunnableAtTime(r, System.currentTimeMillis() + delayMillis);
    }

    private boolean sendRunnableAtTime(Runnable r, long targetTime) {
        return messageQueue.enqueueMessage(new Message(r, targetTime));
    }

    public final void removeCallbacks(Runnable r) {
        messageQueue.removeMessages(r);
    }
}
