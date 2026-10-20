package com.srd.server.db;

import com.srd.common.util.Log;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Database backup / import (Database menu). The backup schema is created by
 * {@link Database#ensureSchema} itself, so new columns/tables (vpnState, vpn_status_log) are always
 * included. Blocking: run on the db thread.
 */
public final class BackupService {
    /** Client-data tables copied by backup/import; server_config is deliberately kept. */
    static final String[] DATA_TABLES = {"clients", "logs", "vpn_status_log"};

    private final Database db;

    public BackupService(Database db) {
        this.db = db;
    }

    public void backupTo(File backupFile) throws SQLException {
        try (Connection backupConn = Database.open(backupFile.getAbsolutePath())) {
            Database.ensureSchema(backupConn);
            for (String t : DATA_TABLES) copyTableData(db.connection(), backupConn, t);
            Log.info("Database backed up to: " + backupFile.getAbsolutePath());
        }
    }

    /**
     * Replaces client data (not server_config) with the source file's.
     *
     * @return false if the source has no clients table
     */
    public boolean importFrom(File sourceFile) throws SQLException {
        try (Connection sourceConn = Database.open(sourceFile.getAbsolutePath())) {
            if (!Database.tableExists(sourceConn, "clients")) return false;
            Connection cur = db.connection();
            boolean auto = cur.getAutoCommit();
            cur.setAutoCommit(false);
            try {
                try (Statement stmt = cur.createStatement()) {
                    for (String t : DATA_TABLES) stmt.execute("DELETE FROM " + t);
                }
                for (String t : DATA_TABLES) {
                    if (Database.tableExists(sourceConn, t)) copyTableData(sourceConn, cur, t);
                }
                cur.commit();
            } catch (SQLException e) {
                cur.rollback();
                throw e;
            } finally {
                cur.setAutoCommit(auto);
            }
            Log.info("Client data imported successfully from: " + sourceFile.getName());
            return true;
        }
    }

    /** Copies every row; only columns that exist in the destination are written. */
    static int copyTableData(Connection sourceConn, Connection destConn, String tableName) throws SQLException {
        Set<String> destCols = new HashSet<>();
        try (Statement s = destConn.createStatement(); ResultSet rs = s.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) destCols.add(rs.getString("name").toLowerCase(Locale.ROOT));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement stmt = sourceConn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM " + tableName)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) {
                    String col = md.getColumnName(i);
                    if (destCols.contains(col.toLowerCase(Locale.ROOT))) row.put(col, rs.getObject(i));
                }
                rows.add(row);
            }
        }
        if (!rows.isEmpty()) {
            String[] columns = rows.get(0).keySet().toArray(new String[0]);
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < columns.length; i++) placeholders.append(i > 0 ? ",?" : "?");
            String sql = "INSERT INTO " + tableName + " (" + String.join(",", columns) + ") VALUES (" + placeholders + ")";
            try (PreparedStatement pstmt = destConn.prepareStatement(sql)) {
                for (Map<String, Object> row : rows) {
                    for (int i = 0; i < columns.length; i++) pstmt.setObject(i + 1, row.get(columns[i]));
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
            }
        }
        Log.info("Copied " + rows.size() + " rows from table: " + tableName);
        return rows.size();
    }
}
