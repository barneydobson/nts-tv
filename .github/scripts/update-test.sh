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
key() { adb shell input keyevent "$1"; sleep 1.5; }
logged() { adb logcat -d | grep -q "$1"; }
wait_logged() { # wait_logged <pattern> <seconds>
  for _ in $(seq 1 "$2"); do logged "$1" && return 0; sleep 1; done
  return 1
}
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

adb logcat -c
if printf '%s\n' "1.2.1" "$before" | sort -V -C; then
  # From 1.2.1 the app asks for the one-off install permission itself: go through that
  # exactly as a first-time user would.
  echo "::notice title=Update test::First-time flow: the permission isn't granted in advance"
  adb shell am start -W -n "$PKG/.MainActivity"
  check "Asks for the install permission" wait_logged "Install permission needed" 90
  sleep 2
  shot 1-allow-updates
  key KEYCODE_DPAD_CENTER   # the focused "Allow updates" button
  sleep 3
  shot 2-settings
  check "Opens the setting" sh -c "adb shell dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity' | grep -qi settings"
  # Switch it on (as the user would on that screen), then go back to the app.
  adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow
  key KEYCODE_BACK
else
  # Older releases relied on Android's own prompt; grant the permission up front.
  adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow
  adb shell am start -W -n "$PKG/.MainActivity"
fi
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
