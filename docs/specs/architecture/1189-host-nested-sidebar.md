# 1189 — Host-nested sidebar

## Files read

- `docs/knowledge/features/channel-list-screen.md` and `channel-list-screen-tree-and-controls.md` → `ConversationTree`, row targets and 48dp control pattern; the current tree intentionally repeats hosts by tier.
- `docs/knowledge/features/conversation-repository.md` and `docs/knowledge/features/development-verification.md` → repository and focused Compose/device proof conventions.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ConversationTree`, `treeSection`, `treeItemKey`, modal binding and events.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `TreeFoldKey`, `HostChannelListState`, `CreateChannelState`, `openCreateChannel`, `submitCreateChannel` and host snapshots.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `TreeHostRow`, `TreeWorkspaceRow`, `TreeConversationRow`, `FoldableTreeRow` and 48dp controls.
- `app/src/main/java/de/pyryco/mobile/ui/components/CreateChannelModal.kt` → modal fields keyed by target, with no folder picker.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `ChannelListEvent` routing.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`, `ConversationCommands.kt`, `RemoteConversationRepository.kt`, `StableConversationRepository.kt`, `FakeConversationRepository.kt` → channel-create contract, wire payload construction and implementations.
- `app/src/main/java/de/pyryco/mobile/data/network/CreateConversationPayloadDto.kt` → optional `cwd` already supported.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` and `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` → tree and retry proof patterns.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → two live scenarios that reference workspace rows.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md` → `create_conversation` with omitted `cwd` uses the daemon default; this remains the wire authority.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

The sidebar is one vertically scrolling column with 20dp gutters and 16dp between host containers. Each host contains indented Channels and Chats folder headings and direct conversation rows; the frame uses M3 title small and body small roles with surface, on-surface and primary-container roles, plus status dots, chevrons and a Channels plus. The Apps sample is deferred; phone controls retain persistent 48dp targets rather than the frame's hover-only 16dp controls.

## Context

The current global-tier tree repeats every host and groups conversations by workspace, which changes source order and hides Create channel when no channel workspace row exists. This change presents a daemon workspace as one host container while preserving conversation ids and `cwd` in the data layer. The nullable create contract and its only UI caller belong together: separating them would create an unconsumed contract slice. No in-flight branch overlaps the planned files after the branch check.

## Design

- `ConversationTree` emits hosts once, in `hostState.hosts` order, through one `LazyColumn`. Each expanded host emits fixed Channels then Chats headings, whether empty or offline. Each expanded heading draws `entry.host.channels` or `.chats` directly, preserving source order and using the row's own `serverId` and conversation id for selection, attention, edit and promotion. Workspace grouping helpers remain for other consumers; the tree stops drawing `TreeWorkspaceRow`.
- `TreeFoldKey` identifies `(serverId, node kind)` for Host, Channels and Chats; the ViewModel's `collapsedKeys` set remains independent of snapshots and screen navigation. Folding removes descendants from composition without changing `lastOpenedTarget`. The host-row chat add action remains.
- Add a section row in `ConversationTreeRows.kt` using `FoldableTreeRow` and the existing 48dp `TreeRowControl`. Its folder glyph is open or closed with expansion; its chevron is down or right. Channels alone has a persistent plus named for its host. The section row is indented 4dp and conversation rows 12dp from the 20dp content edge. Host spacing remains 16dp and the existing toolbar remains. Host, section and conversation keys include node kind and host id, not displayed labels or `cwd`.
- Replace `TreeWorkspaceAddTapped(serverId, cwd)` with a host-qualified Channels add event. `openCreateChannel(serverId)` validates the host from the current snapshot, including an empty Channels list. `CreateChannelState.cwd` and the modal's target `cwd` become nullable; null is the section's daemon-default target and the modal still has no folder picker. `submitCreateChannel` keeps its existing create/prompt retry state machine and passes null to the repository. The repository `createChannel(name, workspace: String?)` and its forwarding implementations allow null; `ConversationCommands` sends the existing optional DTO field, so serialization omits `cwd`. The fake uses its existing default-workspace convention for null.
- Keep host reconnect, re-pair, update and edit controls, row attention, archive and prompt editing as they are. The tree consumes their existing events and target types. Any obsolete workspace-row event routing is removed.

## State and concurrency model

The existing `hostState` `StateFlow` joins snapshots, fold state, selected target and modal state in `viewModelScope`. There is no new job or dispatcher. `collapsedKeys` stays hot and in-memory for the ViewModel lifetime, so reconnect and thread navigation preserve it. `submitCreateChannel` remains a `viewModelScope` job, cancelling on ViewModel teardown; its compare-and-set transitions prevent a dismissed modal from reopening on a late result. The connection driver's background close remains owned by the existing supervisor.

## Error handling

Unknown hosts and invalid names are rejected before creating. Disconnection, daemon errors and malformed replies retain the modal's static create-failure state; a prompt-write failure retains the created id, so retry writes only the prompt. Cancellation propagates. No daemon error text, prompt, name or path enters logs or an accessibility error. A section fold never alters selection or cached rows.

## Testing strategy

- Shared Robolectric Compose screen test: multi-host source ordering, one host row each, section folds, selection with colliding ids, host-qualified edit/promotion, reconnect controls, empty Channels plus, 48dp non-overlapping actions, final-row scroll and light/dark semantics. Update obsolete workspace-row assertions rather than retaining dead tree expectations.
- ViewModel `runTest`: independent host/section fold keys across snapshots and reconnect, valid empty-host create with null `cwd`, wrong-host rejection, cancel, create failure and prompt retry. Repository unit test: a null channel workspace omits `cwd` on the wire while the daemon-confirmed folder is retained.
- Update `InteractiveStreamE2ETest#interactiveTurn_createEditArchiveChannel_readsPromptBack` to start from an empty Channels section, use its plus and prove the returned channel uses the daemon default. Remove the deleted tree-row label assertion in `interactiveTurn_peerWorkspaceLabel_reachesEveryOpenSurfacePerHost`; keep its thread, Settings and cross-host checks. The dispatcher owns the labelled live run after verifier.
- Focused `testDebugUnitTest` for touched unit and shared screen classes, `lint`, `assembleDebug`, `compileDebugAndroidTestKotlin` and Spotless. A focused managed-device screen class is a tiebreaker only if Robolectric fails unexpectedly.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/channel-list-screen.md` § Conversation tree and § Preview for the host-first tree; update `docs/knowledge/features/channel-list-screen-tree-and-controls.md` § Conversation tree, § Add controls and § Workspace row create-channel control for section folds and daemon-default Create channel; update `docs/e2e-interactive-stream.md` § Live mode and § Verification status for the revised create-channel, workspace-label and folder-settings scenarios after the dispatcher supplies live evidence. No shared documentation is edited here.

## Open questions

- Confirm the serializer omits nullable `cwd` rather than emitting JSON null; resolve in the repository unit test before implementation.
- Confirm the one-consumer floor applies to the nullable repository chain (10 production files against the usual 8-file ceiling); the tree cannot offer daemon-default creation without that chain. Recount before committing.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] `ConversationCommands.createChannel` remains the sole request encoder. The existing decoder returns the daemon-confirmed `Conversation`; daemon names remain bounded by `TreeConversationRow` and drawn as text. No new inbound verb is added.
- [Tokens and cryptography] No token, key, pairing or Noise path changes. The existing authenticated session owns this request.
- [Storage and Android surface] No file, intent, provider, deep link, WebView, backup or pending-intent path changes. `cwd` stays daemon-owned metadata, never a local filesystem operation.
- [Network and I/O] The existing relay transport, frame limit, timeouts and reconnect supervisor remain. An omitted `cwd` asks the daemon to choose its default, per the protocol document.
- [Logs and errors] Static event codes and error flags continue to carry outcomes. The name, `cwd`, system prompt, daemon reply and host id are excluded from new log lines and modal errors.
- [Concurrency] The existing `viewModelScope` and compare-and-set creation sequence protect late responses and prompt retries. Fold changes use atomic `getAndUpdate` and have no suspension point.
- [Threat alignment] A hostile relay can delay or drop the existing encrypted request, resulting in the existing retry state. UI text is bounded and remains text. Screenshot, accessibility-service and keyboard exposure remain the existing product threat model and are outside this sidebar ticket.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-27

## Revisions

- During implementation, the existing `InteractiveStreamE2ETest#interactiveTurn_addRenameArchiveWorkspace_roundTripsThroughTheHost` also proved a deleted workspace-row pencil. It now exercises the same folder rename/archive repository round trip and Archive restore while checking the daemon-owned label; its UI pencil assertions cannot remain after folder rows disappear.
- The repository test confirmed `CreateConversationPayloadDto` omits a null `cwd` on the wire and retains the daemon-confirmed folder. The nullable contract is a single consumer chain spanning 10 production Kotlin files; the section action is its only new consumer, so the one-consumer floor keeps this together.
- The verifier found that `ChannelListColoursTest` still expected the removed global-tier divider. The host-first tree keeps only the toolbar rule, so its light and dark visual assertions now require exactly one full-width rule while retaining the color, placement, gutter and selected-row checks.
