package com.srd.common.util;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Operating-system detection plus guards for native (JNA) calls.
 *
 * <p>Native calls on the wrong OS fail with {@link LinkageError} ({@code UnsatisfiedLinkError},
 * {@code NoClassDefFoundError}), which is an {@link Error}, not an {@link Exception} — a plain
 * {@code catch (Exception)} lets it escape and kill the calling thread. Wrap every optional native
 * feature in {@link #tryNative} so it degrades instead of crashing on macOS/Linux.
 */
public final class Os {
    private static final String NAME = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    public static final boolean WINDOWS = NAME.startsWith("windows");
    public static final boolean MAC = NAME.startsWith("mac") || NAME.startsWith("darwin");
    public static final boolean LINUX = NAME.contains("linux") || NAME.contains("nux");

    private Os() {
    }

    public static String describe() {
        return System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "")
                + " (" + System.getProperty("os.arch", "") + ")";
    }

    /** Short tag sent in the handshake: WIN / MAC / LINUX / OTHER. */
    public static String tag() {
        return WINDOWS ? "WIN" : MAC ? "MAC" : LINUX ? "LINUX" : "OTHER";
    }

    /** Runs a native action; any Throwable (including LinkageError) is logged and swallowed. */
    public static boolean tryNative(String what, Runnable action) {
        try {
            action.run();
            return true;
        } catch (Throwable t) {
            Log.warn("Native call failed (" + what + "): " + t);
            return false;
        }
    }

    /** Native call with a result; returns {@code fallback} on any Throwable. */
    public static <T> T tryNative(String what, Supplier<T> action, T fallback) {
        try {
            return action.get();
        } catch (Throwable t) {
            Log.warn("Native call failed (" + what + "): " + t);
            return fallback;
        }
    }
}
