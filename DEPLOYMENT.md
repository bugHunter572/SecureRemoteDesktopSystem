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
5. With auto-add on (the default), the PC appears on the server's table. With auto-add off, add it on the server; the client tray shows *Not registered on server* until then.

## 3b. macOS clients

The macOS packaging (`.app`/`.dmg` build) was removed; there is no ready-made Mac installer. For a dev run on macOS/Linux use `build/run-client.sh` (Java 11+) and grant *Screen Recording* and *Accessibility* in System Settings → Privacy & Security.

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
| TCP 2599 | localhost only | client single-instance guard (no firewall rule needed) |

- **Windows:** 10 or 11, 64-bit. Pen overlays are hidden from Zoom/Meet screen shares only on Windows 10 version 2004 or later.
- **macOS/Linux client:** run the jar with `build/run-client.sh` (Java 11+). On macOS, grant Screen Recording and Accessibility permissions.
- **Security:** keep both ports on the LAN; the server only accepts private/loopback addresses. Change `security.psk` on the server and on every client for a private deployment.
