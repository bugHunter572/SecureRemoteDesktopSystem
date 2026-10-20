package com.srd.server.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server behaviour settings, persisted as key/value rows in the {@code server_config} table.
 * Moved unchanged from the old monolith, plus the protocol-v3 pre-shared key.
 */
public class ServerConfig {
    /** Same text as the old static AES key, so a fresh server matches the clients' default. */
    public static final String DEFAULT_PSK = "MySecretKey12345";

    public static final String KEY_AUTO_ADD = "AUTO_ADD_CLIENTS";
    public static final String KEY_SCREEN_CONTROL = "SCREEN_CONTROL_ENABLED";
    public static final String KEY_SCREENSHOT_SAVE = "SCREENSHOT_SAVE_ENABLED";
    public static final String KEY_INTERVAL = "SCREENSHOT_INTERVAL";
    public static final String KEY_IMAGE_QUALITY = "IMAGE_QUALITY";
    public static final String KEY_ALT_IPS = "ALTERNATIVE_IPS";
    public static final String KEY_THUMB_WIDTH = "THUMB_MAX_WIDTH";
    public static final String KEY_PSK = "security.psk";

    private boolean autoAddClients = true;
    private boolean screenControlEnabled = true;
    private boolean screenshotSaveEnabled = true;
    private List<String> alternativeIPs = new ArrayList<>();
    private int screenshotInterval = 60; // in seconds
    private float imageQuality = 0.85f; // JPEG quality (0.1-1.0) of client snapshots (thumbnails / idle screenshots)
    private int thumbMaxWidth = 1600; // widest thumbnail kept per client, px
    private String psk = DEFAULT_PSK;
    private NotifySettings notify = new NotifySettings();

    public NotifySettings getNotify() {
        return notify;
    }

    public void setNotify(NotifySettings notify) {
        this.notify = notify == null ? new NotifySettings() : notify;
    }

    public boolean isAutoAddClients() {
        return autoAddClients;
    }

    public void setAutoAddClients(boolean autoAddClients) {
        this.autoAddClients = autoAddClients;
    }

    public boolean isScreenControlEnabled() {
        return screenControlEnabled;
    }

    public void setScreenControlEnabled(boolean screenControlEnabled) {
        this.screenControlEnabled = screenControlEnabled;
    }

    public boolean isScreenshotSaveEnabled() {
        return screenshotSaveEnabled;
    }

    public void setScreenshotSaveEnabled(boolean screenshotSaveEnabled) {
        this.screenshotSaveEnabled = screenshotSaveEnabled;
    }

    public List<String> getAlternativeIPs() {
        return alternativeIPs;
    }

    public void setAlternativeIPs(List<String> alternativeIPs) {
        this.alternativeIPs = alternativeIPs == null ? new ArrayList<>() : alternativeIPs;
    }

    public int getScreenshotInterval() {
        return screenshotInterval;
    }

    public void setScreenshotInterval(int screenshotInterval) {
        this.screenshotInterval = screenshotInterval;
    }

    public float getImageQuality() {
        return imageQuality;
    }

    public void setImageQuality(float imageQuality) {
        this.imageQuality = Math.max(0.1f, Math.min(1.0f, imageQuality));
    }

    public int getThumbMaxWidth() {
        return thumbMaxWidth;
    }

    public void setThumbMaxWidth(int px) {
        this.thumbMaxWidth = Math.max(320, Math.min(2560, px));
    }

    public String getPsk() {
        return psk;
    }

    public void setPsk(String psk) {
        this.psk = (psk == null || psk.isEmpty()) ? DEFAULT_PSK : psk;
    }

    /** The CONTROL text sent to every client on connect and after a config change (format unchanged from v2). */
    public String toServerConfigCommand() {
        StringBuilder configCommand = new StringBuilder("SERVER_CONFIG:");
        configCommand.append("AUTO_ADD=").append(autoAddClients ? "1" : "0").append(";");
        configCommand.append("SCREEN_CONTROL=").append(screenControlEnabled ? "1" : "0").append(";");
        configCommand.append("SCREENSHOT_SAVE=").append(screenshotSaveEnabled ? "1" : "0").append(";");
        configCommand.append("IMAGE_QUALITY=").append(imageQuality).append(";");
        configCommand.append("ALTERNATIVE_IPS=").append(String.join(",", alternativeIPs)).append(";");
        configCommand.append("CLIENT_NOTIFY=").append(notify.notifyClients ? "1" : "0").append(";");
        configCommand.append("NOTIFY_DELAY=").append(notify.popupDelaySec);
        return configCommand.toString();
    }

    public String toIntervalCommand() {
        return "SET_INTERVAL:" + screenshotInterval;
    }

    public ServerConfig copy() {
        return fromDatabase(toDatabaseMap());
    }

    @Override
    public String toString() {
        return String.format(
                "ServerConfig[autoAdd=%s, screenControl=%s, screenshotSave=%s, interval=%d, imageQuality=%.2f, altIPs=%s, psk=%s]",
                autoAddClients, screenControlEnabled, screenshotSaveEnabled, screenshotInterval, imageQuality,
                alternativeIPs, DEFAULT_PSK.equals(psk) ? "default" : "custom");
    }

    public Map<String, String> toDatabaseMap() {
        Map<String, String> map = new HashMap<>();
        map.put(KEY_AUTO_ADD, String.valueOf(autoAddClients));
        map.put(KEY_SCREEN_CONTROL, String.valueOf(screenControlEnabled));
        map.put(KEY_SCREENSHOT_SAVE, String.valueOf(screenshotSaveEnabled));
        map.put(KEY_INTERVAL, String.valueOf(screenshotInterval));
        map.put(KEY_IMAGE_QUALITY, String.valueOf(imageQuality));
        map.put(KEY_ALT_IPS, String.join(",", alternativeIPs));
        map.put(KEY_THUMB_WIDTH, String.valueOf(thumbMaxWidth));
        map.put(KEY_PSK, psk);
        map.putAll(notify.toMap());
        return map;
    }

    public static ServerConfig fromDatabase(Map<String, String> data) {
        ServerConfig config = new ServerConfig();
        if (data.containsKey(KEY_AUTO_ADD)) {
            config.setAutoAddClients(Boolean.parseBoolean(data.get(KEY_AUTO_ADD)));
        }
        if (data.containsKey(KEY_SCREEN_CONTROL)) {
            config.setScreenControlEnabled(Boolean.parseBoolean(data.get(KEY_SCREEN_CONTROL)));
        }
        if (data.containsKey(KEY_SCREENSHOT_SAVE)) {
            config.setScreenshotSaveEnabled(Boolean.parseBoolean(data.get(KEY_SCREENSHOT_SAVE)));
        }
        if (data.containsKey(KEY_INTERVAL)) {
            try {
                config.setScreenshotInterval(Integer.parseInt(data.get(KEY_INTERVAL)));
            } catch (NumberFormatException e) {
                config.setScreenshotInterval(60);
            }
        }
        if (data.containsKey(KEY_IMAGE_QUALITY)) {
            try {
                config.setImageQuality(Float.parseFloat(data.get(KEY_IMAGE_QUALITY)));
            } catch (NumberFormatException e) {
                config.setImageQuality(0.75f);
            }
        }
        if (data.containsKey(KEY_ALT_IPS)) {
            String ips = data.get(KEY_ALT_IPS);
            if (ips != null && !ips.isEmpty()) {
                config.setAlternativeIPs(new ArrayList<>(Arrays.asList(ips.split(","))));
            }
        }
        if (data.containsKey(KEY_THUMB_WIDTH)) {
            try {
                config.setThumbMaxWidth(Integer.parseInt(data.get(KEY_THUMB_WIDTH).trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        if (data.containsKey(KEY_PSK)) {
            config.setPsk(data.get(KEY_PSK));
        }
        config.setNotify(NotifySettings.fromMap(data));
        return config;
    }

    /** Dotted IPv4 check used by the config dialog (unchanged). */
    public static boolean isValidIP(String ip) {
        try {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) return false;
            for (String part : parts) {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
