package com.srd.server.ui;

import com.srd.common.status.VpnGuardStatus;
import com.srd.common.ui.Icons;
import com.srd.common.util.Log;
import com.srd.server.ServerApp;
import com.srd.server.ServerController;
import com.srd.server.db.ClientRow;
import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.Point;
import java.awt.PopupMenu;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.RowSorterEvent;
import javax.swing.event.TableModelEvent;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;

/**
 * The server console window: thumbnail wall (left) and client table (right) in a 60:40 split,
 * record / full-wall buttons at the bottom, menus and tray icon. Layout, texts, colours and
 * column indices are those of v2. EDT only; the controller marshals every call onto the EDT.
 */
public final class ServerWindow extends JFrame implements ClientTableRenderers.RowState {
    static final double NORMAL_THUMBNAIL_SPLIT_RATIO = 0.60;
    public static final String BASE_TITLE = "Secure Remote Desktop Server v" + ServerApp.VERSION;
    static final String ABOUT_TEXT = "Secure Remote Desktop Server\nVersion " + ServerApp.VERSION
            + "\nUpdated " + com.srd.common.util.BuildInfo.BUILT + "\n\n© 2026 All rights reserved.";

    private final ServerController ctl;
    private final ClientTableModel clientTableModel = new ClientTableModel();
    private final JTable clientTable;
    private final TableRowSorter<ClientTableModel> sorter;
    private final ThumbnailGrid thumbnailPanel = new ThumbnailGrid();
    private volatile double tileScale = 1.0;
    private int requestedTileWidth;
    private javax.swing.Timer biggerTilesTimer;
    private javax.swing.Timer reorderTimer;
    private final Map<String, ThumbnailPanel> screenLabels = new HashMap<>();
    private final JSplitPane splitPane;
    private final JButton startRecordButton;
    private final JButton stopRecordButton;
    private final JToggleButton fullScreenThumbsButton;
    private javax.swing.Timer thumbnailResizeTimer;
    private javax.swing.Timer tableResizeTimer;
    private String selectedClientId;
    private TrayIcon trayIcon;

    public ServerWindow(ServerController ctl, boolean notificationsOn) {
        this.ctl = ctl;
        setTitle(BASE_TITLE);
        setSize(1200, 800);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout());
        BufferedImage appIcon = Icons.image("server-icon.png");
        if (appIcon != null) setIconImage(appIcon);

        setJMenuBar(buildMenuBar(notificationsOn));

        // ---- client table ----
        clientTable = new JTable(clientTableModel) {
            @Override
            public String getToolTipText(MouseEvent e) {
                Point p = e.getPoint();
                int row = rowAtPoint(p);
                int column = columnAtPoint(p);
                if (row >= 0 && column >= 0 && convertColumnIndexToModel(column) == ClientTableModel.COL_RECORDS) {
                    Component renderer = prepareRenderer(getCellRenderer(row, column), row, column);
                    if (renderer instanceof Container) {
                        Rectangle cellRect = getCellRect(row, column, true);
                        int adjustedX = p.x - cellRect.x;
                        int adjustedY = p.y - cellRect.y;
                        for (Component comp : ((Container) renderer).getComponents()) {
                            if (comp instanceof JLabel && comp.getBounds().contains(adjustedX, adjustedY)) {
                                String tooltip = ((JLabel) comp).getToolTipText();
                                if (tooltip != null) return tooltip;
                            }
                        }
                    }
                }
                return super.getToolTipText(e);
            }
        };
        sorter = new TableRowSorter<>(clientTableModel);
        clientTable.setRowSorter(sorter);
        // The default String comparator uses java.text.Collator, ~50x slower; the table re-sorts on every
        // added client, so with 150+ clients arriving at once that alone froze the window for ~0.7 s.
        sorter.setComparator(ClientTableModel.COL_NAME, String.CASE_INSENSITIVE_ORDER);
        sorter.setComparator(ClientTableModel.COL_IP, ClientTableModel::compareIps);
        sorter.setComparator(ClientTableModel.COL_STATUS, String.CASE_INSENSITIVE_ORDER);
        sorter.setComparator(ClientTableModel.COL_LAST_SEEN, String.CASE_INSENSITIVE_ORDER);
        sorter.setComparator(ClientTableModel.COL_VPN, Comparator.comparing(
                (Object o) -> o instanceof VpnGuardStatus ? ((VpnGuardStatus) o).state.ordinal() : -1));
        sorter.toggleSortOrder(ClientTableModel.COL_IP);
        sorter.addRowSorterListener(e -> {
            if (e.getType() == RowSorterEvent.Type.SORT_ORDER_CHANGED) {
                SwingUtilities.invokeLater(this::updateThumbnailLayout);
            }
        });

        // Low minWidths so resizeRightTableColumns() can size columns to fit content
        // (under AUTO_RESIZE_OFF a column can't shrink below its minWidth).
        ClientTableRenderers.CenteredRenderer centered = new ClientTableRenderers.CenteredRenderer(this);
        column(ClientTableModel.COL_NAME, 40, 10000, -1).setCellRenderer(centered);
        column(ClientTableModel.COL_IP, 40, 10000, -1).setCellRenderer(centered);
        column(ClientTableModel.COL_STATUS, 30, 10000, -1).setCellRenderer(new ClientTableRenderers.StatusRenderer(this));
        column(ClientTableModel.COL_LAST_SEEN, 40, 10000, -1).setCellRenderer(centered);
        column(ClientTableModel.COL_RECORDS, 120, 10000, 180).setCellRenderer(new ClientTableRenderers.RecordsRenderer(this));
        TableColumn action = column(ClientTableModel.COL_ACTION, 90, 140, 96);
        action.setCellRenderer(new ClientTableRenderers.ActionRenderer(this));
        action.setCellEditor(new ClientTableRenderers.ActionEditor(this, new ClientTableRenderers.ActionHandler() {
            @Override
            public void editDirectory(String clientId) {
                ctl.editClientDirectory(clientId);
            }

            @Override
            public void toggleNotifications(String clientId) {
                ctl.toggleClientNotifications(clientId);
            }

            @Override
            public void deleteClient(String clientId) {
                int confirm = JOptionPane.showConfirmDialog(ServerWindow.this,
                        "Are you sure you want to delete client: " + clientId + "?", "Confirm Delete",
                        JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) ctl.deleteClient(clientId);
            }
        }));
        // "No." column (model index 6): fixed narrow width, row number, not sortable, shown first.
        column(ClientTableModel.COL_NO, 40, 56, 46).setCellRenderer(new ClientTableRenderers.RowNumberRenderer(this));
        sorter.setSortable(ClientTableModel.COL_NO, false);
        // VPN Guard column (model index 7), shown right after Status.
        column(ClientTableModel.COL_VPN, 40, 60, 44).setCellRenderer(new ClientTableRenderers.VpnRenderer(this));
        clientTable.moveColumn(ClientTableModel.COL_NO, 0);
        clientTable.moveColumn(clientTable.convertColumnIndexToView(ClientTableModel.COL_VPN),
                clientTable.convertColumnIndexToView(ClientTableModel.COL_STATUS) + 1);
        clientTable.setRowHeight(24);
        clientTable.setFont(new Font("PRK P Chongbong", Font.BOLD, 14));
        clientTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        // F5: refresh the selected client's screen.
        clientTable.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "refreshScreen");
        clientTable.getActionMap().put("refreshScreen", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int selectedRow = clientTable.getSelectedRow();
                if (selectedRow >= 0) {
                    String clientId = clientTableModel.clientIdAt(clientTable.convertRowIndexToModel(selectedRow));
                    ctl.refreshClientScreen(clientId);
                    Log.info("F5 pressed, refreshing client: " + clientId);
                } else {
                    JOptionPane.showMessageDialog(ServerWindow.this, "No client selected for refresh!", "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        // Selection → thumbnail border + window title.
        clientTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int selectedRow = clientTable.getSelectedRow();
            if (selectedRow < 0) return;
            String clientId = clientTableModel.clientIdAt(clientTable.convertRowIndexToModel(selectedRow));
            if (clientId != null && !clientId.equals(selectedClientId)) select(clientId);
        });

        // Double-click a row: records window.
        clientTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = clientTable.rowAtPoint(e.getPoint());
                    int col = clientTable.columnAtPoint(e.getPoint());
                    if (row >= 0 && col >= 0 && clientTable.convertColumnIndexToModel(col) != ClientTableModel.COL_ACTION) {
                        ctl.showClientRecords(clientTableModel.clientIdAt(clientTable.convertRowIndexToModel(row)));
                    }
                }
            }
        });

        // Display-name edits are saved by the controller (SQL on the db thread, not here).
        clientTableModel.addTableModelListener(e -> {
            if (e.getType() == TableModelEvent.UPDATE && e.getColumn() == ClientTableModel.COL_NAME
                    && e.getFirstRow() >= 0 && e.getFirstRow() == e.getLastRow() && e.getFirstRow() < clientTableModel.getRowCount()) {
                int row = e.getFirstRow();
                String clientId = clientTableModel.clientIdAt(row);
                Object name = clientTableModel.getValueAt(row, ClientTableModel.COL_NAME);
                String newName = name == null ? "" : name.toString().trim();
                ThumbnailPanel panel = screenLabels.get(clientId);
                if (panel != null) panel.setDisplayName(newName);
                ctl.renameClient(clientId, newName);
            }
        });

        JScrollPane tableScrollPane = new JScrollPane(clientTable);
        tableScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        tableScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        JPanel mainPanel = new JPanel(new BorderLayout());
        JScrollPane thumbScroll = new JScrollPane(thumbnailPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, thumbScroll, tableScrollPane);
        splitPane.setResizeWeight(0.6);
        mainPanel.add(splitPane, BorderLayout.CENTER);
        add(mainPanel, BorderLayout.CENTER);

        // ---- bottom buttons ----
        startRecordButton = flatButton(Icons.icon("rec-start.png", 32), "Start Recording");
        startRecordButton.addActionListener(e -> {
            if (selectedClientId == null || !ctl.isConnected(selectedClientId)) {
                JOptionPane.showMessageDialog(this, "No connected client selected!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            ctl.startRecording(selectedClientId);
        });
        stopRecordButton = flatButton(Icons.icon("rec-stop.png", 32), "Stop Recording");
        stopRecordButton.addActionListener(e -> {
            if (selectedClientId == null) {
                JOptionPane.showMessageDialog(this, "No client selected!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            ctl.stopRecording(selectedClientId);
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

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(startRecordButton);
        buttonPanel.add(stopRecordButton);
        buttonPanel.add(fullScreenThumbsButton);
        add(buttonPanel, BorderLayout.SOUTH);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                Log.info("Main window opened and visible at: " + getLocation());
            }

            @Override
            public void windowClosing(WindowEvent e) {
                int response = JOptionPane.showConfirmDialog(ServerWindow.this, "Are you sure you want to exit?",
                        "Confirm Exit", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (response == JOptionPane.YES_OPTION) {
                    Log.info("Window is closing...");
                    ctl.exit();
                } else {
                    Log.info("Window close operation cancelled by user.");
                }
            }
        });

        // Debounced re-layout when the split / window changes size.
        thumbnailPanel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                scheduleThumbnailResize();
                scheduleTableResize();
            }
        });
        splitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, evt -> {
            scheduleThumbnailResize();
            scheduleTableResize();
        });
        splitPane.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                scheduleThumbnailResize();
                scheduleTableResize();
            }
        });
        setFocusable(true);
    }

    /** Shows the window, sets the 60:40 split and adds the tray icon. */
    public void open() {
        setVisible(true);
        requestFocusInWindow();
        SwingUtilities.invokeLater(() -> {
            splitPane.setDividerLocation(NORMAL_THUMBNAIL_SPLIT_RATIO);
            scheduleThumbnailResize();
            scheduleTableResize();
        });
        addTrayIcon();
    }

    private TableColumn column(int modelIndex, int min, int max, int preferred) {
        TableColumn c = clientTable.getColumnModel().getColumn(clientTable.convertColumnIndexToView(modelIndex));
        c.setMinWidth(min);
        c.setMaxWidth(max);
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

    private JMenuBar buildMenuBar(boolean notificationsOn) {
        JMenuBar menuBar = new JMenuBar();
        JMenu settingsMenu = new JMenu("Edit");
        JMenu viewMenu = new JMenu("View");
        JMenu clearMenu = new JMenu("Clear");
        JMenu helpMenu = new JMenu("Help");
        JMenu databaseMenu = new JMenu("Database");

        JMenuItem exportDBItem = new JMenuItem("Export Database");
        exportDBItem.addActionListener(e -> ctl.exportDatabase());
        JMenuItem importDBItem = new JMenuItem("Import Database");
        importDBItem.addActionListener(e -> ctl.importDatabase());
        databaseMenu.add(exportDBItem);
        databaseMenu.add(importDBItem);

        JMenuItem serverConfigItem = new JMenuItem("Server Configuration");
        serverConfigItem.addActionListener(e -> ctl.showServerConfigurationDialog());
        settingsMenu.add(serverConfigItem);
        JMenuItem notifySettingsItem = new JMenuItem("Notification Settings");
        notifySettingsItem.addActionListener(e -> ctl.showNotificationSettingsDialog());
        settingsMenu.add(notifySettingsItem);

        JMenuItem clearRecordsItem = new JMenuItem("Clear Records");
        clearRecordsItem.addActionListener(e -> ctl.clearAllRecords());
        JMenuItem clearScreenshotsItem = new JMenuItem("Clear Screenshots");
        clearScreenshotsItem.addActionListener(e -> ctl.clearAllScreenshots());
        JMenuItem addClientItem = new JMenuItem("Add Client");
        addClientItem.addActionListener(e -> addManualClient());

        JMenuItem connectPeerItem = new JMenuItem("Connect to Broadcasting Client");
        connectPeerItem.addActionListener(e -> ctl.connectToBroadcastingClient());
        settingsMenu.add(connectPeerItem);
        JMenuItem syncItem = new JMenuItem("Sync to Alternative Servers");
        syncItem.setToolTipText("Send the client list (names) and tag ranges to the observers listed under Alternative IPs");
        syncItem.addActionListener(e -> ctl.syncAlternativeServers());
        settingsMenu.add(syncItem);

        JCheckBoxMenuItem notificationsItem = new JCheckBoxMenuItem("Toggle Notifications");
        notificationsItem.setState(notificationsOn);
        notificationsItem.addActionListener(e -> notificationsItem.setState(ctl.toggleNotifications()));
        notificationsItem.setToolTipText("Turn On/Off Notifications");
        settingsMenu.add(notificationsItem);

        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> ctl.exit());
        JMenuItem delayTimeItem = new JMenuItem("Screenshot Interval");
        delayTimeItem.addActionListener(e -> ctl.setDelayTime());
        JMenuItem refreshItem = new JMenuItem("Refresh All Screens");
        refreshItem.addActionListener(e -> ctl.refreshAllScreens());
        JMenuItem aboutItem = new JMenuItem("About Me");
        aboutItem.addActionListener(e -> JOptionPane.showMessageDialog(this, ABOUT_TEXT, "About", JOptionPane.INFORMATION_MESSAGE));
        settingsMenu.add(addClientItem);
        settingsMenu.add(exitItem);
        clearMenu.add(clearRecordsItem);
        clearMenu.add(clearScreenshotsItem);
        viewMenu.add(refreshItem);
        viewMenu.add(delayTimeItem);
        helpMenu.add(aboutItem);
        menuBar.add(settingsMenu);
        menuBar.add(viewMenu);
        menuBar.add(databaseMenu);
        menuBar.add(clearMenu);
        menuBar.add(helpMenu);
        return menuBar;
    }

    private void addManualClient() {
        JPanel panel = new JPanel(new GridLayout(3, 2));
        JTextField displayNameField = new JTextField(20);
        JTextField ipField = new JTextField(20);
        panel.add(new JLabel("Display Name:"));
        panel.add(displayNameField);
        panel.add(new JLabel("IP Address:"));
        panel.add(ipField);
        int results = JOptionPane.showConfirmDialog(this, panel, "Add New Client", JOptionPane.OK_CANCEL_OPTION);
        if (results != JOptionPane.OK_OPTION) return;
        String ip = ipField.getText().trim();
        if (ip.isEmpty() || hasClient(ip)) return;
        String displayName = displayNameField.getText().trim();
        ctl.addManualClient(displayName.isEmpty() ? ip : displayName, ip);
    }

    // ------------------------------------------------------------------ RowState (renderers)

    @Override
    public boolean isAlertHighlighted(String clientId) {
        return ctl.alerts().isHighlighted(clientId);
    }

    @Override
    public boolean isNotificationEnabled(String clientId) {
        return ctl.isNotificationEnabled(clientId);
    }

    @Override
    public boolean hasCustomDirectory(String clientId) {
        return ctl.hasCustomDirectory(clientId);
    }

    // ------------------------------------------------------------------ rows & thumbnails (EDT)

    public boolean hasClient(String clientId) {
        return screenLabels.containsKey(clientId) || clientTableModel.rowOf(clientId) >= 0;
    }

    /** Replaces all rows (startup / after a DB import). */
    public void loadRows(List<ClientRow> rows, Map<String, VpnGuardStatus> vpn) {
        if (clientTable.isEditing()) clientTable.getCellEditor().cancelCellEditing();
        clientTableModel.setRowCount(0);
        screenLabels.clear();
        thumbnailPanel.removeAll();
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm:ss");
        for (ClientRow r : rows) {
            Log.info("Loading client from DB: " + r.clientId + " (" + r.displayName + ")");
            String lastSeenStr = r.lastSeen == 0 ? "Never" : sdf.format(new java.util.Date(r.lastSeen));
            addClientInternal(r.clientId, r.displayName, ClientTableModel.STATUS_DISCONNECTED, lastSeenStr, r.recordsCell,
                    vpn.get(r.clientId), new Dimension(1920, 1080));
        }
        updateThumbnailLayout();
        scheduleTableResize();
    }

    public void addClient(String clientId, String displayName, String status, String lastSeen, String records,
                          VpnGuardStatus vpn, Dimension screen) {
        if (hasClient(clientId)) return;
        addClientInternal(clientId, displayName, status, lastSeen, records, vpn, screen);
        scheduleThumbnailReorder();
        scheduleTableResize();
        Log.info("Added thumbnail for client: " + clientId + ", total thumbnails: " + thumbnailPanel.getComponentCount());
    }

    private void addClientInternal(String clientId, String displayName, String status, String lastSeen, String records,
                                   VpnGuardStatus vpn, Dimension screen) {
        clientTableModel.addClientRow(displayName, clientId, status, lastSeen, records, vpn);
        ThumbnailPanel screenPanel = new ThumbnailPanel(clientId, displayName, screen);
        screenPanel.addMouseListener(createMouseListener(clientId));
        screenPanel.setVpnStatus(vpn);
        thumbnailPanel.add(screenPanel);
        screenLabels.put(clientId, screenPanel);
    }

    public void removeClient(String clientId) {
        if (clientTable.isEditing()) clientTable.getCellEditor().cancelCellEditing();
        int row = clientTableModel.rowOf(clientId);
        if (row >= 0) clientTableModel.removeRow(row);
        ThumbnailPanel panel = screenLabels.remove(clientId);
        if (panel != null) {
            thumbnailPanel.remove(panel);
            updateThumbnailLayout();
        }
        if (clientId.equals(selectedClientId)) {
            selectedClientId = null;
            setTitle(BASE_TITLE);
        }
    }

    public String displayName(String clientId) {
        Object v = clientTableModel.cell(clientId, ClientTableModel.COL_NAME);
        return v != null && !v.toString().isEmpty() ? v.toString() : clientId;
    }

    public String status(String clientId) {
        Object v = clientTableModel.cell(clientId, ClientTableModel.COL_STATUS);
        return v == null ? null : v.toString();
    }

    public void setStatus(String clientId, String status) {
        clientTableModel.setCell(clientId, ClientTableModel.COL_STATUS, status);
        clientTable.repaint(); // the VPN cell of this row is dimmed/undimmed with the status
    }

    public void setLastSeen(String clientId, String lastSeen) {
        clientTableModel.setCell(clientId, ClientTableModel.COL_LAST_SEEN, lastSeen);
    }

    public void setRecordsCell(String clientId, String cell) {
        clientTableModel.setCell(clientId, ClientTableModel.COL_RECORDS, cell);
    }

    public void setVpn(String clientId, VpnGuardStatus st) {
        clientTableModel.setCell(clientId, ClientTableModel.COL_VPN, st);
        ThumbnailPanel p = screenLabels.get(clientId);
        if (p != null) p.setVpnStatus(st);
    }

    public void setThumbnail(String clientId, BufferedImage tile, Dimension fullSize) {
        ThumbnailPanel p = screenLabels.get(clientId);
        if (p == null) return;
        p.setClientScreenSize(fullSize);
        p.setImage(tile);
    }

    public void setScreenSize(String clientId, Dimension size) {
        ThumbnailPanel p = screenLabels.get(clientId);
        if (p != null) p.setClientScreenSize(size);
    }

    public void setOffline(String clientId, boolean offline, String text) {
        ThumbnailPanel p = screenLabels.get(clientId);
        if (p != null) p.setOffline(offline, text);
    }

    public void setDisplayNameOnThumbnail(String clientId, String name) {
        ThumbnailPanel p = screenLabels.get(clientId);
        if (p != null) p.setDisplayName(name);
    }

    public void setRecording(String clientId, boolean recording) {
        ThumbnailPanel p = screenLabels.get(clientId);
        if (p != null) p.setRecording(recording);
        if (clientId.equals(selectedClientId)) updateRecordButtons();
    }

    /** Disconnect of the selected client: drop the selection (v2 behaviour). */
    public void clearSelectionIf(String clientId) {
        if (clientId.equals(selectedClientId)) {
            selectedClientId = null;
            setTitle(BASE_TITLE);
            ThumbnailPanel panel = screenLabels.get(clientId);
            if (panel != null) panel.setBorder(BorderFactory.createLineBorder(Color.BLACK));
        }
    }

    public List<String> clientIds() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < clientTableModel.getRowCount(); i++) ids.add(clientTableModel.clientIdAt(i));
        return ids;
    }

    public void repaintTable() {
        clientTable.repaint();
        scheduleTableResize();
    }

    public String selectedClientId() {
        return selectedClientId;
    }

    private void select(String cid) {
        if (selectedClientId != null && !selectedClientId.equals(cid)) {
            ThumbnailPanel prev = screenLabels.get(selectedClientId);
            if (prev != null) prev.setBorder(BorderFactory.createLineBorder(Color.BLACK));
        }
        selectedClientId = cid;
        ThumbnailPanel sel = screenLabels.get(cid);
        if (sel != null) sel.setBorder(BorderFactory.createLineBorder(Color.RED, 2));
        setTitle(BASE_TITLE + " - Controlling: " + cid);
        updateRecordButtons();
    }

    private void updateRecordButtons() {
        boolean rec = selectedClientId != null && ctl.isRecording(selectedClientId);
        startRecordButton.setEnabled(selectedClientId != null && !rec);
        stopRecordButton.setEnabled(rec);
    }

    private MouseListener createMouseListener(String cid) {
        return new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 1) {
                    if (screenLabels.get(cid) == null) return;
                    select(cid);
                    int modelRow = clientTableModel.rowOf(cid);
                    if (modelRow >= 0) {
                        int viewRow = clientTable.convertRowIndexToView(modelRow);
                        if (viewRow != -1) clientTable.setRowSelectionInterval(viewRow, viewRow);
                    }
                    requestFocusInWindow();
                } else if (e.getClickCount() == 2) {
                    ctl.openLiveView(cid);
                }
            }
        };
    }

    // ------------------------------------------------------------------ layout

    private void updateThumbnailLayout() {
        List<String> sortedClientIds = new ArrayList<>();
        for (int viewRow = 0; viewRow < clientTable.getRowCount(); viewRow++) {
            sortedClientIds.add(clientTableModel.clientIdAt(clientTable.convertRowIndexToModel(viewRow)));
        }
        thumbnailPanel.reorder(sortedClientIds, screenLabels);
        resizeThumbnails();
    }

    private void resizeThumbnails() {
        thumbnailPanel.resizeThumbnails(!fullScreenThumbsButton.isSelected());
        try {
            tileScale = Math.max(1.0, getGraphicsConfiguration().getDefaultTransform().getScaleX());
        } catch (RuntimeException ignored) {
        }
        int target = tileTargetWidth();
        // Bigger tiles than the stored pictures can fill (window enlarged, fewer clients): ask for fresh
        // snapshots soon instead of showing upscaled ones until each client's next interval.
        if (requestedTileWidth > 0 && target > requestedTileWidth * 1.3) scheduleRefreshForBiggerTiles();
        requestedTileWidth = target;
    }

    /** Width the controller should keep for each client's picture: just enough for the tiles as drawn now. */
    public int tileTargetWidth() {
        return com.srd.server.util.Images.tileWidthFor((int) Math.round(thumbnailPanel.tileWidth() * tileScale));
    }

    private void scheduleRefreshForBiggerTiles() {
        if (biggerTilesTimer != null) biggerTilesTimer.stop();
        biggerTilesTimer = new javax.swing.Timer(1500, evt -> ctl.refreshAllScreens());
        biggerTilesTimer.setRepeats(false);
        biggerTilesTimer.start();
    }

    /** Re-sorts/re-adds the tiles once for a burst of added clients (150 at a time on a server restart). */
    private void scheduleThumbnailReorder() {
        if (reorderTimer != null && reorderTimer.isRunning()) return;
        reorderTimer = new javax.swing.Timer(60, evt -> updateThumbnailLayout());
        reorderTimer.setRepeats(false);
        reorderTimer.start();
    }

    private void scheduleThumbnailResize() {
        if (thumbnailResizeTimer != null) thumbnailResizeTimer.stop();
        thumbnailResizeTimer = new javax.swing.Timer(80, evt -> resizeThumbnails());
        thumbnailResizeTimer.setRepeats(false);
        thumbnailResizeTimer.start();
    }

    private void scheduleTableResize() {
        if (tableResizeTimer != null) tableResizeTimer.stop();
        tableResizeTimer = new javax.swing.Timer(80, evt -> resizeRightTableColumns());
        tableResizeTimer.setRepeats(false);
        tableResizeTimer.start();
    }

    /**
     * Name / IP / Status / VPN / Last Seen are sized to fit their content; "Today's Records" absorbs
     * the remaining width. Works by model index, so it does not depend on the view order.
     */
    private void resizeRightTableColumns() {
        if (clientTable.getColumnModel().getColumnCount() < ClientTableModel.COLUMN_COUNT) return;
        int viewportWidth = 0;
        Container parent = clientTable.getParent();
        if (parent instanceof JViewport) viewportWidth = parent.getWidth();
        if (viewportWidth <= 0) viewportWidth = clientTable.getWidth();
        if (viewportWidth <= 0) return;

        int[] widths = new int[ClientTableModel.COLUMN_COUNT];
        widths[ClientTableModel.COL_NO] = 44;
        widths[ClientTableModel.COL_ACTION] = 96;
        widths[ClientTableModel.COL_NAME] = measureColumnWidth(ClientTableModel.COL_NAME, 90, 240);
        widths[ClientTableModel.COL_IP] = measureColumnWidth(ClientTableModel.COL_IP, 90, 150);
        widths[ClientTableModel.COL_STATUS] = measureColumnWidth(ClientTableModel.COL_STATUS, 60, 120);
        widths[ClientTableModel.COL_VPN] = 44; // icon only
        widths[ClientTableModel.COL_LAST_SEEN] = measureColumnWidth(ClientTableModel.COL_LAST_SEEN, 80, 150);
        int fixed = 0;
        for (int i = 0; i < widths.length; i++) if (i != ClientTableModel.COL_RECORDS) fixed += widths[i];
        widths[ClientTableModel.COL_RECORDS] = Math.max(120, viewportWidth - fixed);
        for (int model = 0; model < widths.length; model++) {
            int view = clientTable.convertColumnIndexToView(model);
            if (view >= 0) clientTable.getColumnModel().getColumn(view).setPreferredWidth(widths[model]);
        }
        clientTable.revalidate();
        clientTable.repaint();
    }

    /** Width needed for a column's header and all cells (via the renderers), clamped to [min, max]. */
    private int measureColumnWidth(int modelCol, int min, int max) {
        int w = min;
        try {
            int viewCol = clientTable.convertColumnIndexToView(modelCol);
            TableColumn column = clientTable.getColumnModel().getColumn(viewCol);
            Component header = clientTable.getTableHeader().getDefaultRenderer()
                    .getTableCellRendererComponent(clientTable, column.getHeaderValue(), false, false, -1, viewCol);
            w = Math.max(w, header.getPreferredSize().width);
            int rows = clientTable.getRowCount();
            for (int r = 0; r < rows; r++) {
                Component c = clientTable.prepareRenderer(clientTable.getCellRenderer(r, viewCol), r, viewCol);
                w = Math.max(w, c.getPreferredSize().width);
            }
        } catch (Exception ignored) {
        }
        return Math.max(min, Math.min(w + 14, max));
    }

    /** Full-width thumbnail wall toggle (on: 100 %, off: back to 60:40). */
    private void showThumbnailsFullScreen() {
        int totalSize = splitPane.getOrientation() == JSplitPane.HORIZONTAL_SPLIT ? splitPane.getWidth() : splitPane.getHeight();
        if (totalSize <= 0) {
            splitPane.setDividerLocation(fullScreenThumbsButton.isSelected() ? 0.99 : NORMAL_THUMBNAIL_SPLIT_RATIO);
        } else if (fullScreenThumbsButton.isSelected()) {
            splitPane.setDividerLocation(Math.max(0, totalSize - splitPane.getDividerSize()));
        } else {
            splitPane.setDividerLocation(NORMAL_THUMBNAIL_SPLIT_RATIO);
        }
        scheduleThumbnailResize();
        scheduleTableResize();
    }

    // ------------------------------------------------------------------ tray

    private void addTrayIcon() {
        if (!SystemTray.isSupported()) return;
        SystemTray tray = SystemTray.getSystemTray();
        Image trayImage = null;
        BufferedImage img = Icons.image("server-icon.png");
        if (img != null) trayImage = img.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
        if (trayImage == null) trayImage = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        trayIcon = new TrayIcon(trayImage, BASE_TITLE);
        trayIcon.setImageAutoSize(true);
        PopupMenu popup = new PopupMenu();
        MenuItem exitItem = new MenuItem("Exit");
        MenuItem aboutItem = new MenuItem("About");
        aboutItem.addActionListener(e -> JOptionPane.showMessageDialog(this, ABOUT_TEXT, "About", JOptionPane.INFORMATION_MESSAGE));
        exitItem.addActionListener(e -> ctl.exit());
        popup.add(aboutItem);
        popup.add(exitItem);
        trayIcon.setPopupMenu(popup);
        trayIcon.addActionListener(e -> {
            setVisible(true);
            setState(JFrame.NORMAL);
            toFront();
        });
        try {
            tray.add(trayIcon);
        } catch (AWTException ex) {
            Log.warn("Error adding tray icon: " + ex.getMessage());
            trayIcon = null;
        }
    }

    public void trayMessage(String title, String text, TrayIcon.MessageType type) {
        if (trayIcon != null) trayIcon.displayMessage(title, text, type);
    }

    public void removeTrayIcon() {
        if (trayIcon != null) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (Exception ignored) {
            }
        }
    }
}
