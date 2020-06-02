package com.welie.blessed;

public class Looper {

    private final String name;
    private final Thread thread;
    private MessageQueue messageQueue;

    Looper(MessageQueue messageQueue, String name) {
        this.messageQueue = messageQueue;
        this.name = name;
        thread = new Thread(() -> {
            try {
                this.runInsideThread();
            } catch (InterruptedException exception) {
 //               LOGGER.info("LOOPER: Destroy thread" + Thread.currentThread().getName());
            }
        }, this.getClass().getSimpleName() + "-run-" + name);
    }

    public void run() {
        thread.start();
    }

    public void stop() {
        thread.interrupt();
    }

    private void runInsideThread() throws InterruptedException {
        for (; ; ) {
            Message msg = messageQueue.getNext();
            try {
                msg.getRunnable().run();
            } catch (Exception err) {
                err.printStackTrace();
            }
        }
    }
}
