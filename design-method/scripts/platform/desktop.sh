#!/usr/bin/env bash
# STUB platform part for install-gate.sh.template: desktop or web app (copy a build to a folder, run an installer, or deploy). Fill the four functions; each returns 0 on success.
# Dumps: export the UI tree as JSON nodes and use config adapter "json-nodes" (scripts/adapters/json-nodes.mjs); --scale 1 when bounds are already in CSS px / points.
platform_init() { echo "desktop.sh: not implemented: check that the target folder or host is reachable" >&2; return 1; }
platform_not_in_use() { echo "desktop.sh: not implemented: refuse when the app process is running and in use" >&2; return 1; }
platform_install() { echo "desktop.sh: not implemented: copy or install \$ARTIFACT" >&2; return 1; }
platform_verify() { echo "desktop.sh: not implemented: verify the installed build changed (checksum, version file)" >&2; return 1; }
