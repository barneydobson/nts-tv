#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner against an Android TV emulator.
#
# Installs the APK, opens it, tunes in to NTS 1, switches to a mixtape, goes back to NTS 1
# and pauses it, saving screenshots, media-session dumps and logcat along the way.
# Fails if the app crashes, or if NTS 1 never reaches the PLAYING state.
set -u

APK="$1"
OUT="$2"
PKG=io.github.barneydobson.ntstv
mkdir -p "$OUT"
failures=0

log() { echo "==> $*"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
key() { adb shell input keyevent "$1"; sleep 1.5; }

# PlaybackState: 2 = PAUSED, 3 = PLAYING. Newer Android prints e.g. "state=PLAYING(3)".
session_state() {
  adb shell dumpsys media_session \
    | grep -A 40 "package=$PKG" \
    | grep -oE 'state=PlaybackState \{state=[A-Z_]*\(?[0-9]+' \
    | head -n1 | grep -oE '[0-9]+$'
}

wait_for_state() { # wait_for_state <code> <seconds>
  for _ in $(seq 1 "$2"); do
    [ "$(session_state)" = "$1" ] && return 0
    sleep 1
  done
  return 1
}

check() { # check <description> <command...>
  local what="$1"; shift
  if "$@"; then
    echo "::notice title=Smoke test::PASS: $what"
  else
    echo "::error title=Smoke test::FAIL: $what"
    failures=$((failures + 1))
  fi
}

adb shell getprop ro.build.version.release
adb shell wm size

log "Installing $APK"
adb install -r "$APK" || exit 1
adb logcat -c

log "Launching"
adb shell am start -W -n "$PKG/.MainActivity"
sleep 20
shot 1-launched

log "Selecting NTS 1"
key KEYCODE_DPAD_CENTER
check "NTS 1 starts playing" wait_for_state 3 60
sleep 10
shot 2-playing-nts1
adb shell dumpsys media_session > "$OUT/media-session-nts1.txt"

log "Selecting the first Infinite Mixtape"
key KEYCODE_DPAD_DOWN
shot 3-mixtape-focused
key KEYCODE_DPAD_CENTER
sleep 3
check "Mixtape plays" wait_for_state 3 60
sleep 8
shot 4-playing-mixtape
adb shell dumpsys media_session > "$OUT/media-session-mixtape.txt"

log "Back to NTS 1, then pause it"
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_CENTER
check "NTS 1 resumes" wait_for_state 3 60
key KEYCODE_DPAD_CENTER
check "OK pauses playback" wait_for_state 2 15
shot 5-paused

log "Going home; playback should be able to carry on in the background"
key KEYCODE_DPAD_CENTER
wait_for_state 3 60
adb shell input keyevent KEYCODE_HOME
sleep 10
check "Still playing in the background" wait_for_state 3 20
shot 6-home-screen

adb logcat -d > "$OUT/logcat.txt"
adb logcat -d -v threadtime --pid="$(adb shell pidof "$PKG")" > "$OUT/app-log.txt" 2>&1
check "App process is alive" adb shell pidof "$PKG"
updater=$(grep -m1 "NtsTvUpdater" "$OUT/app-log.txt" | sed -E 's/.*NtsTvUpdater: //' | cut -c1-200)
echo "::notice title=Updater::${updater:-(no updater log line)}"
# Artwork for the system's media controls is loaded separately from the app's own images.
if grep -q "Failed to load bitmap" "$OUT/app-log.txt"; then
  echo "::warning title=Smoke test::System media artwork failed to load: $(grep -m1 'Failed to load bitmap' "$OUT/app-log.txt" | cut -c1-200)"
else
  echo "::notice title=Smoke test::System media artwork loaded"
fi
if grep -A 20 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG"; then
  echo "::error title=Smoke test::FAIL: app crashed: $(grep -A 3 'FATAL EXCEPTION' "$OUT/logcat.txt" | tr '\n' ' ' | cut -c1-400)"
  grep -A 30 "FATAL EXCEPTION" "$OUT/logcat.txt"
  failures=$((failures + 1))
fi

log "Media session at the end:"
adb shell dumpsys media_session | grep -A 25 "package=$PKG" | tee "$OUT/media-session-final.txt"
log "Playback-related log lines:"
grep -E "ExoPlayer|PlaybackService|MediaSession|NtsTv" "$OUT/logcat.txt" | tail -n 60

log "$failures check(s) failed"
exit "$failures"
