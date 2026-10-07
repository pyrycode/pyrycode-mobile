# Queued action capture evidence (#1918)

Contrast repair after verifier head `7cc20afc4413`: both glyphs now use full-opacity theme **Primary**, following the sent copy/reply accessibility precedent. The requested Inverse Primary measured 1.61:1 in light and below 3:1 on the dark production gradient. This deliberate Figma tint deviation preserves glyphs, geometry, no backing, independent targets and 60% clock/bubble opacity.

Figma: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=848-9517 (Mobile), with glyph component `847:14138`; both references were refreshed during this rework.

Fresh Pixel 8 API 35 run on 2026-10-07: **2 executed, 2 passed, 0 failed, 0 errors, 0 skipped**, exit 0. `pixel8-captures-green.xml` names both methods. All six PNGs and sidecars were refreshed. The four palette images mount production rows under explicit static palettes because `MainActivity` pins dark; `queued-messages.png` / `queued-long.png` exercise actual dark-gradient thread placement and fresh Send now capability. Sidecars confirm real hardware-rendered 412×892 captures. Light system-bar icon appearance inherited from the dark activity is outside the component comparison.

Command:

```sh
./gradlew lint assembleDebug compileDebugAndroidTestKotlin :app:pixel8Api35DebugAndroidTest --rerun '-Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.design.ThreadDesignCaptureTest#queuedActions_lightDarkAndCancelOnlyAt412By892,de.pyryco.mobile.design.ThreadDesignCaptureTest#queuedAndToolRowFramesAt412By892' -Pandroid.testInstrumentationRunnerArguments.notPackage=de.pyryco.mobile.e2e -Pandroid.testInstrumentationRunnerArguments.requireRealSystemBars=true --console=plain
```

`capture-contrast.txt` records minimum solid-glyph contrast against adjacent unpainted canvas pixels across every queued action in these captures: **6.12:1 light, 11.41:1 flat dark, 10.80:1 queued-messages and 8.38:1 queued-long**. Measurements use actual Primary paint (#32628D light / #9DCBFC dark), not alpha-adjusted or antialiased edge colours.

`contrast-red.xml` retains the test-first failure at 1.614:1 before changing the tint. `contrast-green.xml` retains all **7 geometry tests passed**, including `palettes_keepActionsOpaqueAndOnlyBubbleAndWaitingDimmed`: both glyphs and Cancel-only, light/dark, flat surfaces and the actual production `ThreadScreen` gradient. A row-free render samples each glyph's exact underlying background; the test requires at least 3:1 and full-opacity Primary paint, plus 60% bubble/waiting paint. Existing centre, edge and midpoint pointer tests remain green at 320dp/412dp for short, wrapped and unbroken text, without firing neighbouring rows.

`scripted-stream-green.xml` retains the fresh deterministic stream scenario: **1 executed/passed, 0 failed/errors/skipped**, zero real-Claude turns. Dispatcher-owned full live acceptance remains pending.
