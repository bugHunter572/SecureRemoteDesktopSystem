package com.srd.server.recording;

import com.srd.common.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Builds and runs the ffmpeg command that turns recorded JPEG frames (+ raw PCM) into a video. */
public final class FfmpegEncoder {
    /** Raw PCM input format of every audio track: 48 kHz stereo s16le (AudioPacket's wire format). */
    static final List<String> PCM_INPUT = Arrays.asList("-f", "s16le", "-ar", "48000", "-ac", "2");

    private FfmpegEncoder() {
    }

    /**
     * Finds ffmpeg: {@code <user.dir>/ffmpeg.exe} (the exe's working dir is bin/), then
     * {@code <user.dir>/bin/ffmpeg.exe}, then "ffmpeg" on the PATH.
     */
    public static String locate() {
        String exe = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "ffmpeg.exe" : "ffmpeg";
        String base = System.getProperty("user.dir");
        File f = new File(base, exe);
        if (f.isFile()) return f.getAbsolutePath();
        f = new File(base + File.separator + "bin", exe);
        if (f.isFile()) return f.getAbsolutePath();
        return "ffmpeg";
    }

    /** "12.345" — always '.' as decimal separator regardless of the system locale. */
    static String formatFps(double fps) {
        return String.format(Locale.ROOT, "%.3f", fps);
    }

    /**
     * @param audioTracks 0, 1 or 2 raw s16le 48 kHz stereo files (null entries are skipped)
     */
    public static List<String> buildCommand(String ffmpeg, double fps, File framesDir, List<File> audioTracks, File output) {
        List<File> audio = new ArrayList<>();
        if (audioTracks != null) for (File a : audioTracks) if (a != null) audio.add(a);
        List<String> cmd = new ArrayList<>(Arrays.asList(ffmpeg, "-y", "-framerate", formatFps(fps),
                "-i", framesDir.getAbsolutePath() + File.separator + "frame_%06d.jpg"));
        for (File a : audio) {
            cmd.addAll(PCM_INPUT);
            cmd.add("-i");
            cmd.add(a.getAbsolutePath());
        }
        if (audio.size() >= 2) {
            cmd.addAll(Arrays.asList("-filter_complex", "[1:a][2:a]amix=inputs=2:duration=first[a]", "-map", "0:v", "-map", "[a]"));
        } else if (audio.size() == 1) {
            cmd.addAll(Arrays.asList("-map", "0:v", "-map", "1:a"));
        }
        // libx264 + yuv420p needs even dimensions.
        cmd.addAll(Arrays.asList("-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2", "-c:v", "libx264", "-pix_fmt", "yuv420p"));
        if (!audio.isEmpty()) cmd.addAll(Arrays.asList("-c:a", "aac", "-shortest"));
        cmd.add(output.getAbsolutePath());
        return cmd;
    }

    /** Runs ffmpeg, logging its output; returns the exit code (or -1 if it could not start). */
    public static int run(List<String> cmd) {
        Log.info("Executing FFmpeg command: " + String.join(" ", cmd));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Log.debug("ffmpeg: " + line);
                    tail.addLast(line);
                    if (tail.size() > 25) tail.removeFirst();
                }
            }
            int code = p.waitFor();
            if (code != 0) Log.warn("FFmpeg failed with exit code " + code + ":\n" + String.join("\n", tail));
            return code;
        } catch (Exception e) {
            Log.warn("FFmpeg could not be run (" + e.getMessage() + "). Run manually: " + String.join(" ", cmd));
            return -1;
        }
    }
}
