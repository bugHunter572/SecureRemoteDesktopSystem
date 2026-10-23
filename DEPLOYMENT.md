# Building and deploying SecureRemoteDesktop (Windows)

## 1. Build (on the developer PC)

Requirements: JDK 17+ (JDK 24 is used here), Maven 3.9+, and Launch4j at `C:\Program Files (x86)\Launch4j` (otherwise pass `-Launch4jHome`).

```powershell
cd D:\projects\SecureRemoteDesktop\SecureRemoteDesktop
powershell -ExecutionPolicy Bypass -File build\build-exe.ps1 -BundleJre -Package
```

This runs all tests, builds both exes, bundles a trimmed Java runtime (`jre\`, about 45 MB), and writes:

| File | Contents | Install on |
|---|---|---|
| `dist\SecureRemoteDesktopServer-<ver>-win64.zip` | exe, jre, ffmpeg.exe | the monitoring PC (one) |
| `dist\SecureRemoteDesktopClient-<ver>-win64.zip` | exe, jre | every monitored PC |

With the bundled `jre\` the target PCs **don't need Java installed**. Without `-BundleJre` the exes use `JAVA_HOME`/`PATH` and need Java 11+ (client) or Java 17+ (server).

Other switches:
- `-Only client|server` builds just one app.
- `-SkipTests` skips the tests.
- `-Online` lets Maven download dependencies.

## 2. Install the server (monitoring PC)

1. Unzip to a permanent folder, e.g. `C:\SecureRemoteDesktop\Server\`. The folder must be writable: the database `clients.db`, `logs\`, `Screenshots\` and `Recordings\` are created there.
2. Run `SecureRemoteDesktopServer.exe`. It registers itself to start at Windows login.
3. Allow it through Windows Firewall when asked, or add a rule for inbound **TCP 2582** (Private network).
4. Optional: *Settings → Server Configuration* sets the pre-shared key, image quality, interval, auto-add and alternative server IPs.

## 3. Install the client (each monitored PC)

1. Unzip to a permanent folder, e.g. `C:\SecureRemoteDesktop\Client\` (writable: config, logs, watchdog script).
2. Run `SecureRemoteDesktopClient.exe` and enter the server IP and port 2582 when asked. They are saved to `client-config.properties`, so later starts are silent.
   To skip the prompt on mass deployment, put this `client-config.properties` next to the exe before the first start:
   ```properties
   server.ip=192.168.110.135
   server.port=2582
   autoBroadcast=true
   # must match the server's key if you changed it there
   security.psk=MySecretKey12345
   ```
3. The client registers a watchdog that restarts it at login and after crashes.
4. Allow inbound **TCP 2583** if you use peer viewing / broadcasting ("Connect to Another PC").
   To turn broadcasting on remotely, allow inbound **TCP 2584** and send the client's pre-shared key:
   `curl -X POST -H "X-SRD-Key: <security.psk>" http://<client-ip>:2584/broadcast` (or `GET .../broadcast?key=<psk>`).
   It answers `{"broadcasting":true}`, and the setting is remembered like the tray's *Start Broadcasting*.
   Change the port with `control.port=` in `client-config.properties`, or set `control.port=0` to turn the endpoint off.
5. With auto-add on (the default), the PC appears on the server's table. With auto-add off, add it on the server; the client tray shows *Not registered on server* until then.

## 3b. macOS clients (Apple Silicon M1–M4 and Intel)

Build both on Windows (needs Python 3 and internet the first time; JREs are cached in `build\.cache\jre`):

```powershell
powershell -ExecutionPolicy Bypass -File build\build-macos.ps1
```

| File | For |
|---|---|
| `dist\SecureRemoteDesktopClient-<ver>-macos-arm64.dmg` (and `.zip`) | Apple Silicon Macs (M1, M2, M3, M4) |
| `dist\SecureRemoteDesktopClient-<ver>-macos-x64.dmg` (and `.zip`) | Intel Macs |

Each contains `SecureRemoteDesktopClient.app` with a bundled Eclipse Temurin JRE 21 (no Java install needed), plus `Install` and `Uninstall` double-click scripts and a README. The `.dmg` is a real, compressed Apple disk image that mounts on double-click; the `.zip` is the same contents if you prefer. Building needs Python with `pycdlib` (`python -m pip install pycdlib`); the repo's `build/.cache/venv` already has it.

On the Mac — easiest install:
1. Double-click the `.dmg` to mount it (or double-click the `.zip` to unpack it).
2. Double-click **Install SecureRemoteDesktop.command**. It copies the app to Applications, clears
   the Gatekeeper quarantine (the app is unsigned), and launches it. If macOS blocks the script
   itself, right-click it → Open → Open.

On the Mac — manual install:
1. Drag `SecureRemoteDesktopClient.app` into **Applications**.
2. The app isn't notarized by Apple, so the first launch is blocked:
   - macOS 15+: open it once, then System Settings → Privacy & Security → **Open Anyway**.
   - macOS 13–14: right-click the app → Open → Open.
   - Or: `xattr -dr com.apple.quarantine /Applications/SecureRemoteDesktopClient.app`

Then:
- **First run:** enter the server IP. Settings live in `~/Library/Application Support/SecureRemoteDesktop/Client/`. The client runs in the menu bar (no Dock icon) and starts at login via `~/Library/LaunchAgents/com.srd.client.plist`.
- A smaller, signable `.dmg` with a custom window layout can also be made **on a Mac** with `build/make_dmg_native.sh`, but it isn't required — the one built here mounts and runs as-is.
4. **Permissions:** grant them in System Settings → Privacy & Security, then quit and reopen the app.
   - **Screen Recording** (macOS 15: *Screen & System Audio Recording*): needed for the picture.
   - **Accessibility:** needed for remote mouse and keyboard.
   - **Microphone:** asked automatically.
5. **System sound:** install [BlackHole](https://existential.audio/blackhole/) and route output through it; otherwise only the microphone is shared.

To push the server address to many Macs, place a `client-config.properties` in `~/Library/Application Support/SecureRemoteDesktop/Client/` before the first start.

macOS limitations:
- Pen drawings may appear in Zoom/Meet shares; they are hidden only on Windows.
- There is no VPN Guard status (it's a Windows app).
- Win-key/Alt+Tab forwarding from a Mac viewer is not available.

## 4. Upgrading from v2

v2 and v3 can't talk to each other, so upgrade the server and all clients together. On each old PC:
1. Create an empty file `.client-stop` in the old client folder; the old watchdog then exits.
2. End `javaw.exe` / `SecureRemoteDesktopClient.exe`.
3. Delete or replace the old folder, then install v3 as above. The new exe re-registers its own auto-start.

Until then, the server lists v2 PCs as **Outdated client**.

## 5. Updating to a newer v3 build

Stop the app, replace the exe (and `jre\` if it is in the zip), and start it again. Config, database and logs are kept.

## 6. Uninstall (client)

1. Create `.client-stop` in the client folder and end the process.
2. Delete the `SecureRemoteDesktopClient` value under `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`.
3. Delete the folder.

## Ports and requirements

| Port | Direction | Purpose |
|---|---|---|
| TCP 2582 | client → server | monitoring, live view, control, audio, VPN status |
| TCP 2583 | viewer → client | peer viewing / broadcasting (optional) |
| TCP 2584 | anyone → client | HTTP `/broadcast`: turns broadcasting on (needs the pre-shared key) |
| TCP 2599 | localhost only | client single-instance guard (no firewall rule needed) |

- **Windows:** 10 or 11, 64-bit. Pen overlays are hidden from Zoom/Meet screen shares only on Windows 10 version 2004 or later.
- **macOS/Linux client:** run the jar with `build/run-client.sh` (Java 11+). On macOS, grant Screen Recording and Accessibility permissions.
- **Security:** keep both ports on the LAN; the server only accepts private/loopback addresses. Change `security.psk` on the server and on every client for a private deployment.
