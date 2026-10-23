package com.srd.server.ui;

import com.srd.server.alerts.AlertService;
import com.srd.server.alerts.Mattermost;
import com.srd.server.config.NotifySettings;
import java.awt.BorderLayout;
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
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

/** "Edit → Notification Settings": what raises an alert, where it is shown, and the delays. EDT only. */
public final class NotificationSettingsDialog {
    private NotificationSettingsDialog() {
    }

    /** Shows the dialog; returns the edited copy, or null if cancelled / invalid. */
    public static NotifySettings show(Component parent, NotifySettings current, String serverName, int screenIntervalSec) {
        NotifySettings n = current.copy();

        JCheckBox disconnected = new JCheckBox("ScreenApp disconnected (client was connected, then lost)", n.alertDisconnected);
        JCheckBox noScreen = new JCheckBox("Screen waiting (connected, but no screen arriving)", n.alertNoScreen);
        JCheckBox vpn = new JCheckBox("VPN Guard off", n.alertVpn);
        JCheckBox recovery = new JCheckBox("Also send a \"Connected\" row when it is back to normal", n.recoveryNotices);

        JCheckBox popups = new JCheckBox("Show pop-ups on this server", n.popups);
        JSpinner popupDelay = spinner(n.popupDelaySec, 0, 3600);

        JCheckBox mm = new JCheckBox("Send to Mattermost", n.mattermost);
        JTextField url = new JTextField(n.webhookUrl, 34);
        url.setToolTipText("Only used while the bot token is empty");
        JTextField botServer = new JTextField(n.botServer, 24);
        JPasswordField botToken = new JPasswordField(n.botToken, 34);
        botToken.setToolTipText("Access token of the Mattermost bot account (Integrations > Bot Accounts)");
        JTextField botChannel = new JTextField(n.botChannel, 34);
        botChannel.setToolTipText("Channel name or id. The bot must be a member of the channel.");
        JSpinner mmDelay = spinner(n.mattermostDelaySec, 0, 86400);
        boolean repeatFollowsScreen = n.mattermostRepeatSec < 0;
        int repeatShown = repeatFollowsScreen ? screenIntervalSec : n.mattermostRepeatSec;
        JSpinner mmRepeat = spinner(repeatShown, 0, 86400);
        mmRepeat.setToolTipText("While a PC stays ScreenApp Disconnected, post it again this often. Default: the screen interval (Server Configuration). 0 = post only once.");
        TagRangeEditor tags = new TagRangeEditor(n.tags);
        tags.setToolTipText("The tag is put in the row of every client whose IP is inside the range.");
        JButton test = new JButton("Send test message");
        JLabel testResult = new JLabel(" ");
        test.addActionListener(e -> {
            Mattermost.Target target = new Mattermost.Target(url.getText().trim(), botServer.getText(),
                    new String(botToken.getPassword()), botChannel.getText());
            if (target.isBot() ? !NotifySettings.isValidWebhook(target.serverUrl()) : !NotifySettings.isValidWebhook(target.webhookUrl())) {
                testResult.setText("Enter the bot server, token and channel (or a valid webhook URL) first.");
                return;
            }
            test.setEnabled(false);
            testResult.setText("Sending...");
            Thread t = new Thread(() -> {
                String result;
                try {
                    Mattermost.postBlocking(target, Mattermost.table(java.util.Collections.singletonList(new Mattermost.Row(
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
        JPanel serverRow = new JPanel(new BorderLayout(8, 0));
        serverRow.add(botServer, BorderLayout.CENTER);
        serverRow.add(test, BorderLayout.EAST);
        row(p, c, "Server URL:", serverRow);
        row(p, c, "", testResult);
        row(p, c, "Bot token:", botToken);
        row(p, c, "Channel:", botChannel);
        row(p, c, "Webhook URL (no bot token):", url);
        row(p, c, "Send after (s):", mmDelay);
        row(p, c, "Repeat disconnected every (s):", mmRepeat);
        row(p, c, new JLabel("Tags by IP range (e.g. @acs, 192.168.110.100 to 192.168.110.104):"));
        row(p, c, tags);

        int result = JOptionPane.showConfirmDialog(parent, p, "Notification Settings", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return null;

        String webhook = url.getText().trim();
        String token = new String(botToken.getPassword()).trim();
        boolean bot = !token.isEmpty() && !botChannel.getText().trim().isEmpty();
        if (mm.isSelected() && !(bot ? NotifySettings.isValidWebhook(botServer.getText()) : NotifySettings.isValidWebhook(webhook))) {
            JOptionPane.showMessageDialog(parent, bot ? "The Mattermost server URL must start with http:// or https://"
                            : "Enter the bot token and channel, or a webhook URL that starts with http:// or https://",
                    "Invalid Mattermost settings", JOptionPane.ERROR_MESSAGE);
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
        n.botServer = botServer.getText().trim();
        n.botToken = token;
        n.botChannel = botChannel.getText().trim();
        n.mattermostDelaySec = (Integer) mmDelay.getValue();
        int repeat = (Integer) mmRepeat.getValue();
        n.mattermostRepeatSec = repeatFollowsScreen && repeat == repeatShown ? -1 : repeat;
        n.tags = tags.getText();
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
