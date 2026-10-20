package com.srd.client.input;

import com.srd.common.input.InputEvent;
import com.srd.common.input.KeyMap;
import com.srd.common.util.Log;
import java.awt.AWTException;
import java.awt.MouseInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Portable host input via {@link Robot} in logical (AWT) coordinates. */
final class RobotInjector implements InputInjector {
    private static final class Held {
        final Set<Integer> keys = new HashSet<>();
        final Set<Integer> buttons = new HashSet<>(); // Java button numbers
        int wheelAccY;
        int wheelAccX;
    }

    private final Robot robot;
    private final boolean extraButtons;
    private final Map<Object, Held> held = new HashMap<>();

    RobotInjector() throws AWTException {
        robot = new Robot();
        robot.setAutoDelay(0);
        robot.setAutoWaitForIdle(false);
        boolean extra = false;
        try {
            extra = Toolkit.getDefaultToolkit().areExtraMouseButtonsEnabled() && MouseInfo.getNumberOfButtons() >= 5;
        } catch (Throwable ignored) {
        }
        extraButtons = extra;
    }

    @Override
    public String name() {
        return "Robot";
    }

    /** Normalized → logical pixel. */
    static int toLogical(int n, int origin, int size) {
        int v = (int) (InputEvent.frac(n) * size);
        return origin + Math.max(0, Math.min(size - 1, v));
    }

    @Override
    public synchronized void inject(Object controller, InputEvent e, Rectangle physical, Rectangle logical) {
        try {
            switch (e.kind) {
                case InputEvent.MOVE:
                    robot.mouseMove(toLogical(e.nx, logical.x, logical.width), toLogical(e.ny, logical.y, logical.height));
                    break;
                case InputEvent.BUTTON: {
                    int jb = javaButton(e.button);
                    if (jb == 0) break;
                    robot.mouseMove(toLogical(e.nx, logical.x, logical.width), toLogical(e.ny, logical.y, logical.height));
                    int mask = java.awt.event.InputEvent.getMaskForButton(jb);
                    Held h = heldFor(controller);
                    if (e.down) {
                        robot.mousePress(mask);
                        h.buttons.add(jb);
                    } else {
                        robot.mouseRelease(mask);
                        h.buttons.remove(jb);
                    }
                    break;
                }
                case InputEvent.WHEEL:
                    wheel(heldFor(controller), e);
                    break;
                case InputEvent.KEY: {
                    int vk = e.vk != 0 ? e.vk : KeyMap.toVk(e.scancode, e.extended);
                    if (vk == 0) break;
                    Held h = heldFor(controller);
                    try {
                        if (e.down) {
                            robot.keyPress(vk);
                            h.keys.add(vk);
                        } else {
                            robot.keyRelease(vk);
                            h.keys.remove(vk);
                        }
                    } catch (IllegalArgumentException bad) {
                        Log.debug("Robot can't type vk " + vk);
                    }
                    break;
                }
                case InputEvent.TEXT:
                    // Robot has no Unicode typing; viewers send KEY events for normal typing.
                    break;
                case InputEvent.RELEASE_ALL:
                    releaseAll(controller);
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            Log.warn("Robot injection failed for " + e + ": " + t);
        }
    }

    private int javaButton(int b) {
        switch (b) {
            case InputEvent.BTN_LEFT:
                return 1;
            case InputEvent.BTN_MIDDLE:
                return 2;
            case InputEvent.BTN_RIGHT:
                return 3;
            case InputEvent.BTN_BACK:
                return extraButtons ? 4 : 0;
            case InputEvent.BTN_FORWARD:
                return extraButtons ? 5 : 0;
            default:
                return 0;
        }
    }

    private void wheel(Held h, InputEvent e) {
        h.wheelAccY += e.wheelY;
        int notches = h.wheelAccY / InputEvent.WHEEL_DELTA; // truncates towards zero
        if (notches != 0) {
            h.wheelAccY -= notches * InputEvent.WHEEL_DELTA;
            robot.mouseWheel(-notches); // Robot: positive = towards the user (down)
        }
        h.wheelAccX += e.wheelX;
        int hx = h.wheelAccX / InputEvent.WHEEL_DELTA;
        if (hx != 0) {
            h.wheelAccX -= hx * InputEvent.WHEEL_DELTA;
            // Best effort: Shift+wheel scrolls horizontally in most apps (down = right).
            robot.keyPress(KeyEvent.VK_SHIFT);
            try {
                robot.mouseWheel(hx);
            } finally {
                robot.keyRelease(KeyEvent.VK_SHIFT);
            }
        }
    }

    @Override
    public synchronized void releaseAll(Object controller) {
        Held h = held.remove(controller);
        if (h == null) return;
        int n = 0;
        for (int vk : h.keys) {
            try {
                robot.keyRelease(vk);
                n++;
            } catch (Throwable ignored) {
            }
        }
        for (int b : h.buttons) {
            try {
                robot.mouseRelease(java.awt.event.InputEvent.getMaskForButton(b));
                n++;
            } catch (Throwable ignored) {
            }
        }
        if (n > 0) Log.info("Released " + n + " held keys/buttons for " + controller);
    }

    private Held heldFor(Object controller) {
        return held.computeIfAbsent(controller, c -> new Held());
    }
}
