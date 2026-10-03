#!/usr/bin/env bash
#
# hands-on.sh — device helpers for a hands-on check of the app, outside the dispatcher's gates.
#
#   scripts/hands-on.sh device              print the serial to use
#   scripts/hands-on.sh install             install the debug build on that device only
#   pyry pair ... | scripts/hands-on.sh pair [host-name]
#                                           open the pair-code screen with the code filled in
#
# Device choice: a phone attached with USB debugging wins over any emulator, because a real phone is
# faster than an emulator on a loaded Mac and has real push and a real camera. Without a phone it takes
# a running emulator the operator booted, never one of the Gradle-managed gate emulators (AVD names
# dev<api>_…), which the dispatcher boots and owns. ANDROID_SERIAL, when set, overrides the choice.
#
# Why `pair` exists: typing a 300-character pairing code through `adb shell input text` on a busy
# emulator drops keys and outruns the code's redemption window. Test builds read the code from the
# launch instead (PairingPrefill); the operator still taps Pair and confirms the fingerprint.
#
# A physical phone may be the operator's own, running the Play build under the same application id.
# Nothing here uninstalls the app or clears its data, and install/pair stop rather than touch a phone
# whose installed app is not a test build: replacing it would lose the operator's saved hosts.
set -euo pipefail

APP_ID="de.pyryco.mobile"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-adb}"
if ! command -v "$ADB" >/dev/null 2>&1 && [ -n "${ANDROID_HOME:-}" ]; then ADB="$ANDROID_HOME/platform-tools/adb"; fi

die() {
  echo "hands-on: $*" >&2
  exit 1
}

pick_device() {
  if [ -n "${ANDROID_SERIAL:-}" ]; then
    echo "$ANDROID_SERIAL"
    return
  fi
  local serials phone emulator name
  serials="$("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
  phone="$(grep -v '^emulator-' <<<"$serials" | head -1 || true)"
  if [ -n "$phone" ]; then
    echo "$phone"
    return
  fi
  for emulator in $(grep '^emulator-' <<<"$serials" || true); do
    name="$("$ADB" -s "$emulator" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
    if [[ -n "$name" && ! "$name" =~ ^dev[0-9]+_ ]]; then
      echo "$emulator"
      return
    fi
  done
  die "no phone with USB debugging and no operator-booted emulator; attach a phone or boot one, for example: emulator @Pixel_8 -no-window"
}

# A phone's installed app must be a test build before anything is installed over it or launched with a code.
require_test_build() {
  local serial="$1"
  [[ "$serial" == emulator-* ]] && return
  local flags
  flags="$("$ADB" -s "$serial" shell dumpsys package "$APP_ID" | grep -m1 'pkgFlags=' || true)"
  if [ -n "$flags" ] && ! grep -q DEBUGGABLE <<<"$flags"; then
    die "$serial has a release build of $APP_ID, probably from Play. A test build cannot replace it without removing it and its saved hosts, so stopping. Use another phone or an emulator."
  fi
}

case "${1:-}" in
  device)
    pick_device
    ;;
  install)
    serial="$(pick_device)"
    require_test_build "$serial"
    echo "hands-on: installing the debug build on $serial" >&2
    cd "$ROOT" && ANDROID_SERIAL="$serial" ./gradlew -q installDebug
    ;;
  pair)
    name="${2:-}"
    [[ "$name" =~ ^[A-Za-z0-9._-]*$ ]] || die "host name may hold only letters, digits, dot, dash and underscore"
    code="$(grep -m1 -E '^[A-Za-z0-9_-]{100,}$' || true)"
    [ -n "$code" ] || die "no pairing code on stdin; pipe in the output of pyry pair"
    serial="$(pick_device)"
    require_test_build "$serial"
    # -S stops the app first, so the launch is a fresh start and the activity reads the code.
    "$ADB" -s "$serial" shell am start -S -n "$APP_ID/.MainActivity" \
      --es "$APP_ID.extra.PAIRING_CODE" "$code" \
      --es "$APP_ID.extra.HOST_NAME" "'$name'" >/dev/null
    echo "hands-on: pair-code screen open on $serial with the code filled in; tap Pair, then confirm the fingerprint" >&2
    ;;
  *)
    sed -n '3,8p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
    ;;
esac
