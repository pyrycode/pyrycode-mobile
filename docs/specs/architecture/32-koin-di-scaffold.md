# Spec — Koin DI Scaffold (#32)

## Files to read first

- `gradle/libs.versions.toml` — version catalog. Add `koinBom` to `[versions]` and two `koin-*` aliases to `[libraries]` (BOM platform + `koin-androidx-compose`). No `[bundles]` entry needed for two libs.
- `app/build.gradle.kts:42-62` — dependencies block. Add `implementation(platform(libs.koin.bom))` next to the existing `platform(libs.androidx.compose.bom)` line, then `implementation(libs.koin.androidx.compose)` alphabetically adjacent (right after the `androidx-*` block, before `kotlinx-*`).
- `app/src/main/AndroidManifest.xml:5-13` — `<application>` tag. Add a single `android:name=".PyryApp"` attribute alongside the existing `android:allowBackup` / `android:theme` attributes; everything else is untouched.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` — read once to confirm: this file is **not** modified. `MainActivity` keeps its current shape; `startKoin` is owned by `PyryApp.onCreate` and Koin is initialised before `MainActivity.onCreate` runs (Android lifecycle guarantee).
- `docs/specs/architecture/8-navigation-compose-setup.md` (Dependency wiring section) — mirror this spec's `libs.versions.toml` + `build.gradle.kts` editing style (alias placement, `Refers to:` comment style, the "bump if Gradle complains, don't downgrade" guidance). The Koin BOM follows the same pattern as `compose-bom`.
- `CLAUDE.md` (Status, Stack, Layout sections) — confirms Phase 0 scope (no real backend, fake repo only), Koin as the DI choice, and the planned location for `PyryApp.kt` (composition root) and `di/` (Koin modules).

## Context

The Pyrycode Mobile codebase has no DI framework wired yet. Three near-term tickets need one in place:

- **#11** (`AppPreferences` DataStore wrapper) — wants `single { AppPreferences(androidContext()) }`.
- **#4** (FakeConversationRepository binding) — wants `single { FakeConversationRepository() } bind ConversationRepository::class`.
- Upcoming ViewModel tickets — will use `viewModel { ... }` from `koin-androidx-compose` and `koinViewModel()` in composables.

If each ticket introduced Koin afresh, three architects would have to design the same scaffold, three developers would each pay the assemble+verify cost of bringing in the BOM, and PR review would have to repeatedly relitigate "is this the right module setup?" This ticket lands the scaffold once with **zero bindings**, so downstream tickets append a single `single { ... }` line to a known location.

No app behaviour changes. The empty `appModule` is intentional — bindings come in their own tickets, each with their own tests.

## Design

### Dependency wiring

**`gradle/libs.versions.toml`** — additions only, do not reorder existing entries:

Add to `[versions]`:

```toml
koinBom = "4.0.4"
```

Add to `[libraries]`, alphabetically positioned after the `kotlinx-*` block:

```toml
koin-bom = { group = "io.insert-koin", name = "koin-bom", version.ref = "koinBom" }
koin-androidx-compose = { group = "io.insert-koin", name = "koin-androidx-compose" }
```

Notes for the developer:

- `koin-androidx-compose` deliberately has no `version.ref` — the BOM pins it. Same pattern Compose BOM uses for `androidx-compose-material3` etc. in this catalog.
- `4.0.4` is the starting pin for Kotlin `2.2.10` + AGP `9.2.1`. If Gradle reports a Kotlin-compiler-version mismatch or an unresolved BOM, bump to the latest stable `4.0.x` patch (`./gradlew --refresh-dependencies assembleDebug`). Do **not** downgrade to `3.5.x` — it predates Kotlin 2.2 alignment, same trap noted in spec #8 for navigation-compose.
- `koin-androidx-compose` is the only Koin artifact this ticket needs. It transitively pulls in `koin-android` and `koin-core`. Do not add either explicitly — that pollutes the catalog and risks version drift.

**`app/build.gradle.kts`** — two new lines in `dependencies { ... }`:

```kotlin
implementation(platform(libs.koin.bom))
implementation(libs.koin.androidx.compose)
```

Placement: put the `platform(libs.koin.bom)` line immediately below the existing `implementation(platform(libs.androidx.compose.bom))` line so the two BOM lines sit together visually. Put `implementation(libs.koin.androidx.compose)` after the `androidx-*` block (i.e., after `implementation(libs.androidx.navigation.compose)`) and before `implementation(libs.kotlinx.coroutines.core)` — alphabetic by alias root (`koin` between `androidx` and `kotlinx`). No test-scope Koin deps in this ticket; Koin's `verify()` and `koin-test` come in whichever future ticket first needs them (per ticket's "Out of scope").

### `PyryApp.kt` (new file)

Path: `app/src/main/java/de/pyryco/mobile/PyryApp.kt`.

```kotlin
package de.pyryco.mobile

import android.app.Application
import de.pyryco.mobile.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class PyryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@PyryApp)
            modules(appModule)
        }
    }
}
```

Design notes:

- No logger setup (`androidLogger(...)`). Koin's default `EmptyLogger` is fine for Phase 0; add `androidLogger(Level.INFO)` later if/when a binding-misconfiguration bug actually surfaces. Evidence-based — don't ship a defense for a failure mode that hasn't been observed.
- No `try/catch` around `startKoin`. A failure here means the app cannot start at all; crashing on launch is the correct behaviour and produces a useful stack trace. No fallback path makes sense at the composition root.
- `modules(appModule)` takes one module today; when a second module is introduced (e.g. a future `dataModule` split-out), the call becomes `modules(appModule, dataModule)`. Single varargs slot — no list-builder needed yet.
- `Application.onCreate()` runs on the main thread before any activity is created. Koin is initialised synchronously before `MainActivity.onCreate` runs, so any `by inject()` / `koinViewModel()` calls from composables are safe by construction.

### `di/AppModule.kt` (new file)

Path: `app/src/main/java/de/pyryco/mobile/di/AppModule.kt`.

```kotlin
package de.pyryco.mobile.di

import org.koin.dsl.module

val appModule = module {
    // Bindings added by downstream tickets:
    //   #11 — AppPreferences (DataStore)
    //   #4  — FakeConversationRepository as ConversationRepository
    //   upcoming UI tickets — ViewModels via viewModel { }
}
```

Design notes:

- **Top-level `val`**, not inside an `object` or a function. Koin's idiomatic shape — downstream `single { ... }` / `viewModel { ... }` lines slot in with no boilerplate.
- The comment block is the **only** comment that ships in this file. It documents *why the file is empty today* (the non-obvious bit) and points the next architect/developer at the tickets that fill it. Once #11 and #4 land, that comment should be deleted in whichever of those tickets lands second — it has done its job. (Architects on #11 and #4: drop these three TODO-style lines as part of your spec's edit list.)
- Single-module today. Split into `dataModule` / `uiModule` / etc. only when this file exceeds ~30 bindings or when a feature module is extracted from `app/`. Premature splitting just adds a `modules(...)` argument list to maintain.

### `AndroidManifest.xml` edit

In `app/src/main/AndroidManifest.xml`, add one attribute to the existing `<application>` tag:

```xml
<application
    android:name=".PyryApp"
    android:allowBackup="true"
    ...
```

Place `android:name` as the **first** attribute (Android convention; lint will not flag either order, but `:name` first is what AGP-generated manifests use and what reviewers expect). Do not touch any other manifest attribute, the `<activity>` block, or the intent filter — they remain identical.

## State + concurrency model

Not applicable — no `ViewModel`, no `StateFlow`, no coroutines introduced by this ticket. Koin's `startKoin` is synchronous and runs on the Android main thread during process startup. The `appModule` is a top-level `val` initialised at class-load time; no thread-safety considerations because nothing reads it concurrently before `Application.onCreate` returns.

## Error handling

Not applicable at the ticket level. The only runtime failure path is "Koin initialisation throws" — handled by Android's default process-crash behaviour. No user-facing surface, no banner/dialog, no IO. If `startKoin` ever throws once bindings exist (e.g. duplicate definition, missing factory), the crash log is the correct debugging surface.

## Testing strategy

- **No new tests** required by the ticket and none should be added. AC explicitly says Koin's `checkModules`/`verify` is *not* required and Koin test helpers are out of scope. An empty `appModule` has nothing meaningful to verify — `verify()` on an empty module is a no-op assertion.
- **`./gradlew test`** — must still pass. Confirms the existing unit-test suite is unaffected (it is — no production code that tests touch is changed).
- **`./gradlew assembleDebug`** — primary signal that this ticket works. A successful debug APK build proves:
  1. Catalog entries resolve (Koin BOM + `koin-androidx-compose` are fetched).
  2. `PyryApp` compiles (Koin imports resolve).
  3. The manifest's `.PyryApp` reference is satisfied (AGP's manifest-merger checks this).
- **Manual smoke test (optional, not required for AC):** `./gradlew installDebug && adb shell am start -n de.pyryco.mobile/.MainActivity`. The Welcome screen should render exactly as before — no visible change, no new logs. If `startKoin` were broken, the app would crash before `MainActivity` renders.

Do **not** add a `KoinTest`-based smoke test in this ticket. There are no bindings to verify, and adding a `koin-test` test dependency for an empty module is exactly the "defense for an unobserved failure mode" anti-pattern. The first ticket to introduce a real binding (likely #11) is the right place to bring in `koin-test` and start asserting on `appModule`.

## Edit list (developer's checklist)

1. `gradle/libs.versions.toml` — add `koinBom` to `[versions]`; add `koin-bom` and `koin-androidx-compose` to `[libraries]`.
2. `app/build.gradle.kts` — add `implementation(platform(libs.koin.bom))` and `implementation(libs.koin.androidx.compose)` to `dependencies { ... }`.
3. `app/src/main/java/de/pyryco/mobile/PyryApp.kt` — new file, 11 lines body as shown above.
4. `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` — new file, 9 lines body as shown above.
5. `app/src/main/AndroidManifest.xml` — add `android:name=".PyryApp"` to the `<application>` tag.
6. Run `./gradlew assembleDebug` then `./gradlew test`. Both must pass.

## Open questions

None. PO body and CLAUDE.md align on every decision (Koin as DI framework, `PyryApp.kt` at the package root, `di/` for modules, empty `appModule` today). If `4.0.4` doesn't resolve cleanly, follow the bump guidance above — no architect re-run needed for a patch bump.
