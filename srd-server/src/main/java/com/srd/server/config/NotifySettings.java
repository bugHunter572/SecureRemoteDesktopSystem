package com.srd.server.config;

import java.util.Map;

/**
 * Notification behaviour, editable in Edit → Notification Settings. Stored as {@code notify.*}
 * rows next to the other server settings (see {@link ServerConfig}). Plain mutable bean; the
 * server swaps in a fresh copy on every change.
 */
public final class NotifySettings {
    /** No built-in webhook: set it in the settings dialog (never commit a real one). */
    public static final String DEFAULT_WEBHOOK = "";

    /** No built-in Mattermost server or bot token: set them in the settings dialog (never commit real ones). */
    public static final String DEFAULT_BOT_SERVER = "";
    public static final String DEFAULT_BOT_TOKEN = "";
    public static final String DEFAULT_BOT_CHANNEL = "screenapp";

    public static final String DEFAULT_TAGS = String.join("\n",
            "@acs 192.168.110.100 ~ 104",
            "@cdr 192.168.110.105 ~ 109",
            "@rtr 192.168.110.110 ~ 114",
            "@jrs 192.168.110.115 ~ 119",
            "@yus 192.168.110.120 ~ 124",
            "@rjs 192.168.110.125 ~ 129",
            "@kws 192.168.110.130 ~ 134",
            "@ujb 192.168.110.135 ~ 139",
            "@whs 192.168.110.140 ~ 144",
            "@kjs 192.168.110.145 ~ 149",
            "@khn 192.168.110.150 ~ 154",
            "@ksg 192.168.110.155 ~ 159");

    public boolean popups = true;
    public boolean beep = true;
    public int popupDelaySec = 20;

    public boolean alertDisconnected = true;
    public boolean alertNoScreen = true;
    public boolean alertVpn = true;
    public boolean alertVpnNotInstalled = false;
    /** A client closed on purpose (tray Quit) is a quiet info pop-up unless this is on. */
    public boolean alertUserQuit = false;
    public boolean recoveryNotices = true;
    /** Also show the notice on the affected client PC (only possible while it is connected). */
    public boolean notifyClients = true;

    public boolean mattermost = true;
    public String webhookUrl = DEFAULT_WEBHOOK;
    /** Bot account: posts go through the REST API with this token (a webhook is used only while the token is empty). */
    public String botServer = DEFAULT_BOT_SERVER;
    public String botToken = DEFAULT_BOT_TOKEN;
    /** Channel id, or channel name / display name (the bot must be a member). */
    public String botChannel = DEFAULT_BOT_CHANNEL;
    public int mattermostDelaySec = 60;
    /** Problems this close to the delay ride along in the same post. */
    public int mattermostGroupSec = 5;
    /** While a client stays "ScreenApp Disconnected", post it again this often: -1 = the screen interval, 0 = once only. */
    public int mattermostRepeatSec = -1;
    /** Who to tag per client IP range, one line each: "@tag first-ip ~ last". See TagMap. */
    public String tags = DEFAULT_TAGS;

    public NotifySettings copy() {
        return fromMap(toMap());
    }

    public Map<String, String> toMap() {
        Map<String, String> m = new java.util.HashMap<>();
        m.put("notify.popups", String.valueOf(popups));
        m.put("notify.beep", String.valueOf(beep));
        m.put("notify.popupDelay", String.valueOf(popupDelaySec));
        m.put("notify.disconnected", String.valueOf(alertDisconnected));
        m.put("notify.noScreen", String.valueOf(alertNoScreen));
        m.put("notify.vpn", String.valueOf(alertVpn));
        m.put("notify.vpnNotInstalled", String.valueOf(alertVpnNotInstalled));
        m.put("notify.userQuit", String.valueOf(alertUserQuit));
        m.put("notify.recovery", String.valueOf(recoveryNotices));
        m.put("notify.clients", String.valueOf(notifyClients));
        m.put("notify.mattermost", String.valueOf(mattermost));
        m.put("notify.webhook", webhookUrl);
        m.put("notify.botServer", botServer);
        m.put("notify.botToken", botToken);
        m.put("notify.botChannel", botChannel);
        m.put("notify.mattermostDelay", String.valueOf(mattermostDelaySec));
        m.put("notify.mattermostGroup", String.valueOf(mattermostGroupSec));
        m.put("notify.mattermostRepeat", String.valueOf(mattermostRepeatSec));
        m.put("notify.tags", tags);
        return m;
    }

    public static NotifySettings fromMap(Map<String, String> m) {
        NotifySettings s = new NotifySettings();
        s.popups = bool(m, "notify.popups", s.popups);
        s.beep = bool(m, "notify.beep", s.beep);
        s.popupDelaySec = num(m, "notify.popupDelay", s.popupDelaySec, 0, 3600);
        s.alertDisconnected = bool(m, "notify.disconnected", s.alertDisconnected);
        s.alertNoScreen = bool(m, "notify.noScreen", s.alertNoScreen);
        s.alertVpn = bool(m, "notify.vpn", s.alertVpn);
        s.alertVpnNotInstalled = bool(m, "notify.vpnNotInstalled", s.alertVpnNotInstalled);
        s.alertUserQuit = bool(m, "notify.userQuit", s.alertUserQuit);
        s.recoveryNotices = bool(m, "notify.recovery", s.recoveryNotices);
        s.notifyClients = bool(m, "notify.clients", s.notifyClients);
        s.mattermost = bool(m, "notify.mattermost", s.mattermost);
        String url = m.get("notify.webhook");
        if (url != null && !url.trim().isEmpty()) s.webhookUrl = url.trim();
        if (m.get("notify.botServer") != null) s.botServer = m.get("notify.botServer").trim();
        if (m.get("notify.botToken") != null) s.botToken = m.get("notify.botToken").trim();
        if (m.get("notify.botChannel") != null) s.botChannel = m.get("notify.botChannel").trim();
        s.mattermostDelaySec = num(m, "notify.mattermostDelay", s.mattermostDelaySec, 0, 86400);
        s.mattermostGroupSec = num(m, "notify.mattermostGroup", s.mattermostGroupSec, 0, 600);
        s.mattermostRepeatSec = num(m, "notify.mattermostRepeat", s.mattermostRepeatSec, -1, 86400);
        String tags = m.get("notify.tags");
        if (tags != null) s.tags = tags.trim();
        return s;
    }

    private static boolean bool(Map<String, String> m, String k, boolean def) {
        String v = m.get(k);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    private static int num(Map<String, String> m, String k, int def, int min, int max) {
        try {
            String v = m.get(k);
            return v == null ? def : Math.max(min, Math.min(max, Integer.parseInt(v.trim())));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Where Mattermost posts go now. */
    public com.srd.server.alerts.Mattermost.Target mattermostTarget() {
        return new com.srd.server.alerts.Mattermost.Target(webhookUrl, botServer, botToken, botChannel);
    }

    /** Bot settings complete, or else a valid webhook. */
    public boolean mattermostConfigured() {
        return mattermostTarget().isBot() ? isValidWebhook(botServer) : isValidWebhook(webhookUrl);
    }

    /** True for an http(s) URL; the dialog refuses anything else. */
    public static boolean isValidWebhook(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase();
        return (u.startsWith("https://") || u.startsWith("http://")) && u.length() > 10 && u.indexOf(' ') < 0;
    }
}
