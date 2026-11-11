#!/usr/bin/env bash
# Platform part for install-gate.sh.template: Android over adb. Copy to the project's tools/platform.sh and replace the placeholders.
# Contract: platform_init, platform_not_in_use, platform_install, platform_verify (each returns 0 on success; the gate prints the refusal).
DEVICE="${DEVICE:-<DEVICE_SERIAL_OR_HOST:PORT>}"
APP_ID="<APPLICATION_ID>"                       # or derive it from the build file
ADB="$(command -v adb 2>/dev/null || true)"
platform_init() { [ -n "$ADB" ] && "$ADB" -s "$DEVICE" get-state >/dev/null 2>&1; }
platform_not_in_use() {
  local top; top="$("$ADB" -s "$DEVICE" shell dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -i "topResumedActivity" || true)"
  case "$top" in *"$APP_ID"*) return 1 ;; esac; return 0
}
_updated() { "$ADB" -s "$DEVICE" shell dumpsys package "$APP_ID" 2>/dev/null | tr -d '\r' | sed -n 's/.*lastUpdateTime=//p' | head -n 1; }
platform_install() { BEFORE="$(_updated)"; "$ADB" -s "$DEVICE" install -r "$ARTIFACT"; }
platform_verify() { local after; after="$(_updated)"; [ -n "$after" ] && [ "$after" != "$BEFORE" ] && echo "install-gate: lastUpdateTime $BEFORE -> $after"; }
