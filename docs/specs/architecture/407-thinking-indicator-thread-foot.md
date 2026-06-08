# Spec #407 — Thinking indicator at the foot of the message list

**Ticket:** [#407](https://github.com/pyrycode/pyrycode-mobile/issues/407) — split from #386. UI half of the thinking indicator; the data/ViewModel half is the sibling slice #406 (merged), which exposes `ThreadViewModel.isThinking: StateFlow<Boolean>`.

**Size:** S. 1 new + 2 modified production `.kt` files (`ThinkingIndicator.kt` new; `ThreadScreen.kt` + `MainActivity.kt` modified), 1 new exported composable, 1 forced consumer call-site edit (MainActivity; the param is defaulted so previews + androidTest keep compiling). 1 resource edit (`strings.xml`) + 1 new androidTest file. ~150–200 LOC total. No `security-sensitive` label → no security-review pass.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:64-82` — the stateless screen's parameter list. `connectionState` is a **required** sibling param ahead of the `modifier` defaults block; you add `isThinking: Boolean = false` **into the trailing defaults block** (after `modifier`) so the 4 in-file previews + 2 androidTest call sites keep compiling untouched.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:111-219` — the content `Column`. Structure: `ConnectionBanner` (118) → optional `WorkspaceChip` (119-128) → `if (!hasMessages) EmptyThreadState else LazyColumn(reverseLayout=true, weight(1f))` (129-218). **The placement seam is line 218** — the indicator is the final child of this `Column`, after the `if/else` closes, before the `Column` closes at 219. One site, outside the branch ⇒ surfaces in both the empty and populated cases (AC #2).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionBanner.kt:22-88` — the sibling show/hide idiom to mirror: a stateless row that **early-`return`s** when it should not show (`Connected -> return`, line 33), otherwise renders a padded `Surface`/row. `ThinkingIndicator` is the same shape (`if (!isThinking) return`). Note `BannerHorizontalPadding = 16.dp` / `BannerVerticalPadding = 12.dp` (19-20) — reuse the same horizontal inset so the indicator aligns with the banner above it.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/EmptyThreadState.kt:1-68` — the minimal stateless-component + **light/dark preview** template to copy verbatim: `@Preview(name=…, showBackground=true, widthDp=412)` for light and the same with `uiMode = Configuration.UI_MODE_NIGHT_YES` for dark, each wrapped in `PyrycodeMobileTheme(darkTheme=…) { Surface { … } }`. Also the `stringResource(R.string.…)` usage pattern.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:206-241` — the `isThinking` `StateFlow` (already shipped by #406) and `thinkingTransition` reducer. **Not edited here** — this is the data source you consume. Read it to confirm the flag's semantics: `true` only while the latest `turn_state` is `Thinking`; `false` for `responding`/`idle`/`turn_end` and before any event; the `StateFlow` retains its last value between phase events.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:344-371` — the `CONVERSATION_THREAD` `composable`. `connectionState` is collected at 348 (`val connectionState by vm.connectionState.collectAsStateWithLifecycle()`) and passed at 360. You add the exact-parallel line for `isThinking` and one more named argument in the `ThreadScreen(…)` call.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StatusSheet.kt:276` (`LinearProgressIndicator`), `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerConnectingScreen.kt:79` (`CircularProgressIndicator(modifier = Modifier.size(48.dp))`), `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/LiteralScreenSurface.kt:138` (`CircularProgressIndicator`) — the existing **M3 progress idiom** in the app. Use the indeterminate `CircularProgressIndicator`, sized **smaller** than the 48dp connecting spinner (see Design).
- `app/src/main/res/values/strings.xml:48-60` — string conventions. `thread_*` keys for thread copy; the **`cd_*` prefix** marks content descriptions (`cd_thread_status_expand`, line 53). Add `cd_thread_thinking` (mandatory, AC #5) and — only if you ship a visible label — `thread_thinking_label`.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenChannelInfoTest.kt:1-70` — the Compose-test idiom: `createComposeRule()`, `onNodeWithContentDescription`, `setContent { PyrycodeMobileTheme { ThreadScreen(…) } }`. Note the `ThreadScreen(…)` call here omits all trailing optional params — confirms a **defaulted** `isThinking` keeps this file compiling with no edit.
- `docs/specs/architecture/406-live-turn-state-thread-viewmodel.md` — the sibling data slice. Its closing "Open questions" flags a `WhileSubscribed(5_000)` stale-`true`-on-resubscribe edge it explicitly deferred to this UI slice; see Open questions below for the decision (deliberately not handled).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The Conversation Thread Screen (`16-8`) is a dark Material 3 chat: a back/title/overflow top app bar, a scrolling message list (right-aligned primary-container user bubbles, surface-container assistant bubbles, inline tool-call rows, a horizontal session-boundary delimiter with the "Claude doesn't remember above this line · Install" note), and a pinned bottom status row (`Opus 4.7 · high · 73% used`) above the message input bar (mic + send).

A dedicated **thinking-state indicator** element is **not yet drawn** in the file — **design-owed** (flagged for Juhana to add a thinking-state frame). Until it lands, the behavioural ACs below stand and the visual follows the app's existing M3 progress idiom (small indeterminate `CircularProgressIndicator`); the `responding`-state streaming caret is the separately-drawn affordance at `16-54` and is out of scope here.

## Context

Part of the Phase 2 structured-streaming exit-gate (pyrycode#596, ADR 025). The `responding` phase (assistant text growing) is already covered by the shipped streaming UI (#184/#185). This slice owns the **`thinking` (pre-text)** presentation: while the active conversation's agent is working but no text has appeared yet, the thread shows a thinking indicator at the foot of the message list so the user can tell the agent is active rather than stalled, and the indicator clears the moment the conversation leaves the thinking phase.

The signal already exists: #406 reduced the connection-scoped live `turn_state` stream to `ThreadViewModel.isThinking: StateFlow<Boolean>`, a **sibling** flow beside `connectionState` (deliberately **not** a `ThreadUiState` field). This slice adds no data access — it collects that flag at the call site, threads it through the stateless `ThreadScreen`, and renders it.

## Design

Three additive edits + one string + one test. No new exported type beyond the composable; no data-layer touch (`data/` stays portable, AC "UI-only").

### 1. New stateless composable `ThinkingIndicator`

**File:** `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ThinkingIndicator.kt` — co-located with the other thread components (`ConnectionBanner`, `EmptyThreadState`, `MessageBubble`, …), matching the ticket's "co-locate with the other thread components." Package `de.pyryco.mobile.ui.conversations.components`. The file's single public top-level type is the composable, so the filename matches it (ktlint single-class rule — see [[ktlint-filename-rule-single-class]]).

**Contract:**

```kotlin
@Composable
fun ThinkingIndicator(isThinking: Boolean, modifier: Modifier = Modifier)
```

Behaviour (mirrors `ConnectionBanner`'s early-return idiom):

- `if (!isThinking) return` — emits nothing when not thinking (AC #1 "hidden otherwise"; AC #4 holds no local state — it is a pure function of the hoisted boolean).
- When `true`, render a start-aligned `Row`, full width, padded `horizontal = 16.dp` (align with `ConnectionBanner`) and a modest `vertical` inset (~8–12dp), containing:
  - a small **indeterminate** `CircularProgressIndicator` — size **~16–20dp**, `strokeWidth ≈ 2.dp` (smaller than the 48dp connecting spinner; this is a foot-of-list affordance, not a full-screen loader);
  - **design-owed default:** an adjacent `Text` "Thinking…" (`MaterialTheme.typography.bodySmall`, `color = MaterialTheme.colorScheme.onSurfaceVariant`). The label is the M3-idiom placeholder until the Figma frame lands; a spinner-only variant is equally acceptable if the developer prefers — either satisfies the ACs.
- **Content description (AC #5):** put the description on the row container via `Modifier.semantics(mergeDescendants = true) { contentDescription = … }`, sourced from a new `cd_thread_thinking` string (recommended value: "Agent is thinking"). Merging descendants means TalkBack announces the indicator once even when the visible "Thinking…" label is present (the contentDescription is the accessible name; the decorative spinner + label are subsumed).
- **Previews (AC #4):** light + dark, copying the `EmptyThreadState` preview template (light `@Preview` + dark `@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)`, each `PyrycodeMobileTheme(darkTheme=…) { Surface { ThinkingIndicator(isThinking = true) } }`). Show the **active** state so the visual is reviewable.

No animation is required. An optional subtle fade-in (`AnimatedVisibility`) is a design-owed nicety, not in scope — keep it the plain early-return to match `ConnectionBanner` and the rest of the app.

### 2. Thread the flag through `ThreadScreen`

In `ThreadScreen.kt`:

- **Param:** add `isThinking: Boolean = false` to the trailing **defaults** block (after `modifier`, alongside the `on…` defaults). Defaulted ⇒ the 4 in-file previews and the 2 androidTest call sites compile unchanged; only the live caller (MainActivity) sets it. `false` is the correct inert default (not thinking).
- **Placement:** add `ThinkingIndicator(isThinking = isThinking, modifier = Modifier.fillMaxWidth())` as the **final child of the content `Column`** — immediately after the `if (!state.hasMessages) { … } else { … }` block closes (current line 218), before the `Column` closes (219). Do **not** put it inside either branch and do **not** gate it on `hasMessages`.

Why this seam:

- The `LazyColumn` (populated branch) and `EmptyThreadState` (empty branch) each take `weight(1f)`; the indicator is wrap-height and sits directly below the weighted region, above the Scaffold `bottomBar` (status row + input). That is the visual **foot / most-recent edge** the AC names — directly beneath the latest message (the `reverseLayout = true` list pins index 0 to its bottom) and above where the user types.
- Because it is outside the `if/else`, it surfaces identically in the **empty-thread** case (AC #2 — thinking precedes the first assistant text) and the populated case, with one placement and no duplication.
- No change to the `LazyColumn`, its `weight`, `reverseLayout`, or the auto-scroll effects — they are untouched.

### 3. Collect + pass the flag in `MainActivity`

In the `CONVERSATION_THREAD` `composable` (MainActivity.kt:344-371), mirror the `connectionState` line exactly:

```kotlin
val isThinking by vm.isThinking.collectAsStateWithLifecycle()
```

and add `isThinking = isThinking` to the `ThreadScreen(…)` call (360-369 region). `collectAsStateWithLifecycle` is already imported and used for `state`/`connectionState`. This is the only forced consumer edit.

### 4. String resource

Add to `app/src/main/res/values/strings.xml` (near the other `thread_*` / `cd_*` keys, 48-60):

- `cd_thread_thinking` — content description, mandatory (AC #5). Recommended: `Agent is thinking`.
- `thread_thinking_label` — only if the visible "Thinking…" label is shipped. Recommended: `Thinking…`.

## State + concurrency model

None added by this slice. The flag is collected once in `MainActivity` via `collectAsStateWithLifecycle()` — identical lifecycle to `connectionState` (the underlying `isThinking` `StateFlow` is `stateIn(viewModelScope, WhileSubscribed(5_000), false)` from #406). `ThinkingIndicator` is **stateless** — no `remember`, no `rememberSaveable`, no side effect, no coroutine (AC #3/#4 "driven solely by the hoisted flag, holds no local state of its own").

**Recomposition:** `isThinking: Boolean` is a stable param, so `ThinkingIndicator` is skippable and recomposes only when the flag flips. `ThreadScreen` gains one stable `Boolean` param; it already recomposes on `state`/`connectionState` changes, and the new placement is a single cheap row at the foot — no impact on the `LazyColumn`'s item recomposition.

## Error handling

None. The composable is a total function of a non-failing `Boolean`; the upstream `isThinking` flow is best-effort and never errors (its source `liveSessionEvents` does not complete-with-error; decode failures are dropped to nothing upstream at #385). Absence of a live source ⇒ the flag stays `false` ⇒ the indicator is hidden. Nothing to surface as a banner/dialog.

## Testing strategy

The flag's **reduction** logic (thinking/responding/idle/turn_end/other-conversation) is already covered by `ThreadViewModelTest` (#406) — **not** re-tested here. This slice's only new behaviour is rendering: show when `true` (including the empty-thread case), hidden when `false`. That is a Compose-UI concern, so the test is **instrumented** (androidTest), mirroring `ThreadScreenChannelInfoTest`. There is no pure-Kotlin logic to unit-test, so no new `testDebugUnitTest` is warranted.

**New file** `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThinkingIndicatorTest.kt` (or extend an existing `ThreadScreen…Test`), `createComposeRule()`, content set inside `PyrycodeMobileTheme`, asserting against the `cd_thread_thinking` node. Scenarios (as bullet points — developer writes the bodies in the project idiom):

- **Shown when thinking, with messages:** `ThreadScreen(state = <hasMessages=true, populated items>, isThinking = true, …)` ⇒ `onNodeWithContentDescription(<cd_thread_thinking>).assertIsDisplayed()`.
- **Shown when thinking, empty thread (AC #2):** `ThreadScreen(state = <hasMessages=false, items=emptyList>, isThinking = true, …)` ⇒ the indicator node is displayed even though the list is replaced by `EmptyThreadState`.
- **Hidden when not thinking (AC #1):** same state, `isThinking = false` ⇒ `onNodeWithContentDescription(<cd_thread_thinking>).assertDoesNotExist()`.
- **(Optional) drives off the flag only (AC #3):** flip a `mutableStateOf(false) → true` feeding `isThinking` and assert the node appears, proving no local latch.

Resolve the `cd_thread_thinking` string via `composeTestRule.activity.getString(R.string.cd_thread_thinking)` (or the test-context resource lookup the existing tests use).

**Caveat — device required.** androidTest runs only under `./gradlew connectedAndroidTest` with a connected device/emulator; CI without a device will not execute it (consistent with prior UI slices, e.g. #398). The developer must still run and pass: `./gradlew spotlessCheck`, `./gradlew lint`, `./gradlew testDebugUnitTest` (unaffected — stays green), and `./gradlew assembleDebug`. If no device is available, note `connectedAndroidTest` as not-run rather than skipping the test authoring.

## Open questions

- **Stale-`true`-on-resubscribe (deferred from #406).** With `replay = 0` upstream and `WhileSubscribed(5_000)`, if the agent leaves `thinking` while the screen is backgrounded > 5 s and re-foregrounds before a fresh event, `isThinking` can momentarily read a stale `true` until the next event. #406's spec explicitly punted this to the UI slice. **Decision: do not handle it here.** It matches the transient "right-now" posture already accepted for `connectionState` and the stall flag (#395); engineering a resume-time reset would mean either a data-layer change (out of this slice's UI-only scope) or local state in the composable (violates AC #4). If it proves visually jarring in practice, file a follow-up.
- **Spinner-only vs. spinner + label.** Left to the developer pending the design-owed Figma frame — both satisfy the ACs. The label ("Thinking…") is the recommended M3-idiom default for clarity; the content description (AC #5) is mandatory regardless.
