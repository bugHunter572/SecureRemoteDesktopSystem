package com.srd.client.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.srd.common.annotation.PenMessage;
import com.srd.common.annotation.PenTool;
import com.srd.common.annotation.Stroke;
import java.awt.Rectangle;
import org.junit.jupiter.api.Test;

class AnnotationHubTest {
    @Test
    void convertsBetweenMonitorAndDesktopSpace() {
        Rectangle desktop = new Rectangle(0, 0, 3840, 1080);
        Rectangle second = new Rectangle(1920, 0, 1920, 1080);
        Stroke s = new Stroke(Stroke.id(5, 1), 5, "v", PenTool.PEN, 0xFFFF0000, 0.01f, 0);
        byte[] begin = PenMessage.begin(s, 1, new float[] {0.5f, 0.5f});
        PenMessage g = PenMessage.decode(AnnotationHub.convert(begin, second, desktop));
        assertEquals(0.75f, g.points[0], 1e-4);   // center of monitor 2 = 3/4 across the desktop
        assertEquals(0.5f, g.points[1], 1e-4);
        assertEquals(0.005f, g.width, 1e-5);      // same pixel width, larger reference
        PenMessage back = PenMessage.decode(AnnotationHub.convert(AnnotationHub.convert(begin, second, desktop), desktop, second));
        assertEquals(0.5f, back.points[0], 1e-4);
        byte[] end = PenMessage.end(5, 1);
        assertEquals(end, AnnotationHub.convert(end, second, desktop)); // non-geometric ops untouched
    }
}
