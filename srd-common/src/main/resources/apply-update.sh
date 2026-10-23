#!/bin/bash
# Swaps a staged Secure Remote Desktop macOS update into the installed app once the app has quit.
# Started by the app (com.srd.common.update.UpdateService) as a one-off launchd job; logs to
# <data>/update/apply.log.
#   --app      the installed bundle, e.g. /Applications/SecureRemoteDesktopClient.app
#   --zip      the downloaded package, already checked against the signed manifest
#   --version  the version the package must report
#   --data     the app's data folder (the update folder lives in it)
#   --plist    the LaunchAgent that starts the app (com.srd.client)
#   1. stops the LaunchAgent so KeepAlive can't relaunch the old copy, unpacks the package with ditto
#      (keeps the executable bits that Java's unzip drops) and checks it
#   2. waits for the app to quit
#   3. moves the old bundle to update/backup and copies the new one in; settings, logs and the
#      database live in the data folder and are never touched
#   4. starts the app through its LaunchAgent and waits for update/ok.txt = this version
#   5. no marker in time -> stops it, puts the old bundle back, starts it, marks the version failed
set -u

APP=""; ZIP=""; VERSION=""; DATA=""; PLIST=""
while [ $# -ge 2 ]; do
    case "$1" in
        --app) APP="$2" ;;
        --zip) ZIP="$2" ;;
        --version) VERSION="$2" ;;
        --data) DATA="$2" ;;
        --plist) PLIST="$2" ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
    shift 2
done
if [ -z "$APP" ] || [ -z "$ZIP" ] || [ -z "$VERSION" ] || [ -z "$DATA" ]; then
    echo "usage: apply-update.sh --app <bundle> --zip <zip> --version <v> --data <dir> --plist <plist>" >&2
    exit 2
fi

LABEL=com.srd.client
DOMAIN="gui/$(id -u)"
UPD="$DATA/update"
BACKUP="$UPD/backup"
STAGE="$UPD/stage"
OK="$UPD/ok.txt"
BIN="$APP/Contents/MacOS/SecureRemoteDesktopClient"
HEALTHY_WAIT=150

mkdir -p "$UPD"
log() { printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$UPD/apply.log"; }
app_running() { pgrep -f "$BIN" > /dev/null 2>&1; }
stop_agent() { launchctl bootout "$DOMAIN/$LABEL" > /dev/null 2>&1; }
start_agent() {
    if [ -n "$PLIST" ] && [ -f "$PLIST" ]; then
        launchctl bootstrap "$DOMAIN" "$PLIST" > /dev/null 2>&1
        launchctl kickstart "$DOMAIN/$LABEL" > /dev/null 2>&1
    else
        /usr/bin/open -a "$APP"
    fi
}
mark_failed() { date '+%Y-%m-%dT%H:%M:%S' > "$UPD/failed-$VERSION"; }
restore_old() { rm -rf "$APP"; mv "$BACKUP/app" "$APP"; }

# Failure while the old app is still in place: mark the version, start the old app again, stop.
fail_early() {
    log "update to $VERSION failed: $*"
    mark_failed
    start_agent
    exit 1
}

log "update to $VERSION from $ZIP"
stop_agent

# 1. unpack and check the package before anything is replaced
rm -rf "$STAGE"; mkdir -p "$STAGE"
if ! /usr/bin/ditto -x -k "$ZIP" "$STAGE" >> "$UPD/apply.log" 2>&1; then
    fail_early "cannot unpack the package"
fi
NEW="$STAGE/SecureRemoteDesktopClient.app"
NEW_VERSION="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$NEW/Contents/Info.plist" 2>/dev/null)"
if [ ! -x "$NEW/Contents/MacOS/SecureRemoteDesktopClient" ] \
    || [ ! -f "$NEW/Contents/runtime/Contents/Home/lib/libjli.dylib" ] \
    || [ "$NEW_VERSION" != "$VERSION" ]; then
    fail_early "the package does not contain a $VERSION app"
fi

# 2. wait for the app to quit (it quits right after starting this script); stop the agent again in
#    case launchd relaunched the old copy, and ask the app to quit if it takes too long
waited=0
while app_running; do
    stop_agent
    if [ "$waited" -ge 20 ]; then
        log "the app is still running; asking it to quit"
        pkill -f "$BIN" > /dev/null 2>&1
    fi
    if [ "$waited" -ge 40 ]; then
        fail_early "the app did not quit"
    fi
    sleep 1
    waited=$((waited + 1))
done
sleep 1

# 3. keep the old bundle, copy the new one in
rm -rf "$BACKUP"; mkdir -p "$BACKUP"
if ! mv "$APP" "$BACKUP/app"; then
    fail_early "cannot move the old app aside"
fi
if ! /usr/bin/ditto "$NEW" "$APP"; then
    log "cannot copy the new app; restoring the old one"
    restore_old
    fail_early "cannot copy the new app"
fi
xattr -dr com.apple.quarantine "$APP" > /dev/null 2>&1
log "app replaced with $VERSION"

# 4. start it through the LaunchAgent and wait for the health marker
rm -f "$OK"
start_agent
deadline=$(( $(date +%s) + HEALTHY_WAIT ))
healthy=0
while [ "$(date +%s)" -lt "$deadline" ]; do
    if [ -f "$OK" ] && [ "$(tr -d '[:space:]' < "$OK")" = "$VERSION" ]; then
        healthy=1
        break
    fi
    sleep 2
done

if [ "$healthy" = 1 ]; then
    log "update to $VERSION OK"
    rm -rf "$STAGE" "$UPD/work" "$BACKUP"
    exit 0
fi

# 5. roll back
log "the new app did not report healthy; rolling back"
stop_agent
pkill -f "$BIN" > /dev/null 2>&1
sleep 2
restore_old
mark_failed
start_agent
log "rolled back to the previous version"
exit 1
