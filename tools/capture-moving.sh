#!/usr/bin/env bash
# Held-drag capture for the motion rules (CR-67 / CR-68). EMULATOR ONLY (adb -s emulator-5554); never a phone.
# Usage: bash tools/capture-moving.sh <name> <x1> <y1> <x2> <y2> [folder]
#        bash tools/capture-moving.sh <name> auto [viewport-index] [folder]   (drag start and hold read from the rest dump: centre of the index-th period-viewport)
# A fixed drag start must lie inside a period-viewport of the rest dump (tools/viewport-point.mjs check); otherwise the script stops with exit 3 and writes no moving dump.
#   <name>   dump base name, e.g. fold-pilastri  -> <folder>/<name>-rest.xml and <folder>/<name>-moving.xml
#   x1 y1    where the finger goes down (px, on the pager: the period-viewport); x2 y2 where it is held (px, e.g. 35 % of the pager width to the left)
#   folder   default output/consistency-<today>
# Sequence: rest dump, `input motionevent DOWN`, a few MOVE steps, moving dump (finger still down), `UP`. The checker pairs <name>-rest.xml with
# <name>-moving.xml and fails a moving dump identical to its rest dump (vacuity guard).
set -euo pipefail
ADB=${ADB:-/f/Android/Sdk/platform-tools/adb.exe}
SER=emulator-5554
export MSYS_NO_PATHCONV=1
name=${1:?name}; auto=0; if [ "${2:-}" = auto ]; then auto=1; vi=${3:-0}; dir=${4:-output/consistency-$(date +%F)}; else x1=${2:?x1}; y1=${3:?y1}; x2=${4:?x2}; y2=${5:?y2}; dir=${6:-output/consistency-$(date +%F)}; fi
mkdir -p "$dir"
dump() { "$ADB" -s $SER shell uiautomator dump /sdcard/ck-$1.xml >/dev/null && "$ADB" -s $SER pull /sdcard/ck-$1.xml "$dir/$name-$1.xml" >/dev/null; }
dump rest
here=$(dirname "$0")
if [ $auto = 1 ]; then read -r x1 y1 x2 y2 < <(node "$here/viewport-point.mjs" "$dir/$name-rest.xml" auto "$vi"); else node "$here/viewport-point.mjs" "$dir/$name-rest.xml" check "$x1" "$y1"; fi
"$ADB" -s $SER shell input motionevent DOWN "$x1" "$y1"
for i in 1 2 3 4; do
  x=$(( x1 + (x2 - x1) * i / 4 )); y=$(( y1 + (y2 - y1) * i / 4 ))
  "$ADB" -s $SER shell input motionevent MOVE "$x" "$y"
done
sleep 0.3
trap '"$ADB" -s $SER shell input motionevent UP "$x2" "$y2" >/dev/null 2>&1 || true' EXIT
dump moving
echo "captured $dir/$name-rest.xml and $dir/$name-moving.xml (finger released on exit)"
