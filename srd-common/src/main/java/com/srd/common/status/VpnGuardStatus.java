package com.srd.common.status;

import java.awt.Color;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Status of the "VPN Guard" egress-protection app (avant-man VPN Guard) on a client, as reported
 * to the server.
 *
 * <p>Sources on the client (Windows):
 * <ul>
 *   <li>service {@code VPNGuard} (running = protection enabled; {@code vpnguard disable} stops it);</li>
 *   <li>{@code %ProgramData%\VPNGuard\status.json}, rewritten by the service, with {@code alive}
 *       (heartbeat), {@code state} ALLOWED/BLOCKED, {@code verifying}, {@code ip}, {@code probes},
 *       {@code path_name}, {@code reason} and {@code since};</li>
 *   <li>tray app process {@code vpnguard-app.exe}.</li>
 * </ul>
 */
public final class VpnGuardStatus {
    public enum State {
        NOT_APPLICABLE("N/A", new Color(0x9E9E9E), false),
        NOT_INSTALLED("Not installed", new Color(0x757575), true),
        OFF("Off", new Color(0xD32F2F), true),
        STALE("Not responding", new Color(0xF57C00), true),
        CHECKING("Checking", new Color(0xFBC02D), false),
        PROTECTED("Protected", new Color(0x2E7D32), false),
        BLOCKED("Blocked", new Color(0xC62828), true),
        UNKNOWN("Unknown", new Color(0x9E9E9E), false);

        public final String label;
        public final Color color;
        /** Transitions into this state raise an alert on the server. */
        public final boolean alert;

        State(String label, Color color, boolean alert) {
            this.label = label;
            this.color = color;
            this.alert = alert;
        }

        public static State parse(String s) {
            try {
                return s == null ? UNKNOWN : State.valueOf(s);
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }
    }

    /** status.json older than this while the service runs ⇒ STALE (the service is hung). */
    public static final long STALE_AFTER_SEC = 45;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]");

    public final State state;
    public final String ip;
    public final String country;
    public final String route;
    public final String reason;
    public final String since;
    /** Seconds since the service's last heartbeat; -1 if unknown. */
    public final long aliveAgeSec;
    public final boolean appRunning;
    /** Raw Windows service state (RUNNING, STOPPED, …) or "" if unknown. */
    public final String service;

    public VpnGuardStatus(State state, String ip, String country, String route, String reason, String since,
                          long aliveAgeSec, boolean appRunning, String service) {
        this.state = state;
        this.ip = nz(ip);
        this.country = nz(country);
        this.route = nz(route);
        this.reason = nz(reason);
        this.since = nz(since);
        this.aliveAgeSec = aliveAgeSec;
        this.appRunning = appRunning;
        this.service = nz(service);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public static VpnGuardStatus of(State s) {
        return new VpnGuardStatus(s, "", "", "", "", "", -1, false, "");
    }

    /**
     * Derives the status from what the client observed.
     *
     * @param installed      the VPN Guard executable / uninstall entry exists
     * @param serviceState   RUNNING / STOPPED / … ("" when it can't be queried)
     * @param statusJson     contents of status.json (null if missing/unreadable)
     * @param appRunning     the tray app process is running
     * @param now            local wall-clock time (status.json timestamps are local)
     */
    public static VpnGuardStatus derive(boolean installed, String serviceState, String statusJson, boolean appRunning,
                                        LocalDateTime now) {
        if (!installed && statusJson == null) return of(State.NOT_INSTALLED);
        String svc = serviceState == null ? "" : serviceState;
        Map<String, Object> j = null;
        if (statusJson != null) {
            try {
                j = MiniJson.parseObject(statusJson);
            } catch (IllegalArgumentException ignored) {
                // half-written file: treat as unknown details
            }
        }
        String ip = j != null ? str(j.get("ip")) : "";
        String route = j != null ? str(j.get("path_name")) : "";
        String reason = j != null ? str(j.get("reason")) : "";
        String since = j != null ? str(j.get("since")) : "";
        String country = j != null ? majorityCountry(j.get("probes")) : "";
        long age = -1;
        if (j != null) {
            LocalDateTime alive = parseTs(str(j.get("alive")));
            if (alive != null) age = Math.max(0, Duration.between(alive, now).getSeconds());
        }

        State st;
        if (!svc.isEmpty() && !"RUNNING".equals(svc)) {
            st = State.OFF;
            reason = "VPN Guard service is " + svc.toLowerCase();
        } else if (j == null) {
            st = svc.isEmpty() ? State.UNKNOWN : State.STALE;
            if (st == State.STALE) reason = "service running but status.json unreadable";
        } else if (age > STALE_AFTER_SEC) {
            // A stopped service leaves a frozen status.json behind; if we couldn't query the
            // service, a very old heartbeat most likely means it's off.
            st = svc.isEmpty() ? State.OFF : State.STALE;
            reason = "no heartbeat for " + age + " s";
        } else if (Boolean.TRUE.equals(j.get("verifying"))) {
            st = State.CHECKING;
        } else {
            String s = str(j.get("state")).toUpperCase();
            st = "ALLOWED".equals(s) ? State.PROTECTED : "BLOCKED".equals(s) ? State.BLOCKED : State.UNKNOWN;
        }
        return new VpnGuardStatus(st, ip, country, route, reason, since, age, appRunning, svc);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static LocalDateTime parseTs(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return LocalDateTime.parse(s, TS);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static String majorityCountry(Object probes) {
        if (!(probes instanceof List)) return "";
        Map<String, Integer> votes = new HashMap<>();
        String best = "";
        int bestN = 0;
        for (Object p : (List<Object>) probes) {
            if (!(p instanceof Map)) continue;
            Object c = ((Map<String, Object>) p).get("country");
            if (c == null) continue;
            int n = votes.merge(String.valueOf(c), 1, Integer::sum);
            if (n > bestN) {
                bestN = n;
                best = String.valueOf(c);
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ wire (STATUS keys)

    public Map<String, String> toMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("vpn.state", state.name());
        m.put("vpn.ip", ip);
        m.put("vpn.country", country);
        m.put("vpn.route", route);
        m.put("vpn.reason", reason);
        m.put("vpn.since", since);
        m.put("vpn.aliveAge", Long.toString(aliveAgeSec));
        m.put("vpn.app", appRunning ? "1" : "0");
        m.put("vpn.service", service);
        return m;
    }

    /** Returns null if the map carries no VPN status. */
    public static VpnGuardStatus fromMap(Map<String, String> m) {
        if (!m.containsKey("vpn.state")) return null;
        long age;
        try {
            age = Long.parseLong(m.getOrDefault("vpn.aliveAge", "-1"));
        } catch (NumberFormatException e) {
            age = -1;
        }
        return new VpnGuardStatus(State.parse(m.get("vpn.state")), m.get("vpn.ip"), m.get("vpn.country"),
                m.get("vpn.route"), m.get("vpn.reason"), m.get("vpn.since"), age, "1".equals(m.get("vpn.app")),
                m.get("vpn.service"));
    }

    /** True if anything user-visible differs (ignores the ticking heartbeat age). */
    public boolean sameAs(VpnGuardStatus o) {
        return o != null && state == o.state && ip.equals(o.ip) && country.equals(o.country) && route.equals(o.route)
                && appRunning == o.appRunning && service.equals(o.service);
    }

    /** One-line summary for logs / DB. */
    public String summary() {
        StringBuilder b = new StringBuilder(state.label);
        if (!country.isEmpty() || !ip.isEmpty()) b.append(" — ").append(country).append(' ').append(ip);
        if (!route.isEmpty()) b.append(" via ").append(route);
        if (!reason.isEmpty() && state != State.PROTECTED) b.append(" (").append(reason).append(')');
        return b.toString().trim();
    }

    /**
     * HTML tooltip for the server table / thumbnail, at most three short lines: the state with the
     * country, then IP and route, then the reason when something is wrong. Off is just "VPN Off".
     */
    public String tooltipHtml() {
        if (state == State.OFF) return "<html><b>VPN Off</b></html>";
        StringBuilder b = new StringBuilder("<html><b>");
        if (state == State.PROTECTED) {
            b.append("VPN On");
            if (!country.isEmpty()) b.append(" — ").append(esc(country));
        } else {
            b.append("VPN ").append(esc(state.label));
            if (!country.isEmpty()) b.append(" — ").append(esc(country));
        }
        b.append("</b>");
        StringBuilder where = new StringBuilder();
        if (!ip.isEmpty()) where.append(esc(ip));
        if (!route.isEmpty()) where.append(where.length() > 0 ? " · " : "").append(esc(route));
        if (where.length() > 0) b.append("<br>").append(where);
        if (state != State.PROTECTED && !reason.isEmpty()) b.append("<br>").append(esc(reason));
        return b.append("</html>").toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof VpnGuardStatus)) return false;
        VpnGuardStatus v = (VpnGuardStatus) o;
        return sameAs(v) && reason.equals(v.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, ip, country, route, appRunning, service);
    }

    @Override
    public String toString() {
        return "VpnGuardStatus[" + summary() + "]";
    }
}
