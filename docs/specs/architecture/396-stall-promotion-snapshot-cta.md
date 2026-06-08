# Spec #396 — Surface the screen-snapshot action prominently on a stall

**Ticket:** pyrycode-mobile #396 (`feat(ui/thread)`, `size:s`, **not** `security-sensitive`)
**Split from:** #373 (the UI-reaction slice; the data slice is #395, which this consumes).
**Depends on:** #395 (merged to `main`, commit `1d79da1`) — `ConversationRepository.observeStall(conversationId): Flow<Boolean>`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The node is the **Conversation Thread** frame: a dark column with a top app bar (back · title · overflow ⋮), message bubbles (user = right-aligned primary-container, assistant = left-aligned surface), a tool-call row, a session-boundary delimiter, and a bottom status row (`Opus 4.7 · high · 73% used`) above the message input bar. **Design-owed:** the prominent-on-stall treatment is a *new surface* not yet drawn on this frame (identical design-owed status to siblings #386 thinking-indicator and #388 tool-row). The behavioural ACs below are testable today; pixel fidelity to the locked frame lands when design is added. Build the affordance in the app's existing Material 3 banner idiom (mirror `ConnectionBanner` / `ThinkingIndicator`, both of which shipped design-owed the same way).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:198-241` — **the load-bearing precedent.** `connectionState` and `isThinking` are both exposed as *sibling `StateFlow`s* (not `ThreadUiState` fields) with `stateIn(viewModelScope, WhileSubscribed(5_000), initialValue=…)`. `isStalled` mirrors these 1:1. Read the `isThinking` KDoc (207-223) — its rationale ("transient, connection-scoped cross-cutting signal the stateless screen takes as a separate parameter") applies verbatim to stall.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:98-106` — constructor. `repository: ConversationRepository` is already injected; `observeStall` is on that interface ⇒ **no constructor, no Koin, no DI change** (unlike `isThinking`, which needed a new `liveSessionEvents` param — stall does not).
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:33-43` — the `observeStall` contract from #395: `true` on stall onset, `false` once a forward-progress event arrives; cold; re-emits on change; default `flowOf(false)`.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt:74` — facade delegation `switchToLive(false) { it.observeStall(conversationId) }`. Confirms `false` when no live connection and that a stall never survives a reconnect — the screen needs no special "stale stall" handling.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ThinkingIndicator.kt` (whole file, 94 lines) — **the component template.** Stateless pure function of a hoisted boolean, early-returns when false, M3-default styling with a design-owed KDoc, two light/dark previews. `StallPromotionBanner` is built in this exact shape.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionBanner.kt` (whole file, 124 lines) — **the banner template.** A full-width clickable `Surface` + `Text`, `return` for the no-banner state, container/content color pair, preview matrix. `StallPromotionBanner` is a `ConnectionBanner`-shaped clickable CTA whose `onClick` is `onShowLiteralScreen`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:65-122` — the composable signature (defaulted callbacks incl. `onShowLiteralScreen`, `isThinking`) and the content `Column` where `ConnectionBanner` sits (line 120). The new banner renders here; `isStalled: Boolean = false` is added beside `isThinking`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt:28-36` — the *already-shipped* snapshot entry point (#382): the "Show the literal screen" item calls `onShowLiteralScreen` (pure navigation, bypasses `ThreadEvent`). AC #4 ⇒ the banner reuses this **same** callback — no new event, no new data path.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:341-373` — the live thread destination. `isThinking` is `collectAsStateWithLifecycle()`’d and passed as a param; `onShowLiteralScreen = { navController.navigate("literal_screen/$conversationId") }`. Wire `isStalled` identically (one collect + one param).
- `app/src/main/res/values/strings.xml:64,75-82` — existing literal-screen strings. Reuse `thread_overflow_show_literal_screen` ("Show the literal screen") for the CTA label; add one explanatory string here.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:119-220` (isThinking unit-test idiom), `:1141-1155` (`makeVm` / `vmWithLiveEvents` helpers), `:1162-1233` (`RecordingRepo` — the inline `ConversationRepository` double pattern). Mirror for `isStalled`; you need a repo whose `observeStall` is controllable (see Testing strategy).
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenOverflowTest.kt:21-61` — the `ComposeTestRule` harness for thread UI (`baseState()`, `setContent`, `onNodeWithText(...).performClick()`). Mirror for the banner visibility / trigger tests.
- `docs/specs/architecture/395-observe-stall-state.md` — the upstream contract. **Note its Open Question 1** (does `idle`/`turn_end` clearing match #396's UX?) — resolved in this spec under Open questions.

## Context

Per pyrycode ADR-025 § Safe degradation, the screen snapshot ("show the literal screen") is the *floor* of the degrade strategy: when structured parsing breaks and the remote claude stalls, the parser-independent live view is the safe fallback. The snapshot action and its data path already shipped end-to-end (#375 repo read, #381 surface+DI, #382 entry point + navigation). #395 shipped the thread-observable stall *state*. This ticket adds only the **promotion**: while the stall state is active for the current conversation, raise the snapshot action's prominence so it reads as the recommended next step, and clear the promotion when the stall resolves. No new data path — the prominent affordance triggers the already-wired `onShowLiteralScreen` navigation.

## Design

Four production files, all small additive edits. **No new exported type beyond one composable; no constructor/DI/interface change.**

| File | Edit |
|------|------|
| `ui/conversations/components/StallPromotionBanner.kt` | **new** — stateless `StallPromotionBanner(isStalled, onShowLiteralScreen, modifier)` composable + 2 previews |
| `ui/conversations/thread/ThreadViewModel.kt` | add sibling `val isStalled: StateFlow<Boolean>` off `repository.observeStall(conversationId)` |
| `ui/conversations/thread/ThreadScreen.kt` | add `isStalled: Boolean = false` param; render `StallPromotionBanner` below `ConnectionBanner` |
| `MainActivity.kt` | `collectAsStateWithLifecycle()` `vm.isStalled`; pass `isStalled = isStalled` |
| `res/values/strings.xml` | one new explanatory string (CTA label reuses `thread_overflow_show_literal_screen`) |

### Key decision — sibling `StateFlow`, not a `UiState` field (deviation from ticket Technical Notes, justified)

The ticket's Technical Notes say "hoist the stall flag into the thread `UiState`." **This spec instead exposes it as a sibling `StateFlow<Boolean>` on `ThreadViewModel`, matching `connectionState` and `isThinking`.** Rationale (the deviation is deliberate and behaviour-preserving — all four ACs are satisfied identically):

1. **Same signal class as `isThinking`.** A stall is a transient, connection-scoped, conversation-routed, live-derived cross-cutting signal that drives one piece of UI prominence — structurally identical to `isThinking` (#406), which sits in the same ViewModel and was *explicitly* designed as a sibling `StateFlow` rather than a `UiState` field. Putting stall in `UiState` while `isThinking` sits beside it as a sibling would be internally inconsistent.
2. **The `state` `combine` is already at the 5-arg typed maximum** (`observeConversations`, `observeMessages`, `pendingWorkspacePicker`, `transientDialogs`, `runConfigFlow`). Adding a 6th source forces a vararg-`combine` (loses type safety) or a re-grouping refactor — churn with no benefit. The sibling-flow path adds zero risk to the existing `state` pipeline.
3. **The `state` combine reads durable render state** (conversations, messages, dialogs, run config). Stall is "right now" transient state, not render state; it belongs with the other transient signals.

This is a contract decision the architect owns; the Technical Note is advisory and predates the `isThinking` precedent landing. AC compliance is unaffected (the ACs are behavioural about the *screen*, not about where the flag lives).

### Key types & contracts

**1. ViewModel — sibling flow** (`ThreadViewModel`, beside `isThinking` at :216):

```kotlin
// Whether the current conversation is stalled (#395) — drives the prominent snapshot CTA (#396).
// Sibling StateFlow like connectionState / isThinking; sourced from the already-injected repository.
val isStalled: StateFlow<Boolean> =
    repository.observeStall(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = false)
```

`distinctUntilChanged` is already applied inside the remote impl's `observeStall` (per #395) and the facade default is `false`, so no extra operator is needed. `WhileSubscribed(5_000)` matches the two sibling flows.

**2. New component** (`StallPromotionBanner`, in `ui/conversations/components/`):

```kotlin
@Composable
fun StallPromotionBanner(isStalled: Boolean, onShowLiteralScreen: () -> Unit, modifier: Modifier = Modifier)
```

- Behavior: `if (!isStalled) return` (early-return idiom from `ConnectionBanner`/`ThinkingIndicator`); otherwise a full-width clickable `Surface` whose `onClick = onShowLiteralScreen`, containing an explanatory line + the "Show the literal screen" action label.
- Styling default (design-owed): `tertiaryContainer` / `onTertiaryContainer` — prominent and attention-drawing, but deliberately **not** `errorContainer` (a stall is a degrade fallback, not an error; `ConnectionBanner.Offline` owns the error-red banner). The developer fetches the 16-8 design context and adjusts container/typography when the frame is drawn; until then this default ships, exactly as `ThinkingIndicator` shipped its M3 spinner default.
- Stateless and previewable: two `@Preview`s (light/dark, `isStalled = true`), mirroring `ConnectionBanner`/`ThinkingIndicator`.
- One public top-level type in the file ⇒ filename = `StallPromotionBanner.kt` (ktlint single-class-filename rule).

**3. ThreadScreen** — add the param and render the banner:

- Signature: add `isStalled: Boolean = false` beside `isThinking: Boolean = false` (:74). Defaulted ⇒ the four in-file previews and any other call site inherit `false`; **zero forced call-site changes** (same property as `isThinking`).
- Placement: render `StallPromotionBanner(isStalled = isStalled, onShowLiteralScreen = onShowLiteralScreen)` inside the content `Column` directly **below** `ConnectionBanner` (after :120), above the workspace chip / empty state / message list. Rationale: a persistent top banner is the most prominent surface and is the direct analogue of `ConnectionBanner` (connection-degraded → top banner; parse-degraded/stalled → top banner). It is outside the scrolling `LazyColumn` so it does not scroll away while the stall holds.

**4. MainActivity** — live wiring (mirror `isThinking`, :349 + :363):

```kotlin
val isStalled by vm.isStalled.collectAsStateWithLifecycle()
// …in ThreadScreen(…): isStalled = isStalled,
```

`onShowLiteralScreen` is already bound to the literal-screen navigation here — the banner reuses it verbatim (AC #4).

### Data flow

```
RemoteConversationRepository.observeStall(convId)   (#395; false when no live connection, via facade)
        │  Flow<Boolean>
        ▼
ThreadViewModel.isStalled : StateFlow<Boolean>       (stateIn, WhileSubscribed)
        │  collectAsStateWithLifecycle()  (MainActivity)
        ▼
ThreadScreen(isStalled = …)
        │
        ▼
StallPromotionBanner(isStalled) → onClick = onShowLiteralScreen → navigate("literal_screen/$convId")  (already-shipped #382 path)
```

Onset (`true`) ⇒ banner appears; clear (`false`, on the next forward-progress event per #395) ⇒ `return` ⇒ banner disappears (AC #3). No new data path; the CTA enters the identical navigation the overflow item uses (AC #4).

## State + concurrency model

- `isStalled` is a cold `repository.observeStall` projection `stateIn`'d on `viewModelScope` with `WhileSubscribed(5_000)` — identical lifecycle to `connectionState`/`isThinking`. No new coroutine, no `viewModelScope.launch`, no dispatcher choice (pure flow plumbing).
- Single source of state per signal preserved: the flag lives in exactly one place (`isStalled`); the screen is a pure function of it. No parallel mutable state.
- Recomposition: `StallPromotionBanner` takes a `Boolean` + a stable lambda (`onShowLiteralScreen` is a method reference / remembered nav lambda at the call site, as `isThinking`'s callbacks already are) ⇒ skippable, recomposes only on flag flips.
- Reconnect: the facade reports `false` with no live connection and a fresh per-connection repo re-derives stall from the live stream (#395) ⇒ the screen needs no special handling; the banner simply tracks the boolean.

## Error handling

None. This slice surfaces a single `Boolean`; there are no network/IO/parse/permission failure modes here (the untrusted parse and its fail-closed posture live entirely in #395). The banner has no error state — it is shown iff `isStalled`.

## Testing strategy

Test-first (RED → GREEN). Two layers:

**Unit — `ThreadViewModelTest` (`./gradlew testDebugUnitTest --tests "de.pyryco.mobile.ui.conversations.thread.ThreadViewModelTest"`).** Mirror the `isThinking` block (:119-220) and `makeVm` (:1141). The flag is fed by `repository.observeStall`, so use a controllable repository — the cleanest is a small double that delegates everything to `FakeConversationRepository()` via Kotlin `by`-delegation and overrides only `observeStall` to return a `MutableStateFlow<Boolean>` (so the rest of the `ConversationRepository` surface the VM touches stays seeded). Scenarios (bullets, developer writes bodies in-idiom):

- **Initial value is `false`** with a plain `FakeConversationRepository()` (inherits `flowOf(false)`): `vm.isStalled.value == false`, no collector (the `WhileSubscribed` initial value).
- **Reflects onset → clear.** Controllable repo; launch a `vm.isStalled.collect {}`; flip the backing stall flow `true` ⇒ `vm.isStalled.value == true`; flip `false` ⇒ `false`. (Asserts AC #1 + AC #3 at the VM layer.)
- **Per-conversation routing** is already guaranteed by #395's `observeStall(conversationId)`; no need to re-test it here (the VM passes its own `conversationId` straight through). One optional assertion: a VM for `convId = "c1"` observes only `observeStall("c1")`.

**Instrumented Compose — new test class beside `ThreadScreenOverflowTest` (`./gradlew connectedAndroidTest`, device required).** Use the same `ComposeTestRule` + `baseState()` + `setContent` harness. Scenarios:

- **Stalled ⇒ affordance shown (AC #1).** `setContent { ThreadScreen(state = baseState(), isStalled = true, onShowLiteralScreen = …, …) }`; assert the explanatory text and/or "Show the literal screen" CTA `assertIsDisplayed()`.
- **Not stalled ⇒ affordance absent, overflow item still present (AC #2).** `isStalled = false`; assert the banner text `assertDoesNotExist()`; assert the overflow "Show the literal screen" item is still reachable (the un-promoted manual action is unchanged — open the overflow menu and assert it displays).
- **Trigger reuses the snapshot action (AC #4).** Record `onShowLiteralScreen` invocations; with `isStalled = true`, `performClick()` on the banner; assert the callback fired exactly once. (No new event/data path is exercised — the test asserts the *same* callback the overflow item uses.)
- Optional: a `StallPromotionBanner`-only test (component in isolation) for shown/hidden/click, mirroring `ThinkingIndicatorTest`.

`FakeConversationRepository` needs no change (inherits the `flowOf(false)` default from #395).

## Open questions

1. **`idle`/`turn_end` clearing vs. #396 UX (resolves #395 Open Question 1).** Resolved: this slice treats the stall as a single boolean — promote while `true`, un-promote while `false`. We do **not** distinguish "finished" from "recovered mid-turn"; *any* forward-progress event clears the stall (per #395), which un-promotes the banner. That is the correct, minimal UX for the degrade fallback: the recommendation exists only while the stall condition holds. No data-layer change, no extra derivation over `liveSessionEvents`.
2. **Interaction with `ThinkingIndicator`.** In practice a stall and `thinking` are mutually exclusive (a `turn_state` event clears the stall; `isThinking` is itself a `turn_state` phase), so both flags being `true` at once is not expected. No coordination logic is specified; if design later wants an explicit either/or, that is a follow-up. Flagged so code-review doesn't treat the absence of coordination as a gap.
3. **Banner copy + exact styling/placement** are design-owed (see Design source). The M3 `tertiaryContainer` top-banner default + reused "Show the literal screen" label ship now; pixel fidelity lands when 16-8 gains the treatment.
