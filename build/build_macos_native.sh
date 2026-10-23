#!/bin/sh
# Builds the macOS client's two native pieces as universal (Apple Silicon + Intel) binaries.
# RUN ON A MAC with Xcode or the Command Line Tools. The results are kept next to their sources, so
# package_macos.py can still package the app on Windows/Linux.
#   srd-client/packaging/macos/launcher/SecureRemoteDesktopClient  app launcher (runs Java in-process)
#   srd-client/packaging/macos/audio/srd-system-audio              computer-audio helper (ScreenCaptureKit)
#
# Usage: build/build_macos_native.sh
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="$ROOT/srd-client/packaging/macos"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Launcher (deployment target 11.0 = the app's minimum).
xcrun clang -O2 -Wall -arch arm64 -arch x86_64 -mmacosx-version-min=11.0 \
    -o "$PKG/launcher/SecureRemoteDesktopClient" "$PKG/launcher/launcher.c"

# Audio helper; ScreenCaptureKit is weak-linked and only used on macOS 13+.
for arch in arm64 x86_64; do
    xcrun swiftc -O -target "$arch-apple-macos11.0" -Xlinker -weak_framework -Xlinker ScreenCaptureKit \
        -o "$TMP/srd-system-audio-$arch" "$PKG/audio/SRDSystemAudio.swift"
done
lipo -create -output "$PKG/audio/srd-system-audio" "$TMP/srd-system-audio-arm64" "$TMP/srd-system-audio-x86_64"

for f in "$PKG/launcher/SecureRemoteDesktopClient" "$PKG/audio/srd-system-audio"; do
    chmod 755 "$f"
    lipo -info "$f"
done
