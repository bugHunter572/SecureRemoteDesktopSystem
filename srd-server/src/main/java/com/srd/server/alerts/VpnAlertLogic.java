package com.srd.server.alerts;

import com.srd.common.status.VpnGuardStatus.State;

/** Pure decision rules for VPN Guard alerts (unit-tested). */
public final class VpnAlertLogic {
    public enum Decision {
        /** Nothing to do. */
        NONE,
        /** Entered an alert state: highlight the row, toast, beep, log. */
        ALERT,
        /** The service runs again after an alert: clear the highlight, short "restored" toast. */
        RESTORED
    }

    private VpnAlertLogic() {
    }

    /** The VPN Guard service is running in these states (even if it blocks the internet or is slow to report). */
    public static boolean serviceRunning(State s) {
        return s == State.PROTECTED || s == State.BLOCKED || s == State.STALE || s == State.CHECKING;
    }

    /**
     * @param previous             last known state (null if never seen)
     * @param next                 newly reported state
     * @param notificationsEnabled the client's bell toggle (and the global switch)
     * @param highlighted          whether the row currently carries an alert highlight
     */
    public static Decision decide(State previous, State next, boolean notificationsEnabled, boolean highlighted) {
        if (next == null) return Decision.NONE;
        if (serviceRunning(next)) return highlighted ? Decision.RESTORED : Decision.NONE;
        if (next.alert && next != previous && notificationsEnabled) return Decision.ALERT;
        return Decision.NONE;
    }
}
