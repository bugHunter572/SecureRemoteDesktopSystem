package com.srd.server.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srd.common.status.VpnGuardStatus;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ClientTableModelTest {
    @Test
    void columnIndicesAreStable() {
        ClientTableModel m = new ClientTableModel();
        assertEquals(8, m.getColumnCount());
        assertEquals(ClientTableModel.COLUMN_COUNT, m.getColumnCount());
        assertEquals("Display Name", m.getColumnName(0));
        assertEquals("IP Address", m.getColumnName(1));
        assertEquals("Status", m.getColumnName(2));
        assertEquals("Last Seen", m.getColumnName(3));
        assertEquals("Today's Records", m.getColumnName(4));
        assertEquals("Action", m.getColumnName(5));
        assertEquals("No.", m.getColumnName(6));
        assertEquals("VPN", m.getColumnName(7));
    }

    @Test
    void addClientRowFillsEveryCell() {
        ClientTableModel m = new ClientTableModel();
        VpnGuardStatus st = VpnGuardStatus.of(VpnGuardStatus.State.PROTECTED);
        m.addClientRow("PC", "10.0.0.1", ClientTableModel.STATUS_CONNECTED, "01-01 00:00:00", null, st);
        m.addClientRow("PC2", "10.0.0.2", ClientTableModel.STATUS_DISCONNECTED, "Never", "x", null);
        assertEquals(2, m.getRowCount());
        assertEquals(1, m.rowOf("10.0.0.2"));
        assertEquals(-1, m.rowOf("10.0.0.3"));
        assertEquals("", m.getValueAt(0, ClientTableModel.COL_RECORDS));
        assertSame(st, m.getValueAt(0, ClientTableModel.COL_VPN));
        assertNull(m.getValueAt(1, ClientTableModel.COL_VPN));
        m.setCell("10.0.0.2", ClientTableModel.COL_STATUS, ClientTableModel.STATUS_OUTDATED);
        assertEquals(ClientTableModel.STATUS_OUTDATED, m.cell("10.0.0.2", ClientTableModel.COL_STATUS));
    }

    @Test
    void onlyNameAndActionAreEditable() {
        ClientTableModel m = new ClientTableModel();
        m.addClientRow("PC", "10.0.0.1", "Connected", "", "", null);
        for (int c = 0; c < m.getColumnCount(); c++) {
            boolean editable = c == ClientTableModel.COL_NAME || c == ClientTableModel.COL_ACTION;
            assertEquals(editable, m.isCellEditable(0, c), "column " + c);
        }
    }

    @Test
    void thumbnailLayoutTargetsThirtyTiles() {
        int[] l = ThumbnailGrid.computeLayout(30, 1100, 900, true);
        assertTrue(l[0] >= 1 && l[1] >= 80 && l[1] <= 260, Arrays.toString(l));
        int[] full = ThumbnailGrid.computeLayout(50, 1900, 900, false);
        assertTrue(full[1] >= 170, Arrays.toString(full));
        assertEquals(1, ThumbnailGrid.computeLayout(1, 600, 600, true)[0]);
    }

    @Test
    void thumbnailBadgeLabelsAreShort() {
        for (VpnGuardStatus.State s : VpnGuardStatus.State.values()) {
            assertTrue(ThumbnailPanel.shortLabel(s).length() <= 18, s.name());
        }
    }

    @Test
    void ipsSortNumericallyAndJunkSortsLast() {
        java.util.List<String> ips = new java.util.ArrayList<>(java.util.Arrays.asList("10.0.0.10", "10.0.0.9", "192.168.1.2", "bogus", "10.0.0.2"));
        ips.sort(ClientTableModel::compareIps);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Arrays.asList("10.0.0.2", "10.0.0.9", "10.0.0.10", "192.168.1.2", "bogus"), ips);
    }
}
