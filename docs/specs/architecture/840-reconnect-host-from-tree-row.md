# #840 — Reconnect one disconnected host from its tree row

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `TreeHostRow`, `FoldableTreeRow`, `TreeRowControl`, `ConnectionLegPair`, `boundedRowText`, `treeHostEditTestTag`: the row this ticket extends, the 48dp trailing control it reuses, and the per-host test-tag clamp the new tag shares.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ChannelListEvent`, `treeSection`: where the host row is built from the row's own `serverId`.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `PyryNavHost`'s `ChannelListEvent` dispatch: the one place an event reaches `ChannelListViewModel`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `ChannelListViewModel`: holds only `HostConversationSource`, no registry.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `HostConversationSource`, its `relay` / `demo` factories: the view model's only host seam; `relay` already closes over the registry.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → `retryHost(serverId, expectedBundle)`, `connectionFor`: the existing per-host retry; refuses while backgrounded, after removal, or for a stale bundle.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `ThreadDestinationFactory.thread`: the thread banner's `connectionFor` → `retryHost` pairing this ticket mirrors.
- `app/src/main/java/de/pyryco/mobile/data/model/RelayLinkStatus.kt` → the six relay-leg cases the classification covers.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` → `Fixture`, `Store`, `preferences`: the test shape; not edited (see Context).
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRowsTest.kt`, `.../list/ChannelListScreenTest.kt` → the edit control's presence/description/tap tests the new ones mirror.
- `docs/knowledge/features/channel-list-screen-tree-and-controls.md`: tree controls are host-qualified from the row, never from the selected-host adapter.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=133-259

The Sidebar's lower Channels section's first host row ("Pyrybox") is disconnected. Its server glyph and titleSmall name are drawn in the scheme's error family, and a primary-tinted plug glyph (`plug-solid-full`) sits immediately before the two leg dots, with the relay dot error-red. Everything else matches the connected host row.

Deviations, both deliberate:
- The frame binds the name to `Schemes/error-container`. In the light scheme that is `#ffdad6` on a near-white surface, which is illegible, so glyph and name use `colorScheme.error`, which reads as error-toned in both schemes. The ticket asks for the theme's existing error colours; no new token.
- The frame's disconnected row omits the fold chevron. The row keeps its chevron and fold, because the whole row is the fold control and removing its visible affordance for one state is not in scope.
- The plug is Material `Icons.Filled.Power` (the extended set is already a dependency), matched the same way the row matched `Dns` and `FolderOpen`.

## Context

The host row shows a dropped host's dots but offers no action. The thread's banner already retries one host through `RelayConnectionRegistry.retryHost`; the list has no path to it because `ChannelListViewModel` only holds `HostConversationSource`. This ticket adds that path and the control.

File-overlap check (§ A2): three in-flight branches touch files named here, none on the same hunks. #803 edits `MainActivity`'s thread destination and `strings.xml` around the thread strings; #826 appends to `strings.xml` in the modal block. This ticket edits the `ChannelListEvent` dispatch and the `cd_tree_host_*` block. #798 edits `HostChannelListViewModelTest`'s `Fixture` construction of `HostConversationSource`, which this ticket would otherwise touch, so the view-model test goes in a new file instead. Not blocked.

## Design

**Classification** (`ConversationTreeRows.kt`): `internal fun RelayLinkStatus.isDisconnected(): Boolean`, an exhaustive `when` over all six cases with no `else`: `Reconnecting`, `Offline` and `DaemonAbsent` are `true`; `Idle`, `Connecting` and `Connected` are `false`. It reads only the relay leg, as the ticket specifies.

**Row** (`TreeHostRow`): gains `onReconnectTapped: () -> Unit = {}` as its last callback. The default keeps the seven test call sites and two previews unchanged. The one production caller passes it explicitly, and `ChannelListScreenTest` asserts that wiring. When `connectionStatus.relay.isDisconnected()`:
- `FoldableTreeRow` receives an optional `accent: Color?` (default `null` = today's tints). When it is set, the glyph and name take it. The host row passes `colorScheme.error`.
- A `TreeRowControl(Icons.Filled.Power, cd_tree_host_reconnect(bounded), onReconnectTapped)` tagged `treeHostReconnectTestTag(serverId)` is drawn before `ConnectionLegPair`.

Otherwise the row composes exactly as today. `treeHostReconnectTestTag` shares `boundedTagId` with the other two tags. New string: `cd_tree_host_reconnect` = "Reconnect %1$s".

**Event** (`ChannelListScreen.kt`): `data class TreeHostReconnectTapped(val serverId: String) : ChannelListEvent`, built in `treeSection` from `host.serverId`, the row's own host. `MainActivity` dispatches it to `vm.reconnectHost(event.serverId)`.

**View model**: `fun reconnectHost(serverId: String)` logs `event=tree_host_reconnect_tapped` and calls `hostSource.retryHost(serverId)`. It has no state and does not touch `collapsedKeys`, snapshots or the editor.

**Source** (`HostConversationSource`): a trailing constructor parameter `retry: (String) -> Unit = {}` (after `cache`, so no positional call site moves) and `fun retryHost(serverId: String)`. The method reads `disposed` under the monitor and invokes `retry` **outside** it, so the source never holds its lock while taking the registry's. `relay(...)` passes `{ id -> registry.connectionFor(id)?.let { registry.retryHost(id, it) } }`, the same pairing `ThreadDestinationFactory.thread` uses. `demo(...)` keeps the no-op default because the demo host is always connected.

## State + concurrency model

No new flows or jobs. `retryHost` in the registry is synchronous and launches the supervisor's nonblocking retry under its own lock, which is existing behaviour. A bundle replaced between `connectionFor` and `retryHost` fails the identity check and is refused. Other hosts are never touched: the registry's `retryHost` addresses one `entries` value. The snapshot and its cached rows are unaffected, since a retry only changes that host's `connectionStatus` through the existing collector.

## Error handling

No new failure modes. A refused retry (backgrounded, removed, stale bundle, unknown id) is the registry's existing silent refusal with its `host_retry_rejected` log. There is no UI surface, and the dots keep showing the real state.

## Testing strategy

- **Unit** `ui/conversations/components/RelayLinkDisconnectedTest`: all six cases classified as specified.
- **Unit** `HostConversationSourceTest`: `retryHost` forwards the exact id once; no call after `dispose`.
- **Unit** new `ui/conversations/list/ChannelListReconnectTest`: builds `ChannelListViewModel` directly over a two-host source with a recording `retry` and two fake repositories. `reconnectHost("b")` records exactly `["b"]`, and `hostState` still carries both hosts with their rows.
- **Compose** `ConversationTreeRowsTest`: a disconnected row shows the control with "Reconnect Pyrybox", and a tap fires only `onReconnectTapped`. Connected, connecting and idle rows have no reconnect node.
- **Compose** `ChannelListScreenTest`: tapping the reconnect control on one host emits `TreeHostReconnectTapped(<that serverId>)`.
- **Rung 3**: none. The tap drives the relay supervisor, not a claude turn, and reaching a disconnected host would need a relay outage that the real-claude harness cannot script. The foreground refusal is `retryHost`'s existing, already-tested behaviour.

## Open questions

- Does `ChannelListScreenTest`'s `entry(...)` helper take a `connectionStatus`? If not, add a defaulted parameter in the test file only.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/channel-list-screen-tree-and-controls.md` should add the host row's reconnect control (disconnected classification, `serverId`-only routing, the `HostConversationSource.retryHost` seam).

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The retry key is `HostConversationSnapshot.serverId`, which originates in the saved `PairedServer` record through `RelayConnectionRegistry.reconcile`, never in a daemon frame. The daemon-influenced `displayName` reaches only `boundedRowText` → the control's content description, which is clamped as the edit/add descriptions are, and is never an argument to `reconnectHost`. `TreeHostReconnectTapped` carries only `serverId`.
- [Tokens] No findings. No credential is read. The retry path passes an id and a bundle reference, and the bundle's token stays inside the supervisor.
- [File / storage] N/A. The design writes nothing to disk; the conversation cache is only read by the existing seed path.
- [Android surface] No findings. No new intent, deep link, pending intent or exported component; the control is in-app Compose.
- [Crypto] No findings. A retry re-dials through the existing supervisor, which performs a fresh Noise IK handshake per socket. No key or nonce handling is added.
- [Network & I/O] No findings. The supervisor's backoff, timeouts and single-transport guarantee are unchanged. Repeated taps collapse the pending backoff the same way the thread banner's retry does and do not open a second socket. Tap-rate limiting is the same exposure the banner already has, so it is not a new finding.
- [Logs] No findings. The new log line is a static event name with no id, name or status value.
- [Concurrency] No findings. `HostConversationSource.retryHost` releases its monitor before calling into the registry, so no lock-order inversion is possible with `reconcile`. The registry's identity check (`entries[serverId]?.second !== expectedBundle`) refuses a bundle replaced between lookup and retry. Its `foreground` check keeps a backgrounded tap from reopening a socket, which satisfies the AC unchanged.
- [Threat model] No findings. A hostile daemon can make a host look disconnected (e.g. never register → `DaemonAbsent`). The worst outcome is that the operator redials that one host, which the backoff already bounds.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
