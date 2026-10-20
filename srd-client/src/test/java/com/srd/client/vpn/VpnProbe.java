package com.srd.client.vpn;

import com.srd.common.status.VpnGuardStatus;

/** Manual probe: prints what this machine's VPN Guard reports and how it was detected. */
public final class VpnProbe {
    public static void main(String[] args) {
        long t0 = System.nanoTime();
        String[] src = new String[1];
        VpnGuardStatus s = VpnGuardMonitor.probe(src);
        System.out.println("state:  " + s.state + "  (" + s.summary() + ")");
        System.out.println("source: " + src[0]);
        System.out.println("fields: " + s.toMap());
        System.out.printf("took %.0f ms%n", (System.nanoTime() - t0) / 1e6);
    }
}
