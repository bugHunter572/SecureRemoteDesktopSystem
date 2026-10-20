package com.srd.client.input;

import com.srd.common.input.InputEvent;
import com.srd.common.util.Log;
import com.srd.common.util.Os;
import java.awt.Rectangle;

/** Chooses the host input backend: SendInput on Windows, Robot elsewhere (or as fallback). */
public final class Injectors {
    private Injectors() {
    }

    public static InputInjector create() {
        if (Os.WINDOWS) {
            InputInjector w = Os.tryNative("SendInput injector", WindowsSendInputInjector::new, null);
            if (w != null) {
                Log.info("Input injection: SendInput");
                return w;
            }
        }
        try {
            InputInjector r = new RobotInjector();
            Log.info("Input injection: Robot");
            return r;
        } catch (Throwable t) {
            Log.warn("Robot unavailable, remote input disabled: " + t);
            return new InputInjector() {
                @Override
                public void inject(Object controller, InputEvent e, Rectangle physical, Rectangle logical) {
                }

                @Override
                public void releaseAll(Object controller) {
                }

                @Override
                public String name() {
                    return "none";
                }
            };
        }
    }

    /** Pure mapping math, exposed for probes: normalized → SendInput absolute (x, y). */
    public static int[] windowsAbsolute(int nx, int ny, Rectangle physical, Rectangle virtualDesk) {
        int px = WindowsSendInputInjector.toPhysical(nx, physical.x, physical.width);
        int py = WindowsSendInputInjector.toPhysical(ny, physical.y, physical.height);
        return new int[]{px, py,
                WindowsSendInputInjector.toAbsolute(px, virtualDesk.x, virtualDesk.width),
                WindowsSendInputInjector.toAbsolute(py, virtualDesk.y, virtualDesk.height)};
    }

    /** Pure mapping math, exposed for probes: normalized → Robot logical pixel. */
    public static int robotLogical(int n, int origin, int size) {
        return RobotInjector.toLogical(n, origin, size);
    }
}
