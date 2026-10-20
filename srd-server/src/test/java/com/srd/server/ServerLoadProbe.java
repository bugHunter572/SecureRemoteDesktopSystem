package com.srd.server;

import com.srd.common.crypto.SessionCrypto;
import com.srd.common.protocol.Hello;
import com.srd.common.protocol.MessageType;
import com.srd.common.protocol.MuxConnection;
import com.srd.common.status.StatusMessage;
import com.srd.server.db.ConfigRepository;
import com.srd.server.config.ServerConfig;
import com.srd.server.db.ClientRow;
import com.srd.server.db.Database;
import com.srd.server.ui.ServerWindow;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/**
 * Manual load probe (not a unit test): starts the real server window in this JVM and connects N
 * simulated agents (each from its own 127.0.x.y source address, as the server keys clients by IP)
 * while measuring how long the Swing event queue is delayed. A frozen GUI shows up as a large
 * "max" or "p99" here.
 *
 * <p>Run from an EMPTY working directory (it creates clients.db there):
 * {@code java -cp SecureRemoteDesktopServer.jar;test-classes com.srd.server.ServerLoadProbe 150 5 40}
 * args: clients, seconds between snapshots per client, steady-state seconds.
 * Mattermost is switched off in the probe's own database so nothing is ever posted.
 */
public final class ServerLoadProbe {
    private static final byte[] KEY = SessionCrypto.pskKey(ServerConfig.DEFAULT_PSK);

    private static final class Phase {
        final String name;
        final List<Long> lagsNs = new CopyOnWriteArrayList<>();

        Phase(String name) {
            this.name = name;
        }
    }

    private static volatile Phase current;

    public static void main(String[] args) throws Exception {
        int clients = args.length > 0 ? Integer.parseInt(args[0]) : 150;
        int thumbSec = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        int steadySec = args.length > 2 ? Integer.parseInt(args[2]) : 40;
        System.setProperty("srd.port", "25820");
        javax.imageio.ImageIO.setUseCache(false);
        ServerApp.preload(); // same start-up work as the real server

        // Own database with Mattermost off and no beeps.
        try (Database db = new Database(Database.DEFAULT_FILE)) {
            ServerConfig c = new ServerConfig();
            c.getNotify().mattermost = false;
            c.getNotify().webhookUrl = "http://127.0.0.1:9/never";
            c.getNotify().beep = false;
            c.getNotify().popupDelaySec = 5; // exercise the pop-up path inside the test window
            new ConfigRepository(db).save(c);
        }

        ServerController controller = new ServerController();
        List<ClientRow> rows = controller.init();
        final ServerWindow[] window = new ServerWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            window[0] = new ServerWindow(controller, controller.notificationsOn());
            controller.attach(window[0], rows);
            window[0].open();
        });

        startLagSampler();
        byte[][] jpegs = makeJpegs();
        List<SimClient> sims = new ArrayList<>();
        for (int i = 0; i < clients; i++) sims.add(new SimClient(i, jpegs));
        Map<String, Phase> results = new LinkedHashMap<>();

        results.put("baseline (idle window)", run("baseline (idle window)", 5, () -> { }));

        results.put("connect storm (" + clients + " clients at once)", run("connect storm", 15, () -> connectAll(sims)));

        ScheduledHolder sched = new ScheduledHolder();
        results.put("steady (" + clients + " clients, snapshot every " + thumbSec + " s)", run("steady", steadySec, () -> {
            for (SimClient s : sims) sched.every(s, thumbSec);
        }));
        sched.stop();

        results.put("mass drop (" + clients + " clients vanish)", run("mass drop", 25, () -> sims.forEach(SimClient::dropAbruptly)));

        results.put("reconnect storm", run("reconnect storm", 15, () -> connectAll(sims)));

        System.out.println();
        System.out.println("=== EDT delay while the server handled " + clients + " simulated clients (ms) ===");
        System.out.printf("%-48s %6s %7s %7s %7s%n", "phase", "n", "p50", "p99", "max");
        boolean ok = true;
        for (Map.Entry<String, Phase> e : results.entrySet()) {
            long[] a = e.getValue().lagsNs.stream().mapToLong(Long::longValue).sorted().toArray();
            if (a.length == 0) continue;
            double p50 = a[a.length / 2] / 1e6, p99 = a[(int) Math.min(a.length - 1, a.length * 99L / 100)] / 1e6, max = a[a.length - 1] / 1e6;
            System.out.printf("%-48s %6d %7.1f %7.1f %7.1f%n", e.getKey(), a.length, p50, p99, max);
            if (max > 500) ok = false;
        }
        Runtime rt = Runtime.getRuntime();
        System.out.printf("heap used %d MB, threads %d%n", (rt.totalMemory() - rt.freeMemory()) >> 20, Thread.getAllStackTraces().size());
        System.out.println(ok ? "RESULT: GUI stayed responsive (no stall over 500 ms)" : "RESULT: GUI STALLED (a delay over 500 ms occurred)");
        System.exit(ok ? 0 : 2);
    }

    // ------------------------------------------------------------------ measuring

    private static volatile Thread edt;
    private static volatile long pendingSince;

    private static void startLagSampler() throws Exception {
        SwingUtilities.invokeAndWait(() -> edt = Thread.currentThread());
        // Stall reporter: if a probe event waits > 150 ms, print what the EDT is doing right then.
        Thread w = new Thread(() -> {
            long lastPrint = 0;
            while (true) {
                long since = pendingSince;
                long now = System.nanoTime();
                if (since != 0 && now - since > 150_000_000L && now - lastPrint > 100_000_000L) {
                    lastPrint = now;
                    StringBuilder b = new StringBuilder("  EDT pending " + (now - since) / 1_000_000 + " ms, phase '" + (current == null ? "?" : current.name) + "':");
                    StackTraceElement[] st = edt.getStackTrace();
                    int shown = 0;
                    for (StackTraceElement e : st) {
                        if (e.getClassName().startsWith("com.srd") || shown < 2) {
                            b.append("\n      at ").append(e);
                            shown++;
                        }
                        if (shown >= 6) break;
                    }
                    System.out.println(b);
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "edt-stall-reporter");
        w.setDaemon(true);
        w.start();
        Thread t = new Thread(() -> {
            while (true) {
                Phase p = current;
                long t0 = System.nanoTime();
                pendingSince = t0;
                try {
                    SwingUtilities.invokeAndWait(() -> { });
                } catch (Exception e) {
                    return;
                }
                pendingSince = 0;
                if (p != null) p.lagsNs.add(System.nanoTime() - t0);
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "edt-lag");
        t.setDaemon(true);
        t.start();
    }

    private static final Map<String, Integer> PROFILE = new java.util.concurrent.ConcurrentHashMap<>();

    private static Thread startProfiler() {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                StackTraceElement[] st = edt.getStackTrace();
                if (st.length > 12 && !st[0].getMethodName().equals("wait") && !st[0].getMethodName().equals("park")
                        && !st[0].getMethodName().equals("poll") && !st[0].getClassName().contains("EventQueue")) {
                    String own = "";
                    for (StackTraceElement e : st) {
                        if (e.getClassName().startsWith("com.srd")) {
                            own = e.getClassName().replace("com.srd.", "") + "." + e.getMethodName() + ":" + e.getLineNumber();
                            break;
                        }
                    }
                    PROFILE.merge(own.isEmpty() ? "(no srd frame) " + st[0].getClassName() : own, 1, Integer::sum);
                }
                try {
                    Thread.sleep(4);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "edt-profiler");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void printProfile(String phase) {
        System.out.println("  EDT busy samples in " + phase + " (4 ms each), by first SRD frame:");
        PROFILE.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(12)
                .forEach(e -> System.out.printf("    %5d  %s%n", e.getValue(), e.getKey()));
        PROFILE.clear();
    }

    private static Phase run(String name, int seconds, Runnable action) throws Exception {
        Phase p = new Phase(name);
        Thread prof = Boolean.getBoolean("probe.profile") ? startProfiler() : null;
        current = p;
        long start = System.currentTimeMillis();
        action.run();
        long left = seconds * 1000L - (System.currentTimeMillis() - start);
        if (left > 0) Thread.sleep(left);
        current = null;
        if (prof != null) {
            prof.interrupt();
            printProfile(name);
        }
        double cpu = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuLoad();
        System.gc();
        Runtime rt = Runtime.getRuntime();
        System.out.printf("[%s] done in %.1fs, process CPU %.0f%%, live heap after GC %d MB, threads %d%n", name,
                (System.currentTimeMillis() - start) / 1000.0, cpu * 100, (rt.totalMemory() - rt.freeMemory()) >> 20,
                Thread.getAllStackTraces().size());
        return p;
    }

    // ------------------------------------------------------------------ simulated agents

    private static void connectAll(List<SimClient> sims) {
        ExecutorService pool = Executors.newFixedThreadPool(32);
        AtomicInteger failed = new AtomicInteger();
        for (SimClient s : sims) pool.execute(() -> {
            if (!s.connect()) failed.incrementAndGet();
        });
        pool.shutdown();
        try {
            pool.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        if (failed.get() > 0) System.out.println("  " + failed.get() + " simulated clients failed to connect");
    }

    private static final class ScheduledHolder {
        private final java.util.concurrent.ScheduledExecutorService ex = Executors.newScheduledThreadPool(4);

        void every(SimClient s, int sec) {
            long jitter = new Random(s.index).nextInt(sec * 1000);
            ex.scheduleWithFixedDelay(s::sendSnapshot, jitter, sec * 1000L, TimeUnit.MILLISECONDS);
            ex.scheduleWithFixedDelay(s::sendStatus, jitter, 10_000, TimeUnit.MILLISECONDS);
        }

        void stop() {
            ex.shutdownNow();
        }
    }

    private static final class SimClient {
        final int index;
        final byte[][] jpegs;
        final String ip;
        volatile MuxConnection conn;
        int shots;

        SimClient(int index, byte[][] jpegs) {
            this.index = index;
            this.jpegs = jpegs;
            this.ip = "127.0." + (index / 250 + 1) + "." + (index % 250 + 1);
        }

        boolean connect() {
            try {
                Hello h = Hello.builder(Hello.ROLE_AGENT).put(Hello.K_ID, "SIM-" + index).put(Hello.K_NAME, "SIM-PC-" + index)
                        .put(Hello.K_VERSION, "probe").put(Hello.K_OS, "Probe").put(Hello.K_SCREEN, "1920x1080").build();
                MuxConnection c = MuxConnection.connect("127.0.0.1", 25820, 5000, h, KEY, InetAddress.getByName(ip));
                c.start(new MuxConnection.Listener() {
                    @Override
                    public void onMessage(MuxConnection m, int type, byte[] p) {
                    }

                    @Override
                    public void onClosed(MuxConnection m, String reason) {
                    }
                });
                conn = c;
                sendStatus();
                sendSnapshot();
                return true;
            } catch (Exception e) {
                System.out.println("  connect " + ip + ": " + e);
                return false;
            }
        }

        void sendSnapshot() {
            MuxConnection c = conn;
            if (c != null && c.isOpen()) c.send(MessageType.THUMBNAIL, jpegs[(index + shots++) % jpegs.length]);
        }

        void sendStatus() {
            MuxConnection c = conn;
            if (c == null || !c.isOpen()) return;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("vpn.state", "PROTECTED");
            m.put("vpn.ip", "203.0.113." + (index % 250));
            m.put("vpn.country", "KR");
            m.put("vpn.aliveAge", "3");
            m.put("vpn.app", "1");
            c.send(MessageType.STATUS, StatusMessage.encode(m));
        }

        /** Like pulling the network cable: no BYE. */
        void dropAbruptly() {
            MuxConnection c = conn;
            conn = null;
            if (c != null) c.close("probe: connection vanished");
        }
    }

    /** A few ~100 KB 1920x1080 JPEGs with enough detail to cost real decode time. */
    private static byte[][] makeJpegs() throws Exception {
        byte[][] out = new byte[4][];
        Random r = new Random(1);
        for (int k = 0; k < out.length; k++) {
            BufferedImage img = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(30 + k * 30, 40, 70));
            g.fillRect(0, 0, 1920, 1080);
            for (int i = 0; i < 400; i++) {
                g.setColor(new Color(r.nextInt(256), r.nextInt(256), r.nextInt(256)));
                g.fillRect(r.nextInt(1800), r.nextInt(1000), 20 + r.nextInt(300), 10 + r.nextInt(120));
            }
            g.dispose();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", bos);
            out[k] = bos.toByteArray();
        }
        System.out.println("probe snapshots: " + Arrays.stream(out).mapToInt(b -> b.length / 1024).boxed().collect(java.util.stream.Collectors.toList()) + " KB");
        return out;
    }
}
