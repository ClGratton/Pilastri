#!/usr/bin/env bash
# Pilastri's durable log reader: `bash tools/phone-log.sh <serial> pull|watch` (generic engine: design-method/scripts/phone-log.sh).
# pull: copies the app's log files (Android/data/<pkg>/files/log) to output/phone-log/<yyyymmdd-hhmmss>/, prints counts only.
# watch: streams the tag to a PC file, reconnects, at the first failure line pulls and exits with one masked line.
cd "$(dirname "$0")/.." || exit 1
PHONE_LOG_CONFIG="$PWD/tools/phone-log.conf" exec bash design-method/scripts/phone-log.sh "$@"
