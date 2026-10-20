package com.srd.server.db;

import com.srd.common.status.VpnGuardStatus;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** VPN Guard history ({@code vpn_status_log}) and the latest state ({@code clients.vpnState}). */
public final class VpnStatusRepository {
    private final Database db;

    public VpnStatusRepository(Database db) {
        this.db = db;
    }

    /**
     * Records a status if it differs from the previous one (per {@link VpnGuardStatus#sameAs}).
     *
     * @return true if a history row was written
     */
    public boolean record(String clientId, VpnGuardStatus previous, VpnGuardStatus next) throws SQLException {
        if (next == null || next.sameAs(previous)) return false;
        try (PreparedStatement p = db.connection().prepareStatement(
                "INSERT INTO vpn_status_log (clientId, state, ip, country, route, reason, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            p.setString(1, clientId);
            p.setString(2, next.state.name());
            p.setString(3, next.ip);
            p.setString(4, next.country);
            p.setString(5, next.route);
            p.setString(6, next.reason);
            p.setLong(7, System.currentTimeMillis());
            p.executeUpdate();
        }
        try (PreparedStatement p = db.connection().prepareStatement("UPDATE clients SET vpnState = ? WHERE clientId = ?")) {
            p.setString(1, next.state.name());
            p.setString(2, clientId);
            p.executeUpdate();
        }
        return true;
    }

    /** Newest-first history rows {state, ip, country, route, reason, timestamp}. */
    public List<Object[]> history(String clientId, int limit) throws SQLException {
        List<Object[]> out = new ArrayList<>();
        try (PreparedStatement p = db.connection().prepareStatement(
                "SELECT state, ip, country, route, reason, timestamp FROM vpn_status_log WHERE clientId = ? ORDER BY timestamp DESC LIMIT ?")) {
            p.setString(1, clientId);
            p.setInt(2, limit);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    out.add(new Object[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getLong(6)});
                }
            }
        }
        return out;
    }
}
