package com.srd.server.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class RecorderTest {
    @Test
    void measuredFpsIsFramesOverElapsedSeconds() {
        assertEquals(10.0, Recorder.measuredFps(100, 10_000_000_000L), 1e-9);
        assertEquals(7.5, Recorder.measuredFps(75, 10_000_000_000L), 1e-9);
        assertEquals(Recorder.TARGET_FPS, Recorder.measuredFps(0, 10_000_000_000L), 1e-9);
        assertEquals(Recorder.TARGET_FPS, Recorder.measuredFps(1, 50_000_000L), 1e-9); // too short to measure
        assertEquals(0.5, Recorder.measuredFps(1, 100_000_000_000L), 1e-9);            // clamped low
        assertEquals(60.0, Recorder.measuredFps(1000, 1_000_000_000L), 1e-9);          // clamped high
    }

    @Test
    void silencePaddingOnlyForRealGaps() {
        int bps = Recorder.AUDIO_BYTES_PER_SEC; // 192000
        // 20 ms packet arriving at t=1.0 s with 0.98 s already written: in sync
        assertEquals(0, Recorder.silenceBefore(bps * 98L / 100, 0, 1_000_000_000L, 3840));
        // nothing written yet but the packet arrives 2 s in: pad 2 s minus the packet, 4-byte aligned
        long pad = Recorder.silenceBefore(0, 0, 2_000_000_000L, 3840);
        assertEquals(2L * bps - 3840, pad);
        assertEquals(0, pad % 4);
        // ahead of wall clock (burst): never negative
        assertEquals(0, Recorder.silenceBefore(10L * bps, 0, 1_000_000_000L, 3840));
    }

    @Test
    void ffmpegCommandUsesMeasuredRateAndPcmFormat() {
        File frames = new File("rec", "frames_1");
        File out = new File("rec", "a_b.mp4");
        List<String> cmd = FfmpegEncoder.buildCommand("ffmpeg.exe", 9.876, frames,
                Arrays.asList(new File("rec", "remote.raw"), new File("rec", "mic.raw")), out);
        String s = String.join(" ", cmd);
        assertEquals("ffmpeg.exe", cmd.get(0));
        assertTrue(s.contains("-framerate 9.876"), s);
        assertTrue(s.contains(frames.getAbsolutePath() + File.separator + "frame_%06d.jpg"), s);
        assertEquals(2, Collections.frequency(cmd, "s16le"));
        assertTrue(s.contains("-f s16le -ar 48000 -ac 2 -i"), s);
        assertTrue(s.contains("amix=inputs=2"), s);
        assertTrue(s.endsWith(out.getAbsolutePath()), s);

        List<String> videoOnly = FfmpegEncoder.buildCommand("ffmpeg", 10, frames, null, new File("x.avi"));
        assertFalse(videoOnly.contains("s16le"));
        assertFalse(videoOnly.contains("-c:a"));
        assertTrue(String.join(" ", videoOnly).contains("-framerate 10.000"));

        List<String> oneTrack = FfmpegEncoder.buildCommand("ffmpeg", 10, frames,
                Collections.singletonList(new File("a.raw")), new File("x.mp4"));
        assertTrue(String.join(" ", oneTrack).contains("-map 0:v -map 1:a"));
    }

    @Test
    void fpsFormattingIgnoresLocale() {
        Locale def = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("12.500", FfmpegEncoder.formatFps(12.5));
        } finally {
            Locale.setDefault(def);
        }
    }
}
