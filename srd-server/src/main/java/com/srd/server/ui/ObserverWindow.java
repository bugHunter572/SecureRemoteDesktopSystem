package com.srd.server.ui;

import com.srd.common.status.VpnGuardStatus;
import com.srd.common.ui.Icons;
import com.srd.server.util.Images;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;

/**
 * Window of the watch-only server: the same view as the main server (thumbnail wall left, client
 * table right, 60:40) without menus, buttons, records or actions. Double-click a tile or a row for
 * a watch-only live window. EDT only (except {@link #tileTargetWidth()}).
 */
public final class ObserverWindow extends JFrame implements ClientTableRenderers.RowState {
    private static final double SPLIT_RATIO = 0.60;

    private final String baseTitle;
    private final Consumer<String> openLive;
    private final ThumbnailGrid grid = new ThumbnailGrid();
    private final Map<String, ThumbnailPanel> tiles = new HashMap<>();
    private final ClientTableModel model = new ClientTableModel();
    private final JTable table;
    private final JSplitPane split;
    private final SimpleDateFormat seen = new SimpleDateFormat("MM-dd HH:mm:ss");
    private volatile double tileScale = 1.0;
    private int viewers;
    private Timer resizeTimer;
    private Timer reorderTimer;
    private final JButton startRecordButton;
    private final JButton stopRecordButton;
    private final JToggleButton fullScreenThumbsButton;
    private String selectedClientId;
    private Consumer<String> recStart = id -> { };
    private Consumer<String> recStop = id -> { };
    private java.util.function.Predicate<String> isRecording = id -> false;

    public ObserverWindow(String title, Consumer<String> openLive) {
        super(title);
        this.baseTitle = title;
        this.openLive = openLive;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1200, 800);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setLocationByPlatform(true);
        ImageIcon icon = Icons.icon("server-icon.png", 64);
        if (icon != null) setIconImage(icon.getImage());
        setLayout(new BorderLayout());

        table = new JTable(model) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        TableRowSorter<ClientTableModel> sorter = new TableRowSorter<>(model);
        sorter.setComparator(ClientTableModel.COL_NAME, String.CASE_INSENSITIVE_ORDER);
        sorter.setComparator(ClientTableModel.COL_IP, ClientTableModel::compareIps);
        sorter.setComparator(ClientTableModel.COL_STATUS, String.CASE_INSENSITIVE_ORDER);
        sorter.setComparator(ClientTableModel.COL_LAST_SEEN, String.CASE_INSENSITIVE_ORDER);
        sorter.setComparator(ClientTableModel.COL_VPN, Comparator.comparing(
                (Object o) -> o instanceof VpnGuardStatus ? ((VpnGuardStatus) o).state.ordinal() : -1));
        sorter.setSortable(ClientTableModel.COL_NO, false);
        table.setRowSorter(sorter);
        sorter.toggleSortOrder(ClientTableModel.COL_IP);

        ClientTableRenderers.CenteredRenderer centered = new ClientTableRenderers.CenteredRenderer(this);
        column(ClientTableModel.COL_NAME, 90).setCellRenderer(centered);
        column(ClientTableModel.COL_IP, 110).setCellRenderer(centered);
        column(ClientTableModel.COL_STATUS, 60).setCellRenderer(new ClientTableRenderers.StatusRenderer(this));
        column(ClientTableModel.COL_LAST_SEEN, 110).setCellRenderer(centered);
        column(ClientTableModel.COL_NO, 44).setCellRenderer(new ClientTableRenderers.RowNumberRenderer(this));
        column(ClientTableModel.COL_VPN, 44).setCellRenderer(new ClientTableRenderers.VpnRenderer(this));
        // no records or actions here
        table.removeColumn(column(ClientTableModel.COL_RECORDS, 0));
        table.removeColumn(column(ClientTableModel.COL_ACTION, 0));
        table.moveColumn(table.convertColumnIndexToView(ClientTableModel.COL_NO), 0);
        table.moveColumn(table.convertColumnIndexToView(ClientTableModel.COL_VPN),
                table.convertColumnIndexToView(ClientTableModel.COL_STATUS) + 1);
        table.setRowHeight(24);
        table.setFont(new java.awt.Font("PRK P Chongbong", java.awt.Font.BOLD, 14));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) return;
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0) openLive.accept(model.clientIdAt(table.convertRowIndexToModel(row)));
            }
        });

        JScrollPane thumbScroll = new JScrollPane(grid, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        thumbScroll.setBorder(null);
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, thumbScroll, new JScrollPane(table));
        split.setResizeWeight(SPLIT_RATIO);
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, evt -> scheduleResize());
        add(split, BorderLayout.CENTER);

        // ---- bottom buttons (same as the main server) ----
        startRecordButton = flatButton(Icons.icon("rec-start.png", 32), "Start Recording");
        startRecordButton.addActionListener(e -> {
            if (selectedClientId == null) {
                JOptionPane.showMessageDialog(this, "No connected client selected!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            recStart.accept(selectedClientId);
        });
        stopRecordButton = flatButton(Icons.icon("rec-stop.png", 32), "Stop Recording");
        stopRecordButton.addActionListener(e -> {
            if (selectedClientId != null) recStop.accept(selectedClientId);
        });
        fullScreenThumbsButton = new JToggleButton(Icons.icon("full-screen.png", 32));
        fullScreenThumbsButton.setBorderPainted(false);
        fullScreenThumbsButton.setContentAreaFilled(false);
        fullScreenThumbsButton.setFocusPainted(false);
        fullScreenThumbsButton.setOpaque(false);
        fullScreenThumbsButton.setSelectedIcon(Icons.icon("minimize.png", 32));
        fullScreenThumbsButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        fullScreenThumbsButton.setToolTipText("Show thumbnails full width");
        fullScreenThumbsButton.addActionListener(e -> showThumbnailsFullScreen());
        JPanel buttons = new JPanel();
        buttons.add(startRecordButton);
        buttons.add(stopRecordButton);
        buttons.add(fullScreenThumbsButton);
        add(buttons, BorderLayout.SOUTH);
        updateRecordButtons();

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = table.getSelectedRow();
            if (row < 0) return;
            String id = model.clientIdAt(table.convertRowIndexToModel(row));
            if (id != null && !id.equals(selectedClientId)) select(id);
        });

        grid.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                scheduleResize();
            }
        });
    }

    @Override
    public void setVisible(boolean b) {
        super.setVisible(b);
        if (b) SwingUtilities.invokeLater(() -> {
            split.setDividerLocation(SPLIT_RATIO);
            scheduleResize();
        });
    }

    private TableColumn column(int modelIndex, int preferred) {
        TableColumn c = table.getColumnModel().getColumn(table.convertColumnIndexToView(modelIndex));
        if (preferred > 0) c.setPreferredWidth(preferred);
        return c;
    }

    private static JButton flatButton(ImageIcon icon, String tip) {
        JButton b = new JButton(icon);
        if (icon == null) b.setText(tip);
        b.setToolTipText(tip);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    /** Hooks of the Start / Stop Recording buttons (they act on the selected client). */
    public void setRecordingHandlers(Consumer<String> start, Consumer<String> stop, java.util.function.Predicate<String> isRecording) {
        this.recStart = start;
        this.recStop = stop;
        this.isRecording = isRecording;
        updateRecordButtons();
    }

    public void setRecording(String clientId, boolean recording) {
        ThumbnailPanel p = tiles.get(clientId);
        if (p != null) p.setRecording(recording);
        if (clientId.equals(selectedClientId)) updateRecordButtons();
    }

    private void select(String id) {
        if (selectedClientId != null && !selectedClientId.equals(id)) {
            ThumbnailPanel prev = tiles.get(selectedClientId);
            if (prev != null) prev.setBorder(BorderFactory.createLineBorder(Color.BLACK));
        }
        selectedClientId = id;
        ThumbnailPanel sel = tiles.get(id);
        if (sel != null) sel.setBorder(BorderFactory.createLineBorder(Color.RED, 2));
        updateRecordButtons();
    }

    private void updateRecordButtons() {
        boolean rec = selectedClientId != null && isRecording.test(selectedClientId);
        startRecordButton.setEnabled(selectedClientId != null && !rec);
        stopRecordButton.setEnabled(rec);
    }

    private void showThumbnailsFullScreen() {
        int total = split.getWidth();
        if (total <= 0) {
            split.setDividerLocation(fullScreenThumbsButton.isSelected() ? 0.99 : SPLIT_RATIO);
        } else if (fullScreenThumbsButton.isSelected()) {
            split.setDividerLocation(Math.max(0, total - split.getDividerSize()));
        } else {
            split.setDividerLocation(SPLIT_RATIO);
        }
        scheduleResize();
    }

    // ------------------------------------------------------------------ RowState (renderers)

    @Override
    public boolean isAlertHighlighted(String clientId) {
        return false;
    }

    @Override
    public boolean isNotificationEnabled(String clientId) {
        return false;
    }

    @Override
    public boolean hasCustomDirectory(String clientId) {
        return false;
    }

    // ------------------------------------------------------------------ content

    /** Number of PCs currently watching this PC's broadcast (shown in the title). */
    public void setViewers(int n) {
        viewers = n;
        updateTitle();
    }

    /** Width the controller should keep for each client's picture: just enough for the tiles as drawn now. */
    public int tileTargetWidth() {
        return Images.tileWidthFor((int) Math.round(grid.tileWidth() * tileScale));
    }

    public void addOrUpdate(String clientId, String displayName, Dimension screen) {
        ThumbnailPanel p = tiles.get(clientId);
        if (p != null) {
            p.setDisplayName(displayName);
            p.setClientScreenSize(screen);
            model.setCell(clientId, ClientTableModel.COL_NAME, displayName);
            updateTitle();
            return;
        }
        p = new ThumbnailPanel(clientId, displayName, screen);
        p.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                if (e.getClickCount() == 1) {
                    select(clientId);
                    int modelRow = model.rowOf(clientId);
                    int viewRow = modelRow < 0 ? -1 : table.convertRowIndexToView(modelRow);
                    if (viewRow >= 0) table.setRowSelectionInterval(viewRow, viewRow);
                } else if (e.getClickCount() == 2) {
                    openLive.accept(clientId);
                }
            }
        });
        tiles.put(clientId, p);
        grid.add(p);
        model.addClientRow(displayName, clientId, ClientTableModel.STATUS_DISCONNECTED, "Never", "", null);
        scheduleReorder();
        updateTitle();
    }

    /**
     * The main server's client list: every listed client gets a tile (shown offline until it
     * connects here) with the main server's name; offline tiles the main server no longer lists are removed.
     */
    public void applySync(java.util.Map<String, String> names, java.util.Set<String> connected) {
        for (java.util.Map.Entry<String, String> e : names.entrySet()) {
            String id = e.getKey();
            String name = e.getValue().isEmpty() ? id : e.getValue();
            boolean isNew = !tiles.containsKey(id);
            addOrUpdate(id, name, new Dimension(1920, 1080));
            if (isNew && !connected.contains(id)) {
                tiles.get(id).setOffline(true, "Not connected");
                model.setCell(id, ClientTableModel.COL_STATUS, ClientTableModel.STATUS_DISCONNECTED);
            }
        }
        for (String id : new ArrayList<>(tiles.keySet())) {
            if (names.containsKey(id) || connected.contains(id)) continue;
            ThumbnailPanel p = tiles.remove(id);
            grid.remove(p);
            int row = model.rowOf(id);
            if (row >= 0) model.removeRow(row);
            clearSelectionIf(id);
        }
        scheduleReorder();
        updateTitle();
        table.repaint();
    }

    public String displayName(String clientId) {
        ThumbnailPanel p = tiles.get(clientId);
        return p == null ? clientId : p.displayName();
    }

    public void setThumbnail(String clientId, java.awt.image.BufferedImage tile, Dimension fullSize) {
        ThumbnailPanel p = tiles.get(clientId);
        if (p == null) return;
        p.setClientScreenSize(fullSize);
        p.setImage(tile);
        model.setCell(clientId, ClientTableModel.COL_LAST_SEEN, seen.format(new Date()));
    }

    /** Disconnect of the selected client: drop the selection. */
    public void clearSelectionIf(String clientId) {
        if (clientId.equals(selectedClientId)) {
            selectedClientId = null;
            ThumbnailPanel p = tiles.get(clientId);
            if (p != null) p.setBorder(BorderFactory.createLineBorder(Color.BLACK));
            updateRecordButtons();
        }
    }

    public void setOffline(String clientId, boolean offline, String text) {
        ThumbnailPanel p = tiles.get(clientId);
        if (p != null) p.setOffline(offline, text);
        model.setCell(clientId, ClientTableModel.COL_STATUS,
                offline ? ClientTableModel.STATUS_DISCONNECTED : ClientTableModel.STATUS_CONNECTED);
        model.setCell(clientId, ClientTableModel.COL_LAST_SEEN, seen.format(new Date()));
        table.repaint();
        updateTitle();
    }

    public void setVpn(String clientId, VpnGuardStatus st) {
        ThumbnailPanel p = tiles.get(clientId);
        if (p != null) p.setVpnStatus(st);
        model.setCell(clientId, ClientTableModel.COL_VPN, st);
    }

    private void updateTitle() {
        int online = 0;
        for (ThumbnailPanel p : tiles.values()) if (!p.isOffline()) online++;
        setTitle(baseTitle + "  -  " + online + " online / " + tiles.size() + " clients"
                + (viewers > 0 ? "  -  " + viewers + (viewers == 1 ? " viewer" : " viewers") : ""));
    }

    /** Tiles in IP order; re-sorted once for a burst of new clients. */
    private void scheduleReorder() {
        if (reorderTimer != null && reorderTimer.isRunning()) return;
        reorderTimer = new Timer(60, e -> {
            List<String> ids = new ArrayList<>(tiles.keySet());
            ids.sort(ClientTableModel::compareIps);
            grid.reorder(ids, tiles);
            grid.resizeThumbnails(true);
        });
        reorderTimer.setRepeats(false);
        reorderTimer.start();
    }

    private void scheduleResize() {
        if (resizeTimer != null) resizeTimer.stop();
        resizeTimer = new Timer(80, e -> {
            try {
                tileScale = Math.max(1.0, getGraphicsConfiguration().getDefaultTransform().getScaleX());
            } catch (RuntimeException ignored) {
            }
            grid.resizeThumbnails(true);
        });
        resizeTimer.setRepeats(false);
        resizeTimer.start();
    }
}
