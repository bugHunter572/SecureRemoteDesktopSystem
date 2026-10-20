package com.srd.common.ui;

import com.srd.common.util.Log;
import com.srd.common.util.Os;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.imageio.ImageIO;

/** Saves viewer screenshots as PNG and copies them to the clipboard. */
public final class Screenshots {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss_SSS");

    private Screenshots() {
    }

    /** The user's Downloads folder (honours Windows known-folder redirection); falls back to the home dir. */
    public static File downloadsDir() {
        if (Os.WINDOWS) {
            // {374DE290-123F-4565-9164-39C4925E467B} = Downloads known folder.
            String p = Os.tryNative("downloads folder", () -> com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(
                    com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders",
                    "{374DE290-123F-4565-9164-39C4925E467B}"), null);
            if (p != null && !p.trim().isEmpty()) {
                File d = new File(p.trim());
                if (d.isDirectory() || d.mkdirs()) return d;
            }
        }
        File d = new File(System.getProperty("user.home"), "Downloads");
        if (d.isDirectory() || d.mkdirs()) return d;
        return new File(System.getProperty("user.home"));
    }

    /** Characters not allowed in file names on Windows/macOS replaced with '-'. */
    public static String sanitize(String s) {
        if (s == null || s.trim().isEmpty()) return "client";
        return s.trim().replaceAll("[\\\\/:*?\"<>|]+", "-").replaceAll("\\s+", "-");
    }

    /**
     * Saves {@code img} to {@code <Downloads>/SecureRemoteDesktop/<name>_<time>.png} and copies it
     * to the system clipboard.
     *
     * @param name file-name stem, e.g. {@code 2026-10-14_192.168.1.5_OFFICE-PC}
     * @return the saved file, or null on failure
     */
    public static File saveAndCopy(BufferedImage img, String name) {
        File file = save(img, name);
        copyToClipboard(img);
        return file;
    }

    /** Writes the PNG on the calling thread; returns the file or null on failure. */
    public static File save(BufferedImage img, String name) {
        try {
            File dir = new File(downloadsDir(), "SecureRemoteDesktop");
            dir.mkdirs();
            File out = new File(dir, sanitize(name) + "_" + LocalDateTime.now().format(TIME) + ".png");
            ImageIO.write(img, "png", out);
            Log.info("Saved screenshot " + out.getAbsolutePath());
            return out;
        } catch (Exception e) {
            Log.warn("Screenshot save failed: " + e);
            return null;
        }
    }

    /** Puts the image on the system clipboard (so it can be pasted into chat, docs, etc.). */
    public static void copyToClipboard(BufferedImage img) {
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            cb.setContents(new ImageTransferable(img), null);
        } catch (Exception e) {
            Log.warn("Screenshot clipboard copy failed: " + e);
        }
    }

    /** A date+IP+name stem, e.g. {@code 2026-10-14_192.168.1.5_OFFICE-PC}. */
    public static String stem(String ipOrAddr, String displayName) {
        String date = LocalDate.now().toString();
        String ip = sanitize(ipOrAddr);
        String nm = sanitize(displayName);
        return nm.equalsIgnoreCase(ip) ? date + "_" + ip : date + "_" + ip + "_" + nm;
    }

    private static final class ImageTransferable implements Transferable {
        private final Image image;

        ImageTransferable(Image image) {
            this.image = image;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] {DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!DataFlavor.imageFlavor.equals(flavor)) throw new UnsupportedFlavorException(flavor);
            return image;
        }
    }
}
