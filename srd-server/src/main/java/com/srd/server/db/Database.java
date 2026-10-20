package com.srd.server.db;

import com.srd.common.util.Log;
import com.srd.common.util.Threads;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The server's SQLite database ({@code clients.db} in the working directory) plus the single
 * "db" thread every query runs on. Repositories are plain blocking classes; callers on the EDT
 * or a network thread must go through {@link #execute}/{@link #submit} so SQL never runs there
 * (the old code ran SQL from the table-model listener and from network threads).
 */
public final class Database implements AutoCloseable {
    public static final String DEFAULT_FILE = "clients.db";

    private final String path;
    private volatile Connection conn;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(Threads.factory("db", Thread.NORM_PRIORITY));

    public Database(String path) throws SQLException {
        this.path = path;
        this.conn = open(path);
        ensureSchema(conn);
    }

    public static Connection open(String path) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + path);
    }

    public String path() {
        return path;
    }

    public Connection connection() {
        return conn;
    }

    /**
     * Creates the tables (same definitions as v2) and applies guarded migrations. Idempotent; also
     * used to create the schema of a backup file so the two can never drift apart.
     */
    public static void ensureSchema(Connection c) throws SQLException {
        try (Statement stmt = c.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS clients (" +
                    "clientId TEXT PRIMARY KEY, " +
                    "displayName TEXT, " +
                    "ipAddress TEXT, " +
                    "lastSeen INTEGER, " +
                    "notificationEnabled INTEGER DEFAULT 1, " +
                    "screenshotDir TEXT, " +
                    "recordingDir TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS logs (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "clientId TEXT, " +
                    "eventType TEXT, " +
                    "timestamp INTEGER, " +
                    "FOREIGN KEY(clientId) REFERENCES clients(clientId))");
            stmt.execute("CREATE TABLE IF NOT EXISTS server_config (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "key TEXT UNIQUE, " +
                    "value TEXT)");
        }
        migrate(c);
    }

    /** v3 migrations; each one is guarded so it can run on every start. */
    static void migrate(Connection c) throws SQLException {
        if (!hasColumn(c, "clients", "vpnState")) {
            try (Statement stmt = c.createStatement()) {
                stmt.execute("ALTER TABLE clients ADD COLUMN vpnState TEXT");
            }
            Log.info("DB migration: added clients.vpnState");
        }
        try (Statement stmt = c.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS vpn_status_log (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "clientId TEXT, " +
                    "state TEXT, " +
                    "ip TEXT, " +
                    "country TEXT, " +
                    "route TEXT, " +
                    "reason TEXT, " +
                    "timestamp INTEGER)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_logs_client_ts ON logs(clientId, timestamp)");
        }
    }

    public static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        try (Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
        }
        return false;
    }

    public static boolean tableExists(Connection c, String table) throws SQLException {
        try (ResultSet rs = c.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        }
    }

    /** Runs a DB task on the db thread; failures are logged. */
    public void execute(SqlTask task) {
        exec.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                Log.error("DB task failed", e);
            }
        });
    }

    public <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> f = new CompletableFuture<>();
        exec.execute(() -> {
            try {
                f.complete(task.call());
            } catch (Throwable e) {
                Log.error("DB query failed", e);
                f.completeExceptionally(e);
            }
        });
        return f;
    }

    /** Re-opens the connection (after an import replaced the data). Must run on the db thread. */
    public void reopen() throws SQLException {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
        conn = open(path);
        ensureSchema(conn);
    }

    @Override
    public void close() {
        exec.shutdown();
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }

    @FunctionalInterface
    public interface SqlTask {
        void run() throws Exception;
    }
}
