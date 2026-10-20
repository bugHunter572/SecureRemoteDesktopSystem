package com.srd.server.ui;

import com.srd.server.db.Database;
import com.srd.server.db.EventLogRepository;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

/** "Records for <client>" window (double-click a table row). All SQL runs on the db thread. */
public final class RecordsWindow {
    private static JFrame recordsWindow;

    private RecordsWindow() {
    }

    /**
     * @param onChanged called (on the EDT) after records were removed, to refresh the table cell
     */
    public static void show(Component parent, String clientId, Database db, EventLogRepository events,
                            Consumer<String> onChanged) {
        if (recordsWindow != null && recordsWindow.isVisible()) {
            recordsWindow.getContentPane().removeAll();
            recordsWindow.setTitle("Records for " + clientId);
            recordsWindow.toFront();
        } else {
            recordsWindow = new JFrame("Records for " + clientId);
            recordsWindow.setSize(600, 400);
            recordsWindow.setLocationRelativeTo(parent);
        }
        JFrame window = recordsWindow;

        DefaultTableModel recordsModel = new DefaultTableModel(new Object[] {"Status", "Timestamp"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable recordsTable = new JTable(recordsModel);
        recordsTable.setRowHeight(30);
        recordsTable.setFont(new Font("PRK P Chongbong", Font.BOLD, 14));
        recordsTable.getColumnModel().getColumn(0).setMaxWidth(100);
        ClientTableRenderers.RecordsWindowRenderer renderer = new ClientTableRenderers.RecordsWindowRenderer();
        recordsTable.getColumnModel().getColumn(0).setCellRenderer(renderer);
        recordsTable.getColumnModel().getColumn(1).setCellRenderer(renderer);
        JScrollPane scrollPane = new JScrollPane(recordsTable);

        db.submit(() -> events.allEvents(clientId)).thenAccept(rows -> SwingUtilities.invokeLater(() -> {
            for (String[] r : rows) recordsModel.addRow(new Object[] {r[0], r[1]});
        }));

        JPanel buttonPanel = new JPanel();
        JButton removeButton = new JButton("Remove Selected");
        removeButton.addActionListener(e -> {
            int selectedRow = recordsTable.getSelectedRow();
            if (selectedRow < 0) return;
            String event = (String) recordsModel.getValueAt(selectedRow, 0);
            String tsStr = (String) recordsModel.getValueAt(selectedRow, 1);
            recordsModel.removeRow(selectedRow);
            db.execute(() -> {
                events.deleteEvent(clientId, event, tsStr);
                SwingUtilities.invokeLater(() -> onChanged.accept(clientId));
            });
        });
        JButton removeAllButton = new JButton("Remove All");
        removeAllButton.addActionListener(e -> {
            recordsModel.setRowCount(0);
            db.execute(() -> {
                events.deleteAllFor(clientId);
                SwingUtilities.invokeLater(() -> onChanged.accept(clientId));
            });
        });
        buttonPanel.add(removeButton);
        buttonPanel.add(removeAllButton);

        window.add(scrollPane, BorderLayout.CENTER);
        window.add(buttonPanel, BorderLayout.SOUTH);
        window.revalidate();
        window.setVisible(true);
    }
}
