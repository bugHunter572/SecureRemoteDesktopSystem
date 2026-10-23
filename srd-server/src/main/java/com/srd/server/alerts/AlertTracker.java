package com.srd.server.alerts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure bookkeeping of what is wrong with which client and what has already been announced
 * (unit-tested, no clock, no UI). A problem starts when it is {@link #raise raised}; once it has
 * lasted the pop-up delay it is announced locally, and once it has lasted the Mattermost delay it
 * goes out in a batched post. {@link #resolve} yields a recovery notice only for problems that
 * were announced, so a blip that fixed itself within the delay stays silent.
 */
public final class AlertTracker {
    public enum Kind {
        DISCONNECTED("🔴", "ScreenApp Disconnected", 0),
        NO_SCREEN("🟠", "Screen Waiting", 1),
        VPN("🔴", "VPN Guard Off", 2);

        public final String emoji;
        /** The Status column of the Mattermost table. */
        public final String label;
        public final int order;

        Kind(String emoji, String label, int order) {
            this.emoji = emoji;
            this.label = label;
            this.order = order;
        }
    }

    public static final class Problem {
        public final String clientId;
        public Kind kind;
        public String detail;
        public final long since;
        boolean toasted;
        boolean mmSent;
        /** When this problem last went to Mattermost (first post or a reminder). */
        long lastPostMs;

        Problem(String clientId, Kind kind, String detail, long since) {
            this.clientId = clientId;
            this.kind = kind;
            this.detail = detail;
            this.since = since;
        }
    }

    public static final class Recovery {
        public final String clientId;
        public final Kind kind;
        public final String detail;
        public final long downMs;
        /** When it was fixed (epoch ms). */
        public final long at;
        public final boolean toLocal;
        public final boolean toMattermost;

        Recovery(Problem p, String detail, long now) {
            this.clientId = p.clientId;
            this.kind = p.kind;
            this.detail = detail;
            this.downMs = Math.max(0, now - p.since);
            this.at = now;
            this.toLocal = p.toasted;
            this.toMattermost = p.mmSent;
        }
    }

    /** What {@link #tick} decided; each problem/recovery appears in exactly one tick. */
    public static final class Due {
        public final List<Problem> popups = new ArrayList<>();
        public final List<Problem> mattermost = new ArrayList<>();
        public final List<Problem> reminders = new ArrayList<>();
        public final List<Recovery> recoveries = new ArrayList<>();

        public boolean isEmpty() {
            return popups.isEmpty() && mattermost.isEmpty() && reminders.isEmpty() && recoveries.isEmpty();
        }
    }

    /** Decides who may be announced right now (per-client bell, master switch, type toggles). */
    public interface Gate {
        boolean allowed(Problem p);
    }

    private final Map<String, Problem> problems = new LinkedHashMap<>();
    private final List<Recovery> pendingRecoveries = new ArrayList<>();

    private static String key(String clientId, Kind kind) {
        return clientId + '|' + kind.name();
    }

    /**
     * Starts a problem, or only refreshes the detail text of one already open. A "no screen" report
     * for a client that is still marked disconnected (it reconnected but never sent a picture)
     * continues that same problem instead of opening a second one.
     *
     * @return true only if this opened a brand-new problem (callers log history on that)
     */
    public synchronized boolean raise(String clientId, Kind kind, String detail, long since) {
        Problem existing = problems.get(key(clientId, kind));
        if (existing != null) {
            existing.detail = detail;
            return false;
        }
        if (kind == Kind.NO_SCREEN) {
            Problem dc = problems.remove(key(clientId, Kind.DISCONNECTED));
            if (dc != null) {
                dc.kind = Kind.NO_SCREEN;
                dc.detail = detail;
                problems.put(key(clientId, Kind.NO_SCREEN), dc);
                return false;
            }
        }
        problems.put(key(clientId, kind), new Problem(clientId, kind, detail, since));
        return true;
    }

    public synchronized boolean has(String clientId, Kind kind) {
        return problems.containsKey(key(clientId, kind));
    }

    /** Clients that currently have an open problem of this kind. */
    public synchronized List<String> openIds(Kind kind) {
        List<String> out = new ArrayList<>();
        for (Problem p : problems.values()) if (p.kind == kind) out.add(p.clientId);
        return out;
    }

    /** Ends a problem; queues a recovery notice if it had been announced anywhere. */
    public synchronized void resolve(String clientId, Kind kind, String detail, long now) {
        Problem p = problems.remove(key(clientId, kind));
        if (p != null && (p.toasted || p.mmSent)) pendingRecoveries.add(new Recovery(p, detail, now));
    }

    /** Ends a problem without a recovery notice. */
    public synchronized void drop(String clientId, Kind kind) {
        problems.remove(key(clientId, kind));
    }

    public synchronized void forget(String clientId) {
        for (Kind k : Kind.values()) problems.remove(key(clientId, k));
        pendingRecoveries.removeIf(r -> r.clientId.equals(clientId));
    }

    public synchronized int openCount() {
        return problems.size();
    }

    /**
     * @param popupDelayMs   announce locally after this long (negative = never)
     * @param mmDelayMs      first Mattermost post after this long (negative = never)
     * @param mmGroupMs      problems within this much of the delay join the same post
     */
    public synchronized Due tick(long now, long popupDelayMs, long mmDelayMs, long mmGroupMs, Gate gate) {
        return tick(now, popupDelayMs, mmDelayMs, mmGroupMs, -1, gate);
    }

    /**
     * @param reminderDelayMs a "still disconnected" reminder every this long after the last post, for
     *                        {@link Kind#DISCONNECTED} problems only (zero or negative = never)
     */
    public synchronized Due tick(long now, long popupDelayMs, long mmDelayMs, long mmGroupMs, long reminderDelayMs, Gate gate) {
        Due due = new Due();
        boolean mmTriggered = false;
        for (Problem p : problems.values()) {
            long age = now - p.since;
            boolean allowed = gate.allowed(p);
            if (allowed && !p.toasted && popupDelayMs >= 0 && age >= popupDelayMs) {
                p.toasted = true;
                due.popups.add(p);
            }
            if (allowed && !p.mmSent && mmDelayMs >= 0 && age >= mmDelayMs) mmTriggered = true;
        }
        if (mmTriggered) {
            for (Problem p : problems.values()) {
                if (!p.mmSent && gate.allowed(p) && now - p.since >= mmDelayMs - mmGroupMs) {
                    p.mmSent = true;
                    p.lastPostMs = now;
                    due.mattermost.add(p);
                }
            }
        }
        if (reminderDelayMs > 0) {
            for (Problem p : problems.values()) {
                if ((p.kind == Kind.DISCONNECTED || p.kind == Kind.NO_SCREEN) && p.mmSent && now - p.lastPostMs >= reminderDelayMs && gate.allowed(p)) {
                    p.lastPostMs = now;
                    due.reminders.add(p);
                }
            }
        }
        due.recoveries.addAll(pendingRecoveries);
        pendingRecoveries.clear();
        return due;
    }
}
