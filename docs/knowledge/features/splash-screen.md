# Splash screen

System-managed cold-launch splash via `androidx.core:core-splashscreen`. The launcher activity's `android:theme` resolves a splash style that paints the dark background + a centred snowflake mark for the duration of process start; `installSplashScreen()` in `MainActivity.onCreate` swaps to the regular app theme before the Compose hierarchy is realised, and the platform dismisses the splash window as soon as the first Compose frame is produced.

## What it does

Replaces the bare-app-theme cold-launch frame with a deterministic, system-drawn splash window:

1. Cold launch → OS reads the launcher activity theme (`Theme.PyrycodeMobile.SplashScreen`) and draws the splash window with `windowSplashScreenBackground` (`@color/splash_background`, `#FF101418`, the dark scheme's background) and the centred `windowSplashScreenAnimatedIcon` (`@drawable/ic_splash_logo`, the `ic_pyry_logo` snowflake mark filled `@color/splash_mark`, `#FF7AB8E8`, the brand glacier blue).
2. Process spins up; `MainActivity.onCreate` enters and immediately calls `installSplashScreen()`. The compat library swaps the activity theme to `postSplashScreenTheme = @style/Theme.PyrycodeMobile` and registers the splash-to-app handoff with the platform.
3. `super.onCreate(...)` + `enableEdgeToEdge()` + `setContent { ... }` run as before.
4. First Compose frame is produced → platform dismisses the splash window. The `produceState`-driven empty `Surface { }` placeholder in `MainActivity.setContent` covers the brief preference-read window before NavHost mounts at the chosen start destination from [Navigation](navigation.md) (#13).

No `setKeepOnScreenCondition { ... }`, no exit-animation listener, no artificial timeout — the system controls dismissal end-to-end.

## How it works

### Theme

`app/src/main/res/values/themes.xml`:

```xml
<style name="Theme.PyrycodeMobile.SplashScreen" parent="Theme.SplashScreen">
    <item name="windowSplashScreenBackground">@color/splash_background</item>
    <item name="windowSplashScreenAnimatedIcon">@drawable/ic_splash_logo</item>
    <item name="postSplashScreenTheme">@style/Theme.PyrycodeMobile</item>
</style>
```

- `parent="Theme.SplashScreen"` resolves to the **compat library's** theme (no `android:` namespace prefix). It ships in `androidx.core:core-splashscreen` and normalises behaviour across API 23+; the platform `android:Theme.SplashScreen` is **not** a substitute.
- `postSplashScreenTheme` is the theme the activity switches to after `installSplashScreen()` runs. Pointing it back at the existing `Theme.PyrycodeMobile` preserves prior behaviour for everything after first frame.
- `windowSplashScreenAnimationDuration` and `windowSplashScreenIconBackgroundColor` are intentionally **absent** — no exit animation or icon-background ring is specced (Figma 701:5001). Add them only if a future design calls for one.
- `Theme.PyrycodeMobile` itself is untouched — splash theming is additive.

### Drawable

`app/src/main/res/drawable/ic_splash_logo.xml` — 288×288dp viewport per the Android 12+ splash icon spec, with the visible mark fitting inside the inner 192×192dp safe area (48dp padding ring on each side). It draws `ic_pyry_logo`'s path (viewport 91.002 × 103.812) at native size inside a `<group>` that places its 92 × 104dp frame centred on the canvas and flips it vertically, matching how Figma `701:5001` draws the mark:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="288dp"
    android:height="288dp"
    android:viewportWidth="288"
    android:viewportHeight="288">
    <group
        android:pivotY="51.906"
        android:scaleY="-1"
        android:translateX="98.998"
        android:translateY="92">
        <path
            android:fillColor="@color/splash_mark"
            android:pathData="M44.7516 103.733C...(ic_pyry_logo's path)" />
    </group>
</vector>
```

`translateX`/`translateY` place the mark's frame at (98, 92) on the 288dp canvas (Figma's frame origin plus its ~1dp left inset); `scaleY="-1"` about `pivotY` (half the path's own height) reproduces Figma's `-rotate-180 -scale-x-100` vertical flip without touching the path data. Fill is `@color/splash_mark` (`#FF7AB8E8`, the brand glacier blue the launcher icon ([#1546](https://github.com/pyrycode/pyrycode-mobile/issues/1546)) uses) rather than a theme attribute — splash drawables resolve **outside the app theme**, so `?attr/...` references won't work, which is also why the colour is a literal resource rather than a reference to the in-app theme's token. `ic_pyry_logo.xml` supplies the path data; the two files are kept separate because the splash group applies its own transform and fill rather than reusing `ic_pyry_logo`'s drawable wholesale.

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
- **Colors:** `@color/splash_background` (#FF101418, equal to `backgroundDark` in `ui/theme/Color.kt`) and `@color/splash_mark` (#FF7AB8E8) are the splash window's own colors. `@color/ic_launcher_background` ([#1546](https://github.com/pyrycode/pyrycode-mobile/issues/1546)) is now #FF101418 too — the same `backgroundDark` value as `splash_background`, redrawn under a radial glow rather than flat, no longer the earlier #FF32628D. The launcher foreground mark uses `@color/splash_mark` directly, so the two brand marks already share one color name; there is no separate launcher-mark resource left to merge. All of these resolve outside the app theme (launcher and splash windows draw before the app theme exists), so none can be a `?attr/...` reference.
- **Elliptical glow from a circular gradient primitive:** Android VectorDrawable `<gradient type="radial">` is circular only, but Figma exports an elliptical glow as a `gradientTransform` matrix (`ic_launcher_background`'s glow, Figma `703:5002`). Decompose the matrix by SVD into rotate · scale · rotate: the right-hand rotation turns a circular gradient about its own centre, which changes nothing, so dropping it and keeping one `<group android:rotation=... android:scaleX=... android:scaleY=...>` around the gradient path reproduces any positive-determinant matrix's ellipse exactly. Watch the transparent end stop's colour literal when doing this: Figma interpolates gradient stops with straight alpha, so a transparent stop's RGB still matters there, while Android interpolates premultiplied, where a `0` alpha stop's RGB is inert. A mistranscribed fully-transparent stop colour (`ic_launcher_background` shipped `#00335500` instead of Figma's `#00003355`) is invisible on-device but shifts the mid-falloff brightness against the Figma render — write the correct literal anyway so the file does not misstate the design, and add an intermediate stop holding the straight-alpha blend if matching the preview exactly matters.
- **`minSdk`:** 33 (Android 13). The compat library backports to API 23, so the same setup also covers any future `minSdk` walk-back without a code change.

## Flow

```
Cold launch
   ↓ OS reads launcher activity theme
[Splash window: #101418 background + snowflake mark]
   ↓ process starts
MainActivity.onCreate
   ↓ installSplashScreen()                          (swaps theme → Theme.PyrycodeMobile)
   ↓ super.onCreate(...) + enableEdgeToEdge()
   ↓ setContent { ... }
First Compose frame
   ↓ platform dismisses splash window
[Surface { } placeholder]                            (paired === null, ~1–2 frames)
   ↓ pairedServerStore.load() resolves
[NavHost @ welcome | channel_list]                   (#13 conditional start destination)
```

## State + concurrency

No new state. `installSplashScreen()` is a one-shot call with no observable lifecycle beyond the platform's own splash window. The `SplashScreen` handle is intentionally discarded — nothing in this codebase reads it.

## Edge cases / limitations

- **Resource contract is tested; the rendered pixels are not.** `SplashResourcesTest` (`app/src/sharedTest/java/de/pyryco/mobile/`) pins `R.color.splash_background` to `backgroundDark`, the splash theme's `windowSplashScreenBackground` to that same colour, `R.color.splash_mark` to #FF7AB8E8, and `R.color.ic_launcher_background` (`launcherIconBackgroundIsTheDarkSchemeBackground`) to `backgroundDark` as well, not an unchanged #FF32628D. `LauncherIconResourcesTest` (same directory) inflates `R.mipmap.ic_launcher` and `ic_launcher_round` as `AdaptiveIconDrawable` under Robolectric and asserts the background, foreground and monochrome layers are all non-null — inflation exercises both layered vectors, including the background's inline `aapt:attr` gradient, but does not check pixel values. Neither test can prove the drawable renders those resources correctly: `androidx.compose.ui.test` only attaches after dismissal, and `ActivityScenario` recreates the activity in-process and bypasses the cold-launch path. Visual verification is a cold-launch capture compared against the Figma render, not an instrumented test — see below.
- **Cold-launch capture needs a non-ATD emulator image.** The managed `pixel2Api33Atd` device cannot composite a starting window for capture: `adb exec-out screencap` returns an all-black frame and the emulator console's own screenshot command returns a static grey frame, before and during launch. Boot the host's non-ATD `pixel8Api35` AVD instead (`-read-only -no-snapshot`, under the gate's device lock), install the gate-built APK, and run `am start` and `screencap -p` in a loop inside one `adb shell` command so there is no adb round-trip between frames — a cold debug start takes roughly 9–12s, so several frames catch the splash. This is the same ATD-black-framebuffer limitation documented for real-system-bar Compose captures; see [Development verification — Compose evidence](development-verification-compose-evidence.md#compose-evidence).
- **Launcher icon capture needs a home screen, not just a non-ATD framebuffer ([#1546](https://github.com/pyrycode/pyrycode-mobile/issues/1546)).** The ATD image's limitation goes further for the launcher icon than for the splash window: the API 33 ATD image has no launcher app at all, so there is no home screen to capture even once the framebuffer itself composites. Use a Play Store managed AVD image (Pixel Launcher) for launcher icon and themed-icon captures. Pixel Launcher only themes workspace and dock icons, not the app drawer, so a themed-icon check has to put the icon on the workspace first — `adb install --install-reason 4` does that — before toggling themed icons on through `grid_control/icon_themed` and capturing.
- **Pre-Android-12 fallback.** The compat library renders a visually simpler splash on API ≤ 30 — solid background + centred icon, no platform-level animation envelope. Expected, not a regression. PR bodies should call this out if an Android 11/12 image is available.
- **No `setKeepOnScreenCondition { ... }` gating.** Deliberate — the #13 `produceState` + empty-`Surface` placeholder pattern already covers the preference-read window. If a future ticket needs splash-time data preloading, evaluate whether the data can move into the placeholder window first; adopting `setKeepOnScreenCondition` couples splash duration to arbitrary work and risks a long-pause regression.

## Related

- Ticket notes: [`../codebase/80.md`](../codebase/80.md) (plumbing); [`../codebase/13.md`](../codebase/13.md) (the conditional NavHost start destination that makes splash-side gating unnecessary).
- Specs: `docs/specs/architecture/80-splash-screen-api-plumbing.md`; `docs/specs/architecture/1545-splash-snowflake-dark.md` (dark background + branded mark); `docs/specs/architecture/1546-launcher-icon-snowflake.md` (the launcher icon's matching redesign).
- Sibling feature: [Navigation](navigation.md) — owns the post-splash start-destination decision.
- Related ticket: [#1546](https://github.com/pyrycode/pyrycode-mobile/issues/1546), the launcher icon, which now draws its own radial-glow background and reuses `@color/splash_mark` directly for its mark, so the two brand marks already share one colour resource.
- Capture technique: [Development verification — Compose evidence](development-verification-compose-evidence.md#compose-evidence) and [Development verification — emulator and real evidence](development-verification-emulator-evidence.md#emulator-and-real-evidence) for the ATD/non-ATD split this ticket's visual check relied on.
