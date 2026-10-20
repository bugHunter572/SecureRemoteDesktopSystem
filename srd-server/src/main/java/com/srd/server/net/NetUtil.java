package com.srd.server.net;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;

/** Address helpers moved from the v2 monolith. */
public final class NetUtil {
    private NetUtil() {
    }

    /** Private, link-local and loopback IPv4 ranges only (public addresses are rejected). */
    public static boolean isLocalIPAddress(String ipAddress) {
        try {
            String[] octets = ipAddress.split("\\.");
            if (octets.length != 4) return false;
            int first = Integer.parseInt(octets[0]);
            int second = Integer.parseInt(octets[1]);
            if (first == 10) return true;
            if (first == 172 && second >= 16 && second <= 31) return true;
            if (first == 192 && second == 168) return true;
            if (first == 169 && second == 254) return true;
            return first == 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** True if the address belongs to this machine (remote control of yourself would loop input). */
    public static boolean isSameHost(String ip) {
        try {
            if (ip == null) return false;
            if ("127.0.0.1".equals(ip) || "::1".equals(ip)) return true;
            if (ip.equals(InetAddress.getLocalHost().getHostAddress())) return true;
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface ni = nets.nextElement();
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr != null && ip.equals(addr.getHostAddress())) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** True if the host answers a single ICMP echo within ~1.5 s (i.e. the computer is on). Blocking. */
    public static boolean isHostReachable(String ip) {
        try {
            boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
            ProcessBuilder pb = windows
                    ? new ProcessBuilder("ping", "-n", "1", "-w", "1500", ip)
                    : new ProcessBuilder("ping", "-c", "1", "-W", "2", ip);
            Process p = pb.redirectErrorStream(true).start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            String n = System.getenv("COMPUTERNAME");
            return n != null ? n : "server";
        }
    }
}
