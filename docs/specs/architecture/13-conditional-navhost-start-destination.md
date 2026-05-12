# Spec — Conditional `NavHost` start destination based on `pairedServerExists` (#13)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:1-107` — current state. `PyryNavHost` is a private composable in this file with a hard-coded `startDestination = Routes.Welcome`. This ticket changes that one line into a runtime-computed value and adds gating around `NavHost` composition. Everything else in the file stays put.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt:1-22` — the wrapper introduced by #11. The relevant surface is `val pairedServerExists: Flow<Boolean>`. DataStore's first emit on cold start delivers the persisted value (or `false` if unset) — see #11's spec, "State + concurrency model" section, for the exact semantics. No write happens for the default; the elvis (`?: false`) handles fresh installs.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:1-22` — confirms `AppPreferences` is bound as a `single { ... }` and resolvable via `koinInject<AppPreferences>()`. Pattern already used at `MainActivity.kt:61` inside the `composable(Routes.Scanner)` block — mirror that style.
- `docs/specs/architecture/11-datastore-app-preferences.md` (sections "State + concurrency model" and "Error handling") — guarantees behind the `Flow<Boolean>`: replay-1 semantics on first collection, IO-dispatcher-internal, no `.catch { }` added there. This spec relies on those guarantees rather than restating them.
- `docs/specs/architecture/8-navigation-compose-setup.md` (lines 121–146 in particular) — pins existing conventions: `Routes` is a `private object`, string constants, no type-safe routes refactor, no deep-link config. This ticket keeps all of that.
- `docs/specs/architecture/12-stub-scanner-screen-route.md` — the write side of `pairedServerExists` (`appPreferences.setPairedServerExists(true)` inside Scanner's `onTap`) is already wired (see `MainActivity.kt:60-74`). Confirms this ticket is read-only with respect to DataStore; the flag-flipping path is already correct.
- `gradle/libs.versions.toml` and `app/build.gradle.kts:42-64` — confirm that `androidx.lifecycle.runtime.ktx` is wired but `androidx.lifecycle.runtime.compose` (the artifact that provides `collectAsStateWithLifecycle`) is **not**. Spec chooses `produceState { … .first() }` over `collectAsStateWithLifecycle` partly for this reason — see "Design" section. Do not add a new dependency.
- `CLAUDE.md` (Stack, Conventions sections) — confirms MVI + sealed `UiState`/`Event` for screens that own state. This ticket does not introduce a screen-with-state; it gates `NavHost` composition. No ViewModel, no `UiState`, no `Event` — see "Design" rationale.

## Context

`PyryNavHost` currently hard-codes `startDestination = Routes.Welcome`. With #11 landed and #12 wiring the Scanner write, `AppPreferences.pairedServerExists` flips to `true` once the user pairs. From that point onward, every cold start should open at `channel_list` and never put `welcome` on the back stack.

The only non-obvious part is timing. `pairedServerExists` is a `Flow<Boolean>` backed by DataStore I/O — its first emit is asynchronous. A naïve switch (`startDestination = if (paired) ChannelList else Welcome` with `paired` defaulted to `false`) would briefly compose Welcome on every cold start of a paired install before recomposing with the right value. `NavHost`'s `startDestination` is captured at first composition, so even if the value flips a frame later, the NavController would already have pushed `welcome` onto its back stack. That violates AC2 (Welcome must not appear in the back stack) and AC3 (no flash).

The fix has three viable shapes, and the ticket explicitly leaves the mechanism to the architect:

1. **Synchronous seed in `PyryApp.onCreate`** — `runBlocking { appPreferences.pairedServerExists.first() }`, cache on the Application, expose to MainActivity. Trades a small (~5–20 ms) main-thread block at app start for zero possible flash.
2. **Suspending init with a SplashScreen API** — `installSplashScreen()` + `setKeepOnScreenCondition { paired == null }`. Adds the `androidx.core:core-splashscreen` dependency.
3. **Compose-side gating on a nullable state** — collect the first emit inside composition into a `Boolean?`; render a neutral `Surface` while `null`, render `NavHost` (with the resolved start destination) once non-null. No new dependency, no main-thread blocking, single recomposition.

This spec picks **#3** with `produceState`. Rationale and trade-offs are in the next section.

## Design

### Mechanism: `produceState`-gated `NavHost`

In `MainActivity.kt`'s `setContent` block, read `AppPreferences` via Koin, drive a `Boolean?` state from the first emit of `pairedServerExists`, and `when`-branch on it:

```kotlin
setContent {
    PyrycodeMobileTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            val appPreferences = koinInject<AppPreferences>()
            val paired: Boolean? by produceState<Boolean?>(initialValue = null, appPreferences) {
                value = appPreferences.pairedServerExists.first()
            }
            when (val v = paired) {
                null -> Surface(modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)) {}
                else -> PyryNavHost(
                    startDestination = if (v) Routes.ChannelList else Routes.Welcome,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}
```

`PyryNavHost`'s signature gains one parameter:

```kotlin
@Composable
private fun PyryNavHost(
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        // body unchanged — Welcome, Scanner, ChannelList, ConversationThread, Settings entries as today
    }
}
```

The rest of `PyryNavHost`'s body is verbatim what's already at `MainActivity.kt:50-88`. No route changes, no back-stack policy changes for existing destinations.

### Why this mechanism (and not the others)

**Why not `runBlocking` in `PyryApp.onCreate`** — that pattern works (DataStore's first read is fast) and gives strictly zero flash, but `runBlocking` on the main thread during `Application.onCreate` is a documented anti-pattern that can manifest as ANR variability on cold-start-degraded devices. The ticket's "no flash" bar does not require zero placeholder time; it requires that the Welcome route does not visibly render when the persisted value is `true`. A neutral `Surface` for one or two frames (during which the Android 12+ system splash typically still covers the window) is explicitly allowed by the ticket's "Technical Notes": *"Until the first emit is available, either render nothing / a neutral surface or block composition of the NavHost — architect's call."* Choosing the non-blocking path on equal-quality outcome is the right trade.

**Why not `androidx.core:core-splashscreen`** — same outcome as `produceState`-gating from the user's perspective, but adds a new dependency (catalog entry + build line), a `Theme.SplashScreen` style in `themes.xml`, and a `MainActivity.installSplashScreen()` call that has to be made *before* `super.onCreate`. That's more surface for an XS ticket whose visible result is identical. Defer until the team actually wants a branded splash — at which point it's a dedicated, sized ticket with a Figma reference.

**Why `produceState` and not `collectAsStateWithLifecycle`** — three reasons.

1. `collectAsStateWithLifecycle` requires `androidx.lifecycle:lifecycle-runtime-compose`, which is **not** in the version catalog today (only `lifecycle-runtime-ktx` is wired — see `gradle/libs.versions.toml`). Adding it would inflate the diff and the dependency surface for a single use site. The spec stays within existing deps.
2. We only need the **first** emit. The `startDestination` is read once by `NavHost` and frozen at first composition; later changes to `pairedServerExists` (e.g. Scanner flipping it from `false` to `true` mid-session) must **not** rewrite the start destination, because the NavController already has its own back-stack state. `produceState` + `.first()` expresses that one-shot intent exactly; `collectAsStateWithLifecycle` would keep observing for no benefit (and arguably for a subtle hazard, see point 3).
3. After the Scanner writes `true`, the Flow re-emits. With `collectAsStateWithLifecycle`, `paired` would update from `false` → `true`, the `when` would re-evaluate, and the `else` branch would recompose with a new `startDestination` argument. `NavHost` ignores `startDestination` changes after first composition (it does **not** rebuild the back stack), so functionally nothing breaks — but reading the code, a future developer might assume the start destination is reactive and reason incorrectly. `produceState { value = …first() }` makes the one-shot read explicit at the call site and removes that foot-gun.

**Why the `produceState` `key` is `appPreferences`** — `produceState`'s keys re-trigger the coroutine when they change. `appPreferences` is a Koin singleton; in practice it will never change identity during the activity's lifetime, so the producer runs exactly once. Passing it as a key is defensive: if a future refactor makes `AppPreferences` swappable (e.g. for a debug fake), the produced state correctly re-initialises.

**Why the placeholder is a bare `Surface`, not nothing** — `Surface(modifier = Modifier.fillMaxSize())` paints the theme's background colour, which on Android 12+ matches the system splash background by default (both default to `?colorBackground`). A `Box {}` would also work but doesn't paint a background, so a brief transparent flash could expose whatever the window manager has behind the activity. `Surface` is the minimal-cost guarantee that "neutral surface" actually looks neutral. Loading-state polish (spinner, branded mark) is explicitly out of scope per the ticket.

### Routes

No changes. `Routes.Welcome`, `Routes.Scanner`, `Routes.ChannelList`, `Routes.ConversationThread`, `Routes.Settings` are exactly as today (`MainActivity.kt:101-107`). Do not rename, do not reorder, do not migrate to type-safe routes.

### Back-stack policy

The Welcome → Scanner → ChannelList path inside `Routes.Scanner`'s `onTap` already pops Scanner with `popUpTo(Routes.Scanner) { inclusive = true }` (see `MainActivity.kt:67-70`). After this ticket lands, the relaunch path (paired install opens directly at `channel_list`) sidesteps that flow entirely — Welcome is never composed and therefore never on the back stack, satisfying AC2 directly without any back-stack manipulation in this ticket.

What this means concretely: with `startDestination = Routes.ChannelList`, hitting the system Back button on `channel_list` exits the app. That's the correct behaviour for a paired-and-returning user. (Today, with the fresh-install path, Back from `channel_list` returns to `welcome` because Welcome is on the stack. After #12+#13 land, the post-Scanner path also exits the app from `channel_list` because Scanner's `popUpTo(Routes.Scanner)` removed itself, leaving only `channel_list` — and Welcome was popped earlier by Scanner navigation. Behaviour is consistent across both entry paths.)

### Files touched

Exactly one production file:

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` — edit the existing `setContent` body to gate on `produceState`, change `PyryNavHost`'s signature to accept `startDestination: String`. Add three imports (see "Edit list" below).

No new files. No changes to `PyryApp.kt`, `AppPreferences.kt`, `AppModule.kt`, `libs.versions.toml`, or `build.gradle.kts`.

## State + concurrency model

- **`produceState`'s coroutine** runs in the composition's coroutine scope, which is tied to the composable's lifecycle (cancelled on disposal). It launches once on first composition because `appPreferences` (its only key) does not change identity. The block suspends inside `appPreferences.pairedServerExists.first()` until DataStore's first emit, then assigns `value`. Total duration in practice: bounded by DataStore's first-read I/O, typically < 50 ms on warm storage.
- **Dispatcher**: `produceState`'s block runs on the immediate (Main) dispatcher. `Flow<Boolean>.first()` suspends correctly when DataStore is doing IO on its own internal scope; the Flow's emit hop back to the collector does not require a dispatcher switch. No `withContext(Dispatchers.IO)` needed and none should be added.
- **Cancellation**: activity destruction cancels the composition scope, which cancels the producer. Configuration changes recreate the activity → `produceState` runs again → `Surface` placeholder shows briefly → first emit lands → `NavHost` composes. This is acceptable and matches the alternative mechanisms' behaviour.
- **DataStore lifecycle**: this ticket does not change how `DataStore<Preferences>` is constructed or scoped. Per #11's spec, it's a Koin singleton on `Dispatchers.IO + SupervisorJob()`, surviving for the process lifetime.
- **Reactive updates after first emit**: by design, ignored. `NavHost` captures `startDestination` at first composition; subsequent flips of `pairedServerExists` (Scanner → true, or a hypothetical debug "unpair" toggle) do not rewrite the start destination. Mid-session navigation continues through `navController.navigate(...)` calls, which is correct.

## Error handling

- **Read failure**: if `pairedServerExists.first()` throws (e.g. file corruption non-IO error per #11's design), the exception propagates out of `produceState`. The composition coroutine reports it to the default uncaught-exception handler. This is acceptable for Phase 0 — there's no observed failure mode to defend against, and the corruption case is reserved for a dedicated future ticket per #11's "Error handling" section. **Do not** add a `try { … } catch { value = false }` here: a silent fallback would mask corruption, and treating a corrupted store as "fresh install" could send a real paired user back through Welcome on every launch.
- **Missing DI binding**: `koinInject<AppPreferences>()` would throw at composition if the binding were missing. The binding is present (`AppModule.kt:17`), so this is a programming-error class only — no runtime defence.
- **Stuck producer**: `pairedServerExists` is a hot DataStore flow that emits within milliseconds in practice; there is no realistic indefinite-stall path. If one ever appears, the `Surface` placeholder remains visible — degrading gracefully — and the symptom is diagnosable. No timeout is added.

## Testing strategy

- **`./gradlew assembleDebug`** — must pass. This is AC4 and the only mandatory automated signal for this ticket.
- **`./gradlew test`** — must pass. The existing `AppPreferencesTest` already covers DataStore default-`false` and round-trip semantics (`AppPreferencesTest.kt:43-52`); those are the foundations this spec relies on. No new unit tests are added — the mechanism is a Compose composition decision that isn't meaningfully unit-testable without UI scaffolding, and the foundational primitive is covered.
- **No new instrumented tests.** A `ComposeTestRule`-driven test could in principle verify "with `pairedServerExists = true` in DataStore, the first composable shown contains the ChannelList placeholder text", but it would require a non-trivial Koin override + `androidx.compose.ui.test.junit4` setup that the project hasn't built yet. The XS budget doesn't justify adding that infrastructure here; revisit when the first real screen replaces the placeholder.
- **Manual smoke (developer should run before declaring done)**:
  1. Fresh install: `./gradlew installDebug` → launch → expect Welcome. (Covers AC1.)
  2. From Welcome, tap "Pair" → Scanner appears → tap the Scanner surface → ChannelList appears. (Existing #12 flow; spot-check that this spec didn't break it.)
  3. Force-stop the app, relaunch from the launcher → expect ChannelList directly, no Welcome flash, system Back from ChannelList exits the app. (Covers AC2 + AC3.)
  4. Uninstall + reinstall → expect Welcome again. (Re-covers AC1 after a state change.)

The "no visible flash" condition (AC3) is judged by the developer's eye on a real device or emulator at step 3. A brief (one-frame) blank surface is acceptable per the ticket's Technical Notes; the prohibited symptom is Welcome's actual UI rendering before being replaced.

## Edit list (developer's checklist)

1. `app/src/main/java/de/pyryco/mobile/MainActivity.kt`:
   - Add imports: `androidx.compose.material3.Surface`, `androidx.compose.runtime.produceState`, `androidx.compose.runtime.getValue`, `kotlinx.coroutines.flow.first`. Remove any imports that this edit obviates (none expected; `koinInject` is already imported).
   - Inside `setContent { PyrycodeMobileTheme { Scaffold { innerPadding -> … } } }`, replace the single `PyryNavHost(modifier = Modifier.padding(innerPadding))` call with the `koinInject<AppPreferences>()` + `produceState` + `when (val v = paired)` block shown in the "Design" section above.
   - Change `private fun PyryNavHost(modifier: Modifier = Modifier)` to `private fun PyryNavHost(startDestination: String, modifier: Modifier = Modifier)`.
   - Inside `PyryNavHost`, change `startDestination = Routes.Welcome` to `startDestination = startDestination`.
2. Run `./gradlew assembleDebug` — must pass (AC4).
3. Run `./gradlew test` — must pass (no test changes; just confirming nothing regressed).
4. Run the manual smoke from "Testing strategy" on a device or emulator.

Expected net diff: ~10–12 added lines, ~1 removed line, all inside `MainActivity.kt`. No other files touched.

## Open questions

None. The mechanism, the placeholder shape, the back-stack consequence, the choice not to add a new dependency, and the choice not to add a `try/catch` are all settled by existing conventions (#11 spec, #8 spec, CLAUDE.md, the ticket's Technical Notes). If `produceState` ever shows a measurable cold-start delay in practice, a future ticket can revisit Option 1 (synchronous seed in `Application.onCreate`) — but that's evidence-based escalation, not something to pre-engineer.
