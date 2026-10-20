package com.srd.server.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * {@code logs} table: CONNECT / DISCONNECT / POWEROFF / NO_SCREEN / VPN_OFF events shown as "Today's Records" icons and
 * in the records window. Blocking: call on the db thread.
 */
public final class EventLogRepository {
    public static final String CONNECT = "CONNECT";
    public static final String DISCONNECT = "DISCONNECT";
    public static final String POWEROFF = "POWEROFF";
    /** Connected, but its screen stopped arriving (announced by the alert service). */
    public static final String NO_SCREEN = "NO_SCREEN";
    /** VPN Guard went off / blocked / stopped responding. */
    public static final String VPN_OFF = "VPN_OFF";

    private final Database db;

    public EventLogRepository(Database db) {
        this.db = db;
    }

    static long startOfToday() {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        return today.getTimeInMillis();
    }

    /**
     * Inserts an event unless it repeats today's latest event of the same kind (unchanged v2 rule).
     *
     * @return true if a row was written
     */
    public boolean log(String clientId, String eventType) throws SQLException {
        long timestamp = System.currentTimeMillis();
        try (PreparedStatement tstmt = db.connection().prepareStatement(
                "SELECT eventType, timestamp FROM logs WHERE clientId = ? AND timestamp >= ? ORDER BY timestamp DESC LIMIT 1")) {
            tstmt.setString(1, clientId);
            tstmt.setLong(2, startOfToday());
            try (ResultSet rs = tstmt.executeQuery()) {
                if (rs.next()) {
                    String lastEvent = rs.getString("eventType");
                    if (eventType.equals(lastEvent) && (eventType.equals(CONNECT) || eventType.equals(DISCONNECT)
                            || eventType.equals(POWEROFF))) {
                        return false;
                    }
                }
            }
        }
        try (PreparedStatement pstmt = db.connection()
                .prepareStatement("INSERT INTO logs (clientId, eventType, timestamp) VALUES (?, ?, ?)")) {
            pstmt.setString(1, clientId);
            pstmt.setString(2, eventType);
            pstmt.setLong(3, timestamp);
            pstmt.executeUpdate();
        }
        return true;
    }

    /** Icon file name for an event type, as used by the records renderers. */
    public static String iconFor(String event) {
        switch (event == null ? "" : event) {
            case CONNECT:
                return "icons/connected.png";
            case DISCONNECT:
                return "icons/disconnected.png";
            case POWEROFF:
                return "icons/x-off.png";
            case NO_SCREEN:
                return "icons/no-screen.png";
            case VPN_OFF:
                return "icons/vpn-off.png";
            default:
                return "?";
        }
    }

    /**
     * The "Today's Records" cell value: up to the last 5 events of today, oldest first, encoded as
     * {@code ;icons/x.png,yyyy-MM-dd HH:mm:ss;} entries (same format as v2).
     */
    public String todaysRecordsCell(String clientId) throws SQLException {
        StringBuilder events = new StringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        List<String> entries = new ArrayList<>();
        try (PreparedStatement pstmt = db.connection().prepareStatement(
                "SELECT eventType, timestamp FROM logs WHERE clientId = ? AND timestamp >= ? "
                        + "ORDER BY timestamp DESC LIMIT 5")) {
            pstmt.setString(1, clientId);
            pstmt.setLong(2, startOfToday());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    entries.add(iconFor(rs.getString("eventType")) + "," + sdf.format(new Date(rs.getLong("timestamp"))));
                }
            }
        }
        // Query is newest-first; append oldest-first so icons read left(old) -> right(new).
        for (int i = entries.size() - 1; i >= 0; i--) {
            events.append(";").append(entries.get(i)).append(";");
        }
        return events.toString().trim();
    }

    /** All events of one client, newest first: {eventType, formatted timestamp}. */
    public List<String[]> allEvents(String clientId) throws SQLException {
        List<String[]> out = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        try (PreparedStatement pstmt = db.connection()
                .prepareStatement("SELECT eventType, timestamp FROM logs WHERE clientId = ? ORDER BY timestamp DESC")) {
            pstmt.setString(1, clientId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[] {rs.getString("eventType"), sdf.format(new Date(rs.getLong("timestamp")))});
                }
            }
        }
        return out;
    }

    /** Deletes one event identified by type + second-resolution timestamp (v2 rule). */
    public void deleteEvent(String clientId, String eventType, String formattedTs) throws Exception {
        long ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(formattedTs).getTime();
        try (PreparedStatement pstmt = db.connection().prepareStatement(
                "DELETE FROM logs WHERE clientId = ? AND eventType = ? AND timestamp / 1000 = ?")) {
            pstmt.setString(1, clientId);
            pstmt.setString(2, eventType);
            pstmt.setLong(3, ts / 1000);
            pstmt.executeUpdate();
        }
    }

    public void deleteAllFor(String clientId) throws SQLException {
        try (PreparedStatement pstmt = db.connection().prepareStatement("DELETE FROM logs WHERE clientId = ?")) {
            pstmt.setString(1, clientId);
            pstmt.executeUpdate();
        }
    }

    public void clearAll() throws SQLException {
        try (Statement stmt = db.connection().createStatement()) {
            stmt.execute("DELETE FROM logs");
        }
    }
}
