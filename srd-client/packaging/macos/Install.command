#!/bin/sh
# Double-click this to install Secure Remote Desktop Client on this Mac.
# It stops a running copy, copies the app to /Applications, removes the "downloaded from the
# internet" quarantine flag (the app is not notarized by Apple), and launches it. No Terminal
# knowledge needed.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/SecureRemoteDesktopClient.app"
if [ ! -d "$APP" ]; then
    echo "Could not find SecureRemoteDesktopClient.app next to this installer." >&2
    echo "Open the disk image / unzip first, then run Install from there." >&2
    exit 1
fi

DEST="/Applications/SecureRemoteDesktopClient.app"

# Stop the running copy first (and its start-at-login job, which would relaunch it); otherwise the
# old version keeps running and the new one exits at once as a second instance.
launchctl bootout "gui/$(id -u)/com.srd.client" 2>/dev/null || true
pkill -f "SecureRemoteDesktopClient.app/Contents" 2>/dev/null || true
sleep 1
pkill -9 -f "SecureRemoteDesktopClient.app/Contents" 2>/dev/null || true

# Builds before 3.0.5 were unsigned; macOS may still hold their Screen Recording / Accessibility
# entries under an identity the new app doesn't match (the switch looks on but the app is refused).
# Unless a signed 3.0.5+ copy is being updated, clear them once after copying (tccutil only knows
# the bundle while it is installed). The signed app keeps its permissions across later updates.
RESET_PERMISSIONS=1
if [ -d "$DEST" ] && codesign -dv "$DEST" 2>&1 | grep -q "Identifier=com.srd.client"; then
    RESET_PERMISSIONS=0
fi

echo "Installing to $DEST ..."
rm -rf "$DEST" 2>/dev/null || sudo rm -rf "$DEST"
cp -R "$APP" /Applications/ 2>/dev/null || sudo cp -R "$APP" /Applications/

# Clear the quarantine flag so Gatekeeper lets the app run.
xattr -dr com.apple.quarantine "$DEST" 2>/dev/null || sudo xattr -dr com.apple.quarantine "$DEST" 2>/dev/null || true

/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$DEST" 2>/dev/null || true
if [ "$RESET_PERMISSIONS" = 1 ]; then
    echo "Clearing permissions left by older versions ..."
    for service in ScreenCapture Accessibility Microphone; do
        tccutil reset "$service" com.srd.client >/dev/null 2>&1 || true
    done
fi

echo "Launching ..."
open "$DEST"
echo "Done. Secure Remote Desktop is now in your Applications and runs from the menu bar."
echo "Allow Screen Recording and Accessibility when asked (System Settings > Privacy & Security),"
echo "then choose \"Quit & Reopen\" in the app's message."
