package com.srd.client.vpn;

import com.srd.common.status.VpnGuardStatus;
import com.srd.common.util.Log;
import com.srd.common.util.Os;
import com.srd.common.util.Threads;
import com.sun.jna.platform.win32.W32Service;
import com.sun.jna.platform.win32.W32ServiceManager;
import com.sun.jna.platform.win32.Winsvc;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Watches "VPN Guard" (avant-man) on this computer every {@link #POLL_SECONDS} s and reports its
 * state to the server. Windows, macOS and Linux use the same logic.
 *
 * <p>Primary source: VPN Guard's own CLI, {@code vpnguard status} ("service state, current
 * verdict, recent log (no admin)"). It prints {@code service: Running|Stopped|…} followed by the
 * status JSON, so the answer comes from VPN Guard itself instead of being inferred. It takes
 * about 130 ms.
 *
 * <p>Fallback, if the CLI isn't found or fails: the service state (Windows service query; on
 * macOS/Linux, whether a {@code vpnguard} daemon process runs) plus the {@code status.json} the
 * service rewrites about every 15 s ({@code %ProgramData%\VPNGuard} on Windows,
 * {@code /Library/Application Support/VPNGuard} on macOS, {@code /var/lib/vpnguard} on Linux).
 *
 * <p>None of this needs admin rights.
 */
public final class VpnGuardMonitor {
    public static final int POLL_SECONDS = 5;
    private static final String SERVICE = "VPNGuard";
    private static final long CLI_TIMEOUT_MS = 5000;

    private final List<Consumer<VpnGuardStatus>> listeners = new CopyOnWriteArrayList<>();
    private volatile VpnGuardStatus current = VpnGuardStatus.of(VpnGuardStatus.State.UNKNOWN);
    private volatile boolean started;
    private volatile String lastSource = "";

    public VpnGuardStatus current() {
        return current;
    }

    /** Called (on the poll thread) whenever the visible state changes. */
    public void addListener(Consumer<VpnGuardStatus> l) {
        listeners.add(l);
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        poll(); // synchronous first probe, so the first STATUS a server receives is real, not "Unknown"
        Threads.scheduler().scheduleWithFixedDelay(this::poll, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
    }

    void poll() {
        try {
            String[] source = {""};
            VpnGuardStatus next = probe(source);
            if (!source[0].equals(lastSource)) {
                lastSource = source[0];
                Log.info("VPN Guard detection source: " + source[0]);
            }
            VpnGuardStatus prev = current;
            current = next;
            if (!next.sameAs(prev)) {
                Log.info("VPN Guard: " + next.summary());
                for (Consumer<VpnGuardStatus> l : listeners) {
                    try {
                        l.accept(next);
                    } catch (Exception e) {
                        Log.warn("VPN listener failed: " + e);
                    }
                }
            }
        } catch (Throwable t) {
            Log.warn("VPN Guard probe failed: " + t);
        }
    }

    /** One observation of VPN Guard on this machine. {@code sourceOut[0]} says how it was obtained. */
    public static VpnGuardStatus probe(String[] sourceOut) {
        boolean app = trayAppRunning();
        // 1) VPN Guard's own CLI.
        File cli = findCli();
        if (cli != null) {
            CliStatus st = runStatus(cli);
            if (st != null) {
                sourceOut[0] = "vpnguard status (" + cli + ")";
                return VpnGuardStatus.derive(true, st.service, st.json, app, LocalDateTime.now());
            }
        }
        // 2) Service state + status.json.
        String service = serviceState(); // RUNNING / STOPPED / … / MISSING / "" (unknown)
        boolean serviceExists = !service.isEmpty() && !"MISSING".equals(service);
        File statusFile = findStatusFile();
        boolean installed = cli != null || serviceExists || statusFile != null || app;
        if (!installed) {
            sourceOut[0] = "not found (no vpnguard CLI, service, process or status.json)";
            return VpnGuardStatus.of(VpnGuardStatus.State.NOT_INSTALLED);
        }
        String json = null;
        if (statusFile != null) {
            try {
                json = new String(Files.readAllBytes(statusFile.toPath()), StandardCharsets.UTF_8);
            } catch (Exception e) {
                // being rewritten right now; the next poll will read it
            }
        }
        sourceOut[0] = "service " + (service.isEmpty() ? "?" : service) + " + " + (statusFile == null ? "no status.json" : statusFile);
        return VpnGuardStatus.derive(true, serviceExists ? service : "", json, app, LocalDateTime.now());
    }

    public static VpnGuardStatus probe() {
        return probe(new String[1]);
    }

    // ------------------------------------------------------------------ CLI

    static final class CliStatus {
        final String service; // RUNNING / STOPPED / … ("" if not printed)
        final String json;    // may be null

        CliStatus(String service, String json) {
            this.service = service;
            this.json = json;
        }
    }

    static File findCli() {
        List<File> c = new ArrayList<>();
        if (Os.WINDOWS) {
            c.add(new File(env("ProgramFiles", "C:\\Program Files"), "VPNGuard\\vpnguard.exe"));
            c.add(new File(env("ProgramFiles(x86)", "C:\\Program Files (x86)"), "VPNGuard\\vpnguard.exe"));
            c.add(new File(env("LOCALAPPDATA", ""), "Programs\\VPNGuard\\vpnguard.exe"));
        } else {
            String home = System.getProperty("user.home");
            for (String app : new String[] {"VPNGuard.app", "VPN Guard.app", "vpnguard.app"}) {
                c.add(new File("/Applications/" + app + "/Contents/MacOS/vpnguard"));
                c.add(new File("/Applications/" + app + "/Contents/Resources/vpnguard"));
                c.add(new File(home + "/Applications/" + app + "/Contents/MacOS/vpnguard"));
            }
            for (String dir : new String[] {"/usr/local/bin", "/opt/homebrew/bin", "/usr/bin", "/usr/sbin", "/opt/vpnguard/bin",
                    "/usr/local/vpnguard", "/Library/Application Support/VPNGuard"}) {
                c.add(new File(dir, "vpnguard"));
            }
        }
        for (File f : c) if (f.isFile()) return f;
        // On PATH?
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                File f = new File(dir, Os.WINDOWS ? "vpnguard.exe" : "vpnguard");
                if (f.isFile()) return f;
            }
        }
        return null;
    }

    /** Runs {@code vpnguard status}; null if it fails or prints nothing usable. */
    static CliStatus runStatus(File cli) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cli.getPath(), "status").redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().close();
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            InputStream in = p.getInputStream();
            long deadline = System.currentTimeMillis() + CLI_TIMEOUT_MS;
            byte[] buf = new byte[8192];
            while (System.currentTimeMillis() < deadline) {
                int avail = in.available();
                if (avail > 0) {
                    int n = in.read(buf, 0, Math.min(buf.length, avail));
                    if (n > 0) out.write(buf, 0, n);
                    if (out.size() > 512 * 1024) break;
                    continue;
                }
                if (!p.isAlive()) {
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    break;
                }
                Thread.sleep(10);
            }
            if (p.isAlive()) {
                p.destroyForcibly();
                Log.warn("vpnguard status timed out");
                return null;
            }
            return parseStatus(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.debug("vpnguard status failed: " + e);
            return null;
        }
    }

    /**
     * Parses {@code vpnguard status} output:
     * <pre>
     * service: Running
     * VPN clients that may reconnect while blocked:
     *   C:\Program Files\ExpressVPN
     * {
     *   "alive": "...", ... "state": "ALLOWED", ...
     * }
     * --- last log lines
     * ...
     * </pre>
     */
    static CliStatus parseStatus(String text) {
        String service = "";
        String json = null;
        String[] lines = text.replace("\r", "").split("\n");
        int jsonStart = -1;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            String lower = l.toLowerCase(Locale.ROOT);
            if (service.isEmpty() && lower.startsWith("service:")) {
                service = normalizeService(l.substring("service:".length()).trim());
            }
            if (jsonStart < 0 && l.equals("{")) jsonStart = i;
            if (jsonStart >= 0 && lines[i].startsWith("}")) {
                StringBuilder b = new StringBuilder();
                for (int k = jsonStart; k <= i; k++) b.append(lines[k]).append('\n');
                json = b.toString();
                break;
            }
        }
        if (service.isEmpty() && json == null) return null;
        return new CliStatus(service, json);
    }

    /** "Running" → RUNNING, "Stopped" → STOPPED, "not installed" → MISSING, other text upper-cased. */
    static String normalizeService(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        if (l.startsWith("running")) return "RUNNING";
        if (l.contains("not installed") || l.contains("not found") || l.contains("missing")) return "MISSING";
        if (l.startsWith("stopped") || l.equals("stop") || l.contains("not running") || l.contains("inactive")) return "STOPPED";
        if (l.contains("start")) return "START_PENDING";
        if (l.contains("stopping")) return "STOP_PENDING";
        return s.toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    // ------------------------------------------------------------------ fallback

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isEmpty() ? def : v;
    }

    static File findStatusFile() {
        List<File> c = new ArrayList<>();
        if (Os.WINDOWS) {
            c.add(new File(env("ProgramData", "C:\\ProgramData"), "VPNGuard\\status.json"));
        } else {
            c.add(new File("/Library/Application Support/VPNGuard/status.json"));
            c.add(new File("/Library/Application Support/vpnguard/status.json"));
            c.add(new File("/var/lib/vpnguard/status.json"));
            c.add(new File("/var/db/vpnguard/status.json"));
            c.add(new File("/etc/vpnguard/status.json"));
            c.add(new File(System.getProperty("user.home"), "Library/Application Support/VPNGuard/status.json"));
        }
        for (File f : c) if (f.isFile()) return f;
        return null;
    }

    /** RUNNING / STOPPED / START_PENDING / …, "MISSING" if no such service, "" if unknown. */
    static String serviceState() {
        if (!Os.WINDOWS) return daemonProcessRunning() ? "RUNNING" : "";
        return Os.tryNative("query VPNGuard service", () -> {
            W32ServiceManager scm = new W32ServiceManager();
            try {
                scm.open(Winsvc.SC_MANAGER_CONNECT);
                W32Service svc;
                try {
                    svc = scm.openService(SERVICE, Winsvc.SERVICE_QUERY_STATUS);
                } catch (Exception e) {
                    return "MISSING";
                }
                try {
                    return stateName(svc.queryStatus().dwCurrentState);
                } finally {
                    svc.close();
                }
            } finally {
                scm.close();
            }
        }, "");
    }

    private static String stateName(int s) {
        switch (s) {
            case Winsvc.SERVICE_RUNNING:
                return "RUNNING";
            case Winsvc.SERVICE_STOPPED:
                return "STOPPED";
            case Winsvc.SERVICE_START_PENDING:
                return "START_PENDING";
            case Winsvc.SERVICE_STOP_PENDING:
                return "STOP_PENDING";
            case Winsvc.SERVICE_PAUSED:
                return "PAUSED";
            default:
                return "STATE_" + s;
        }
    }

    /**
     * macOS/Linux: a root daemon's command line is often hidden from ProcessHandle, so ask
     * {@code pgrep}, which sees every user's processes.
     */
    private static boolean daemonProcessRunning() {
        try {
            Process p = new ProcessBuilder("pgrep", "-x", "vpnguard").redirectErrorStream(true).start();
            p.getOutputStream().close();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Tray / window app: vpnguard-app(.exe), or a process inside VPNGuard.app (macOS). */
    private static boolean trayAppRunning() {
        return ProcessHandle.allProcesses().anyMatch(ph -> ph.info().command().map(c -> {
            String l = c.toLowerCase(Locale.ROOT).replace('\\', '/');
            return l.endsWith("/vpnguard-app.exe") || l.endsWith("/vpnguard-app") || l.contains("/vpnguard.app/contents/macos/")
                    && !l.endsWith("/vpnguard");
        }).orElse(false));
    }
}
