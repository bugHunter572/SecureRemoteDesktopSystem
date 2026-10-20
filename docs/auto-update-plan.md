# Auto-update plan (Windows client, server, observer)

> Status: implemented (srd-common `com.srd.common.update`, `.github/workflows/release.yml`, `apply-update.ps1`). Remaining one-time setup: add the repo secret `SRD_UPDATE_PRIVATE_KEY`. Differences from the plan: manifests are signed `.properties` files instead of JSON; the swap helper is a PowerShell script, not a separate exe.

Goal: push a version tag to GitHub and every client, the server and the observer update themselves.

## Scope and branches
- Branches: `main` (server + client) and `observer-server` (observer build). Keep both; the observer is a server build, so the updater code lives in `main` and is merged into `observer-server`.
- Windows only. The macOS build was removed, so Macs get no updates and stay on their current version until a Mac path is added.
- Artifacts per release: `SecureRemoteDesktopClient-<v>-win64.zip`, `SecureRemoteDesktopServer-<v>-win64.zip`, `SecureRemoteDesktopObserver-<v>-win64.zip`.

## 1. CI (GitHub Actions, `windows-latest`)
1. Trigger: push of tag `v*`.
2. Check out `main` for client and server; check out `observer-server` for the observer.
3. Run `build\build-exe.ps1 -BundleJre -Package` (needs launch4j and a JRE; cache both).
4. Compute the SHA-256 of each zip.
5. Write `latest.json` per channel: `{ "version", "url", "sha256", "size", "minFrom" }`.
6. Sign `latest.json` with an Ed25519 private key stored as a repo secret; publish `latest.json` and `latest.json.sig` plus the zips as release assets.
7. `minFrom` marks releases that need a protocol-compatible predecessor (see section 5).

## 2. Hosting decision (needed from you)
- **Public repo or public release assets:** clients fetch directly; no secrets on client PCs. The zips contain no secrets (the PSK is entered per install).
- **Private repo:** clients cannot download without a token, and a token must not ship in the apps. Mirror the release to a static host or to the server on 33 instead.

## 3. Updater inside each app
- Check `latest.json` on startup and every ~4 hours, with random jitter so 25 PCs do not hit the host together.
- Verify the signature with an Ed25519 public key embedded in the app. If it fails, refuse and log it.
- Download the zip to a staging folder, check size and SHA-256, unpack.
- Never touch `client-config.properties`, `clients.db`, logs, recordings, or the bundled JRE (replace the JRE only when its version changed).
- Apply with a small helper (`srd-updater`): the app exits, the helper waits for it to stop, swaps the app files, restarts the app, and restores the old files if the new version does not report "started" within about 60 s.
- The client already refuses to run twice (loopback port 2599), and the build script refuses while the exe is running; the helper must stop the existing process the same way.
- Setting `update.enabled=false` in config turns it off; `update.channel` selects client, server or observer.

## 4. Optional: server as distribution point
Clients keep a connection to the server, so the server could download once and push the package to clients over it. Clients then need no internet access. It costs extra server code and makes 33 a single point of failure for updates. Start with direct download; add this later if clients lack internet.

## 5. Protocol compatibility
Pre-v3 clients were already rejected because both sides must upgrade together. Rules:
- Bump a `protocolVersion` only on wire changes; the server accepts N and N-1 for one release.
- Update order for a wire change: server first (accepts both), then clients.
- `minFrom` in `latest.json` stops a client from jumping across an incompatible gap.

## 6. Rollout and safety
- Stage 1: one test client and a test server on a second port (`-Dsrd.port`, `-Dsrd.instance`).
- Stage 2: a subset of PCs via a `ring` value in config; the `latest.json` for ring 2 lags ring 1 by a day.
- Kill switch: re-publish `latest.json` with the previous version (clients only move forward, so publish it as a higher version number that contains the old code).
- Log every update attempt and result on the client; show the result in the server table's version column.

## 7. Build order
1. `srd-common`: update check, signature verify, download with hash check.
2. `srd-updater`: helper process (Windows), with tests for swap and rollback.
3. Wire into client, server and observer startup and a menu entry "Check for updates".
4. GitHub Actions workflow and `latest.json` signing.
5. Dry run on a test machine, then ring 1, then everything.

## Open questions
1. Repo visibility / hosting (section 2).
2. Do Macs need an update path later? (Not covered here.)
3. Should the observer follow the same release version as the server?
