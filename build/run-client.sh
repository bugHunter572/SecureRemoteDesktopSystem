#!/usr/bin/env sh
# Runs the client agent on macOS / Linux from the shaded jar (Java 11+ required).
# Usage: build/run-client.sh            (after: mvn -o clean package -pl srd-client -am)
# macOS: grant "Screen Recording" and "Accessibility" permission to the Java binary on first run.
DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$DIR/srd-client/target/SecureRemoteDesktopClient.jar"
mkdir -p "$DIR/srd-client/bin"
cd "$DIR/srd-client/bin" || exit 1
exec java -Xms128m -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=10 -jar "$JAR" "$@"
