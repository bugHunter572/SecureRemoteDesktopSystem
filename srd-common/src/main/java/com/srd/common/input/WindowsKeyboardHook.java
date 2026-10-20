package com.srd.common.input;

import com.srd.common.util.Log;
import com.srd.common.util.Threads;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import java.awt.event.KeyEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * WH_KEYBOARD_LL hook that, while active, forwards every physical key (including Win, Alt+Tab,
 * Ctrl+Esc) to the sink and swallows it locally. The hook lives on its own thread with a
 * GetMessage loop (LL hooks are called on the installing thread's message loop). Events we
 * injected ourselves (LLKHF_INJECTED) are always passed through, so a host on the same machine
 * never loops. Ctrl+Alt+Del is handled by the secure attention sequence and never reaches hooks.
 */
public final class WindowsKeyboardHook implements KeyboardGrabber {
    private static final int WH_KEYBOARD_LL = 13;
    private static final int WM_KEYDOWN = 0x100, WM_SYSKEYDOWN = 0x104;
    private static final int WM_QUIT = 0x12;
    private static final int LLKHF_EXTENDED = 0x01, LLKHF_INJECTED = 0x10;

    private volatile boolean active;
    private volatile Consumer<InputEvent> sink;
    private volatile boolean closed;

    private Thread thread;
    private volatile int threadId;
    private volatile WinUser.HHOOK hook;
    /** Strong reference: the native hook calls into this; GC'ing it would crash the process. */
    private WinUser.LowLevelKeyboardProc proc;
    /** Hands events off the hook thread (LL hooks must return within ~300 ms or Windows drops them). */
    private final ExecutorService delivery = Executors.newSingleThreadExecutor(Threads.factory("srd-kbhook-sink", Thread.NORM_PRIORITY + 1));
    /** Windows vkCodes whose key-down we swallowed (guarded by swallowLock). */
    private final Object swallowLock = new Object();
    private final boolean[] swallowedDown = new boolean[256];
    private final int[] swallowedScan = new int[256];
    private final boolean[] swallowedExt = new boolean[256];

    public WindowsKeyboardHook() {
        // Bind user32 eagerly so KeyboardGrabbers.create() fails fast on a broken JNA setup.
        User32.INSTANCE.GetSystemMetrics(0);
    }

    @Override
    public synchronized void setActive(boolean on) {
        if (closed) return;
        if (on && thread == null) start();
        boolean was = active;
        active = on;
        if (was && !on) releaseSwallowedLater();
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void setSink(Consumer<InputEvent> sink) {
        this.sink = sink;
    }

    private void start() {
        CountDownLatch ready = new CountDownLatch(1);
        thread = Threads.start("srd-kbhook", Thread.MAX_PRIORITY, () -> loop(ready));
        try {
            ready.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (hook == null) Log.warn("Keyboard hook not installed; system keys will not be forwarded");
    }

    private void loop(CountDownLatch ready) {
        try {
            threadId = Kernel32.INSTANCE.GetCurrentThreadId();
            proc = this::callback;
            hook = User32.INSTANCE.SetWindowsHookEx(WH_KEYBOARD_LL, proc, Kernel32.INSTANCE.GetModuleHandle(null), 0);
            if (hook == null) {
                Log.warn("SetWindowsHookEx(WH_KEYBOARD_LL) failed: " + Kernel32.INSTANCE.GetLastError());
                return;
            }
            Log.info("Keyboard hook installed");
        } catch (Throwable t) {
            Log.warn("Keyboard hook unavailable: " + t);
            return;
        } finally {
            ready.countDown();
        }
        try {
            WinUser.MSG msg = new WinUser.MSG();
            while (!closed) {
                int r = User32.INSTANCE.GetMessage(msg, null, 0, 0);
                if (r == 0 || r == -1) break; // WM_QUIT or error
                User32.INSTANCE.TranslateMessage(msg);
                User32.INSTANCE.DispatchMessage(msg);
            }
        } catch (Throwable t) {
            Log.warn("Keyboard hook loop ended: " + t);
        } finally {
            WinUser.HHOOK h = hook;
            hook = null;
            if (h != null) {
                try {
                    User32.INSTANCE.UnhookWindowsHookEx(h);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private WinDef.LRESULT callback(int nCode, WinDef.WPARAM wParam, WinUser.KBDLLHOOKSTRUCT k) {
        try {
            if (nCode >= 0 && active && k != null && (k.flags & LLKHF_INJECTED) == 0) {
                int msg = wParam.intValue();
                boolean down = msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN;
                int vkCode = k.vkCode & 0xFF;
                int sc = k.scanCode & 0xFF;
                boolean ext = (k.flags & LLKHF_EXTENDED) != 0;
                synchronized (swallowLock) {
                    if (down) {
                        swallowedDown[vkCode] = true;
                        swallowedScan[vkCode] = sc;
                        swallowedExt[vkCode] = ext;
                    } else if (!swallowedDown[vkCode]) {
                        // Pressed before we became active: let the release reach the local OS so
                        // the key doesn't stay stuck down here; the remote never saw the press.
                        return callNext(nCode, wParam, k);
                    } else {
                        swallowedDown[vkCode] = false;
                    }
                }
                deliver(InputEvent.key(down, sc, ext, javaVk(vkCode), 0));
                return new WinDef.LRESULT(1);
            }
        } catch (Throwable t) {
            // Never let an exception escape into native code.
        }
        return callNext(nCode, wParam, k);
    }

    private WinDef.LRESULT callNext(int nCode, WinDef.WPARAM wParam, WinUser.KBDLLHOOKSTRUCT k) {
        WinDef.LPARAM lp = new WinDef.LPARAM(k == null ? 0 : com.sun.jna.Pointer.nativeValue(k.getPointer()));
        return User32.INSTANCE.CallNextHookEx(hook, nCode, wParam, lp);
    }

    private void deliver(InputEvent e) {
        Consumer<InputEvent> s = sink;
        if (s == null) return;
        try {
            delivery.execute(() -> {
                try {
                    s.accept(e);
                } catch (Throwable t) {
                    Log.warn("Keyboard sink failed: " + t);
                }
            });
        } catch (Throwable ignored) {
            // executor shut down
        }
    }

    /** On deactivation, send key-ups for keys we swallowed so the remote doesn't keep them held. */
    private void releaseSwallowedLater() {
        final java.util.List<InputEvent> ups = new java.util.ArrayList<>();
        synchronized (swallowLock) {
            for (int vk = 0; vk < 256; vk++) {
                if (!swallowedDown[vk]) continue;
                swallowedDown[vk] = false;
                ups.add(InputEvent.key(false, swallowedScan[vk], swallowedExt[vk], javaVk(vk), 0));
            }
        }
        for (InputEvent e : ups) deliver(e); // queued after any in-flight key events
    }

    /** Windows VK → Java VK where the mapping is trivial or well-known; 0 otherwise. */
    static int javaVk(int w) {
        if ((w >= 'A' && w <= 'Z') || (w >= '0' && w <= '9')) return w;
        if (w >= 0x70 && w <= 0x7B) return KeyEvent.VK_F1 + (w - 0x70);
        if (w >= 0x7C && w <= 0x87) return KeyEvent.VK_F13 + (w - 0x7C);
        if (w >= 0x60 && w <= 0x69) return KeyEvent.VK_NUMPAD0 + (w - 0x60);
        switch (w) {
            case 0x13:
                return KeyEvent.VK_PAUSE;
            case 0x90:
                return KeyEvent.VK_NUM_LOCK;
            case 0x91:
                return KeyEvent.VK_SCROLL_LOCK;
            case 0x2C:
                return KeyEvent.VK_PRINTSCREEN;
            case 0x5B:
            case 0x5C:
                return KeyEvent.VK_WINDOWS;
            default:
                return 0;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        active = false;
        closed = true;
        int id = threadId;
        if (id != 0) {
            try {
                User32.INSTANCE.PostThreadMessage(id, WM_QUIT, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
            } catch (Throwable ignored) {
            }
        }
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        WinUser.HHOOK h = hook; // loop didn't exit in time: unhook from here as a last resort
        if (h != null) {
            hook = null;
            try {
                User32.INSTANCE.UnhookWindowsHookEx(h);
            } catch (Throwable ignored) {
            }
        }
        delivery.shutdown();
    }
}
