package com.srd.server.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;
import javax.swing.JViewport;

/** The thumbnail wall (v2 layout logic, unchanged). EDT only. */
public final class ThumbnailGrid extends JPanel {
    static final int TARGET_VISIBLE_THUMBNAILS = 30;
    private volatile int lastTileWidth = 260;

    /** On-screen width (logical px) of a tile after the last layout; readable from any thread. */
    public int tileWidth() {
        return lastTileWidth;
    }

    /** Re-orders the tiles to match the table's current (sorted) order. */
    public void reorder(List<String> sortedClientIds, Map<String, ThumbnailPanel> panels) {
        removeAll();
        for (String id : sortedClientIds) {
            ThumbnailPanel p = panels.get(id);
            if (p != null) add(p);
        }
    }

    /**
     * @param normalMode true in the 60:40 split (targets about 30 visible tiles); false when the
     *                   wall is shown full width (fixed ~220 px tiles)
     */
    public void resizeThumbnails(boolean normalMode) {
        int count = getComponentCount();
        if (count == 0) {
            revalidate();
            repaint();
            return;
        }
        int panelWidth = 0;
        int panelHeight = 0;
        Container parent = getParent();
        if (parent instanceof JViewport) {
            panelWidth = parent.getWidth();
            panelHeight = parent.getHeight();
        }
        if (panelWidth <= 0) panelWidth = getWidth();
        if (panelWidth <= 0) panelWidth = 600;
        if (panelHeight <= 0) panelHeight = getHeight();
        if (panelHeight <= 0) panelHeight = 600;

        int[] layout = computeLayout(count, panelWidth, panelHeight, normalMode);
        int cols = layout[0];
        int thumbW = layout[1];
        int thumbH = layout[2];
        lastTileWidth = thumbW;
        int gap = 10;
        setLayout(new GridLayout(0, cols, gap, gap));
        for (Component comp : getComponents()) comp.setPreferredSize(new Dimension(thumbW, thumbH));
        revalidate();
        repaint();
    }

    /** @return {columns, tileWidth, tileHeight} */
    static int[] computeLayout(int count, int panelWidth, int panelHeight, boolean normalMode) {
        int gap = 10;
        int cols;
        int thumbW;
        int thumbH;
        if (normalMode) {
            int targetVisible = Math.min(TARGET_VISIBLE_THUMBNAILS, count);
            int bestCols = 1;
            int bestThumbW = 80;
            for (int candidateCols = 1; candidateCols <= targetVisible; candidateCols++) {
                int rows = (int) Math.ceil((double) targetVisible / candidateCols);
                int maxWPerCell = (panelWidth - (candidateCols - 1) * gap) / candidateCols;
                int maxHPerCell = (panelHeight - (rows - 1) * gap) / rows;
                if (maxWPerCell <= 0 || maxHPerCell <= 0) continue;
                int fitByHeight = maxHPerCell * 16 / 9;
                int candidateThumbW = Math.min(maxWPerCell, fitByHeight);
                if (candidateThumbW > bestThumbW) {
                    bestThumbW = candidateThumbW;
                    bestCols = candidateCols;
                }
            }
            cols = Math.max(1, Math.min(bestCols, count));
            thumbW = Math.max(80, Math.min(260, bestThumbW));
            thumbH = Math.max(45, thumbW * 9 / 16);
        } else {
            int targetThumbWidth = 220;
            int minThumbWidth = 170;
            cols = Math.max(1, (panelWidth + gap) / (targetThumbWidth + gap));
            cols = Math.min(cols, count);
            thumbW = Math.max(50, (panelWidth - (cols - 1) * gap) / cols);
            while (cols > 1 && thumbW < minThumbWidth) {
                cols--;
                thumbW = Math.max(50, (panelWidth - (cols - 1) * gap) / cols);
            }
            thumbH = Math.max(120, thumbW * 9 / 16);
        }
        return new int[] {cols, thumbW, thumbH};
    }
}
