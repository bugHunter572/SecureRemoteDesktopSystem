package com.srd.common.input;

import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * Java VK ⇄ PC/AT scancode (set 1) for the physical US-layout key positions.
 *
 * <p>Scancodes name physical keys, not characters, so a host with a different keyboard layout
 * types what its own layout puts on that key, exactly as a local keyboard would. The old
 * protocol sent Java VK codes and hardcoded {@code KEY_COMBO}s, which mistyped on non-US
 * layouts and lost modifiers.
 */
public final class KeyMap {
    /** Packed value: scancode | (extended ? 0x100 : 0). */
    private static final Map<Integer, Integer> VK_TO_SC = new HashMap<>();
    private static final Map<Integer, Integer> NUMPAD_VK_TO_SC = new HashMap<>();
    private static final Map<Integer, Integer> SC_TO_VK = new HashMap<>();

    private KeyMap() {
    }

    private static void put(int vk, int sc) {
        VK_TO_SC.put(vk, sc);
        SC_TO_VK.putIfAbsent(sc, vk);
    }

    static {
        String letters = "QWERTYUIOP";
        for (int i = 0; i < letters.length(); i++) put(KeyEvent.getExtendedKeyCodeForChar(letters.charAt(i)), 0x10 + i);
        letters = "ASDFGHJKL";
        for (int i = 0; i < letters.length(); i++) put(KeyEvent.getExtendedKeyCodeForChar(letters.charAt(i)), 0x1E + i);
        letters = "ZXCVBNM";
        for (int i = 0; i < letters.length(); i++) put(KeyEvent.getExtendedKeyCodeForChar(letters.charAt(i)), 0x2C + i);
        put(KeyEvent.VK_1, 0x02);
        put(KeyEvent.VK_2, 0x03);
        put(KeyEvent.VK_3, 0x04);
        put(KeyEvent.VK_4, 0x05);
        put(KeyEvent.VK_5, 0x06);
        put(KeyEvent.VK_6, 0x07);
        put(KeyEvent.VK_7, 0x08);
        put(KeyEvent.VK_8, 0x09);
        put(KeyEvent.VK_9, 0x0A);
        put(KeyEvent.VK_0, 0x0B);
        put(KeyEvent.VK_MINUS, 0x0C);
        put(KeyEvent.VK_EQUALS, 0x0D);
        put(KeyEvent.VK_BACK_SPACE, 0x0E);
        put(KeyEvent.VK_TAB, 0x0F);
        put(KeyEvent.VK_OPEN_BRACKET, 0x1A);
        put(KeyEvent.VK_CLOSE_BRACKET, 0x1B);
        put(KeyEvent.VK_ENTER, 0x1C);
        put(KeyEvent.VK_CONTROL, 0x1D);
        put(KeyEvent.VK_SEMICOLON, 0x27);
        put(KeyEvent.VK_QUOTE, 0x28);
        put(KeyEvent.VK_BACK_QUOTE, 0x29);
        put(KeyEvent.VK_SHIFT, 0x2A);
        put(KeyEvent.VK_BACK_SLASH, 0x2B);
        put(KeyEvent.VK_COMMA, 0x33);
        put(KeyEvent.VK_PERIOD, 0x34);
        put(KeyEvent.VK_SLASH, 0x35);
        put(KeyEvent.VK_ALT, 0x38);
        put(KeyEvent.VK_SPACE, 0x39);
        put(KeyEvent.VK_CAPS_LOCK, 0x3A);
        for (int i = 0; i < 10; i++) put(KeyEvent.VK_F1 + i, 0x3B + i);
        put(KeyEvent.VK_NUM_LOCK, 0x145); // NumLock is E0-extended 0x45; plain 0x45 is Pause (sent by VK instead)
        put(KeyEvent.VK_SCROLL_LOCK, 0x46);
        put(KeyEvent.VK_LESS, 0x56); // ISO 102nd key
        put(KeyEvent.VK_F11, 0x57);
        put(KeyEvent.VK_F12, 0x58);
        for (int i = 0; i < 12; i++) put(KeyEvent.VK_F13 + i, 0x64 + i);
        put(KeyEvent.VK_ESCAPE, 0x01);
        // Extended (E0-prefixed) navigation/editing keys.
        put(KeyEvent.VK_INSERT, 0x152);
        put(KeyEvent.VK_DELETE, 0x153);
        put(KeyEvent.VK_HOME, 0x147);
        put(KeyEvent.VK_END, 0x14F);
        put(KeyEvent.VK_PAGE_UP, 0x149);
        put(KeyEvent.VK_PAGE_DOWN, 0x151);
        put(KeyEvent.VK_UP, 0x148);
        put(KeyEvent.VK_DOWN, 0x150);
        put(KeyEvent.VK_LEFT, 0x14B);
        put(KeyEvent.VK_RIGHT, 0x14D);
        put(KeyEvent.VK_WINDOWS, 0x15B);
        put(KeyEvent.VK_CONTEXT_MENU, 0x15D);
        put(KeyEvent.VK_PRINTSCREEN, 0x137);
        put(KeyEvent.VK_ALT_GRAPH, 0x138);
        put(KeyEvent.VK_DIVIDE, 0x135);
        // Numpad
        put(KeyEvent.VK_MULTIPLY, 0x37);
        put(KeyEvent.VK_SUBTRACT, 0x4A);
        put(KeyEvent.VK_ADD, 0x4E);
        put(KeyEvent.VK_DECIMAL, 0x53);
        put(KeyEvent.VK_NUMPAD7, 0x47);
        put(KeyEvent.VK_NUMPAD8, 0x48);
        put(KeyEvent.VK_NUMPAD9, 0x49);
        put(KeyEvent.VK_NUMPAD4, 0x4B);
        put(KeyEvent.VK_NUMPAD5, 0x4C);
        put(KeyEvent.VK_NUMPAD6, 0x4D);
        put(KeyEvent.VK_NUMPAD1, 0x4F);
        put(KeyEvent.VK_NUMPAD2, 0x50);
        put(KeyEvent.VK_NUMPAD3, 0x51);
        put(KeyEvent.VK_NUMPAD0, 0x52);
        // Keys whose scancode depends on location.
        NUMPAD_VK_TO_SC.put(KeyEvent.VK_ENTER, 0x11C);
        // Navigation keys reported with NUMPAD location are the numpad (non-extended) variants.
        NUMPAD_VK_TO_SC.put(KeyEvent.VK_HOME, 0x47);
        NUMPAD_VK_TO_SC.put(KeyEvent.VK_UP, 0x48);
        NUMPAD_VK_TO_SC.put(KeyEvent.VK_PAGE_UP, 0x49);
        NUMPAD_VK_TO_SC.put(KeyEvent.VK_LEFT, 0x4B);
        NUMPAD_VK_TO_SC.put(KeyEvent.VK_RIGHT, 0x4D);
        NUMPAD_VK_TO_SC.put(KeyEvent.VK_END, 0x4F);
        NUMPAD_VK_TO_SC.put(KeyEvent.VK_DOWN, 0x50);
        NUMPAD_VK_TO_SC.put(KeyEvent.VK_PAGE_DOWN, 0x51);
        NUMPAD_VK_TO_SC.put(KeyEvent.VK_INSERT, 0x52);
        NUMPAD_VK_TO_SC.put(KeyEvent.VK_DELETE, 0x53);
        NUMPAD_VK_TO_SC.put(KeyEvent.VK_KP_UP, 0x48);
        NUMPAD_VK_TO_SC.put(KeyEvent.VK_KP_DOWN, 0x50);
        NUMPAD_VK_TO_SC.put(KeyEvent.VK_KP_LEFT, 0x4B);
        NUMPAD_VK_TO_SC.put(KeyEvent.VK_KP_RIGHT, 0x4D);
    }

    /**
     * @return packed scancode ({@code sc | 0x100} if E0-extended), or 0 if unknown
     */
    public static int toScancode(int vk, int keyLocation) {
        if (keyLocation == KeyEvent.KEY_LOCATION_NUMPAD) {
            Integer np = NUMPAD_VK_TO_SC.get(vk);
            if (np != null) return np;
        }
        if (keyLocation == KeyEvent.KEY_LOCATION_RIGHT) {
            switch (vk) {
                case KeyEvent.VK_CONTROL:
                    return 0x11D;
                case KeyEvent.VK_SHIFT:
                    return 0x36;
                case KeyEvent.VK_ALT:
                    return 0x138;
                case KeyEvent.VK_WINDOWS:
                    return 0x15C;
                default:
                    break;
            }
        }
        Integer sc = VK_TO_SC.get(vk);
        return sc == null ? 0 : sc;
    }

    /** Java VK for a scancode (for Robot-based hosts); 0 if unknown. */
    public static int toVk(int scancode, boolean extended) {
        int key = scancode | (extended ? 0x100 : 0);
        switch (key) {
            case 0x11D:
                return KeyEvent.VK_CONTROL;
            case 0x36:
                return KeyEvent.VK_SHIFT;
            case 0x138:
                return KeyEvent.VK_ALT; // right Alt; Robot can't distinguish sides anyway
            case 0x15C:
                return KeyEvent.VK_WINDOWS;
            case 0x11C:
                return KeyEvent.VK_ENTER;
            default:
                break;
        }
        Integer vk = SC_TO_VK.get(key);
        return vk == null ? 0 : vk;
    }

    public static boolean isModifier(int vk) {
        return vk == KeyEvent.VK_SHIFT || vk == KeyEvent.VK_CONTROL || vk == KeyEvent.VK_ALT
                || vk == KeyEvent.VK_ALT_GRAPH || vk == KeyEvent.VK_WINDOWS || vk == KeyEvent.VK_META;
    }
}
