# Spec — Adaptive Icon Resource Scaffolding (Placeholder)

**Ticket:** [#79](https://github.com/pyrycode/pyrycode-mobile/issues/79)
**Size:** S (resource-only; no Kotlin)

## Context

The Android Studio project template seeded the repo with default adaptive-icon scaffolding (green Android-robot foreground, green-grid background) plus per-density `.webp` raster icons. Plan Phase 5 will deliver real branding; this ticket lands the **structural** scaffolding so the eventual branding swap is a one-drawable change:

- Foreground glyph becomes a simple vector "P" instead of the green robot.
- Background fill is externalised to a named color resource (single edit point for branding).
- Adaptive-icon XMLs move from `mipmap-anydpi/` to the qualifier `<adaptive-icon>` actually requires: `mipmap-anydpi-v26/`. They render today only because of Android's resource fallback chain — fragile, and the wrong scaffolding to leave behind for the branding ticket.
- Legacy `.webp` raster icons are deleted; min-SDK 33 means the adaptive icon supersedes them everywhere.

No Kotlin changes. No manifest changes (`android:icon="@mipmap/ic_launcher"` resolves to whichever density bucket has the resource — moving from `mipmap-anydpi/` to `mipmap-anydpi-v26/` is transparent).

## Design source

N/A — placeholder asset per ticket body. Real launcher icon design lands as a separate ticket once branding is scoped (Plan Phase 5).

## Files to read first

- `app/src/main/res/mipmap-anydpi/ic_launcher.xml` — current `<adaptive-icon>` definition; preserve the three-layer (background / foreground / monochrome) structure when moving to `mipmap-anydpi-v26/`. Reuses the foreground as monochrome — keep that.
- `app/src/main/res/mipmap-anydpi/ic_launcher_round.xml` — identical to `ic_launcher.xml` (round mask is applied by the launcher, not the XML). Mirror any edit.
- `app/src/main/res/drawable/ic_launcher_background.xml` — current 170-line green-grid vector; will be replaced wholesale with a tiny `<vector>` whose single `<path>` fillColor references `@color/ic_launcher_background`.
- `app/src/main/res/drawable/ic_launcher_foreground.xml` — current Android-robot vector; will be replaced wholesale with a simple "P" glyph. `viewportWidth/Height="108"` and the **safe zone** (centre 66dp circle inside the 108dp viewport) are the constraints to respect.
- `app/src/main/res/values/colors.xml:1-10` — existing color palette; add the new `ic_launcher_background` entry here. Note: the existing `purple_*`/`teal_*`/`black`/`white` entries are template residue and not consumed by Compose code (`MaterialTheme` reads from `Color.kt`), so don't try to "reuse" them.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt:4` — `primaryLight = Color(0xFF32628D)` is the M3 seed (light) primary. The ticket asks for a launcher-background value that *mirrors* the in-app primary so the icon feels native to the app once branding lands. Use `#FF32628D` (full alpha; launcher icons render opaquely).
- `app/src/main/AndroidManifest.xml:10-12` — `android:icon="@mipmap/ic_launcher"` + `android:roundIcon="@mipmap/ic_launcher_round"`. Reference-only; no edits.

## Design

### Resource file changes

1. **Move adaptive-icon XMLs.**
   - Create directory `app/src/main/res/mipmap-anydpi-v26/`.
   - `git mv app/src/main/res/mipmap-anydpi/ic_launcher.xml app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
   - `git mv app/src/main/res/mipmap-anydpi/ic_launcher_round.xml app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
   - Delete the now-empty `app/src/main/res/mipmap-anydpi/` directory (`git rm -r` after the moves — `git mv` will not remove the parent).
   - **No content change** to the two XMLs themselves; their three-layer structure (`<background>` / `<foreground>` / `<monochrome>` all currently pointing at `@drawable/ic_launcher_{background,foreground}`) is correct and stays as-is. Monochrome continues to reuse the foreground vector (acceptable per ticket).

2. **Rewrite `app/src/main/res/drawable/ic_launcher_background.xml`** to a minimal solid-fill vector that references the new color resource:

   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <vector xmlns:android="http://schemas.android.com/apk/res/android"
       android:width="108dp"
       android:height="108dp"
       android:viewportWidth="108"
       android:viewportHeight="108">
       <path
           android:fillColor="@color/ic_launcher_background"
           android:pathData="M0,0h108v108h-108z" />
   </vector>
   ```

   The single path fills the full 108×108 viewport. No grid lines, no hardcoded hex.

3. **Rewrite `app/src/main/res/drawable/ic_launcher_foreground.xml`** to a "P" glyph centred inside the adaptive-icon safe zone. Constraints:
   - `viewportWidth/Height="108"` (matches background).
   - Safe zone = centre **66dp** circle inside the 108dp viewport. Glyph artwork must fit within roughly the **centre 72×72** square (gives a small margin inside the safe zone). System masks (square, circle, squircle, etc.) crop everything outside the inner 66dp circle.
   - `android:fillColor="#FFFFFFFF"` for the glyph (white on the seed-blue background gives high contrast; matches the white-on-blue feel of `ic_pyry_logo.xml`).
   - A single `<path>` with `android:pathData` drawing a stylised capital "P". Build the path geometry yourself (the developer agent may sketch in any vector editor or hand-author the path commands); the only contract is:
     - Visually reads as the letter "P" at launcher size (≥48dp).
     - All artwork fits in the centre 72×72 region of the 108×108 viewport.
     - Single colour fill (white). No gradients, no shadows.
   - No `<aapt:attr>` gradient blocks. No `strokeColor` decorations. Keep it short — target under 30 lines total.
   - **Reusable as monochrome.** Because the system renders the monochrome layer as a silhouette (colour-stripped), the white fill is irrelevant in themed-icon mode and what matters is the alpha shape. A solid white "P" fill is correct.

4. **Append to `app/src/main/res/values/colors.xml`** a new entry inside the existing `<resources>` block, with a comment explaining the duplication-with-theme:

   ```xml
   <!--
     Launcher icon background. Mirrors the M3 seed primary (primaryLight in
     ui/theme/Color.kt) so the icon feels consistent with the in-app theme.
     Cannot reference theme attributes here — launchers render outside the
     app process, so ?attr/colorPrimary does not resolve. Update both
     locations together when the branding ticket lands.
   -->
   <color name="ic_launcher_background">#FF32628D</color>
   ```

5. **Delete legacy raster icons.** Remove every file matching `app/src/main/res/mipmap-*dpi/ic_launcher*.webp`. Concretely, 10 files across 5 density buckets:
   - `mipmap-mdpi/ic_launcher.webp`, `mipmap-mdpi/ic_launcher_round.webp`
   - `mipmap-hdpi/ic_launcher.webp`, `mipmap-hdpi/ic_launcher_round.webp`
   - `mipmap-xhdpi/ic_launcher.webp`, `mipmap-xhdpi/ic_launcher_round.webp`
   - `mipmap-xxhdpi/ic_launcher.webp`, `mipmap-xxhdpi/ic_launcher_round.webp`
   - `mipmap-xxxhdpi/ic_launcher.webp`, `mipmap-xxxhdpi/ic_launcher_round.webp`

   The density bucket directories themselves will be left empty. Delete the now-empty `mipmap-*dpi/` directories too (`git rm` of files leaves empty dirs untracked; remove them on the filesystem so the tree is clean). Adaptive icons in `mipmap-anydpi-v26/` are the sole launcher icon source on min-SDK 33 (the project floor — see CLAUDE.md "Stack").

### Why `mipmap-anydpi-v26/` specifically

`<adaptive-icon>` was introduced in API 26 (Oreo). The resource qualifier `-v26` is the documented way to declare "this resource requires API 26 or higher" — pre-26 devices would fall back to a raster `ic_launcher.png` (which we no longer ship; min-SDK 33 means pre-26 devices can't install the APK at all, so the absence is moot). `mipmap-anydpi/` (no version qualifier) happens to work today only because the resource resolver finds it as the only candidate; on a hypothetical API-25 device it would attempt to parse `<adaptive-icon>` and crash. The move is the correct scaffolding, not a behavioural change for our supported devices.

### State, concurrency, errors

N/A — resource-only change. No runtime state, no flows, no failure modes. Build-time validation only.

## Testing strategy

No unit or instrumented test changes. Validation is build + visual:

- **`./gradlew assembleDebug`** must succeed. AAPT2 validates that `@color/ic_launcher_background` resolves, the `<vector>` parses, and the moved XMLs are picked up from the new qualifier directory.
- **`./gradlew lint`** must succeed with no new icon-related warnings. Specifically, lint should *not* warn about:
  - `IconLauncherShape` / `IconMissingDensityFolder` (the adaptive-icon supersedes per-density buckets on min-SDK 33).
  - `IconDuplicates` (no longer applicable once raster files are gone).
  - `IconExpectedSize` (vector drawables don't have a pixel size).
  - If a new warning appears, treat it as a spec gap, not a lint suppression target.
- **Manual launcher check** (the AC's load-bearing test, since the renderer is the launcher, not Gradle). After `./gradlew installDebug` on a connected device or emulator running Android 13+ (the min-SDK target):
  1. **Regular mode.** Launcher (e.g. Pixel Launcher, default emulator) shows the "P" glyph on a blue (`#32628D`) background, masked to whatever shape the launcher uses (square / circle / squircle).
  2. **Round mask.** On a launcher that applies a circular mask, the glyph stays centred and fully visible (i.e. the artwork respects the safe zone).
  3. **Themed icons.** On Android 13+: Settings → Wallpaper & style → enable "Themed icons". The launcher renders the "P" as a monochrome silhouette tinted by the current Material You palette. The shape should still be unambiguously "P".

  The developer agent cannot perform the visual check inside its environment. State explicitly in the PR description that the build + lint passes, and note that the visual launcher check is a code-review responsibility (or, if the dev has a device/emulator available, attach a screenshot).

## Open questions

- **Path data for the "P".** Deliberately under-specified — any clean, single-colour, safe-zone-respecting "P" satisfies the contract. The developer may hand-author path commands or generate them in an editor; do not block on aesthetic perfection (this is a placeholder; the branding ticket replaces it wholesale).
- **`mipmap-anydpi/` parent removal.** After moving the two XMLs out, the directory is empty. Filesystem rm is sufficient; git tracks files not directories, so `git status` will be clean once the files are gone.
