package com.srd.server.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srd.server.alerts.AlertTracker.Due;
import com.srd.server.alerts.AlertTracker.Kind;
import com.srd.server.config.NotifySettings;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AlertTrackerTest {
    private static final long S = 1000;
    private static final AlertTracker.Gate ALL = p -> true;

    private static Due tick(AlertTracker t, long nowSec) {
        return t.tick(nowSec * S, 20 * S, 60 * S, 5 * S, ALL);
    }

    @Test
    void popupAfterDelayThenMattermostAfterMinute_eachOnce() {
        AlertTracker t = new AlertTracker();
        t.raise("10.0.0.1", Kind.DISCONNECTED, "lost", 0);
        assertTrue(tick(t, 19).isEmpty());
        assertEquals(1, tick(t, 20).popups.size());
        assertTrue(tick(t, 21).isEmpty());
        assertTrue(tick(t, 59).mattermost.isEmpty());
        assertEquals(1, tick(t, 60).mattermost.size());
        assertTrue(tick(t, 120).isEmpty());
    }

    @Test
    void disconnectedAndNoScreenAreRepeated_vpnIsNot() {
        AlertTracker t = new AlertTracker();
        t.raise("10.0.0.1", Kind.DISCONNECTED, "lost", 0);
        t.raise("10.0.0.2", Kind.NO_SCREEN, "no pic", 0);
        t.raise("10.0.0.3", Kind.VPN, "off", 0);
        assertEquals(3, t.tick(60 * S, 20 * S, 60 * S, 5 * S, 300 * S, ALL).mattermost.size());
        assertTrue(t.tick(359 * S, 20 * S, 60 * S, 5 * S, 300 * S, ALL).reminders.isEmpty());
        Due d = t.tick(360 * S, 20 * S, 60 * S, 5 * S, 300 * S, ALL);
        assertEquals(2, d.reminders.size());
        assertEquals("10.0.0.1", d.reminders.get(0).clientId);
        assertEquals("10.0.0.2", d.reminders.get(1).clientId);
        assertTrue(t.tick(659 * S, 20 * S, 60 * S, 5 * S, 300 * S, ALL).reminders.isEmpty());
        assertEquals(2, t.tick(660 * S, 20 * S, 60 * S, 5 * S, 300 * S, ALL).reminders.size());
        assertTrue(t.tick(1000 * S, 20 * S, 60 * S, 5 * S, 0, ALL).reminders.isEmpty()); // 0 = once only
    }

    @Test
    void severalClientsGoOutInOnePost() {
        AlertTracker t = new AlertTracker();
        t.raise("a", Kind.DISCONNECTED, "lost", 0);
        t.raise("b", Kind.NO_SCREEN, "no frames", 3 * S);
        t.raise("c", Kind.VPN, "off", 30 * S);
        Due d = tick(t, 60);
        // a is due; b (57 s old) is within the 5 s group window; c (30 s old) is not and gets its own post later.
        assertEquals(2, d.mattermost.size());
        assertEquals(1, tick(t, 90).mattermost.size());
    }

    @Test
    void problemFixedInsideTheDelayStaysSilent() {
        AlertTracker t = new AlertTracker();
        t.raise("a", Kind.DISCONNECTED, "lost", 0);
        t.resolve("a", Kind.DISCONNECTED, "back", 10 * S);
        assertTrue(tick(t, 30).isEmpty());
    }

    @Test
    void disconnectedHeldBackWhileReachable_announcedOnceUnreachable_thenConnectedOnRecovery() {
        AlertTracker t = new AlertTracker();
        boolean[] unreachable = {false};
        AlertTracker.Gate gate = p -> unreachable[0];
        t.raise("a", Kind.DISCONNECTED, "lost", 0);
        assertTrue(t.tick(70 * S, 20 * S, 60 * S, 5 * S, gate).isEmpty()); // PC answers ping: no alert
        assertEquals(java.util.List.of("a"), t.openIds(Kind.DISCONNECTED));
        unreachable[0] = true;
        Due d = t.tick(80 * S, 20 * S, 60 * S, 5 * S, gate); // PC stops answering
        assertEquals(1, d.mattermost.size());
        unreachable[0] = false; // PC is back, app not yet: nothing new
        assertTrue(t.tick(90 * S, 20 * S, 60 * S, 5 * S, gate).isEmpty());
        t.resolve("a", Kind.DISCONNECTED, "Reconnected", 100 * S); // app connected and delivered a picture
        Due back = t.tick(101 * S, 20 * S, 60 * S, 5 * S, gate);
        assertEquals(1, back.recoveries.size());
        assertTrue(back.recoveries.get(0).toMattermost);
    }

    @Test
    void recoveryOnlyForWhatWasAnnounced() {
        AlertTracker t = new AlertTracker();
        t.raise("a", Kind.VPN, "off", 0);
        tick(t, 20); // popup only
        t.resolve("a", Kind.VPN, "protected", 30 * S);
        Due d = tick(t, 31);
        assertEquals(1, d.recoveries.size());
        assertTrue(d.recoveries.get(0).toLocal);
        assertFalse(d.recoveries.get(0).toMattermost);
        assertEquals(30 * S, d.recoveries.get(0).downMs);

        t.raise("b", Kind.VPN, "off", 0);
        tick(t, 61); // popup + Mattermost
        t.resolve("b", Kind.VPN, "protected", 70 * S);
        assertTrue(tick(t, 71).recoveries.get(0).toMattermost);
    }

    @Test
    void noScreenAfterReconnectContinuesTheDisconnectedProblem() {
        AlertTracker t = new AlertTracker();
        t.raise("a", Kind.DISCONNECTED, "lost", 0);
        tick(t, 25); // announced
        t.raise("a", Kind.NO_SCREEN, "connected, no screen", 40 * S);
        assertFalse(t.has("a", Kind.DISCONNECTED));
        assertTrue(t.has("a", Kind.NO_SCREEN));
        assertEquals(1, t.openCount());
        assertTrue(tick(t, 41).popups.isEmpty()); // already announced, not again
        assertEquals(1, tick(t, 60).mattermost.size()); // original clock keeps running
    }

    @Test
    void gateHoldsBackSilencedClientsWithoutLosingThem() {
        AlertTracker t = new AlertTracker();
        t.raise("a", Kind.DISCONNECTED, "lost", 0);
        assertTrue(t.tick(25 * S, 20 * S, 60 * S, 5 * S, p -> false).isEmpty());
        assertEquals(1, t.tick(26 * S, 20 * S, 60 * S, 5 * S, ALL).popups.size());
    }

    @Test
    void disabledChannelsNeverFire() {
        AlertTracker t = new AlertTracker();
        t.raise("a", Kind.DISCONNECTED, "lost", 0);
        Due d = t.tick(500 * S, -1, -1, 0, ALL);
        assertTrue(d.isEmpty());
    }

    @Test
    void settingsRoundTripAndDefaults() {
        NotifySettings s = new NotifySettings();
        assertEquals(20, s.popupDelaySec);
        assertEquals(60, s.mattermostDelaySec);
        assertEquals(NotifySettings.DEFAULT_WEBHOOK, s.webhookUrl);
        s.webhookUrl = "https://chat.example/hooks/abc";
        s.alertUserQuit = true;
        s.mattermostDelaySec = 90;
        NotifySettings back = NotifySettings.fromMap(s.toMap());
        assertEquals("https://chat.example/hooks/abc", back.webhookUrl);
        assertTrue(back.alertUserQuit);
        assertEquals(90, back.mattermostDelaySec);
        Map<String, String> junk = new HashMap<>();
        junk.put("notify.popupDelay", "abc");
        junk.put("notify.mattermostDelay", "-5");
        NotifySettings d = NotifySettings.fromMap(junk);
        assertEquals(20, d.popupDelaySec);
        assertEquals(0, d.mattermostDelaySec);
        assertFalse(NotifySettings.isValidWebhook("ftp://x"));
        assertTrue(NotifySettings.isValidWebhook("https://a.b/hooks/c"));
    }

    @Test
    void mattermostTableHasTagNameIpStatusWhen() {
        java.util.List<Mattermost.Row> rows = new java.util.ArrayList<>();
        rows.add(new Mattermost.Row("@kws", "Finance|3", "192.168.110.131", Kind.VPN.emoji + " " + Kind.VPN.label, "2026-10-15 20:11:30"));
        rows.add(new Mattermost.Row("@acs", "Reception PC", "192.168.110.100", Kind.DISCONNECTED.emoji + " " + Kind.DISCONNECTED.label, "2026-10-15 20:10:04"));
        java.util.List<String> posts = Mattermost.formatTables(rows);
        assertEquals(1, posts.size());
        String msg = posts.get(0);
        assertTrue(msg.startsWith("| Tag | Display Name | IP | Status | When |\n"));
        assertTrue(msg.indexOf("Reception PC") < msg.indexOf("Finance"), "oldest first");
        assertTrue(msg.contains("| @acs | Reception PC | 192.168.110.100 | " + Kind.DISCONNECTED.emoji + " ScreenApp Disconnected | 2026-10-15 20:10:04 |"));
        assertTrue(msg.contains("Finance\\|3"), "pipes escaped inside table cells");
        assertEquals("1m 04s", Mattermost.duration(64_000));
        assertEquals("\"a\\\"b\\n\"", Mattermost.jsonString("a\"b\n"));
        assertEquals("2026-10-15 20:10:04", AlertService.when(java.time.LocalDateTime.of(2026, 10, 15, 20, 10, 4)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()));
    }

    @Test
    void longListsAreSplitIntoSeveralPosts() {
        java.util.List<Mattermost.Row> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 250; i++) rows.add(new Mattermost.Row("", "PC" + i, "10.0.0." + (i % 250), "x", "2026-01-01 00:00:00"));
        java.util.List<String> posts = Mattermost.formatTables(rows);
        assertEquals(3, posts.size());
        for (String post : posts) assertTrue(post.length() < 16000);
    }

    @Test
    void tagsFollowTheIpRanges() {
        TagMap m = TagMap.parse(NotifySettings.DEFAULT_TAGS + "\n@zzz: 10.0.0.5 - 10.0.0.7\n@one 10.1.1.1\nnonsense");
        assertEquals("@acs", m.tagFor("192.168.110.100"));
        assertEquals("@acs", m.tagFor("192.168.110.104"));
        assertEquals("@cdr", m.tagFor("192.168.110.105"));
        assertEquals("@ksg", m.tagFor("192.168.110.159"));
        assertEquals("", m.tagFor("192.168.110.160"));
        assertEquals("", m.tagFor("192.168.110.99"));
        assertEquals("@zzz", m.tagFor("10.0.0.6"));
        assertEquals("@one", m.tagFor("10.1.1.1"));
        assertEquals("", m.tagFor("not an ip"));
        assertEquals("@kws", TagMap.parse("@kws: 192.168.110.130 - 134").tagFor("192.168.110.133"));
    }

    @Test
    void raiseReportsWhetherTheProblemIsNew() {
        AlertTracker t = new AlertTracker();
        assertTrue(t.raise("a", Kind.VPN, "off", 0));
        assertFalse(t.raise("a", Kind.VPN, "still off", 5));
    }
}
