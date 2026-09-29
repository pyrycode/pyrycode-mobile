# Scanner visual evidence — #1213

Figma nodes [13:2](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=13-2)
and [32:2](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=32-2)
were inspected on 2026-09-29. Their tool response did not provide a last-modified
design date. The static 412 × 892 Figma renders and actual emulator comparisons
are retained here as `scanner-*` and `denied-*` images. Android's 24 dp system
bars shift the actual layout within the same logical viewport.

`ScannerLivePreviewDeviceTest.readyRoute_streamingCameraIsVisibleUnderOverlay`
adds the production camera proof: it enters from Welcome, grants one-time camera
permission, waits for a streaming `PreviewView` and a nonblank camera frame, then
captures `camera-raw-api35.png` and `scanner-live-actual-412x892.png`. The
emulator's virtual scene is visible through the scanner's dark mask, stripes and
reticle. No pairing code, QR credential or server connection is used.

Run the focused method from this worktree:

```sh
./gradlew :app:pixel8Api35DebugAndroidTest --rerun \
  '-Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.ScannerLivePreviewDeviceTest#readyRoute_streamingCameraIsVisibleUnderOverlay' \
  -Pandroid.testInstrumentationRunnerArguments.notPackage=de.pyryco.mobile.e2e \
  --console=plain
```

Exit status **0**; retained `scanner-live-result.xml` reports **1 executed,
0 failures, 0 errors, 0 skips**. Fresh XML appears under
`app/build/outputs/androidTest-results/managedDevice/debug/pixel8Api35/` and
the captured PNGs under
`app/build/intermediates/managed_device_android_test_additional_output/debugAndroidTest/pixel8Api35DebugAndroidTest/scanner-live-1213/`.

The static scanner comparison remains the layout reference because the live
camera scene is dynamic. Node 32:20 (Connecting) has no production caller;
keyboard and menu states are not applicable to these surfaces.
