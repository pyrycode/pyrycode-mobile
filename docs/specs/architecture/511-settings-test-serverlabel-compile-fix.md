# 511 — SettingsScreenTest: restore androidTest compilation (missing `serverLabel`)

## Context

Commit `51501f3` (*feat(mobile): paste-pairing-code dialog + live server label in Settings, #503*) added a **required** `serverLabel: String` parameter to the `SettingsScreen` composable. Production call sites and the two `@Preview`s were updated; the three `SettingsScreen(...)` invocations in `SettingsScreenTest.kt` were not. The `androidTest` source set therefore fails to compile on `main`:

```
> Task :app:compileDebugAndroidTestKotlin FAILED
e: .../SettingsScreenTest.kt:54:21 No value passed for parameter 'serverLabel'.
e: .../SettingsScreenTest.kt:92:21 No value passed for parameter 'serverLabel'.
e: .../SettingsScreenTest.kt:131:21 No value passed for parameter 'serverLabel'.
```

This shipped undetected because the mandatory gates (`./gradlew check`, `assembleDebug`) do **not** compile `androidTest` — the documented androidTest gap (see [[androidtest-not-compiled-by-mandatory-gates]]). PR #510 (#492) merely *surfaced* it: its QA androidTest-compile gate ran because that PR touches `app/src/androidTest/`. PR #510 does not reference `serverLabel` and does not touch this test file; a baseline against `git merge-base HEAD origin/main` reproduces the three errors → pre-existing on `main`.

This is a pure compile-restoration ticket: add the one already-required argument to three existing test call sites. No behaviour change, no new coverage.

## Files to read first

- `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt` — the only file to edit. Three `SettingsScreen(...)` call sites, currently at the `setContent { PyrycodeMobileTheme { SettingsScreen(...) } }` blocks in `wallpaperColorsRow_rendersMaterialYouLabel` (~line 31), `archivedDiscussionsRow_rendersSupportingTextWithCount` (~line 69), and `aboutRow_navigatesOnClick` (~line 108). All three use named arguments; `serverLabel` is simply absent from each.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:53-78` — composable signature. `serverLabel: String` is the **2nd** parameter, immediately after `connectionStatus: ConnectionStatus` and before `themeMode`. Match this position in the test call sites for readability (named args make it order-independent, but mirror the source).
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:341-366` — `SettingsScreenLightPreview`. Canonical example of the argument as used elsewhere: `serverLabel = "abc123 · wss://relay…"` (line 344). The dark preview at line 377 uses the identical value.

## Design source

N/A — test-only compile fix. No new or changed UI surface; the `SettingsScreen` visual design is unchanged and out of scope. Visual-fidelity review is intentionally not applicable.

## Design

Single-file edit to `SettingsScreenTest.kt`. In each of the three `SettingsScreen(...)` invocations, insert one line immediately after the `connectionStatus = ...` argument:

```kotlin
serverLabel = "abc123 · wss://relay…",
```

- Use the same non-empty literal as the previews (`"abc123 · wss://relay…"`) for all three sites — satisfies AC#1 (non-empty) and keeps the test fixtures consistent with the preview fixtures.
- Placement after `connectionStatus` mirrors the composable's parameter order (`SettingsScreen.kt:54-55`); readability only, since the calls are fully named.
- No import changes — `serverLabel` is a plain `String`.
- Do **not** touch any production file (AC#4). Do **not** add assertions on the server-label row (explicitly out of scope — that coverage belongs to a separate ticket).

There is no state, concurrency, or error-handling surface: the argument is a static `String` literal passed into a stateless composable under `ComposeTestRule`.

## Testing strategy

Instrumented (`androidTest`) — this is the source set that currently fails to compile. No new tests; the three existing tests must keep passing unchanged (AC#3):

- `wallpaperColorsRow_rendersMaterialYouLabel` — asserts the "Use Material You dynamic color" row exists. Unaffected by `serverLabel`.
- `archivedDiscussionsRow_rendersSupportingTextWithCount` — asserts the "11 archived" supporting text. Unaffected.
- `aboutRow_navigatesOnClick` — asserts the About-row click callback fires once. Unaffected.

**Gate to run** (the acceptance signal, AC#2):

```bash
./gradlew compileDebugAndroidTestKotlin   # must go from FAILED → green
```

Note `./gradlew test` / `check` / `assembleDebug` will **not** exercise this fix — they skip `androidTest` compilation (that gap is precisely why the break shipped). The developer must run `compileDebugAndroidTestKotlin` explicitly. Running the tests on a device (`connectedAndroidTest`) is optional confirmation of AC#3 but not required to close the ticket; the assertions are untouched and the composable's rendered output does not depend on `serverLabel` for any of the three assertions.

## Acceptance criteria (from ticket)

1. Each of the three `SettingsScreen(...)` invocations passes a non-empty `serverLabel` argument.
2. `./gradlew compileDebugAndroidTestKotlin` succeeds.
3. The three existing tests still pass, with no assertion/behaviour changes beyond the added argument.
4. No production code is modified — the change is confined to `SettingsScreenTest.kt`.

## Open questions

None. The argument, its value, its placement, and the acceptance gate are all fully determined.
