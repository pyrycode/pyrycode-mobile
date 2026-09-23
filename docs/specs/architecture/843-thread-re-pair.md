# #843 — Re-pair beside the composer when the host rejects the pairing

## Files read

- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `ThreadDestinationFactory.thread` — builds the thread's `ConnectionStateSource` from the bundle captured at open; `supervisor.observe()` folds `PairingRejected` into `Offline`, so the thread cannot tell a rejection from network loss. `repositoryAvailable` is the defaulted-`Flow` constructor-argument precedent.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → `hostConnections`, `reconcile` — per-host `HostConversationConnection` list; a changed saved record (a successful re-pair) closes the old bundle and publishes a new entry with a new `status` flow.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `HostConversationConnection` — `serverId` + `status: StateFlow<ConnectionStatus>`.
- `app/src/main/java/de/pyryco/mobile/data/model/RelayLinkStatus.kt` → `RelayLinkStatus.PairingRejected`.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt` → the legacy mapping `PairingRejected -> ConnectionState.Offline` (why the banner offers a hopeless retry).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `ThreadViewModel` constructor, `connectionState`, `draft` — sibling-`StateFlow` convention (the `state` combine is at its arity ceiling).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`, `ThreadStatusArea`, `HistoryRetryRow` — the status slot's arms and the "no live signal emits no node" rule; the KDoc already reserves the trailing contextual-action slot for #675 (this ticket's parent).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionBanner.kt` → `ConnectionBanner` — the `errorContainer`/`onErrorContainer` family; `Offline` is the "tap to retry" arm.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `CONVERSATION_THREAD` destination, `Routes.pairCode`, the `TreeHostRePairTapped` binding (#842) and the `PAIR_CODE_ROUTE` destination (Complete pops the graph to the channel list; Cancel pops back).
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeViewModel.kt` + the `PairCodeViewModel` Koin binding — the optional `serverId` target already restricts the flow to that host's pairing.
- Issue #843's earlier builder comment (design notes against the pre-#805/#808 tree) — the key lesson: read the rejection through `registry.hostConnections` by `serverId`, never from the captured bundle, or the button could never clear after a re-pair replaces the bundle.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8 (button: node `354:7093`; status area: `111:3525`)

The status area is one row: the live status signal (e.g. "Thinking…") leading, the "Pairing error - Re-pair" small button trailing. The button is a 6dp-radius container with 16dp horizontal / 8dp vertical padding and a `bodySmall` label at medium (500) weight (`M3/body/small-emphasized`). The Figma paints it `on-error` / `error`; the ticket directs the theme's `errorContainer` / `onErrorContainer` pair instead, the same family as `ConnectionBanner` — that deviation is deliberate and noted in code.

## Context

When a host rejects the saved pairing, the thread shows "Offline — tap to retry", which cannot succeed. The recovery flow (code-pair for one named host, #842) exists but is only reachable from the channel list. This ticket offers it in the thread. It fills the trailing contextual-action slot `ThreadStatusArea`'s KDoc reserved for #675.

## Design

**Rejection signal (AppModule.kt).** A new top-level `internal fun pairingRejected(connections: Flow<List<HostConversationConnection>>, serverId: String): Flow<Boolean>`: find the connection whose `serverId` equals the argument exactly, `flatMapLatest` onto its `status`, map to `status.relay == RelayLinkStatus.PairingRejected`, a missing host maps to `false`, `distinctUntilChanged`. `ThreadDestinationFactory.thread` passes `pairingRejected(registry.hostConnections, serverId)` to the view model on the relay path; the demo path keeps the default. Keyed by `serverId` through the registry, not the captured bundle, so a re-pair that replaces the bundle is observed.

**View model.** New defaulted constructor parameter `pairingRejected: Flow<Boolean> = flowOf(false)` and a sibling `val rePairAvailable: StateFlow<Boolean>` (`stateIn(viewModelScope, WhileSubscribed(5_000), false)`), logging `event=thread_repair_offered` when it turns true (content-free). No `ThreadUiState` field: the `state` combine is at its arity ceiling, and this matches `connectionState`.

**Screen.** `ThreadScreen` gains `showRePair: Boolean = false` and `onRePair: () -> Unit = {}`.
- `ConnectionBanner` is not drawn while `showRePair` holds (the legacy state is `Offline` then, and its retry cannot succeed). `Connecting` / `Reconnecting` / `Offline` banners otherwise unchanged.
- `ThreadStatusArea` takes the two parameters. Without `showRePair` it is exactly today's `when` (so an idle band still emits no node and the column gap collapses). With it, a `Row` over the gutter: the existing signal in a `weight(1f)` box leading, a private `RePairButton` trailing.
- `RePairButton`: M3 `Surface(onClick)` with `RoundedCornerShape(6.dp)`, `errorContainer` / `onErrorContainer`, a `bodySmall` + `FontWeight.Medium` label from a new string resource `thread_re_pair` ("Pairing error - Re-pair"). The label is local, never daemon text.
- Previews: a light and dark preview of the status area with the button.

**Navigation (MainActivity).** Collect `vm.rePairAvailable`; pass `showRePair`, and `onRePair = { navController.navigate(Routes.pairCode(target.serverId)) }` — the route's own `serverId`, the same route #842's tree-row entry uses. The thread stays on the back stack under the pair-code screen (Cancel pops back to it, history intact); Complete keeps its existing pop-to-channel-list behaviour.

## State + concurrency model

One new cold flow per thread destination, collected only through `rePairAvailable`'s `WhileSubscribed` in `viewModelScope`; cancelled with the screen. `hostConnections` and each `status` are the registry's existing hot `StateFlow`s; collecting them never dials. `flatMapLatest` drops the previous host entry's status when reconcile replaces it.

## Error handling

No new failure modes. A missing host (unpaired while the thread is open) reads `false`; `HostDestination` already bounces an unknown host.

## Testing strategy

- Unit, `app/src/test/.../di/PairingRejectedTest.kt`: own host rejected → true; another host rejected → false; missing host → false; the host entry replaced by a new one with a connected status (a successful re-pair) → false; own host moving rejected → connected → false.
- Unit, new `app/src/test/.../thread/ThreadViewModelRePairTest.kt` (a new file, not `ThreadViewModelTest.kt`, to avoid colliding with #816's in-flight additions there): default is false; follows the injected flow true → false.
- Compose, new `app/src/androidTest/.../thread/ThreadScreenRePairTest.kt`: with `showRePair` and `Offline`, the Re-pair button is displayed and "Offline — tap to retry" does not exist; tapping it invokes `onRePair`; without `showRePair` and `Offline`, the banner shows and the button does not exist.
- Real-Claude e2e: not applicable — the state needs a host that rejects the pairing, which the live harness cannot produce without re-keying its daemon; the dispatcher's UI gate covers the Compose test.

## Open questions

- In-flight overlap with #816 (In Documentation, verified): it touches `AppModule.kt`, `ThreadViewModel.kt`, `ThreadScreen.kt` and `ThreadViewModelTest.kt` in regions separate from this plan's edits. Resolution: keep tests out of `ThreadViewModelTest.kt` and prove a clean merge with `git merge-tree` against `origin/feature/816` before opening the PR.

## Documentation handoff

None named by the ticket. Pending for the documentation stage: the thread's status-area overview should note the Re-pair arm and the banner suppression.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the only inputs are the destination route's own `serverId` (an app-internal navigation argument `HostDestination` has already resolved to a saved host) and the local `RelayLinkStatus` enum the supervisor derives. `pairingRejected` matches by exact `serverId` equality, the same rule `connectionFor` and the tree use, so another host's rejection cannot raise the button. No daemon-authored text reaches the new UI; the label is a local string resource.
- [Tokens, secrets] No findings — the new flow reads `HostConversationConnection` (id, name, status flows), never a `PairedServerEntry`, so no token or static key enters the view model. Pairing itself is the unchanged #842 flow; its target mode refuses a code whose `serverId` differs from the route's (`target_mismatch` in `PairCodeViewModel`), so the thread cannot be used to replace a different host's pairing.
- [File / storage] Not applicable — nothing is written; re-pair storage is the existing Keystore-wrapped store path.
- [Inter-process] No findings — no intent filter, deep link or pending intent. `Routes.pairCode` URI-encodes the id before it enters the route string.
- [Crypto] Not applicable — no primitive touched; the Noise handshake after re-pair is the existing path.
- [Network & I/O] No findings — collecting the registry's hot status flows never dials; retry is not offered in the rejected state, so the thread cannot drive an auth-failure redial loop (the banner's `Offline` retry is suppressed exactly then).
- [Logs] No findings — one content-free debug event `event=thread_repair_offered`, no server id, via `RelayLog.d` (debug-only).
- [Concurrency] No findings — one cold flow per destination behind `WhileSubscribed` in `viewModelScope`; `flatMapLatest` drops a replaced host entry's status, so a re-pair that swaps the bundle is observed and the old flow is released.
- [Threat model] OUT OF SCOPE — an on-path relay could fake a rejection to show the button; tapping it only opens the code-pair screen, which needs a code from the operator's own host, so the relay gains nothing new over #842's existing tree-row entry.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

## Revisions

**2026-09-23 — Open question resolved (overlap with #816).** #816 merged to `main` (PR #870) during the build. `git merge-tree` of this branch against `origin/main` reports no conflict; `main` is merged into the branch and the touched scope re-verified on the merged tree. The design is unchanged.
