package com.srd.common.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** Fixtures mirror the real C:\ProgramData\VPNGuard\status.json format (IPs replaced by test ranges). */
class VpnGuardStatusTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 13, 0, 10);

    private static String json(String state, boolean verifying, String alive) {
        return "{\n"
                + "  \"alive\": \"" + alive + "\",\n"
                + "  \"checked\": \"2026-10-14 05:16:58.740\",\n"
                + "  \"ip\": \"203.0.113.60\",\n"
                + "  \"known_bad_country\": null,\n"
                + "  \"known_bad_ip\": null,\n"
                + "  \"path\": \"if=13 via=192.168.8.1 src=192.168.8.209\",\n"
                + "  \"path_name\": \"Wi-Fi\",\n"
                + "  \"permitted_path\": \"if=13 via=192.168.8.1 src=192.168.8.209\",\n"
                + "  \"probes\": [\n"
                + "    {\"country\": \"US\", \"error\": null, \"ip\": \"203.0.113.60\", \"provider\": \"cloudflare\"},\n"
                + "    {\"country\": \"US\", \"error\": null, \"ip\": \"203.0.113.60\", \"provider\": \"ipinfo\"}\n"
                + "  ],\n"
                + "  \"reason\": \"2/2 say allowed [cloudflare=US ipinfo=US]\",\n"
                + "  \"since\": \"2026-10-14 05:16:58.755\",\n"
                + "  \"state\": \"" + state + "\",\n"
                + "  \"verifying\": " + verifying + ",\n"
                + "  \"vpn_apps_permitted\": 47\n"
                + "}";
    }

    @Test
    void protectedWhenServiceRunningAndAllowed() {
        VpnGuardStatus s = VpnGuardStatus.derive(true, "RUNNING", json("ALLOWED", false, "2026-10-14 13:00:00.218"), true, NOW);
        assertEquals(VpnGuardStatus.State.PROTECTED, s.state);
        assertEquals("US", s.country);
        assertEquals("203.0.113.60", s.ip);
        assertEquals("Wi-Fi", s.route);
        assertEquals(9, s.aliveAgeSec);
        assertFalse(s.state.alert);
    }

    @Test
    void runningServiceIsAlwaysProtected() {
        // only "service stopped" is reported as a problem: blocking or re-checking is still "running"
        assertEquals(VpnGuardStatus.State.PROTECTED,
                VpnGuardStatus.derive(true, "RUNNING", json("BLOCKED", false, "2026-10-14 13:00:05"), true, NOW).state);
        assertEquals(VpnGuardStatus.State.PROTECTED,
                VpnGuardStatus.derive(true, "RUNNING", json("BLOCKED", true, "2026-10-14 13:00:05"), true, NOW).state);
        assertFalse(VpnGuardStatus.State.BLOCKED.alert);
        assertFalse(VpnGuardStatus.State.STALE.alert);
        assertFalse(VpnGuardStatus.State.CHECKING.alert);
    }

    @Test
    void offWhenServiceStoppedEvenIfFileLooksFine() {
        VpnGuardStatus s = VpnGuardStatus.derive(true, "STOPPED", json("ALLOWED", false, "2026-10-14 13:00:05"), true, NOW);
        assertEquals(VpnGuardStatus.State.OFF, s.state);
        assertTrue(s.state.alert);
    }

    @Test
    void staleHeartbeat() {
        String old = json("ALLOWED", false, "2026-10-14 12:50:00.000");
        assertEquals(VpnGuardStatus.State.PROTECTED, VpnGuardStatus.derive(true, "RUNNING", old, true, NOW).state);
        // service state unknown + frozen file → most likely stopped
        assertEquals(VpnGuardStatus.State.OFF, VpnGuardStatus.derive(true, "", old, false, NOW).state);
    }

    @Test
    void notInstalledAndHalfWrittenFile() {
        assertEquals(VpnGuardStatus.State.NOT_INSTALLED, VpnGuardStatus.derive(false, "", null, false, NOW).state);
        assertEquals(VpnGuardStatus.State.PROTECTED, VpnGuardStatus.derive(true, "RUNNING", "{\"alive\": \"2026-", true, NOW).state);
    }

    @Test
    void wireRoundTripAndChangeDetection() {
        VpnGuardStatus s = VpnGuardStatus.derive(true, "RUNNING", json("ALLOWED", false, "2026-10-14 13:00:00"), true, NOW);
        VpnGuardStatus back = VpnGuardStatus.fromMap(StatusMessage.decode(StatusMessage.encode(s.toMap())));
        assertNotNull(back);
        assertTrue(s.sameAs(back));
        assertEquals(s.reason, back.reason);
        VpnGuardStatus later = VpnGuardStatus.derive(true, "RUNNING", json("ALLOWED", false, "2026-10-14 13:00:08"), true, NOW);
        assertTrue(s.sameAs(later), "only the heartbeat age changed");
        assertTrue(s.tooltipHtml().contains("Wi-Fi"));
    }

    @Test
    void tooltipIsShortAndOffIsJustOff() {
        assertEquals("<html><b>VPN Off</b></html>", VpnGuardStatus.of(VpnGuardStatus.State.OFF).tooltipHtml());
        VpnGuardStatus on = new VpnGuardStatus(VpnGuardStatus.State.PROTECTED, "1.2.3.4", "US", "Wi-Fi", "", "2026-10-14 13:00:00", 3, true, "RUNNING");
        String tip = on.tooltipHtml();
        assertTrue(tip.contains("US") && tip.contains("1.2.3.4"));
        assertTrue(tip.split("<br>").length <= 3, tip);
    }

    @Test
    void miniJsonHandlesEscapesAndNesting() {
        Object o = MiniJson.parse("{\"a\": [1, 2.5, -3e2, true, null, \"x\\\"y\\u00e9\"], \"b\": {}}");
        assertTrue(o instanceof java.util.Map);
        assertEquals("x\"yé", ((java.util.List<?>) ((java.util.Map<?, ?>) o).get("a")).get(5));
    }
}
