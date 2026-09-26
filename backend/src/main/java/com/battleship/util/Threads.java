package com.battleship.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Thread factories that produce readable, prefixed thread names. */
public final class Threads {

    private Threads() {}

    public static ThreadFactory named(String prefix, boolean daemon) {
        AtomicInteger seq = new AtomicInteger(1);
        return runnable -> {
            Thread t = new Thread(runnable, prefix + "-" + seq.getAndIncrement());
            t.setDaemon(daemon);
            return t;
        };
    }
}
