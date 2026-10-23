package com.srd.server.alerts;

import com.srd.common.status.VpnGuardStatus;
import com.srd.common.util.Log;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VPN Guard row highlighting: decides on transitions ({@link VpnAlertLogic}) and keeps the set of
 * rows that are highlighted red. Pop-ups, Mattermost and client notices are {@link AlertService}'s.
 * Thread-safe.
 */
public final class AlertCenter {
    private final Set<String> highlighted = ConcurrentHashMap.newKeySet();

    public boolean isHighlighted(String clientId) {
        return clientId != null && highlighted.contains(clientId);
    }

    public void clear(String clientId) {
        highlighted.remove(clientId);
    }

    /**
     * Handles a reported status. Call for every report; non-transitions are no-ops.
     *
     * @return the decision taken (the caller repaints the row if it is not NONE)
     */
    public VpnAlertLogic.Decision onVpnStatus(String clientId, String displayName, VpnGuardStatus previous,
                                              VpnGuardStatus next, boolean notificationsEnabled) {
        VpnAlertLogic.Decision d = VpnAlertLogic.decide(previous == null ? null : previous.state, next.state,
                notificationsEnabled, highlighted.contains(clientId));
        switch (d) {
            case ALERT:
                highlighted.add(clientId);
                Log.warn("[VPN] ALERT " + displayName + " (" + clientId + "): " + next.summary()
                        + (previous != null ? " (was " + previous.state.label + ")" : ""));
                break;
            case RESTORED:
                highlighted.remove(clientId);
                Log.info("[VPN] restored " + displayName + " (" + clientId + "): " + next.summary());
                break;
            default:
                if (next.state == VpnGuardStatus.State.PROTECTED) highlighted.remove(clientId);
        }
        return d;
    }
}
