package com.srd.server.ui;

import com.srd.common.status.VpnGuardStatus;
import com.srd.common.ui.Icons;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import javax.swing.AbstractCellEditor;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;

/**
 * Cell renderers/editors of the client table (moved from the v2 monolith). Renderers never load
 * or scale images while painting: icons come from the {@link Icons} cache and fonts/labels are
 * reused. Rows of clients with an active VPN alert are painted with a red background.
 */
public final class ClientTableRenderers {
    static final Color ALERT_BG = new Color(255, 200, 200);
    private static final Color OUTDATED_FG = new Color(0xE6, 0x51, 0x00);

    private ClientTableRenderers() {
    }

    /** Supplies per-row state the renderers need (all lookups are by clientId = IP). */
    public interface RowState {
        boolean isAlertHighlighted(String clientId);

        boolean isNotificationEnabled(String clientId);

        boolean hasCustomDirectory(String clientId);
    }

    /** Callbacks of the Action column buttons. */
    public interface ActionHandler {
        void editDirectory(String clientId);

        void toggleNotifications(String clientId);

        void deleteClient(String clientId);
    }

    static String clientIdAtView(JTable table, int viewRow) {
        if (viewRow < 0 || viewRow >= table.getRowCount()) return null;
        int modelRow = table.convertRowIndexToModel(viewRow);
        Object v = table.getModel().getValueAt(modelRow, ClientTableModel.COL_IP);
        return v == null ? null : v.toString();
    }

    static Color rowBackground(JTable table, int viewRow, boolean selected, RowState state) {
        if (selected) return table.getSelectionBackground();
        return state.isAlertHighlighted(clientIdAtView(table, viewRow)) ? ALERT_BG : table.getBackground();
    }

    // ------------------------------------------------------------------ text cells

    /** Centered text cell (v2 MTableCellRenderer) with the alert background. */
    public static class CenteredRenderer extends DefaultTableCellRenderer {
        protected final RowState state;

        public CenteredRenderer(RowState state) {
            this.state = state;
            setHorizontalAlignment(SwingConstants.CENTER);
            setVerticalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setIcon(null);
            setToolTipText(null);
            setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
            setBackground(rowBackground(table, row, isSelected, state));
            setOpaque(true);
            return this;
        }
    }

    /** Status column: Connected / Disconnected / Power Off icons; "Outdated client" as orange text. */
    public static final class StatusRenderer extends CenteredRenderer {
        public StatusRenderer(RowState state) {
            super(state);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            String status = value != null ? value.toString() : "";
            String iconName = null;
            if (ClientTableModel.STATUS_CONNECTED.equals(status)) iconName = "connected.png";
            else if (ClientTableModel.STATUS_DISCONNECTED.equals(status)) iconName = "disconnected.png";
            else if (ClientTableModel.STATUS_POWER_OFF.equals(status)) iconName = "x-off.png";
            ImageIcon icon = iconName == null ? null : Icons.icon(iconName, Math.max(8, table.getRowHeight() - 10));
            if (icon != null) {
                super.getTableCellRendererComponent(table, " ", isSelected, hasFocus, row, column);
                setIcon(icon);
                setToolTipText(status);
            } else {
                super.getTableCellRendererComponent(table, ClientTableModel.STATUS_OUTDATED.equals(status) ? "Outdated" : status,
                        isSelected, hasFocus, row, column);
                if (!isSelected) setForeground(ClientTableModel.STATUS_OUTDATED.equals(status) ? OUTDATED_FG : new Color(150, 0, 0));
                if (ClientTableModel.STATUS_OUTDATED.equals(status)) {
                    setToolTipText("This client runs a pre-3.0 version and cannot connect. Upgrade it.");
                }
            }
            return this;
        }
    }

    /** Renders the "No." column as the 1-based on-screen row number. */
    public static final class RowNumberRenderer extends CenteredRenderer {
        public RowNumberRenderer(RowState state) {
            super(state);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            return super.getTableCellRendererComponent(table, Integer.toString(row + 1), isSelected, hasFocus, row, column);
        }
    }

    // ------------------------------------------------------------------ Today's Records

    /**
     * "Today's Records": one icon per event with the timestamp as tooltip. Value format (v2):
     * {@code ;icons/connected.png,2026-01-01 08:00:00;;icons/...}. Labels are pooled and laid out
     * with explicit bounds so the table's tooltip lookup can hit-test them.
     */
    public static final class RecordsRenderer extends JPanel implements TableCellRenderer {
        private final RowState state;
        private final List<JLabel> pool = new ArrayList<>();
        private final JLabel empty = new JLabel("No Records");
        private java.awt.Font emptyFontBase;

        /** Parsed cell values (icon, tooltip), so a repaint of 150 rows doesn't re-split every string. EDT only. */
        private final Map<String, List<Object[]>> parsedCache = new java.util.LinkedHashMap<String, List<Object[]>>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<Object[]>> eldest) {
                return size() > 1024;
            }
        };

        private List<Object[]> parsed(String value, int size) {
            String key = size + "#" + value;
            List<Object[]> hit = parsedCache.get(key);
            if (hit != null) return hit;
            List<Object[]> out = new ArrayList<>();
            for (String entry : value.split(";")) {
                entry = entry.trim();
                if (entry.isEmpty()) continue;
                int comma = entry.indexOf(',');
                String path = comma >= 0 ? entry.substring(0, comma) : entry;
                String tip = comma >= 0 ? entry.substring(comma + 1) : "";
                int slash = path.lastIndexOf('/');
                ImageIcon icon = Icons.icon(slash >= 0 ? path.substring(slash + 1) : path, size);
                if (icon != null) out.add(new Object[] {icon, tip});
            }
            parsedCache.put(key, out);
            return out;
        }

        public RecordsRenderer(RowState state) {
            super(null);
            this.state = state;
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            removeAll();
            int size = Math.max(12, table.getRowHeight() - 8);
            int n = 0;
            if (value instanceof String) {
                for (Object[] entry : parsed((String) value, size)) {
                    ImageIcon icon = (ImageIcon) entry[0];
                    if (pool.size() <= n) pool.add(new JLabel());
                    JLabel l = pool.get(n);
                    l.setIcon(icon);
                    l.setToolTipText((String) entry[1]);
                    l.setBounds(2 + n * (size + 5), Math.max(0, (table.getRowHeight() - size) / 2), size, size);
                    add(l);
                    n++;
                }
            }
            if (n == 0) {
                if (emptyFontBase != table.getFont()) {
                    emptyFontBase = table.getFont();
                    empty.setFont(emptyFontBase.deriveFont(java.awt.Font.PLAIN));
                }
                empty.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
                Dimension d = empty.getPreferredSize();
                empty.setBounds(4, Math.max(0, (table.getRowHeight() - d.height) / 2), d.width, d.height);
                add(empty);
            }
            setPreferredSize(new Dimension(n == 0 ? empty.getPreferredSize().width + 8 : 4 + n * (size + 5), table.getRowHeight()));
            setBackground(rowBackground(table, row, isSelected, state));
            return this;
        }
    }

    // ------------------------------------------------------------------ VPN

    /** Filled circle icon in a state colour. */
    static final class DotIcon implements Icon {
        private final Color color;
        private final int size;

        DotIcon(Color color, int size) {
            this.color = color;
            this.size = size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(x, y, size, size);
            g2.setColor(color.darker());
            g2.drawOval(x, y, size - 1, size - 1);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    private static final Map<String, DotIcon> DOTS = new ConcurrentHashMap<>();

    static Color dim(Color c) {
        // Gray, whatever the state: the last known state of a disconnected client (the glyph still tells which).
        int lum = (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000;
        int g = (lum * 30 + 175 * 70) / 100;
        return new Color(g, g, g);
    }

    static DotIcon dot(VpnGuardStatus.State s, boolean dimmed) {
        return DOTS.computeIfAbsent(s.name() + dimmed, k -> new DotIcon(dimmed ? dim(s.color) : s.color, 11));
    }

    /**
     * Shield icon for the VPN column: state colour plus a glyph (check, cross, !, …), so states
     * stay distinguishable without relying on colour alone.
     */
    static final class ShieldIcon implements Icon {
        private final VpnGuardStatus.State state;
        private final Color color;
        private final int size;

        ShieldIcon(VpnGuardStatus.State state, Color color, int size) {
            this.state = state;
            this.color = color;
            this.size = size;
        }

        private static final Map<String, java.awt.image.BufferedImage> BITMAPS = new ConcurrentHashMap<>();

        /**
         * Drawn once per state/colour/device size, then blitted: anti-aliased vector paths for every
         * row on every repaint added up to hundreds of ms of event-thread time with many clients.
         */
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            double scale = ((Graphics2D) g).getTransform().getScaleX();
            int px = (int) Math.ceil(size * Math.max(1.0, scale));
            java.awt.image.BufferedImage bmp = BITMAPS.computeIfAbsent(state.name() + color.getRGB() + '@' + px, k -> {
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(px, px, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                drawShield(img.createGraphics(), px);
                return img;
            });
            g.drawImage(bmp, x, y, size, size, null);
        }

        private void drawShield(Graphics2D g2, double s) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            java.awt.geom.Path2D.Double shield = new java.awt.geom.Path2D.Double();
            shield.moveTo(s * 0.5, s * 0.04);
            shield.lineTo(s * 0.90, s * 0.18);
            shield.curveTo(s * 0.90, s * 0.58, s * 0.74, s * 0.82, s * 0.5, s * 0.97);
            shield.curveTo(s * 0.26, s * 0.82, s * 0.10, s * 0.58, s * 0.10, s * 0.18);
            shield.closePath();
            g2.setColor(color);
            g2.fill(shield);
            g2.setColor(color.darker());
            g2.setStroke(new java.awt.BasicStroke(1f));
            g2.draw(shield);
            g2.setColor(Color.WHITE);
            g2.setStroke(new java.awt.BasicStroke((float) Math.max(1.6, s * 0.12), java.awt.BasicStroke.CAP_ROUND,
                    java.awt.BasicStroke.JOIN_ROUND));
            switch (state) {
                case PROTECTED: { // check mark
                    java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
                    p.moveTo(s * 0.31, s * 0.50);
                    p.lineTo(s * 0.45, s * 0.64);
                    p.lineTo(s * 0.70, s * 0.36);
                    g2.draw(p);
                    break;
                }
                case BLOCKED: // bar (stop sign): traffic is being blocked, distinct from the Off cross
                    g2.draw(new java.awt.geom.Line2D.Double(s * 0.30, s * 0.5, s * 0.70, s * 0.5));
                    g2.draw(new java.awt.geom.Line2D.Double(s * 0.30, s * 0.5, s * 0.70, s * 0.5));
                    break;
                case OFF: // cross
                    g2.draw(new java.awt.geom.Line2D.Double(s * 0.34, s * 0.34, s * 0.66, s * 0.66));
                    g2.draw(new java.awt.geom.Line2D.Double(s * 0.66, s * 0.34, s * 0.34, s * 0.66));
                    break;
                case STALE: // exclamation
                    g2.draw(new java.awt.geom.Line2D.Double(s * 0.5, s * 0.28, s * 0.5, s * 0.56));
                    g2.fill(new java.awt.geom.Ellipse2D.Double(s * 0.43, s * 0.66, s * 0.14, s * 0.14));
                    break;
                case CHECKING: // three dots
                    for (int k = 0; k < 3; k++) {
                        g2.fill(new java.awt.geom.Ellipse2D.Double(s * (0.27 + k * 0.18), s * 0.45, s * 0.12, s * 0.12));
                    }
                    break;
                case NOT_INSTALLED: // minus
                    g2.draw(new java.awt.geom.Line2D.Double(s * 0.32, s * 0.5, s * 0.68, s * 0.5));
                    break;
                default: // unknown / n.a.: question mark-ish dot
                    g2.fill(new java.awt.geom.Ellipse2D.Double(s * 0.42, s * 0.42, s * 0.16, s * 0.16));
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    private static final Map<String, ShieldIcon> SHIELDS = new ConcurrentHashMap<>();

    static ShieldIcon shield(VpnGuardStatus.State s, boolean dimmed, int size) {
        return SHIELDS.computeIfAbsent(s.name() + dimmed + size,
                k -> new ShieldIcon(s, dimmed ? dim(s.color) : s.color, size));
    }

    /** VPN Guard column: shield icon only (details in the tooltip); dimmed while the client is offline. */
    public static final class VpnRenderer extends CenteredRenderer {
        public VpnRenderer(RowState state) {
            super(state);
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column);
            setHorizontalAlignment(SwingConstants.CENTER);
            setText("");
            int size = Math.max(12, Math.min(20, table.getRowHeight() - 8));
            if (!(value instanceof VpnGuardStatus)) {
                setIcon(shield(VpnGuardStatus.State.UNKNOWN, true, size));
                setToolTipText("No VPN Guard status received yet");
                return this;
            }
            VpnGuardStatus st = (VpnGuardStatus) value;
            int modelRow = table.convertRowIndexToModel(row);
            boolean connected = ClientTableModel.STATUS_CONNECTED.equals(
                    table.getModel().getValueAt(modelRow, ClientTableModel.COL_STATUS));
            setIcon(shield(st.state, !connected, size));
            String tip = st.tooltipHtml();
            setToolTipText(connected ? tip : tip.replace("</html>", "<br><i>(last known; client offline)</i></html>"));
            return this;
        }
    }

    // ------------------------------------------------------------------ Action column

    private static JButton actionButton(String iconName, String tip) {
        JButton b = new JButton(Icons.icon(iconName, 18));
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setMargin(new Insets(0, 2, 0, 2));
        b.setToolTipText(tip);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    /** Folder / bell / delete buttons (shared by renderer and editor). */
    static final class ActionPanel extends JPanel {
        final JButton folderButton = actionButton("folder.png", "Edit Save Directory");
        final JButton bellButton = actionButton("bell.png", "Toggle Notifications");
        final JButton deleteButton = actionButton("delete.png", "Delete Client");

        ActionPanel() {
            super(new FlowLayout(FlowLayout.CENTER, 2, 0));
            add(folderButton);
            add(bellButton);
            add(deleteButton);
            setAlignmentX(Component.CENTER_ALIGNMENT);
        }

        void update(JTable table, int row, boolean isSelected, String clientId, RowState state) {
            bellButton.setIcon(Icons.icon(state.isNotificationEnabled(clientId) ? "bell.png" : "alarm-bell.png", 18));
            folderButton.setToolTipText(state.hasCustomDirectory(clientId)
                    ? "Custom Directory Set (Click to Change)" : "Set Custom Save Directory");
            setBackground(rowBackground(table, row, isSelected, state));
        }
    }

    public static final class ActionRenderer implements TableCellRenderer {
        private final ActionPanel panel = new ActionPanel();
        private final RowState state;

        public ActionRenderer(RowState state) {
            this.state = state;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            panel.update(table, row, isSelected, clientIdAtView(table, row), state);
            return panel;
        }
    }

    public static final class ActionEditor extends AbstractCellEditor implements TableCellEditor {
        private final ActionPanel panel = new ActionPanel();
        private final RowState state;
        private String clientId;

        public ActionEditor(RowState state, ActionHandler handler) {
            this.state = state;
            // Stop editing before running the action: dialogs (and row deletion) must not happen
            // while the editor still owns the cell.
            panel.folderButton.addActionListener(e -> {
                String id = clientId;
                stopCellEditing();
                handler.editDirectory(id);
            });
            panel.bellButton.addActionListener(e -> {
                String id = clientId;
                stopCellEditing();
                handler.toggleNotifications(id);
            });
            panel.deleteButton.addActionListener(e -> {
                String id = clientId;
                stopCellEditing();
                handler.deleteClient(id);
            });
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            clientId = clientIdAtView(table, row);
            panel.update(table, row, isSelected, clientId, state);
            return panel;
        }

        @Override
        public Object getCellEditorValue() {
            return "";
        }
    }

    // ------------------------------------------------------------------ records window

    /** Event column of the records window: connected / disconnected / power-off icon. */
    public static final class RecordsWindowRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setIcon(null);
            if (column == 0) {
                String status = value != null ? value.toString() : "";
                String name = "CONNECT".equals(status) ? "connected.png"
                        : "DISCONNECT".equals(status) ? "disconnected.png"
                        : "POWEROFF".equals(status) ? "x-off.png"
                        : "NO_SCREEN".equals(status) ? "no-screen.png"
                        : "VPN_OFF".equals(status) ? "vpn-off.png" : null;
                ImageIcon icon = name == null ? null : Icons.icon(name, Math.max(8, table.getRowHeight() - 10));
                if (icon != null) {
                    setText(" ");
                    setIcon(icon);
                    setToolTipText(status);
                }
            }
            setOpaque(true);
            setHorizontalAlignment(SwingConstants.CENTER);
            setVerticalAlignment(SwingConstants.CENTER);
            return this;
        }
    }
}
