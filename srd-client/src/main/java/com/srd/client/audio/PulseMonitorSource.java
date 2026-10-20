package com.srd.client.audio;

import com.srd.common.util.Log;
import com.srd.common.util.Threads;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Linux system-output capture. It records the default sink's monitor with a PulseAudio or
 * PipeWire command-line recorder running as a subprocess and reads raw 48 kHz stereo s16le from
 * its stdout.
 *
 * <p>Commands are tried in this order (the first one that delivers audio is kept):
 * <ol>
 *   <li>{@code parec --device=@DEFAULT_MONITOR@ ...}</li>
 *   <li>{@code parec --device=<pactl get-default-sink>.monitor ...}</li>
 *   <li>{@code pw-record -P {stream.capture.sink=true} ... -} (PipeWire without pipewire-pulse)</li>
 * </ol>
 * If the recorder exits, it is restarted with backoff. When {@code pactl} is available, the
 * default sink is polled every 3 s and the recorder is restarted when it changes, because
 * {@code @DEFAULT_MONITOR@} is resolved only once, at start.
 */
public final class PulseMonitorSource implements AudioSource {
    private static final long[] BACKOFF_MS = {250, 500, 1000, 2000};
    private static final long SINK_CHECK_MS = 3000;

    private volatile boolean running;
    private volatile Thread thread;
    private volatile Process process;
    private volatile String status = "PulseAudio monitor (starting)";
    private PcmFifo out;

    /** True when parec or pw-record is on PATH. */
    public static boolean isAvailable() {
        return onPath("parec") || onPath("pw-record");
    }

    @Override
    public synchronized void start(PcmFifo fifo) {
        if (running) return;
        out = fifo;
        running = true;
        thread = Threads.start("audio-pulse-monitor", Thread.MAX_PRIORITY, this::loop);
    }

    @Override
    public void stop() {
        Thread t;
        synchronized (this) {
            running = false;
            t = thread;
            thread = null;
        }
        kill(process);
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public String name() {
        return "PulseAudio/PipeWire monitor";
    }

    @Override
    public Kind kind() {
        return Kind.SYSTEM;
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public int primeMs() {
        return 60;
    }

    // ------------------------------------------------------------------ recorder loop

    private void loop() {
        int failures = 0;
        int candidate = 0;
        String lastError = null;
        while (running) {
            List<List<String>> cmds = commands();
            if (cmds.isEmpty()) {
                status = name() + " (no parec/pw-record)";
                Log.warn("audio: no parec/pw-record on PATH; Linux system audio unavailable");
                return;
            }
            List<String> cmd = cmds.get(candidate % cmds.size());
            long startedAt = System.nanoTime();
            String result = runOnce(cmd);
            if (!running) break;
            boolean ranLong = System.nanoTime() - startedAt > 5_000_000_000L;
            if (result == null) {           // default sink changed: restart right away
                failures = 0;
                continue;
            }
            if (ranLong) {
                failures = 0;
            } else {
                candidate++;                // died quickly: try the next command variant
            }
            String msg = String.join(" ", cmd) + ": " + result;
            if (!msg.equals(lastError)) Log.warn("audio: " + msg + "; restarting");
            lastError = msg;
            status = name() + " (recovering: " + result + ")";
            try {
                Thread.sleep(BACKOFF_MS[Math.min(failures++, BACKOFF_MS.length - 1)]);
            } catch (InterruptedException e) {
                break;
            }
        }
        status = name() + " (stopped)";
    }

    /** Runs one recorder process. Returns null if restarted for a sink change, else the reason it ended. */
    private String runOnce(List<String> cmd) {
        Process p;
        try {
            p = new ProcessBuilder(cmd).redirectInput(ProcessBuilder.Redirect.PIPE).start();
        } catch (Throwable t) {
            return "cannot start: " + t;
        }
        process = p;
        if (!running) {
            kill(p);
            return null;
        }
        final String[] lastStderr = {""};
        Threads.start("audio-pulse-stderr", () -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) lastStderr[0] = l;
            } catch (Throwable ignored) {
            }
        });
        String sink = defaultSink();
        boolean pwRecord = cmd.get(0).endsWith("pw-record");
        status = name() + " via " + new File(cmd.get(0)).getName() + (sink != null ? " (" + sink + ")" : "");
        Log.info("audio: system audio via " + String.join(" ", cmd));
        long nextSinkCheck = System.currentTimeMillis() + SINK_CHECK_MS;
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[3840];
            boolean headerChecked = !pwRecord;
            while (running) {
                int n = in.read(buf);
                if (n < 0) break;
                if (!headerChecked) {
                    headerChecked = true;
                    // Some pw-record versions write a WAV header even to stdout; skip it.
                    if (n >= 44 && buf[0] == 'R' && buf[1] == 'I' && buf[2] == 'F' && buf[3] == 'F') {
                        out.writeLE(buf, 44, n - 44);
                        continue;
                    }
                }
                out.writeLE(buf, 0, n);
                long now = System.currentTimeMillis();
                if (sink != null && now >= nextSinkCheck) {
                    nextSinkCheck = now + SINK_CHECK_MS;
                    String cur = defaultSink();
                    if (cur != null && !cur.equals(sink)) {
                        Log.info("audio: default sink changed (" + sink + " -> " + cur + "), restarting monitor");
                        return null;
                    }
                }
            }
        } catch (Throwable t) {
            if (running) return "read failed: " + t;
        } finally {
            kill(p);
            process = null;
        }
        if (!running) return null;
        int code = -1;
        try {
            if (p.waitFor(200, TimeUnit.MILLISECONDS)) code = p.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "exited (code " + code + (lastStderr[0].isEmpty() ? "" : ", " + lastStderr[0]) + ")";
    }

    private List<List<String>> commands() {
        List<List<String>> list = new ArrayList<>();
        String parec = which("parec");
        if (parec != null) {
            list.add(Arrays.asList(parec, "--device=@DEFAULT_MONITOR@", "--format=s16le", "--rate=48000",
                    "--channels=2", "--latency-msec=20", "--raw"));
            String sink = defaultSink();
            if (sink != null) {
                list.add(Arrays.asList(parec, "--device=" + sink + ".monitor", "--format=s16le", "--rate=48000",
                        "--channels=2", "--latency-msec=20", "--raw"));
            }
        }
        String pw = which("pw-record");
        if (pw != null) {
            list.add(Arrays.asList(pw, "-P", "{ stream.capture.sink=true }", "--format=s16", "--rate=48000",
                    "--channels=2", "-"));
        }
        return list;
    }

    /** {@code pactl get-default-sink}, or null if pactl is missing/fails. */
    static String defaultSink() {
        String pactl = which("pactl");
        if (pactl == null) return null;
        try {
            Process p = new ProcessBuilder(pactl, "get-default-sink").redirectErrorStream(true).start();
            String s;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                s = r.readLine();
            }
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0 || s == null || s.trim().isEmpty()) return null;
            return s.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean onPath(String bin) {
        return which(bin) != null;
    }

    static String which(String bin) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            File f = new File(dir, bin);
            if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    private static void kill(Process p) {
        if (p == null) return;
        try {
            p.destroy();
            if (!p.waitFor(300, TimeUnit.MILLISECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
        }
    }
}
