package com.srd.common.input;

import com.srd.common.util.Threads;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Viewer-side input pacing. Pointer moves are coalesced to at most one per
 * {@link #MOVE_INTERVAL_MS}, but the latest position is always delivered (trailing-edge flush).
 * The old 16 ms throttle simply dropped moves, so the pointer often stopped short of where the
 * user left it. Buttons, wheel and keys go out immediately, after any pending move, so ordering
 * is preserved.
 */
public final class InputBatcher {
    // 2 ms = up to 500 moves/s. On a LAN this tracks the pointer smoothly without flooding; the
    // trailing-edge flush still guarantees the final position is delivered.
    public static final long MOVE_INTERVAL_MS = 2;

    private final Consumer<byte[]> sender;
    private int pendingX = -1;
    private int pendingY = -1;
    private int lastX = -1;
    private int lastY = -1;
    private long lastMoveSentNs;
    private ScheduledFuture<?> flushTask;

    public InputBatcher(Consumer<byte[]> sender) {
        this.sender = sender;
    }

    public synchronized void move(int nx, int ny) {
        if (nx == lastX && ny == lastY && pendingX < 0) return;
        long now = System.nanoTime();
        if (now - lastMoveSentNs >= MOVE_INTERVAL_MS * 1_000_000L) {
            sendMove(nx, ny, now);
        } else {
            pendingX = nx;
            pendingY = ny;
            if (flushTask == null) {
                long delayUs = Math.max(200, (MOVE_INTERVAL_MS * 1_000_000L - (now - lastMoveSentNs)) / 1000);
                flushTask = Threads.scheduler().schedule(this::flush, delayUs, TimeUnit.MICROSECONDS);
            }
        }
    }

    private void sendMove(int nx, int ny, long now) {
        pendingX = -1;
        pendingY = -1;
        lastX = nx;
        lastY = ny;
        lastMoveSentNs = now;
        sender.accept(InputEvent.encode(InputEvent.move(nx, ny)));
    }

    public synchronized void flush() {
        flushTask = null;
        if (pendingX >= 0) sendMove(pendingX, pendingY, System.nanoTime());
    }

    /** Sends a non-move event right away (after any pending move). */
    public synchronized void send(InputEvent e) {
        if (flushTask != null) {
            flushTask.cancel(false);
            flushTask = null;
        }
        if (pendingX >= 0) sendMove(pendingX, pendingY, System.nanoTime());
        if (e.kind == InputEvent.BUTTON) {
            lastX = e.nx;
            lastY = e.ny;
        }
        sender.accept(InputEvent.encode(e));
    }
}
