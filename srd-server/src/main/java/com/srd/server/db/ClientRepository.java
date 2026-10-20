package com.srd.server.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** {@code clients} table. Blocking: call on the db thread (see {@link Database#execute}). */
public final class ClientRepository {
    private final Database db;

    public ClientRepository(Database db) {
        this.db = db;
    }

    public List<ClientRow> loadAll() throws SQLException {
        List<ClientRow> rows = new ArrayList<>();
        try (PreparedStatement pstmt = db.connection().prepareStatement("SELECT * FROM clients");
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                // As in v2 the client is keyed by its IP address column.
                rows.add(new ClientRow(rs.getString("ipAddress"), rs.getString("displayName"), rs.getString("ipAddress"),
                        rs.getLong("lastSeen"), rs.getInt("notificationEnabled") == 1, rs.getString("screenshotDir"),
                        rs.getString("recordingDir"), rs.getString("vpnState")));
            }
        }
        return rows;
    }

    /** Auto-add on first connection. */
    public void insert(String clientId, String displayName, String ip, long lastSeen) throws SQLException {
        try (PreparedStatement pstmt = db.connection().prepareStatement(
                "INSERT OR IGNORE INTO clients (clientId, displayName, ipAddress, lastSeen, notificationEnabled) VALUES (?, ?, ?, ?, 1)")) {
            pstmt.setString(1, clientId);
            pstmt.setString(2, displayName);
            pstmt.setString(3, ip);
            pstmt.setLong(4, lastSeen);
            pstmt.executeUpdate();
        }
    }

    public void updateSeen(String clientId, String ip, long lastSeen) throws SQLException {
        try (PreparedStatement pstmt = db.connection()
                .prepareStatement("UPDATE clients SET ipAddress = ?, lastSeen = ? WHERE clientId = ?")) {
            pstmt.setString(1, ip);
            pstmt.setLong(2, lastSeen);
            pstmt.setString(3, clientId);
            pstmt.executeUpdate();
        }
    }

    public void updateDisplayName(String clientId, String name) throws SQLException {
        try (PreparedStatement pstmt = db.connection()
                .prepareStatement("UPDATE clients SET displayName = ? WHERE clientId = ?")) {
            pstmt.setString(1, name);
            pstmt.setString(2, clientId);
            pstmt.executeUpdate();
        }
    }

    public void updateNotification(String clientId, boolean enabled) throws SQLException {
        try (PreparedStatement pstmt = db.connection()
                .prepareStatement("UPDATE clients SET notificationEnabled = ? WHERE clientId = ?")) {
            pstmt.setInt(1, enabled ? 1 : 0);
            pstmt.setString(2, clientId);
            pstmt.executeUpdate();
        }
    }

    public void updateDirectories(String clientId, String screenshotDir, String recordingDir) throws SQLException {
        try (PreparedStatement pstmt = db.connection().prepareStatement(
                "UPDATE clients SET screenshotDir = ?, recordingDir = ? WHERE clientId = ?")) {
            pstmt.setString(1, screenshotDir);
            pstmt.setString(2, recordingDir);
            pstmt.setString(3, clientId);
            pstmt.executeUpdate();
        }
    }

    public void updateVpnState(String clientId, String state) throws SQLException {
        try (PreparedStatement pstmt = db.connection()
                .prepareStatement("UPDATE clients SET vpnState = ? WHERE clientId = ?")) {
            pstmt.setString(1, state);
            pstmt.setString(2, clientId);
            pstmt.executeUpdate();
        }
    }

    public void delete(String clientId) throws SQLException {
        try (PreparedStatement pstmt = db.connection().prepareStatement("DELETE FROM clients WHERE clientId = ?")) {
            pstmt.setString(1, clientId);
            pstmt.executeUpdate();
        }
    }
}
