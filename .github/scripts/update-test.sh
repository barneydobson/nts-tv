#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner after a release has been published.
#
# Installs the previous release, opens it, and checks that it updates itself to the release
# that was just published (EXPECTED_VERSION), the same way it would on a TV.
set -u

APK="$1"
OUT="$2"
EXPECTED="${EXPECTED_VERSION:?}"
PKG=io.github.barneydobson.ntstv
mkdir -p "$OUT"
failures=0

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
installed_version() {
  adb shell dumpsys package "$PKG" | grep -m1 -oE 'versionName=[^ ]+' | cut -d= -f2 | tr -d '\r'
}
resumed_activity() {
  adb shell dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity' | tr -d '\r'
}
check() { # check <description> <command...>
  local what="$1"; shift
  if "$@"; then
    echo "::notice title=Update test::PASS: $what"
  else
    echo "::error title=Update test::FAIL: $what"
    failures=$((failures + 1))
  fi
}

adb install -r "$APK" || exit 1
before=$(installed_version)
echo "::notice title=Update test::Installed previous release $before; expecting it to update to $EXPECTED"

# Equivalent of the one-time "allow NTS Radio to install apps" prompt on a real TV.
adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"
sleep 5
shot 1-opened

# The app finds the new release, downloads it and installs it (nothing is playing).
prompted=0
for i in $(seq 1 60); do
  [ "$(installed_version)" = "$EXPECTED" ] && break
  if resumed_activity | grep -qi packageinstaller; then
    # Android asked for confirmation; accept it like a user would.
    prompted=1
    shot "2-install-prompt-$i"
    adb shell input keyevent KEYCODE_DPAD_CENTER
  fi
  sleep 2
done
after=$(installed_version)
[ "$prompted" = 1 ] && echo "::notice title=Update test::Android asked for confirmation before installing"

adb logcat -d -v threadtime > "$OUT/logcat.txt"
grep "NtsTvUpdater" "$OUT/logcat.txt" | sed -E 's/.*NtsTvUpdater: //' | head -n 6 | while read -r line; do
  echo "::notice title=Updater::$line"
done

check "Updated itself from $before to $EXPECTED (now: ${after:-unknown})" [ "$after" = "$EXPECTED" ]

echo "==> Reopening the updated app"
# The new version is registered before Android has finished optimising it; a launch
# during that window is ignored, so give it a moment and retry like a person would.
sleep 10
for attempt in 1 2 3 4; do
  adb shell am start -W -n "$PKG/.MainActivity"
  sleep 8
  adb shell pidof "$PKG" > /dev/null && break
  echo "Launch attempt $attempt didn't start the app yet; retrying"
done
sleep 5
shot 3-after-update
check "Updated app starts" adb shell pidof "$PKG"
adb logcat -d -v threadtime > "$OUT/logcat-after.txt"
if grep -A 20 "FATAL EXCEPTION" "$OUT/logcat-after.txt" | grep -q "$PKG"; then
  echo "::error title=Update test::FAIL: the updated app crashed"
  failures=$((failures + 1))
fi

exit "$failures"
