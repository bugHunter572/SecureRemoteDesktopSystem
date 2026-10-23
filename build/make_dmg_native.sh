#!/bin/sh
# Builds a small, compressed macOS .dmg from an already-built .app, using Apple's hdiutil.
# RUN THIS ON A MAC. (The Windows build produces a larger uncompressed .dmg via package_macos.py.)
#
# Usage:
#   build/make_dmg_native.sh <SecureRemoteDesktopClient.app> [output.dmg]
# e.g. after unpacking a dist/*.tar.gz:
#   build/make_dmg_native.sh SecureRemoteDesktopClient.app dist/SecureRemoteDesktopClient-macos.dmg
set -e
APP="$1"
OUT="${2:-SecureRemoteDesktopClient.dmg}"
[ -d "$APP" ] || { echo "Not a .app bundle: $APP" >&2; exit 1; }

STAGE="$(mktemp -d)"
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
# Keep the launcher and the bundled java executable, well, executable.
chmod +x "$STAGE/$(basename "$APP")/Contents/MacOS/"* 2>/dev/null || true

rm -f "$OUT"
hdiutil create -volname "Secure Remote Desktop" -srcfolder "$STAGE" \
    -fs HFS+ -format UDZO -imagekey zlib-level=9 "$OUT"
rm -rf "$STAGE"
echo "Created $OUT"
echo "Note: the app is unsigned. To distribute without Gatekeeper warnings, sign and notarize it:"
echo "  codesign --deep --force --sign \"Developer ID Application: <you>\" \"$APP\""
echo "  xcrun notarytool submit \"$OUT\" --keychain-profile <profile> --wait"
