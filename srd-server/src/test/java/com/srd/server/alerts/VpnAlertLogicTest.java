package com.srd.server.alerts;

import static com.srd.common.status.VpnGuardStatus.State.BLOCKED;
import static com.srd.common.status.VpnGuardStatus.State.CHECKING;
import static com.srd.common.status.VpnGuardStatus.State.NOT_INSTALLED;
import static com.srd.common.status.VpnGuardStatus.State.OFF;
import static com.srd.common.status.VpnGuardStatus.State.PROTECTED;
import static com.srd.common.status.VpnGuardStatus.State.STALE;
import static com.srd.server.alerts.VpnAlertLogic.Decision.ALERT;
import static com.srd.server.alerts.VpnAlertLogic.Decision.NONE;
import static com.srd.server.alerts.VpnAlertLogic.Decision.RESTORED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.srd.common.status.VpnGuardStatus;
import org.junit.jupiter.api.Test;

class VpnAlertLogicTest {
    @Test
    void alertsOnTransitionIntoAlertStates() {
        assertEquals(ALERT, VpnAlertLogic.decide(PROTECTED, OFF, true, false));
        assertEquals(ALERT, VpnAlertLogic.decide(null, NOT_INSTALLED, true, false));
        assertEquals(ALERT, VpnAlertLogic.decide(BLOCKED, OFF, true, false));
    }

    @Test
    void blockedAndNotRespondingAreNotAlerts() {
        assertEquals(NONE, VpnAlertLogic.decide(PROTECTED, BLOCKED, true, false));
        assertEquals(NONE, VpnAlertLogic.decide(CHECKING, STALE, true, false));
        assertEquals(NONE, VpnAlertLogic.decide(PROTECTED, STALE, true, false));
        // the service started again, even though it blocks: the OFF alert is over
        assertEquals(RESTORED, VpnAlertLogic.decide(OFF, BLOCKED, true, true));
        assertEquals(RESTORED, VpnAlertLogic.decide(OFF, STALE, true, true));
    }

    @Test
    void noAlertWhenUnchangedOrMutedOrHarmless() {
        assertEquals(NONE, VpnAlertLogic.decide(OFF, OFF, true, true));
        assertEquals(NONE, VpnAlertLogic.decide(PROTECTED, OFF, false, false));
        assertEquals(NONE, VpnAlertLogic.decide(PROTECTED, CHECKING, true, false));
        assertEquals(NONE, VpnAlertLogic.decide(OFF, null, true, true));
    }

    @Test
    void protectedRestoresOnlyWhenHighlighted() {
        assertEquals(RESTORED, VpnAlertLogic.decide(OFF, PROTECTED, true, true));
        assertEquals(RESTORED, VpnAlertLogic.decide(OFF, PROTECTED, false, true));
        assertEquals(NONE, VpnAlertLogic.decide(CHECKING, PROTECTED, true, false));
        assertEquals(NONE, VpnAlertLogic.decide(null, PROTECTED, true, false));
    }

    @Test
    void everyAlertFlaggedStateAlerts() {
        for (VpnGuardStatus.State s : VpnGuardStatus.State.values()) {
            if (VpnAlertLogic.serviceRunning(s)) continue;
            VpnAlertLogic.Decision d = VpnAlertLogic.decide(PROTECTED, s, true, false);
            assertEquals(s.alert ? ALERT : NONE, d, s.name());
        }
    }

    @Test
    void alertCenterTracksHighlight() {
        AlertCenter ac = new AlertCenter();
        VpnGuardStatus on = VpnGuardStatus.of(PROTECTED);
        VpnGuardStatus off = VpnGuardStatus.of(OFF);
        assertEquals(NONE, ac.onVpnStatus("c", "C", null, on, true));
        assertEquals(ALERT, ac.onVpnStatus("c", "C", on, off, true));
        assertEquals(true, ac.isHighlighted("c"));
        assertEquals(NONE, ac.onVpnStatus("c", "C", off, off, true));
        assertEquals(RESTORED, ac.onVpnStatus("c", "C", off, on, true));
        assertEquals(false, ac.isHighlighted("c"));
        assertEquals(NONE, ac.onVpnStatus("c", "C", on, off, false)); // bell off: no alert
        assertEquals(false, ac.isHighlighted("c"));
    }
}
