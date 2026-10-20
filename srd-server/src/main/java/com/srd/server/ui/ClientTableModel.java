package com.srd.server.ui;

import com.srd.common.status.VpnGuardStatus;
import javax.swing.table.DefaultTableModel;

/**
 * Client table model. Model indices 0..6 are unchanged from v2 ("No." was appended last so the
 * older indices stayed valid); the VPN Guard column is appended as index 7. EDT only.
 */
public final class ClientTableModel extends DefaultTableModel {
    public static final int COL_NAME = 0;
    public static final int COL_IP = 1;
    public static final int COL_STATUS = 2;
    public static final int COL_LAST_SEEN = 3;
    public static final int COL_RECORDS = 4;
    public static final int COL_ACTION = 5;
    public static final int COL_NO = 6;
    public static final int COL_VPN = 7;
    public static final int COLUMN_COUNT = 8;

    public static final String[] COLUMN_NAMES = {
            "Display Name", "IP Address", "Status", "Last Seen", "Today's Records", "Action", "No.", "VPN"};

    public static final String STATUS_CONNECTED = "Connected";
    public static final String STATUS_DISCONNECTED = "Disconnected";
    public static final String STATUS_POWER_OFF = "Power Off";
    public static final String STATUS_OUTDATED = "Outdated client";

    public ClientTableModel() {
        super(COLUMN_NAMES, 0);
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return column == COL_NAME || column == COL_ACTION;
    }

    /** The only way rows are added, so every row always has all {@value #COLUMN_COUNT} cells. */
    public int addClientRow(String displayName, String ip, String status, String lastSeen, String records,
                            VpnGuardStatus vpn) {
        Object[] row = new Object[COLUMN_COUNT];
        row[COL_NAME] = displayName;
        row[COL_IP] = ip;
        row[COL_STATUS] = status;
        row[COL_LAST_SEEN] = lastSeen;
        row[COL_RECORDS] = records == null ? "" : records;
        row[COL_ACTION] = "";
        row[COL_NO] = "";
        row[COL_VPN] = vpn;
        addRow(row);
        return getRowCount() - 1;
    }

    /** Model row of a client (keyed by the IP column), or -1. */
    public int rowOf(String clientId) {
        if (clientId == null) return -1;
        for (int i = 0; i < getRowCount(); i++) {
            if (clientId.equals(getValueAt(i, COL_IP))) return i;
        }
        return -1;
    }

    /** Orders dotted IPv4 addresses numerically (10.0.0.9 before 10.0.0.10); other text falls back to plain order. */
    public static int compareIps(Object x, Object y) {
        String a = String.valueOf(x), b = String.valueOf(y);
        long ka = ipKey(a), kb = ipKey(b);
        if (ka >= 0 && kb >= 0) return Long.compare(ka, kb);
        if (ka >= 0) return -1;
        if (kb >= 0) return 1;
        return a.compareToIgnoreCase(b);
    }

    private static long ipKey(String ip) {
        long key = 0;
        int parts = 0;
        int start = 0;
        for (int i = 0; i <= ip.length(); i++) {
            if (i == ip.length() || ip.charAt(i) == '.') {
                if (i == start || i - start > 3 || parts == 4) return -1;
                int v = 0;
                for (int k = start; k < i; k++) {
                    char c = ip.charAt(k);
                    if (c < '0' || c > '9') return -1;
                    v = v * 10 + (c - '0');
                }
                if (v > 255) return -1;
                key = (key << 8) | v;
                parts++;
                start = i + 1;
            }
        }
        return parts == 4 ? key : -1;
    }

    public String clientIdAt(int modelRow) {
        Object v = getValueAt(modelRow, COL_IP);
        return v == null ? null : v.toString();
    }

    public void setCell(String clientId, int column, Object value) {
        int row = rowOf(clientId);
        if (row >= 0) setValueAt(value, row, column);
    }

    public Object cell(String clientId, int column) {
        int row = rowOf(clientId);
        return row >= 0 ? getValueAt(row, column) : null;
    }
}
