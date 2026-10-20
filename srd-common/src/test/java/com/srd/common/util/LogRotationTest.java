package com.srd.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class LogRotationTest {
    private static void deleteAll(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }

    private static void writeLines(Log.RotatingStream s, int count, String text) throws IOException {
        byte[] line = (text + "\n").getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < count; i++) for (byte b : line) s.write(b);
        s.flush();
    }

    @Test
    void rotatesAtTheLimitAndKeepsOnlyN() throws IOException {
        File dir = Files.createTempDirectory("srdlog").toFile();
        try {
            Log.RotatingStream s = new Log.RotatingStream(dir, "t", 1000, 3);
            writeLines(s, 400, "0123456789012345678901234567890123456789"); // ~16 KB => ~16 rotations
            s.close();
            assertTrue(new File(dir, "t.log").isFile());
            assertTrue(new File(dir, "t.1.log").isFile());
            assertTrue(new File(dir, "t.2.log").isFile());
            assertTrue(new File(dir, "t.3.log").isFile());
            assertFalse(new File(dir, "t.4.log").exists(), "never more than 'keep' old files");
            // The size limit is only checked at line ends, so a file can overshoot by at most one line.
            for (File f : dir.listFiles()) assertTrue(f.length() <= 1000 + 41, f.getName() + " is " + f.length());
        } finally {
            deleteAll(dir);
        }
    }

    @Test
    void oversizedFileFromAPreviousRunRotatesOnOpen() throws IOException {
        File dir = Files.createTempDirectory("srdlog").toFile();
        try {
            Files.write(new File(dir, "t.log").toPath(), new byte[5000]);
            Log.RotatingStream s = new Log.RotatingStream(dir, "t", 1000, 2);
            writeLines(s, 1, "fresh");
            s.close();
            assertEquals(5000, new File(dir, "t.1.log").length());
            assertTrue(new File(dir, "t.log").length() < 100);
        } finally {
            deleteAll(dir);
        }
    }

    @Test
    void keepsAppendingBelowTheLimit() throws IOException {
        File dir = Files.createTempDirectory("srdlog").toFile();
        try {
            Log.RotatingStream s = new Log.RotatingStream(dir, "t", 10_000, 2);
            writeLines(s, 10, "hello");
            s.close();
            s = new Log.RotatingStream(dir, "t", 10_000, 2);
            writeLines(s, 10, "again");
            s.close();
            assertEquals(120, new File(dir, "t.log").length());
            assertFalse(new File(dir, "t.1.log").exists());
        } finally {
            deleteAll(dir);
        }
    }
}
