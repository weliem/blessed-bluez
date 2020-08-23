package com.welie.blessed.internal;


import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

class MessageQueue {

    private static final Object BLOCK = new Object();
    private final BlockingQueue<Message> messageBlockingQueue = new LinkedBlockingQueue<>();
    private final Map<Runnable, List<Thread>> runnableListMap = new ConcurrentHashMap<>();

    boolean enqueueMessage(Message msgToQueue) {
        cleanUpThreads();

        Thread lcThread = new Thread(() -> {
            long sleepTime = msgToQueue.getWhenToRun() - System.currentTimeMillis();
            try {
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }

                messageBlockingQueue.put(msgToQueue);


            } catch (InterruptedException ignored) {

            }
        }, this.getClass().getSimpleName() + "-" + "enqueueMessage");

        synchronized (BLOCK) {
            if (!runnableListMap.containsKey(msgToQueue.getRunnable())) {
                runnableListMap.put(msgToQueue.getRunnable(), new ArrayList<>());
            }
            runnableListMap.get(msgToQueue.getRunnable()).add(lcThread);
        }
        lcThread.start();

        return true;
    }

    Message getNext() throws InterruptedException {
        return messageBlockingQueue.take();
    }

    private void cleanUpThreads() {
        synchronized (BLOCK) {
            Map<Runnable, List<Thread>> lcToRemove = new HashMap<>();
            runnableListMap.keySet().stream().forEach(runnable -> {
                runnableListMap.get(runnable).forEach(thread -> {
                    if (thread.getState() == Thread.State.TERMINATED) {
                        lcToRemove.putIfAbsent(runnable, new ArrayList<>());
                        lcToRemove.get(runnable).add(thread);
                    }
                });
            });
            lcToRemove.keySet().stream().forEach(runnable -> {
                lcToRemove.get(runnable).forEach(thread -> {
                    runnableListMap.getOrDefault(runnable, new ArrayList<>()).remove(thread);
                });
            });
            List<Runnable> toDelete = new ArrayList<>();
            runnableListMap.keySet().stream().forEach(runnable -> {
                if (runnableListMap.get(runnable).size() == 0) {
                    toDelete.add(runnable);
                }
            });
            toDelete.forEach(runnable -> runnableListMap.remove(runnable));
        }
    }

    void removeMessages(Runnable r) {
        if (r == null) {
            return;
        }

        synchronized (BLOCK) {
            List<Thread> threads = runnableListMap.get(r);
            if (threads != null) {
                for (Thread thread : threads) {
                    thread.interrupt();
                }
                runnableListMap.remove(r);
            }
        }
    }

}
