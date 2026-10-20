package com.srd.common.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnnotationTest {

    @Test
    void viewerStrokeReplicatesToHostModel() {
        AnnotationModel viewer = new AnnotationModel();
        AnnotationModel host = new AnnotationModel();
        List<byte[]> wire = new ArrayList<>();
        PenController pen = new PenController(viewer, "alice", wire::add);
        pen.setTool(PenTool.PEN);
        pen.press(0.1f, 0.1f, 1920, 1080f / 1920);
        for (int i = 1; i <= 20; i++) pen.drag(0.1f + i * 0.01f, 0.1f + i * 0.005f, 0.5625f);
        pen.release(0.35f, 0.2f, 0.5625f);
        for (byte[] m : wire) host.apply(m);
        assertEquals(1, viewer.snapshot().size());
        assertEquals(1, host.snapshot().size());
        Stroke v = viewer.snapshot().get(0), h = host.snapshot().get(0);
        assertEquals(v.id, h.id);
        assertTrue(h.isEnded());
        assertEquals("alice", h.authorName);
        assertTrue(Math.abs(v.pointsXY()[v.size() * 2 - 2] - h.pointsXY()[h.size() * 2 - 2]) < 1e-4);
    }

    @Test
    void shapesKeepTwoPointsAndUndoRemoves() {
        AnnotationModel m = new AnnotationModel();
        List<byte[]> wire = new ArrayList<>();
        PenController pen = new PenController(m, "bob", wire::add);
        pen.setTool(PenTool.RECTANGLE);
        pen.press(0.2f, 0.2f, 1000, 1);
        for (int i = 0; i < 30; i++) pen.drag(0.3f + i * 0.01f, 0.4f, 1);
        pen.release(0.6f, 0.5f, 1);
        assertEquals(2, m.snapshot().get(0).size());
        pen.undo();
        assertTrue(m.isEmpty());
        AnnotationModel replica = new AnnotationModel();
        for (byte[] b : wire) replica.apply(b);
        assertTrue(replica.isEmpty(), "REMOVE replicated");
    }

    @Test
    void eraserHitsNearbyStrokeAndClearAll() {
        AnnotationModel m = new AnnotationModel();
        PenController pen = new PenController(m, "c", b -> { });
        pen.press(0.1f, 0.5f, 1000, 1);
        pen.drag(0.9f, 0.5f, 1);
        pen.release(0.9f, 0.5f, 1);
        pen.setTool(PenTool.ERASER);
        pen.press(0.5f, 0.503f, 1000, 1);
        assertTrue(m.isEmpty());
        pen.setTool(PenTool.PEN);
        pen.press(0.1f, 0.1f, 1000, 1);
        pen.release(0.2f, 0.2f, 1);
        pen.clearAll();
        assertTrue(m.isEmpty());
    }

    @Test
    void snapshotMessagesRebuildState() {
        AnnotationModel m = new AnnotationModel();
        PenController pen = new PenController(m, "d", b -> { });
        pen.press(0.1f, 0.1f, 1000, 1);
        pen.drag(0.2f, 0.3f, 1);
        pen.release(0.3f, 0.3f, 1);
        AnnotationModel late = new AnnotationModel();
        for (byte[] b : m.snapshotMessages()) late.apply(b);
        assertEquals(1, late.snapshot().size());
        assertTrue(late.snapshot().get(0).isEnded());
    }

    @Test
    void rendererDrawsSomething() {
        AnnotationModel m = new AnnotationModel();
        PenController pen = new PenController(m, "e", b -> { });
        pen.setWidthPx(8);
        pen.press(0.1f, 0.5f, 200, 0.5f);
        pen.drag(0.9f, 0.5f, 0.5f);
        pen.release(0.9f, 0.5f, 0.5f);
        BufferedImage img = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB);
        new AnnotationRenderer().paint(img.createGraphics(), m.snapshot(), 0, 0, 200, 100, System.currentTimeMillis());
        assertTrue(((img.getRGB(100, 50) >>> 24) & 0xFF) > 200, "stroke painted at center");
        assertEquals(0, img.getRGB(100, 10) >>> 24, "transparent elsewhere");
    }
}
