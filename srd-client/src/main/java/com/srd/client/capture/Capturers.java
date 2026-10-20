package com.srd.client.capture;

import com.srd.common.util.Log;
import com.srd.common.util.Os;
import java.awt.Rectangle;
import java.io.IOException;

/** Picks the best available {@link ScreenCapturer}: DXGI → GDI → Robot on Windows, Robot elsewhere. */
public final class Capturers {
    private Capturers() {
    }

    /**
     * @param monitorIndex index into {@code layout.monitors}, or -1 for the whole desktop
     * @throws IOException only if even Robot capture is unavailable (e.g. headless)
     */
    public static ScreenCapturer open(ScreenLayout layout, int monitorIndex) throws IOException {
        StringBuilder why = new StringBuilder();
        if (Os.WINDOWS && !Boolean.getBoolean("srd.capture.noDxgi")) {
            ScreenCapturer c = attempt("DXGI", layout, monitorIndex, why);
            if (c != null) return chosen(c, layout, monitorIndex, why);
        }
        if (Os.WINDOWS && !Boolean.getBoolean("srd.capture.noGdi")) {
            ScreenCapturer c = attempt("GDI", layout, monitorIndex, why);
            if (c != null) return chosen(c, layout, monitorIndex, why);
        }
        ScreenCapturer c = attempt("Robot", layout, monitorIndex, why);
        if (c != null) return chosen(c, layout, monitorIndex, why);
        throw new IOException("No screen capturer available:" + why);
    }

    /** Opens one specific implementation ("DXGI", "GDI", "Robot"); throws if it can't. */
    public static ScreenCapturer openType(String type, ScreenLayout layout, int monitorIndex) throws IOException {
        try {
            switch (type.toUpperCase()) {
                case "DXGI":
                    return new DxgiCapturer(layout, monitorIndex);
                case "GDI":
                    return new GdiCapturer(layout, monitorIndex);
                case "ROBOT":
                    return new RobotCapturer(layout, monitorIndex);
                default:
                    throw new IOException("unknown capturer " + type);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) { // LinkageError on non-Windows, JNA errors, AWT headless, …
            throw new IOException(type + ": " + t, t);
        }
    }

    public static Rectangle physicalRegion(ScreenLayout layout, int monitorIndex) {
        return layout.physicalRegion(monitorIndex);
    }

    public static Rectangle logicalRegion(ScreenLayout layout, int monitorIndex) {
        return layout.logicalRegion(monitorIndex);
    }

    private static ScreenCapturer attempt(String type, ScreenLayout layout, int monitorIndex, StringBuilder why) {
        try {
            return openType(type, layout, monitorIndex);
        } catch (IOException e) {
            why.append(" [").append(type).append(": ").append(e.getMessage()).append(']');
            return null;
        }
    }

    private static ScreenCapturer chosen(ScreenCapturer c, ScreenLayout layout, int monitorIndex, StringBuilder why) {
        Log.info("Screen capture: " + c.name() + " " + c.width() + "x" + c.height()
                + " (monitor " + (monitorIndex < 0 ? "all" : String.valueOf(monitorIndex)) + ")"
                + (why.length() > 0 ? "; fell back because" + why : ""));
        return c;
    }
}
