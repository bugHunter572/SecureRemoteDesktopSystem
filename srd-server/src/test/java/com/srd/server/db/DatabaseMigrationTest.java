package com.srd.server.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srd.common.status.VpnGuardStatus;
import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseMigrationTest {
    @TempDir
    File tmp;

    /** Creates a database exactly as the v2 server did (no vpnState, no vpn_status_log). */
    static File createV2Database(File f) throws Exception {
        try (Connection c = Database.open(f.getAbsolutePath()); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS clients (clientId TEXT PRIMARY KEY, displayName TEXT, ipAddress TEXT, "
                    + "lastSeen INTEGER, notificationEnabled INTEGER DEFAULT 1, screenshotDir TEXT, recordingDir TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS logs (id INTEGER PRIMARY KEY AUTOINCREMENT, clientId TEXT, eventType TEXT, "
                    + "timestamp INTEGER, FOREIGN KEY(clientId) REFERENCES clients(clientId))");
            s.execute("CREATE TABLE IF NOT EXISTS server_config (id INTEGER PRIMARY KEY AUTOINCREMENT, key TEXT UNIQUE, value TEXT)");
            s.execute("INSERT INTO clients VALUES ('192.168.1.5', 'PC-5', '192.168.1.5', 1000, 0, NULL, 'D:/rec')");
            s.execute("INSERT INTO logs (clientId, eventType, timestamp) VALUES ('192.168.1.5', 'CONNECT', 1000)");
        }
        return f;
    }

    @Test
    void migratesV2SchemaIdempotently() throws Exception {
        File f = createV2Database(new File(tmp, "clients.db"));
        try (Connection c = Database.open(f.getAbsolutePath())) {
            assertFalse(Database.hasColumn(c, "clients", "vpnState"));
            assertFalse(Database.tableExists(c, "vpn_status_log"));
            Database.ensureSchema(c);
            Database.ensureSchema(c); // second run must be a no-op, not "duplicate column"
            assertTrue(Database.hasColumn(c, "clients", "vpnState"));
            assertTrue(Database.tableExists(c, "vpn_status_log"));
            for (String col : new String[] {"id", "clientId", "state", "ip", "country", "route", "reason", "timestamp"}) {
                assertTrue(Database.hasColumn(c, "vpn_status_log", col), col);
            }
        }
        // and again through the Database class (as on every server start)
        Database db = new Database(f.getAbsolutePath());
        try {
            List<ClientRow> rows = new ClientRepository(db).loadAll();
            assertEquals(1, rows.size());
            ClientRow r = rows.get(0);
            assertEquals("192.168.1.5", r.clientId);
            assertEquals("PC-5", r.displayName);
            assertFalse(r.notificationEnabled);
            assertEquals("D:/rec", r.recordingDir);
            assertNull(r.vpnState);
        } finally {
            db.close();
        }
    }

    @Test
    void vpnHistoryIsWrittenOnlyOnChange() throws Exception {
        Database db = new Database(new File(tmp, "v.db").getAbsolutePath());
        try {
            ClientRepository clients = new ClientRepository(db);
            VpnStatusRepository repo = new VpnStatusRepository(db);
            clients.insert("10.0.0.2", "A", "10.0.0.2", 1);
            VpnGuardStatus on = new VpnGuardStatus(VpnGuardStatus.State.PROTECTED, "1.2.3.4", "JP", "wg0", "", "", 3, true, "RUNNING");
            VpnGuardStatus onLater = new VpnGuardStatus(VpnGuardStatus.State.PROTECTED, "1.2.3.4", "JP", "wg0", "", "", 9, true, "RUNNING");
            VpnGuardStatus off = new VpnGuardStatus(VpnGuardStatus.State.OFF, "", "", "", "service stopped", "", -1, false, "STOPPED");
            assertTrue(repo.record("10.0.0.2", null, on));
            assertFalse(repo.record("10.0.0.2", on, onLater)); // only the heartbeat age changed
            assertTrue(repo.record("10.0.0.2", onLater, off));
            assertEquals(2, repo.history("10.0.0.2", 10).size());
            assertEquals("OFF", repo.history("10.0.0.2", 10).get(0)[0]);
            assertEquals("OFF", clients.loadAll().get(0).vpnState);
        } finally {
            db.close();
        }
    }

    @Test
    void backupContainsNewSchemaAndImportAcceptsV2File() throws Exception {
        Database db = new Database(new File(tmp, "main.db").getAbsolutePath());
        try {
            ClientRepository clients = new ClientRepository(db);
            clients.insert("10.0.0.9", "Nine", "10.0.0.9", 5);
            new VpnStatusRepository(db).record("10.0.0.9", null, VpnGuardStatus.of(VpnGuardStatus.State.BLOCKED));
            new EventLogRepository(db).log("10.0.0.9", EventLogRepository.CONNECT);

            BackupService backup = new BackupService(db);
            File bak = new File(tmp, "backup.db");
            backup.backupTo(bak);
            try (Connection c = Database.open(bak.getAbsolutePath()); Statement s = c.createStatement()) {
                assertTrue(Database.hasColumn(c, "clients", "vpnState"));
                try (ResultSet rs = s.executeQuery("SELECT vpnState FROM clients")) {
                    assertTrue(rs.next());
                    assertEquals("BLOCKED", rs.getString(1));
                }
                try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM vpn_status_log")) {
                    rs.next();
                    assertEquals(1, rs.getInt(1));
                }
                try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM logs")) {
                    rs.next();
                    assertEquals(1, rs.getInt(1));
                }
            }

            // Importing an old v2 file replaces client data and keeps working.
            File v2 = createV2Database(new File(tmp, "old.db"));
            assertTrue(backup.importFrom(v2));
            List<ClientRow> rows = clients.loadAll();
            assertEquals(1, rows.size());
            assertEquals("192.168.1.5", rows.get(0).clientId);
            assertEquals(0, new VpnStatusRepository(db).history("10.0.0.9", 10).size());
            // Re-importing the backup restores the VPN data.
            assertTrue(backup.importFrom(bak));
            assertEquals("BLOCKED", clients.loadAll().get(0).vpnState);
        } finally {
            db.close();
        }
    }

    @Test
    void eventLogDeduplicatesAndBuildsRecordsCell() throws Exception {
        Database db = new Database(new File(tmp, "e.db").getAbsolutePath());
        try {
            EventLogRepository ev = new EventLogRepository(db);
            assertTrue(ev.log("c", EventLogRepository.CONNECT));
            assertFalse(ev.log("c", EventLogRepository.CONNECT));
            assertTrue(ev.log("c", EventLogRepository.POWEROFF));
            String cell = ev.todaysRecordsCell("c");
            assertTrue(cell.startsWith(";icons/connected.png,"), cell);
            assertTrue(cell.contains(";;icons/computer-off.png,"), cell);
            assertEquals(2, ev.allEvents("c").size());
        } finally {
            db.close();
        }
    }
}
