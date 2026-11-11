#!/usr/bin/env bash
# Generic durable-log reader (criterion K29): `phone-log.sh <serial> pull|watch`. Config: a shell file named by PHONE_LOG_CONFIG
# (or env): PKG (package), FOLDER (app-owned external files folder, relative to Android/data/<pkg>/files), TAG (logcat tag),
# FAIL_RE (extended regex of failure lines), ADB (adb path), OUT (output root, default output/phone-log).
# Read-only on the device: logcat reads and `adb pull` only; nothing that changes the screen or the focused app.
# Prints counts and one masked line, never log content.
set -u
[ -n "${PHONE_LOG_CONFIG:-}" ] && . "$PHONE_LOG_CONFIG"
export MSYS_NO_PATHCONV=1
S="${1:?serial}"; CMD="${2:?pull|watch}"
A="${ADB:-adb}"; PKG="${PKG:?PKG}"; FOLDER="${FOLDER:-log}"; TAG="${TAG:?TAG}"; OUT="${OUT:-output/phone-log}"
FAIL_RE="${FAIL_RE:-outcome=failed|outcome=denied|cause=error|error #[0-9]+ .* kept=no|FATAL EXCEPTION}"
MASK='s/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+/<mail>/g; s/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f-]{20,}/<uuid>/g; s/[A-Za-z0-9_-]{40,}/<long>/g; s/[A-Z]{2}[0-9]{2}[A-Z0-9]{10,30}/<iban>/g; s/[0-9]{8,}/<num>/g'
REMOTE="/sdcard/Android/data/$PKG/files/$FOLDER"
# Exit codes of pull: 0 ok or no log yet, 3 device unreachable, 4 copy failed although files exist, 5 copied line count differs from the device's.
# PHONE_LOG_FILEWISE=1 forces the file-by-file copy (test hook).

adb_err() { # first adb error reduced to a name, no paths, no content
  local e; e=$(printf '%s' "$1" | grep -Eo 'Permission denied|device offline|not found|no devices[^.]*|closed|protocol fault|Read-only|No such file or directory|unauthorized|timed out' | head -1)
  printf '%s' "${e:-unknown}" | tr ' ' '_'
}

pull() {
  local d="$OUT/$(date +%Y%m%d-%H%M%S)" st names n err e dev pc fails files
  st=$("$A" -s "$S" get-state 2>&1 | tr -d '\r' | head -1)
  if [ "$st" != "device" ]; then echo "pull failed reason=device_unreachable serial=$S state=$(adb_err "$st")"; return 3; fi
  names=$("$A" -s "$S" shell "ls -1 $REMOTE 2>/dev/null" 2>/dev/null | tr -d '\r' | grep -v '^$')
  n=$(printf '%s' "$names" | grep -c .)
  if [ "$n" -eq 0 ]; then echo "pulled files=0 reason=no_log_yet"; return 0; fi
  mkdir -p "$d"
  err=""
  if [ "${PHONE_LOG_FILEWISE:-0}" = 1 ]; then err="forced"; else
    err=$("$A" -s "$S" pull "$REMOTE/." "$d/" 2>&1 >/dev/null) && err=""
  fi
  if [ -n "$err" ]; then
    local bad=0 f
    while IFS= read -r f; do
      e=$("$A" -s "$S" pull "$REMOTE/$f" "$d/$f" 2>&1 >/dev/null) || { bad=1; err="$e"; }
    done <<< "$names"
    if [ "$bad" = 1 ]; then echo "pull failed reason=copy_failed adb_error=$(adb_err "$err") dir=$d files_on_device=$n"; return 4; fi
  fi
  dev=$("$A" -s "$S" shell "cat $REMOTE/* 2>/dev/null | wc -l" 2>/dev/null | tr -d '\r ')
  files=$(ls "$d" | wc -l); pc=$(cat "$d"/* 2>/dev/null | wc -l | tr -d ' ')
  fails=$(cat "$d"/* 2>/dev/null | grep -Ec "$FAIL_RE")
  if [ "$dev" != "$pc" ]; then echo "pull failed reason=line_mismatch dir=$d files=$files device_lines=$dev pc_lines=$pc"; return 5; fi
  echo "pulled dir=$d files=$files lines=$pc device_lines=$dev pc_lines=$pc failure_lines=$fails"
}

watch() {
  local d="$OUT/watch-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$d"; local f="$d/logcat.txt"
  echo "watching serial=$S tag=$TAG file=$f"
  while true; do
    "$A" -s "$S" wait-for-device >/dev/null 2>&1
    while IFS= read -r line; do
      printf '%s\n' "$line" >> "$f"
      if printf '%s' "$line" | grep -Eq "$FAIL_RE"; then
        local out rc app=ok
        out=$(pull); rc=$?
        printf '%s\n' "$out"
        case "$out" in *files=0*) app=missing ;; esac
        [ "$rc" -ne 0 ] && app="pull_failed_rc$rc"
        printf 'FAILURE app_file=%s pc_stream=%s %s\n' "$app" "$f" "$(printf '%s' "$line" | sed -E "$MASK" | cut -c1-160)"
        return 0
      fi
    done < <("$A" -s "$S" logcat -v time -s "$TAG:I" "AndroidRuntime:E" 2>/dev/null)
    sleep 2   # the connection dropped: reconnect
  done
}

case "$CMD" in pull) pull; exit $? ;; watch) watch ;; *) echo "usage: phone-log.sh <serial> pull|watch"; exit 2 ;; esac
