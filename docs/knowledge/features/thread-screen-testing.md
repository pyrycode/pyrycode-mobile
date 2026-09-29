# Thread screen — testing

Split out of [Thread screen](thread-screen.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Thread screen](thread-screen.md); see that document for what it does, its edge cases and its links.

## Testing

The [frame captures](../../../app/src/androidTest/assets/frame-1206/README.txt) compare live Figma
`16:8` and `568:3139` renders inspected on 2026-09-29 with real 412 × 892 emulator captures and
labelled overlays. Header paths, inset rule, message-region origin, radial canvas, input-area
position and task-pill anchor match the reference. Figma supplies no matching empty, compact,
enlarged-text, keyboard-open or menu-open state; the companion captures check those states for
clipping and reachability. The `533:1946` container metadata reports light tokens despite the dark
renders, so the dark render and shared theme roles govern this frame. Fixture text, attachments,
notices and footer controls differ from the art and retain their own visual owners.

`ThreadFrameTest` uses native graphics to check visible geometry and real pointer taps at the
send/field/footer boundary. Layout bounds alone missed Compose's automatic touch-target expansion:
an earlier footer placement looked clear by measurement yet intercepted input and send taps.
`ThreadComposerFooter` keeps 32 dp touch boxes in this frame, while its content sits 12 dp higher
and the reported band is 20 dp; its default outside the thread frame remains 32 dp. The real-IME
`MainActivityInsetsDeviceTest` checks the footer's unmerged visible icon separately from its
clickable parent, which extends below it, and checks scroll position and draft across keyboard
reopening at 412 × 892 and 360 × 640. Forced-size Compose captures constrain the content view,
whereas popup windows use the physical emulator window; full-device menu captures preserve that
distinction.

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
20–25. **Deleted whole by [#807](../codebase/807.md), not rewritten:** `state_initialValue_includesDefaultModelEffortAndYolo`, `state_postSubscription_emitsDefaultModelEffortAndYolo`, `selectedModel_followsAppPreferencesDefault`, `selectedModel_reemitsWhenAppPreferencesDefaultChanges`, `onModelSelected_overridesPerConversationWithoutMutatingPreferences`, `onModelSelected_overrideWinsOverSubsequentDefaultChange` (plus their `selectedEffort` twins). These pinned exactly the sourcing #807 retires — `selectedModel: Model` / `selectedEffort: Effort` following `AppPreferences.defaultModel` / `defaultEffort` with an in-memory per-conversation override — and the new design has nothing analogous to assert: there is no default to override any more, only a daemon reading. See [§ `ThreadViewModel` re-sourcing (#807)](#threadviewmodel-re-sourcing-807) below for the replacement coverage.

Two tests added in [#722](https://github.com/pyrycode/pyrycode-mobile/issues/722), continuing the numbering above:

26. **`state_workspaceLabel_prefersConversationLabel_overCwdBasename`** (#722) — `fixedRepo` with one discussion at `cwd = "pyry-workspace/my-app"` and `workspaceLabel = "Design system"`; asserts `vm.state.value.workspaceLabel == "Design system"`. Pins the label-first rule at the ViewModel boundary — items 13-15's cwd-only fallback tests are unaffected, since they pass `workspaceLabel = null`.
27. **`state_workspaceLabel_followsLiveRenameAndClear_onOneSubscription`** (#722) — a `MutableStateFlow<List<Conversation>>`-backed `fixedRepo` overload (`fixedRepo(conversations: Flow<List<Conversation>>)`, delegated to by the existing `fixedRepo(List<Conversation>)`) emits the same conversation unlabelled, then labelled (`"Design system"`), then cleared (`workspaceLabel = null`) again; `vm.state.value.workspaceLabel` is asserted after each emission (`"my-app"` → `"Design system"` → `"my-app"`). An `onStart { subscriptions++ }` counter on the flow asserts `subscriptions == 1` across all three emissions, and `collector.isActive` is asserted true throughout — proving the open thread updates via re-emission of the *same* `observeConversations(All)` subscription, without reopening or resubscribing. Drives AC2 through the bound repository's conversation emissions, per the ticket's requirement.

The pure rule itself — the label-first ordering, the unconditional win over the scratch sentinel, verbatim (non-normalising) rendering including Unicode and markup-looking text, and the `MAX_WORKSPACE_LABEL_CHARS` clamp on a conformant vs. an oversized label — is exhaustively covered by a sibling file added in #722: `app/src/test/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayNameTest.kt`, eight JUnit 4 tests against the pure `workspaceDisplayName(cwd, label)` function with no VM, no `runTest`, no coroutines. See [`workspace-chip.md`](workspace-chip.md#workspacelabel-derivation) for the rule and its behaviour table.

The `fixedRepo(conversations)` helper is an anonymous `object : ConversationRepository { … }` with `TODO("not used")` overrides plus a `flowOf(conversations)`-backed `observeConversations`. It's kept local rather than extracted — each test's bespoke conversation shape would force a builder-shaped helper that doesn't pay for itself yet. Since [#722](https://github.com/pyrycode/pyrycode-mobile/issues/722) it has a second overload, `fixedRepo(conversations: Flow<List<Conversation>>)`, that the original `fixedRepo(List<Conversation>)` now delegates to (`= fixedRepo(flowOf(conversations))`) — a one-line addition rather than a second 40-line stub, used by test 27 above for its live-emitting double.

### `ThreadViewModel` re-sourcing (#807)

[#807](../codebase/807.md) added a `runConfig_*` / `on{Model,Effort,Yolo}Selected_*` / `sessionSettings_*` group to `ThreadViewModelTest.kt` (~25 tests) covering the sourcing, the write round trip and the per-conversation scoping this ticket's AC #5 requires, replacing the six deleted tests from items 20-25 above. Representative cases, by what each pins:

- **Sourcing.** `state_initialValue_isUnknownRunConfigNotADeviceDefault` and `runConfig_withoutAnyReading_rendersUnknownAndOffersNothing` — no reading yet renders "unknown", never a `Model` / `Effort` default. `runConfig_labelsComeFromTheReadingAndThePublishedRow` and `runConfig_readingWithNoOverride_readsAsInheritedDefaultNotUnknown` — the three-state label rule (`unknown` / `default` / published label). `runConfig_savedValueTheMenuDidNotPublish_rendersTheValueItself` — a saved value absent from the menu still renders (made inert), not blanked. `runConfig_choicesAreThePublishedRowsInWireOrder`, `runConfig_effortLevelsComeFromTheSelectedRow`, `runConfig_unsetSavedEffortStillOffersTheRowsLevels` — the choice-list contract AC #2 states. `runConfig_droppedModelsIsCarriedAsReportedAndNeverRecomputed` and `runConfig_oversizedMenuIsCappedIntoHiddenChoicesLeavingDroppedModelsUntouched` — the render-cap / producer-cut distinction.
- **Trust boundary.** `runConfig_claudeAuthoredTextIsRenderedInertWhileTheWriteArgumentStaysVerbatim` and `runConfig_savedValueWithControlCharacters_isRenderedInert` — the `String.inert()` boundary (control characters dropped, length-bounded) applied to every rendered field while `.value` stays byte-identical.
- **Scoping.** `runConfig_isScopedToItsOwnConversation` — AC #4's "no carried-over selection… a value appears only once that context reports one."
- **Write round trip.** `onModelSelected_whenConnected_sendsOnlyModelFieldToTheSettingsReportedSession` (and its `onEffortSelected` / `onYoloToggled` twins) — the changed field, addressed to `SessionSettings.sessionId`, never `Conversation.currentSessionId`. `onModelSelected_sameAsCurrentValue_doesNotSend`, `onModelSelected_withEmptySessionId_isReadOnlyAndSendsNothing`, `onModelSelected_whileAWriteIsPending_doesNotSendASecondTime` — the three no-op guards. `onModelSelected_settledWrite_staysPendingUntilAFreshReadingLands` — a settled write asks for a fresh reading and the pending survives until it lands, not until the ack. `onModelSelected_whenServerError_restoresConfirmedStateAndSurfacesErrorWithoutLeakingMessage` and `onYoloToggled_whenDisconnected_revertsYoloAndSurfacesError` — revert-on-failure without leaking `RelayErrorException.message`. `sessionSettings_scopeCancellationMidCall_isInertWithoutSurfacing` — screen-exit teardown mid-send neither reverts nor signals.

See the test file directly for the full list — it is not reproduced here. Two `onYoloToggled_*` tests (`_initialValueIsFalseRegardlessOfAppPreferencesDefault`, and the preferences half of `_flipsStateAndDoesNotMutatePreferences`) still construct an unused `AppPreferences` fixture and assert against it; `makeVm` no longer takes that parameter, so the assertion holds by construction rather than by exercising anything — a verifier NIT on #807's review, not fixed as of this writing.

Sibling test files added in [#843](https://github.com/pyrycode/pyrycode-mobile/issues/843), the Re-pair
action: `app/src/test/java/de/pyryco/mobile/di/PairingRejectedTest.kt` (own host rejected → `true`,
another host rejected → `false`, missing host → `false`, a re-pair that replaces the registry entry with
a connected status → `false`, own host moving rejected → connected → `false`) and
`app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelRePairTest.kt` (default
`rePairAvailable == false`; follows an injected `pairingRejected` flow true → false). The second file is
**deliberately not folded into `ThreadViewModelTest.kt`** above — #816 was in flight against that same
file when this ticket was built, and a new file sidesteps the merge entirely rather than relying on the
two tickets' insertion points staying disjoint. The Compose coverage is a third file,
`app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenRePairTest.kt`
(moved from `androidTest` by the 2026-09-23 shared-test migration): with `showRePair` and `Offline`, the
pairing notice is displayed and the "Offline — tap to retry" banner text does not exist; tapping it invokes
`onRePair`; without `showRePair`, the banner shows and the notice does not exist. Assertions find the
notice **by its `R.string.thread_re_pair` text**, not by composable identity, so
[#1002](https://github.com/pyrycode/pyrycode-mobile/issues/1002) moving that notice from a status-row
button to a [Top overlay pill](thread-top-overlay.md#the-pairing-pill) needed no change to this file.

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

Tests added in [#789](https://github.com/pyrycode/pyrycode-mobile/issues/789), grouped under the file's `// ---- #789: per-chat composer drafts ----` section comment: `makeVm` gained a `draftStore: ComposerDraftStore = ComposerDraftStore()` parameter, defaulted so every pre-existing case is unaffected, and a `threadHandle(serverId, conversationId)` helper builds a two-argument `SavedStateHandle` for cases that need a real pair. Coverage: `onDraftChange` writes only its own pair; a second VM built against the same store restores the first VM's text (AC #1's navigate-away-and-back, proven by construction rather than navigation); the same conversation id under two hosts holds two independent drafts; an accepted send clears the entry and drops the now-empty host bucket; a refused send leaves the draft — one case per failure type the guard catches, driven through `ThrowingConversationRepository`; a `TypingDuringSendRepository` test double runs a callback from inside the fake's suspending `sendMessage` to prove text typed while a send is in flight survives that send's completion; a blank send never touches the store; and `ThreadEvent.NewSession` leaves the draft untouched (AC #3). New file `ComposerDraftStoreTest.kt` covers the store directly: exact round-trip including surrounding whitespace, `""` removing an entry and emptying its host bucket, a whitespace-only draft retained rather than treated as empty, two hosts under one conversation id staying independent, and an unknown pair reading as `""`.

**`ThrowingConversationRepository` does not throw for every reachable conversation id — only for the failure it was constructed with, and only from the overrides it actually implements.** The #789 refusal cases route through `sendMessage(conversationId, text)`, but `state` still assembles from `observeConversations`/`observeMessages`, which the seeded `FakeConversationRepository` backs by default. Pointing a refusal test's `conversationId` at an unseeded id throws `IllegalArgumentException` ("Unknown conversation") out of the *state* machinery, not the guarded `sendMessage` call under test — a different failure than the one the assertion means to pin, and one `launchGuardedRepoCall` does not catch. Fixture conversation ids for any test that reaches a repository's other reads need to be ones the fake actually seeds (`DRAFT_CONV = "seed-channel-personal"` here), not an arbitrary string.

Sibling test file added in [#1043](https://github.com/pyrycode/pyrycode-mobile/issues/1043):
`app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/TaskCountPillTest.kt`
(`@GraphicsMode(GraphicsMode.Mode.NATIVE)` — see [Compose evidence](development-verification.md#compose-evidence)
for why an exact-width/position assertion needs real fonts rather than Robolectric's legacy renderer, which
measured the pill's label at almost no width and made it wrap). Five `@Test`s host the real `ThreadScreen`:
the pill beside a live `ThinkingIndicator` reading, positioned to that reading's right; the pill alone,
right-edge-aligned on `rootWidth - ComposerGutter`; the singular "1 task running" copy (and that "1 tasks
running" does not exist); the zero-count case, which measures the newest message row's **bottom** edge
rather than the input field's top — the composer is a bottom-anchored `bottomBar`, so only the status band's
own height moves that edge — at zero, again after the pill raises it by the exact 32dp of its own 24dp plus
the composer column's 8dp gap, and again after the count returns to zero, asserting the last measurement
equals the first; and a tap opening `BackgroundTaskPanel` with the roster's task visible. See [Thread screen
— how it works, overlays, retry and the app bar §
Thinking-indicator placement](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643)
for the composable this test pins.

**General lesson for the next structural change to `ThreadScreen`'s list:** a
blast-radius search keyed on the symbols a change touches (`QueuedBacklog`,
`foldQueuedRows`, `ThreadUiState`) will not surface a test helper whose matcher
encodes a *structural position* rather than a symbol reference — `pingReplyMatcher`
read as "text is not decoration" but was actually written as "text is inside the
scrollable list," and nothing in #782's diff mentioned it. Search test helpers for
placement-coded matchers (`hasAnyAncestor(hasScrollAction())`, sibling-index lookups,
tree-order assumptions under `reverseLayout`) whenever a change moves content between
regions of the screen, not just when it changes the content's type.
