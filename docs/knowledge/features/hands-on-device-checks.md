# Hands-on device checks

How an operator or an interactive assistant session checks the app by hand on a
device, outside the dispatcher's gates. The pipeline roles never do this: the
dispatcher owns every routine device run, see
[Development verification](development-verification.md).

## Which device

`scripts/hands-on.sh device` prints the serial to use. A phone attached with USB
debugging wins over any emulator. A real phone is much faster than an emulator on
a loaded Mac, its key input does not drop characters, and it has real push and a
real camera. Without a phone the script takes a running emulator the operator
booted, such as `Pixel_8`. It never takes a Gradle-managed gate emulator, whose
AVD name starts with `dev` and an API level, because the dispatcher boots and owns
those. Setting `ANDROID_SERIAL` overrides the choice.

`scripts/hands-on.sh install` installs the debug build on that one device. Plain
`./gradlew installDebug` installs on every attached device, gate emulators
included.

## The operator's own phone

A phone on USB may be the operator's own, running the Play build. Debug and
release builds share the application id `de.pyryco.mobile`, and the Play build is
signed with a different key, so a test build cannot be installed over it. The
only way past that error is to uninstall the app, which deletes every saved host.
Never do that on a physical phone. The script stops on a phone whose installed
app is not a test build. A separate debug application id would let both builds
sit side by side. It needs a second Android app registered in the Firebase
project first, because push requires a Firebase client for every application id.

## Pairing without typing

A pairing code is about 300 characters. Typing it through `adb shell input text`
on a busy emulator drops keys, and a slow retype can outrun the code's
redemption window, so the daemon refuses it. Test builds read the code from the
launch instead:

```bash
pyry pair --name=<device-name> | scripts/hands-on.sh pair <host-name>
```

The script restarts the app with the code and the optional host name as launch
extras, and the app opens the pair-code screen with both fields filled in. The
operator still taps Pair and compares the fingerprint, exactly as after a paste,
so the [pairing confirm gate](pairing-confirm-gate.md) is unchanged.

`PairingPrefill` reads the extras only when `BuildConfig.DEBUG`, so a release
build ignores them and no other app on the phone can pre-fill a pairing. The code
carries the pairing token: it is never logged, never put in a navigation route,
and held in memory only until the pair-code screen takes it.
