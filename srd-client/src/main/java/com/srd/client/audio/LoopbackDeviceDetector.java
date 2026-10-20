package com.srd.client.audio;

import com.srd.common.util.Log;
import com.srd.common.util.Os;
import java.util.Locale;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/**
 * Finds virtual loopback capture devices (BlackHole, Rogue Amoeba Loopback, Soundflower,
 * VB-Cable) and recognises loopback-like device names.
 *
 * <p>macOS has no OS-level loopback capture, so system audio is only available when such a
 * driver is installed and the output is routed through it. On Windows, WASAPI loopback is
 * normally used instead; the Windows names here ("Stereo Mix") are only a fallback for when
 * WASAPI cannot load, and they are excluded from microphone selection so system audio is not
 * captured twice.
 */
public final class LoopbackDeviceDetector {
    /** Virtual loopback drivers that can serve as the system-audio source. */
    private static final String[] SYSTEM_DEVICES = {"blackhole", "loopback", "soundflower", "vb-cable", "cable output"};
    /** Windows-only fallback names (used only if WASAPI loopback is unavailable). */
    private static final String[] WINDOWS_FALLBACK = {"stereo mix", "what u hear", "wave out mix"};
    /** Anything that captures output rather than a microphone. Never chosen as the mic. */
    private static final String[] LOOPBACK_LIKE = {
            "stereo mix", "what u hear", "wave out", "loopback", "blackhole", "soundflower",
            "vb-cable", "cable output", "vb-audio", "voicemeeter", "monitor of", ".monitor", "wasapi loopback"};

    private LoopbackDeviceDetector() {
    }

    /** True if a mixer name/description looks like an output-loopback device rather than a mic. */
    public static boolean isLoopbackName(String nameAndDescription) {
        return containsAny(nameAndDescription, LOOPBACK_LIKE);
    }

    /** First capture mixer that is a virtual loopback device, or null. */
    public static Mixer.Info findSystemCaptureMixer() {
        try {
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                String text = info.getName() + " " + info.getDescription();
                boolean match = containsAny(text, SYSTEM_DEVICES) || (Os.WINDOWS && containsAny(text, WINDOWS_FALLBACK));
                if (!match) continue;
                if (!hasCaptureLine(info)) continue;
                return info;
            }
        } catch (Throwable t) {
            Log.warn("audio: enumerating loopback devices failed: " + t);
        }
        return null;
    }

    /** A Java Sound source on the detected loopback device, or null if there is none. */
    public static AudioSource createSystemSource() {
        Mixer.Info info = findSystemCaptureMixer();
        if (info == null) return null;
        Log.info("audio: using loopback capture device '" + info.getName() + "' for system audio");
        return new JavaSoundMicSource(info.getName(), AudioSource.Kind.SYSTEM);
    }

    static boolean hasCaptureLine(Mixer.Info info) {
        try {
            Mixer m = AudioSystem.getMixer(info);
            return m.getTargetLineInfo(new Line.Info(TargetDataLine.class)).length > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean containsAny(String text, String[] needles) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.ROOT);
        for (String n : needles) {
            if (t.contains(n)) return true;
        }
        return false;
    }
}
