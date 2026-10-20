package com.srd.client;

import com.srd.client.input.Injectors;
import com.srd.client.input.InputInjector;
import com.srd.client.overlay.AnnotationHub;
import com.srd.client.stream.ScreenStreamer;
import com.srd.client.vpn.VpnGuardMonitor;
import com.srd.common.crypto.SessionCrypto;
import com.srd.common.protocol.Hello;
import com.srd.common.util.Os;
import java.awt.Rectangle;
import java.net.InetAddress;

/** Process-wide services shared by every server link, peer host and viewer. */
public final class ClientContext {
    public static final String VERSION = com.srd.common.util.BuildInfo.VERSION;

    public final ClientConfig config;
    public final byte[] pskKey;
    public final ScreenStreamer streamer;
    public final InputInjector injector;
    public final AnnotationHub annotations;
    public final VpnGuardMonitor vpn;
    public final String clientName;

    /** SERVER_CONFIG SCREEN_CONTROL: the server owner can disable remote control entirely. */
    public volatile boolean serverControlEnabled = true;
    /** SERVER_CONFIG IMAGE_QUALITY: JPEG quality of the periodic snapshots. */
    public volatile float snapshotQuality = 0.85f;
    /** SERVER_CONFIG CLIENT_NOTIFY: the server owner can silence notices on this PC entirely. */
    public volatile boolean serverNotify = true;
    /** SERVER_CONFIG NOTIFY_DELAY: seconds a lost connection must last before this PC says so. */
    public volatile int notifyDelaySec = 20;

    public ClientContext(ClientConfig config) {
        this.config = config;
        this.pskKey = SessionCrypto.pskKey(config.psk());
        this.streamer = new ScreenStreamer();
        this.injector = Injectors.create();
        this.annotations = new AnnotationHub(streamer::layout);
        this.vpn = new VpnGuardMonitor();
        this.clientName = hostName();
    }

    static String hostName() {
        try {
            String h = InetAddress.getLocalHost().getHostName();
            int dot = h.indexOf(".local");
            return dot > 0 ? h.substring(0, dot) : h;
        } catch (Exception e) {
            return "Client-" + System.currentTimeMillis();
        }
    }

    private Hello.Builder base(String role) {
        Rectangle r = streamer.layout().physicalRegion(-1);
        return Hello.builder(role)
                .put(Hello.K_ID, clientName)
                .put(Hello.K_NAME, clientName)
                .put(Hello.K_OS, Os.describe())
                .put(Hello.K_VERSION, VERSION)
                .put(Hello.K_USER, System.getProperty("user.name"))
                .put(Hello.K_SCREEN, r.width + "x" + r.height);
    }

    /** This client connecting to a server console. */
    public Hello agentHello() {
        return base(Hello.ROLE_AGENT).build();
    }

    /** This client accepting a viewer on its peer port. */
    public Hello hostHello() {
        return base(Hello.ROLE_HOST).build();
    }

    /** This client viewing another client. */
    public Hello viewerHello() {
        return base(Hello.ROLE_VIEWER).build();
    }

    public boolean peerControlAllowed() {
        return serverControlEnabled && config.peerControlAllowed();
    }

    public boolean peerDrawAllowed() {
        return config.peerDrawAllowed();
    }
}
