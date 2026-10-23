package com.srd.client.vpn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srd.common.status.VpnGuardStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** Output format of {@code vpnguard status} as printed by VPN Guard 0.4.2 (IPs replaced). */
class VpnGuardMonitorTest {
    static final String OUTPUT = "service: Running\r\n"
            + "VPN clients that may reconnect while blocked:\r\n"
            + "  C:\\Program Files\\ExpressVPN\r\n"
            + "{\r\n"
            + "  \"alive\": \"2026-10-14 15:23:55.926\",\r\n"
            + "  \"ip\": \"203.0.113.60\",\r\n"
            + "  \"path_name\": \"Wi-Fi\",\r\n"
            + "  \"probes\": [\r\n"
            + "    {\r\n      \"country\": \"US\",\r\n      \"error\": null,\r\n      \"ip\": \"203.0.113.60\",\r\n      \"provider\": \"cloudflare\"\r\n    }\r\n"
            + "  ],\r\n"
            + "  \"reason\": \"1/1 say allowed [cloudflare=US]\",\r\n"
            + "  \"since\": \"2026-10-14 05:16:58.755\",\r\n"
            + "  \"state\": \"ALLOWED\",\r\n"
            + "  \"verifying\": false\r\n"
            + "}\r\n"
            + "--- last log lines\r\n"
            + "2026-10-13 20:52:47.747 route at start: Wi-Fi\r\n"
            + "2026-10-14 05:16:53.486 BLOCKED  internet route moved {x}\r\n";

    @Test
    void parsesServiceAndJsonFromCliOutput() {
        VpnGuardMonitor.CliStatus st = VpnGuardMonitor.parseStatus(OUTPUT);
        assertNotNull(st);
        assertEquals("RUNNING", st.service);
        assertTrue(st.json.trim().startsWith("{") && st.json.trim().endsWith("}"));
        VpnGuardStatus s = VpnGuardStatus.derive(true, st.service, st.json, true, LocalDateTime.of(2026, 10, 14, 15, 24, 0));
        assertEquals(VpnGuardStatus.State.PROTECTED, s.state);
        assertEquals("US", s.country);
        assertEquals("Wi-Fi", s.route);
    }

    @Test
    void stoppedServiceIsOff() {
        VpnGuardMonitor.CliStatus st = VpnGuardMonitor.parseStatus(OUTPUT.replace("service: Running", "service: Stopped"));
        VpnGuardStatus s = VpnGuardStatus.derive(true, st.service, st.json, true, LocalDateTime.of(2026, 10, 14, 15, 24, 0));
        assertEquals(VpnGuardStatus.State.OFF, s.state);
    }

    @Test
    void serviceOnlyAndGarbage() {
        VpnGuardMonitor.CliStatus st = VpnGuardMonitor.parseStatus("service: not installed\n(no status.json yet)\n");
        assertEquals("MISSING", st.service);
        assertNull(st.json);
        assertNull(VpnGuardMonitor.parseStatus("something unrelated"));
        assertEquals("STOPPED", VpnGuardMonitor.normalizeService("Stopped (manual start)"));
        assertEquals("START_PENDING", VpnGuardMonitor.normalizeService("StartPending"));
    }

    @Test
    void macDaemonLineIsRead() {
        // macOS prints "daemon:" instead of "service:"; a stopped daemon must be Off at once, not
        // after its frozen status.json turns stale.
        String mac = OUTPUT.replace("service: Running", "daemon: Stopped");
        VpnGuardMonitor.CliStatus st = VpnGuardMonitor.parseStatus(mac);
        assertEquals("STOPPED", st.service);
        VpnGuardStatus s = VpnGuardStatus.derive(true, st.service, st.json, true, LocalDateTime.of(2026, 10, 14, 15, 24, 0));
        assertEquals(VpnGuardStatus.State.OFF, s.state);
        assertEquals("RUNNING", VpnGuardMonitor.parseStatus(OUTPUT.replace("service: Running", "daemon: Running")).service);
        assertEquals("MISSING", VpnGuardMonitor.normalizeService("NotInstalled"));
    }

    @Test
    void onlyOnOffIsReported() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 14, 15, 24, 0);
        String json = VpnGuardMonitor.parseStatus(OUTPUT).json;
        // Running but blocking the internet / re-verifying / heartbeat late: still On.
        for (String variant : new String[] {json.replace("\"ALLOWED\"", "\"BLOCKED\""),
                json.replace("\"verifying\": false", "\"verifying\": true"),
                json.replace("2026-10-14 15:23:55.926", "2026-10-14 15:00:00.000")}) {
            VpnGuardStatus s = VpnGuardMonitor.onOff(VpnGuardStatus.derive(true, "RUNNING", variant, true, now));
            assertEquals(VpnGuardStatus.State.PROTECTED, s.state, variant);
        }
        // Service stopped: Off.
        assertEquals(VpnGuardStatus.State.OFF,
                VpnGuardMonitor.onOff(VpnGuardStatus.derive(true, "STOPPED", json, true, now)).state);
    }

    @Test
    void macTrayAppWithSpaceInBundleName() {
        assertTrue(VpnGuardMonitor.isTrayApp("/Applications/VPN Guard.app/Contents/MacOS/VPN Guard"));
        assertTrue(VpnGuardMonitor.isTrayApp("C:\\Program Files\\VPNGuard\\vpnguard-app.exe"));
        assertTrue(!VpnGuardMonitor.isTrayApp("/Applications/VPN Guard.app/Contents/MacOS/vpnguard"));
    }
}
