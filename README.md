# SecureRemoteDesktop v3

LAN remote monitoring and remote control suite:

- **Server console** (`srd-server`): thumbnail wall, client table, live view and control, recording, VPN Guard monitoring.
- **Client agent** (`srd-client`): the monitored PC. It also works as a **peer broadcaster/viewer**, so one PC can view and control another directly.

## Layout

```
pom.xml            parent (Maven reactor, offline-buildable)
srd-common/        shared library, Java 11
  protocol/        MuxConnection: encrypted, multiplexed TCP with priority lanes, PING/PONG liveness
  crypto/          SessionCrypto: HKDF(PSK, nonces) -> AES-128-GCM per direction
  video/           tile grid diff, palette+deflate / JPEG tile codec, parallel encoder, decoder
  input/           InputEvent (normalized coords, precise wheel, scancodes), batching, keyboard hook
  audio/           48 kHz stereo packets, adaptive JitterBuffer, AudioPlayer
  annotation/      pen strokes model/renderer/toolbar (viewer drawing)
  status/          VPN Guard status model, STATUS codec, tiny JSON reader
  ui/              RemoteScreenPanel, ViewerSession, RemoteViewerFrame (viewer used everywhere)
srd-client/        client agent, Java 11 (Windows/macOS/Linux)
  capture/         DXGI desktop duplication (Win) -> GDI -> Robot fallbacks, cursor probe
  stream/          ScreenStreamer: one capture loop, many viewers, ack-paced dirty-tile streaming
  input/           SendInput injector (Win) / Robot injector
  audio/           AudioEngine: ref-counted, clock-driven mixer; WASAPI loopback, mic, PulseAudio monitor
  overlay/         annotation overlay on the host screen (excluded from screen capture on Win10 2004+)
  net/             ServerLink (to the console), PeerHost (broadcasting)
  viewer/          PeerViewerWindow (Connect to Another PC)
  vpn/             VpnGuardMonitor
srd-server/        server console, Java 17
build/             build-exe.ps1 (Maven + launch4j), run-client.sh (macOS/Linux)
```

## Build

```powershell
# compile + all tests
mvn -o clean verify

# shaded jars + launch4j executables
powershell -ExecutionPolicy Bypass -File build\build-exe.ps1            # both
powershell -ExecutionPolicy Bypass -File build\build-exe.ps1 -Only client -SkipTests
```

Outputs:
- `srd-client\bin\SecureRemoteDesktopClient.exe`
- `srd-server\bin\SecureRemoteDesktopServer.exe`

Each `bin\` folder is that app's runtime folder: `ffmpeg.exe` for the server, config, logs, DB and screenshots live there. Icons are bundled in the jars; an optional `icons\` folder next to the exe overrides them. Launch4j is expected at `C:\Program Files (x86)\Launch4j` (override with `-Launch4jHome`).

**Deploying to other PCs:** run `build\build-exe.ps1 -BundleJre -Package` and see [DEPLOYMENT.md](DEPLOYMENT.md). The zips include a Java runtime, so target PCs need no Java.

**macOS (M1–M4 and Intel):** `build\build-macos.ps1` produces ready `.app` packages with a bundled Java runtime (see DEPLOYMENT.md §3b). For a quick dev run on macOS/Linux, run the client from the shaded jar with `build/run-client.sh`. On macOS, grant the Java binary *Screen Recording* and *Accessibility* permission.

## Protocol v3 (summary)

Every frame on the wire is `[u8 type][u32 len][AES-GCM payload]`. The handshake is `SRD3` magic + version + nonces, then sealed `HELLO`/`HELLO_ACK`. A wrong pre-shared key (`security.psk`, default compatible with v2) fails at the first message.

Each connection has one sender thread with three lanes:

| Lane | Carries |
|---|---|
| interactive | input, pen, cursor, control |
| audio | 20 ms frames, drop-oldest |
| video | tile batches of 60 KB or less, ack-paced |

Input and audio are never stuck behind a large frame.

v3 is a clean break: v2 clients and servers can't connect. The server shows such PCs as *Outdated client*.

## Performance design

- **Capture:** DXGI desktop duplication delivers changes only, with dirty rectangles. It blocks until the screen changes, so idle CPU is near zero.
- **Encoding:** 64×64 tile diff. Text and UI tiles use a lossless palette, photo/video tiles use JPEG. Encoding is parallel across cores. Regions are re-sent once at high quality after they settle.
- **Pacing:** at most 2 frames in flight per viewer. Changes accumulate while a viewer is busy, so it always gets the newest picture and latency never builds up.
- **Cursor:** sent as separate tiny messages. The viewer shows the remote cursor shape on its own local cursor, so pointer motion has zero latency.
- **Viewer rendering:** only damaged regions are copied to a GPU (VolatileImage) surface, then scaled in hardware.

## Input

- **Coordinates:** normalized over the streamed image, with one shared transform for painting and mapping, so clicks land exactly at any window size, resolution or DPI.
- **Mouse:** buttons carry their own position. There are no synthetic clicks; double-clicks come from the host OS.
- **Wheel:** precise and smooth, in 1/120 notch units, so one notch scrolls one notch. Horizontal scroll is supported.
- **Keys:** sent as scancodes, so they follow the host's keyboard layout. On a Windows viewer a low-level hook also forwards Win, Alt+Tab and Ctrl+Esc. Everything is released on focus loss or disconnect.

## Audio

- **Engine:** capture runs only while at least one viewer subscribes. Each viewer has its own subscription.
- **Mixer:** a clock-driven 20 ms mixer pads or trims each source independently, so there is no drift between the microphone and system audio.
- **Recovery:** WASAPI loopback re-initializes by itself when the default device changes or a headset is unplugged.
- **Playback:** an adaptive jitter buffer with concealment. The output line is never stopped or restarted.
- **System audio by OS:**
  - Windows: WASAPI loopback
  - Linux: PulseAudio/PipeWire monitor via `parec`
  - macOS: BlackHole or a similar loopback device

## Pen annotations (peer viewer and server view)

- **Tools:** pen, highlighter, line, arrow, rectangle, ellipse, laser pointer and eraser, with a color palette or custom color, width, auto-fade, undo and clear.
- **Rendering:** strokes are drawn locally immediately and streamed to the host. The host shows them in click-through overlay windows marked `WDA_EXCLUDEFROMCAPTURE`. The person at the PC sees them, but Zoom, Meet and Teams screen shares do not, and other viewers render them from the stroke stream.
- **Platform limits:** this needs Windows 10 2004 or later. On macOS it is best effort. On Linux the overlay can't be hidden from capture.

## VPN Guard

On Windows the client watches the `VPNGuard` service, the `vpnguard-app.exe` tray app and `%ProgramData%\VPNGuard\status.json`. The derived states are:

| State | Meaning |
|---|---|
| Protected | service running, egress allowed |
| Blocked | VPN Guard is blocking egress |
| Checking | VPN Guard is verifying the route |
| Off | service not running |
| Not responding | heartbeat older than 45 s |
| Not installed | VPN Guard isn't present |

Each state carries the egress IP, country and route. The server shows a **VPN** column with a details tooltip and a badge on each thumbnail. It logs every change to `vpn_status_log`, and alerts on Off, Blocked, Not responding and uninstall when the client's notification bell is on.
