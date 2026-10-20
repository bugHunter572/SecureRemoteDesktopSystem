package com.srd.common.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class InputTest {

    @Test
    void eventsRoundTrip() {
        List<InputEvent> in = Arrays.asList(
                InputEvent.move(12, 65535),
                InputEvent.button(InputEvent.BTN_RIGHT, true, 100, 200),
                InputEvent.wheel(-240, 30),
                InputEvent.key(true, 0x1D, true, KeyEvent.VK_CONTROL, KeyEvent.KEY_LOCATION_RIGHT),
                InputEvent.releaseAll(),
                InputEvent.text("héllo"));
        List<InputEvent> out = InputEvent.decodeBatch(InputEvent.encodeBatch(in));
        assertEquals(in.size(), out.size());
        for (int i = 0; i < in.size(); i++) assertEquals(in.get(i).toString(), out.get(i).toString());
        assertEquals("héllo", out.get(5).text);
        assertEquals(-240, out.get(2).wheelY);
    }

    @Test
    void oneMouseNotchIsExactlyOneWheelDelta() {
        WheelAccumulator w = new WheelAccumulator();
        InputEvent e = w.onWheel(1.0, false); // one notch towards the user = scroll down
        assertEquals(-InputEvent.WHEEL_DELTA, e.wheelY);
        assertEquals(InputEvent.WHEEL_DELTA, w.onWheel(-1.0, false).wheelY);
    }

    @Test
    void touchpadFractionsAccumulateWithoutLoss() {
        WheelAccumulator w = new WheelAccumulator();
        int total = 0;
        for (int i = 0; i < 100; i++) {
            InputEvent e = w.onWheel(-0.013, false); // tiny precise deltas
            if (e != null) total += e.wheelY;
        }
        assertEquals(156, total, 1); // 100 * 0.013 * 120 = 156
        assertNull(new WheelAccumulator().onWheel(0.001, false));
    }

    @Test
    void horizontalWheel() {
        WheelAccumulator w = new WheelAccumulator();
        InputEvent e = w.onWheel(1.0, true);
        assertEquals(0, e.wheelY);
        assertEquals(InputEvent.WHEEL_DELTA, e.wheelX); // shift+wheel down = scroll right
    }

    @Test
    void scancodesForCommonKeys() {
        assertEquals(0x1E, KeyMap.toScancode(KeyEvent.VK_A, KeyEvent.KEY_LOCATION_STANDARD));
        assertEquals(0x1C, KeyMap.toScancode(KeyEvent.VK_ENTER, KeyEvent.KEY_LOCATION_STANDARD));
        assertEquals(0x11C, KeyMap.toScancode(KeyEvent.VK_ENTER, KeyEvent.KEY_LOCATION_NUMPAD));
        assertEquals(0x11D, KeyMap.toScancode(KeyEvent.VK_CONTROL, KeyEvent.KEY_LOCATION_RIGHT));
        assertEquals(0x1D, KeyMap.toScancode(KeyEvent.VK_CONTROL, KeyEvent.KEY_LOCATION_LEFT));
        assertEquals(0x14B, KeyMap.toScancode(KeyEvent.VK_LEFT, KeyEvent.KEY_LOCATION_STANDARD));
        assertEquals(0x15B, KeyMap.toScancode(KeyEvent.VK_WINDOWS, KeyEvent.KEY_LOCATION_LEFT));
        assertEquals(KeyEvent.VK_A, KeyMap.toVk(0x1E, false));
        assertEquals(KeyEvent.VK_LEFT, KeyMap.toVk(0x4B, true));
        assertEquals(KeyEvent.VK_NUMPAD4, KeyMap.toVk(0x4B, false));
        assertTrue(KeyMap.isModifier(KeyEvent.VK_SHIFT));
    }

    @Test
    void denormIsExactAtEdges() {
        assertEquals(0, InputEvent.denorm(0, 1920));
        assertEquals(1919, InputEvent.denorm(65535, 1920));
        assertEquals(959, InputEvent.denorm(32767, 1920));
    }
}
