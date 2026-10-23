package com.srd.server.ui;

import com.srd.server.config.ServerConfig;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.JTextField;

/** "Edit → Server Configuration" dialog (v2 layout plus the pre-shared key). EDT only. */
public final class ServerConfigDialog {
    private ServerConfigDialog() {
    }

    /** Shows the dialog; returns the edited copy, or null if cancelled / invalid. */
    public static ServerConfig show(Component parent, ServerConfig current) {
        ServerConfig cfg = current.copy();
        JPanel panel = new JPanel(new GridLayout(7, 2, 5, 5));

        JCheckBox autoAddCheckbox = new JCheckBox("Auto-add clients on connection", cfg.isAutoAddClients());
        JCheckBox screenControlCheckbox = new JCheckBox("Enable screen control", cfg.isScreenControlEnabled());
        JCheckBox screenshotSaveCheckbox = new JCheckBox("Enable screenshot saving", cfg.isScreenshotSaveEnabled());
        JTextField intervalField = new JTextField(String.valueOf(cfg.getScreenshotInterval()));
        intervalField.setToolTipText("Screenshot interval in seconds (1-3600)");
        // Alternative IPs: a list with an add field, instead of one comma-separated text.
        DefaultListModel<String> altModel = new DefaultListModel<>();
        for (String ip : cfg.getAlternativeIPs()) altModel.addElement(ip);
        JList<String> altList = new JList<>(altModel);
        altList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JScrollPane altScroll = new JScrollPane(altList);
        altScroll.setPreferredSize(new Dimension(300, 110));
        JTextField altInput = new JTextField(14);
        altInput.setToolTipText("An IP address; several can be pasted at once, separated by commas or spaces");
        JButton altAdd = new JButton("Add");
        JButton altRemove = new JButton("Remove");
        altRemove.setEnabled(false);
        altList.addListSelectionListener(e -> altRemove.setEnabled(!altList.isSelectionEmpty()));
        Runnable addIp = () -> {
            StringBuilder bad = new StringBuilder();
            for (String ip : altInput.getText().trim().split("[,;\\s]+")) {
                if (ip.isEmpty()) continue;
                if (!ServerConfig.isValidIP(ip)) {
                    bad.append(bad.length() == 0 ? "" : ", ").append(ip);
                } else if (!altModel.contains(ip)) {
                    altModel.addElement(ip);
                }
            }
            if (bad.length() > 0) {
                JOptionPane.showMessageDialog(altInput, "Not a valid IP address: " + bad, "Invalid IP", JOptionPane.ERROR_MESSAGE);
                altInput.setText(bad.toString());
            } else {
                altInput.setText("");
            }
            altInput.requestFocusInWindow();
        };
        altAdd.addActionListener(e -> addIp.run());
        altInput.addActionListener(e -> addIp.run()); // Enter adds
        altRemove.addActionListener(e -> {
            for (String ip : new ArrayList<>(altList.getSelectedValuesList())) altModel.removeElement(ip);
        });
        JPanel altAddRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        altAddRow.add(altInput);
        altAddRow.add(altAdd);
        altAddRow.add(altRemove);
        JPanel altPanel = new JPanel(new BorderLayout(0, 5));
        altPanel.setBorder(BorderFactory.createTitledBorder("Alternative IPs (other servers the clients also report to)"));
        altPanel.add(altScroll, BorderLayout.CENTER);
        altPanel.add(altAddRow, BorderLayout.SOUTH);

        // JPEG quality clients encode thumbnails/screenshots at, 10-100 %.
        int qualityPercent = Math.round(cfg.getImageQuality() * 100);
        JSlider imageQualitySlider = new JSlider(10, 100, qualityPercent);
        imageQualitySlider.setMajorTickSpacing(10);
        imageQualitySlider.setPaintTicks(true);
        JLabel imageQualityValueLabel = new JLabel(qualityPercent + "%");
        imageQualitySlider.addChangeListener(e -> imageQualityValueLabel.setText(imageQualitySlider.getValue() + "%"));
        imageQualitySlider.setToolTipText("JPEG quality of thumbnails and idle screenshots (higher = clearer, more bandwidth)");
        JPanel imageQualityPanel = new JPanel(new BorderLayout(5, 0));
        imageQualityPanel.add(imageQualitySlider, BorderLayout.CENTER);
        imageQualityPanel.add(imageQualityValueLabel, BorderLayout.EAST);

        JSpinner thumbWidth = new JSpinner(new SpinnerNumberModel(cfg.getThumbMaxWidth(), 320, 2560, 80));
        thumbWidth.setToolTipText("Widest thumbnail kept per client (320-2560 px). Bigger = sharper large tiles, more memory");

        JPasswordField pskField = new JPasswordField(cfg.getPsk());
        pskField.setToolTipText("Pre-shared key for encrypted connections; every client must use the same key");

        panel.add(new JLabel("Auto-add Clients:"));
        panel.add(autoAddCheckbox);
        panel.add(new JLabel("Screen Control:"));
        panel.add(screenControlCheckbox);
        panel.add(new JLabel("Screenshot Saving:"));
        panel.add(screenshotSaveCheckbox);
        panel.add(new JLabel("Screenshot Interval (s):"));
        panel.add(intervalField);
        panel.add(new JLabel("Image Quality:"));
        panel.add(imageQualityPanel);
        panel.add(new JLabel("Thumbnail Size (px):"));
        panel.add(thumbWidth);
        panel.add(new JLabel("Pre-shared Key:"));
        panel.add(pskField);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.add(panel, BorderLayout.NORTH);
        content.add(altPanel, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(parent, content, "Server Configuration",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return null;

        int interval;
        try {
            interval = Integer.parseInt(intervalField.getText().trim());
            if (interval < 1 || interval > 3600) {
                JOptionPane.showMessageDialog(parent, "Interval must be between 1 and 3600 seconds!",
                        "Invalid Interval", JOptionPane.ERROR_MESSAGE);
                return null;
            }
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(parent, "Invalid interval value! Please enter a number.",
                    "Invalid Interval", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        String psk = new String(pskField.getPassword());
        if (psk.trim().isEmpty()) {
            JOptionPane.showMessageDialog(parent, "The pre-shared key must not be empty!",
                    "Invalid Key", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        List<String> newAlternativeIPs = new ArrayList<>();
        for (int i = 0; i < altModel.size(); i++) newAlternativeIPs.add(altModel.get(i));
        cfg.setAlternativeIPs(newAlternativeIPs);
        cfg.setAutoAddClients(autoAddCheckbox.isSelected());
        cfg.setScreenControlEnabled(screenControlCheckbox.isSelected());
        cfg.setScreenshotSaveEnabled(screenshotSaveCheckbox.isSelected());
        cfg.setImageQuality(imageQualitySlider.getValue() / 100f);
        cfg.setThumbMaxWidth((Integer) thumbWidth.getValue());
        cfg.setScreenshotInterval(interval);
        cfg.setPsk(psk);
        return cfg;
    }
}
