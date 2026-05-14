# Splash screen

System-managed cold-launch splash via `androidx.core:core-splashscreen`. The launcher activity's `android:theme` resolves a splash style that paints a brand-coloured background + a centred placeholder vector for the duration of process start; `installSplashScreen()` in `MainActivity.onCreate` swaps to the regular app theme before the Compose hierarchy is realised, and the platform dismisses the splash window as soon as the first Compose frame is produced.

## What it does

Replaces the bare-app-theme cold-launch frame with a deterministic, system-drawn splash window:

1. Cold launch → OS reads the launcher activity theme (`Theme.PyrycodeMobile.SplashScreen`) and draws the splash window with `windowSplashScreenBackground` (brand color `@color/ic_launcher_background`, `#FF32628D`) and the centred `windowSplashScreenAnimatedIcon` (placeholder `@drawable/ic_splash_logo`, white circle on the brand-coloured field).
2. Process spins up; `MainActivity.onCreate` enters and immediately calls `installSplashScreen()`. The compat library swaps the activity theme to `postSplashScreenTheme = @style/Theme.PyrycodeMobile` and registers the splash-to-app handoff with the platform.
3. `super.onCreate(...)` + `enableEdgeToEdge()` + `setContent { ... }` run as before.
4. First Compose frame is produced → platform dismisses the splash window. The `produceState`-driven empty `Surface { }` placeholder in `MainActivity.setContent` covers the brief preference-read window before NavHost mounts at the chosen start destination from [Navigation](navigation.md) (#13).

No `setKeepOnScreenCondition { ... }`, no exit-animation listener, no artificial timeout — the system controls dismissal end-to-end.

## How it works

### Theme

`app/src/main/res/values/themes.xml`:

```xml
<style name="Theme.PyrycodeMobile.SplashScreen" parent="Theme.SplashScreen">
    <item name="windowSplashScreenBackground">@color/ic_launcher_background</item>
    <item name="windowSplashScreenAnimatedIcon">@drawable/ic_splash_logo</item>
    <item name="postSplashScreenTheme">@style/Theme.PyrycodeMobile</item>
</style>
```

- `parent="Theme.SplashScreen"` resolves to the **compat library's** theme (no `android:` namespace prefix). It ships in `androidx.core:core-splashscreen` and normalises behaviour across API 23+; the platform `android:Theme.SplashScreen` is **not** a substitute.
- `postSplashScreenTheme` is the theme the activity switches to after `installSplashScreen()` runs. Pointing it back at the existing `Theme.PyrycodeMobile` preserves prior behaviour for everything after first frame.
- `windowSplashScreenAnimationDuration` and `windowSplashScreenIconBackgroundColor` are intentionally **absent** — they're part of the branded-splash follow-up. Setting them today would lock in placeholder values that the follow-up has to undo.
- `Theme.PyrycodeMobile` itself is untouched — splash theming is additive.

### Drawable

`app/src/main/res/drawable/ic_splash_logo.xml` — 288×288dp viewport per the Android 12+ splash icon spec, with the visible mark fitting inside the inner 192×192dp safe area (48dp padding ring on each side). Single white-filled `<path>` describing a r=64dp circle centred at (144, 144):

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="288dp"
    android:height="288dp"
    android:viewportWidth="288"
    android:viewportHeight="288">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M80,144a64,64 0 1,0 128,0a64,64 0 1,0 -128,0Z" />
</vector>
```

Fill is the literal hex `#FFFFFFFF` — splash drawables resolve **outside the app theme**, so `?attr/...` references won't work. The placeholder is deliberately generic: a recognisable "something is here" mark, not brand identity. Branded artwork is the follow-up.

`ic_pyry_logo.xml` is **not** reused here. Its viewport (104dp) and full-bleed paths don't fit the 288dp / 192dp-safe-area contract; retrofitting would require a translation/scale rewrite of every path coordinate.

### Manifest

`app/src/main/AndroidManifest.xml` — only the launcher `<activity>`'s `android:theme` changes:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:label="@string/app_name"
    android:theme="@style/Theme.PyrycodeMobile.SplashScreen">
    <!-- intent-filter unchanged -->
</activity>
```

The application-level `android:theme` stays at `@style/Theme.PyrycodeMobile`. The activity's own theme is what the OS reads when drawing the first frame **before the process is alive**, so the launcher activity is the only correct target; the app-level theme remains the fallback for any future non-launcher activity.

### Install call

`app/src/main/java/de/pyryco/mobile/MainActivity.kt`:

```kotlin
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
// ...
override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent { /* unchanged */ }
}
```

Two non-obvious rules:

- **Before `super.onCreate(...)`.** AndroidX's `installSplashScreen()` swaps the activity theme from the splash theme to `postSplashScreenTheme` before the activity's window is realised; invoking it after `super.onCreate(...)` causes a visible splash-theme-leaked-into-app-frame glitch on some OEMs.
- **Return value discarded.** No `setKeepOnScreenCondition { ... }` — the [#13 conditional NavHost start destination](navigation.md) already avoids the only flash that would warrant gating.

## Configuration

- **Dependency:** `androidx.core:core-splashscreen:1.0.1` via the version catalog (alias `androidx-core-splashscreen`, version key `coreSplashscreen`). No BOM exists for this artifact; version it directly. If a later bump trips an unresolved-artifact warning, bump *up* to the latest stable `1.0.x` / `1.1.x` — do not downgrade.
- **Brand color:** `@color/ic_launcher_background` (#FF32628D) is the single source of truth. Do not introduce a parallel `splash_background` color name; the launcher icon and splash share the brand background by construction.
- **`minSdk`:** 33 (Android 13). The compat library backports to API 23, so the same setup also covers any future `minSdk` walk-back without a code change.

## Flow

```
Cold launch
   ↓ OS reads launcher activity theme
[Splash window: brand color + placeholder icon]
   ↓ process starts
MainActivity.onCreate
   ↓ installSplashScreen()                          (swaps theme → Theme.PyrycodeMobile)
   ↓ super.onCreate(...) + enableEdgeToEdge()
   ↓ setContent { ... }
First Compose frame
   ↓ platform dismisses splash window
[Surface { } placeholder]                            (paired === null, ~1–2 frames)
   ↓ appPreferences.pairedServerExists.first() resolves
[NavHost @ welcome | channel_list]                   (#13 conditional start destination)
```

## State + concurrency

No new state. `installSplashScreen()` is a one-shot call with no observable lifecycle beyond the platform's own splash window. The `SplashScreen` handle is intentionally discarded — nothing in this codebase reads it.

## Edge cases / limitations

- **No automated test coverage.** The splash is a system-window concern; `androidx.compose.ui.test` only attaches after dismissal, and `ActivityScenario` recreates the activity in-process and bypasses the cold-launch path. Verification is the build (theme/drawable/manifest resolve) plus one manual cold-launch on an Android 13+ emulator. Future splash work should not invent flaky instrumented tests — factor testable state out of the splash window or stick to manual verification.
- **Pre-Android-12 fallback.** The compat library renders a visually simpler splash on API ≤ 30 — solid background + centred icon, no platform-level animation envelope. Expected, not a regression. PR bodies should call this out if an Android 11/12 image is available.
- **Placeholder mark.** The white circle is intentionally generic. The branded-splash follow-up replaces the drawable and may introduce `windowSplashScreenAnimationDuration` / `windowSplashScreenIconBackgroundColor` / an exit listener — all additive to `Theme.PyrycodeMobile.SplashScreen`, none of them touch the manifest, the install call, or the app theme.
- **No `setKeepOnScreenCondition { ... }` gating.** Deliberate — the #13 `produceState` + empty-`Surface` placeholder pattern already covers the preference-read window. If a future ticket needs splash-time data preloading, evaluate whether the data can move into the placeholder window first; adopting `setKeepOnScreenCondition` couples splash duration to arbitrary work and risks a long-pause regression.

## Related

- Ticket notes: [`../codebase/80.md`](../codebase/80.md) (plumbing); [`../codebase/13.md`](../codebase/13.md) (the conditional NavHost start destination that makes splash-side gating unnecessary).
- Spec: `docs/specs/architecture/80-splash-screen-api-plumbing.md`.
- Sibling feature: [Navigation](navigation.md) — owns the post-splash start-destination decision.
- Reused asset: `R.color.ic_launcher_background` (launcher icon brand color).
- Follow-up (not yet filed): branded splash icon + animated transition under Phase 5 polish. Will replace `ic_splash_logo.xml`, may add animation-duration / icon-background-color theme items, possibly an exit-animation listener. Should not touch the manifest, the install call, or `Theme.PyrycodeMobile`.
