#!/usr/bin/env bash
# One command captures every screen (emulator only); the app lists its own destinations. See tools/README.md "Capture every screen".
#   bash tools/capture-all.sh [--config fold|cover|fs2|all] [--only 'phone-month*'] [--out <dir>] [-s emulator-5554] [--no-shots] [--no-motion] [--list] [--crawl]
cd "$(dirname "$0")/.." && exec node tools/capture-all.mjs --settings tools/capture-config.json "$@"
