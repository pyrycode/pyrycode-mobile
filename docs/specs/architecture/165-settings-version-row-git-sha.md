# Spec — Settings Version row: Git short SHA via Gradle (#165)

## Files to read first

- `app/build.gradle.kts` (whole file, ~80 lines) — current `defaultConfig`, `buildFeatures.buildConfig = true` already enabled. The new `buildConfigField` and `ValueSource` declaration land here.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:210-214` — the `About` section's Version row; line 213's `"build ${BuildConfig.VERSION_CODE}"` becomes `"build ${BuildConfig.GIT_SHA}"`. Line 212 (`Version ${BuildConfig.VERSION_NAME}`) stays.
- `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt:22-42` — existing `versionRow_rendersBuildConfigVersionName` test; the new assertion follows the same `composeTestRule.onNode(hasText(..., substring = true)).performScrollTo().assertExists()` shape.
- `gradle/libs.versions.toml` — confirm `agp = "9.2.1"` (already verified — modern enough for `providers.of(ValueSource)`). No new dependency required.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

The About section's Version row is an M3 two-line `ListItem`: headline `Version <semver>` in `body-large` on `Schemes/on-surface`, supporting `build <git-short-sha>` in `body-small` on `Schemes/on-surface-variant`. Figma reference value: `build a8f3c2d`. The row is non-clickable (no trailing chevron). Surrounding rows and section spacing are unchanged; only the supporting-text source flips from `BuildConfig.VERSION_CODE` (integer `1`) to `BuildConfig.GIT_SHA` (7-char abbreviated commit hash, or the literal string `unknown` in non-git environments).

## Context

`SettingsScreen.kt:213` currently renders `"build ${BuildConfig.VERSION_CODE}"` — the integer `versionCode = 1` from `app/build.gradle.kts:21`. That value is useless for diagnostics: every shipped APK from the same `versionCode` looks identical, so a Settings screenshot can't be mapped back to a commit. Pyrycode is in Phase 0 with no stable release cadence; the Git short SHA is the only deterministic build identifier we have.

The injection happens at Gradle configuration time. The constraints:

1. **Configuration-cache safe** — `./gradlew help --configuration-cache` must pass on both cold and warm runs. AGP 9.2+ marks reads from arbitrary processes during configuration as cache-invalidating unless funneled through `providers.exec` or `ValueSource`.
2. **Resilient to non-git environments** — CI builds from tarballs, F-Droid reproducible builds, and `git`-less Docker images must succeed with `GIT_SHA = "unknown"` rather than failing the build.

The canonical Gradle pattern for both constraints together is a `ValueSource` that wraps `ExecOperations` with exception handling. `providers.exec { ... }` alone covers constraint (1) but not (2) — when the `git` binary is absent, `providers.exec` throws at provider resolution time, not at a recoverable seam.

## Design

### 1. `app/build.gradle.kts` — `GIT_SHA` buildConfigField

Add, at the top of `app/build.gradle.kts` (after the `plugins { ... }` block, before `android { ... }`), a parameterless `ValueSource` that runs `git rev-parse --short HEAD` via injected `ExecOperations`, catches all exceptions, and returns `"unknown"` on any failure (non-zero exit, blank stdout, or thrown `IOException` / `BuildException` from a missing binary).

Contract sketch (the developer fills in the body):

```kotlin
abstract class GitShaValueSource : ValueSource<String, ValueSourceParameters.None> {
    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String {
        // try ExecOperations.exec { commandLine("git", "rev-parse", "--short", "HEAD"); ... }
        // capture stdout; on success+non-blank → trim()
        // on any failure path → "unknown"
    }
}
```

Then inside `android { defaultConfig { ... } }`, after the existing `versionName = "1.0"`:

```kotlin
val gitSha = providers.of(GitShaValueSource::class.java) {}
buildConfigField("String", "GIT_SHA", "\"${gitSha.get()}\"")
```

Key correctness notes for the developer:

- The value passed to `buildConfigField` for a `String` type must be a quoted Java string literal — note the embedded `\"` around the SHA. A bare `gitSha.get()` produces an unquoted identifier and breaks the generated `BuildConfig.java`.
- `ExecOperations.exec { ... }` must set `isIgnoreExitValue = true` so a non-git checkout (exit 128) doesn't throw — let the empty-stdout path produce `"unknown"`.
- Wrap the whole `exec` call in a single `try { ... } catch (t: Throwable) { "unknown" }`. Catch `Throwable` (not `Exception`) so `ProcessException` and `ExecException` subtypes don't slip past; this is a build-time fallback, not application logic.
- Capture stdout into a `ByteArrayOutputStream` and decode with `Charsets.UTF_8`. `trim()` the result; if blank, return `"unknown"`.
- Don't suppress stderr — let Gradle's normal logging pick it up, useful when diagnosing CI weirdness.

### 2. `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt`

Line 213 only:

```diff
-                supporting = "build ${BuildConfig.VERSION_CODE}",
+                supporting = "build ${BuildConfig.GIT_SHA}",
```

Nothing else in this file changes. `BuildConfig` is already imported (line 42). The headline on line 212 (`"Version ${BuildConfig.VERSION_NAME}"`) is left alone per the ticket body.

### 3. `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt`

Add one new `@Test` method, modeled on the existing `versionRow_rendersBuildConfigVersionName` at lines 22-42. Place it directly after that test for proximity.

Test contract:

- Sets `SettingsScreen` content with the same default parameters used by the sibling tests (any `archivedDiscussionCount`, `ThemeMode.SYSTEM`, no-op lambdas).
- Asserts a node exists with text containing `"build ${BuildConfig.GIT_SHA}"` (substring match, then `performScrollTo()`, then `assertExists()` — the row sits near the bottom of a `verticalScroll` column).
- Suggested name: `versionRow_rendersSupportingTextWithGitSha`.

Do **not** assert against a hardcoded SHA. `BuildConfig.GIT_SHA` at test time is whatever the test APK was built with — could be a real short SHA on a dev machine or `"unknown"` in CI. Comparing to `BuildConfig.GIT_SHA` itself is the correct invariant: it verifies the binding between the build-time field and the rendered text, regardless of value.

## State + concurrency model

Not applicable. `BuildConfig.GIT_SHA` is a compile-time `String` constant injected by Gradle. No runtime state, no flows, no scopes.

## Error handling

Single failure mode, fully handled at the Gradle layer: the `GitShaValueSource.obtain()` method catches all exceptions and returns `"unknown"`. There is no UI-level error path — `"build unknown"` is a valid render and itself the failure signal.

The UI does not validate the SHA format (length, hex-only). A malformed value would still render verbatim; we accept that because the only producer is the Gradle layer above.

## Testing strategy

- **Unit (`./gradlew test`)** — no new unit tests. The Gradle build script is implicitly exercised by every build; the Compose UI uses a constant.
- **Instrumented (`./gradlew connectedAndroidTest`)** — one new test in `SettingsScreenTest.kt` (see § 3) asserting the supporting-text binding to `BuildConfig.GIT_SHA`.
- **Configuration-cache verification** — covered by AC 4. The developer runs `./gradlew help --configuration-cache` twice; the second invocation must log `Reusing configuration cache.` (or equivalent for AGP 9.2 — phrasing differs slightly across Gradle minor versions; the success signal is the absence of `Calculating task graph as no configuration cache is available...` on the second run).

The configuration-cache AC is the load-bearing one — it's why we route through `ValueSource` instead of a naive `"git rev-parse".execute().text.trim()`. The developer should run the two `--configuration-cache` invocations explicitly before opening the PR, not assume it works.

## Open questions

None. The ticket is fully specified; AGP 9.2.1 supports the canonical pattern; the existing test file has the right shape to extend; the Figma frame matches the existing row layout (no visual restructure needed).
