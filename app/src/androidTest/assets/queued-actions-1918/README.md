# Queued action capture evidence (#1918)

Production revision: `c8ed660e8`. The amended capture fixture mounts production rows under explicit static palettes; `MainActivity` itself pins dark. The four palette images prove the component, and `queued-messages.png` / `queued-long.png` retain its assembled-thread placement with fresh Send now capability.

Figma: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=848-9517 (Mobile), with glyph component `847:14138`.

Fresh Pixel 8 API 35 run on 2026-10-07: **2 executed, 2 passed, 0 failed, 0 errors, 0 skipped**, exit 0. `pixel8-captures-green.xml` names both methods. Sidecars record real hardware-rendered 412×892 captures; standalone light chrome inherits the dark activity's system-bar icon appearance and is outside the component comparison.

Command:

```sh
./gradlew spotlessApply :app:pixel8Api35DebugAndroidTest --rerun '-Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.design.ThreadDesignCaptureTest#queuedActions_lightDarkAndCancelOnlyAt412By892,de.pyryco.mobile.design.ThreadDesignCaptureTest#queuedAndToolRowFramesAt412By892' -Pandroid.testInstrumentationRunnerArguments.notPackage=de.pyryco.mobile.e2e -Pandroid.testInstrumentationRunnerArguments.requireRealSystemBars=true --console=plain
```

The controls match the Mobile reference's 12dp glyphs, 13dp column, 12dp bubble gap, 25dp centre spacing, Inverse Primary tint and selective opacity. Paired short bubbles use the 96dp sent-action accessibility precedent so both independent 48×48dp targets fit inside the row; Cancel-only bubbles retain their natural height. Shared pointer tests prove centre, edge and midpoint routing at 320dp and 412dp for short, wrapped and unbroken text, without firing the neighbouring row.
