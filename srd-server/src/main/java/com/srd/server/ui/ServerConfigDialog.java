package com.srd.server.ui;

import com.srd.server.config.ServerConfig;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
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
        JPanel panel = new JPanel(new GridLayout(8, 2, 5, 5));

        JCheckBox autoAddCheckbox = new JCheckBox("Auto-add clients on connection", cfg.isAutoAddClients());
        JCheckBox screenControlCheckbox = new JCheckBox("Enable screen control", cfg.isScreenControlEnabled());
        JCheckBox screenshotSaveCheckbox = new JCheckBox("Enable screenshot saving", cfg.isScreenshotSaveEnabled());
        JTextField intervalField = new JTextField(String.valueOf(cfg.getScreenshotInterval()));
        intervalField.setToolTipText("Screenshot interval in seconds (1-3600)");
        JTextField alternativeIPsField = new JTextField(String.join(",", cfg.getAlternativeIPs()));
        alternativeIPsField.setToolTipText("Comma-separated list of alternative IP addresses");

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
        panel.add(new JLabel("Alternative IPs:"));
        panel.add(alternativeIPsField);
        panel.add(new JLabel("Pre-shared Key:"));
        panel.add(pskField);

        int result = JOptionPane.showConfirmDialog(parent, panel, "Server Configuration",
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
        String ips = alternativeIPsField.getText().trim();
        if (!ips.isEmpty()) {
            for (String ip : ips.split(",")) {
                ip = ip.trim();
                if (!ip.isEmpty() && ServerConfig.isValidIP(ip)) newAlternativeIPs.add(ip);
            }
        }
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
