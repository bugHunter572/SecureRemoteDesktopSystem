package com.srd.client.capture;

import com.srd.common.ui.CursorKind;

/** Host pointer position in the same coordinate space as {@link ScreenLayout} physical bounds. */
public final class CursorState {
    public final int x;
    public final int y;
    public final boolean visible;
    public final CursorKind kind;

    public CursorState(int x, int y, boolean visible, CursorKind kind) {
        this.x = x;
        this.y = y;
        this.visible = visible;
        this.kind = kind;
    }
}
