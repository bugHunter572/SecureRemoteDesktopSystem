package com.srd.server.alerts;

import com.srd.common.status.VpnGuardStatus.State;

/** Pure decision rules for VPN Guard alerts (unit-tested). */
public final class VpnAlertLogic {
    public enum Decision {
        /** Nothing to do. */
        NONE,
        /** Entered an alert state: highlight the row, toast, beep, log. */
        ALERT,
        /** Back to PROTECTED after an alert: clear the highlight, short "restored" toast. */
        RESTORED
    }

    private VpnAlertLogic() {
    }

    /**
     * @param previous             last known state (null if never seen)
     * @param next                 newly reported state
     * @param notificationsEnabled the client's bell toggle (and the global switch)
     * @param highlighted          whether the row currently carries an alert highlight
     */
    public static Decision decide(State previous, State next, boolean notificationsEnabled, boolean highlighted) {
        if (next == null) return Decision.NONE;
        if (next == State.PROTECTED) return highlighted ? Decision.RESTORED : Decision.NONE;
        if (next.alert && next != previous && notificationsEnabled) return Decision.ALERT;
        return Decision.NONE;
    }
}
