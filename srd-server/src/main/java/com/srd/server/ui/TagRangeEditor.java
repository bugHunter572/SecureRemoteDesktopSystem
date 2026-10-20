package com.srd.server.ui;

import com.srd.server.alerts.TagMap;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

/**
 * Tags by IP range as a table (Tag, From IP, To IP) with Add / Remove. Reads and writes the same
 * text the settings store ({@code @acs 192.168.110.100 ~ 104}, one line per row), so nothing else changes.
 */
final class TagRangeEditor extends JPanel {
    private final DefaultTableModel model = new DefaultTableModel(new Object[] {"Tag", "From IP", "To IP (optional)"}, 0);
    private final JTable table = new JTable(model);

    TagRangeEditor(String text) {
        super(new BorderLayout(0, 4));
        for (String[] r : TagMap.parse(text).rows()) model.addRow(new Object[] {r[0], r[1], r[1].equals(r[2]) ? "" : r[2]});
        table.setRowHeight(table.getRowHeight() + 2);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(420, 170));
        add(scroll, BorderLayout.CENTER);
        JButton add = new JButton("Add");
        add.addActionListener(e -> {
            model.addRow(new Object[] {"@", "", ""});
            int row = model.getRowCount() - 1;
            table.getSelectionModel().setSelectionInterval(row, row);
            table.editCellAt(row, 0);
        });
        JButton remove = new JButton("Remove");
        remove.addActionListener(e -> {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            int[] rows = table.getSelectedRows();
            for (int i = rows.length - 1; i >= 0; i--) model.removeRow(rows[i]);
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(add);
        buttons.add(remove);
        add(buttons, BorderLayout.SOUTH);
    }

    /** One line per complete row; rows without a tag or a first IP are left out. */
    String getText() {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < model.getRowCount(); i++) {
            String tag = cell(i, 0);
            String from = cell(i, 1);
            String to = cell(i, 2);
            if (tag.isEmpty() || tag.equals("@") || from.isEmpty()) continue;
            if (b.length() > 0) b.append('\n');
            b.append(tag).append(' ').append(from);
            if (!to.isEmpty()) b.append(" ~ ").append(to);
        }
        return b.toString();
    }

    /** Rows the text parser would reject (so the dialog can warn instead of silently dropping them). */
    List<String[]> parsedRows() {
        return TagMap.parse(getText()).rows();
    }

    int rowCount() {
        return model.getRowCount();
    }

    private String cell(int row, int col) {
        Object v = model.getValueAt(row, col);
        return v == null ? "" : v.toString().trim();
    }
}
