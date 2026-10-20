package com.srd.common.ui;

import java.awt.Cursor;

/**
 * Remote cursor shapes as a small enum, so the viewer can show the right local cursor (arrow,
 * I-beam, hand…) with zero latency instead of drawing the cursor into every video frame.
 */
public enum CursorKind {
    ARROW(Cursor.DEFAULT_CURSOR),
    IBEAM(Cursor.TEXT_CURSOR),
    HAND(Cursor.HAND_CURSOR),
    WAIT(Cursor.WAIT_CURSOR),
    CROSSHAIR(Cursor.CROSSHAIR_CURSOR),
    SIZE_NS(Cursor.N_RESIZE_CURSOR),
    SIZE_WE(Cursor.E_RESIZE_CURSOR),
    SIZE_NWSE(Cursor.NW_RESIZE_CURSOR),
    SIZE_NESW(Cursor.NE_RESIZE_CURSOR),
    SIZE_ALL(Cursor.MOVE_CURSOR),
    APP_STARTING(Cursor.WAIT_CURSOR),
    NO(Cursor.DEFAULT_CURSOR),
    HIDDEN(Cursor.DEFAULT_CURSOR);

    public final int awtType;

    CursorKind(int awtType) {
        this.awtType = awtType;
    }

    public static CursorKind of(int ordinal) {
        CursorKind[] v = values();
        return ordinal >= 0 && ordinal < v.length ? v[ordinal] : ARROW;
    }
}
