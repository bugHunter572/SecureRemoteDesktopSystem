package com.srd.common.util;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Minimal size-rotating logger. {@link #init} redirects System.out/err into
 * {@code <dir>/<base>.log}, rolling to {@code <base>.1.log … <base>.N.log} when the file exceeds
 * {@code maxBytes}. Each line gets exactly one timestamp (the old TimestampPrintStream stamped
 * both print() and println(), producing doubled stamps, and never rotated — one client log had
 * grown past 1 GB).
 */
public final class Log {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static RotatingStream sink;
    private static PrintStream console = System.out;

    private Log() {
    }

    public static synchronized void init(File dir, String baseName, long maxBytes, int keep) {
        try {
            if (!dir.exists()) dir.mkdirs();
            sink = new RotatingStream(dir, baseName, maxBytes, keep);
            PrintStream ps = new PrintStream(new LineStampingStream(sink), true, "UTF-8");
            console = ps;
            System.setOut(ps);
            System.setErr(ps);
        } catch (IOException e) {
            System.err.println("Log init failed, using console: " + e);
        }
    }

    public static void info(String msg) {
        console.println("INFO  " + msg);
    }

    public static void warn(String msg) {
        console.println("WARN  " + msg);
    }

    public static void error(String msg, Throwable t) {
        console.println("ERROR " + msg + (t != null ? ": " + t : ""));
        if (t != null && !(t instanceof IOException)) t.printStackTrace(console);
    }

    public static void debug(String msg) {
        if (Boolean.getBoolean("srd.debug")) console.println("DEBUG " + msg);
    }

    /** Prefixes a timestamp at the start of every line (works for println, print and stack traces). */
    private static final class LineStampingStream extends OutputStream {
        private final OutputStream out;
        private boolean atLineStart = true;

        LineStampingStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            if (atLineStart) {
                out.write(("[" + TS.format(LocalDateTime.now()) + "] ").getBytes(StandardCharsets.UTF_8));
                atLineStart = false;
            }
            out.write(b);
            if (b == '\n') {
                atLineStart = true;
                out.flush();
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            for (int i = off; i < off + len; i++) write(b[i]);
        }

        @Override
        public synchronized void flush() throws IOException {
            out.flush();
        }
    }

    static final class RotatingStream extends OutputStream {
        private final File dir;
        private final String base;
        private final long maxBytes;
        private final int keep;
        private OutputStream out;
        private long written;

        RotatingStream(File dir, String base, long maxBytes, int keep) throws IOException {
            this.dir = dir;
            this.base = base;
            this.maxBytes = maxBytes;
            this.keep = keep;
            File f = current();
            if (f.length() > maxBytes) rotate();
            open();
        }

        private File current() {
            return new File(dir, base + ".log");
        }

        private void open() throws IOException {
            File f = current();
            written = f.length();
            out = new BufferedOutputStream(new FileOutputStream(f, true), 16 * 1024);
        }

        private void rotate() {
            new File(dir, base + "." + keep + ".log").delete();
            for (int i = keep - 1; i >= 1; i--) {
                File from = new File(dir, base + "." + i + ".log");
                if (from.exists()) from.renameTo(new File(dir, base + "." + (i + 1) + ".log"));
            }
            File cur = current();
            if (!cur.renameTo(new File(dir, base + ".1.log")) && cur.exists() && !cur.delete()) {
                // Could not move or delete it (locked by a scanner, read-only...): truncate in place so the
                // size check doesn't trigger a rotation on every single line afterwards.
                try (FileOutputStream t = new FileOutputStream(cur, false)) {
                    t.getChannel().truncate(0);
                } catch (IOException ignored) {
                }
            }
        }

        @Override
        public synchronized void write(int b) throws IOException {
            out.write(b);
            if (++written > maxBytes && b == '\n') {
                out.close();
                rotate();
                open();
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            out.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            out.close();
        }
    }
}
