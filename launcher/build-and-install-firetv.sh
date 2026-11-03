#!/usr/bin/env bash
# Fire TV version of build-and-install.sh. See docs/fire-tv.md.
# Fire OS won't let another app be the default home or disable Amazon's launcher, so instead this
# enables the launcher's accessibility service, which brings the launcher back whenever Amazon's
# home screen appears. Uses the stock adb (tested on Linux).
# Usage: TV_HOST=<fire-tv-ip> launcher/build-and-install-firetv.sh
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the Fire TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
T="$TV_HOST:5555"
SVC=com.gathin.tvhome/com.gathin.tvhome.TvAccessibilityService
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || true)
  for d in /opt/homebrew/opt/openjdk@17 /usr/lib/jvm/java-17-openjdk; do [ -n "$JAVA_HOME" ] || { [ -d "$d" ] && JAVA_HOME=$d; }; done
fi
if [ -z "${ANDROID_HOME:-}" ]; then
  for d in ~/Library/Android/sdk /opt/homebrew/share/android-commandlinetools ~/Android/Sdk; do [ -d "$d" ] && { ANDROID_HOME=$d; break; }; done
fi
export JAVA_HOME ANDROID_HOME PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug -q --console=plain
adb connect "$T" >/dev/null
adb -s "$T" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$T" shell pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION || true
# Reinstalling unbinds the service, and writing the same value back doesn't rebind it,
# so clear the setting first to force a fresh bind.
adb -s "$T" shell settings delete secure enabled_accessibility_services >/dev/null
sleep 1
adb -s "$T" shell settings put secure enabled_accessibility_services "$SVC"
adb -s "$T" shell settings put secure accessibility_enabled 1
for _ in $(seq 1 20); do   # wait for the service to bind, then open the launcher
  adb -s "$T" shell dumpsys accessibility | grep -q "Enabled services:{{$SVC}}" && break
  sleep 0.5
done
sleep 1
adb -s "$T" shell am start -n com.gathin.tvhome/.MainActivity >/dev/null
echo "Installed. Press Home on the remote to see it."
