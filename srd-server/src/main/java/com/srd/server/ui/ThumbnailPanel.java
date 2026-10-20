package com.srd.server.ui;

import com.srd.common.status.VpnGuardStatus;
import com.srd.common.ui.Icons;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.BorderFactory;
import javax.swing.JPanel;

/**
 * One tile of the thumbnail wall (moved from v2). It only ever paints the pre-scaled image it is
 * given (≤ 480 px wide), never a full-resolution frame. Adds a VPN Guard badge in the top-left
 * corner. EDT only.
 */
public final class ThumbnailPanel extends JPanel {
    private static final Map<Integer, Font> FONTS = new ConcurrentHashMap<>();
    private static final Font BADGE_FONT = new Font("Arial", Font.BOLD, 10);
    private static final Color BAR_BG = new Color(0, 0, 0, 180);
    private static final Color BADGE_BG = new Color(0, 0, 0, 150);
    private static final Color NO_FRAME_BG = new Color(60, 60, 60);
    private static final BasicStroke BADGE_STROKE = new BasicStroke(1f);

    private final String clientId;
    private BufferedImage image;
    /** {@link #image} resized to the tile's exact device-pixel size (rebuilt on resize / new frame). */
    private BufferedImage scaled;
    private BufferedImage scaledFrom;
    private String displayName;
    private boolean isOffline = true;
    private String offlineText = "Disconnected";
    private boolean isRecording = false;
    private Dimension clientScreenSize;
    private VpnGuardStatus vpn;
    private boolean vpnDimmed = true;

    /** Fills the tile and writes {@code text} in the middle, as large as fits. */
    private static void paintCentered(Graphics g, int w, int h, String text, Color bg, Color fg) {
        g.setColor(bg);
        g.fillRect(0, 0, w, h);
        g.setColor(fg);
        int size = Math.max(8, Math.min(w, h) / 5);
        Font fitFont = font(size);
        FontMetrics fm = g.getFontMetrics(fitFont);
        while (size > 8 && (fm.stringWidth(text) > (w - 8) || fm.getHeight() > (h - 8))) {
            fitFont = font(--size);
            fm = g.getFontMetrics(fitFont);
        }
        g.setFont(fitFont);
        int textX = Math.max(4, (w - fm.stringWidth(text)) / 2);
        int textY = Math.max(fm.getAscent() + 2, (h + fm.getAscent() - fm.getDescent()) / 2);
        g.drawString(text, textX, textY);
    }

    static Font font(int size) {
        return FONTS.computeIfAbsent(size, s -> new Font("Arial", Font.BOLD, s));
    }

    public ThumbnailPanel(String clientId, String displayName, Dimension clientScreenSize) {
        this.clientId = clientId;
        this.displayName = displayName == null ? clientId : displayName;
        this.clientScreenSize = clientScreenSize;
        setBorder(BorderFactory.createLineBorder(Color.BLACK));
    }

    public String clientId() {
        return clientId;
    }

    public BufferedImage getImage() {
        return image;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int w = getWidth();
        int h = getHeight();
        // Offline visual is driven ONLY by isOffline, which moves in lockstep with the Status
        // column; a connected client without a frame yet shows a neutral placeholder.
        if (isOffline) {
            paintCentered(g, w, h, offlineText, Color.LIGHT_GRAY, Color.DARK_GRAY);
        } else if (image == null) {
            // Connected, but no picture yet (or none arriving): say so instead of an empty dark tile.
            paintCentered(g, w, h, "Waiting for screen...", NO_FRAME_BG, Color.LIGHT_GRAY);
        } else {
            double aspectRatio = clientScreenSize.getWidth() / Math.max(1, clientScreenSize.getHeight());
            int drawWidth = w;
            int drawHeight = (int) (w / aspectRatio);
            if (drawHeight > h) {
                drawHeight = h;
                drawWidth = (int) (h * aspectRatio);
            }
            int x = (w - drawWidth) / 2;
            int y = (h - drawHeight) / 2;
            // Draw a copy pre-scaled (multi-step, high quality) to the exact device pixels of the
            // tile: crisp text at any tile size or Windows display scaling, and cheap repaints.
            Graphics2D gi = (Graphics2D) g;
            double dpi = gi.getTransform().getScaleX();
            int dw = Math.max(1, (int) Math.round(drawWidth * dpi));
            int dh = Math.max(1, (int) Math.round(drawHeight * dpi));
            if (scaled == null || scaledFrom != image || scaled.getWidth() != dw || scaled.getHeight() != dh) {
                scaled = com.srd.server.util.Images.scaleHQ(image, dw, dh);
                scaledFrom = image;
            }
            gi.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            gi.drawImage(scaled, x, y, drawWidth, drawHeight, null);
        }
        if (isRecording) {
            BufferedImage recIcon = Icons.image("rec-in-progress.png");
            if (recIcon != null) {
                int iconW = Math.min(30, w / 5);
                int iconH = Math.min(25, h / 4);
                g.drawImage(recIcon, w - Math.min(35, w / 4), 3, iconW, iconH, null);
            }
        }
        if (vpn != null) paintVpnBadge((Graphics2D) g);
        // Black bar at bottom with the display name: fixed-size band (16..26 px) so a single large
        // tile doesn't get a giant label; shrink the font to fit, then ellipsize if still too long.
        int barHeight = Math.max(16, Math.min(26, h / 10));
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(BAR_BG);
        g2.fillRect(0, h - barHeight, w, barHeight);
        g2.setColor(Color.WHITE);
        int size = barHeight - 6;
        FontMetrics fm = g2.getFontMetrics(font(size));
        while (size > 9 && fm.stringWidth(displayName) > w - 8) fm = g2.getFontMetrics(font(--size));
        g2.setFont(font(size));
        String label = displayName;
        if (fm.stringWidth(label) > w - 8) {
            while (label.length() > 1 && fm.stringWidth(label + "…") > w - 8) label = label.substring(0, label.length() - 1);
            label = label + "…";
        }
        int textY = h - barHeight + (barHeight + fm.getAscent() - fm.getDescent()) / 2;
        g2.drawString(label, 4, textY);
    }

    private void paintVpnBadge(Graphics2D g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Color c = vpnDimmed ? ClientTableRenderers.dim(vpn.state.color) : vpn.state.color;
        String text = shortLabel(vpn.state);
        g.setFont(BADGE_FONT);
        FontMetrics fm = g.getFontMetrics();
        int shield = 11;
        int bw = 4 + shield + 4 + fm.stringWidth(text) + 5;
        int bh = 16;
        g.setColor(BADGE_BG);
        g.fillRoundRect(3, 3, bw, bh, 8, 8);
        // shield
        int sx = 7, sy = 5;
        Path2D p = new Path2D.Double();
        p.moveTo(sx + shield / 2.0, sy);
        p.lineTo(sx + shield, sy + 2.5);
        p.curveTo(sx + shield, sy + 8, sx + shield * 0.75, sy + 10.5, sx + shield / 2.0, sy + 12);
        p.curveTo(sx + shield * 0.25, sy + 10.5, sx, sy + 8, sx, sy + 2.5);
        p.closePath();
        g.setColor(c);
        g.fill(p);
        g.setColor(c.darker());
        g.setStroke(BADGE_STROKE);
        g.draw(p);
        g.setColor(vpnDimmed ? Color.LIGHT_GRAY : Color.WHITE);
        g.drawString(text, sx + shield + 4, 3 + (bh + fm.getAscent() - fm.getDescent()) / 2);
        g.dispose();
    }

    static String shortLabel(VpnGuardStatus.State s) {
        switch (s) {
            case PROTECTED:
                return "VPN";
            case STALE:
                return "VPN ?";
            case NOT_INSTALLED:
                return "No VPN";
            case NOT_APPLICABLE:
                return "N/A";
            default:
                return "VPN " + s.label;
        }
    }

    public void setImage(BufferedImage image) {
        this.image = image;
        repaint();
    }

    /** Flips the online/offline visual WITHOUT dropping the last frame (shown again on reconnect). */
    public void setOffline(boolean isOffline) {
        setOffline(isOffline, "Disconnected");
    }

    public void setOffline(boolean isOffline, String text) {
        this.isOffline = isOffline;
        this.offlineText = text == null ? "Disconnected" : text;
        this.vpnDimmed = isOffline;
        repaint();
    }

    public boolean isOffline() {
        return isOffline;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName == null ? clientId : displayName;
        repaint();
    }

    public void setRecording(boolean isRecording) {
        this.isRecording = isRecording;
        repaint();
    }

    public void setClientScreenSize(Dimension d) {
        if (d != null && d.width > 0 && d.height > 0) clientScreenSize = d;
    }

    public void setVpnStatus(VpnGuardStatus st) {
        this.vpn = st;
        setToolTipText(st == null ? null : st.tooltipHtml());
        repaint();
    }
}
