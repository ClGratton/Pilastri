#!/usr/bin/env bash
# STUB platform part for install-gate.sh.template: iOS (device via ios-deploy / devicectl, simulator via xcrun simctl). Fill the four functions; each returns 0 on success.
# Layout unit for the checker: points; pass --scale <screen scale> to consistency-check.mjs (or 1 when the dump is already in points). Minimum touch target: 44 pt.
platform_init() { echo "ios.sh: not implemented: check that the target is reachable (xcrun devicectl list devices, or xcrun simctl list)" >&2; return 1; }
platform_not_in_use() { echo "ios.sh: not implemented: refuse when the app is frontmost on the target" >&2; return 1; }
platform_install() { echo "ios.sh: not implemented: install \$ARTIFACT (xcrun devicectl device install app, or xcrun simctl install booted)" >&2; return 1; }
platform_verify() { echo "ios.sh: not implemented: verify the installed build changed (version/build number or install date)" >&2; return 1; }
