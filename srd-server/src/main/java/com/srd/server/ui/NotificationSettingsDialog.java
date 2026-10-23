package com.srd.server.ui;

import com.srd.server.alerts.AlertService;
import com.srd.server.alerts.Mattermost;
import com.srd.server.config.NotifySettings;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.Box;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

/** "Edit → Notification Settings": what raises an alert, where it is shown, and the delays. EDT only. */
public final class NotificationSettingsDialog {
    private NotificationSettingsDialog() {
    }

    /** Shows the dialog; returns the edited copy, or null if cancelled / invalid. */
    public static NotifySettings show(Component parent, NotifySettings current, String serverName) {
        NotifySettings n = current.copy();

        JCheckBox disconnected = new JCheckBox("ScreenApp disconnected (client was connected, then lost)", n.alertDisconnected);
        JCheckBox noScreen = new JCheckBox("Screen waiting (connected, but no screen arriving)", n.alertNoScreen);
        JCheckBox vpn = new JCheckBox("VPN Guard off", n.alertVpn);
        JCheckBox recovery = new JCheckBox("Also send a \"Connected\" row when it is back to normal", n.recoveryNotices);

        JCheckBox popups = new JCheckBox("Show pop-ups on this server", n.popups);
        JSpinner popupDelay = spinner(n.popupDelaySec, 0, 3600);

        JCheckBox mm = new JCheckBox("Send to Mattermost", n.mattermost);
        JTextField url = new JTextField(n.webhookUrl, 34);
        JSpinner mmDelay = spinner(n.mattermostDelaySec, 0, 86400);
        JTextArea tags = new JTextArea(n.tags, 12, 34);
        tags.setToolTipText("One line per tag: @tag first-ip ~ last (last octet or full IP). The tag is put in that client's row.");
        JButton test = new JButton("Send test message");
        JLabel testResult = new JLabel(" ");
        test.addActionListener(e -> {
            String u = url.getText().trim();
            if (!NotifySettings.isValidWebhook(u)) {
                testResult.setText("Enter a valid http(s) webhook URL first.");
                return;
            }
            test.setEnabled(false);
            testResult.setText("Sending...");
            Thread t = new Thread(() -> {
                String result;
                try {
                    Mattermost.postBlocking(u, Mattermost.table(java.util.Collections.singletonList(new Mattermost.Row(
                            "", "Test", "-", "✅ Test message", AlertService.when(System.currentTimeMillis())))));
                    result = "Sent. Check the channel.";
                } catch (Exception ex) {
                    result = "Failed: " + ex.getMessage();
                }
                String shown = result;
                SwingUtilities.invokeLater(() -> {
                    testResult.setText(shown);
                    test.setEnabled(true);
                });
            }, "mattermost-test");
            t.setDaemon(true);
            t.start();
        });

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(1, 4, 1, 4);

        section(p, c, "What to alert about");
        row(p, c, disconnected);
        row(p, c, noScreen);
        row(p, c, vpn);
        row(p, c, recovery);
        section(p, c, "Pop-up on this server");
        row(p, c, popups);
        row(p, c, "Show pop-up after (s):", popupDelay);
        section(p, c, "Mattermost");
        row(p, c, mm);
        row(p, c, "Webhook URL:", url);
        row(p, c, "Send after (s):", mmDelay);
        row(p, c, new JLabel("Tags by IP range (one per line, e.g. @acs 192.168.110.100 ~ 104):"));
        row(p, c, new JScrollPane(tags));
        JPanel testRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        testRow.add(test);
        testRow.add(Box.createHorizontalStrut(8));
        testRow.add(testResult);
        row(p, c, testRow);

        int result = JOptionPane.showConfirmDialog(parent, p, "Notification Settings", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return null;

        String webhook = url.getText().trim();
        if (mm.isSelected() && !NotifySettings.isValidWebhook(webhook)) {
            JOptionPane.showMessageDialog(parent, "The Mattermost webhook URL must start with http:// or https://",
                    "Invalid URL", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        n.alertDisconnected = disconnected.isSelected();
        n.alertNoScreen = noScreen.isSelected();
        n.alertVpn = vpn.isSelected();
        n.recoveryNotices = recovery.isSelected();
        n.popups = popups.isSelected();
        n.popupDelaySec = (Integer) popupDelay.getValue();
        n.mattermost = mm.isSelected();
        if (NotifySettings.isValidWebhook(webhook)) n.webhookUrl = webhook;
        n.mattermostDelaySec = (Integer) mmDelay.getValue();
        n.tags = tags.getText().trim();
        return n;
    }

    private static JSpinner spinner(int value, int min, int max) {
        return new JSpinner(new SpinnerNumberModel(value, min, max, 1));
    }

    private static void section(JPanel p, GridBagConstraints c, String title) {
        JLabel l = new JLabel(title);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        l.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0));
        c.gridx = 0;
        c.gridwidth = 2;
        p.add(l, c);
        c.gridy++;
    }

    private static void row(JPanel p, GridBagConstraints c, Component wide) {
        c.gridx = 0;
        c.gridwidth = 2;
        p.add(wide, c);
        c.gridy++;
    }

    private static void row(JPanel p, GridBagConstraints c, String label, Component field) {
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        p.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        p.add(field, c);
        c.gridy++;
    }
}
