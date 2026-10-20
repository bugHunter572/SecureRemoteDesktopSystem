package com.srd.server.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Small non-modal, undecorated, always-on-top notification in the bottom-right corner that fades
 * out by itself. Several toasts stack upwards. Click to dismiss. EDT only.
 */
public final class Toast extends JWindow {
    private static final List<Toast> OPEN = new ArrayList<>();
    private static final int WIDTH = 340;
    private static final int GAP = 8;
    private static final int MAX_OPEN = 5;
    private static final Font TITLE_FONT = new Font(Font.DIALOG, Font.BOLD, 13);
    private static final Font BODY_FONT = new Font(Font.DIALOG, Font.PLAIN, 12);

    private final Timer life;
    private Timer fade;

    private Toast(String title, String body, Color accent, int millis) {
        setAlwaysOnTop(true);
        setFocusableWindowState(false);
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBackground(new Color(0x26, 0x28, 0x2E));
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 6, 0, 0, accent),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        JLabel t = new JLabel(title);
        t.setFont(TITLE_FONT);
        t.setForeground(Color.WHITE);
        JLabel b = new JLabel("<html><div style='width:" + (WIDTH - 40) + "px'>" + esc(body) + "</div></html>");
        b.setFont(BODY_FONT);
        b.setForeground(new Color(0xDD, 0xDD, 0xE2));
        p.add(t, BorderLayout.NORTH);
        p.add(b, BorderLayout.CENTER);
        setContentPane(p);
        pack();
        setSize(WIDTH, getHeight());
        MouseAdapter dismiss = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                close();
            }
        };
        p.addMouseListener(dismiss);
        b.addMouseListener(dismiss);
        life = new Timer(millis, e -> startFade());
        life.setRepeats(false);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    /** Shows a toast; safe to call from any thread. */
    public static void show(String title, String body, Color accent, int millis) {
        if (GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeLater(() -> {
            // Never let pop-ups pile up past the screen: the oldest makes way for the newest.
            while (OPEN.size() >= MAX_OPEN) OPEN.get(0).close();
            Toast t = new Toast(title, body, accent, millis);
            OPEN.add(t);
            restack();
            t.setVisible(true);
            t.life.start();
        });
    }

    private void startFade() {
        boolean translucent = false;
        try {
            translucent = getGraphicsConfiguration().getDevice()
                    .isWindowTranslucencySupported(java.awt.GraphicsDevice.WindowTranslucency.TRANSLUCENT);
        } catch (Exception ignored) {
        }
        if (!translucent) {
            close();
            return;
        }
        fade = new Timer(40, e -> {
            float o = getOpacity() - 0.06f;
            if (o <= 0.05f) {
                close();
            } else {
                setOpacity(o);
            }
        });
        fade.start();
    }

    private void close() {
        life.stop();
        if (fade != null) fade.stop();
        OPEN.remove(this);
        dispose();
        restack();
    }

    private static void restack() {
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int y = screen.y + screen.height - GAP;
        for (int i = OPEN.size() - 1; i >= 0; i--) {
            Toast t = OPEN.get(i);
            y -= t.getHeight();
            t.setLocation(screen.x + screen.width - t.getWidth() - GAP, y);
            y -= GAP;
        }
    }
}
