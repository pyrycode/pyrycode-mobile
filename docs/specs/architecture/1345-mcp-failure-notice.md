# #1345 — A failed MCP server raises a notice that opens Channel info

## Files read

- `ui/conversations/thread/ThreadViewModel.kt` — `mcpStatusReading` (the #1344 reading), the `ThreadEvent.ChannelInfo` branch of `onOverflowEvent` (the `requestMcpStatus` call gated on `runConfig.mcpServersSupported`), `repositoryAvailable` and the #778 walk-restart collector in the second `init`, `hostConnection`, `serverId`.
- `ui/conversations/thread/UsageLimitDismissals.kt` — the shape the new holder copies: app-scoped, heap only, never logged.
- `ui/conversations/thread/ComposerDraftStore.kt` — `clearHost`, the per-host nested-map shape for the host/conversation key.
- `ui/conversations/thread/ThreadTopOverlay.kt` — the pill stack: usage, then Re-pair or offline.
- `ui/conversations/thread/ThreadScreen.kt` — the `ThreadTopOverlay` call and the `showRePair` / `dismissedUsageLimits` parameters.
- `ui/conversations/components/McpServersSection.kt` — `boundMcpText`.
- `ui/conversations/components/NoticePill.kt` — `onClick` Error pill, `maxLines` with ellipsis.
- `data/repository/ConversationRepository.kt` — `McpStatus`, `McpStatusReport`, `McpServerStatus` (redacted `toString`, security note).
- `MainActivity.kt` — the thread destination collecting `vm.*` flows and `UsageLimitDismissals`.
- `di/AppModule.kt` — the app-scoped `single`s, `ThreadDestinationFactory.thread`, the `forgetRemovedHost` binding.
- `di/ObservablePairedServerStore.kt` — `forgetRemovedHost`.
- Desktop `src/renderer/src/store/mcpStatusStore.ts` — `selectUnacknowledgedMcpFailureFor`, `acknowledgeMcpFailures`, `isMcpServerFailed` (`status === 'failed'`). Acknowledgements are never pruned on later reports.
- Tests mirrored: `ThreadViewModelMcpServersTest` (its `RecordingRepo` and `rig`), `ThreadTopOverlayTest`, `UsageLimitDismissalsTest`, `HostChannelListViewModelTest`'s unpair fixture.
- Feature overview lessons: `thread-top-overlay.md` (pill order and touch-target sizing), `channel-info-sheet.md` (the MCP reading starts empty per connection).

In-flight overlaps: #1340, #1346, #1348, #1355, #1359, #1360, #1410, #1412, #1449 touch `ThreadViewModel.kt`, `ThreadScreen.kt`, `MainActivity.kt`, `AppModule.kt` or `strings.xml`. Edits here stay additive and local.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=541-2446

The message area's `Top overlay`: a right-aligned stack of 24dp `NoticePill`s 12dp apart, the usage pill on top. The new notice reuses the Error pill without an X (`541:2499`, the Re-pair pill): `errorContainer` ground, `error` text, `bodySmall`, tappable as a whole. No new visuals; only the copy is new.

## Context

#1344 shows the MCP reading in Channel info, but a failed server is only discovered by opening the sheet. Desktop #1494 raises a notice in the conversation instead. This ticket ports it. No decision record is needed: it follows the #1002 overlay and the desktop store.

## Design

**`McpFailureAcknowledgements`** (new, `ui/conversations/thread/`), app-scoped `single` beside `UsageLimitDismissals`:

- `observe(serverId, conversationId): Flow<Set<String>>` — the server names acknowledged for that chat, `distinctUntilChanged`.
- `acknowledge(serverId, conversationId, names: Collection<String>)` — adds; an empty collection changes nothing.
- `clearHost(serverId)` — drops every conversation of that host.
- Held as `MutableStateFlow<Map<String, Map<String, Set<String>>>>` (host → conversation → names), the `ComposerDraftStore` shape, so the same conversation id on two hosts never collides.

Pure helpers in the same file:

- `isMcpServerFailed(status: String): Boolean` — exactly `"failed"`.
- `firstUnacknowledgedMcpFailure(report: McpStatusReport?, acknowledged: Set<String>): String?` — first server in report order that is failed and not acknowledged. No built-in filter.
- `failedMcpServerNames(report: McpStatusReport?): Set<String>`.

**`ThreadViewModel`**:

- New optional constructor parameter `mcpFailureAcknowledgements: McpFailureAcknowledgements? = null`; absent, a private holder stands in (the `permissionDraftStore` pattern).
- `val mcpFailure: StateFlow<String?>` — `combine(mcpStatusReading, acknowledgements.observe(serverId, conversationId), hostConnection)`: the selected name while `hostConnection == Connected`, else `null`. `WhileSubscribed(5_000)`, initial `null`.
- `fun onMcpFailureTapped()` — acknowledges `failedMcpServerNames(state.value.mcpStatus.report)` for this host and conversation, logs `event=mcp_failure_acknowledged count=N`, then `onOverflowEvent(ThreadEvent.ChannelInfo)`, which opens the sheet and asks for fresh status.
- Open and reconnect ask: a collector in the second `init` over `repositoryAvailable.distinctUntilChanged()`, acting on each `true` — the first one is the opening, every later one a reconnect. Each calls `requestMcpStatus(conversationId)` when `state.value.runConfig.mcpServersSupported`, the same gate the Channel info branch reads, and logs `event=mcp_status_requested reason=thread_open|reconnect`. Keyed on the repository's availability rather than the socket for the #861 reason: the ask needs the published repository.

**`ThreadTopOverlay`**: new parameters `mcpFailure: String? = null`, `onOpenMcpFailure: () -> Unit = {}`. Drawn after the usage pill and only when neither the Re-pair nor the offline pill shows: an Error `NoticePill` with `onClick`, text `stringResource(R.string.thread_mcp_server_failed, boundMcpText(name))`, `maxLines = 2` so a 256-code-point name cannot cover the thread.

**`ThreadScreen`**: passes the same two parameters through to the overlay, defaulted.

**`MainActivity`**: collects `vm.mcpFailure` and binds `onOpenMcpFailure = vm::onMcpFailureTapped`.

**`AppModule`**: `single { McpFailureAcknowledgements() }`; `ThreadDestinationFactory.thread` gains `mcpFailureAcknowledgements: McpFailureAcknowledgements? = null` passed to the relay-host `ThreadViewModel` (the demo early return stays inert); the Koin `viewModel` passes `get()`; `forgetRemovedHost` gets `get()` for the holder.

**`ObservablePairedServerStore.forgetRemovedHost`**: new parameter `mcpAcknowledgements: McpFailureAcknowledgements`, cleared with `clearHost(serverId)` right after `drafts.clearHost`.

**String**: `thread_mcp_server_failed` = `MCP server %1$s failed` (desktop's `mcpFailedCopy`).

## State and concurrency model

- The holder is a `MutableStateFlow` mutated with `update {}` only, so concurrent acknowledges and an unpair cannot lose each other's writes. It holds no scope.
- `mcpFailure` is cold until the screen collects; `hostConnection` is already `Eagerly` collected.
- The ask collector runs in `viewModelScope` and ends with the view model. `requestMcpStatus` is fire-and-forget and never throws.
- On background the lifecycle driver closes the socket; `repositoryAvailable` goes false, and the return to true is a reconnect, which asks again. The reading starts empty on the new connection, so the notice reappears only after the fresh report, and the acknowledgements, held app-wide, still apply.

## Error handling

No new failure modes. A refused ask sets `McpStatus.unavailable` and leaves `report` as it was (null on a fresh connection), so no notice shows. A tap with no current report acknowledges nothing and still opens Channel info.

## Testing strategy

- `McpFailureAcknowledgementsTest` (unit): selection — first failed in report order, exactly `failed` (`Failed`, `failed `, `pending` do not count), built-in names count, acknowledged skipped, `null` report; holder — per host and per conversation isolation, `clearHost` leaves other hosts, empty acknowledge emits nothing new.
- `ThreadViewModelMcpFailureTest` (unit, the `ThreadViewModelMcpServersTest` rig with a controllable connection and `repositoryAvailable`): notice shows the first unacknowledged failure; tap acknowledges every failed server and opens Channel info with one status request; a repeat report stays quiet, a newly failed server raises its own notice, a recovered server stops showing; acknowledgements survive a second view model on the shared holder (reopen) and a reconnect; another conversation's notice still shows; hidden while not `Connected`; one ask on open and one per return of `repositoryAvailable`, none while `mcpServersSupported` is false; logs never carry a server name.
- `ThreadViewModelMcpServersTest`: `rig` clears the open-time ask and its log before each existing assertion.
- `ThreadTopOverlayTest` (shared screen test): the pill reads "MCP server NAME failed", sits below the usage pill, is tappable, and is absent alongside Re-pair or offline.
- `HostChannelListViewModelTest`: confirming unpair through the production `forgetRemovedHost` drops that host's acknowledgements and keeps the other host's.
- Not operator-facing in the real-Claude sense: a failed MCP server cannot be produced on demand by the scripted or real-Claude harness without a broken MCP config on the daemon host. A follow-up for a rung-3 scenario is filed with the PR.

## Open Questions

- Does the scripted daemon fixture tolerate an `mcp_status_request` on every thread open? The daemon answers or refuses with `mcp_status.unavailable`; neither changes the thread. The gate's scripted scenarios will show it.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] The server name and status are Claude-authored and arrive only as `McpServerStatus` from the existing #1343 decoder; this ticket adds no parsing. The name reaches Compose once, through `boundMcpText` into a `stringResource` format argument rendered by `NoticePill`'s `Text`, never as markup, a URL, a tag or a content key. `status` is only compared with the client constant `"failed"`. SHOULD FIX: give the pill `maxLines = 2` so the bounded 256-code-point name cannot cover the message area; the verifier checks it.
- [Tokens] No findings. The design touches no token, key or pairing secret; the holder stores server names only.
- [Files and storage] No findings. `McpFailureAcknowledgements` is heap only: no disk, no DataStore, no saved-instance state, so nothing is backed up and a restart forgets it. It is cleared from `forgetRemovedHost` on unpair, so a removed host's server names do not outlive its pairing.
- [Android attack surface] No findings. No new component, intent, deep link or WebView.
- [Cryptography] No findings. No crypto change.
- [Network and I/O] The new open and reconnect asks reuse `requestMcpStatus`, which sends one small frame per call and never retries. A hostile relay toggling availability can only cause one ask per reconnect, which the supervisor's backoff already rate-limits.
- [Errors, logs, telemetry] SHOULD FIX: the acknowledgement and ask logs carry only static codes and a count; never the server name, the status or the conversation id. The holder overrides nothing printable: it is a plain class with no `toString` that would print its map. A test asserts no name reaches a log line.
- [Concurrency] No findings. Holder mutations use `MutableStateFlow.update`; the ask collector is owned by `viewModelScope`; no read-then-write across a suspension point.
- [Resource bounds] A hostile daemon could report many distinct failed names, but names enter the holder only on an operator tap and only from the current report, whose length the daemon caps (`droppedServers`). Growth is bounded by taps × report size per app run.
- [Threat model] Malicious relay: cannot inject a report (inside Noise); dropping or delaying reports only hides or delays the notice. Hostile daemon frame: handled by the #1343 decoder; rendered as bounded text. Token theft from disk: nothing stored. UI leakage: the name is already shown in Channel info; no new exposure.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-02

## Revisions

- 2026-10-02: The opening ask no longer logs; only the reconnect ask logs `event=mcp_status_requested reason=reconnect`. Driven by six existing `ThreadViewModel` suites that construct the view model without a `RelayLog` sink: the thread's construction path is otherwise log-free, so a log there threw `Log not mocked` on the JVM. The opening is already logged by the factory's `thread_destination_bound`. The ask itself is unchanged: once on opening, once per return of `repositoryAvailable`.
