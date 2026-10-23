#!/bin/sh
# Uninstall Secure Remote Desktop Client (double-click in Finder; runs in Terminal).
echo "This removes Secure Remote Desktop Client from this Mac."
printf "Continue? [y/N] "
read answer
case "$answer" in y|Y|yes|YES) ;; *) echo "Cancelled."; exit 0 ;; esac

# Stop the start-at-login job first, otherwise it relaunches the app.
launchctl bootout "gui/$(id -u)/com.srd.client" 2>/dev/null
launchctl unload "$HOME/Library/LaunchAgents/com.srd.client.plist" 2>/dev/null
pkill -f "SecureRemoteDesktopClient.app/Contents" 2>/dev/null
pkill -f SecureRemoteDesktopClient.jar 2>/dev/null
rm -f "$HOME/Library/LaunchAgents/com.srd.client.plist"
# Forget the privacy permissions given to the app.
tccutil reset All com.srd.client >/dev/null 2>&1

for app in "/Applications/SecureRemoteDesktopClient.app" "$HOME/Applications/SecureRemoteDesktopClient.app"; do
    if [ -d "$app" ]; then
        rm -rf "$app" 2>/dev/null || sudo rm -rf "$app"
        echo "Removed $app"
    fi
done

printf "Also delete settings and logs (~/Library/Application Support/SecureRemoteDesktop)? [y/N] "
read answer
case "$answer" in
    y|Y|yes|YES) rm -rf "$HOME/Library/Application Support/SecureRemoteDesktop"; echo "Settings removed." ;;
esac
echo "Done. You can close this window."
