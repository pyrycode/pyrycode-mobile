# Architecture: R8 / ProGuard Rules Baseline + `assembleRelease` Green (#81)

## Context

`./gradlew assembleDebug` is green (Phase 0 exit criterion). `assembleRelease` currently builds with `isMinifyEnabled = false` — so R8 is not exercised, and we have no signal on whether the app survives shrinking + obfuscation. Phase 5 (Play Store internal testing) needs a release build that minifies, shrinks resources, signs (debug keystore for now), installs, launches, and reaches `ChannelListScreen` with fakes intact.

This ticket lands the **minimum** baseline: turn on R8 + resource shrinking, add only the ProGuard rules our currently-wired deps actually require (sourced from each library's official docs), and verify the release APK runs end-to-end on an emulator. Release-keystore signing config and Phase 4 serialization rules are explicitly out of scope.

## Design source

N/A — build infrastructure; no Figma.

## Files to read first

- `app/build.gradle.kts:24-32` — current `release` build type; the `isMinifyEnabled = false` line is the change point.
- `app/build.gradle.kts:43-69` — the wired-dependency list. **This is the canonical source of truth for which rules are needed.** Anything not in this list does not need rules (e.g. Ktor, kotlinx.serialization — both deferred to Phase 4).
- `app/proguard-rules.pro` — currently only stock template comments; this is the file to populate.
- `app/src/main/java/de/pyryco/mobile/PyryApp.kt` — Koin `startKoin { modules(appModule) }` site; relevant when checking that Koin's runtime DSL survives R8.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` — the Koin module uses the explicit-constructor DSL (`single { FakeConversationRepository() }`, `viewModel { ChannelListViewModel(get()) }`). No `@Single`/`@KoinViewModel` annotations, no `koin-annotations`, no KSP. Important: Koin's reflection surface here is small.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:117,144` — `koinViewModel<ChannelListViewModel>()` / `koinViewModel<DiscussionListViewModel>()` call sites; these are the runtime VM lookups that have to survive R8.
- `gradle/libs.versions.toml:1-15` — exact versions in play (Koin BoM `4.0.4`, kotlinx-coroutines `1.10.2`, navigation-compose `2.9.5`, datastore `1.1.7`, kotlinx-datetime `0.6.2`, compose BoM `2026.02.01`, AGP `9.2.1`). When fetching official R8 rules, pin to these versions — older Koin / coroutines docs prescribe different rules.

## Design

### Two edit points, no new files

1. **`app/build.gradle.kts` — `buildTypes.release` block.** Change `isMinifyEnabled` to `true`, add `isShrinkResources = true`, and wire the debug signing config so the resulting APK is installable on an emulator. Final shape:

   ```kotlin
   buildTypes {
       release {
           isMinifyEnabled = true
           isShrinkResources = true
           signingConfig = signingConfigs.getByName("debug")
           proguardFiles(
               getDefaultProguardFile("proguard-android-optimize.txt"),
               "proguard-rules.pro"
           )
       }
   }
   ```

   `signingConfigs.getByName("debug")` references the auto-generated debug config — this is *not* introducing a new `signingConfigs` block (which the ticket forbids), it's reusing the implicit one AGP provides. Phase 5 will add a real `signingConfigs { create("release") { ... } }` block in a separate ticket.

2. **`app/proguard-rules.pro` — populated with the minimum rule set.** Replace the stock-template comment block. Structure the file by library, with a one-line header naming the dep + the version it was authored against. Final shape (logical sections, not literal contents — see § Rule sourcing for what goes inside each):

   ```
   # --- Koin (io.insert-koin:koin-androidx-compose, BoM 4.0.4) ---
   <rules here>

   # --- Kotlin Coroutines (org.jetbrains.kotlinx:kotlinx-coroutines-core 1.10.2) ---
   <rules here>

   # --- AndroidX DataStore Preferences (1.1.7) ---
   <rules here, if any beyond consumer-rules>

   # --- (Compose, Lifecycle, Navigation, kotlinx-datetime: consumer-rules sufficient) ---
   ```

### Rule sourcing — non-negotiable policy

**Every rule must trace to an official source.** No copy-pasted Stack Overflow rules, no "I think we'll need this." Sources, in priority order:

1. The library's own GitHub README / docs site, on the version tag matching `libs.versions.toml`. For Koin 4.x: <https://insert-koin.io/docs/reference/koin-mp/android>. For kotlinx.coroutines: the project's `kotlinx-coroutines-core/src/main/resources/META-INF/proguard/` rules and the `kotlinx-coroutines-android/META-INF/proguard/coroutines.pro` file shipped as consumer-rules.
2. `context7` (use `mcp__plugin_context7_context7__query-docs`) when the official README is thin or version-pinned guidance is unclear. Fetch current docs — don't trust training-data memory for ProGuard rules, they shift across library versions.
3. If a rule is needed but no official source documents it, that's a signal it's not needed — drop it and let the build tell you.

**`-dontwarn` is allowed only with a comment naming the library and the missing class it suppresses.** Example: `-dontwarn kotlinx.coroutines.debug.AgentPremain  # debug-only entry, absent in release classpath`. Bare `-dontwarn` lines are rejected by code-review (and by AC #3, which treats unexplained warnings as failures).

### Currently-wired deps and what each likely needs

This is a **starting hypothesis** — the build log is the ground truth. Confirm each entry below by reading official docs at the version pinned in `libs.versions.toml`; add only what's required.

| Library | Likely rule footprint | Notes |
|---|---|---|
| Compose (BoM 2026.02.01) | None — ships consumer-rules | Both `ui` and `material3` artifacts include their own ProGuard rules. |
| Koin 4.0.4 (`koin-androidx-compose`) | Small set: keep `KoinComponent`, keep no-arg constructors of injected types, keep `viewModel { }` factory lambdas. Koin 4 with explicit-constructor DSL is mostly inline — but the runtime still uses `KClass` for type lookup. | Check `insert-koin.io` docs for the version-pinned set. The annotation-based path (`koin-annotations`) is **not** used here, so KSP-related rules don't apply. |
| Kotlin Coroutines 1.10.2 | `kotlinx-coroutines-android` ships consumer-rules. `kotlinx-coroutines-core` does too. Expected additions: `-dontwarn` lines for debug-agent classes that don't exist on Android. | Verify: with current consumer-rules nothing extra may be needed. Empty section is acceptable if build is green and runtime survives. |
| AndroidX DataStore Preferences 1.1.7 | None expected — the Preferences flavor doesn't use Proto codegen. | If runtime crash on read/write, revisit. |
| AndroidX Lifecycle / ViewModel / Navigation Compose | Ship consumer-rules. | `androidx.navigation:navigation-compose` includes rules for the `composable("route")` destination registry — verify by reaching `ChannelListScreen` post-shrink. |
| kotlinx-datetime 0.6.2 | None — pure Kotlin, no reflection. | — |

Two deps that need a careful look despite shipping consumer-rules:

- **Koin's `viewModel { ChannelListViewModel(get()) }` lookups via `koinViewModel<T>()`.** R8 will rename `ChannelListViewModel` and `DiscussionListViewModel` unless they're kept. Koin's official rules typically handle this via a generic `-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }` rule, but verify against current Koin docs before adding.
- **Navigation Compose route registration.** `composable("channels")` / etc. — string-based, no reflection on the destination class, so usually fine. Confirmed by AC #4 (reach `ChannelListScreen`).

### Verification loop (the "fix what R8 complains about" workflow)

This is the developer's iteration loop, not a code structure. Build → read errors → add minimum rule → repeat.

1. Run `./gradlew assembleRelease`. Capture the full log.
2. If it succeeds → install on emulator, launch, navigate to `ChannelListScreen`. If that's green → done.
3. If it fails with `R8: missing class` errors → add a `-dontwarn` for each (with comment naming why), or a `-keep` if the class is actually used at runtime via reflection. Distinguish: missing-class warning ≠ needs-keep. A class that's missing from the classpath but never instantiated at runtime gets `-dontwarn`; one that's looked up reflectively gets `-keep`.
4. If it succeeds but the APK crashes at launch / on navigation → read the stacktrace, identify the obfuscated type, add the minimum `-keep` for that type or its package. Re-run.
5. Record the final APK size (`ls -la app/build/outputs/apk/release/app-release.apk`) for the PR body.

The loop terminates when AC #3 + #4 are both green. The architect's expectation: ≤ 5 iterations. If the developer hits 10+ iterations, that's a signal to escalate — likely a library version mismatch against current ProGuard guidance.

## State + concurrency model

N/A — build-tooling change, no runtime state, no flows.

## Error handling

- **R8 build errors during `assembleRelease`:** treated as failures; developer iterates per § Verification loop. AC #3 explicitly forbids ignoring warnings (only `-dontwarn` with comment is allowed).
- **Crash at launch / navigation:** treated as failures; developer adds the minimum `-keep` rule. AC #4 catches this.
- **APK installs but crashes silently:** check logcat with `adb logcat | grep -i 'AndroidRuntime\|de.pyryco'` immediately after install. The build is not green until the channel list renders.

## Testing strategy

- **No unit-test changes.** R8 doesn't run for `./gradlew test`, and there's no production code to unit-test here.
- **Manual verification on emulator** is the binding test. Boot an emulator (`emulator -avd <name>` or via Android Studio), then:
  ```
  ./gradlew installRelease
  adb shell am start -n de.pyryco.mobile/.MainActivity
  ```
  Confirm the channel list renders with seeded fakes, navigate into a thread and back, navigate to settings if wired, no crashes.
- **Instrumented tests are not the right vehicle** — they run against the debug build by default, so they don't exercise R8. Wiring `testBuildType = "release"` is out of scope (it has its own consequences for instrumented testing and belongs in a Phase 5 follow-up).
- **What code-review will check:**
  - `app/build.gradle.kts` has exactly the three changes specified (`isMinifyEnabled`, `isShrinkResources`, `signingConfig`); no stray `signingConfigs { ... }` block.
  - `app/proguard-rules.pro` has every rule attributable to a wired library or to a `-dontwarn` with comment.
  - PR body lists the APK size (baseline number for future tracking).
  - Build log excerpt in PR confirms no missing-class warnings and successful R8 pass.

## Open questions

- **Does Koin 4.0.4 with the explicit-constructor DSL need any rules at all?** Strong hypothesis: minimal or none, because there's no `koin-annotations` and no KSP. Developer should verify by reading insert-koin.io docs for 4.x — if Koin officially says "no rules needed for runtime DSL on Android," the Koin section in `proguard-rules.pro` can be empty (just a header comment noting where the answer was verified).
- **APK size as a baseline:** record both raw size and shrunk size (`./gradlew assembleRelease` produces both `app-release-unsigned.apk` and the signed APK in `app/build/outputs/apk/release/`). The signed APK size is the number to record. We have no prior baseline to compare against — this run *is* the baseline.
- **Should `vcsInfo { include = false }` be set to keep the APK reproducible?** Out of scope for this ticket — defer until release-keystore work.
