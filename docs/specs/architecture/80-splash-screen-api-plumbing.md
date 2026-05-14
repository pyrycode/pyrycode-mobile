# Spec: Splash Screen API plumbing (Android 12+) (#80)

## Context

Plan Phase 5 calls for an Android 12+ Splash Screen API integration. The
AndroidX compat library `androidx.core:core-splashscreen` backports the same
splash configuration surface to API 23+, so a single setup covers every
supported device (this app's `minSdk` is 33).

This ticket is plumbing only:

- One dependency (`androidx.core:core-splashscreen`).
- One new placeholder vector drawable.
- One new XML theme.
- One manifest attribute change on the launcher activity.
- One `installSplashScreen()` call in `MainActivity.onCreate`.

No animation, no exit-listener tweaks, no `setKeepOnScreenCondition { ... }`
gating. The conditional NavHost start destination wired in #13 already
prevents the "fresh launch flashes Welcome before flipping to Channel List"
problem without splash-side gating — splash dismisses as soon as the first
frame is ready, and #13's `produceState`+empty-`Surface` placeholder
handles the brief preference-read window.

The point of this ticket is: stop showing the bare app theme on cold launch,
start showing a system-managed splash with the brand color and a centred
mark.

## Design source

N/A — placeholder vector icon on brand color; real branded splash (final
mark + transition) is a deliberate follow-up to be filed under Phase 5 polish.

## Files to read first

- `app/src/main/AndroidManifest.xml` — current launcher activity wiring;
  the launcher `<activity>` theme is what changes.
- `app/src/main/res/values/themes.xml:1-5` — current single-style file;
  add the splash style alongside the existing `Theme.PyrycodeMobile`.
- `app/src/main/res/values/colors.xml:10-17` — `ic_launcher_background`
  (#FF32628D) is the documented brand color and the splash background
  must reuse it; do **not** introduce a parallel color name.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:49-89` —
  `onCreate` signature and the existing `super.onCreate(...)` /
  `enableEdgeToEdge()` / `setContent { ... }` ordering. The
  `installSplashScreen()` call goes **before** `super.onCreate(...)`.
- `gradle/libs.versions.toml:1-50` — version catalog conventions
  (alphabetical `[versions]`, then `[libraries]`, kebab-case alias).
- `app/build.gradle.kts:49-76` — existing `dependencies { ... }` block;
  splash compat is an `implementation` dependency.
- `app/src/main/res/drawable/ic_pyry_logo.xml` — reference only; shows
  the existing brand-mark vector. The new splash drawable is a **separate**
  minimal placeholder, not a rename or move of this file. The branded
  icon (animated, final artwork) is an explicit follow-up.

## Design

### Dependency

Add `androidx.core:core-splashscreen:1.0.1` (the stable AndroidX release;
no `BomVersionCatalog` exists for this artifact, so version the alias
directly in the catalog).

`gradle/libs.versions.toml`:

```toml
[versions]
# ...existing entries...
coreSplashscreen = "1.0.1"

[libraries]
# ...existing entries, alphabetical-ish — keep adjacent to other androidx-core-* entries...
androidx-core-splashscreen = { group = "androidx.core", name = "core-splashscreen", version.ref = "coreSplashscreen" }
```

`app/build.gradle.kts` — add one `implementation` line in the existing
`dependencies { ... }` block, adjacent to `libs.androidx.core.ktx`:

```kotlin
implementation(libs.androidx.core.splashscreen)
```

### Placeholder vector drawable

Create `app/src/main/res/drawable/ic_splash_logo.xml`. Spec target: the
Android 12+ splash icon spec — **288×288dp viewport with the visible mark
fitting inside a 192×192dp inner mask** (so a 48dp padding ring on each
side). The compat library honours the same drawable dimensions.

Contract:

- `android:width="288dp"`, `android:height="288dp"`,
  `android:viewportWidth="288"`, `android:viewportHeight="288"`.
- A single solid-fill white `<path>` describing a minimal placeholder
  glyph centred in the viewport and contained within the inner 192dp
  square (i.e. all path coordinates between 48 and 240).
- White fill (`#FFFFFFFF`) so the mark reads against the brand-coloured
  background; **no `?attr/...` references** — splash drawables resolve
  outside the app theme.

Concrete suggestion (developer free to substitute equivalent simple shape):
a centred filled circle, radius 64dp, at (144, 144). One `<path>` element
using arcs. The point is a recognisable "something is here" mark, not
brand identity — the branded mark is the follow-up ticket.

**Do not** reuse `ic_pyry_logo.xml`. Its viewport is 104dp (wrong size
for the splash spec) and its content fills the entire viewport (no inner
padding mask). Copying the paths into a 288dp viewport with translation
math is more invasive than drawing one circle.

### Splash theme

Append to `app/src/main/res/values/themes.xml`:

```xml
<style name="Theme.PyrycodeMobile.SplashScreen" parent="Theme.SplashScreen">
    <item name="windowSplashScreenBackground">@color/ic_launcher_background</item>
    <item name="windowSplashScreenAnimatedIcon">@drawable/ic_splash_logo</item>
    <item name="postSplashScreenTheme">@style/Theme.PyrycodeMobile</item>
</style>
```

Notes:

- `parent="Theme.SplashScreen"` resolves to the compat library's theme
  (no `android:` namespace; it ships in `androidx.core:core-splashscreen`).
- `postSplashScreenTheme` is the theme the activity switches to after
  `installSplashScreen()` runs. Pointing it back at the existing
  `Theme.PyrycodeMobile` preserves today's behaviour for everything
  after first frame.
- No `windowSplashScreenAnimationDuration`, no
  `windowSplashScreenIconBackgroundColor` — those are part of the
  branded-splash follow-up, not this plumbing ticket. Setting them now
  would lock in placeholder values that the follow-up has to undo.
- The existing `Theme.PyrycodeMobile` style stays untouched — splash
  theming is additive.

### Manifest

In `app/src/main/AndroidManifest.xml`, change **only the launcher
`<activity>`** theme to the splash theme. The application-level
`android:theme` stays at `@style/Theme.PyrycodeMobile`:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:label="@string/app_name"
    android:theme="@style/Theme.PyrycodeMobile.SplashScreen">
    <!-- intent-filter unchanged -->
</activity>
```

Rationale: the activity's `android:theme` is what the system reads when
drawing the first frame before the process is alive. The
application-level theme is the fallback for activities that don't declare
their own — keeping it at `Theme.PyrycodeMobile` means any future
non-launcher activity inherits the normal app theme by default.

### MainActivity

Add the install call as the first statement in `onCreate`, before
`super.onCreate(savedInstanceState)`:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
        // existing body, unchanged
    }
}
```

Import:

```kotlin
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
```

Notes:

- The return value of `installSplashScreen()` (a `SplashScreen` handle)
  is discarded. We do **not** call `setKeepOnScreenCondition { ... }` —
  Out of Scope per the ticket, and the existing #13 conditional start
  destination already avoids the only flash this would prevent.
- The call must precede `super.onCreate(...)`. AndroidX's
  `installSplashScreen()` swaps the activity theme from the splash theme
  to `postSplashScreenTheme` before the activity's window is realised;
  invoking it after `super.onCreate(...)` causes a visible
  splash-theme-leaked-into-app-frame glitch on some OEMs.

### Flow

1. Cold launch → system reads launcher activity theme
   (`Theme.PyrycodeMobile.SplashScreen`) and draws splash window with
   brand-color background + centred placeholder icon.
2. Process spins up; `MainActivity.onCreate` enters.
3. `installSplashScreen()` swaps the theme to `Theme.PyrycodeMobile`
   (the `postSplashScreenTheme`) and registers the splash-to-app
   handoff with the platform.
4. `super.onCreate(...)`, `enableEdgeToEdge()`, `setContent { ... }`
   run as today.
5. First Compose frame is produced. The splash window dismisses; the
   `produceState`-driven `Surface { }` placeholder shown by
   `MainActivity.kt:64-78` (paired === null) covers the
   single-frame-or-two preference-read window.
6. `appPreferences.pairedServerExists.first()` resolves; NavHost
   mounts with the chosen start destination. No additional flash.

## State + concurrency model

No new state. No new flows. `installSplashScreen()` is a one-shot call
with no observable lifecycle beyond the platform's own splash window.

## Error handling

No runtime failure modes — the splash theme is resolved at activity
creation by the OS; misconfiguration surfaces at build time (theme
resource not found, drawable not found) and is caught by
`./gradlew assembleDebug` and `./gradlew lint`.

## Testing strategy

No automated tests for this ticket. Rationale:

- The splash is a system-window concern; `androidx.compose.ui.test`
  observes the Compose hierarchy after the splash has dismissed, so it
  can't assert on the splash frame itself.
- Activity-level instrumentation (`ActivityScenario`) recreates the
  activity inside the test harness, which bypasses the cold-launch path
  the splash actually runs on.
- The risk being shipped is "splash plumbing is wired correctly" —
  the deterministic check is the build (theme attributes, drawable
  reference, manifest theme reference, import resolution) plus a
  one-shot manual cold-launch verification, as the ticket's AC#4 already
  requires.

Manual verification checklist (run before opening PR):

- `./gradlew assembleDebug` → green, no new warnings.
- `./gradlew lint` → green, no new warnings.
- Install on an Android 13+ emulator. Force-stop the app. Cold-launch.
  Confirm:
  - Brand-coloured splash with centred placeholder icon appears.
  - Transition to Welcome (fresh install) or Channel List (after pairing)
    has no flash, gap, or duplicate frame between splash and first
    screen.
  - No artificial pause — splash dismisses as soon as the first Compose
    frame is ready.
- If an Android 11/12 emulator image is available, repeat the cold-launch
  check and note any fallback behaviour in the PR body (the compat
  library's pre-Android-12 splash is visually distinct — solid background
  + centred icon, no platform-level animation envelope — and is
  expected, not a regression).

## Open questions

None blocking implementation. Two deferred to the follow-up branded-splash
ticket:

- Final splash icon artwork and viewport — the placeholder circle is
  intentionally generic.
- Exit-animation listener for any custom transition into the first
  Compose frame — out of scope here per ticket body.
