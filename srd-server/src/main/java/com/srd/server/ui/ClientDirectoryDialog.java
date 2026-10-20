package com.srd.server.ui;

import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

/** "Edit Save Directories for <client>" dialog (Action column folder button; v2 layout). */
public final class ClientDirectoryDialog {
    private ClientDirectoryDialog() {
    }

    /** @return {screenshotDir, recordingDir} ("" = default), or null if cancelled */
    public static String[] show(Component parent, String clientId, String screenshotDir, String recordingDir) {
        // 2 rows x 4 columns (GridLayout derives the columns from the 8 components), as in v2.
        JPanel panel = new JPanel(new GridLayout(2, 2));
        JTextField screenshotField = new JTextField(30);
        JTextField recordingField = new JTextField(30);
        JButton screenshotBrowseButton = new JButton("Browse...");
        JButton recordingBrowseButton = new JButton("Browse...");
        JButton clearScreenshotButton = new JButton("Clear");
        JButton clearRecordingButton = new JButton("Clear");
        screenshotField.setText(screenshotDir == null ? "" : screenshotDir);
        recordingField.setText(recordingDir == null ? "" : recordingDir);

        screenshotBrowseButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Select Screenshot Save Directory");
            if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                screenshotField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        recordingBrowseButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Select Recording Save Directory");
            if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                recordingField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        clearScreenshotButton.addActionListener(e -> screenshotField.setText(""));
        clearRecordingButton.addActionListener(e -> recordingField.setText(""));

        panel.add(new JLabel("Screenshot Directory:"));
        panel.add(screenshotField);
        panel.add(new JLabel(""));
        JPanel screenshotButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        screenshotButtonPanel.add(screenshotBrowseButton);
        screenshotButtonPanel.add(clearScreenshotButton);
        panel.add(screenshotButtonPanel);
        panel.add(new JLabel("Recording Directory:"));
        panel.add(recordingField);
        panel.add(new JLabel(""));
        JPanel recordingButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        recordingButtonPanel.add(recordingBrowseButton);
        recordingButtonPanel.add(clearRecordingButton);
        panel.add(recordingButtonPanel);

        int result = JOptionPane.showConfirmDialog(parent, panel, "Edit Save Directories for " + clientId,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return null;
        return new String[] {screenshotField.getText().trim(), recordingField.getText().trim()};
    }
}
