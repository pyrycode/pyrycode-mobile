# #2215 — Render and operate shared pins above all hosts

## Files read

- `ui/conversations/list/ChannelListScreen.kt` — `ConversationTree` (sorts each host's sections with `conversationLabelComparator`, one `LazyColumn`), `treeHost` (host row, Channels/Chats section rows, conversation rows keyed by `treeItemKey("conversation", section, serverId, id)`, section plus null while `!connected || selectionOnly`), `treeItemKey` (length-prefixed parts), `sidebarRuleColor` (the toolbar rule: `inversePrimary` at 60% on the dark surface, `outlineVariant` on light), `ChannelListEvent`.
- `ui/conversations/components/ConversationTreeRows.kt` — `TreeConversationRow` (24dp band, `selectable` leading region, `ConversationStatusDot`, bodySmall name clamped by `boundedRowText`), `TreeRowControl` (24dp-wide slot, primary-tinted glyph, own click node), `RelayLinkStatus.isDisconnected` (the host row's error-accent rule), `boundedTagId`, `TreeHostSectionRow` (16dp plus glyph in the same column as the host pen).
- `ui/conversations/list/ChannelListViewModel.kt` — `HostChannelListState.isHostConnected`, `isHostLive`, `hostState` combine, `reconnectHost` and `createChat` (host-qualified dispatch, content-free `RelayLog` events, guard-then-launch in `viewModelScope`).
- `di/HostConversationSource.kt` — `HostConversationSnapshot.channels` holds only promoted, unarchived rows (`withRows`), for cached and live lists alike; `repositoryFor`.
- `data/model/Conversation.kt` — `pinnedAt: Instant?` (#2213). `data/repository/ConversationRepository.kt` — `setPinned(conversationId, pinned)` (#2214): nothing changes while pending; on success `observeConversations` shows the host's confirmed `pinnedAt`; failures throw and change nothing.
- `MainActivity.kt` — the `ChannelListEvent` dispatch `when`.
- `res/drawable/ic_tree_edit.xml`, `ic_tree_server.xml` — Font Awesome Free 7 solid paths cropped to their bounds and scaled to the drawn dp size; the new assets follow the same recipe.
- Tests: `HostChannelListViewModelTest` (`Fixture` with colliding hosts `Host`/`host`, `Repo.rows`, `setMuted` recorder pattern), `ChannelListScreenTest` (`entry`, `setTree`, `aDisconnectedHostDrawsNoSectionPlusAndTheyReturnOnReconnect`, `assertTrailingControlsFormOneColumn`), `ConversationTreeRowsTest` (`treeRows_matchFigmaBandHeightsAndKeepActionRegionsSeparate`, `countPixels`, `conversationRow_noAttentionState_drawsTheErrorFill`).
- Overview `channel-list-screen-tree-and-controls.md` (§ host row reconnect control, #1336): an absent control must be asserted after scrolling its owning row into view, or a lazily unrendered row passes vacuously.

No in-flight branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=869-13163

One Pinned group above every host: a "Pinned" heading in `labelLarge`/`onSurfaceVariant`, the pinned channel rows (24dp conversation band, status dot, "channel name · host name" in `bodySmall` with the suffix `onSurfaceVariant`), then a 1dp `inversePrimary` rule before the first host. Every unpinned channel row of a connected host ends in a primary-tinted 16dp thumbtack (Icon Edgeless set 399:918, Pin 872:9638 / Unpin 872:9649, Font Awesome Free 7 `thumbtack` / `thumbtack-slash`) in the column of the host pen and section plus. A pinned row whose host the host row accents as disconnected draws name, suffix and dot in `error` at 60% alpha; the ticket's explicit override disables that row's unpin control, where Figma draws it at full strength.

The Figma connector was not authenticated in this run, so the values above come from the ticket, which recorded them from the frame and screenshot during refinement. The two glyphs are the Font Awesome Free 7.3.1 solid `thumbtack` and `thumbtack-slash` paths, cropped and scaled by the same recipe as `ic_tree_edit` and `ic_tree_server` (verified against FA 7 `pen` and `server`). Spacing the ticket does not state reuses existing tree tokens: the heading uses the 28dp band of `TreeSectionHeader`, rows sit 4dp apart (`TreeConversationGap`) at the list's start edge, and the rule has 16dp (`TreeHostGap`) above and below. The rule reuses `sidebarRuleColor`, so it matches the toolbar rule's `inversePrimary` on the dark surface and its existing light-scheme substitution. The PR records these choices as the parts Figma could not confirm.

## Context

#2213 stores the host-confirmed `Conversation.pinnedAt` (live and cached); #2214 added `setPinned`. This slice is the first consumer: it draws the shared Pinned group and the pin/unpin controls. Pins belong to the host, so the list draws only confirmed state and never moves a row optimistically. No decision record is needed: the change follows the existing host-qualified control pattern.

## Design

### View model (`ChannelListViewModel.kt`)

- `data class PinnedChannel(val entry: HostChannelListEntry, val conversation: Conversation)` with `val target: HostConversationTarget`. It carries its own host's entry, so name, connection and attention come from the owning host.
- `internal fun pinnedChannels(hosts: List<HostChannelListEntry>): List<PinnedChannel>`: every `entry.host.channels` row with non-null `pinnedAt`, sorted by `pinnedAt` ascending, then `serverId`, then `conversation.id` (`String.compareTo`, UTF-16 code units). It is a pure function, so it is independent of host enumeration order and update arrival. Chats are never included: discussions get no pin controls, and archived rows are already outside `channels`.
- `fun setPinned(target: HostConversationTarget, pinned: Boolean)`. Guards: the target's host must be in the current snapshot and live (`isHostLive`), the conversation must be an active channel in that host's `channels`, and `hostSource.repositoryFor(serverId)` must be non-null. Each refusal logs `event=channel_pin_rejected code=<disconnected|unknown_channel|unavailable>` and sends nothing. Otherwise it launches in `viewModelScope`: `repository.setPinned(conversationId, pinned)`. A thrown non-cancellation error logs `event=channel_pin_failed`; success logs `event=channel_pin_confirmed`. Nothing in the view model's state changes on any path. Placement follows only the host's confirmed record, which arrives through the host's conversation stream.

### Events (`ChannelListScreen.kt`, `MainActivity.kt`)

- `ChannelListEvent.TreePinToggled(target: HostConversationTarget, pinned: Boolean)`: the requested state for the row's own host. `MainActivity` maps it to `vm.setPinned(event.target, event.pinned)`.

### Rows (`ConversationTreeRows.kt`)

- `enum class TreePinControl { None, Pin, Unpin, UnpinDisabled }`, which is stable for Compose. `None` draws nothing.
- `TreeConversationRow` gains trailing parameters, all defaulted so existing callers are unchanged: `hostName: String? = null` (suffix), `disconnected: Boolean = false`, `pinControl: TreePinControl = TreePinControl.None`, `onPinToggled: () -> Unit = {}`, `pinModifier: Modifier = Modifier` (the control's test handle).
  - Text: one `Text` holding an `AnnotatedString` of name and, when `hostName != null`, `" · " + host`. The suffix span is `onSurfaceVariant`; when `disconnected` both spans are `error`. Both parts go through `boundedRowText`; `maxLines = 1` with ellipsis.
  - When `disconnected`, the leading region (dot and text) draws at `DISCONNECTED_ROW_ALPHA = 0.60f` and the dot is a solid `error` disc with no ring and no blink. The dot keeps its attention description.
  - Trailing `TreeRowControl` when `pinControl != None`: `ic_tree_pin` (11.29 × 16dp) labelled "Pin channel" for `Pin`, `ic_tree_unpin` (16 × 16dp) labelled "Unpin channel" otherwise, at height `ConversationBandHeight`. It is disabled for `UnpinDisabled`.
- `TreeRowControl` gains `enabled: Boolean = true`. The click is disabled and the tint becomes `onSurface` at Material's 38% disabled alpha. The node stays in the tree with its description, so the visible control is reachable and announces itself as disabled.
- `fun treeConversationPinTestTag(serverId: String, conversationId: String): String`: both ids are clamped by `boundedTagId` and length-prefixed, so colliding conversation ids on two hosts get distinct handles.
- Drawables `ic_tree_pin.xml` (viewport 384 × 544, FA 7 `thumbtack`) and `ic_tree_unpin.xml` (viewport 576 × 576, FA 7 `thumbtack-slash` with its −32 top overhang shifted by a `group` translate).

### Tree (`ChannelListScreen.kt`)

- `ConversationTree`: `pinned = remember(hostState.hosts, selectionOnly) { if (selectionOnly) emptyList() else pinnedChannels(hostState.hosts) }`. Host Channels sections drop rows with `pinnedAt != null` unless `selectionOnly`, so a pinned channel appears exactly once. Pickers keep today's grouping, with no Pinned group and no pin controls.
- When `pinned` is non-empty, the tree draws three things before the hosts and outside every fold, so no host or section collapse hides them:
  - `item(key = treeItemKey("pinned-heading"))` holds the heading, as `semantics { heading() }`.
  - `items(pinned, key = { treeItemKey("pinned", serverId, id) })` holds each row: `TreeConversationRow` with the owning host's name or `unnamed_host` fallback, `untitled_discussion` for a nameless channel, `selected = target == hostState.selected`, `onClick = TreeRowTapped(target)`, `attention = entry.attentionFor(id)`, `disconnected = relay.isDisconnected()`, `pinControl = if (hostState.isHostConnected(serverId)) Unpin else UnpinDisabled`, and `onPinToggled = TreePinToggled(target, false)`. Each row carries the test tag `TREE_PINNED_ROW_TEST_TAG`.
  - `item(key = treeItemKey("pinned-rule"))` holds the 1dp rule.
- `treeHost` Channels rows: `pinControl = if (connected && !selectionOnly) Pin else None`, `onPinToggled = TreePinToggled(target, true)`. Chats rows are unchanged.
- Strings: `tree_pinned_section` "Pinned", `cd_tree_pin_channel` "Pin channel", `cd_tree_unpin_channel` "Unpin channel".

## State and concurrency model

No new flow or state. The Pinned group is derived on each composition from `hostState.hosts`, which is already `HostChannelListState`'s confirmed host-local rows (live, or cached while offline). `setPinned` launches one job per tap in `viewModelScope`. It is cancelled with the view model, and a socket close mid-await fails it through the repository's existing teardown sweep. The view model writes nothing on success or failure, so a late completion cannot resurrect anything. The host check is a read of `hostSource.snapshots.value` immediately before dispatch, the same rule as `createChat`. A host that drops between the check and the send fails inside `setPinned` and is logged as failed.

## State transitions and identity reuse

| Event | Test |
|---|---|
| Pin request pending, then confirmed by the host's record | `HostChannelListViewModelTest.pinRequestChangesNothingUntilTheHostsRecordArrives` |
| Refused request (thrown error) leaves placement | `HostChannelListViewModelTest.refusedPinLeavesConfirmedPlacementAndLogsNoIds` |
| Other-device pin/unpin pushed by the host | `HostChannelListViewModelTest.pinnedGroupFollowsPushedRecordsFromAnotherDevice` |
| Reply and duplicate push of the same record; reconnect re-list | `HostChannelListViewModelTest.duplicateAndReListedRecordsNeverDuplicateAPinnedRow` |
| Same conversation id pinned on two hosts | `HostChannelListViewModelTest.collidingIdsStayDistinctInOrderAndDispatchToTheirOwnHost` and `ChannelListScreenTest.pinnedRowsWithCollidingIdsOpenAndUnpinOnTheirOwnHost` |
| Equal `pinnedAt`, host order flipped | `HostChannelListViewModelTest.pinnedOrderIsOldestFirstThenServerIdThenIdWhateverTheHostOrder` |
| Host goes offline, cached pins kept; reconnect | `HostChannelListViewModelTest.offlineHostKeepsCachedPinsAndRefusesToDispatch` and `ChannelListScreenTest.disconnectedPinnedRowIsErrorTintedWithADisabledUnpinUntilReconnect` |
| Cached pins after restart with unreachable host (no live list yet) | `ChannelListScreenTest.restoredPinsOfAnUnreachableHostRenderBeforeAnyLiveList` |
| Host or section folded | `ChannelListScreenTest.pinnedGroupLeadsEveryHostAndSurvivesEveryFold` |

## Error handling

- Disconnected, unknown channel or unavailable repository: `setPinned` refuses before sending, with a static log code. The UI already shows a disabled unpin control for a disconnected pinned row and no pin control for a disconnected host's unpinned rows, so the refusal is a backstop.
- `setPinned` throws (`RelayErrorException`, `IllegalArgumentException`, `IllegalStateException`): `event=channel_pin_failed` is logged, no state changes, and the row stays where the host last confirmed it. The ticket asks for no failure notice.
- Cancellation is rethrown.

## Testing strategy

All JVM/Robolectric; no device test. Nothing needs a real IME, pixels saved to disk or the Keystore.

- `HostChannelListViewModelTest` (unit, the existing colliding-host `Fixture`; `Repo` gains a `setPinned` recorder that applies `pinnedAt` to its own rows, plus a gate and a failure counter):
  - The transition-table tests above. `pinnedChannels` is also driven directly from projected `hostState.hosts` for ordering and membership: no unpinned or archived row, no chat even with `pinnedAt`, and each pinned channel exactly once.
- `ChannelListScreenTest` (shared screen test):
  - The group precedes the first host with its heading and rule, and is absent when no channel is pinned.
  - A pinned channel is absent from its host's Channels section; the row reads "name · host" with fallbacks.
  - Row taps route `TreeRowTapped` with the host-qualified target; pin and unpin taps route `TreePinToggled` with colliding ids.
  - The error treatment and disabled unpin appear for a disconnected host, after scrolling the owning row into view, and are restored on reconnect.
  - `selectionOnly` (`shareHeader`) draws no Pinned group and no pin controls, keeps pinned channels under their host, and keeps selection.
  - The pin control's right edge equals the host pen's.
- `ConversationTreeRowsTest` (shared, `GraphicsMode.NATIVE`):
  - 24dp band height with a control.
  - Physical `click(Offset)` taps one pixel inside each side of the control/row boundary: the inside tap reaches only the control, the outside one only the row, and neither folds or triggers anything else.
  - `None` draws no control node, and a tap at the band's trailing edge opens the row.
  - A disabled unpin tap sends nothing; the control is `assertIsNotEnabled`.
  - Pixel checks: the disconnected treatment paints `error` at 60% over the surface (within ±2 per channel); the connected suffix paints no `error`.
  - The suffix truncates on one line and clamps an oversized host name.

## Invariants (trial:invariant-probes)

- *Each active pinned channel appears exactly once, in Pinned; unpinned channels stay in their host; collapse never hides Pinned.* Covered by `pinnedChannelsMembershipProbes` (VM: duplicates, chats, archived, empty and one-row host) and `pinnedGroupLeadsEveryHostAndSurvivesEveryFold` (screen).
- *Order is oldest `pinnedAt`, then serverId, then conversationId, independent of host enumeration or arrival.* Covered by `pinnedOrderIsOldestFirstThenServerIdThenIdWhateverTheHostOrder`, which checks every host permutation, equal instants, and code-unit order of mixed-case ids.
- *Keys, selection, navigation and pin actions keep both serverId and conversationId with colliding ids.* Covered by `collidingIdsStayDistinctInOrderAndDispatchToTheirOwnHost` and `pinnedRowsWithCollidingIdsOpenAndUnpinOnTheirOwnHost`.
- *Pending or refused commands leave confirmed placement; cached offline pins remain; duplicates and reconnect never duplicate.* Covered by `pinRequestChangesNothingUntilTheHostsRecordArrives`, `refusedPinLeavesConfirmedPlacementAndLogsNoIds`, `offlineHostKeepsCachedPinsAndRefusesToDispatch` and `duplicateAndReListedRecordsNeverDuplicateAPinnedRow`.

## Open Questions

- Pinned-row start inset and the rule's spacing are not in the ticket, and Figma was unreachable. Resolved by reusing tree tokens (see Design source) and naming the choice in the PR.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] SHOULD FIX. Daemon-authored conversation and host names now meet in one `AnnotatedString`, and both reach a content description through row merging. Each part must pass `boundedRowText` separately before concatenation, so a hostile host name cannot make the whole string unbounded, and the string must be rendered only as `Text`. Test: `conversationRow_suffixTruncatesAndClampsAnOversizedHostName`. `pinnedAt` reaches the UI only as a sort key. It is already a strictly parsed `Instant` (#2213) and is never displayed.
- [Trust boundaries] No findings for routing. A pin action carries the row's own `HostConversationTarget`, built from the snapshot that drew the row and never from row text. `setPinned` re-reads the current snapshot and requires the id to be an active channel on that exact host before resolving `repositoryFor(serverId)`, so a stale or forged event cannot reach another host's same-id conversation.
- [Tokens] No findings. No credential is read, created or sent. The command rides the existing Noise session.
- [Files and storage] No findings. No new storage. Cached pins come from #2213's app-private, atomically written `FileConversationCache`; this slice only reads them through `HostConversationSnapshot`.
- [Android surface] No findings. No exported component, intent, deep link, WebView or push path changes. The controls are in-app Compose nodes.
- [Cryptography] No findings. Untouched.
- [Network and I/O] No findings. A dropped reply leaves the job suspended until the socket's teardown sweep or `viewModelScope` cancellation, which is the bound #2214's review assigned to this first UI caller. No state is mutated meanwhile, and repeated taps send idempotent requests whose replies the host's record supersedes.
- [Errors and logs] SHOULD FIX. The new log lines must carry only the static event, code and `pinned` boolean, never the server id, conversation id, name or a `RelayErrorException` message. Test: `refusedPinLeavesConfirmedPlacementAndLogsNoIds` asserts no id or message text in `logs`.
- [Concurrency] No findings. Each tap's job is owned by `viewModelScope`. No state is read-modify-written; the only shared read is `snapshots.value`. No mutex.
- [Threat model] Malicious relay: it can drop or delay the request (the job waits, nothing moves) but cannot forge a record. Hostile daemon frame: names are bounded and rendered as text, and order uses only parsed instants and ids. A colliding id across hosts is handled by host-qualified keys, tags and targets. UI leakage: no new sensitive text is displayed.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-17
