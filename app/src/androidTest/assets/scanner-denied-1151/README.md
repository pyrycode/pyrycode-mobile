# Denied pairing route — #1151

Captured 2026-09-27 from the production `MainActivity`, using the implementation
tree committed unchanged as `60d1e8eebefea1ca4460e6f05aa0f8d26f5f0395` after the
successful run. The APK was compiled before that commit, so its embedded
`GIT_SHA` is the plan commit `2febcccc`; the APK hash below identifies the exact
tested binary. This is a fresh unpaired app
after tapping **I already have pyrycode** and denying Android's real camera
permission dialog. No forced scanner state or preview was used.

- `denied-dark.png`: actual full-display screenshot from the full Pixel 8 API 35 image.
- `figma-reference.png`: exported [Figma node 32:2](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=32-2), 412×892.
- `context.txt`: recorded activity, Android build, density, system insets and Back bounds.
- `result.xml`: fresh focused execution, **1 test, 0 failures, 0 errors, 0 skips**.
- Exported illustration source: `app/src/main/res/raw/scanner_denied_source.svg`, node `32:8`; Android vector resources preserve its geometry and bind outline/strike to theme roles.

## Reproduce

Run from a checkout with the installed Android SDK and JDK configured:

```sh
./gradlew :app:pixel8Api35DebugAndroidTest --rerun \
  '-Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.ScannerDeniedRouteDeviceTest' \
  -Pandroid.testInstrumentationRunnerArguments.notPackage=de.pyryco.mobile.e2e \
  --console=plain
```

Exit status: **0**. The test requires a fresh unpaired app with camera permission
ungranted. The managed device supplies that isolated installation. It changes the
display to 412×892 pixels at 160 dpi (density 1), selects the app's dark theme,
then restores the previous display and theme configuration. API 33 ATD does not
run this API 35+ screenshot test; shared callback tests run there independently.

Fresh XML is produced under
`app/build/outputs/androidTest-results/managedDevice/debug/pixel8Api35/`.
PNG/context output is under
`app/build/intermediates/managed_device_android_test_additional_output/debugAndroidTest/pixel8Api35DebugAndroidTest/scanner-denied-1151/`.

After capture, the same test opens Android settings and checks the app-specific
package intent, returns to denied, opens the existing full-page pairing form,
cancels it untouched, and taps Back to Welcome. The real paired-server collection
is empty at startup, after cancellation and after Back.

## Visual comparison

Both files are 412×892. The reference is frameless; the actual display has 24 dp
status and navigation bars, leaving 844 dp safe content. The activity consumes
these insets once. Compare top-anchored content after a +24 dp shift and the
bottom-anchored actions after a -24 dp shift; do not scale the illustration or
subtract another pair of bars. The Back target is (4,36)–(52,84) in the real
window, exactly the reference target translated by the status bar.

Visual review confirms the header, 120 dp illustration slot, camera proportions,
upper-left-to-lower-right strike, message wrapping, theme colors, and action
geometry. The filled action occupies y=688–736 (reference y=712–760); the text
action has the reference's 40 dp visible height with a 48 dp accessible target.
Native system chrome and its square screenshot corners are outside the frameless
Figma surface. No in-scope visual deviation remains.

## Build and artifact hashes (SHA-256)

```text
app-debug.apk: d8339165068d7419d912d52be344468ec1b1b78af570e9eda346b9ea61fbad44
denied-dark.png: da238fc2a6fdddb8c09e083e5b0e36092341da589a547143be24d13039636d86
figma-reference.png: 37ef1db810e1282e0b39d16ddfa683b53dcd89eca2a13455a8eefa48bd3f7a1a
scanner_denied_source.svg: f87517f387b83799a7f70dce273107df4ad376e5dd30e01fa0f2d083363175b3
```
