---
ticket: 14
title: Wire Welcome `onSetup` CTA to external browser launch
size: XS
---

# Spec — #14: Welcome `onSetup` → external browser

## Files to read first

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:74-83` — the `composable(Routes.Welcome) { ... }` site; the `onSetup = { TODO(#14) ... }` line is the only thing this ticket changes. Note the existing `onPaired` lambda body (line 76–78) as the structural pattern to mirror.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:84-98` — the `composable(Routes.Scanner)` site; example of capturing per-route values (`koinInject`, `rememberCoroutineScope`) in the `composable { ... }` body and using them from inside a callback lambda. The same shape applies for capturing `LocalContext.current`.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt:28-32` — `WelcomeScreen` signature. Confirms `onSetup: () -> Unit` is the parameter; **do not change this signature**. AC: previews must still compile with `onSetup = {}` no-ops.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt:104-124` — the two `@Preview` composables; their `WelcomeScreen(onPaired = {}, onSetup = {})` calls remain untouched.

## Context

`WelcomeScreen` (scaffolded in #7) exposes two hoisted callbacks: `onPaired` and `onSetup`. PR #41 (#12) wired `onPaired` to `navController.navigate(Routes.Scanner)` at the `PyryNavHost` site. The `onSetup` callback is still `// TODO(#14): launch external browser intent to pyrycode install docs.` (MainActivity.kt:80).

This ticket replaces that TODO with an `Intent.ACTION_VIEW` launch of `https://pyryco.de/setup` via the Compose-local `Context`. The URL is hardcoded; not user input; not security-sensitive (ticket label confirms).

## Design

Change scope: **one file, one composable block**.

`app/src/main/java/de/pyryco/mobile/MainActivity.kt`

1. **Add a top-level constant** near `private object Routes` (line 125) for the URL. Naming: `private const val SetupUrl = "https://pyryco.de/setup"`. Rationale: a single named constant at file scope reads cleaner than an inline literal, makes it greppable from QMD / future refactors, and keeps the lambda body to one expression. Do not over-engineer — no separate config object, no `Urls` namespace; one `const val` next to `Routes`.

2. **In `composable(Routes.Welcome) { ... }` (line 74–83)**, capture the local `Context`:

   ```kotlin
   composable(Routes.Welcome) {
       val context = LocalContext.current
       WelcomeScreen(
           onPaired = {
               navController.navigate(Routes.Scanner)
           },
           onSetup = {
               context.startActivity(
                   Intent(Intent.ACTION_VIEW, Uri.parse(SetupUrl))
               )
           },
       )
   }
   ```

   The `LocalContext.current` read happens during composition (cheap, returns the `LocalContext` `CompositionLocal`'s current value — the hosting `Activity`). The captured `context` reference flows into the `onSetup` lambda closure; the `Intent` is constructed lazily on tap. No `remember { ... }` wrapping is needed — `LocalContext.current` is stable for the lifetime of the composition and re-reading on recomposition is a no-op cost. (Same pattern as `koinInject` reads at line 85: read once in the composable body, capture in lambdas.)

3. **Imports to add** in `MainActivity.kt`:
   - `android.content.Intent`
   - `android.net.Uri`
   - `androidx.compose.ui.platform.LocalContext`

   No new Gradle dependencies. `Intent`, `Uri`, and `LocalContext` are all in the Android SDK / Compose UI already on the classpath.

That is the entire change. Net line delta: roughly +6 lines (1 const, 1 LocalContext read, the multi-line lambda body, 3 imports) minus 1 (the TODO line).

### Why this site, not inside `WelcomeScreen`

`WelcomeScreen` is a stateless composable in `ui/onboarding/` that knows nothing about navigation or intents. The ticket explicitly preserves the `onSetup: () -> Unit` parameter shape. Putting the `Intent` launch behind the hoisted callback at the `PyryNavHost` site keeps `WelcomeScreen` testable in isolation (preview / future ComposeTestRule tests pass `onSetup = {}` without needing a real `Context`) and mirrors how `onPaired` already lives at the same site.

## State + concurrency model

None. `Intent.ACTION_VIEW` + `Context.startActivity` is a synchronous fire-and-forget call from the Main thread (the Compose `onClick` callback already runs on Main). No `viewModelScope`, no `Flow`, no dispatcher choice. The OS process handoff to the browser happens off-process; the app does not await any result.

## Error handling

**Deferred per ticket — do not add `try`/`catch`.** The ticket's "Out of Scope" section explicitly defers `ActivityNotFoundException` hardening:

> Hardening for the "no browser installed" edge case (`ActivityNotFoundException`) — defer until observed; not a realistic failure mode on stock Android 13+ where the system handles `ACTION_VIEW` for `https` URLs.

This matches the [[Evidence-Based Fix Selection]] principle: ship the defense when the failure is observed. Adding a `try`/`catch` with a `Toast` or `Snackbar` for a failure mode that cannot occur on min-SDK 33 with a stock browser stack would be speculative scope. Min SDK 33 (Android 13) ships with WebView + Chrome / vendor browser pre-installed; the system handler for `https` `ACTION_VIEW` is always resolvable.

If the failure is ever observed in the wild, the fix is local (wrap the `startActivity` in `try { ... } catch (e: ActivityNotFoundException) { ... }` at this same call site) — defer until then.

No package-visibility (`<queries>`) manifest declaration is required: an app targeting API 30+ does **not** need `<queries>` to *launch* an `Intent` it doesn't introspect; queries are only required to enumerate or check resolvers in code. `startActivity(Intent(ACTION_VIEW, https://...))` is allowed unconditionally.

## Testing strategy

**No new tests in this ticket.** Rationale:

- The change is a single Intent construction + `startActivity` call. Unit-testing this requires either mocking `Context` (low signal — verifies the SDK API was called with the args we just typed) or instrumenting (instrumented tests for "did the system browser open" require `UiAutomator` / `Intents.intended` from `androidx.test.espresso:espresso-intents`, neither of which the project currently depends on, and standing up that infrastructure for one call site is disproportionate).
- The ACs are verifiable mechanically by the existing checks the ticket already names: `./gradlew assembleDebug` (compile + lint preconditions) and `./gradlew lint` (Android Lint, which catches missing-import, deprecated-API, and obvious intent-launch issues).
- The previews in `WelcomeScreen.kt` provide visual regression coverage of the screen itself (unchanged here).
- Manual verification on a device/emulator confirms the tap behavior end-to-end. The developer should run `./gradlew installDebug`, tap **Set up pyrycode first**, and confirm the browser opens at `https://pyryco.de/setup` (currently redirects to the pyrycode README — that's expected per ticket).

The decision aligns with the project [[Test-first]] convention's spirit (red → green → refactor *for logic worth testing*) — there is no behavioral logic here to red-test, just an SDK call wired to a callback.

If a future ticket introduces a `LinkLauncher` abstraction (e.g. to centralize analytics around outbound link clicks, or to enable `CustomTabsIntent`), that ticket owns its own tests. Don't pre-build the abstraction here.

## Open questions

None blocking. Two minor implementation notes for the developer:

1. `Uri.parse` vs `androidx.core.net.toUri()`: both work; `core-ktx` is already on the classpath (verified via `app/build.gradle.kts`). Either `Uri.parse(SetupUrl)` or `SetupUrl.toUri()` is acceptable. **Prefer `Uri.parse`** — it's the most universally recognized idiom and avoids importing an extension whose only job is to call the same parse method.

2. The constant could equally live as `private const val` at file scope or inside the `private object Routes`. **Prefer file scope** (next to `Routes`, not inside it) — `Routes` is semantically a route-name namespace, and a URL is not a route. Don't introduce a new singleton object just to hold one value.
