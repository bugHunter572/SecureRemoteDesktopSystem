package com.srd.client.input;

import com.srd.common.input.InputEvent;
import com.srd.common.input.KeyMap;
import com.srd.common.util.Log;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Windows host input via SendInput: absolute virtual-desktop mouse coordinates in physical
 * pixels (exact on mixed-DPI multi-monitor setups), scancode keyboard events (layout
 * independent) and KEYEVENTF_UNICODE text.
 */
final class WindowsSendInputInjector implements InputInjector {
    private static final int SM_XVIRTUALSCREEN = 76, SM_YVIRTUALSCREEN = 77, SM_CXVIRTUALSCREEN = 78, SM_CYVIRTUALSCREEN = 79;

    private static final int MOUSEEVENTF_MOVE = 0x0001;
    private static final int MOUSEEVENTF_LEFTDOWN = 0x0002, MOUSEEVENTF_LEFTUP = 0x0004;
    private static final int MOUSEEVENTF_RIGHTDOWN = 0x0008, MOUSEEVENTF_RIGHTUP = 0x0010;
    private static final int MOUSEEVENTF_MIDDLEDOWN = 0x0020, MOUSEEVENTF_MIDDLEUP = 0x0040;
    private static final int MOUSEEVENTF_XDOWN = 0x0080, MOUSEEVENTF_XUP = 0x0100;
    private static final int MOUSEEVENTF_WHEEL = 0x0800, MOUSEEVENTF_HWHEEL = 0x1000;
    private static final int MOUSEEVENTF_VIRTUALDESK = 0x4000, MOUSEEVENTF_ABSOLUTE = 0x8000;
    private static final int XBUTTON1 = 1, XBUTTON2 = 2;

    private static final int KEYEVENTF_EXTENDEDKEY = 0x1, KEYEVENTF_KEYUP = 0x2, KEYEVENTF_UNICODE = 0x4, KEYEVENTF_SCANCODE = 0x8;

    /** Java VK → Windows VK for keys without a scancode mapping (where the codes differ). */
    private static final Map<Integer, Integer> VK_JAVA_TO_WIN = new HashMap<>();
    /** Windows VKs that need KEYEVENTF_EXTENDEDKEY when injected by VK. */
    private static final Set<Integer> EXTENDED_VK = new HashSet<>();

    static {
        int[][] t = {
                {KeyEvent.VK_ENTER, 0x0D}, {KeyEvent.VK_BACK_SPACE, 0x08}, {KeyEvent.VK_TAB, 0x09},
                {KeyEvent.VK_SHIFT, 0x10}, {KeyEvent.VK_CONTROL, 0x11}, {KeyEvent.VK_ALT, 0x12},
                {KeyEvent.VK_PAUSE, 0x13}, {KeyEvent.VK_CAPS_LOCK, 0x14}, {KeyEvent.VK_ESCAPE, 0x1B},
                {KeyEvent.VK_SPACE, 0x20}, {KeyEvent.VK_PAGE_UP, 0x21}, {KeyEvent.VK_PAGE_DOWN, 0x22},
                {KeyEvent.VK_END, 0x23}, {KeyEvent.VK_HOME, 0x24}, {KeyEvent.VK_LEFT, 0x25},
                {KeyEvent.VK_UP, 0x26}, {KeyEvent.VK_RIGHT, 0x27}, {KeyEvent.VK_DOWN, 0x28},
                {KeyEvent.VK_PRINTSCREEN, 0x2C}, {KeyEvent.VK_INSERT, 0x2D}, {KeyEvent.VK_DELETE, 0x2E},
                {KeyEvent.VK_WINDOWS, 0x5B}, {KeyEvent.VK_CONTEXT_MENU, 0x5D},
                {KeyEvent.VK_MULTIPLY, 0x6A}, {KeyEvent.VK_ADD, 0x6B}, {KeyEvent.VK_SEPARATOR, 0x6C},
                {KeyEvent.VK_SUBTRACT, 0x6D}, {KeyEvent.VK_DECIMAL, 0x6E}, {KeyEvent.VK_DIVIDE, 0x6F},
                {KeyEvent.VK_NUM_LOCK, 0x90}, {KeyEvent.VK_SCROLL_LOCK, 0x91},
                {KeyEvent.VK_SEMICOLON, 0xBA}, {KeyEvent.VK_EQUALS, 0xBB}, {KeyEvent.VK_COMMA, 0xBC},
                {KeyEvent.VK_MINUS, 0xBD}, {KeyEvent.VK_PERIOD, 0xBE}, {KeyEvent.VK_SLASH, 0xBF},
                {KeyEvent.VK_BACK_QUOTE, 0xC0}, {KeyEvent.VK_OPEN_BRACKET, 0xDB}, {KeyEvent.VK_BACK_SLASH, 0xDC},
                {KeyEvent.VK_CLOSE_BRACKET, 0xDD}, {KeyEvent.VK_QUOTE, 0xDE},
                {KeyEvent.VK_KANA, 0x15}, {KeyEvent.VK_KANJI, 0x19}, {KeyEvent.VK_CONVERT, 0x1C},
                {KeyEvent.VK_NONCONVERT, 0x1D},
        };
        for (int[] p : t) VK_JAVA_TO_WIN.put(p[0], p[1]);
        for (int i = 0; i < 12; i++) VK_JAVA_TO_WIN.put(KeyEvent.VK_F13 + i, 0x7C + i);
        int[] ext = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2C, 0x2D, 0x2E, 0x5B, 0x5C, 0x5D, 0x6F, 0x90};
        for (int v : ext) EXTENDED_VK.add(v);
    }

    /** Per-controller pressed state: keys as "s<packed sc>" / "v<winVk>", buttons as Integer. */
    private static final class Held {
        final Set<Integer> scancodes = new HashSet<>(); // packed sc | 0x100 if extended
        final Set<Integer> vks = new HashSet<>();       // Windows VKs
        final Set<Integer> buttons = new HashSet<>();
    }

    private final Map<Object, Held> held = new HashMap<>();
    private final User32 user32;

    WindowsSendInputInjector() {
        user32 = User32.INSTANCE;
        // Fail fast (→ Robot fallback) if user32 can't be loaded / the INPUT layout is unusable.
        if (user32.GetSystemMetrics(SM_CXVIRTUALSCREEN) <= 0) throw new IllegalStateException("no virtual screen");
        new WinUser.INPUT().size();
    }

    @Override
    public String name() {
        return "SendInput";
    }

    @Override
    public synchronized void inject(Object controller, InputEvent e, Rectangle physical, Rectangle logical) {
        try {
            switch (e.kind) {
                case InputEvent.MOVE:
                    send(move(e.nx, e.ny, physical));
                    break;
                case InputEvent.BUTTON:
                    button(controller, e, physical);
                    break;
                case InputEvent.WHEEL: {
                    List<WinUser.INPUT> l = new ArrayList<>(2);
                    if (e.wheelY != 0) l.add(mouse(0, 0, e.wheelY, MOUSEEVENTF_WHEEL));
                    if (e.wheelX != 0) l.add(mouse(0, 0, e.wheelX, MOUSEEVENTF_HWHEEL));
                    send(l.toArray(new WinUser.INPUT[0]));
                    break;
                }
                case InputEvent.KEY:
                    key(controller, e);
                    break;
                case InputEvent.TEXT:
                    text(e.text);
                    break;
                case InputEvent.RELEASE_ALL:
                    releaseAll(controller);
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            Log.warn("SendInput failed for " + e + ": " + t);
        }
    }

    private void button(Object controller, InputEvent e, Rectangle physical) {
        int flag;
        int data = 0;
        switch (e.button) {
            case InputEvent.BTN_LEFT:
                flag = e.down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP;
                break;
            case InputEvent.BTN_RIGHT:
                flag = e.down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP;
                break;
            case InputEvent.BTN_MIDDLE:
                flag = e.down ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP;
                break;
            case InputEvent.BTN_BACK:
                flag = e.down ? MOUSEEVENTF_XDOWN : MOUSEEVENTF_XUP;
                data = XBUTTON1;
                break;
            case InputEvent.BTN_FORWARD:
                flag = e.down ? MOUSEEVENTF_XDOWN : MOUSEEVENTF_XUP;
                data = XBUTTON2;
                break;
            default:
                return;
        }
        Held h = heldFor(controller);
        if (e.down) h.buttons.add(e.button);
        else h.buttons.remove(e.button);
        // Move to the button's coordinates and press/release in one atomic batch.
        WinUser.INPUT[] in = (WinUser.INPUT[]) new WinUser.INPUT().toArray(2);
        fillMove(in[0], e.nx, e.ny, physical);
        fillMouse(in[1], 0, 0, data, flag);
        send(in);
    }

    private void key(Object controller, InputEvent e) {
        Held h = heldFor(controller);
        int sc = e.scancode;
        boolean ext = e.extended;
        int winVk = 0;
        // Pause (E1 1D 45) and NumLock (E0 45 on Windows) don't round-trip through plain set-1
        // scancodes; inject those by VK.
        boolean byVk = e.vk == KeyEvent.VK_PAUSE || (e.vk == KeyEvent.VK_NUM_LOCK && !e.extended);
        if (byVk) sc = 0;
        if (sc == 0 && e.vk != 0 && !byVk) {
            int packed = KeyMap.toScancode(e.vk, e.location);
            sc = packed & 0xFF;
            ext = (packed & 0x100) != 0;
        }
        if (sc == 0) {
            winVk = toWinVk(e.vk);
            if (winVk == 0) return; // unmappable
        }
        WinUser.INPUT in = new WinUser.INPUT();
        in.type = new WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD);
        in.input.setType("ki");
        int flags = e.down ? 0 : KEYEVENTF_KEYUP;
        if (sc != 0) {
            flags |= KEYEVENTF_SCANCODE | (ext ? KEYEVENTF_EXTENDEDKEY : 0);
            in.input.ki.wVk = new WinDef.WORD(0);
            in.input.ki.wScan = new WinDef.WORD(sc);
            int packed = sc | (ext ? 0x100 : 0);
            if (e.down) h.scancodes.add(packed);
            else h.scancodes.remove(packed);
        } else {
            if (EXTENDED_VK.contains(winVk)) flags |= KEYEVENTF_EXTENDEDKEY;
            in.input.ki.wVk = new WinDef.WORD(winVk);
            in.input.ki.wScan = new WinDef.WORD(0);
            if (e.down) h.vks.add(winVk);
            else h.vks.remove(winVk);
        }
        in.input.ki.dwFlags = new WinDef.DWORD(flags);
        in.input.ki.time = new WinDef.DWORD(0);
        in.input.ki.dwExtraInfo = new BaseTSD.ULONG_PTR(0);
        send(new WinUser.INPUT[]{in});
    }

    /** Java VK → Windows VK; identical for A-Z, 0-9, F1-F12, numpad digits. 0 = unknown. */
    static int toWinVk(int javaVk) {
        if ((javaVk >= 'A' && javaVk <= 'Z') || (javaVk >= '0' && javaVk <= '9')) return javaVk;
        if (javaVk >= KeyEvent.VK_F1 && javaVk <= KeyEvent.VK_F12) return javaVk; // 0x70..0x7B both
        if (javaVk >= KeyEvent.VK_NUMPAD0 && javaVk <= KeyEvent.VK_NUMPAD9) return javaVk; // 0x60..0x69 both
        Integer w = VK_JAVA_TO_WIN.get(javaVk);
        return w == null ? 0 : w;
    }

    private void text(String s) {
        if (s == null || s.isEmpty()) return;
        WinUser.INPUT[] in = (WinUser.INPUT[]) new WinUser.INPUT().toArray(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            for (int k = 0; k < 2; k++) {
                WinUser.INPUT x = in[i * 2 + k];
                x.type = new WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD);
                x.input.setType("ki");
                x.input.ki.wVk = new WinDef.WORD(0);
                x.input.ki.wScan = new WinDef.WORD(s.charAt(i));
                x.input.ki.dwFlags = new WinDef.DWORD(KEYEVENTF_UNICODE | (k == 1 ? KEYEVENTF_KEYUP : 0));
                x.input.ki.time = new WinDef.DWORD(0);
                x.input.ki.dwExtraInfo = new BaseTSD.ULONG_PTR(0);
            }
        }
        send(in);
    }

    @Override
    public synchronized void releaseAll(Object controller) {
        Held h = held.remove(controller);
        if (h == null) return;
        try {
            List<WinUser.INPUT> ups = new ArrayList<>();
            for (int packed : h.scancodes) {
                WinUser.INPUT in = keyInput(0, packed & 0xFF,
                        KEYEVENTF_KEYUP | KEYEVENTF_SCANCODE | ((packed & 0x100) != 0 ? KEYEVENTF_EXTENDEDKEY : 0));
                ups.add(in);
            }
            for (int vk : h.vks) {
                ups.add(keyInput(vk, 0, KEYEVENTF_KEYUP | (EXTENDED_VK.contains(vk) ? KEYEVENTF_EXTENDEDKEY : 0)));
            }
            for (int b : h.buttons) {
                switch (b) {
                    case InputEvent.BTN_LEFT:
                        ups.add(mouse(0, 0, 0, MOUSEEVENTF_LEFTUP));
                        break;
                    case InputEvent.BTN_RIGHT:
                        ups.add(mouse(0, 0, 0, MOUSEEVENTF_RIGHTUP));
                        break;
                    case InputEvent.BTN_MIDDLE:
                        ups.add(mouse(0, 0, 0, MOUSEEVENTF_MIDDLEUP));
                        break;
                    case InputEvent.BTN_BACK:
                        ups.add(mouse(0, 0, XBUTTON1, MOUSEEVENTF_XUP));
                        break;
                    case InputEvent.BTN_FORWARD:
                        ups.add(mouse(0, 0, XBUTTON2, MOUSEEVENTF_XUP));
                        break;
                    default:
                        break;
                }
            }
            if (!ups.isEmpty()) {
                send(ups.toArray(new WinUser.INPUT[0]));
                Log.info("Released " + ups.size() + " held keys/buttons for " + controller);
            }
        } catch (Throwable t) {
            Log.warn("releaseAll failed: " + t);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Held heldFor(Object controller) {
        return held.computeIfAbsent(controller, c -> new Held());
    }

    /**
     * Physical virtual-desktop pixel → SendInput absolute coordinate (0..65535 across the
     * virtual desktop with MOUSEEVENTF_VIRTUALDESK). Targets the pixel centre, so it lands on
     * exactly {@code px} whether Windows maps back with {@code floor(ax * size / 65536)} or
     * {@code round(ax * (size - 1) / 65535)} (the plain {@code round(px * 65535 / (size - 1))}
     * form is off by one under the first model).
     */
    static int toAbsolute(int px, int virtualOrigin, int virtualSize) {
        if (virtualSize <= 1) return 0;
        long a = (long) Math.floor((px - virtualOrigin + 0.5) * 65536.0 / virtualSize);
        return (int) Math.max(0, Math.min(65535, a));
    }

    /** Normalized stream coordinate → physical virtual-desktop pixel. */
    static int toPhysical(int n, int origin, int size) {
        return origin + InputEvent.denorm(n, size);
    }

    private WinUser.INPUT[] move(int nx, int ny, Rectangle physical) {
        WinUser.INPUT[] in = (WinUser.INPUT[]) new WinUser.INPUT().toArray(1);
        fillMove(in[0], nx, ny, physical);
        return in;
    }

    private void fillMove(WinUser.INPUT in, int nx, int ny, Rectangle physical) {
        int px = toPhysical(nx, physical.x, physical.width);
        int py = toPhysical(ny, physical.y, physical.height);
        int vx = user32.GetSystemMetrics(SM_XVIRTUALSCREEN);
        int vy = user32.GetSystemMetrics(SM_YVIRTUALSCREEN);
        int vw = user32.GetSystemMetrics(SM_CXVIRTUALSCREEN);
        int vh = user32.GetSystemMetrics(SM_CYVIRTUALSCREEN);
        fillMouse(in, toAbsolute(px, vx, vw), toAbsolute(py, vy, vh), 0,
                MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK);
    }

    private static WinUser.INPUT mouse(int dx, int dy, int data, int flags) {
        WinUser.INPUT in = new WinUser.INPUT();
        fillMouse(in, dx, dy, data, flags);
        return in;
    }

    private static void fillMouse(WinUser.INPUT in, int dx, int dy, int data, int flags) {
        in.type = new WinDef.DWORD(WinUser.INPUT.INPUT_MOUSE);
        in.input.setType("mi");
        in.input.mi.dx = new WinDef.LONG(dx);
        in.input.mi.dy = new WinDef.LONG(dy);
        in.input.mi.mouseData = new WinDef.DWORD(data & 0xFFFFFFFFL); // wheel deltas may be negative
        in.input.mi.dwFlags = new WinDef.DWORD(flags);
        in.input.mi.time = new WinDef.DWORD(0);
        in.input.mi.dwExtraInfo = new BaseTSD.ULONG_PTR(0);
    }

    private static WinUser.INPUT keyInput(int vk, int scan, int flags) {
        WinUser.INPUT in = new WinUser.INPUT();
        in.type = new WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD);
        in.input.setType("ki");
        in.input.ki.wVk = new WinDef.WORD(vk);
        in.input.ki.wScan = new WinDef.WORD(scan);
        in.input.ki.dwFlags = new WinDef.DWORD(flags);
        in.input.ki.time = new WinDef.DWORD(0);
        in.input.ki.dwExtraInfo = new BaseTSD.ULONG_PTR(0);
        return in;
    }

    /**
     * SendInput needs a contiguous INPUT array; structures built individually are copied into
     * one block from {@code toArray}.
     */
    private void send(WinUser.INPUT[] inputs) {
        if (inputs.length == 0) return;
        WinUser.INPUT[] block = inputs;
        if (!contiguous(inputs)) {
            block = (WinUser.INPUT[]) new WinUser.INPUT().toArray(inputs.length);
            for (int i = 0; i < inputs.length; i++) copy(inputs[i], block[i]);
        }
        int sent = user32.SendInput(new WinDef.DWORD(block.length), block, block[0].size()).intValue();
        if (sent != block.length) {
            // 0 = blocked by UIPI (elevated foreground window) or the secure desktop.
            Log.debug("SendInput injected " + sent + "/" + block.length);
        }
    }

    private static boolean contiguous(WinUser.INPUT[] a) {
        if (a.length == 1) return true;
        long base = com.sun.jna.Pointer.nativeValue(a[0].getPointer());
        int sz = a[0].size();
        for (int i = 1; i < a.length; i++) {
            if (com.sun.jna.Pointer.nativeValue(a[i].getPointer()) != base + (long) i * sz) return false;
        }
        return true;
    }

    private static void copy(WinUser.INPUT from, WinUser.INPUT to) {
        to.type = from.type;
        String t = from.type.intValue() == WinUser.INPUT.INPUT_MOUSE ? "mi" : "ki";
        to.input.setType(t);
        if ("mi".equals(t)) {
            to.input.mi.dx = from.input.mi.dx;
            to.input.mi.dy = from.input.mi.dy;
            to.input.mi.mouseData = from.input.mi.mouseData;
            to.input.mi.dwFlags = from.input.mi.dwFlags;
            to.input.mi.time = from.input.mi.time;
            to.input.mi.dwExtraInfo = from.input.mi.dwExtraInfo;
        } else {
            to.input.ki.wVk = from.input.ki.wVk;
            to.input.ki.wScan = from.input.ki.wScan;
            to.input.ki.dwFlags = from.input.ki.dwFlags;
            to.input.ki.time = from.input.ki.time;
            to.input.ki.dwExtraInfo = from.input.ki.dwExtraInfo;
        }
    }
}
