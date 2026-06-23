# Spec #461 — Render the queued-message backlog in the conversation thread

**Ticket:** pyrycode-mobile #461 (`feat(ui)`, `size:s`, **not** `security-sensitive`)
**Split from:** #429 (epic pyrycode#597 Phase 3, queued-message backlog). **Blocked by:** #460 (the decode/observe substrate — **merged**, PR #463). The drop slice is #462 (blockedBy this).

This is the **render** slice: read the already-decoded `observeQueue(conversationId)` flow (#460) through the thread ViewModel, surface it on `ThreadUiState`, and render the ordered backlog as a de-emphasized foot-of-list section. It is the render-after-decode twin of the **thinking indicator** (#386 → `ThinkingIndicator`) and the **stall promotion** (#396 → `StallPromotionBanner`): a design-owed, M3-default foot-of-list affordance driven by a hoisted signal. No new wire, no decode, no trust boundary — `QueuedMessage` arrives pre-validated from the data layer.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:45-55` — the `observeQueue(conversationId): Flow<List<QueuedMessage>>` contract (FIFO order, empty until first snapshot, cold). `:196-207` — the `QueuedMessage(id: Long, text: String, timestamp: Instant)` element type. **This is the data you render** — already decoded/validated by #460; do not re-parse.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:80-101` — `ThreadUiState` (the single hoisted state; add the `queuedMessages` field here). `:186-233` — the `threadItems` flow + the 5-arg `state` `combine` (**the surfacing edit site** — fold `observeQueue` in here via an intermediate combine). `:519-530` — the file-private `RunConfig` / `TransientDialogs` data classes (where `ThreadContent` joins them). `:262-277` — `isStalled`: the sibling-`StateFlow` precedent you are deliberately **not** copying for surfacing (see § Design, "Why a `ThreadUiState` field, not a sibling `StateFlow`").
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:151-261` — the Scaffold-content `Column`: `ConnectionBanner` → `StallPromotionBanner` → (`WorkspaceChip`) → `LazyColumn`/`EmptyThreadState` (`weight(1f)`) → `ThinkingIndicator`. **The render insertion site** is between the list block and `ThinkingIndicator` (line ~259-260). Note the screen reads only `state` + sibling params — adding a `ThreadUiState` field needs **no** new screen parameter.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt:29-88` — `UserMessageBubble` + its private bubble constants (`UserBubbleShape` rounded with `bottomEnd = 6.dp`, `UserBubbleMaxWidth`, paddings, `primaryContainer`/`onPrimaryContainer`, end-aligned `Row`, plain `Text`). **Mirror this** for the queued row; the constants are `private`, so redeclare the few you need locally.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ThinkingIndicator.kt` (whole) — the closest sibling: design-owed, pure function of a hoisted signal, **early-return when inactive**, `cd_*` contentDescription, M3 default until Figma lands. Your `QueuedBacklog` is this shape with a list instead of a boolean.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StallPromotionBanner.kt` (whole) — the other sibling; copy its KDoc style (the "design-owed, M3 default, Figma 16-8 not yet drawn" framing) and its previews-pair layout.
- `app/src/main/res/values/strings.xml:54-57` — `thread_thinking_label` / `cd_thread_thinking` / `thread_stall_promotion_message` / `cd_thread_stall_promotion`. Add the two queue strings (§ Design) directly beneath these.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThinkingIndicatorTest.kt` (whole) — **the exact screen-test idiom for AC #5**: `createComposeRule()`, `setContent { ThreadScreen(state = …) }`, assert via `onNodeWithContentDescription` / `onNodeWithText`. Your `QueuedBacklogTest` mirrors it (construct a `ThreadUiState` with `queuedMessages = …`).
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:585-627` — the `isStalled` VM tests (initial-inert / onset / own-conversation-id). `:1783-1792` — the `makeVm` helper. `:1992-2002` — `StallControllableRepo` (`ConversationRepository by delegate`, overrides one observe-method with a controllable `MutableStateFlow`). **Mirror these** for the queue surfacing test (§ Testing).
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt:49` — constructs `ThreadUiState`; the new field is **defaulted** (`= emptyList()`), so this compiles unchanged. **No edit needed** — listed only so you don't go hunting when `codegraph_impact ThreadUiState` flags it.
- `docs/specs/architecture/460-observe-queue-state.md` — the data substrate. § "Open questions" #1 (stall/queue are **independent** — do not build a combined "stalled with N waiting" view here) and § "Threat model alignment" (the UI-leakage flag forwarded to this slice — resolved in § Confidentiality posture below).

## Context

Epic pyrycode#597 Phase 3: while claude is busy, the daemon buffers inbound phone turns and broadcasts the current backlog as a `queue_state` envelope. #460 (merged) decodes that into an observable per-conversation `Flow<List<QueuedMessage>>`. This slice **shows** the backlog so the user knows what is still waiting to be sent. Dropping an entry is #462 (next).

The data is live and validated upstream: `observeQueue` already applies `distinctUntilChanged`, is empty until the first snapshot, replaces the backlog in full on each new snapshot, and is connection-scoped (empty between connections). This slice adds **only** UI: one `ThreadUiState` field, one combine fold, one stateless component.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The Conversation Thread Screen is a dark M3 column: a top app bar, a scrolling message list of end-aligned **user bubbles** (`Schemes/primary-container` fill, `on-primary-container` text, 20dp corners except a 6dp bottom-right notch) and start-aligned assistant content, with session-delimiter rules between sessions, a thin status row, and a rounded composer. **The queued-backlog affordance is design-owed — it is not yet drawn on `16-8`.** Per the ticket, match the thread's existing message-row treatment: render queued entries as user-side bubbles (same shape/colour family) but **de-emphasized** (reduced opacity) with a small leading "waiting" affordance so they read as *not yet sent*, distinct from the full-opacity sent bubbles. This mirrors how `ThinkingIndicator` (#386) and `StallPromotionBanner` (#396) shipped an M3 default against the same un-drawn `16-8` frame; the final visual lands on this node later.

## Design

Three production files; two new files total (component + screen test). **No new screen parameter, no `MainActivity` change** — the queue rides on the existing `state: StateFlow<ThreadUiState>`.

| File | Edit |
|------|------|
| `ui/conversations/thread/ThreadViewModel.kt` | add `queuedMessages: List<QueuedMessage>` to `ThreadUiState`; add a file-private `ThreadContent` + a `threadContent` combine folding `observeQueue` with `threadItems`; consume it in the `state` combine |
| `ui/conversations/components/QueuedBacklog.kt` | **new** — the stateless `QueuedBacklog(queued, modifier)` section + a private queued-row composable + previews |
| `ui/conversations/thread/ThreadScreen.kt` | call `QueuedBacklog(...)` in the Scaffold-content `Column`; add a preview overload exercising a non-empty queue |
| `res/values/strings.xml` | add `thread_queued_backlog_label`, `cd_thread_queued_backlog` |

### Why a `ThreadUiState` field, not a sibling `StateFlow`

`isThinking` / `isStalled` / `currentModal` are sibling `StateFlow`s passed to `ThreadScreen` as separate parameters because each is a **transient cross-cutting boolean/scalar** (connection status, a spinner, a single app-level overlay). The queue is different: it is **thread content** — an ordered list of message text that extends the user's side of the conversation, the same category as `ThreadUiState.items`. AC #4 asks for exactly this ("state in via `UiState`; the composable reads no repository directly"). Putting it on `ThreadUiState`:

- keeps `ThreadScreen`'s signature stable (no new param) and **`MainActivity` untouched** — the queue rides the already-collected `state`; zero call-site cascade (contrast `isStalled`, which added a screen param + a `MainActivity` wiring line);
- makes the AC #5 screen test a one-liner (construct `ThreadUiState(queuedMessages = …)`), consistent with how the codebase tests render paths (`ThinkingIndicatorTest`);
- preserves "single source of state per ViewModel" — the queue is folded into the one hoisted `state`, not a parallel surface.

### Surfacing (ViewModel)

`ThreadUiState` gains one field (defaulted, so existing constructions compile):

```kotlin
val queuedMessages: List<QueuedMessage> = emptyList()
```

The `state` combine already uses **all five** typed `combine` arms; a sixth flow does not fit the typed overload. Fold `observeQueue` together with `threadItems` into a file-private pair, then use it in the existing `threadItems` slot:

```kotlin
private val threadContent: Flow<ThreadContent> =
    combine(threadItems, repository.observeQueue(conversationId)) { items, queued -> ThreadContent(items, queued) }
// data class ThreadContent(val items: List<ThreadItem>, val queued: List<QueuedMessage>)  — file-private, by RunConfig
```

In the `state` combine, replace the `threadItems` arm with `threadContent`, then set `items = content.items`, `queuedMessages = content.queued`, and source `hasMessages` from `content.items`. No new operator: `threadItems` and `observeQueue` each already carry `distinctUntilChanged`, and `stateIn` dedups the resulting `ThreadUiState` by data-class equality. Both arms emit immediately (the `scan` seeds `emptyList()`; `observeQueue` seeds `emptyList()`), so the combine never stalls.

### Rendering (component + screen)

`QueuedBacklog` — a stateless component in `ui/conversations/components/`, the `ThinkingIndicator` shape with a list:

```kotlin
@Composable
fun QueuedBacklog(queued: List<QueuedMessage>, modifier: Modifier = Modifier)
// queued.isEmpty() -> return (render nothing)            // AC #2
// else -> Column of one row per entry, in list order      // AC #1 (FIFO == wire order; no sort/dedup)
//   container carries cd_thread_queued_backlog (mergeDescendants) for a11y + the AC#5 test anchor
```

Each row mirrors `UserMessageBubble` but de-emphasized to read as *not yet sent* (the visual distinction AC #1 requires): an end-aligned `Row`; a small leading "waiting" glyph (`Icons.Outlined.Schedule`, tint `onSurfaceVariant` — `material-icons-extended` is already a dependency, used by `ToolCallRow`); a `Surface` bubble (the same rounded user shape, `primaryContainer`/`onPrimaryContainer`, plain `Text(entry.text, bodyMedium)` — **plain `Text`, not `MarkdownText`**, matching `UserMessageBubble`, since this is un-sent user input); the whole row at a reduced `Modifier.alpha(...)` (a local `QUEUED_ALPHA` ≈ 0.6f, in the spirit of `ThreadScreen.ABOVE_DELIMITER_ALPHA`). Redeclare the handful of bubble constants locally (MessageBubble's are `private`). Two previews (light/dark) with a 2–3 entry fixture, per the component-pair convention.

**Placement** — in the `ThreadScreen` Scaffold-content `Column`, insert between the `LazyColumn`/`EmptyThreadState` block and the existing `ThinkingIndicator` call:

```
ConnectionBanner → StallPromotionBanner → (WorkspaceChip) → [list | empty] (weight 1f)
   → QueuedBacklog(queued = state.queuedMessages)          // NEW — content continuation, below the thread
   → ThinkingIndicator(...)                                 // unchanged, stays foot-most above the composer
```

The backlog sits directly below the message list (it continues the user's side of the conversation); `ThinkingIndicator` keeps its shipped foot-most position. The backlog is **not** placed inside the `LazyColumn` — keeping it a separate wrap-content section leaves the list's keying / alpha-dimming / auto-scroll logic untouched (lowest blast radius) and is the established foot-of-list-affordance pattern (`ThinkingIndicator`, `StallPromotionBanner`). The `weight(1f)` on the list absorbs the section's height; a typical backlog is a few entries.

### Strings

```xml
<string name="thread_queued_backlog_label">Queued</string>
<string name="cd_thread_queued_backlog">Queued messages waiting to send</string>
```

`thread_queued_backlog_label` is the optional section caption / per-row label; `cd_thread_queued_backlog` is the section `contentDescription` (a11y + test anchor). No server text is ever placed in a string resource — `entry.text` renders only through the bubble `Text`.

## State + concurrency model

- **Single hoisted state.** The queue is one more field on the existing `state: StateFlow<ThreadUiState>`; no new `StateFlow`, no new `viewModelScope` job. `stateIn(WhileSubscribed(5_000))` is inherited unchanged.
- **Cold, reconnection-correct.** `observeQueue` is a cold projection of the data layer; it re-emits `emptyList()` on subscribe and is empty between connections (#460's facade `switchToLive(emptyList())`). The VM adds no caching, so a reconnect re-derives the backlog from the next live snapshot — nothing stale survives.
- **No dispatcher choice.** Pure in-process flow folding on `viewModelScope`'s default dispatcher; the component is stateless (no `remember`, no `LaunchedEffect`, no `rememberSaveable`).
- **Recomposition.** `QueuedMessage` is a stable data class (`Long` / `String` / `Instant` fields); `List<QueuedMessage>` is a stable read. `QueuedBacklog` is `restartable` + skippable. No lambda capture instability (no callbacks — this is read-only render; the per-row drop affordance is #462).

## Error handling

None at this layer. The data layer surfaces only a `List<QueuedMessage>` (never an error type — #460 drops malformed snapshots at the decode boundary and keeps the collector alive). The component's only "edge" is the empty list, which is the AC #2 render-nothing path, not an error. No network, IO, parse, or permission failure mode is introduced.

### Confidentiality posture (the #460-forwarded UI-leakage flag)

#460 forwarded the screenshot/overlay/accessibility-eavesdropping question to this visible-render slice. **Resolution (confirmed against the thread host): no new screen-capture defense is added, and that is consistent.** `entry.text` is user-authored message text — the *same content class* the thread host already renders for sent user messages (`UserMessageBubble`) **without** `FLAG_SECURE`. In this app `FLAG_SECURE` is reserved for verbatim screen-content surfaces — `LiteralScreenSurface` (the literal-snapshot screen) and `PermissionModalOverlay` (the permission modal, `SecureFlagPolicy.SecureOn` on its own window). `ThreadScreen` itself carries no `FLAG_SECURE`; the queued backlog renders in that same host, so it introduces no leakage surface the thread doesn't already have. The component also logs nothing and writes no `entry.text` to `rememberSaveable` / saved-instance state (it holds no state at all), matching the data layer's verbatim-no-log discipline. This slice is therefore **not** `security-sensitive` (no untrusted-parse point, no trust boundary, no new exposure) — consistent with the ticket's label set.

## Testing strategy

**Unit (`./gradlew testDebugUnitTest --tests "…ThreadViewModelTest"`)** — surfacing (AC #4). Mirror the `isStalled` block; add a `QueueControllableRepo` (`ConversationRepository by delegate`, overriding `observeQueue` with a `MutableStateFlow<List<QueuedMessage>>`, recording observed ids) beside `StallControllableRepo`:

- **Empty by default / inert fake.** A plain `FakeConversationRepository` (inherits `observeQueue`'s `flowOf(emptyList())` default) → `vm.state.value.queuedMessages == emptyList()`.
- **Reactive surfacing (AC #3, #4).** `QueueControllableRepo`; collect `vm.state`; push `[QueuedMessage(1,"a",t1), QueuedMessage(2,"b",t2)]` → `state.queuedMessages` becomes that list **in order**; push `[QueuedMessage(3,"c",t3)]` → replaces (full-snapshot semantics flow through); push `emptyList()` → clears.
- **Own conversation id only.** Assert `QueueControllableRepo.observedIds` are all the active conversation id (mirrors `isStalled_observesOnlyOwnConversationId`).

**Instrumented screen test (`./gradlew connectedAndroidTest`, new `QueuedBacklogTest`)** — render path (AC #1, #2, #5). Mirror `ThinkingIndicatorTest` (construct `ThreadUiState` directly, `setContent { ThreadScreen(state = …) }`); resolve `cd_thread_queued_backlog` via `InstrumentationRegistry…getString`:

- **Renders in order (AC #1, #5).** State with `queuedMessages = [entry "first", entry "second"]` → both texts displayed; assert "first" renders **above** "second" (compare `onNodeWithText(...).getUnclippedBoundsInRoot().top`) — wire order preserved.
- **Empty → nothing (AC #2).** State with `queuedMessages = emptyList()` → `onNodeWithContentDescription(cd_thread_queued_backlog).assertDoesNotExist()`.
- **Reactive (AC #3).** Drive `ThreadUiState` from a `mutableStateOf` (the `indicator_tracks_the_hoisted_flag` idiom): empty → absent; set a non-empty list → the rows appear; back to empty → absent.
- **Coexists with messages.** State with both `items` (sent messages) and a non-empty `queuedMessages` → the backlog renders below the list and the sent messages still render (the section is additive, not a replacement).

Scenarios are bullets — the developer writes them in the project idiom (red → green). `assertDoesNotExist` / `assertIsDisplayed` are `SemanticsNodeInteraction` members (no import); `compileDebugAndroidTestKotlin` catches instrumented-test breaks with no device attached.

## Open questions

1. **Tall backlogs.** `QueuedBacklog` is a wrap-content `Column`; the list's `weight(1f)` absorbs its height, and real backlogs are short (the daemon's `msgqueue` cap is server-side). If a future observation shows the queue growing tall enough to crowd the composer, cap it with `heightIn(max = …)` + internal scroll — **deferred** (no observed failure; Evidence-Based Fix Selection). Not built now.
2. **Stall × queue interaction.** Per #460's open question, `observeStall` and `observeQueue` are independent; this slice renders the queue only. A combined "stalled with N waiting" presentation is a separate derivation, explicitly **out of scope** — do not build it.
3. **Empty thread + non-empty queue.** The rare case (a queue exists before any persisted message) shows `EmptyThreadState` with the backlog below it. Harmless and unlikely (a queue implies prior sends); not specially handled to avoid coupling the empty-state gate to the queue. Flag only.
