package com.srd.client.audio;

import com.srd.common.util.Log;
import java.util.Locale;
import java.util.Properties;

/**
 * Audio capture settings. Gains apply live on the next 20 ms tick; enabling or disabling a source
 * while the engine runs restarts the engine's sources (subscribers keep receiving packets).
 *
 * <p>Properties keys (client-config.properties): {@code audio.mic}, {@code audio.system},
 * {@code audio.micGain}, {@code audio.systemGain}.
 */
public final class AudioConfig {
    public static final float MAX_GAIN = 4f;

    private static volatile boolean micEnabled = true;
    private static volatile boolean systemAudioEnabled = true;
    private static volatile float micGain = 1f;
    private static volatile float systemGain = 1f;

    private AudioConfig() {
    }

    public static boolean isMicEnabled() {
        return micEnabled;
    }

    public static boolean isSystemAudioEnabled() {
        return systemAudioEnabled;
    }

    public static float getMicGain() {
        return micGain;
    }

    public static float getSystemGain() {
        return systemGain;
    }

    public static void setMicEnabled(boolean on) {
        if (micEnabled == on) return;
        micEnabled = on;
        AudioEngine.get().restartSources();
    }

    public static void setSystemAudioEnabled(boolean on) {
        if (systemAudioEnabled == on) return;
        systemAudioEnabled = on;
        AudioEngine.get().restartSources();
    }

    /** Linear gain, clamped to 0..{@value #MAX_GAIN}. Applies on the next tick. */
    public static void setMicGain(float g) {
        micGain = clampGain(g);
    }

    /** Linear gain, clamped to 0..{@value #MAX_GAIN}. Applies on the next tick. */
    public static void setSystemGain(float g) {
        systemGain = clampGain(g);
    }

    /** Loads all four settings (missing/invalid keys keep their current value). */
    public static void load(Properties p) {
        if (p == null) return;
        boolean mic = parseBool(p.getProperty("audio.mic"), micEnabled);
        boolean sys = parseBool(p.getProperty("audio.system"), systemAudioEnabled);
        micGain = clampGain(parseFloat(p.getProperty("audio.micGain"), micGain));
        systemGain = clampGain(parseFloat(p.getProperty("audio.systemGain"), systemGain));
        boolean changed = mic != micEnabled || sys != systemAudioEnabled;
        micEnabled = mic;
        systemAudioEnabled = sys;
        Log.info("audio config: mic=" + mic + " system=" + sys + " micGain=" + micGain
                + " systemGain=" + systemGain);
        if (changed) AudioEngine.get().restartSources();
    }

    static float clampGain(float g) {
        if (Float.isNaN(g)) return 1f;
        return Math.max(0f, Math.min(MAX_GAIN, g));
    }

    static boolean parseBool(String v, boolean def) {
        if (v == null) return def;
        switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "true": case "yes": case "on": case "1":
                return true;
            case "false": case "no": case "off": case "0":
                return false;
            default:
                return def;
        }
    }

    static float parseFloat(String v, float def) {
        if (v == null) return def;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
