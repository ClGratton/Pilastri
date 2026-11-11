#!/usr/bin/env bash
# Records what Pilastri does on the user's phone while the user walks a flow (e.g. setup), for later analysis.
# Read-only on the phone: only logcat reads and dumpsys reads, no input, no install, no app data access.
# Privacy: the raw files stay on this PC under output/ (git-ignored) and are never pasted into AI context;
# analysis reads them through grep for tags, exceptions, timings and screen names only, never values.
# Usage: PHONE=<serial> bash tools/setup-log.sh [seconds]   (default 7200)
set -u
A="${ADB:-/f/Android/Sdk/platform-tools/adb.exe}"
P="${PHONE:?set PHONE to the phone serial}"
PKG=xyz.grtlabs.pilastri
SECS="${1:-7200}"
OUT="output/setup-log-$(date +%Y%m%d-%H%M)"
mkdir -p "$OUT"
UID_="$("$A" -s "$P" shell pm list packages -U "$PKG" | tr -d '\r' | sed -n 's/.*uid:\([0-9]*\).*/\1/p')"
echo "uid=$UID_ out=$OUT start=$(date +%T)" | tee "$OUT/info.txt"
"$A" -s "$P" logcat -c 2>/dev/null
# 1) everything the app process logs (all levels), by uid
timeout "$SECS" "$A" -s "$P" logcat -v threadtime --uid="$UID_" > "$OUT/app.log" 2>&1 &
# 2) crashes from any process
timeout "$SECS" "$A" -s "$P" logcat -v threadtime -b crash > "$OUT/crash.log" 2>&1 &
# 3) activity / window transitions of the app (screen names, no content)
timeout "$SECS" "$A" -s "$P" logcat -v threadtime -b events -e "$PKG" > "$OUT/events.log" 2>&1 &
# 4) every 5 s: which activity is on top (names only)
( end=$(( $(date +%s) + SECS )); last="";
  while [ "$(date +%s)" -lt "$end" ]; do
    t="$("$A" -s "$P" shell dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -m1 topResumedActivity | sed 's/.*u0 //; s/ t[0-9]*}//')"
    [ "$t" != "$last" ] && { echo "$(date +%T) $t" >> "$OUT/top.log"; last="$t"; }
    sleep 5
  done ) &
wait
echo "end=$(date +%T)" >> "$OUT/info.txt"
