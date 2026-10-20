package com.srd.common.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class TextImageStrokeTest {
    @Test
    void textReachesTheHostWithItsSize() {
        AnnotationModel viewer = new AnnotationModel();
        AnnotationModel host = new AnnotationModel();
        List<byte[]> wire = new ArrayList<>();
        PenController pen = new PenController(viewer, "alice", wire::add);
        pen.setWidthPx(8f);
        pen.placeText(0.25f, 0.5f, "Hello\nWorld", 2000);
        for (byte[] m : wire) host.apply(m);
        Stroke s = host.snapshot().get(0);
        assertEquals(PenTool.TEXT, s.tool);
        assertEquals("Hello\nWorld", s.text);
        assertEquals(pen.textSizePx() / 2000f, s.width, 1e-6);
        assertTrue(s.isEnded());
        assertTrue(s.near(0.26f, 0.505f, 0.008f, 0.5625f), "eraser finds the text");
        AnnotationModel late = new AnnotationModel();
        for (byte[] m : host.snapshotMessages()) late.apply(m);
        assertEquals("Hello\nWorld", late.snapshot().get(0).text);
    }

    @Test
    void imageKeepsItsProportionsAndTravelsAsBytes() throws Exception {
        BufferedImage img = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        AnnotationModel viewer = new AnnotationModel();
        AnnotationModel host = new AnnotationModel();
        List<byte[]> wire = new ArrayList<>();
        PenController pen = new PenController(viewer, "bob", wire::add);
        pen.setPendingImage(png.toByteArray(), 40, 20);
        float aspect = 0.5625f; // 16:9 screen
        pen.press(0.1f, 0.1f, 1920, aspect);
        pen.drag(0.3f, 0.9f, aspect);
        pen.release(0.3f, 0.9f, aspect);
        assertEquals(PenTool.PEN, pen.tool(), "back to the pen after placing");
        for (byte[] m : wire) host.apply(m);
        Stroke s = host.snapshot().get(0);
        assertEquals(PenTool.IMAGE, s.tool);
        assertNotNull(s.image());
        float[] p = s.pointsXY();
        float wPx = (p[2] - p[0]) * 1920, hPx = (p[3] - p[1]) * 1920 * aspect;
        assertEquals(2f, wPx / hPx, 0.02f, "2:1 picture stays 2:1 on screen");
    }
}
