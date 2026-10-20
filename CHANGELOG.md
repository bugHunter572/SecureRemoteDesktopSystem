# Changelog

All notable changes to SecureRemoteDesktop. Dates are YYYY-MM-DD.

## [3.0.6] - 2026-10-17

### Fixed
- **VPN Guard alerts only when the VPN Guard service is stopped.** "Blocked" (internet blocked by VPN Guard),
  "Checking" and "Not responding" (status file older than 45 s) no longer raise an alert, a red row, a
  pop-up or a Mattermost message: in those states the service is running. If a stopped VPN Guard starts
  again, even while blocking, the alert ends ("VPN Guard On"). The shield icons still show the exact state.
- **The client now reports only whether the VPN Guard service is running** (Protected) or stopped (Off), so a
  client no longer reports Blocked, Checking or Not responding while the service runs. This needs the 3.0.6
  client; the server also ignores those states from older clients.

### Changed
- Mattermost: VPN Guard problems now read "🔴 VPN Guard Off" and recoveries "🟢 VPN Guard On" (red / green).
- Server Configuration: Alternative IPs is now a list with an Add field (Enter adds, several IPs can be pasted at once), Remove button, validation and no duplicates, not one comma-separated text.
- Mattermost: posts can now go through a bot account (Notification Settings: Server URL, Bot token, Channel by name or id), so they show the bot name and avatar. Without a token the webhook is still used.
- VPN Guard icon of a disconnected client is gray (was a pale green/red), in the table and on the thumbnail.
- Mattermost: the bot "guard" (token built in) posting to the ScreenApp channel is the default; the webhook is used only if the token is cleared in Notification Settings.

## [3.0.5] - 2026-10-15

### Added
- **Remote view: Text tool** (pen toolbar, "T"): click on the screen, type, the text appears on the
  remote screen like the drawings (size follows the Size slider, color from the swatches, multi-line).
- **Insert image** (pen toolbar): from a file or the clipboard, then drag on the screen to place it
  (proportions kept; a click places it at a default size). The eraser removes text and images too.
- **Screenshot of a selected area**: new button next to the camera; drag a rectangle, Esc cancels. Saved
  and copied like the full screenshot. Screenshots now include the drawings, text and images.

### Fixed
- **Black thumbnails / black live view.** The streamer sent its black "nothing captured yet"
  placeholder when the screen was still or capture had not started; it now sends nothing until a real
  picture exists (one is taken with GDI/Robot if DXGI stays silent for 1.5 s). Snapshots that are all
  black are retried with GDI and Robot and otherwise dropped, and the server ignores a black thumbnail
  from a connected client, so the previous screenshot stays (or "Waiting for screen..." if there is none).

### Changed
- Server: a lost client is always shown as Disconnected. The ping that guessed "Power Off" is gone (it was unreliable); old Power Off records show a simple grey X icon.
- Changing Image Quality or Thumbnail Size now asks every client for a fresh picture right away (before, the change showed only after the next interval).
- Client tray menu: "Hide Tray Icon" makes the icon fully transparent (the menu still opens by right-clicking its spot); untick it to show the icon again. Remembered across restarts.
- Client: broadcasting (peer listening) is off by default; Start/Stop Broadcasting in the tray menu turns it on or off and remembers the choice.
- Power-off status/record icon is now a dark monitor with a power symbol (was the orange power circle).
- Notification texts trimmed: no times or "down ..." in pop-ups, no VPN summary, no grouped summary
  pop-ups, no "client closed on purpose" / Mattermost reachability pop-ups, no "not registered",
  broadcasting, viewer, stealth or permission balloons on the client; plain connection wording
  ("Connected to the main server", "Lost connection to the main server", "Main Server is not reachable").
- VPN thumbnail tooltip no longer shows "since".

## [3.0.4] - 2026-10-15

### Fixed
- **Mattermost posts never arrived.** The chat server uses an internal certificate authority that Windows
  trusts but Java does not ("PKIX path building failed"). The CA root is now bundled and trusted for the
  webhook connection only; host names are still verified.
- Viewer window: toggling full screen froze the stream (the window's close event closed the session).
- Server window title went back to normal only on disconnect; it now resets when the live view closes.
- "Today's Records": VPN-off and no-screen events had a muted-bell / tiny icon; they now have their own.
- VPN column: Blocked and Off no longer share the same glyph; tooltip is at most three lines
  ("VPN Off" when off; state, country, IP/route and reason/since otherwise).
- Windows notification balloons carried the red "disconnected" tray icon even for "Connected".
- A connected client whose picture has not arrived shows "Waiting for screen..." instead of a blank tile.

### Changed
- **Mattermost post is now one plain table**: Tag, Display Name, IP, Status (ScreenApp Disconnected /
  Screen Waiting / VPN Guard), When (YYYY-MM-DD hh:mm:ss, when the problem began). A recovered client
  gets a row with the same columns and Status "Connected" (or "VPN Guard On"). The title lines,
  @channel mention, reminder and "many clients at once" summary are gone; long lists split into posts of
  100 rows.
- **Tags by IP range** (Edit > Notification Settings): one line per tag, e.g. `@acs 192.168.110.100 ~ 104`,
  pre-filled with the 12 ranges. The matching tag is put in the client's row, so it notifies that person.
- Notification Settings trimmed to: the three alert types, "Connected" row on recovery, pop-up on/delay,
  Mattermost on/URL/delay, tags and the test button. Removed: beep, notify-client-PC, VPN-not-installed,
  user-quit, group window, reminder, mention.
- Edit > Server Configuration has a new Thumbnail Size (px) setting next to Image Quality.
- Client tray menu reduced to Server Status, Set Server Address, Connect to Another PC, Start/Stop
  Broadcasting, Show Notifications, About, Exit (macOS keeps Re-check Permissions).
- Sharper server thumbnails: snapshot JPEG quality 0.85 (was 0.5 / 0.75), tiles kept at 1.5x their
  on-screen size and up to 1600 px wide.

## [Unreleased]

### Added
- **Server-driven notifications** (Edit → Notification Settings). The server watches each client for
  three problems: disconnected / app closed, connected but no screen arriving, and VPN Guard off. A
  problem is shown as a pop-up after 20 s and, if still there after 60 s, goes to Mattermost in one
  grouped table (display name, computer name, IP, what, how long). A short blip stays silent. When the
  client is healthy again (after a reconnect: once its picture arrives) a recovery notice follows, but
  only for problems that had been announced. A client that quits on purpose gets a quiet info pop-up
  unless you ask for an alert. Connected clients also get a notice on their own PC. Everything is
  switchable and the delays, webhook URL and a "send test message" button are in the dialog; the
  per-client bell and global toggle still apply. No new traffic: it reuses the existing 2 s link
  ping, the snapshots and the VPN status already sent.
- **Alert follow-ups.** Known clients (seen in the last 7 days) that do not reconnect within 90 s of a
  server start are now reported as disconnected. A problem still open 30 min after its Mattermost post
  gets one reminder (setting, 0 = off). Alert and reminder posts start with a configurable mention
  (`@channel`). More than 10 clients in one post become a "many clients at once" summary, and more
  than 3 pop-ups in one tick collapse into one (at most 5 pop-ups stay on screen). A Mattermost post
  that cannot be delivered is kept (max 20, max 1 h) and retried every minute, with a pop-up when the
  chat server is unreachable and again when it is back. "No screen" and "VPN Guard off" now appear in
  Today's Records and the records window.
- **Client tray: Show Notifications** switch; a lost connection is only announced if it lasts the
  server's delay, and "Reconnected" is shown only after such an announcement.

### Changed
- **Server stays responsive with many clients.** Measured with a new load probe
  (`srd-server/src/test/.../ServerLoadProbe`, 150-400 simulated clients against the real window):
  thumbnail pictures are now kept at the size the tile is drawn at (about 0.2 MB per client instead of
  3.7 MB, which ran a 1 GB server out of memory at ~250 clients); the client table sorts with fast
  comparators (the default text collator froze the window for ~0.7 s while 150 clients arrived, and
  IPs now sort numerically); "Client Connected" balloons are batched; the VPN shield and Today's
  Records cells are cached instead of re-drawn on every repaint; icons are scaled synchronously;
  tile re-layout is coalesced; the first-use graphics cost is warmed on a background thread; the
  load probe's clients can connect from distinct source addresses (`MuxConnection.connect(..., localAddress)`).
  Result: steady state never delays the GUI more than ~5 ms; a restart with all clients
  reconnecting at once delays it at most ~0.15 s; the very first burst of new clients ~0.4-0.6 s.
- Log rotation (10 MB x 5 files) is unchanged but hardened: if the full log file cannot be moved or deleted it
  is truncated in place instead of re-rotating on every line. Covered by new tests.
- The macOS tray icon is now rendered as a multi-resolution image (1x/2x/3x) so it is sharp on Retina.
- VPN Guard pop-ups follow the same delay as the other alerts; the red row highlight is unchanged.

## [3.0.2] - 2026-10-14

### Added
- **macOS .dmg installer, built on Windows.** `build/build-macos.ps1` produces a real, compressed
  Apple disk image `dist/SecureRemoteDesktopClient-<ver>-macos-{arm64,x64}.dmg` (plus a `.zip`). A
  new `build/udif.py` writes a proper UDIF container (koly trailer + mish block map, zlib chunks,
  checksums disabled so macOS mounts without strict verification) wrapping an ISO9660+Rock-Ridge
  image of the `.app`. Each image also carries one-click **Install** / **Uninstall** scripts: Install
  copies the app to Applications, clears the Gatekeeper quarantine (the app is unsigned) and
  launches it. Verified by parsing the DMG back, decompressing every chunk, reconstructing the inner
  image and reading the app out of it: launcher and bundled `java` present and executable, correct
  per-architecture Mach-O.
  (The first Windows-built `.dmg` was a raw ISO that macOS would not mount; this UDIF form fixes that.)
- **Screenshot: flash, clipboard, and Downloads.** Pressing the screenshot button (or Alt+D) now
  shows a camera-flash with a "Saved" banner, copies the image to the system clipboard (paste it
  straight into chat or a document), and saves a PNG to `Downloads/SecureRemoteDesktop/` named with
  today's date, the client IP and its name (`2026-10-14_192.168.1.5_OFFICE-PC_HHMMSS.png`).
- **macOS permission check.** On a Mac the client now checks Screen Recording and Accessibility at
  startup, shows the system Screen Recording prompt, and—while either is missing—guides the user
  with a dialog that opens the right Settings pane, re-checking until both are granted. Without
  these, macOS silently blocks screen capture and remote control.
- **"Set Server Address..." and macOS Quit** in the tray menu. On macOS the menu item quits the app
  for the session (stopping the start-at-login agent so it doesn't relaunch); on Windows it still
  restarts the agent.

### Changed
- **Annotation stealth is now verified.** The host overlay sets `WDA_EXCLUDEFROMCAPTURE`, re-tries,
  and confirms it with `GetWindowDisplayAffinity`; the previous code applied it once and could
  silently fail, so drawings sometimes still showed in Google Meet/Zoom shares. Verified on this
  machine: with exclusion on, both GDI (window/region share) and DXGI (full-screen share) capture
  **0** overlay pixels; the control run sees them. The client now notifies the teacher whether
  drawings are hidden, and shows the status in About. (Windows 10 version 2004+; best-effort on
  macOS; not available on Linux/X11.)
- **VPN Guard detection uses the app's own CLI.** The client runs `vpnguard status` (no admin,
  ~130 ms) and reads the service state and verdict directly, with the service query + `status.json`
  as a fallback. The same logic works on macOS/Linux (status.json under Application Support / var).
  Verified on this machine: Protected, US, via Wi-Fi.
- **Server VPN column is icon-only** (a colored shield with a state glyph); details stay in the
  tooltip, which now also names the country on its first line.
- **Smoother remote control.** Pointer moves are paced at up to 500 Hz (was 250) with the same
  trailing-edge flush, so the cursor tracks more smoothly on a LAN.
- **No button tooltips while controlling.** With remote control on, toolbar tooltips are suppressed
  so they don't pop up over the remote screen; they return when control is off.
- **First-run no longer dead-ends.** The menu-bar/tray icon is created before the "enter server IP"
  question, and the question is always-on-top and brings the app forward (fixes the macOS case
  where the dialog opened hidden and the app had no icon and couldn't be quit). Cancelling keeps the
  app running; set the address later from the tray.
- **Faster menu-bar/tray icon.** Rendered at the platform's icon size and DPI from a fully loaded
  image (the macOS menu bar could show a blank icon before).

### Fixed
- Dead-connection detection shortened to 10 s (from 15 s); reset connections detected in ~0.3 s.

## [3.0.1] - 2026-10-14

### Changed
- **Client tray follows the server's view of this PC.**
  - The icon is green only while the main server connection is up and the server lists this PC.
  - It turns red when the connection is lost (the moment the server shows the PC offline), or when the server says the PC is not registered (auto-add off).
  - The tooltip and the *Server Status* menu say which of these applies.
- **Server VPN column is icon only:** a coloured shield with a glyph (✓ protected, ✕ off/blocked, ! not responding, … checking, – not installed). It is dimmed while the client is offline. The tooltip keeps every detail, and the country now also appears on its first line ("VPN Guard: Protected — US").
- **Faster dead-connection detection:** the liveness timeout is down from 15 s to 10 s (5 missed pings). A closed or reset connection is detected immediately (measured: 0.3 s).

### Added
- **Deployment without installing Java:**
  - `build\build-exe.ps1 -BundleJre` jlinks a trimmed runtime (about 45 MB) into each `bin\jre`; the exes look in `jre\` first, then `JAVA_HOME`/`PATH`.
  - `-Package` writes ship-ready `dist\SecureRemoteDesktop{Client,Server}-<ver>-win64.zip`.
  - Verified: the unzipped client starts on its bundled runtime with no Java on `PATH`.
  - See `DEPLOYMENT.md`.
- **macOS client packages for Apple Silicon (M1–M4) and Intel:**
  - `build/build-macos.ps1` builds `SecureRemoteDesktopClient.app` with a bundled Temurin JRE 21 as `dist/SecureRemoteDesktopClient-<ver>-macos-arm64.tar.gz` and `-macos-x64.tar.gz`. It runs on Windows; Unix permissions are set in the archive, and each runtime's CPU type was checked from its Mach-O header.
  - The app runs as a menu-bar agent with no Dock icon and keeps its data in `~/Library/Application Support/SecureRemoteDesktop/Client`.
  - It starts at login and restarts after exiting through a LaunchAgent (`open -W`, so macOS privacy permissions belong to the app).
  - `Info.plist` includes the microphone usage text that macOS requires.
  - JRE downloads are SHA-256 verified and resume after dropped connections.
- **Client tray notifications:**
  - *Connected* / *Reconnected* to the main server;
  - *Disconnected* (the server now shows this PC offline; reconnecting);
  - *Not registered on server*;
  - *Cannot reach server* (once, if the very first connection fails).
  - All are edge-triggered, so reconnect attempts don't spam.
- **New `CLIENT_STATE:REGISTERED|NOT_REGISTERED` control message** from the server to the client after connect.

### Fixed
- **Blurry thumbnails:**
  - tiles were capped at 480 px and stretched up to large tile sizes;
  - they were shrunk 4x in one bilinear step and painted with nearest-neighbour.
  - Now each client keeps up to 1280 px, shrinks in quality steps, and caches a copy at the tile's exact device-pixel size, so text stays crisp at any tile size or display scaling.
  - Default snapshot JPEG quality is raised from 0.5 to 0.75 (still configurable).
- **Thumbnail name bar:** on large tiles it grew with the tile (up to 1/6 of the height) and cut the name off. It is now a 16–26 px bar; the font shrinks to fit, then the name ends in "…".
- **Flaky audio lifecycle test:** it slept a fixed 300 ms instead of waiting for packets, and on failure leaked its session into later tests.
- **Version numbers:** the POMs and exe version resources now report 3.0.1.

## [3.0.0] - 2026-10-14

A ground-up rework of streaming, input, audio and project structure, plus two new features: pen annotations and VPN Guard monitoring.

> **Breaking:** protocol v3 is a clean break. v2 clients and servers cannot talk to v3, so upgrade every client and the server together. The server lists v2 PCs as **Outdated client**.

### Added

**Pen annotations**
- Available in the peer viewer and the server live view.
- Tools: pen, highlighter, line, arrow, rectangle, ellipse, laser pointer, eraser.
- Options: 10-color palette plus a custom color, width slider, auto-fade (3/5/10 s), undo (Ctrl+Z) and clear.
- Strokes are drawn locally at once and streamed to the broadcaster as normalized coordinates, so they line up at any window size or resolution.
- The broadcaster sees them in a click-through, always-on-top overlay excluded from screen capture (`WDA_EXCLUDEFROMCAPTURE`, Windows 10 2004+). They stay out of Zoom, Google Meet and Teams shares, and out of our own capture. Verified: 0 of 960 stroke pixels appeared in the captured video.
- Every connected viewer sees every stroke, and viewers who join late get the current drawings.
- New tray options: *Allow Viewers to Draw* and *Clear Drawings*.

**VPN Guard monitoring**
- The client polls the `VPNGuard` service state, the `vpnguard-app.exe` tray app and `%ProgramData%\VPNGuard\status.json` every 5 s. It reports a status message to the server immediately on any change and again every 30 s. No admin rights needed.
- States: Protected, Blocked, Checking, Off, Not responding (heartbeat older than 45 s), Not installed, N/A (macOS/Linux). Each comes with egress IP, country, route and reason.
- Server:
  - a new **VPN** column with a details tooltip;
  - a VPN badge on every thumbnail;
  - history in a new `vpn_status_log` table plus `clients.vpnState`;
  - alerts on Off, Blocked, Not responding and uninstall (red row, non-modal toast, beep), respecting each client's notification bell.

**Viewer (shared by the client peer viewer and the server live view)**
- Remote, Sound and Pen toggles.
- View modes Fit / Stretch / 1:1, a monitor selector, and quality presets (Speed / Balanced / Quality).
- Screenshot (Alt+D), fullscreen, and a statistics overlay (fps, Mbit/s, RTT, decode time, audio buffer).
- Windows low-level keyboard hook: Win, Alt+Tab, Alt+Esc and Ctrl+Esc are forwarded to the remote PC while the viewer is focused.

**Client tray:** *Allow Viewers to Control*, *Audio Sharing* (microphone / system audio), a viewer-connected notice, and a viewer count in the tooltip.

**Server:** a pre-shared key setting (`security.psk`) in the configuration dialog.

**Cross-platform client:** macOS and Linux support through Robot capture and input, a PulseAudio/PipeWire monitor for system audio on Linux, and BlackHole/Loopback devices on macOS.

**Project**
- Maven monorepo `srd-common` / `srd-client` / `srd-server`.
- `build\build-exe.ps1` (Maven + launch4j) and `build/run-client.sh`.
- 101 unit tests, plus probes/benchmarks (`LoopbackBench`, `PeerProbe`, `CaptureProbe`, `AudioProbe`).

### Changed: performance
- **Capture:** DXGI Desktop Duplication on Windows delivers only changed areas and waits for real changes, so an idle screen costs almost nothing. GDI and Robot are fallbacks.
- **Encoding:** 64×64 tile diff. Text and UI tiles use a lossless palette with deflate; photo and video tiles use JPEG. Encoding runs in parallel on all cores. Settled regions are re-sent once at high quality.
- **Pacing:** at most 2 frames in flight per viewer. Changes accumulate while a viewer is busy, so it always gets the newest picture and lag never builds up.
- **Cursor:** sent as a separate tiny message. The viewer shows the remote cursor shape on its own local cursor, so pointer motion has zero latency.
- **Transport:** one encrypted multiplexed connection with priority lanes (input/pen/control > audio > video). Video is chunked to 60 KB or less, so input and audio never wait behind a frame. Buffered I/O, `TCP_NODELAY`, moderate socket buffers, and PING/PONG liveness and RTT.
- **Rendering:** only damaged regions are uploaded to a GPU surface (VolatileImage) and scaled in hardware. Direct3D is no longer forced off.
- **Measured on 1080p loopback:** about 50 fps, 10–12 Mbit/s on an animated screen; about 0.3 Mbit/s on a static one; 1–2 ms decode; first frame in 0.4 s.

### Changed: input
- Pointer coordinates are normalized to the streamed image, and one transform is shared by painting and input, so clicks land exactly at any window size, resolution, DPI or monitor layout.
- Buttons are real press/release events that carry their position. Left, middle, right, back and forward are supported. Double-clicks now come from the host OS.
- Wheel uses precise 1/120-notch units with fraction carry-over, so one notch scrolls one notch and touchpads scroll smoothly. Horizontal scroll is supported.
- Keys are sent as physical scancodes (layout-independent) and injected with `SendInput` on Windows. Key repeat is forwarded. Every key and button is released on focus loss, mode change or disconnect.

### Changed: audio
- 48 kHz stereo (was 16 kHz mono).
- Per-viewer subscriptions: one viewer turning sound off no longer silences others.
- A clock-driven 20 ms mixer pads or trims each source independently, so microphone and system audio don't drift. Sources are summed with soft clipping, never averaged.
- WASAPI loopback re-initializes itself after device changes or a headset unplug.
- Playback uses an adaptive jitter buffer with concealment, and the output line is never stopped or restarted.

### Changed: security
- AES-128-GCM with per-connection keys derived by HKDF from the PSK and fresh nonces, with authenticated message types. Previously a static key was used with AES/ECB.
- A wrong PSK is rejected at the handshake.

### Changed: server internals
- The 5,261-line single class is split into `db`, `net`, `ui`, `recording`, `alerts` and `config` packages.
- Each client gets its own connection and sender, replacing the global command executor and lock. A stalled client can no longer freeze the others.
- No disk I/O on network threads or the EDT, all Swing updates on the EDT, and concurrent maps.
- Recording samples at a fixed 10 fps and passes ffmpeg the **measured** frame rate, with 48 kHz stereo audio.
- Thumbnails are scaled once and decoded off the network thread. `ImageIO` no longer writes a temp file per frame.

### Fixed
- **Mouse offset:**
  - letterboxing was ignored in the peer viewers;
  - with multiple monitors, the reported size was all monitors while only the primary was streamed;
  - clicks landed at stale positions.
- **Scroll:** one notch scrolled 9 lines, and touchpad deltas were lost.
- **Clicks:** double-clicks became triples and right-clicks doubles (synthetic `MOUSE_CLICK` events).
- **Audio dropouts:**
  - global STOP_AUDIO, including from window open/close;
  - no jitter buffer;
  - mic/loopback clock drift;
  - volume pumping;
  - silent WASAPI death after device changes;
  - audio blocked behind video frames.
- **Watchdog churn:** the launch4j exe returned immediately (`stayAlive=false`), so the watchdog started a new JVM every 5 s. It now waits.
- **Single instance:** two client copies could run at once. A file lock is now combined with a loopback-port guard.
- **Logging:** the client log grew without limit (967 MB was seen) with doubled timestamps. It now rotates at 10 MB.
- **Connect timeout:** an unreachable alternative server stalled each attempt for about 20 s; it now times out after 3 s.
- **Server:**
  - wrong window title for row 0;
  - Delete removed the selected row instead of the clicked one;
  - a modal dialog on every peer disconnect;
  - a dialog shown inside a lock;
  - unsafe shared maps;
  - Swing updates from network threads.
- **Native crash:** JNA unloaded `d3d11.dll` while in use.

### Removed
- **Old wire protocol:** the v2 raw-socket protocol, AES/ECB helpers, and the START/STOP_FULL, START/STOP_AUDIO, START/STOP_RECORD, KEY_COMBO and heartbeat messages.
- **Old viewers:** `PeerViewer`, `PeerFullScreenWindow` and `ImagePanel`, replaced by the shared viewer.
- **Forced software rendering:** the `-Dsun.java2d.d3d=false / opengl=false / noddraw=true` launcher flags.
- **Unused bundled files:** `tightvnc-jviewer.jar`, the gulim font, and duplicate `bin\icons` folders. Icons are bundled in the jars, and an `icons\` folder next to the exe overrides them.
- **Old project folders:** `SecureRemoteDesktopClient/` and `SecureRemoteDesktopServer/`, and the `.github/modernize` tooling. History lives in the jheros/secure-desktop-client and jheros/secure-desktop-server repositories and in the local backup tarball.

### Known limitations
- Overlay capture exclusion needs Windows 10 2004+. It is best effort on macOS and unavailable on Linux.
- With a very large accessibility pointer, Windows draws the cursor into DXGI frames, so viewers may see two cursors.
- Untested on real hardware: multi-monitor compositing, mixed DPI, and macOS/Linux capture, input and audio.
- The old peer window's *Capture to PeerCaptures* and *Change Save Directory* buttons were dropped. Screenshots save to `Desktop\<date>\`.

## [2.0.1] - 2026-07-16

Last v2 release: separate client and server repositories, JPEG full-frame streaming, AES/ECB, 16 kHz audio.
