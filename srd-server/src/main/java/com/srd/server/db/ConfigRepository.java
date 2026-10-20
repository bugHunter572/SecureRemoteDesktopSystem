package com.srd.server.db;

import com.srd.server.config.ServerConfig;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/** {@code server_config} key/value table. Blocking: call on the db thread (or at startup). */
public final class ConfigRepository {
    private final Database db;

    public ConfigRepository(Database db) {
        this.db = db;
    }

    public ServerConfig load() throws SQLException {
        Map<String, String> data = new HashMap<>();
        try (PreparedStatement pstmt = db.connection().prepareStatement("SELECT key, value FROM server_config");
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) data.put(rs.getString("key"), rs.getString("value"));
        }
        return ServerConfig.fromDatabase(data);
    }

    public void save(ServerConfig config) throws SQLException {
        boolean auto = db.connection().getAutoCommit();
        db.connection().setAutoCommit(false);
        try {
            try (Statement stmt = db.connection().createStatement()) {
                stmt.execute("DELETE FROM server_config");
            }
            try (PreparedStatement pstmt = db.connection().prepareStatement("INSERT INTO server_config (key, value) VALUES (?, ?)")) {
                for (Map.Entry<String, String> entry : config.toDatabaseMap().entrySet()) {
                    pstmt.setString(1, entry.getKey());
                    pstmt.setString(2, entry.getValue());
                    pstmt.executeUpdate();
                }
            }
            db.connection().commit();
        } catch (SQLException e) {
            db.connection().rollback();
            throw e;
        } finally {
            db.connection().setAutoCommit(auto);
        }
    }
}
