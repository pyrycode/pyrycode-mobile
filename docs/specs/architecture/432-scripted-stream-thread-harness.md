# 432 — Layer 1a: scripted-stream thread harness + text + spinner

**Ticket:** [#432](https://github.com/pyrycode/pyrycode-mobile/issues/432) · size **S** · no `security-sensitive` · no Figma (renders existing `ThreadScreen`; no new visual design).

## Context

The mobile e2e ladder (`docs/e2e-interactive-stream.md`) has two halves of the structured-stream render path covered **separately** today:

- The **fold** — scripted live-session events → `List<ThreadItem>` — is unit-tested in `RemoteConversationRepositoryTest` (host JVM, `FakeSessionPump` + envelope pushes, no render).
- The **render** — `ThreadScreen(state, isThinking, …)` — is component-tested in `ThinkingIndicatorTest` (instrumented `createComposeRule`, from a **hand-built** `ThreadUiState`).

This ticket builds the **rung-2** harness that joins them: a reusable component-level harness that drives a scripted sequence of structured events through the **real production graph** (repository fold + ViewModel) and renders the assembled thread, with no daemon/network/host-orchestration. It ships the harness plus the first two render cases (text, spinner). Sibling **#435** (Layer 1b: tool rows, session divider, connection banner) rides this same harness and is blocked on it — so the harness entry point is the load-bearing deliverable.

### Where the "real fold" actually lives (read before designing)

The ticket's phrase "the real repository fold" spans **two** real production sites — confirm both before writing:

1. **`assistant_delta` → streaming row → finalize on `turn_end` (#337)** lives in **`RemoteConversationRepository`** (`applyAssistantDelta` / `finalizeAssistantTurn`), folded into `observeMessages()`. This is the **production-canonical** streaming row — the real app renders it. `RemoteConversationRepositoryTest` (line ~2334+) drives exactly this via `FakeSessionPump` + `assistantDeltaEnvelope`/`turnEndEnvelope`.
2. **`turn_state` → `isThinking` (#406)** is derived in **`ThreadViewModel.isThinking`** (`thinkingTransition` over `liveSessionEvents`), exposed as a sibling `StateFlow<Boolean>` and taken by `ThreadScreen` as a separate `isThinking` param.

> ⚠️ `ThreadViewModel` *also* contains a delta fold (`ThreadFold`/`reduceDelta`/`render` in `threadItems`). In production it is **dormant for the streaming row**: its `render()` guard (`finished.any { id == turn.turnId }`) suppresses the VM synthetic whenever the repo fold already produced a row with that `turnId` — which it always does for the live repo. So the **repository fold wins** at render time. A harness that drove only the VM fold (fake repo + scripted `LiveSessionEvent`s) would exercise the *dormant* path and miss regressions in the real one. **The harness must drive the real `RemoteConversationRepository`** so the production-canonical fold is the one under test. This is the central design constraint.

The faithful production path the harness reproduces end-to-end:

```
scripted Envelope  →  FakeSessionPump.inbound  →  RemoteConversationRepository
   (decode #385 + fold #337 → observeMessages;  emit LiveSessionEvent → liveSessionEvents)
   →  ThreadViewModel  (state = combine(…, threadItems, …);  isThinking = thinkingTransition(liveSessionEvents))
   →  ThreadScreen(state, isThinking, …)  →  rendered thread
```

## Files to read first

| Path (key lines) | What to extract |
| --- | --- |
| `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:3499-3517` | `FakeSessionPump` (Channel.UNLIMITED `inbound`, recording `send`, `push`) — **port this** into androidTest verbatim; it depends only on `SessionPump`/`Envelope` (both in `main/`). |
| `…/RemoteConversationRepositoryTest.kt:3392-3473`, `:3487-3496`, `TS` const `:3520` | Envelope builders `turnStateEnvelope` / `assistantDeltaEnvelope` / `turnEndEnvelope` / `conversationsEnvelope` — **port the ones the two cases need**; each is a 1-line `MobileJson.parseToJsonElement(...)` over `Envelope`. |
| `…/RemoteConversationRepositoryTest.kt:2334-2455` | The exact fold cases to reproduce through render (single delta, concatenation, `turn_end` finalize, capability gate). Mirror the scripts; render instead of inspecting `ThreadItem`. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:89-118`, `:208-213`, `:688-735`, `:822-833` | Constructor (`pump`, `scope`, `negotiatedCapabilities` **supplier** — must return `setOf("interactive")` or the fold/`liveSessionEvents` are gated **closed**); `liveSessionEvents` SharedFlow (replay=0); `applyAssistantDelta`/`finalizeAssistantTurn`; `observeMessages`. |
| `app/src/main/java/de/pyryco/mobile/data/repository/SessionPump.kt:19-32` | The 2-member interface `FakeSessionPump` implements (`inbound: Flow<Envelope>`, `send: Boolean`). |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:105-116`, `:178-252`, `:304-315` | VM constructor (5 args; `liveSessionEvents` 5th, defaulted) — pass `repo.liveSessionEvents`; `state`/`threadItems`/`isThinking` (`WhileSubscribed(5000)`); `thinkingTransition` (`thinking`→true, everything else→false, conv-id–filtered). |
| `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThinkingIndicatorTest.kt:24-31`, `:63-97` | The instrumented render precedent: `createComposeRule()`, `cd_thread_thinking` content-description lookup, `ThreadScreen(...)` call shape, `assertIsDisplayed`/`assertDoesNotExist`. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:81-104` | `ThreadScreen` signature — required params (`state`, `onBack`, `onSendMessage`, `connectionState`, `onRetry`) + `isThinking`; everything else defaulted. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt:41`, `:104-151` | Render targets: finalized row → `MarkdownText` (plain text matchable by `onNodeWithText`, see `LiteralScreenSurfaceTest.kt:56`); streaming row → progressive reveal + caret glyph `STREAMING_CARET_GLYPH = "▎"`. |
| `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:55-73` | Real-`AppPreferences`-from-`DataStore` construction (`PreferenceDataStoreFactory.create(scope, produceFile)`) + `SavedStateHandle(mapOf("conversationId" to id))`. Adapt `produceFile` to the instrumentation `targetContext`. |
| `app/src/main/java/de/pyryco/mobile/data/repository/FakeConnectionStateSource.kt` | Lives in `main/` → reusable from androidTest; supplies a `ConnectionState.Connected` source for the VM (no port needed). |
| `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt:12` | Confirm the `AppPreferences` constructor param type (`DataStore<Preferences>`). |
| `docs/e2e-interactive-stream.md` (Constraints, "The ladder" rung 2) | Tolerant-assert rule (substring/presence, generous timeouts; never on delta counts/timing). |

## Design

### Placement decision — instrumented androidTest (not Robolectric)

The harness lives in **`app/src/androidTest/`** and renders via `createComposeRule()`.

The ticket delegates "instrumented vs Robolectric host-JVM" to the architect. Decision: **instrumented**, because:

- **Render precedent is 100% androidTest.** Every existing Compose component render test (`ThinkingIndicatorTest`, `ThreadScreen*Test`, `StallPromotionBannerTest`) is instrumented `createComposeRule`. The ladder doc calls rung 2 "Compose render test — component level, deterministic, **no daemon**" — "no daemon," not "no emulator." The "no … emulator-host" in AC #1 refers to the rung-3/4 **host-daemon orchestration**, not the test device.
- **No Robolectric in the project** (`gradle/libs.versions.toml` / `app/build.gradle.kts` have zero references; no `sharedTest`/`testFixtures` source set). Adding it introduces a new test stack + Compose-under-Robolectric (`GraphicsMode`) config for marginal benefit — exactly the "don't add adjacent infra" the pipeline cautions against.
- **Cost of porting `test/` helpers is small** (`FakeSessionPump` + a few envelope builders are trivial, `main/`-only deps), so Robolectric's "reuse the `test/` driving stack" advantage is thin.

`./gradlew connectedAndroidTest` (device/emulator required) is this project's standard component-render suite — the harness joins it.

### New files (both androidTest, zero production change)

```
app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/
├── ScriptedThreadHarness.kt        # the reusable entry point (the #435 contract)
└── ScriptedThreadRenderTest.kt     # the two render cases (text, spinner)
```

`FakeSessionPump` + the envelope builders go **inside `ScriptedThreadHarness.kt`** as a private top-level class + private top-level fns (ktlint's single-public-class-per-file rule only constrains the **public** top-level type — `ScriptedThreadHarness` — so private siblings are fine; see `ktlint-filename-rule-single-class` lesson). Do **not** add a third file.

### Harness contract — `ScriptedThreadHarness`

The single shared entry point #435 extends. It wires the real graph, renders, and exposes a thin scripting + assertion surface. Contract (signatures, not bodies):

- **Construction** — `ScriptedThreadHarness(composeRule: ComposeContentTestRule, conversationId: String = "c1")`. Internally:
  - owns a `CoroutineScope` (real dispatcher) for the repository's inbound collector; cancelled by `close()`.
  - builds a real `AppPreferences` over a `DataStore<Preferences>` rooted at a unique file under the instrumentation `targetContext` (mirror `ThreadViewModelTest.newDataStore`, swap `produceFile`).
  - `pump = FakeSessionPump()`; `repo = RemoteConversationRepository(pump, scope, negotiatedCapabilities = { setOf("interactive") })`.
  - `vm = ThreadViewModel(SavedStateHandle(mapOf("conversationId" to conversationId)), repo, FakeConnectionStateSource(ConnectionState.Connected), appPreferences, repo.liveSessionEvents)`.
- **`start()`** (or do it in the constructor) — `composeRule.setContent { PyrycodeMobileTheme { ThreadScreen(state = vm.state.collectAsState().value, isThinking = vm.isThinking.collectAsState().value, connectionState = vm.connectionState.collectAsState().value, onBack = {}, onSendMessage = {}, onRetry = {}) } }`, then **seed** the conversation and `awaitReady()` (see State + concurrency).
  - Use `collectAsState()` (not `collectAsStateWithLifecycle()`) — `StateFlow`s, and `createComposeRule` has no `LifecycleOwner`.
- **Scripting surface** — one method per event the two cases need, each a thin `pump.push(builder(...))`:
  - `pushAssistantDelta(turnId: String, seq: Int, text: String)`
  - `pushTurnState(state: String)`  // "thinking" | "responding" | "idle"
  - `pushTurnEnd(turnId: String, stopReason: String = "end_turn")`
  - (internal) `seedConversation()` — `pump.push(conversationsEnvelope(one row with id == conversationId, is_promoted true))` so `observeMessages`/`observeConversations` have a conversation and `state`'s `combine` emits.
- **`close()`** — cancel the scope (call from the test's `@After`).

Keep the surface **minimal** — only what the text + spinner cases consume. #435 adds `pushToolUse`/`pushToolResult`/`pushMessage`/divider scripting on top; do not pre-build them here (YAGNI; #435 owns its own additions).

### The two render cases — `ScriptedThreadRenderTest`

`@get:Rule val composeRule = createComposeRule()`, `@Before` builds + starts the harness, `@After` closes it. Two `@Test`s. Assert **tolerantly** (substring/presence, `waitUntil` with a generous timeout) — never on delta counts or timing.

**Text case** — `text_deltasConcatenateAndFinalizeOnTurnEnd`:
- Script: `pushAssistantDelta("t1", 0, "hel")`, `pushAssistantDelta("t1", 1, "lo ")`, `pushAssistantDelta("t1", 2, "world")`, `pushTurnEnd("t1")`.
- Assert (after `turn_end`, the **finalized** state): `composeRule.waitUntil { onAllNodesWithText("hello world", substring = true).fetchSemanticsNodes().isNotEmpty() }`; then `onNodeWithText("hello world", substring = true).assertIsDisplayed()` and `onNodeWithText("▎", substring = true).assertDoesNotExist()` (no streaming caret).
- Use **plain alphanumeric** delta text (no markdown metacharacters) so `MarkdownText`'s rendered output matches the raw substring. Assert only **after** `turn_end`: the streaming body reveals progressively (~50 chars/s) and carries the caret, so the full string is reliably present only once finalized (`MarkdownText`, full content, no caret).

**Spinner case** — `spinner_shownWhileThinking_goneAfterTurnEnd`:
- Script step 1: `pushTurnState("thinking")` → `waitUntil`/assert `onNodeWithContentDescription(cd_thread_thinking).assertIsDisplayed()` (look the string up via `targetContext.getString(R.string.cd_thread_thinking)`, per `ThinkingIndicatorTest`).
- Script step 2: `pushTurnEnd("t1")` (or `pushTurnState("idle")`) → `waitUntil`/assert the indicator `assertDoesNotExist()`.
- **Script `thinking` specifically.** `isThinking` tracks the `thinking` phase only (`thinkingTransition`: `responding`/`idle`/`turn_end` → `false`). The ticket's "thinking / responding" is loose; the spinner shows for `thinking`. Do not script `responding` expecting the spinner.

## State + concurrency model

- **Dispatchers.** Instrumented → real Android main looper. `ThreadViewModel.viewModelScope` and the `WhileSubscribed(5000)` `stateIn`s run on the real Main; no `Dispatchers.setMain`. The repository's inbound collector runs on the harness-owned scope. Use `composeRule.waitUntil(timeoutMillis = …)` / `waitForIdle()` for all synchronization — there is no virtual clock, and that is fine: the fold has no internal delays (only `MessageBubble`'s reveal animation does, which the cases avoid by asserting the finalized state).
- **Subscription-before-push is mandatory (the key correctness constraint).** `repo.liveSessionEvents` is a **`replay = 0`** SharedFlow: a `turn_state`/`assistant_delta` emitted while the VM's collectors are not yet subscribed is **dropped, not buffered for late subscribers**. The VM's `state`/`isThinking` collectors attach only when `collectAsState()` composes (and `WhileSubscribed` then subscribes upstream on the next dispatch). Therefore the harness `start()` ordering is: `setContent` → seed conversation → **`awaitReady()`** → only then may a test push live events. Implement `awaitReady()` as `composeRule.waitForIdle()` followed by a `waitUntil` on an observable proof the pipeline is live and subscribed — e.g. the seeded conversation's title rendered in the top bar (`onNodeWithText(<seed name>)`), or the empty-thread surface present. This is the single most likely flake source; gate every live push behind it. (The seeded `conversations` snapshot is safe to push at any time — `observeConversations`/`observeMessages` are cold flows that re-emit the retained projection to new subscribers, unlike the `replay=0` live stream.)
- **Capability gate.** Construct the repo with `negotiatedCapabilities = { setOf("interactive") }`. With the default closed set, `assistant_delta` folds nothing and `liveSessionEvents` stays silent (see `assistantDelta_capabilityGateClosed_producesNoRow`) — the harness would render an empty thread and both cases would falsely fail.
- **Conversation-id match.** Every scripted envelope's `conversation_id` must equal the harness `conversationId` — `applyAssistantDelta` and `thinkingTransition` both filter on it first. The builders take it as a parameter; the harness threads its own id.
- **Cleanup.** `@After { harness.close() }` cancels the scope (stops the inbound collector + DataStore scope). The DataStore file is per-run unique; leaking it in the test sandbox is acceptable.

## Error handling

Not applicable as a product surface — this is additive test infrastructure with no failure modes to surface to a user. The only "failure" is a flaky assertion; the mitigations are the tolerant-assert rule and the subscribe-before-push ordering above. No `try`/`catch`, no result types.

## Testing strategy

- **The deliverable is the tests.** `ScriptedThreadRenderTest` runs under `./gradlew connectedAndroidTest` (device/emulator required), alongside `ThinkingIndicatorTest`. No unit (`./gradlew test`) coverage is added — the fold's unit coverage already exists in `RemoteConversationRepositoryTest`; this rung is the render join.
- **No new production code, so no production unit tests.** Confirm `./gradlew test`, `./gradlew lint`, `./gradlew spotlessCheck`, and `compileDebugAndroidTestKotlin` (or `assembleDebugAndroidTest`) all stay green — the realistic local verification when no device is attached (matches the e2e doc's "Verified here (host JVM, no device): … all androidTest sources compile").
- **Fakes vs real:** real `RemoteConversationRepository` + real `ThreadViewModel` + real `ThreadScreen` (the integration point); `FakeSessionPump` (ported) as the only seam; real `AppPreferences`/`DataStore`; `FakeConnectionStateSource` (from `main/`).

## Open questions

1. **`awaitReady()` robustness.** The `replay=0` subscribe-before-push race is handled by ordering + a `waitUntil` on a rendered proof. If the first push still occasionally races on slower emulators, the developer may need an explicit settle (a `waitUntil` that the VM's first non-initial `state` emission has landed). Flag if observed; do **not** pre-harden against an unobserved failure (evidence-based fix selection).
2. **Harness entry-point shape for #435.** This spec models it as a class taking the `ComposeContentTestRule`. If #435's scenarios (divider, connection banner) need additional `ThreadScreen` params wired (e.g. `connectionState` transitions, `isStalled`), they extend the harness's scripting surface then — the constructor already injects the real graph, so the extension is additive. Confirm the class shape reads naturally when the developer writes the second case; a top-level builder function is an acceptable alternative if it composes better with `createComposeRule`.
3. **`MarkdownText` substring fidelity.** Plain alphanumeric delta text renders 1:1. If a future case needs markdown content, the rendered (not raw) text must be matched — out of scope here; the two cases use plain text.
