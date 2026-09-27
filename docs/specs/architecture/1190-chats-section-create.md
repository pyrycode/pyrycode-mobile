# #1190 — Create chats from each host's Chats section

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `treeHost`, `ChannelListScreen`, `ChannelListEvent` — section assembly, modal binding and host-qualified events.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `TreeHostRow`, `TreeHostSectionRow`, `TreeRowControl` — separate 48dp controls and bounded host labels.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `hostState`, `submitCreateChannel`, `sendHostDiscussion` — retained modal state, repository resolution and navigation.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `ChannelListEvent` dispatch — route from screen events to the list ViewModel.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileGateModal` — existing Create/Cancel footer style with caller-owned state.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationCommands.kt` → `createDiscussion` — null `cwd` request path; wire contract is `../pyrycode/docs/protocol-mobile.md` § `create_conversation`.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` → host-qualified tree assertions — multi-host Compose proof.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` → `Repo` fixture — create, error, cancellation and navigation proof.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `createChat`, folder and label scenarios — live daemon paths to migrate.
- `docs/knowledge/features/channel-list-screen-tree-and-controls.md` § Add controls and `docs/knowledge/features/mobile-modal.md` — existing control and modal conventions; documentation stage updates the former's historical host-add description.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

The dark sidebar nests Channels and Chats beneath each host, with a trailing primary plus on the Channels header and separate chevrons for folding. The Chats header gains the matching primary plus in its own 48dp target; the confirmation uses the app's `MobileGateModal` M3 surface, type and buttons.

## Context

The host-row plus currently creates immediately using a saved per-host folder, while its long press opens Add workspace. Chats creation must move to the Chats section, require confirmation and omit `cwd` so the daemon chooses its own default. The open dialog must retain its host identity across a connection change. No new wire type or repository API is needed.

The one-ticket size check is 5 production Kotlin files, about 1,000 total written lines, no more than 2 new exported declarations, 4 acceptance criteria and 3 error branches. Removing the old `TreeHostRow` callbacks updates 15 callers (11 screen-test fixtures, 3 previews and the list), above the 10-site ceiling. A separate caller-migration ticket would introduce a wrapper used only by the sibling removal ticket. The floor rule therefore keeps this one product change together despite the measured 15-site overage. The in-flight branch check found no overlap with planned files.

## Design

- `TreeHostSectionRow` takes a section-specific add label and tag when an add action exists. Its Chats plus emits `TreeHostChatAddTapped(serverId)` without toggling the fold, including when `host.chats` is empty. The Channels plus remains unchanged. `TreeHostRow` loses its plus and its two callbacks; edit, connection and fold controls stay.
- `HostChannelListState.createChat: CreateChatState?` holds the exact `serverId`, bounded host display name, `saving` and `failed`. `ChannelListEvent` has Chats plus, Create and Cancel actions. `ChannelListScreen` binds this state to a fieldless `CreateChatModal` using `MobileGateModal`, with static generic failure text, Create/Cancel buttons and connectivity-based submit enablement.
- `ChannelListViewModel.openCreateChat(serverId)` validates the host against its current snapshots before setting the dialog. `submitCreateChat()` resolves the repository for the held host, sends exactly one `createDiscussion(null)`, and on a confirmed reply clears the matching dialog, highlights and navigates to `HostConversationTarget(serverId, id)`. An unavailable repository or write failure leaves the dialog open with a static retryable error. Cancel clears the state and sends nothing. In-flight result publication uses compare-and-set so a stale response cannot close a later dialog.
- Existing Add workspace and saved per-host default capabilities remain for other consumers; no stored preference, folder model or wire contract is changed.

## State and concurrency model

`createChat` is a `MutableStateFlow` in `ChannelListViewModel` and joins `hostState` independently of host snapshots, so disconnect and reconnect cannot clear its target or error. A `viewModelScope` job owns the single create request and ends on success, failure or ViewModel cancellation. The existing host navigation channel carries the qualified target. The dialog disables Create while sending or disconnected; the ViewModel's `saving` guard prevents duplicate writes independently of UI state.

## Error handling

Unknown host: refuse the open and emit a content-free static log code. Unavailable repository or create exception: leave the dialog and target intact, clear `saving`, set `failed` and log only a static event. Cancellation is rethrown. The UI resolves `failed` to a resource string and never renders the daemon's exception. No folder path or host name enters a log.

## Testing strategy

- JVM ViewModel tests: opening does not create; Cancel creates nothing; confirming uses `null` `cwd` and the clicked host despite saved defaults and colliding conversation ids; duplicate Create is ignored; failure and disconnect retain the dialog and retry works; success selects and navigates with the host id.
- Robolectric Compose tests: each Chats section has a named 48dp control even when empty, independent of fold and host-row controls; tapping host B's plus with colliding ids opens a host-B dialog, and Cancel/Create dispatch only to B.
- Update affected `ConversationTreeRowsTest` fixtures for the removed host callbacks. Run focused JVM and Compose classes.
- Update `InteractiveStreamE2ETest`'s `createChat` helper and promote scenario to tap the Chats plus then Create. The folder-use scenario creates a chat first, uses the thread picker to make and select a folder, then proves a live turn works there. Retire the host-row-only workspace scenario. In the peer-label scenario move A's new chat to the chosen folder before the peer relabels it. The dispatcher owns the real-Claude suite after verifier; focused device proof covers the migrated scenario when feasible.

## Open questions

- Resolve the thread picker selectors and exact folder-change confirmation sequence from the existing Compose and e2e helpers during implementation.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/channel-list-screen-tree-and-controls.md` § Add controls and the `docs/e2e-interactive-stream.md` coverage list to describe the Chats-section Create dialog, removal of the host-row plus and migrated live scenarios. The issue names no other reference-documentation requirement.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] The host display name comes from a daemon snapshot. `openCreateChat` bounds it before storage and `CreateChatModal` renders it only through `Text`; the stable server id, never display text, selects the repository.
- [Tokens and secrets] No credential is created, read or logged. Existing paired-host storage and Noise transport are unchanged.
- [File and storage] `createDiscussion(null)` delegates default-folder choice to the daemon and the client performs no path operation or settings write. Existing folder storage is unchanged.
- [Android attack surface] The dialog adds no intent, deep link, provider, WebView or exported component. The existing modal window handles local input.
- [Cryptography] No primitive, key or nonce changes; the existing Noise session carries the request.
- [Network and I/O] The existing request path and protocol frame limits remain; no URL, timeout or reconnect policy changes. The daemon's `create_conversation` contract defines null `cwd`.
- [Errors and logs] The UI shows only a static resource error. Structured lifecycle logs carry event and static code only, never daemon text, host name, folder path or exception message.
- [Concurrency] The `saving` guard prevents repeated sends, compare-and-set fences a response after dismissal/reopen, and `viewModelScope` cancels on ViewModel exit. A disconnect leaves the state but disables Create until reconnection.
- [Threat model] A malicious relay cannot read the existing Noise-sealed request. A hostile daemon may fail or delay a create; failure stays retryable and a delayed completion cannot retarget another host. Existing token-theft and screen-overlay risks are outside this UI change and remain with the transport and pairing owners.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-27
