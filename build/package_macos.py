#!/usr/bin/env python3
"""
Packages the client as a macOS .app for Apple Silicon (arm64, M1-M4) and Intel (x64).

Produces, per architecture:
    dist/SecureRemoteDesktopClient-<ver>-macos-<arch>.zip      <- double-click installer on macOS
    dist/SecureRemoteDesktopClient-<ver>-macos-<arch>.tar.gz   <- same app, smaller, for Terminal

Both contain:
    SecureRemoteDesktopClient.app/Contents/
        Info.plist, PkgInfo
        MacOS/SecureRemoteDesktopClient      native launcher (universal; runs Java in-process)
        MacOS/srd-system-audio               computer-audio helper (universal, ScreenCaptureKit)
        Resources/SecureRemoteDesktopClient.jar, AppIcon.icns
        runtime/Contents/Home/...            Eclipse Temurin JRE 21 for that architecture
    README-macOS.txt, Uninstall SecureRemoteDesktop.command

Built on any OS (the audio helper is prebuilt by build/build_macos_native.sh). On a Mac the .dmg is
made with hdiutil (compressed, the format macOS expects); elsewhere it is an ISO wrapped in UDIF. The .zip is what macOS opens on double-click (Archive Utility), with Unix
permissions preserved so the launcher and bundled `java` stay executable. A true .dmg can only be
made with Apple's hdiutil on a Mac (build/make_dmg_native.sh) - macOS refuses to mount a disk image
that wasn't produced in its own format. JREs are fetched from the Adoptium API, SHA-256 verified,
cached in build/.cache/jre, and only read here.

Usage: python build/package_macos.py --jar srd-client/target/SecureRemoteDesktopClient.jar --version 3.0.2
"""
import argparse
import hashlib
import io
import json
import os
import sys
import tarfile
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = os.path.join(ROOT, "srd-client", "packaging", "macos")
AUDIO_HELPER = os.path.join(PKG, "audio", "srd-system-audio")
LAUNCHER = os.path.join(PKG, "launcher", "SecureRemoteDesktopClient")
BUNDLE_ID = "com.srd.client"
APP = "SecureRemoteDesktopClient.app"
ARCHES = {"arm64": "aarch64", "x64": "x64"}  # our label -> Adoptium architecture
JRE_FEATURE = 21

README = """Secure Remote Desktop Client {version} for macOS ({arch})
=====================================================

Install (easiest)
  1. Open the .dmg (double-click) - or, from the .zip, double-click to unpack it.
  2. Double-click "Install SecureRemoteDesktop.command". It copies the app to Applications,
     clears the Gatekeeper quarantine (the app is not signed by Apple), and launches it.
     If macOS blocks the installer script itself, right-click it > Open > Open.

Install (manual)
  1. Drag SecureRemoteDesktopClient.app to your Applications folder.
  2. The app is not notarized by Apple, so on first start macOS blocks it:
       macOS 15+: try to open it once, then System Settings > Privacy & Security > "Open Anyway".
       macOS 13-14: right-click the app > Open > Open.
     Or, in Terminal:  xattr -dr com.apple.quarantine /Applications/SecureRemoteDesktopClient.app
  3. Start it. It asks for the server IP (port 2582) on the first run; settings are kept in
     ~/Library/Application Support/SecureRemoteDesktop/Client/client-config.properties
     It runs in the menu bar (no Dock icon), starts automatically at login, and comes back by
     itself if it is closed - Exit in its menu restarts it, like on Windows
     (~/Library/LaunchAgents/com.srd.client.plist). To remove it, use the Uninstall command.

Permissions (System Settings > Privacy & Security), then quit and restart the app:
  - Screen Recording (macOS 15: "Screen & System Audio Recording") - required to show the screen.
  - Accessibility - required for remote mouse/keyboard control.
  Nothing else needs installing: Java and the audio helper are inside the app.

Sound: viewers hear this Mac's computer audio (macOS 13 Ventura or later; it uses the Screen
Recording permission). The microphone is never used.

Drawing: viewers can't draw on a Mac's screen (the pen button is hidden for Mac broadcasters, and
strokes from older viewers are removed right away). A Mac viewing a Windows PC can draw as usual.

Uninstall: launchctl unload ~/Library/LaunchAgents/com.srd.client.plist; delete that file, the app,
and ~/Library/Application Support/SecureRemoteDesktop.
"""


def log(msg):
    print(msg, flush=True)


def fetch_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": "srd-build"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url, dest, expected_size, attempts=6):
    """Downloads with resume: dropped connections (seen as IncompleteRead / short reads) continue
    from the bytes already on disk via an HTTP Range request instead of restarting or, worse,
    silently keeping a truncated file."""
    if os.path.exists(dest):
        os.remove(dest)
    for attempt in range(1, attempts + 1):
        have = os.path.getsize(dest) if os.path.exists(dest) else 0
        if expected_size and have >= expected_size:
            break
        headers = {"User-Agent": "srd-build"}
        if have:
            headers["Range"] = "bytes=%d-" % have
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=120) as r:
                if have and r.status != 206:  # server ignored Range: start over
                    have = 0
                with open(dest, "ab" if have else "wb") as out:
                    while True:
                        b = r.read(1 << 20)
                        if not b:
                            break
                        out.write(b)
        except Exception as e:  # IncompleteRead, timeouts, resets
            log("      attempt %d interrupted at %.1f MB: %s" % (attempt, os.path.getsize(dest) / 1e6 if os.path.exists(dest) else 0, e))
            time.sleep(min(10, 2 * attempt))
            continue
        size = os.path.getsize(dest)
        if not expected_size or size >= expected_size:
            break
        log("      attempt %d ended early at %.1f of %.1f MB, resuming" % (attempt, size / 1e6, expected_size / 1e6))
    size = os.path.getsize(dest) if os.path.exists(dest) else 0
    if expected_size and size != expected_size:
        raise SystemExit("download incomplete: %d of %d bytes from %s" % (size, expected_size, url))


def get_jre(adoptium_arch, cache):
    """Returns (path, version_name) of the verified Temurin JRE tarball for macOS/<arch>."""
    os.makedirs(cache, exist_ok=True)
    meta_url = ("https://api.adoptium.net/v3/assets/latest/{f}/hotspot?architecture={a}"
                "&image_type=jre&os=mac&vendor=eclipse").format(f=JRE_FEATURE, a=adoptium_arch)
    assets = fetch_json(meta_url)
    if not assets:
        raise SystemExit("no Temurin JRE %d for mac/%s" % (JRE_FEATURE, adoptium_arch))
    pkg = assets[0]["binary"]["package"]
    name, link, checksum = pkg["name"], pkg["link"], pkg["checksum"].lower()
    path = os.path.join(cache, name)
    if os.path.isfile(path) and sha256(path) == checksum:
        log("    cached %s" % name)
    else:
        log("    downloading %s (%.1f MB)" % (name, pkg.get("size", 0) / 1e6))
        tmp = path + ".part"
        download(link, tmp, int(pkg.get("size", 0)))
        got = sha256(tmp)
        if got != checksum:
            os.remove(tmp)
            raise SystemExit("checksum mismatch for %s: %s != %s" % (name, got, checksum))
        os.replace(tmp, path)
    return path, assets[0].get("release_name", name)


def add_bytes(tar, name, data, mode=0o644, mtime=None):
    ti = tarfile.TarInfo(name)
    ti.size = len(data)
    ti.mode = mode
    ti.mtime = mtime or int(time.time())
    ti.uname = ti.gname = ""
    tar.addfile(ti, io.BytesIO(data))


def add_dir(tar, name, mtime):
    ti = tarfile.TarInfo(name)
    ti.type = tarfile.DIRTYPE
    ti.mode = 0o755
    ti.mtime = mtime
    tar.addfile(ti)


# A member of the .app payload: ('dir'|'file'|'symlink', iso/tar path, bytes-or-None, mode, linkname).
def collect_members(arch, jar, version, jre_path):
    with open(os.path.join(PKG, "Info.plist"), "r", encoding="utf-8") as f:
        plist = f.read().replace("@VERSION@", version).encode("utf-8")
    if not os.path.isfile(LAUNCHER):
        raise SystemExit("launcher missing: %s (run build/build_macos_native.sh on a Mac)" % LAUNCHER)
    with open(LAUNCHER, "rb") as f:
        launcher = f.read()
    with open(os.path.join(PKG, "AppIcon.icns"), "rb") as f:
        icns = f.read()
    with open(os.path.join(PKG, "uninstall.command"), "rb") as f:
        uninstall = f.read().replace(b"\r\n", b"\n")
    with open(os.path.join(PKG, "Install.command"), "rb") as f:
        install = f.read().replace(b"\r\n", b"\n")
    with open(jar, "rb") as f:
        jar_bytes = f.read()
    if not os.path.isfile(AUDIO_HELPER):
        raise SystemExit("audio helper missing: %s (run build/build_macos_native.sh on a Mac)" % AUDIO_HELPER)
    with open(AUDIO_HELPER, "rb") as f:
        audio_helper = f.read()

    m = []
    for d in ["", "Contents", "Contents/MacOS", "Contents/Resources", "Contents/runtime"]:
        m.append(("dir", (APP + "/" + d).rstrip("/"), None, 0o755, None))
    m.append(("file", APP + "/Contents/Info.plist", plist, 0o644, None))
    m.append(("file", APP + "/Contents/PkgInfo", b"APPL????", 0o644, None))
    m.append(("file", APP + "/Contents/MacOS/SecureRemoteDesktopClient", launcher, 0o755, None))
    m.append(("file", APP + "/Contents/MacOS/srd-system-audio", audio_helper, 0o755, None))
    m.append(("file", APP + "/Contents/Resources/AppIcon.icns", icns, 0o644, None))
    m.append(("file", APP + "/Contents/Resources/SecureRemoteDesktopClient.jar", jar_bytes, 0o644, None))
    m.append(("file", "README-macOS.txt", README.format(version=version, arch=arch).encode("utf-8"), 0o644, None))
    m.append(("file", "Install SecureRemoteDesktop.command", install, 0o755, None))
    m.append(("file", "Uninstall SecureRemoteDesktop.command", uninstall, 0o755, None))

    files = 0
    with tarfile.open(jre_path, "r:gz") as src:
        for tm in src:
            parts = tm.name.split("/", 1)
            if len(parts) < 2 or not parts[1]:
                continue
            rel = parts[1]
            if rel.startswith("/") or ".." in rel.split("/"):
                raise SystemExit("unsafe path in JRE archive: " + tm.name)
            path = APP + "/Contents/runtime/" + rel
            if tm.issym():
                m.append(("symlink", path, None, 0o755, tm.linkname))
            elif tm.isdir():
                m.append(("dir", path, None, tm.mode or 0o755, None))
            elif tm.isfile():
                m.append(("file", path, src.extractfile(tm).read(), tm.mode or 0o644, None))
                files += 1
    return m, files


def write_tar(target, members):
    now = int(time.time())
    with tarfile.open(target, "w:gz", compresslevel=9, format=tarfile.PAX_FORMAT) as out:
        for kind, path, data, mode, link in members:
            if kind == "dir":
                ti = tarfile.TarInfo(path)
                ti.type = tarfile.DIRTYPE
                ti.mode, ti.mtime = mode, now
                out.addfile(ti)
            elif kind == "symlink":
                ti = tarfile.TarInfo(path)
                ti.type = tarfile.SYMTYPE
                ti.linkname = link
                ti.mode, ti.mtime = mode, now
                out.addfile(ti)
            else:
                ti = tarfile.TarInfo(path)
                ti.size, ti.mode, ti.mtime = len(data), mode, now
                ti.uname = ti.gname = ""
                out.addfile(ti, io.BytesIO(data))


def write_zip(target, members):
    """
    Zips the .app (plus README and uninstaller) with Unix permissions preserved, so the launcher
    and the bundled `java` stay executable after macOS extracts it. This is the format macOS opens
    on double-click (Archive Utility): you get the ready-to-run .app, which you drag to
    /Applications. A true compressed .dmg can only be made with Apple's hdiutil on a Mac
    (build/make_dmg_native.sh); macOS will not mount a non-Apple disk image.
    """
    import zipfile
    dosdate = (2026, 1, 1, 0, 0, 0)
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for kind, path, data, mode, link in members:
            if kind == "symlink":
                # Store as a real symlink entry (S_IFLNK); the target is the content.
                zi = zipfile.ZipInfo(path, dosdate)
                zi.external_attr = (0o120000 | (mode & 0o777)) << 16
                z.writestr(zi, link)
            elif kind == "dir":
                zi = zipfile.ZipInfo(path.rstrip("/") + "/", dosdate)
                zi.external_attr = (0o040000 | (mode & 0o777)) << 16 | 0x10
                z.writestr(zi, b"")
            else:
                zi = zipfile.ZipInfo(path, dosdate)
                zi.external_attr = (0o100000 | (mode & 0o777)) << 16
                zi.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(zi, data)
    return target


def build_iso_bytes(members):
    """Builds an ISO9660 + Rock Ridge image (app with +x modes and an Applications drop-link)."""
    try:
        import pycdlib
    except ImportError:
        return None
    iso = pycdlib.PyCdlib()
    iso.new(interchange_level=3, rock_ridge="1.09", vol_ident="SecureRemoteDesktop")
    counters = {"d": 0, "f": 0}
    iso_paths = {}

    def iso_name(is_dir):
        key = "d" if is_dir else "f"
        counters[key] += 1
        return "/%s%d%s" % ("DIR" if is_dir else "FILE", counters[key], "" if is_dir else ".;1")

    def parent_iso(path):
        return iso_paths.get(path.rsplit("/", 1)[0] if "/" in path else "", "")

    for kind, path, data, mode, link in members:
        name = path.rsplit("/", 1)[-1]
        if kind == "dir":
            ip = parent_iso(path) + iso_name(True)
            iso_paths[path] = ip
            iso.add_directory(ip, rr_name=name, file_mode=0o040000 | mode)
        elif kind == "symlink":
            iso.add_symlink(parent_iso(path) + iso_name(False), rr_symlink_name=name, rr_path=link)
        else:
            ip = parent_iso(path) + iso_name(False)
            iso.add_fp(io.BytesIO(data), len(data), ip, rr_name=name, file_mode=0o100000 | mode)
    iso.add_symlink("/APPS.;1", rr_symlink_name="Applications", rr_path="/Applications")
    buf = io.BytesIO()
    iso.write_fp(buf)
    iso.close()
    return buf.getvalue()


def stage_members(stage, members):
    """Writes the members into a folder with their modes and symlinks intact."""
    for kind, path, data, mode, link in members:
        dest = os.path.join(stage, path)
        if kind == "dir":
            os.makedirs(dest, exist_ok=True)
            os.chmod(dest, mode | 0o700)
        elif kind == "symlink":
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            os.symlink(link, dest)
        else:
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "wb") as f:
                f.write(data)
            os.chmod(dest, mode)


def sign_app(app):
    """
    Ad-hoc signs the bundle (no Apple account needed) so it has ONE code identity, com.srd.client.
    macOS privacy permissions (Screen Recording, Accessibility) are recorded against the app's
    designated requirement; pinning it to the bundle identifier keeps a permission valid across
    updates (a plain ad-hoc signature would be tied to this exact build's hash). Inner code first:
    the audio helper and the Java runtime, then the app, which seals them.
    """
    import subprocess
    run = lambda *a: subprocess.run(["codesign", "--force", "--sign", "-", "--timestamp=none"] + list(a), check=True)
    run(os.path.join(app, "Contents", "MacOS", "srd-system-audio"))
    run("--deep", os.path.join(app, "Contents", "runtime"))
    run("--identifier", BUNDLE_ID, "-r", '=designated => identifier "%s"' % BUNDLE_ID, app)
    subprocess.run(["codesign", "--verify", "--deep", "--strict", app], check=True)


def build_on_mac(members, dmg_target, zip_target):
    """On a Mac: stage, sign, then a compressed UDZO .dmg (hdiutil, with an Applications drop-link)
    and a .zip, both made from the same signed files."""
    import shutil
    import subprocess
    import tempfile
    stage = tempfile.mkdtemp(prefix="srd-dmg-")
    try:
        stage_members(stage, members)
        sign_app(os.path.join(stage, APP))
        top = sorted(os.listdir(stage))
        for t in (zip_target, dmg_target):
            if os.path.exists(t):
                os.remove(t)
        subprocess.run(["zip", "-qry", "-X", zip_target] + top, cwd=stage, check=True)
        os.symlink("/Applications", os.path.join(stage, "Applications"))
        subprocess.run(["hdiutil", "create", "-quiet", "-volname", "Secure Remote Desktop", "-srcfolder", stage,
                        "-fs", "HFS+", "-format", "UDZO", "-imagekey", "zlib-level=9", dmg_target], check=True)
    finally:
        shutil.rmtree(stage, ignore_errors=True)


def build(arch, jar, version, out_dir, cache):
    jre_path, release = get_jre(ARCHES[arch], cache)
    members, files = collect_members(arch, jar, version, jre_path)
    dmg_target = os.path.join(out_dir, "SecureRemoteDesktopClient-%s-macos-%s.dmg" % (version, arch))
    zip_target = os.path.join(out_dir, "SecureRemoteDesktopClient-%s-macos-%s.zip" % (version, arch))

    if sys.platform == "darwin" and os.path.exists("/usr/bin/hdiutil"):
        build_on_mac(members, dmg_target, zip_target)
        log("==> %s (%.1f MB, signed com.srd.client; double-click, then run Install)"
            % (os.path.relpath(dmg_target, ROOT), os.path.getsize(dmg_target) / 1e6))
    else:
        # Elsewhere (unsigned): a UDIF .dmg wrapping an ISO that carries the app with +x bits and
        # an Applications drop-link. Signing needs codesign, i.e. a Mac.
        import udif
        iso = build_iso_bytes(members)
        if iso is not None:
            udif.write_dmg(iso, dmg_target)
            log("==> %s (%.1f MB, double-click to mount, then drag the app to Applications)"
                % (os.path.relpath(dmg_target, ROOT), os.path.getsize(dmg_target) / 1e6))
        else:
            log("    (pycdlib not installed; skipping .dmg - run: python -m pip install pycdlib)")
        write_zip(zip_target, members)
    log("==> %s (%.1f MB, JRE %s, %d files - alternative: double-click to unzip, run Install)"
        % (os.path.relpath(zip_target, ROOT), os.path.getsize(zip_target) / 1e6, release, files))
    return zip_target


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jar", default=os.path.join(ROOT, "srd-client", "target", "SecureRemoteDesktopClient.jar"))
    ap.add_argument("--version", required=True)
    ap.add_argument("--out", default=os.path.join(ROOT, "dist"))
    ap.add_argument("--cache", default=os.path.join(ROOT, "build", ".cache", "jre"))
    ap.add_argument("--arch", choices=["arm64", "x64", "both"], default="both")
    a = ap.parse_args()
    if not os.path.isfile(a.jar):
        raise SystemExit("client jar not found: %s (build it first)" % a.jar)
    os.makedirs(a.out, exist_ok=True)
    for arch in (["arm64", "x64"] if a.arch == "both" else [a.arch]):
        log("macOS %s:" % arch)
        build(arch, a.jar, a.version, a.out, a.cache)
    return 0


if __name__ == "__main__":
    sys.exit(main())
