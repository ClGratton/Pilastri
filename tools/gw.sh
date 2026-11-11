#!/usr/bin/env bash
# One Gradle build at a time on this PC (2026-10-31: parallel agent builds exhausted the commit limit and crashed other apps).
# Usage: tools/gw.sh <gradle args>   e.g. tools/gw.sh -q :app:testDebugUnitTest --tests "xyz.grtlabs.pilastri.ai.*"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCK="$ROOT/.gradle/agent-build.lock"
mkdir -p "$ROOT/.gradle"
until mkdir "$LOCK" 2>/dev/null; do
  if [ -d "$LOCK" ] && [ $(( $(date +%s) - $(stat -c %Y "$LOCK") )) -gt 2700 ]; then rmdir "$LOCK" 2>/dev/null; fi
  sleep 5
done
trap 'rmdir "$LOCK" 2>/dev/null' EXIT
# Commit headroom gate (2026-11-01: 3.8 GB free at the start of the run; a build peaks around 3 GB). Wait up to 15 min for >= 3.0 GB free.
free_mb() { powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreeVirtualMemory/1024)" | tr -dc '0-9'; }
waited=0; F=$(free_mb)
while [ "${F:-0}" -lt 3000 ] && [ $waited -lt 900 ]; do sleep 30; waited=$((waited+30)); F=$(free_mb); done
echo "gw.sh: commit free ${F} MB before build" >&2
[ "${F:-0}" -lt 3000 ] && { echo "gw.sh: BLOCK commit free ${F} MB < 3000 after 15 min; not starting Gradle" >&2; exit 3; }
cd "$ROOT" && JAVA_HOME="/c/Program Files/Java/jdk-21" ./gradlew --no-daemon "$@"  # single-use daemon: frees its memory when the build ends
