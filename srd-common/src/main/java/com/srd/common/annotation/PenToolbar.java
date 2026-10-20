package com.srd.common.annotation;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JFileChooser;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Paint-style pen palette: tools, color swatches plus a custom color, a width slider, auto-fade,
 * undo and clear. All icons are vector-drawn, so there are no image assets to ship.
 */
public final class PenToolbar extends JPanel {
    private static final Color BG = new Color(0x1E, 0x1E, 0x22, 235);
    private static final Color FG = new Color(0xE8, 0xE8, 0xEC);
    private static final Color ACCENT = new Color(0x4F, 0x9D, 0xFF);
    private static final int[] SWATCHES = {
        0xE53935, 0xFB8C00, 0xFDD835, 0x43A047, 0x00ACC1, 0x1E88E5, 0x8E24AA, 0xEC407A, 0xFFFFFF, 0x212121
    };

    private final PenController pen;
    private final SwatchButton current;

    public PenToolbar(PenController pen) {
        this.pen = pen;
        setOpaque(false);
        setLayout(new FlowLayout(FlowLayout.CENTER, 4, 3));
        setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));

        ButtonGroup tools = new ButtonGroup();
        java.util.Map<PenTool, JToggleButton> toggles = new java.util.EnumMap<>(PenTool.class);
        for (PenTool t : PenTool.values()) {
            if (t == PenTool.IMAGE) continue; // has its own button below
            JToggleButton b = new JToggleButton(new ToolIcon(t, false));
            b.setSelectedIcon(new ToolIcon(t, true));
            b.setToolTipText(t == PenTool.TEXT ? "Text: click on the screen, then type" : t.label);
            style(b);
            b.setSelected(t == pen.tool());
            b.addActionListener(e -> pen.setTool(t));
            tools.add(b);
            toggles.put(t, b);
            add(b);
        }
        JToggleButton imageButton = new JToggleButton(new ToolIcon(PenTool.IMAGE, false));
        imageButton.setSelectedIcon(new ToolIcon(PenTool.IMAGE, true));
        imageButton.setToolTipText("Insert an image (from a file or the clipboard), then drag on the screen to place it");
        style(imageButton);
        tools.add(imageButton);
        imageButton.addActionListener(e -> showImageMenu(imageButton));
        add(imageButton);
        // The controller switches tools itself (back to the pen after an image is placed).
        pen.setToolListener(() -> SwingUtilities.invokeLater(() -> {
            JToggleButton b = toggles.get(pen.tool());
            if (b != null) b.setSelected(true);
            else if (pen.tool() == PenTool.IMAGE) imageButton.setSelected(true);
            else tools.clearSelection();
        }));
        add(sep());
        for (int rgb : SWATCHES) {
            SwatchButton s = new SwatchButton(new Color(rgb));
            s.addActionListener(e -> setColor(s.color));
            add(s);
        }
        current = new SwatchButton(pen.color());
        current.setToolTipText("Custom color…");
        current.custom = true;
        current.addActionListener(e -> {
            Color c = JColorChooser.showDialog(this, "Pen color", pen.color());
            if (c != null) setColor(c);
        });
        add(current);
        add(sep());

        JLabel wl = new JLabel("Size");
        wl.setForeground(FG);
        wl.setFont(wl.getFont().deriveFont(Font.PLAIN, 11f));
        add(wl);
        JSlider width = new JSlider(1, 32, Math.round(pen.widthPx()));
        width.setOpaque(false);
        width.setPreferredSize(new Dimension(90, 22));
        width.setToolTipText("Line width");
        width.addChangeListener(e -> pen.setWidthPx(width.getValue()));
        add(width);

        JComboBox<String> fade = new JComboBox<>(new String[] {"Keep", "Fade 3s", "Fade 5s", "Fade 10s"});
        fade.setToolTipText("Auto-erase strokes after a few seconds");
        fade.setFont(fade.getFont().deriveFont(11f));
        fade.addActionListener(e -> {
            int[] secs = {0, 3, 5, 10};
            pen.setFadeSeconds(secs[fade.getSelectedIndex()]);
        });
        add(fade);
        add(sep());

        JButton undo = new JButton(new ActionIcon(true));
        undo.setToolTipText("Undo my last stroke (Ctrl+Z)");
        style(undo);
        undo.addActionListener(e -> pen.undo());
        add(undo);
        JButton clear = new JButton(new ActionIcon(false));
        clear.setToolTipText("Clear all drawings");
        style(clear);
        clear.addActionListener(e -> pen.clearAll());
        add(clear);
    }

    private void showImageMenu(JToggleButton anchor) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem file = new JMenuItem("Image from file...");
        file.addActionListener(e -> insertFromFile());
        JMenuItem clip = new JMenuItem("Image from clipboard");
        clip.addActionListener(e -> {
            ImageInsert.Prepared p = ImageInsert.fromClipboard();
            if (p == null) {
                JOptionPane.showMessageDialog(this, "The clipboard has no image.", "Insert image", JOptionPane.INFORMATION_MESSAGE);
                anchor.setSelected(false);
                return;
            }
            pen.setPendingImage(p.data, p.width, p.height);
        });
        menu.add(file);
        menu.add(clip);
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void insertFromFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Insert image");
        fc.setFileFilter(new FileNameExtensionFilter("Images (png, jpg, gif, bmp)", "png", "jpg", "jpeg", "gif", "bmp"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            ImageInsert.Prepared p = ImageInsert.fromFile(fc.getSelectedFile());
            pen.setPendingImage(p.data, p.width, p.height);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Cannot use that file: " + ex.getMessage(), "Insert image", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void setColor(Color c) {
        pen.setColor(c);
        current.color = c;
        repaint(); // every swatch re-evaluates its "selected" ring
    }

    private static Component sep() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setPreferredSize(new Dimension(6, 22));
        return p;
    }

    private static void style(AbstractButton b) {
        b.setFocusable(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setOpaque(false);
        b.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(BG);
        g2.fill(new RoundRectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 1, 14, 14));
        g2.dispose();
        super.paintComponent(g);
    }

    private final class SwatchButton extends JButton {
        Color color;
        boolean custom;

        SwatchButton(Color c) {
            this.color = c;
            setPreferredSize(new Dimension(18, 18));
            style(this);
            setToolTipText(String.format("#%06X", c.getRGB() & 0xFFFFFF));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int s = Math.min(getWidth(), getHeight()) - 2;
            g2.setColor(color);
            g2.fill(new Ellipse2D.Double(1, 1, s, s));
            boolean selected = (pen.color().getRGB() & 0xFFFFFF) == (color.getRGB() & 0xFFFFFF);
            g2.setStroke(new BasicStroke(selected ? 2.2f : 1f));
            g2.setColor(selected ? ACCENT : new Color(255, 255, 255, 120));
            g2.draw(new Ellipse2D.Double(1, 1, s, s));
            if (custom) {
                g2.setColor(new Color(0, 0, 0, 160));
                g2.setFont(getFont().deriveFont(Font.BOLD, 12f));
                g2.drawString("+", s / 2 - 3, s / 2 + 5);
            }
            g2.dispose();
        }
    }

    /** Vector tool glyphs. */
    private static final class ToolIcon implements Icon {
        private final PenTool tool;
        private final boolean selected;

        ToolIcon(PenTool tool, boolean selected) {
            this.tool = tool;
            this.selected = selected;
        }

        @Override
        public int getIconWidth() {
            return 24;
        }

        @Override
        public int getIconHeight() {
            return 24;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.translate(x, y);
            if (selected) {
                g2.setColor(new Color(0x4F, 0x9D, 0xFF, 90));
                g2.fill(new RoundRectangle2D.Double(0, 0, 24, 24, 7, 7));
            }
            g2.setColor(FG);
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (tool) {
                case PEN: {
                    Path2D p = new Path2D.Double();
                    p.moveTo(5, 19);
                    p.curveTo(8, 10, 12, 18, 15, 10);
                    p.lineTo(19, 5);
                    g2.draw(p);
                    break;
                }
                case HIGHLIGHTER:
                    g2.setColor(new Color(0xFD, 0xD8, 0x35, 200));
                    g2.setStroke(new BasicStroke(6f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_ROUND));
                    g2.draw(new Line2D.Double(6, 17, 18, 7));
                    break;
                case LINE:
                    g2.draw(new Line2D.Double(5, 19, 19, 5));
                    break;
                case ARROW:
                    g2.draw(new Line2D.Double(5, 19, 18, 6));
                    g2.draw(new Line2D.Double(18, 6, 11, 7));
                    g2.draw(new Line2D.Double(18, 6, 17, 13));
                    break;
                case RECTANGLE:
                    g2.drawRect(5, 7, 14, 10);
                    break;
                case ELLIPSE:
                    g2.draw(new Ellipse2D.Double(4, 6, 16, 12));
                    break;
                case LASER:
                    g2.setColor(new Color(0xE5, 0x39, 0x35, 110));
                    g2.fill(new Ellipse2D.Double(5, 5, 14, 14));
                    g2.setColor(new Color(0xFF, 0x52, 0x52));
                    g2.fill(new Ellipse2D.Double(9, 9, 6, 6));
                    break;
                case TEXT: {
                    g2.setFont(new Font(Font.SERIF, Font.BOLD, 20));
                    java.awt.FontMetrics fm = g2.getFontMetrics();
                    g2.drawString("T", 12 - fm.stringWidth("T") / 2, 19);
                    break;
                }
                case IMAGE: {
                    g2.drawRoundRect(4, 6, 16, 13, 3, 3);
                    Path2D hills = new Path2D.Double();
                    hills.moveTo(5, 17);
                    hills.lineTo(10, 11);
                    hills.lineTo(14, 15);
                    hills.lineTo(16, 13);
                    hills.lineTo(19, 17);
                    g2.draw(hills);
                    g2.fill(new Ellipse2D.Double(14, 8, 3, 3));
                    break;
                }
                case ERASER: {
                    Path2D p = new Path2D.Double();
                    p.moveTo(6, 15);
                    p.lineTo(13, 8);
                    p.lineTo(18, 13);
                    p.lineTo(13, 18);
                    p.lineTo(9, 18);
                    p.closePath();
                    g2.draw(p);
                    g2.draw(new Line2D.Double(9, 18, 19, 18));
                    break;
                }
                default:
                    break;
            }
            g2.dispose();
        }
    }

    /** Undo (curved arrow) / clear (trash) glyphs. */
    private static final class ActionIcon implements Icon {
        private final boolean undo;

        ActionIcon(boolean undo) {
            this.undo = undo;
        }

        @Override
        public int getIconWidth() {
            return 24;
        }

        @Override
        public int getIconHeight() {
            return 24;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.translate(x, y);
            g2.setColor(FG);
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (undo) {
                Path2D p = new Path2D.Double();
                p.moveTo(8, 9);
                p.curveTo(16, 6, 21, 13, 16, 18);
                g2.draw(p);
                g2.draw(new Line2D.Double(8, 9, 12, 5));
                g2.draw(new Line2D.Double(8, 9, 12, 13));
            } else {
                g2.drawRect(7, 8, 10, 11);
                g2.draw(new Line2D.Double(5, 8, 19, 8));
                g2.draw(new Line2D.Double(10, 5, 14, 5));
                g2.draw(new Line2D.Double(10, 11, 10, 16));
                g2.draw(new Line2D.Double(14, 11, 14, 16));
            }
            g2.dispose();
        }
    }
}
