#!/usr/bin/env bash
# Undo everything on a Fire TV: re-enable the debloated packages, turn off the home override and
# remove the launcher. Amazon's home screen comes back on the next Home press. Uses the stock adb.
# Usage: TV_HOST=<fire-tv-ip> tools/restore-firetv.sh examples/amazon-fire-tv-stick-4k-aftkm/debloat.txt
set -euo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: restore-firetv.sh <debloat list>}"
T="${TV_HOST:?set TV_HOST}:5555"
adb connect "$T" >/dev/null
grep -vE '^\s*(#|$)' "$LIST" | sed -E 's/\s+#.*$//' | while read -r p; do
  printf '%s: ' "$p"; adb -s "$T" shell pm enable "$p" </dev/null   # one pm call per package
done
adb -s "$T" shell settings delete secure enabled_accessibility_services
adb -s "$T" shell pm uninstall com.gathin.tvhome || true
adb -s "$T" shell input keyevent KEYCODE_HOME
adb -s "$T" shell pm list packages -d
