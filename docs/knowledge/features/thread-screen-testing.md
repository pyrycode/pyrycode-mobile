# Thread screen — testing

Split out of [Thread screen](thread-screen.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Thread screen](thread-screen.md); see that document for what it does, its edge cases and its links.

## Testing

`app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt` — thirty-four JUnit 4 tests post-[#722](https://github.com/pyrycode/pyrycode-mobile/issues/722) (seven from #139, two from #188, three from #201, six from #137 in the workspace-chip group, one added in #246 for the `items` passthrough, two added in #145 for the stub effort/tokenPercent + default-model fields, four added in #253 for `selectedModel` plumbing, three added in #226 for channel-info open/dismiss/ingredient-population, four added in #227 for the delete family + one-shot nav, two added in #722 for the label-first rule's live-emission coverage — the numbered list below covers the #139→#253 core plus #722's two additions, appended after it; the #226/#227 additions are summarised in the sibling-test paragraph after that). The pre-#139 plain-JUnit shape (no `runTest`, no `Dispatchers.setMain`) no longer works because `stateIn(viewModelScope, …)` requires a `Main` test dispatcher to publish emissions in test scope; post-#253 the file additionally needs the `runTest { }` wrapper around every test because `makeVm` is now a `TestScope.()` receiver function that constructs a `TemporaryFolder`-backed `AppPreferences` on `backgroundScope`. The file adopts the canonical scaffold from `ChannelListViewModelTest:1-60` (extended in #253 with the prefs-DataStore harness from `AppPreferencesTest`):

```kotlin
@Before fun setUpMainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
@After  fun tearDownMainDispatcher() { Dispatchers.resetMain() }
```

Since #201 the file also carries a `private fun makeVm(handle, repository, source = FakeConnectionStateSource())` helper at the bottom. Every existing `ThreadViewModel(handle, repository)` call site was rewritten as `makeVm(handle, repository)` to absorb the new constructor arg without threading a fixture through nine call sites — the default arg keeps `state`-focused tests terse and the three connection-focused tests pass an explicit source. Same shape as `ChannelListViewModelTest.makeVm` from #239. In [#253](../codebase/253.md) the helper gained a fourth defaulted parameter `prefs: AppPreferences = AppPreferences(newDataStore())` and became a `TestScope` receiver function (so it can call the `TestScope.newDataStore()` helper that builds a `PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { tmp.newFile("prefs_${UUID.randomUUID()}.preferences_pb") })`). The `UUID.randomUUID()` per call guarantees per-VM prefs isolation when a single test constructs two VMs in one `runTest { }` block.

Tests:

1. **`state_initialValue_isConversationIdPlaceholderBeforeSubscription`** — synchronously reads `vm.state.value` *without* a launched collector; asserts the `stateIn` `initialValue` is `ThreadUiState(id, displayName = id)`. Pins the placeholder contract.
2. **`state_resolvedTitle_isChannelNameForSeededChannel`** — `runTest { launch collector; advanceUntilIdle(); assert displayName == "Personal" }` against `seed-channel-personal` and `FakeConversationRepository()`. Pins the happy-path channel-name resolution.
3. **`state_resolvedTitle_isUntitledDiscussionForUnnamedDiscussion`** — same shape against `seed-discussion-a` (seeded with `name = null, isPromoted = false`). Pins the discussion fallback.
4. **`state_resolvedTitle_isUntitledChannelForUnnamedChannel`** — uses the file-scope `fixedRepo(listOf(Conversation(name = null, isPromoted = true, …)))` helper; asserts `"Untitled channel"`. Pins the channel fallback.
5. **`state_resolvedTitle_fallsBackToConversationIdWhenConversationMissing`** — `fixedRepo(emptyList())` with `conversationId = "ghost-id"`; asserts `displayName == "ghost-id"`. Pins the missing-conversation fallback.
6. **`state_displayName_reemitsOnRename`** — collect into a `mutableListOf<ThreadUiState>()`; call `repository.rename("seed-channel-personal", "Personal — renamed")` inside the same `runTest` block; assert the post-rename emission's `displayName` matches. **Pins the load-bearing contract** that the downstream rename dialog (#141) inherits "for free" from option (c).
7. **`state_collapsesAbsentConversationIdToEmptyString`** — `SavedStateHandle(initialState = emptyMap())`; assert `state.value.conversationId == ""` synchronously. Pins the `.orEmpty()` narrowing (path not user-reachable in production).
8. **`sendMessage_blankText_isNoOp`** (#188) — calls `vm.sendMessage("")` then `vm.sendMessage("   \n\t ")`; asserts observed messages unchanged. VM-side blank-rejection contract.
9. **`sendMessage_nonBlankText_appendsToConversation`** (#188) — calls `vm.sendMessage("Hello world")`; asserts the appended `ThreadItem.MessageItem` lands with `content = "Hello world"`, `role = Role.User`, `sessionId = "seed-session-personal"`.
10. **`connectionState_initialValue_isConnected`** (#201) — construct VM with default `FakeConnectionStateSource()`. Without any collector, assert `vm.connectionState.value == ConnectionState.Connected`. Pins the AC1 default + the `WhileSubscribed` initialValue contract. Synchronous; no `runTest { }` wrapper needed.
11. **`connectionState_reemitsOnSourceChange`** (#201) — construct a `FakeConnectionStateSource` explicitly, build the VM with it, launch a collector, call `source.emit(ConnectionState.Offline)`, `advanceUntilIdle()`, assert `vm.connectionState.value == ConnectionState.Offline`. Pins the AC1 "exposes current state" wiring (not just the initialValue).
12. **`retry_invokesSourceRetry`** (#201) — construct a `RecordingConnectionStateSource` (file-private test double; see below), call `vm.retry()`, `advanceUntilIdle()`, assert `source.retryCallCount == 1`. Pins AC2: the VM actually forwards to the source rather than swallowing the call.
13. **`state_workspaceLabel_isScratch_whenCwdIsEmptyString`** (#137) — `repository.createDiscussion(workspace = null)`, assert pre-condition `freshDiscussion.cwd == ""`, then assert `vm.state.value.workspaceLabel == "scratch"`. Exercises the `cwd.isEmpty()` fallback arm of the shared `workspaceDisplayName(cwd, label)` (moved there from the deleted `Conversation.workspaceLabel()` extension in #722) — the actual default state of a fresh discussion per `FakeConversationRepository.createDiscussion`.
14. **`state_workspaceLabel_isScratch_whenCwdIsDefaultScratchSentinel`** (#137) — `fixedRepo` with one discussion at `cwd = DEFAULT_SCRATCH_CWD`, assert label `"scratch"`. Exercises the sentinel branch — both `""` and the sentinel must collapse to the same label, matching `FakeConversationRepository.bumpWorkspace`'s no-bound-workspace filter.
15. **`state_workspaceLabel_isBasename_forArbitraryCwd`** (#137) — `fixedRepo` with `cwd = "pyry-workspace/my-app"`, assert label `"my-app"`. Exercises the `substringAfterLast('/')` happy path.
16. **`state_chipFields_reflectChannelAndMessagePresence`** (#137) — single test walking both raw chip-gate fields across two VMs. Seeded channel (`seed-channel-personal`) → assert `isPromoted = true`, `hasMessages = true` (the seed carries messages). Fresh discussion via `createDiscussion(null)` → assert `isPromoted = false`, `hasMessages = false`. Then call `discussionVm.sendMessage("hi")`, `advanceUntilIdle()`, assert `hasMessages` flipped to `true` while `isPromoted` stays `false`. Locks the two raw signals that the chip's call-site `if (!isPromoted && !hasMessages)` consumes.
17. **`onWorkspacePicked_callsChangeWorkspaceOnceAndClearsPickerFlag`** (#137) — fresh discussion, call `vm.onWorkspaceChipTapped()`, assert `workspacePickerVisible == true`. Call `vm.onWorkspacePicked("pyry-workspace/my-app")`, `advanceUntilIdle()`, assert `workspacePickerVisible == false` AND the conversation's `cwd` is now `"pyry-workspace/my-app"` (re-fetched via `repository.observeConversations(All).first().first { it.id == … }`). Pins the side-effect-plus-flag-clear contract.
18. **`onWorkspacePickerDismissed_clearsFlagWithoutCallingChangeWorkspace`** (#137) — fresh discussion, `onWorkspaceChipTapped`, `onWorkspacePickerDismissed`, assert `workspacePickerVisible == false` AND the conversation's `cwd` is unchanged (re-fetched via the same path). Pins the no-side-effect dismiss path.
19. **`state_items_reflectsObserveMessagesStream`** (#246) — `FakeConversationRepository()`, VM on `seed-channel-personal` (the fake's seeded channel carries seeded messages per [#161](../codebase/161.md)), `runTest { launch collector; advanceUntilIdle() }`, assert `vm.state.value.items.isNotEmpty()` and `vm.state.value.items.first() is ThreadItem.MessageItem`. Mirrors the shape of `sendMessage_nonBlankText_appendsToConversation` from [#188](../codebase/188.md). Pins the load-bearing passthrough: the `observeMessages` stream now reaches the screen, not just the `hasMessages` derivation.
20. **`state_initialValue_includesDefaultModelEffortAndYolo`** (#145 as `…AndTokenPercentDefaults`; renamed `*Stub*` → `*Default*` in [#253](../codebase/253.md); trailing `tokenPercent` assertion dropped and renamed again in [#603](../codebase/603.md)) — wrapped in `runTest { }` for the prefs-IO context (was synchronous pre-#253). Constructs `makeVm(handle, FakeConversationRepository())`, asserts `vm.state.value.selectedModel == Model.OPUS_4_7`, `effort == "high"`, `yoloEnabled == false`. Pins the data-class default contract — the pre-subscription initial frame that `stateIn(initialValue = …)` publishes.
21. **`state_postSubscription_emitsDefaultModelEffortAndYolo`** (#145 as `…AndTokenPercent`; renamed in [#253](../codebase/253.md), trailing `tokenPercent` assertion dropped and renamed again in [#603](../codebase/603.md)) — `runTest { launch collector; advanceUntilIdle() }`, asserts `selectedModel == Model.OPUS_4_7`, `effort == "high"`, `yoloEnabled == false`. Pins the VM's explicit population inside `combine` — the assertion that fails if the prefs-defaulted `selectedModel` stops propagating through the pre-combined `selectedModelFlow`. #20 pins the pre-subscription `stateIn` initial value (the `data class` defaults); #21 pins the post-`combine` emission; they read as duplicates only because the preference default happens to match.
22. **`selectedModel_followsAppPreferencesDefault`** ([#253](../codebase/253.md)) — `runTest { }`; calls `prefs.setDefaultModel(Model.SONNET_4_6)` **before** VM construction, then `vm.state.first { it.selectedModel == Model.SONNET_4_6 }` inside `withTimeout(2.seconds)`. Pins the AC line "populates `selectedModel` from `appPreferences.defaultModel` on conversation open".
23. **`selectedModel_reemitsWhenAppPreferencesDefaultChanges`** ([#253](../codebase/253.md)) — subscribe, assert default `Model.OPUS_4_7`, call `prefs.setDefaultModel(Model.HAIKU_4_5)`, assert `state.first { it.selectedModel == Model.HAIKU_4_5 }`. Pins the reactive contract — the override-less case is a pure passthrough of `appPreferences.defaultModel`.
24. **`onModelSelected_overridesPerConversationWithoutMutatingPreferences`** ([#253](../codebase/253.md)) — the AC's **explicit verification line**: pre-asserts `prefs.defaultModel.first() == Model.OPUS_4_7`, calls `vm.onModelSelected(Model.HAIKU_4_5)`, asserts `vm.state.value.selectedModel == Model.HAIKU_4_5`, then re-asserts `prefs.defaultModel.first() == Model.OPUS_4_7` (unchanged). Fails if anyone wires `onModelSelected` to also call `appPreferences.setDefaultModel(...)`.
25. **`onModelSelected_overrideWinsOverSubsequentDefaultChange`** ([#253](../codebase/253.md)) — sets override to `Model.HAIKU_4_5`, then changes the prefs default to `Model.SONNET_4_6`, asserts `selectedModel` stays `Model.HAIKU_4_5`. Pins the `override ?: default` rule baked into `selectedModelFlow`.

Two tests added in [#722](https://github.com/pyrycode/pyrycode-mobile/issues/722), continuing the numbering above:

26. **`state_workspaceLabel_prefersConversationLabel_overCwdBasename`** (#722) — `fixedRepo` with one discussion at `cwd = "pyry-workspace/my-app"` and `workspaceLabel = "Design system"`; asserts `vm.state.value.workspaceLabel == "Design system"`. Pins the label-first rule at the ViewModel boundary — items 13-15's cwd-only fallback tests are unaffected, since they pass `workspaceLabel = null`.
27. **`state_workspaceLabel_followsLiveRenameAndClear_onOneSubscription`** (#722) — a `MutableStateFlow<List<Conversation>>`-backed `fixedRepo` overload (`fixedRepo(conversations: Flow<List<Conversation>>)`, delegated to by the existing `fixedRepo(List<Conversation>)`) emits the same conversation unlabelled, then labelled (`"Design system"`), then cleared (`workspaceLabel = null`) again; `vm.state.value.workspaceLabel` is asserted after each emission (`"my-app"` → `"Design system"` → `"my-app"`). An `onStart { subscriptions++ }` counter on the flow asserts `subscriptions == 1` across all three emissions, and `collector.isActive` is asserted true throughout — proving the open thread updates via re-emission of the *same* `observeConversations(All)` subscription, without reopening or resubscribing. Drives AC2 through the bound repository's conversation emissions, per the ticket's requirement.

The pure rule itself — the label-first ordering, the unconditional win over the scratch sentinel, verbatim (non-normalising) rendering including Unicode and markup-looking text, and the `MAX_WORKSPACE_LABEL_CHARS` clamp on a conformant vs. an oversized label — is exhaustively covered by a sibling file added in #722: `app/src/test/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayNameTest.kt`, eight JUnit 4 tests against the pure `workspaceDisplayName(cwd, label)` function with no VM, no `runTest`, no coroutines. See [`workspace-chip.md`](workspace-chip.md#workspacelabel-derivation) for the rule and its behaviour table.

The `fixedRepo(conversations)` helper is an anonymous `object : ConversationRepository { … }` with `TODO("not used")` overrides plus a `flowOf(conversations)`-backed `observeConversations`. It's kept local rather than extracted — each test's bespoke conversation shape would force a builder-shaped helper that doesn't pay for itself yet. Since [#722](https://github.com/pyrycode/pyrycode-mobile/issues/722) it has a second overload, `fixedRepo(conversations: Flow<List<Conversation>>)`, that the original `fixedRepo(List<Conversation>)` now delegates to (`= fixedRepo(flowOf(conversations))`) — a one-line addition rather than a second 40-line stub, used by test 27 above for its live-emitting double.

Sibling test file added in [#136](../codebase/136.md): `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenCutoffTest.kt` — six JUnit 4 tests against the `internal` top-level helper `mostRecentSessionBoundaryIndex(items: List<ThreadItem>): Int`: `emptyList → -1`, `messagesOnly → -1`, `singleBoundary → its index`, `multipleBoundaries → latest index`, `boundaryAtFirstPosition → 0`, `boundaryAtLastPosition → lastIndex`. No `runTest`, no `Dispatchers.setMain`, no coroutines — the helper is pure and synchronous. File-private `msg(id, sessionId, role, timestamp)` and `boundary(previousSessionId, newSessionId, occurredAt)` constructors keep the body terse; `BoundaryReason.Clear` is fine for every fixture (the helper doesn't discriminate on reason). The Compose-side correctness of the per-row `Box(Modifier.alpha(...))` wrap is verified visually by the two new `@Preview`s; no `ComposeTestRule` in this ticket because there's no interactive behaviour to assert.

Sibling test files added in [#226](../codebase/226.md): `app/src/test/java/.../thread/ThreadScreenMapperTest.kt` — three JVM unit tests over the pure `internal fun ThreadUiState.toChannelInfoUiModel(now)` mapper (label/count derivation + pass-through with a seeded `items` list and injected `now`; empty-items → em-dash `createdLabel` + zero `messageCount`; null `lastUsedAt` → em-dash `lastActivityLabel`), and `app/src/androidTest/java/.../thread/ThreadScreenChannelInfoTest.kt` — an instrumented Compose test (six `@Test`s following the `ThreadScreenOverflowTest` idiom) asserting the sheet renders with `channelInfoOpen = true` and that each button records the right event sequence (Rename / Change workspace emit-then-dismiss; Archive / Delete / close dismiss-only). `ThreadViewModelTest` also gained three `@Test`s (open / dismiss / ingredient-population from a seeded `Conversation`), and the existing `onOverflowEvent_otherCases_doNotCallArchive` dropped `ChannelInfo` from its iteration list (moved to its own positive test). The instrumented file is written but **not run** (no device); see [`../codebase/226.md`](../codebase/226.md) for the full breakdown.

Tests added in [#227](../codebase/227.md): in `ThreadViewModelTest`, `RecordingRepo` gains a `delete` override + `deleteCalls` list (else it inherits the throwing interface default and the confirm test crashes), and nav is asserted by collecting `navigationEvents` into a list via a second `launch`. The existing archive test was renamed `onOverflowEvent_archive_archivesClosesSheetAndPopsBack` and now also asserts `channelInfoOpen == false` + one `PopBack`; three new `@Test`s cover delete-tap (dialog opens, no `delete` call, no nav), delete-cancel (`DeleteDismiss` closes the dialog, sheet stays open, no `delete`), and delete-confirm (`delete` called + both surfaces closed + one `PopBack`); plus `navigationEvents_eachPopBackDeliveredExactlyOnce_notReplayed` pins AC #5 (consumed `PopBack` not replayed; a second trigger produces its own single event). `ThreadScreenChannelInfoTest` gained a `deleteConfirmState()` helper + a defaulted `state` param on `setContent`, renamed the two archive/delete tests to expect `[ThreadEvent.Archive]` / `[ThreadEvent.Delete]`, and added three dialog tests (title + interpolated-body display, dialog **Delete** → `[DeleteConfirm]`, **Cancel** → `[DeleteDismiss]`) — written but **not run** (no device). See [`../codebase/227.md`](../codebase/227.md) for the full breakdown.

The `RecordingConnectionStateSource` test double introduced in #201 lives at the bottom of `ThreadViewModelTest.kt` alongside `fixedRepo`:

```kotlin
private class RecordingConnectionStateSource : ConnectionStateSource {
    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    var retryCallCount: Int = 0
        private set
    override fun observe(): Flow<ConnectionState> = state.asStateFlow()
    override suspend fun retry() { retryCallCount++ }
}
```

It cannot reuse `FakeConnectionStateSource` because the fake's `retry()` is a no-op — an assertion-of-effect through the fake would have nothing to observe. The recording double is deliberate test-local fixture; pre-staging this as a public-ish helper in `data/repository/` would be premature (no other consumer needs it).

Instrumented `PingReplyTest` and `SessionBoundaryVisibilityTest` render the real
`ThreadScreen` with hoisted state and exercise the same assertion helpers as
`InteractiveStreamE2ETest`. They cover queue replacement without substring-count
growth and revealing a delimiter after a tall finalized wrap-up. Both live beside
`QueuedBacklogTest`, outside the routine UI gate's excluded `e2e` package. See
[Compose evidence](development-verification.md#compose-evidence) for matcher scope
and the distinction between semantic existence and display.

`PingReplyAssertions.kt`'s `pingReplyMatcher()` anchors on content, not placement
(changed by [#782](../codebase/782.md)). #694 originally wrote it as `hasText("ping")
and hasAnyAncestor(hasScrollAction())`, reasoning that the app bar title and the
foot-of-list `QueuedBacklog` section both sat outside `ThreadScreen`'s scrollable
message list, so "has a scroll ancestor" was enough to exclude them. #782 folded the
queued row inline into the same `LazyColumn` (see
[Queued backlog rendering](queued-backlog-section.md)), so a queued entry whose text
is exactly `"ping"` gained a scroll ancestor and the matcher started matching it,
reddening `PingReplyTest` on the pre-verifier UI gate. The matcher now anchors on
`MessageContainer`'s `MESSAGE_BUBBLE_TEST_TAG` instead —
`hasText("ping", ignoreCase = true) and hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG))`
— which states #694's actual intent (reply detection is independent of queued text) as
content rather than placement: a queued row renders its own `Surface` and never a
message bubble, and neither does the app bar title, so both are excluded without a
scroll clause, and an assistant reply still matches because both roles reach the tag
through `MessageContainer`. `awaitDisplayedPingReply` shares the matcher, so the same
fix also covers its three `InteractiveStreamE2ETest` call sites, each a singular
`onNode(...)` that would otherwise throw on multiple matches once a queued `"ping"`
reached them.

**General lesson for the next structural change to `ThreadScreen`'s list:** a
blast-radius search keyed on the symbols a change touches (`QueuedBacklog`,
`foldQueuedRows`, `ThreadUiState`) will not surface a test helper whose matcher
encodes a *structural position* rather than a symbol reference — `pingReplyMatcher`
read as "text is not decoration" but was actually written as "text is inside the
scrollable list," and nothing in #782's diff mentioned it. Search test helpers for
placement-coded matchers (`hasAnyAncestor(hasScrollAction())`, sibling-index lookups,
tree-order assumptions under `reverseLayout`) whenever a change moves content between
regions of the screen, not just when it changes the content's type.
