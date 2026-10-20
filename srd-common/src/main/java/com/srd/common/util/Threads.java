package com.srd.common.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Named daemon threads, so thread dumps and logs say what each thread is. */
public final class Threads {
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(2, factory("srd-scheduler", Thread.NORM_PRIORITY));

    private Threads() {
    }

    public static ThreadFactory factory(String prefix, int priority) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(priority);
            t.setUncaughtExceptionHandler((th, e) -> Log.error("Uncaught in " + th.getName(), e));
            return t;
        };
    }

    public static Thread start(String name, Runnable r) {
        return start(name, Thread.NORM_PRIORITY, r);
    }

    public static Thread start(String name, int priority, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(priority);
        t.setUncaughtExceptionHandler((th, e) -> Log.error("Uncaught in " + th.getName(), e));
        t.start();
        return t;
    }

    public static ExecutorService pool(String prefix, int size) {
        return Executors.newFixedThreadPool(size, factory(prefix, Thread.NORM_PRIORITY));
    }

    /** Shared low-traffic scheduler for timers (pings, watchdogs, status polls). */
    public static ScheduledExecutorService scheduler() {
        return SCHEDULER;
    }

    public static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
