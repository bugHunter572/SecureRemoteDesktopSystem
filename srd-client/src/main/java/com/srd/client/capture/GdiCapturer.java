package com.srd.client.capture;

import com.srd.common.video.FrameBuffer;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;
import java.awt.Rectangle;
import java.io.IOException;

/**
 * GDI BitBlt of the physical region into a reused top-down 32-bit DIB section. Works everywhere
 * DXGI doesn't (RDP sessions, rotated outputs, some virtual display drivers), at higher CPU cost.
 * Reports {@link Grab#UNKNOWN}: the caller diffs.
 */
final class GdiCapturer implements ScreenCapturer {
    private static final int SRCCOPY = 0x00CC0020;
    private static final int CAPTUREBLT = 0x40000000;
    private static final int SM_XVIRTUALSCREEN = 76, SM_YVIRTUALSCREEN = 77, SM_CXVIRTUALSCREEN = 78, SM_CYVIRTUALSCREEN = 79;

    private final Rectangle region;
    private final Rectangle virtualAtOpen;
    private WinDef.HDC screenDc;
    private WinDef.HDC memDc;
    private WinDef.HBITMAP bitmap;
    private WinNT.HANDLE oldBitmap;
    private Pointer bits;
    private int checkCounter;

    GdiCapturer(ScreenLayout layout, int monitorIndex) throws IOException {
        this.region = layout.physicalRegion(monitorIndex);
        this.virtualAtOpen = virtualScreen();
        boolean ok = false;
        try {
            screenDc = User32.INSTANCE.GetDC(null);
            if (screenDc == null) throw new IOException("GetDC(NULL) failed");
            memDc = GDI32.INSTANCE.CreateCompatibleDC(screenDc);
            if (memDc == null) throw new IOException("CreateCompatibleDC failed");
            WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
            bmi.bmiHeader.biSize = bmi.bmiHeader.size();
            bmi.bmiHeader.biWidth = region.width;
            bmi.bmiHeader.biHeight = -region.height; // top-down
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = WinGDI.BI_RGB;
            PointerByReference ppBits = new PointerByReference();
            bitmap = GDI32.INSTANCE.CreateDIBSection(memDc, bmi, WinGDI.DIB_RGB_COLORS, ppBits, null, 0);
            if (bitmap == null || ppBits.getValue() == null) throw new IOException("CreateDIBSection failed");
            bits = ppBits.getValue();
            oldBitmap = GDI32.INSTANCE.SelectObject(memDc, bitmap);
            ok = true;
        } finally {
            if (!ok) close();
        }
    }

    private static Rectangle virtualScreen() {
        User32 u = User32.INSTANCE;
        return new Rectangle(u.GetSystemMetrics(SM_XVIRTUALSCREEN), u.GetSystemMetrics(SM_YVIRTUALSCREEN),
                u.GetSystemMetrics(SM_CXVIRTUALSCREEN), u.GetSystemMetrics(SM_CYVIRTUALSCREEN));
    }

    @Override
    public int width() {
        return region.width;
    }

    @Override
    public int height() {
        return region.height;
    }

    @Override
    public String name() {
        return "GDI";
    }

    @Override
    public Grab grab(FrameBuffer fb, int timeoutMs) throws IOException {
        if (fb.width != region.width || fb.height != region.height) {
            throw new IllegalArgumentException("fb " + fb.width + "x" + fb.height + " != region " + region);
        }
        // Cheap layout-change detection (DXGI gets ACCESS_LOST; GDI has to look).
        if ((++checkCounter & 15) == 0 && !virtualScreen().equals(virtualAtOpen)) {
            throw new CaptureLostException("virtual desktop changed");
        }
        if (!GDI32.INSTANCE.BitBlt(memDc, 0, 0, region.width, region.height, screenDc, region.x, region.y, SRCCOPY | CAPTUREBLT)) {
            // Secure desktop / lock screen / session switch.
            throw new CaptureLostException("BitBlt failed (error " + com.sun.jna.Native.getLastError() + ")");
        }
        int[] px = fb.pixels;
        bits.read(0, px, 0, region.width * region.height);
        for (int i = 0; i < px.length; i++) px[i] &= 0x00FFFFFF;
        return Grab.UNKNOWN;
    }

    @Override
    public void close() {
        try {
            if (memDc != null && oldBitmap != null) GDI32.INSTANCE.SelectObject(memDc, oldBitmap);
            if (bitmap != null) GDI32.INSTANCE.DeleteObject(bitmap);
            if (memDc != null) GDI32.INSTANCE.DeleteDC(memDc);
            if (screenDc != null) User32.INSTANCE.ReleaseDC(null, screenDc);
        } catch (Throwable ignored) {
        }
        oldBitmap = null;
        bitmap = null;
        memDc = null;
        screenDc = null;
        bits = null;
    }
}
