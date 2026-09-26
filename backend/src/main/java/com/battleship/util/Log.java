package com.battleship.util;

import java.time.Instant;

/**
 * Minimal structured logger. Writes one line per event with the emitting
 * thread name, which is what makes the concurrency in this server readable
 * when several games run in parallel.
 */
public final class Log {

    private final String tag;

    private Log(String tag) { this.tag = tag; }

    public static Log of(Class<?> type) { return new Log(type.getSimpleName()); }

    public void info(String msg)  { write("INFO", msg, null); }
    public void warn(String msg)  { write("WARN", msg, null); }
    public void error(String msg) { write("ERROR", msg, null); }

    public void error(String msg, Throwable t) { write("ERROR", msg, t); }
    public void warn(String msg, Throwable t)  { write("WARN", msg, t); }

    private void write(String level, String msg, Throwable t) {
        String line = String.format("%s %-5s [%s] %s - %s",
                Instant.now(), level, Thread.currentThread().getName(), tag, msg);
        if ("ERROR".equals(level)) {
            System.err.println(line);
            if (t != null) t.printStackTrace(System.err);
        } else {
            System.out.println(line);
            if (t != null) t.printStackTrace(System.out);
        }
    }
}
