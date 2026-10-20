package com.srd.client.capture;

import com.srd.common.util.Log;
import com.srd.common.video.FrameBuffer;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.awt.Rectangle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * DXGI Desktop Duplication (Windows 8+) via raw COM vtable calls through JNA.
 *
 * <p>One duplication per output that intersects the requested physical region. Each output gets
 * a D3D11 device created on <em>its own</em> adapter (hybrid-GPU laptops and multi-GPU desktops
 * would otherwise fail DuplicateOutput with DXGI_ERROR_UNSUPPORTED). Every changed area is
 * copied GPU→GPU into a persistent CPU-readable staging texture, mapped, and only the dirty
 * rectangles are converted into {@link FrameBuffer#pixels}.
 *
 * <p>Vtable indices (declaration order in dxgi.h / dxgi1_2.h / d3d11.h):
 * <pre>
 * IUnknown            0 QueryInterface, 1 AddRef, 2 Release
 * IDXGIObject         3 SetPrivateData, 4 SetPrivateDataInterface, 5 GetPrivateData, 6 GetParent
 * IDXGIFactory        7 EnumAdapters
 * IDXGIAdapter        7 EnumOutputs, 8 GetDesc, 9 CheckInterfaceSupport
 * IDXGIOutput         7 GetDesc … 18 GetFrameStatistics
 * IDXGIOutput1        19 GetDisplayModeList1, 20 FindClosestMatchingMode1, 21 GetDisplaySurfaceData1, 22 DuplicateOutput
 * IDXGIOutputDuplication 7 GetDesc, 8 AcquireNextFrame, 9 GetFrameDirtyRects, 10 GetFrameMoveRects,
 *                     11 GetFramePointerShape, 12 MapDesktopSurface, 13 UnMapDesktopSurface, 14 ReleaseFrame
 * ID3D11Device        5 CreateTexture2D, 39 GetDeviceRemovedReason, 40 GetImmediateContext
 * ID3D11DeviceContext (ID3D11DeviceChild 3-6) 14 Map, 15 Unmap, 46 CopySubresourceRegion, 47 CopyResource
 * </pre>
 */
final class DxgiCapturer implements ScreenCapturer {
    // HRESULTs
    private static final int DXGI_ERROR_NOT_FOUND = 0x887A0002;
    private static final int DXGI_ERROR_MORE_DATA = 0x887A0003;
    private static final int DXGI_ERROR_WAIT_TIMEOUT = 0x887A0027;

    private static final int D3D_DRIVER_TYPE_UNKNOWN = 0;
    private static final int D3D11_CREATE_DEVICE_BGRA_SUPPORT = 0x20;
    private static final int D3D11_SDK_VERSION = 7;
    private static final int DXGI_FORMAT_B8G8R8A8_UNORM = 87;
    private static final int D3D11_USAGE_STAGING = 3;
    private static final int D3D11_CPU_ACCESS_READ = 0x20000;
    private static final int D3D11_MAP_READ = 1;
    private static final int DXGI_MODE_ROTATION_UNSPECIFIED = 0;
    private static final int DXGI_MODE_ROTATION_IDENTITY = 1;

    /** Above this many rects per frame, one full copy is cheaper than many small ones. */
    private static final int MAX_RECTS = 128;
    private static final int FIRST_FRAME_TIMEOUT_MS = 1000;

    private static final int PS = Native.POINTER_SIZE;
    private static final int CONV = Function.ALT_CONVENTION; // stdcall on 32-bit, ignored on x64

    private static final Memory IID_IDXGIFactory1 = guid("770aae78-f26f-4dba-a829-253c83d1b387");
    private static final Memory IID_IDXGIOutput1 = guid("00cddea8-939b-4b83-a340-a685226666cc");
    private static final Memory IID_ID3D11Texture2D = guid("6f15aaf2-d208-4e89-9ab4-489535d34f9c");

    /**
     * Strong, process-lifetime references. A NativeLibrary that becomes unreachable is disposed
     * by JNA's cleaner, which calls FreeLibrary. Unloading d3d11.dll while devices (and their
     * driver worker threads) are alive crashed the JVM with an access violation in
     * "d3d11.dll_unloaded".
     */
    private static final class Libs {
        static final NativeLibrary DXGI = NativeLibrary.getInstance("dxgi");
        static final NativeLibrary D3D11 = NativeLibrary.getInstance("d3d11");
    }

    /** One D3D11 device per adapter. */
    private static final class Dev {
        final Pointer adapterKey;
        Pointer device;
        Pointer context;

        Dev(Pointer adapterKey) {
            this.adapterKey = adapterKey;
        }
    }

    private static final class Out {
        String name;
        Dev dev;
        Pointer dupl;
        Pointer staging;
        /** Output bounds on the virtual desktop (physical px). */
        Rectangle desk;
        /** Part of this output inside the capture region, output-local coordinates. */
        Rectangle partLocal;
        int texW, texH;
        boolean needFull = true;
    }

    private final Rectangle region;
    private final List<Dev> devs = new ArrayList<>();
    private final List<Out> outs = new ArrayList<>();
    private final Memory frameInfo = new Memory(48);
    private final Memory mapped = new Memory(16);
    private final Memory box = new Memory(24);
    private Memory rectBuf = new Memory(16 * 64);
    private final IntByReference sizeRequired = new IntByReference();

    DxgiCapturer(ScreenLayout layout, int monitorIndex) throws IOException {
        this.region = layout.physicalRegion(monitorIndex);
        boolean ok = false;
        try {
            open(layout, monitorIndex);
            ok = true;
        } finally {
            if (!ok) close();
        }
    }

    private void open(ScreenLayout layout, int monitorIndex) throws IOException {
        NativeLibrary dxgi = Libs.DXGI;
        NativeLibrary d3d11 = Libs.D3D11;
        PointerByReference pp = new PointerByReference();
        int hr = dxgi.getFunction("CreateDXGIFactory1", CONV).invokeInt(new Object[]{IID_IDXGIFactory1, pp});
        check(hr, "CreateDXGIFactory1");
        Pointer factory = pp.getValue();
        try {
            for (int a = 0; ; a++) {
                pp.setValue(null);
                hr = call(factory, 7, a, pp); // IDXGIFactory::EnumAdapters
                if (hr == DXGI_ERROR_NOT_FOUND) break;
                check(hr, "EnumAdapters");
                Pointer adapter = pp.getValue();
                try {
                    for (int o = 0; ; o++) {
                        pp.setValue(null);
                        hr = call(adapter, 7, o, pp); // IDXGIAdapter::EnumOutputs
                        if (hr == DXGI_ERROR_NOT_FOUND) break;
                        check(hr, "EnumOutputs");
                        Pointer output = pp.getValue();
                        try {
                            addOutput(d3d11, adapter, output);
                        } finally {
                            release(output);
                        }
                    }
                } finally {
                    release(adapter);
                }
            }
        } finally {
            release(factory);
        }

        // Every layout monitor inside the region must be covered, else GDI does it better.
        for (ScreenLayout.Monitor m : layout.monitors) {
            if (monitorIndex >= 0 && m.index != monitorIndex) continue;
            if (!m.physical.intersects(region)) continue;
            boolean covered = false;
            for (Out out : outs) if (out.desk.equals(m.physical)) covered = true;
            if (!covered) throw new IOException("no DXGI duplication for " + m);
        }
        if (outs.isEmpty()) throw new IOException("no DXGI output intersects " + region);
        StringBuilder sb = new StringBuilder();
        for (Out o : outs) sb.append(' ').append(o.name).append(o.desk);
        Log.info("DXGI duplication open for " + region + ":" + sb);
    }

    private void addOutput(NativeLibrary d3d11, Pointer adapter, Pointer output) throws IOException {
        Memory desc = new Memory(PS == 8 ? 96 : 92);
        check(call(output, 7, desc), "IDXGIOutput::GetDesc");
        String name = desc.getWideString(0);
        Rectangle desk = new Rectangle(desc.getInt(64), desc.getInt(68),
                desc.getInt(72) - desc.getInt(64), desc.getInt(76) - desc.getInt(68));
        boolean attached = desc.getInt(80) != 0;
        int rotation = desc.getInt(84);
        if (!attached || !desk.intersects(region)) return;
        if (rotation != DXGI_MODE_ROTATION_IDENTITY && rotation != DXGI_MODE_ROTATION_UNSPECIFIED) {
            throw new IOException(name + " is rotated (" + rotation + "); DXGI path supports identity only");
        }

        Out out = new Out();
        out.name = name;
        out.desk = desk;
        Rectangle part = desk.intersection(region);
        out.partLocal = new Rectangle(part.x - desk.x, part.y - desk.y, part.width, part.height);
        out.dev = deviceFor(d3d11, adapter);
        outs.add(out); // added before the calls below so close() releases partial state

        PointerByReference pp = new PointerByReference();
        check(call(output, 0, IID_IDXGIOutput1, pp), "QueryInterface(IDXGIOutput1)");
        Pointer output1 = pp.getValue();
        try {
            pp.setValue(null);
            check(call(output1, 22, out.dev.device, pp), "DuplicateOutput(" + name + ")");
            out.dupl = pp.getValue();
        } finally {
            release(output1);
        }

        // DXGI_OUTDUPL_DESC: DXGI_MODE_DESC{Width,Height,RefreshRate{N,D},Format,ScanlineOrdering,Scaling}(28)
        //                    Rotation@28, DesktopImageInSystemMemory@32 (36 bytes)
        Memory dd = new Memory(36);
        dd.clear();
        callVoid(out.dupl, 7, dd);
        out.texW = dd.getInt(0);
        out.texH = dd.getInt(4);
        int fmt = dd.getInt(16);
        int drot = dd.getInt(28);
        if (drot != DXGI_MODE_ROTATION_IDENTITY && drot != DXGI_MODE_ROTATION_UNSPECIFIED) {
            throw new IOException(name + " duplication is rotated (" + drot + ")");
        }
        if (out.texW != desk.width || out.texH != desk.height) {
            throw new IOException(name + " mode " + out.texW + "x" + out.texH + " != desktop " + desk);
        }
        if (fmt != DXGI_FORMAT_B8G8R8A8_UNORM) throw new IOException(name + " unexpected format " + fmt);

        // D3D11_TEXTURE2D_DESC (11 UINTs = 44 bytes)
        Memory td = new Memory(44);
        td.clear();
        td.setInt(0, out.texW);
        td.setInt(4, out.texH);
        td.setInt(8, 1);  // MipLevels
        td.setInt(12, 1); // ArraySize
        td.setInt(16, DXGI_FORMAT_B8G8R8A8_UNORM);
        td.setInt(20, 1); // SampleDesc.Count
        td.setInt(24, 0); // SampleDesc.Quality
        td.setInt(28, D3D11_USAGE_STAGING);
        td.setInt(32, 0); // BindFlags
        td.setInt(36, D3D11_CPU_ACCESS_READ);
        td.setInt(40, 0); // MiscFlags
        pp.setValue(null);
        check(call(out.dev.device, 5, td, null, pp), "CreateTexture2D(staging)");
        out.staging = pp.getValue();
    }

    private Dev deviceFor(NativeLibrary d3d11, Pointer adapter) throws IOException {
        // Same adapter object ⇒ same COM pointer only within one enumeration, which is all we need.
        for (Dev d : devs) if (d.adapterKey.equals(adapter)) return d;
        Dev d = new Dev(adapter);
        PointerByReference ppDev = new PointerByReference();
        PointerByReference ppCtx = new PointerByReference();
        IntByReference level = new IntByReference();
        int hr = d3d11.getFunction("D3D11CreateDevice", CONV).invokeInt(new Object[]{
                adapter, D3D_DRIVER_TYPE_UNKNOWN, null, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                null, 0, D3D11_SDK_VERSION, ppDev, level, ppCtx});
        check(hr, "D3D11CreateDevice");
        d.device = ppDev.getValue();
        d.context = ppCtx.getValue();
        devs.add(d);
        return d;
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
        return "DXGI";
    }

    @Override
    public Grab grab(FrameBuffer fb, int timeoutMs) throws IOException {
        if (fb.width != region.width || fb.height != region.height) {
            throw new IllegalArgumentException("fb " + fb.width + "x" + fb.height + " != region " + region);
        }
        List<Rectangle> dirty = new ArrayList<>();
        boolean anyFull = false;
        for (Out o : outs) anyFull |= o.needFull;

        if (anyFull) {
            // Initial frame: every output must deliver its complete image.
            for (Out o : outs) {
                if (o.needFull) acquire(o, Math.max(timeoutMs, FIRST_FRAME_TIMEOUT_MS), fb, dirty);
                else acquire(o, 0, fb, dirty);
            }
            return dirty.isEmpty() ? Grab.NO_CHANGE : new Grab(true, dirty);
        }

        if (outs.size() == 1) {
            acquire(outs.get(0), Math.max(0, timeoutMs), fb, dirty);
        } else {
            // Several duplications: poll all, then wait in small slices so a change on any
            // output is picked up promptly.
            long deadline = System.nanoTime() + Math.max(0, timeoutMs) * 1_000_000L;
            while (true) {
                for (Out o : outs) acquire(o, 0, fb, dirty);
                if (!dirty.isEmpty()) break;
                long remainMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainMs <= 0) break;
                int slice = (int) Math.max(1, Math.min(remainMs, 4));
                acquire(outs.get(0), slice, fb, dirty); // blocks on the first output
                if (!dirty.isEmpty()) {
                    for (int i = 1; i < outs.size(); i++) acquire(outs.get(i), 0, fb, dirty);
                    break;
                }
            }
        }
        return dirty.isEmpty() ? Grab.NO_CHANGE : new Grab(true, dirty);
    }

    /** Acquires one frame from {@code o}; on an image update copies its dirty rects into fb. */
    private void acquire(Out o, int timeoutMs, FrameBuffer fb, List<Rectangle> dirtyOut) throws IOException {
        PointerByReference ppRes = new PointerByReference();
        frameInfo.clear();
        int hr = call(o.dupl, 8, timeoutMs, frameInfo, ppRes); // AcquireNextFrame
        if (hr == DXGI_ERROR_WAIT_TIMEOUT) return;
        if (hr < 0) throw new CaptureLostException("AcquireNextFrame(" + o.name + ") failed " + hex(hr));
        Pointer res = ppRes.getValue();
        Pointer tex = null;
        try {
            // DXGI_OUTDUPL_FRAME_INFO: LastPresentTime@0 (LARGE_INTEGER), LastMouseUpdateTime@8,
            // AccumulatedFrames@16, RectsCoalesced@20, ProtectedContentMaskedOut@24,
            // PointerPosition{POINT,BOOL}@28, TotalMetadataBufferSize@40, PointerShapeBufferSize@44
            long lastPresent = frameInfo.getLong(0);
            int accumulated = frameInfo.getInt(16);
            int metaSize = frameInfo.getInt(40);
            if (!o.needFull && (lastPresent == 0 || accumulated == 0)) return; // pointer-only update
            if (res == null) return;

            PointerByReference ppTex = new PointerByReference();
            check(call(res, 0, IID_ID3D11Texture2D, ppTex), "QueryInterface(ID3D11Texture2D)");
            tex = ppTex.getValue();

            List<Rectangle> rects = null; // output-local; null = full
            if (!o.needFull && metaSize > 0) rects = frameRects(o);
            if (rects != null) {
                // Only rects that touch the captured part matter.
                List<Rectangle> clipped = new ArrayList<>(rects.size());
                for (Rectangle r : rects) {
                    Rectangle c = r.intersection(o.partLocal);
                    if (!c.isEmpty()) clipped.add(c);
                }
                rects = clipped;
                if (rects.isEmpty()) return;
            }

            Pointer ctx = o.dev.context;
            if (rects == null) {
                callVoid(ctx, 47, o.staging, tex); // CopyResource(dst, src)
                rects = new ArrayList<>(1);
                rects.add(new Rectangle(o.partLocal));
            } else {
                for (Rectangle r : rects) {
                    // D3D11_BOX {left, top, front, right, bottom, back}
                    box.setInt(0, r.x);
                    box.setInt(4, r.y);
                    box.setInt(8, 0);
                    box.setInt(12, r.x + r.width);
                    box.setInt(16, r.y + r.height);
                    box.setInt(20, 1);
                    // CopySubresourceRegion(dst, dstSub, dstX, dstY, dstZ, src, srcSub, srcBox)
                    callVoid(ctx, 46, o.staging, 0, r.x, r.y, 0, tex, 0, box);
                }
            }

            mapped.clear();
            hr = call(ctx, 14, o.staging, 0, D3D11_MAP_READ, 0, mapped); // Map
            if (hr < 0) throw new CaptureLostException("Map(staging) failed " + hex(hr));
            try {
                Pointer data = mapped.getPointer(0);
                int pitch = mapped.getInt(PS); // RowPitch follows the void* pData
                int offX = o.desk.x - region.x;
                int offY = o.desk.y - region.y;
                int[] px = fb.pixels;
                int stride = fb.width;
                for (Rectangle r : rects) {
                    for (int y = r.y; y < r.y + r.height; y++) {
                        int dst = (offY + y) * stride + offX + r.x;
                        data.read((long) y * pitch + (long) r.x * 4, px, dst, r.width);
                        for (int i = dst, end = dst + r.width; i < end; i++) px[i] &= 0x00FFFFFF;
                    }
                    dirtyOut.add(new Rectangle(offX + r.x, offY + r.y, r.width, r.height));
                }
            } finally {
                callVoid(ctx, 15, o.staging, 0); // Unmap
            }
            o.needFull = false;
        } finally {
            release(tex);
            release(res);
            call(o.dupl, 14); // ReleaseFrame
        }
    }

    /** Move-destination + dirty rects of the current frame (output-local), or null = treat as full. */
    private List<Rectangle> frameRects(Out o) {
        List<Rectangle> out = new ArrayList<>();
        // GetFrameMoveRects: DXGI_OUTDUPL_MOVE_RECT { POINT SourcePoint; RECT DestinationRect } = 24 bytes
        int n = fetch(o.dupl, 10);
        if (n < 0) return null;
        for (int i = 0; i < n / 24; i++) {
            long b = (long) i * 24 + 8;
            out.add(rect(rectBuf.getInt(b), rectBuf.getInt(b + 4), rectBuf.getInt(b + 8), rectBuf.getInt(b + 12)));
        }
        n = fetch(o.dupl, 9); // GetFrameDirtyRects: RECT[]
        if (n < 0) return null;
        for (int i = 0; i < n / 16; i++) {
            long b = (long) i * 16;
            out.add(rect(rectBuf.getInt(b), rectBuf.getInt(b + 4), rectBuf.getInt(b + 8), rectBuf.getInt(b + 12)));
        }
        if (out.size() > MAX_RECTS) return null;
        return out;
    }

    /** Calls GetFrameMoveRects/GetFrameDirtyRects into rectBuf; returns bytes written or -1. */
    private int fetch(Pointer dupl, int idx) {
        for (int attempt = 0; attempt < 3; attempt++) {
            sizeRequired.setValue(0);
            int hr = call(dupl, idx, (int) rectBuf.size(), rectBuf, sizeRequired);
            if (hr == DXGI_ERROR_MORE_DATA) {
                rectBuf = new Memory(Math.max(rectBuf.size() * 2, sizeRequired.getValue() + 256L));
                continue;
            }
            return hr < 0 ? -1 : sizeRequired.getValue();
        }
        return -1;
    }

    private static Rectangle rect(int l, int t, int r, int b) {
        return new Rectangle(l, t, Math.max(0, r - l), Math.max(0, b - t));
    }

    @Override
    public void close() {
        for (Out o : outs) {
            release(o.staging);
            release(o.dupl);
            o.staging = null;
            o.dupl = null;
        }
        outs.clear();
        for (Dev d : devs) {
            release(d.context);
            release(d.device);
            d.context = null;
            d.device = null;
        }
        devs.clear();
    }

    // ------------------------------------------------------------------ COM helpers

    private static int call(Pointer iface, int idx, Object... args) {
        Object[] full = new Object[args.length + 1];
        full[0] = iface;
        System.arraycopy(args, 0, full, 1, args.length);
        return fn(iface, idx).invokeInt(full);
    }

    private static void callVoid(Pointer iface, int idx, Object... args) {
        Object[] full = new Object[args.length + 1];
        full[0] = iface;
        System.arraycopy(args, 0, full, 1, args.length);
        fn(iface, idx).invokeVoid(full);
    }

    private static Function fn(Pointer iface, int idx) {
        Pointer vtbl = iface.getPointer(0);
        return Function.getFunction(vtbl.getPointer((long) idx * PS), CONV);
    }

    private static void release(Pointer iface) {
        if (iface == null) return;
        try {
            fn(iface, 2).invokeInt(new Object[]{iface});
        } catch (Throwable ignored) {
        }
    }

    private static void check(int hr, String what) throws IOException {
        if (hr < 0) throw new IOException(what + " failed " + hex(hr));
    }

    private static String hex(int hr) {
        return "0x" + Integer.toHexString(hr).toUpperCase();
    }

    /** GUID string → 16-byte native GUID {Data1 u32, Data2 u16, Data3 u16, Data4[8]}. */
    private static Memory guid(String s) {
        String h = s.replace("-", "");
        Memory m = new Memory(16);
        m.setInt(0, (int) Long.parseLong(h.substring(0, 8), 16));
        m.setShort(4, (short) Integer.parseInt(h.substring(8, 12), 16));
        m.setShort(6, (short) Integer.parseInt(h.substring(12, 16), 16));
        for (int i = 0; i < 8; i++) m.setByte(8 + i, (byte) Integer.parseInt(h.substring(16 + i * 2, 18 + i * 2), 16));
        return m;
    }
}
