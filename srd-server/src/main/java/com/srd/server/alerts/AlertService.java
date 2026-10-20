package com.srd.server.alerts;

import com.srd.common.status.VpnGuardStatus;
import com.srd.common.util.Log;
import com.srd.common.util.Threads;
import com.srd.server.alerts.AlertTracker.Due;
import com.srd.server.alerts.AlertTracker.Kind;
import com.srd.server.alerts.AlertTracker.Problem;
import com.srd.server.alerts.AlertTracker.Recovery;
import com.srd.server.config.NotifySettings;
import com.srd.server.ui.Toast;
import java.awt.Color;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Turns client facts (connection lost, no picture arriving, VPN Guard off) into notifications:
 * a pop-up on the server after the pop-up delay, a notice on the affected PC, and one grouped
 * Mattermost post after the Mattermost delay (a disconnected PC is posted again every repeat interval). When the client is
 * healthy again (and, after a reconnect, has delivered a picture) a recovery notice follows for
 * everything that was announced.
 *
 * <p>Cost: one 2 s tick on the shared scheduler that walks the connected clients' timestamps —
 * no traffic, no per-client threads. Everything else is event driven.
 *
 * <p>Floods stay readable: pop-ups stack (the toast cap drops the rest) and a long Mattermost
 * table is split into posts of 100 rows.
 */
public final class AlertService {
    private static final long TICK_SEC = 2;
    /** Grace on top of the snapshot interval before a missing picture counts. */
    private static final long SCREEN_SLACK_MS = 5000;
    /** After a server start, clients get this long to reconnect before "never came back" is raised. */
    private static final long STARTUP_GRACE_SEC = 90;
    /** Only clients seen this recently are expected to reconnect after a server restart. */
    private static final long STARTUP_MAX_AGE_MS = 7L * 24 * 3600 * 1000;
    private static final long RETRY_EVERY_TICKS = 30; // ~60 s
    private static final Color RED = new Color(0xD32F2F);
    private static final Color ORANGE = new Color(0xF57C00);
    private static final Color PURPLE = new Color(0x7B1FA2);
    private static final Color GREEN = new Color(0x2E7D32);

    public interface Host {
        /** Master switch and the client's own bell. */
        boolean enabled(String clientId);

        String displayName(String clientId);

        /** Computer name the agent announced, or "". */
        String hostName(String clientId);

        /** Sends a CONTROL text to the client if it is connected. */
        void sendToClient(String clientId, String control);

        /** Registered, currently connected clients: connect time and last picture time (0 = none yet). */
        void forEachConnected(ConnectedVisitor v);

        /** Registered clients that are not connected and were last connected no longer than maxAgeMs ago. */
        List<String> knownNotConnected(long maxAgeMs);

        /** Whether the client's computer answers a ping (blocking, a few seconds at most). */
        boolean reachable(String clientId);

        /** Adds an entry to the client's Today's Records / history. */
        void logEvent(String clientId, String eventType);

        int snapshotIntervalSec();

        String serverName();
    }

    public interface ConnectedVisitor {
        void visit(String clientId, long connectedAtMs, long lastFrameMs);
    }

    private static final long PROBE_EVERY_TICKS = 5; // ~10 s
    private static final ExecutorService PROBES = Threads.pool("alert-ping", 4);

    private final Host host;
    private final Supplier<NotifySettings> settings;
    /** Clients with an open "disconnected" problem whose computer did not answer the last ping. */
    private final Set<String> unreachable = ConcurrentHashMap.newKeySet();
    private final Set<String> probed = ConcurrentHashMap.newKeySet();
    private final Set<String> probing = ConcurrentHashMap.newKeySet();
    private final AlertTracker tracker = new AlertTracker();
    private final Mattermost mattermost = new Mattermost();
    private volatile ScheduledFuture<?> ticker;
    private volatile ScheduledFuture<?> startupScan;
    private long tickCount;

    public AlertService(Host host, Supplier<NotifySettings> settings) {
        this.host = host;
        this.settings = settings;
        mattermost.setHealth(new Mattermost.Health() {
            @Override
            public void unreachable(String reason) {
                Log.warn("[ALERT] Mattermost unreachable: " + reason);
            }

            @Override
            public void reachableAgain() {
                Log.info("[ALERT] Mattermost reachable again");
            }
        });
    }

    public void start() {
        ticker = Threads.scheduler().scheduleWithFixedDelay(this::safeTick, TICK_SEC, TICK_SEC, TimeUnit.SECONDS);
        startupScan = Threads.scheduler().schedule(this::safeStartupScan, STARTUP_GRACE_SEC, TimeUnit.SECONDS);
    }

    public void stop() {
        ScheduledFuture<?> t = ticker;
        if (t != null) t.cancel(false);
        t = startupScan;
        if (t != null) t.cancel(false);
    }

    // ------------------------------------------------------------------ events

    /**
     * The client's connection ended. {@code userQuit}: it said goodbye (tray Quit, or the PC is
     * shutting down/restarting: both send the same BYE). The problem opens either way and is announced
     * after the delay; the ping ({@link #probeAway}) only decides the wording: a PC that is down is
     * "Disconnected", a PC that still answers is "ScreenApp Not Running (PC online)".
     */
    public void clientClosed(String id, boolean userQuit) {
        NotifySettings s = settings.get();
        tracker.drop(id, Kind.VPN);
        tracker.drop(id, Kind.NO_SCREEN);
        if (s.alertDisconnected) {
            tracker.raise(id, Kind.DISCONNECTED, userQuit ? "Client app was closed" : "Connection lost", System.currentTimeMillis());
        }
    }

    /** Refines the text of an open "disconnected" problem (e.g. after the reachability ping). */
    public void clientAway(String id, String detail) {
        if (tracker.has(id, Kind.DISCONNECTED)) tracker.raise(id, Kind.DISCONNECTED, detail, System.currentTimeMillis());
    }

    /** Every VPN Guard status report. */
    public void vpn(String id, VpnGuardStatus st) {
        NotifySettings s = settings.get();
        long now = System.currentTimeMillis();
        VpnGuardStatus.State state = st.state;
        if (VpnAlertLogic.serviceRunning(state)) { // service running = VPN Guard on, even while it blocks
            tracker.resolve(id, Kind.VPN, "VPN Guard On", now);
        } else if (state == VpnGuardStatus.State.NOT_APPLICABLE) {
            tracker.drop(id, Kind.VPN);
        } else if (state.alert) {
            boolean wanted = s.alertVpn && (state != VpnGuardStatus.State.NOT_INSTALLED || s.alertVpnNotInstalled);
            if (wanted) {
                if (tracker.raise(id, Kind.VPN, st.summary(), now)) host.logEvent(id, "VPN_OFF");
            } else {
                tracker.drop(id, Kind.VPN);
            }
        }
    }

    /** Whether anything is currently wrong with this client (announced or still inside the delay). */
    public boolean hasProblem(String id) {
        for (Kind k : Kind.values()) if (tracker.has(id, k)) return true;
        return false;
    }

    public void forget(String id) {
        tracker.forget(id);
    }

    // ------------------------------------------------------------------ tick

    private void safeTick() {
        try {
            tick(System.currentTimeMillis());
        } catch (Exception e) {
            Log.error("Alert tick failed", e);
        }
    }

    private void safeStartupScan() {
        try {
            NotifySettings s = settings.get();
            if (!s.alertDisconnected) return;
            long now = System.currentTimeMillis();
            List<String> missing = host.knownNotConnected(STARTUP_MAX_AGE_MS);
            for (String id : missing) {
                tracker.raise(id, Kind.DISCONNECTED, "Not connected since the server started", now);
            }
            if (!missing.isEmpty()) Log.info("[ALERT] " + missing.size() + " known client(s) did not reconnect after the server started");
        } catch (Exception e) {
            Log.error("Startup scan failed", e);
        }
    }

    /** Repeat interval for "ScreenApp Disconnected" posts: the setting, or the screen interval while it is -1 (0 = never). */
    private long repeatMs(NotifySettings s) {
        int sec = s.mattermostRepeatSec < 0 ? host.snapshotIntervalSec() : s.mattermostRepeatSec;
        return sec * 1000L;
    }

    /** Status column: a missing app on a PC that still answers ping is told apart from a PC that is down. */
    private String statusText(Problem p) {
        if (p.kind == Kind.DISCONNECTED && !unreachable.contains(p.clientId)) return "🟡 ScreenApp Not Running (PC online)";
        return p.kind.emoji + " " + p.kind.label;
    }

    void tick(long now) {
        NotifySettings s = settings.get();
        if (++tickCount % RETRY_EVERY_TICKS == 0) mattermost.retryPending();
        scanScreens(now, s);
        probeAway();
        boolean mmOn = s.mattermost && s.mattermostConfigured();
        Due due = tracker.tick(now, s.popups ? s.popupDelaySec * 1000L : -1, mmOn ? s.mattermostDelaySec * 1000L : -1,
                s.mattermostGroupSec * 1000L, mmOn ? repeatMs(s) : -1,
                p -> host.enabled(p.clientId));
        if (due.isEmpty()) return;

        showProblems(due.popups, now, s);

        TagMap tags = tags(s);
        if (mmOn && !(due.mattermost.isEmpty() && due.reminders.isEmpty())) {
            List<Mattermost.Row> rows = new ArrayList<>();
            List<Problem> all = new ArrayList<>(due.mattermost);
            all.addAll(due.reminders);
            for (Problem p : all) {
                rows.add(new Mattermost.Row(tags.tagFor(p.clientId), host.displayName(p.clientId), p.clientId,
                        statusText(p), when(p.since)));
            }
            for (String post : Mattermost.formatTables(rows)) mattermost.post(s.mattermostTarget(), post);
            Log.info("[ALERT] Mattermost: " + due.mattermost.size() + " client(s), " + due.reminders.size() + " reminder(s)");
        }

        if (s.recoveryNotices && !due.recoveries.isEmpty()) {
            List<Mattermost.Row> mmRows = new ArrayList<>();
            List<Recovery> local = new ArrayList<>();
            for (Recovery r : due.recoveries) {
                if (r.toLocal && s.popups) local.add(r);
                if (r.toMattermost && mmOn) {
                    mmRows.add(new Mattermost.Row(tags.tagFor(r.clientId), host.displayName(r.clientId), r.clientId,
                            (r.kind == Kind.VPN ? "🟢 VPN Guard On" : "✅ Connected"), when(r.at)));
                }
            }
            showRecoveries(local, s);
            for (String post : Mattermost.formatTables(mmRows)) mattermost.post(s.mattermostTarget(), post);
        }
    }

    /**
     * Pings every client with an open "disconnected" problem (once, then every ~10 s while it is
     * open) on a small pool. A computer that does not answer is "unreachable" ("Disconnected" in the post);
     * one that answers is posted as "ScreenApp Not Running (PC online)".
     */
    private void probeAway() {
        List<String> open = tracker.openIds(Kind.DISCONNECTED);
        unreachable.retainAll(open);
        probed.retainAll(open);
        for (String id : open) {
            boolean due = !probed.contains(id) || tickCount % PROBE_EVERY_TICKS == 0;
            if (!due || !probing.add(id)) continue;
            PROBES.execute(() -> {
                try {
                    if (host.reachable(id)) {
                        unreachable.remove(id);
                    } else {
                        unreachable.add(id);
                        clientAway(id, "Computer not reachable");
                    }
                    probed.add(id);
                } catch (Exception e) {
                    Log.warn("[ALERT] reachability check for " + id + " failed: " + e);
                } finally {
                    probing.remove(id);
                }
            });
        }
    }

    private static final java.time.format.DateTimeFormatter WHEN = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private volatile String tagText;
    private volatile TagMap tagMap = TagMap.parse("");

    /** The Mattermost table's When column, local time. */
    public static String when(long epochMs) {
        return java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault()).format(WHEN);
    }

    private TagMap tags(NotifySettings s) {
        if (!s.tags.equals(tagText)) {
            tagMap = TagMap.parse(s.tags);
            tagText = s.tags;
        }
        return tagMap;
    }

    /** Connected clients whose picture is overdue become NO_SCREEN; a fresh picture heals them. */
    private void scanScreens(long now, NotifySettings s) {
        long intervalMs = Math.max(1, host.snapshotIntervalSec()) * 1000L;
        host.forEachConnected((id, connectedAt, lastFrame) -> {
            long ref = Math.max(connectedAt, lastFrame);
            long expected = ref + intervalMs;
            boolean overdue = now - expected > SCREEN_SLACK_MS;
            if (overdue) {
                if (s.alertNoScreen && tracker.raise(id, Kind.NO_SCREEN, "Connected, but no screen received", expected)) {
                    host.logEvent(id, "NO_SCREEN");
                }
            } else if (lastFrame > 0) {
                tracker.resolve(id, Kind.NO_SCREEN, "Screen is being received again", now);
                tracker.resolve(id, Kind.DISCONNECTED, "Reconnected, screen is being received", now);
            }
        });
    }

    // ------------------------------------------------------------------ output

    private String label(String id) {
        return host.displayName(id) + " (" + id + ")";
    }

    private String what(Problem p) {
        return p.kind == Kind.VPN ? "" : p.detail;
    }

    private static Color color(Kind kind) {
        return kind == Kind.DISCONNECTED ? RED : kind == Kind.NO_SCREEN ? ORANGE : PURPLE;
    }

    private static String title(Kind kind) {
        return kind == Kind.DISCONNECTED ? "Client disconnected" : kind == Kind.NO_SCREEN ? "No screen from client" : "VPN Guard Off";
    }

    private void showProblems(List<Problem> problems, long now, NotifySettings s) {
        if (problems.isEmpty()) return;
        for (Problem p : problems) {
            String what = what(p);
            Toast.show(title(p.kind), label(p.clientId) + (what.isEmpty() ? "" : "\n" + what), color(p.kind), 10_000);
            Log.warn("[ALERT] " + title(p.kind) + ": " + label(p.clientId) + " - " + p.detail);
        }
        if (s.beep) Toolkit.getDefaultToolkit().beep();
        if (!s.notifyClients) return;
        for (Problem p : problems) {
            if (p.kind == Kind.DISCONNECTED) continue; // not reachable; the client reports its own link loss
            host.sendToClient(p.clientId, p.kind == Kind.VPN
                    ? notice("WARN", "VPN Guard Off", "VPN Guard is off")
                    : notice("WARN", "Screen not received", "The server has not received your screen."));
        }
    }

    private void showRecoveries(List<Recovery> recoveries, NotifySettings s) {
        for (Recovery r : recoveries) {
            String title = r.kind == Kind.VPN ? "VPN Guard On" : "Client back";
            Toast.show(title, label(r.clientId) + "\n" + r.detail, GREEN, 6000);
            Log.info("[ALERT] recovered: " + label(r.clientId) + " - " + r.detail);
            if (s.notifyClients && r.kind != Kind.DISCONNECTED) {
                host.sendToClient(r.clientId, notice("INFO", "Back to normal", r.detail));
            }
        }
    }

    /** CONTROL text the client turns into a system notification. */
    static String notice(String level, String title, String text) {
        return "NOTIFY:" + level + "|" + clean(title) + "|" + clean(text);
    }

    private static String clean(String s) {
        return s.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
    }
}
