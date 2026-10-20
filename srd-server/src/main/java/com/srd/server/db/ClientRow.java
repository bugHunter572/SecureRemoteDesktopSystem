package com.srd.server.db;

/** One row of the {@code clients} table. */
public final class ClientRow {
    public final String clientId;
    public final String displayName;
    public final String ipAddress;
    public final long lastSeen;
    public final boolean notificationEnabled;
    public final String screenshotDir;
    public final String recordingDir;
    public final String vpnState;
    /** "Today's Records" cell text, filled in when loading for the UI. */
    public String recordsCell = "";

    public ClientRow(String clientId, String displayName, String ipAddress, long lastSeen, boolean notificationEnabled,
                     String screenshotDir, String recordingDir, String vpnState) {
        this.clientId = clientId;
        this.displayName = displayName;
        this.ipAddress = ipAddress;
        this.lastSeen = lastSeen;
        this.notificationEnabled = notificationEnabled;
        this.screenshotDir = screenshotDir;
        this.recordingDir = recordingDir;
        this.vpnState = vpnState;
    }
}
