#!/usr/bin/env bash
# The ONLY way to put Pilastri on the Fold (AGENTS.md "The check is an algorithm, not an opinion"; "Using the user's phone").
# Refuses (non-zero exit, reason printed) unless ALL hold:
#   1. Pilastri is not the foreground app on the phone (an install kills it);
#   2. the JVM layout tests pass (LayoutConsistencyTest, ConsistencyTagsTest, LayoutTest, SourceRulesTest, TextClipTest, PeriodMotionTest);
#   3. the newest output/consistency-*/ report is newer than the fast APK and the checker re-run on its dumps has 0 deviations
#      outside tools/consistency-exceptions.json.
# Then: install -r the fast APK, verify lastUpdateTime changed, run `cmd package compile -m speed -f`.
# Never: run-as, uninstall, pm clear (the phone build is not debuggable; the app snapshots itself, data/UpdateSnapshot.kt).
# Usage: PHONE=192.168.1.74:42975 bash tools/install-fold.sh      (build the APK first: tools/gw.sh :app:assembleFast)
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
PHONE="${PHONE:-192.168.1.74:42975}"
refuse() { echo "install-fold: REFUSED: $*" >&2; exit 1; }

# adb: PATH first, then the Android SDK.
ADB="$(command -v adb 2>/dev/null || true)"
if [ -z "$ADB" ]; then
  # The project's own SDK location first (local.properties sdk.dir, e.g. F\:/Android/Sdk).
  SDKDIR="$(grep '^sdk.dir=' local.properties 2>/dev/null | cut -d= -f2- | tr '\\' '/' | sed 's#/:#:#; s#//*#/#g')"
  for c in "${SDKDIR:+$SDKDIR/platform-tools/adb.exe}" "${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe" "$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"; do
    [ -x "$c" ] && ADB="$c" && break
  done
fi
[ -n "$ADB" ] || refuse "adb not found on PATH or in %LOCALAPPDATA%/Android/Sdk/platform-tools"

# applicationId from app/build.gradle.kts; the fast build type sets no applicationIdSuffix, so the id is the same as debug (upgrade in place).
APP_ID="$(sed -n 's/.*applicationId *= *"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -n 1)"
[ -n "$APP_ID" ] || refuse "applicationId not found in app/build.gradle.kts"
SUFFIX="$(awk '/create\("fast"\)/{f=1} f&&/applicationIdSuffix/{print; exit} f&&/^        }/{exit}' app/build.gradle.kts | sed -n 's/.*applicationIdSuffix *= *"\([^"]*\)".*/\1/p')"
PKG="${APP_ID}${SUFFIX}"
APK="app/build/outputs/apk/fast/app-fast.apk"
[ -f "$APK" ] || refuse "$APK missing (bash tools/gw.sh :app:assembleFast)"

# 0. Phone reachable.
"$ADB" -s "$PHONE" get-state >/dev/null 2>&1 || refuse "phone $PHONE not reachable with adb (set PHONE=host:port)"

# 1. Never disturb Pilastri while the user is in it.
TOP="$("$ADB" -s "$PHONE" shell dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -i "topResumedActivity" || true)"
case "$TOP" in *"$APP_ID"*) refuse "Pilastri ($APP_ID) is on top on the phone; an install would kill it. Tell the user first." ;; esac

# 2. JVM layout tests.
mkdir -p output/fixwave
LOG="output/fixwave/install-fold-tests.log"
echo "install-fold: running the JVM layout tests (log: $LOG)"
bash tools/gw.sh :app:testDebugUnitTest --tests "*LayoutConsistencyTest*" --tests "*ConsistencyTagsTest*" --tests "*LayoutTest" --tests "*SourceRulesTest*" --tests "*TextClipTest*" --tests "*PeriodMotionTest*" >"$LOG" 2>&1
RC=$?
tail -n 15 "$LOG"
[ $RC -eq 0 ] || refuse "JVM layout tests failed (exit $RC); see $LOG and app/build/layout-consistency/*.md"

# 3. Emulator-dump consistency report: newer than the APK, and clean when re-run.
REPORT="$(ls -1dt output/consistency-*/ 2>/dev/null | head -n 1)"
[ -n "$REPORT" ] || refuse "no output/consistency-*/ folder: capture the dumps (tools/README.md) from THIS build first"
REPORT="${REPORT%/}"
[ -f "$REPORT/REPORT.md" ] || refuse "$REPORT/REPORT.md missing"
[ "$REPORT/REPORT.md" -nt "$APK" ] || refuse "$REPORT/REPORT.md is older than $APK: the dumps are not from this build; capture and run the checker again"
DENSITY="$(sed -n 's/.*density \([0-9.]*\),.*/\1/p' "$REPORT/REPORT.md" | head -n 1)"
[ -n "$DENSITY" ] || refuse "density not found in $REPORT/REPORT.md"
node tools/consistency-check.mjs "$REPORT" --density "$DENSITY" --out "$REPORT/REPORT-recheck.md" >"$REPORT/recheck.log" 2>&1
CC=$?
[ $CC -eq 0 ] || refuse "consistency checker on $REPORT reports deviations (exit $CC); see $REPORT/REPORT-recheck.md"

# 4. Install, verify, compile.
updated() { "$ADB" -s "$PHONE" shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' | sed -n 's/.*lastUpdateTime=//p' | head -n 1; }
BEFORE="$(updated)"
echo "install-fold: installing $APK on $PHONE (lastUpdateTime before: ${BEFORE:-none})"
"$ADB" -s "$PHONE" install -r "$APK" || refuse "adb install -r failed"
AFTER="$(updated)"
[ -n "$AFTER" ] && [ "$AFTER" != "$BEFORE" ] || refuse "lastUpdateTime did not change ($BEFORE -> $AFTER): the install did not replace the app"
echo "install-fold: lastUpdateTime $BEFORE -> $AFTER"
"$ADB" -s "$PHONE" shell cmd package compile -m speed -f "$PKG" || { echo "install-fold: WARNING: compile -m speed failed" >&2; exit 1; }
echo "install-fold: installed and compiled (speed). Not launched: the user opens it."
