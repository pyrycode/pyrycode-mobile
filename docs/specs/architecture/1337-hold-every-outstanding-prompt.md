# #1337 — Hold every outstanding prompt per conversation; drop a host's prompts on reconnect

## Files read

- `app/src/main/java/de/pyryco/mobile/data/model/ModalUiState.kt` → `ModalUiState`, `ModalUiState.reduce`, `ModalUiState.scopedTo` — the single-value fold this ticket replaces with a host state.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` → `modalEvents`, `currentModal`, `activeConnection`, `onConnection`, `teardownActive` — where the fold runs `Eagerly` and where a new connection is published.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `hostModal` ctor parameter, `currentModal`, `scopedModal`, `armedOptionId`, `alwaysAllowAccepted`, `onAlwaysAllowChanged`, `grantsAlwaysAllow` — the per-thread consumers that keep working off the scoped value.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/PermissionDraftStore.kt` → `bind`, `retireStale` — today retires every draft whose conversation is not the single host modal's.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `thread(...)` destination factory: passes the coordinator's modal flow to `PermissionDraftStore.bind` and to `ThreadViewModel(hostModal = …)`.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → `currentModal`, `reconcile` (`HostConversationConnection` construction); `di/ConversationAttention.kt` → `HostAttentionState.resolve`; `di/HostConversationSource.kt` → `promptKeys` — single-value readers left untouched (they keep reading `coordinator.currentModal`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → the `when (modalState)` Dismissed arm — the snackbar keyed on `modalId` that the scoped Dismissed must still drive.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation` and its helpers (`answerHostPeer`, `answerChat`, `awaitPromptDialog`, `tapInPrompt`, `peer.awaitModalDismissed`, `peer.awaitPermissionModal`, `peer.allowOnce`).
- Tests: `ModalUiStateTest`, `RelayRepositoryCoordinatorTest` (§ #492 modal tests, `newEnv`, `modalShownEnvelope`), `PermissionDraftStoreTest`, `ThreadViewModelTest` (`makeVm`, `vmWithModal`, `vmWithModalSendPath`).
- `docs/knowledge/features/current-modal-state.md` § "Connection teardown = RETAIN, not reset" — the #492 decision this ticket revises now that the daemon guarantees connect-time re-sends (protocol-mobile § Reconcile on (re)connect). Teardown still retains; only a *new connection* clears.
- Desktop reference: `src/renderer/src/store/modalPrompts.ts` `reduceModal` (shown / dismissed / reconnected arms), `PermissionModal` prompt lookup.

Overlapping in-flight branches (#1311, #1325, #1329, #1399 on `ThreadViewModel.kt`/`InteractiveStreamE2ETest.kt`/`ThreadViewModelTest.kt`, #1330 on `AppModule.kt`) touch other blocks; no dependency. Edits to those files stay local.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=639-2242

The inline permission card from #1306 — unchanged. This ticket changes which prompt each thread receives, not how it renders; no visual-fidelity delta.

## Context

Each host held one modal: a second chat's `modal_shown` replaced the first chat's, and the first's later `modal_dismissed` matched nothing. The fold also retained an `Open` across reconnects (#492), so a prompt resolved while the phone was away stayed on screen and every tap failed. The daemon scopes each `modal_shown` by `conversation_id` and re-sends every still-outstanding prompt on (re)connect, so the phone can hold all of them and drop its copy on each new connection. This mirrors desktop #415/#510/#1140.

The revised teardown/reconnect decision in `current-modal-state.md` is ADR-worthy only as an amendment; the documentation stage decides.

## Design

### `HostModalState` (new, `data/model/ModalUiState.kt`)

```kotlin
data class HostModalState(
    val outstanding: List<ModalUiState.Open> = emptyList(),   // shown order
    val resolved: List<ModalUiState.Dismissed> = emptyList(), // dismissal order, this connection only
)
internal fun HostModalState.reduce(event: ModalEvent): HostModalState
fun HostModalState.scopedTo(conversationId: String): ModalUiState
val HostModalState.latestOutstanding: ModalUiState  // last of outstanding, else Hidden
```

- `reduce(Shown)`: an id already in `resolved` → unchanged. An id held in `outstanding` → replaced in place (same index). Else appended.
- `reduce(Dismissed)`: an id in `outstanding` → removed, and a `ModalUiState.Dismissed` carrying the verbatim outcome/source plus the held prompt's `conversationId` appended to `resolved`. Unknown id → unchanged (spoofed-dismiss safety kept).
- Reconnect = `HostModalState()` (empty). No dedicated function needed beyond the empty value.
- `scopedTo(c)`: the first `outstanding` whose `scopedTo(c)` is not Hidden; else the last `resolved` whose `scopedTo(c)` is not Hidden; else Hidden. Reuses `ModalUiState.scopedTo` so a blank conversation still matches nothing.
- `latestOutstanding`: the single-value view kept for `HostAttentionState.resolve`, `HostConversationSource.promptKeys`, `RelayConnectionRegistry.currentModal` until #1338 (those read only `Open`).
- `ModalUiState.reduce` is removed (its only caller was the coordinator's fold); its tests are ported to the host fold. `ModalUiState.scopedTo` stays.

### Coordinator

- `hostModals: StateFlow<HostModalState>` (new, public): `activeConnection.flatMapLatest { conn -> conn's repo.modalEvents mapped to a nullable input and prefixed with onStart { emit(null) } ; null conn → emptyFlow() }.scan(HostModalState()) { s, e -> if (e == null) HostModalState() else s.reduce(e) }.stateIn(scope, Eagerly, HostModalState())`. The `null` input is the reconnect marker: `onStart` emits it when the switched inner flow starts for a new connection, before any of that connection's modal frames are collected. Teardown (`activeConnection = null`) switches to `emptyFlow()` and folds nothing, so prompts held while disconnected stay until the next connection is published. `Eagerly` for #492's reason.
- `currentModal: StateFlow<ModalUiState>` keeps its name and type as the single-value view: `hostModals.map { it.latestOutstanding }.stateIn(scope, Eagerly, Hidden)`. It no longer carries a `Dismissed` (no remaining reader of it uses one).
- `modalEvents` stays private.

### ThreadViewModel

- `hostModal` ctor parameter type changes to `StateFlow<HostModalState>` (default `MutableStateFlow(HostModalState())`). `currentModal` = `hostModal.map { it.scopedTo(conversationId) }` seeded the same way; `scopedModal()` reads `hostModal.value.scopedTo(conversationId)`. Nothing else in the VM changes: arming, the grant draft and the answer guard already work off the scoped value.

### PermissionDraftStore

- `bind(serverId, owner, modals: StateFlow<HostModalState>)`. `retireStale` keeps a host's draft when any `outstanding` prompt has the draft's conversation, offers always-allow and matches the draft key (`modalId` + rules); otherwise retires it. So B's prompt arriving does not retire A's draft; A's dismissal, replacement by a different offer, or a reconnect clear does.

### AppModule

- `thread(...)` passes `bundle.coordinator.hostModals` to `permissionDrafts.bind` and as `hostModal` (fallback `MutableStateFlow(HostModalState())`).

## State + concurrency model

- One new `StateFlow` on the coordinator's process-lived scope, `Eagerly`; the existing `currentModal` becomes a derived `Eagerly` `stateIn` on the same scope. Both cancelled by `close()`.
- `flatMapLatest` guarantees the reconnect marker for connection N+1 is emitted after collection of connection N's events is cancelled and before connection N+1's first frame is collected (the marker is the first emission of the new inner flow). Modal frames arrive only after the pump is `Open`, which is after `activeConnection` is published.
- VM: unchanged structure (`stateIn(viewModelScope, Eagerly, seeded)`).

## Error handling

No new failure modes. The fold is total over `ModalEvent` plus the reconnect marker; malformed frames are dropped upstream at decode. The answer path keeps its deterministic null-guard. No modal field is logged anywhere (the draft store's existing content-free `event=permission_grant_draft action=retired` line is the only log touched).

## Testing strategy

- `ModalUiStateTest` (unit, pure): port the existing reduce cases to `HostModalState.reduce`; add — two chats' prompts both held and each scoped to its own chat; dismissing one leaves the other; repeated `Shown` for a held id replaces in place keeping order; a dismissed id re-shown on the same fold is ignored; unknown dismiss is a no-op; `scopedTo` falls back to the conversation's most recent dismissal, else Hidden; `latestOutstanding`.
- `RelayRepositoryCoordinatorTest` (unit, stub pumps): a prompt held across a teardown stays until the next connection, then is dropped; a prompt the daemon re-sends on the new connection reappears; one it does not re-send stays gone; an id dismissed on connection 1 is re-shown when re-sent on connection 2; `currentModal` single view tracks the latest outstanding. Existing #492 modal tests stay green (the teardown-retain test still holds). Another host's prompts untouched: two coordinators in one test, reconnect one.
- `PermissionDraftStoreTest`: migrate to `HostModalState`; add "B's prompt arriving does not retire A's draft" and "A's dismissal retires A's draft but not B's".
- `ThreadViewModelTest`: existing cases keep their `StateFlow<ModalUiState>` inputs through a test-only adapter onto `HostModalState` (a synchronous `StateFlow` view, so `.value` reads stay exact). New: two VMs (chats A and B) over one host state both show their own prompt; answering in A sends A's id; A's dismissal leaves B open; each keeps its own don't-ask-again tick through a shared `PermissionDraftStore`.
- Rung-3 e2e: sibling method `interactiveTurn_permissionPrompts_heldPerConversation` on `InteractiveStreamE2ETest` — A and B each raise a real prompt; both show in their own chats; answering A from the phone dismisses A's id only and B's prompt is still shown; the peer then allows B's. Two real-claude turns. Accepted on the dispatcher's post-verifier live run. No rung-4 twin (not required by the ACs).
- No new Compose screen test: rendering is unchanged.

## Open questions

- None blocking. `latestOutstanding` uses list order, so a prompt replaced in place keeps its earlier position rather than becoming "most recent"; #1338 replaces the single view.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/current-modal-state.md` and `docs/knowledge/features/permission-modal-overlay.md` to say the host holds every outstanding prompt (keyed on `modalId`, scoped per conversation) and clears them on each reconnect (the teardown-retain section changes: teardown still retains, a new connection clears).

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — every input to the fold is a `ModalEvent` already decoded and capability-gated at the #437 boundary (`ModalShownPayloadDto` decode on `RemoteConversationRepository`); `HostModalState.reduce` copies fields verbatim and interprets none. Rendering stays the #446/#1306 inert-text path, unchanged.
- [Trust boundaries — wrong-conversation answer] No findings — a thread only ever sees `HostModalState.scopedTo(ownConversationId)`, which reuses `ModalUiState.scopedTo` (blank conversation matches nothing); `ThreadViewModel.onModalOption`/`onModalCancel` keep the synchronous `scopedModal()` read plus the `modalId` equality guard, so chat B can never send for A's prompt even with both held. A unit test answers in A with B also held and asserts A's id is sent.
- [Trust boundaries — re-surfacing an answered prompt] No findings — an id in `resolved` is ignored by `reduce(Shown)` for the rest of the connection; `resolved` is cleared only together with `outstanding` on a new connection, after which only the daemon's connect-time re-send (authoritative on what is still outstanding) can bring a prompt back. Tests cover both cases.
- [Trust boundaries — unbounded growth] OUT OF SCOPE (accepted) — `outstanding`/`resolved` are no longer bounded to one entry; a misbehaving daemon could grow them until the next reconnect. The sender is the authenticated paired daemon inside the Noise session (the relay cannot inject frames), the same trust as the unbounded message stream, and the lists are cleared on every reconnect. Desktop holds the same unbounded lists. No cap without an observed failure.
- [Tokens] No findings — no token, key or credential is created, stored or read.
- [File / storage] No findings — process memory only; `PermissionDraftStore` stays heap-only.
- [Android surface] No findings — no intent, deep link, pending intent, push or WebView change.
- [Crypto] No findings — no crypto change; per-connection pumps are untouched.
- [Network & I/O] No findings — no new frame, send or decode. Ordering: the reconnect marker is the first emission of the `flatMapLatest` inner flow for a new `Connection` (identity, not equality), so a stale prompt cannot survive into the new connection, and a new connection's frame cannot be folded before the clear. A frame emitted before the inner collector subscribes would be dropped (pre-existing `replay = 0` behaviour; fails closed: no stale prompt is shown).
- [Logs] No findings — no modal field is logged; the only log touched is the draft store's existing content-free `event=permission_grant_draft action=retired`.
- [Concurrency] No findings — the fold stays a single `scan` on the coordinator's process scope (`Eagerly`, cancelled by `close()`); no check-then-act across suspension. Teardown still retains held prompts while disconnected; the answer path's deterministic null-guard in `RelayRepositoryCoordinator.answerModal`/`cancelModal` keeps a tap on a held prompt from reaching a dead connection.
- [Threat model] Malicious relay: can drop/delay frames only; worst case a prompt is not shown or lingers until the next reconnect, never answered in the wrong conversation. Hostile daemon frame: decoded defensively upstream, rendered as text.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-01

## Revisions

### 2026-10-01 — the new live method joins the curated LIVE list

Driven by the verifier's re-review MUST FIX: the live run on `f62f1719` never executed `interactiveTurn_permissionPrompts_heldPerConversation`, because the method was on no list the live gate selects. New contract: the method is on the LIVE `TEST_TARGET` list in `scripts/e2e-emulator.sh`, `LIVE_MINIMUM` in `scripts/android-test-gate.py` rises by one to 41, and `test_live_floor_matches_the_curated_list` pins it. The PR body's `## Live tests` names it and the sibling `interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation`. No production behaviour changes; the same pass corrects four comments that still described #492's single retained modal and adds the missing pre-reconnect assertion in `hostModals_anotherHostsReconnectLeavesThisHostsPrompts`.

### 2026-10-01 — the held-prompts method no longer awaits A's turn end

Driven by the real-claude gate on `8ef507cd`: `interactiveTurn_permissionPrompts_heldPerConversation` failed with "A's allowed turn never ended within 90000 ms". A's prompt had been dismissed from the phone with `allow_once` (the daemon's audit log records the remote allow), so the phone fold was not at fault. The daemon streams turn frames only for the conversation a message was last routed to (its `activeConversation` follow-active cursor; `startStreamTurnDrainV2` drops every other session's events as `stream_turn.not_active`). B's send in step 2 moves the cursor to B, so A's `tool_use` and `turn_end` after the allow never reach any client. New contract: the method proves A's answer through the peer's `modal_dismissed` for A's id (source `remote`, outcome `allow_once`) and the dialog leaving A, and no longer awaits A's `turn_end`; B's `turn_end` is still awaited, since B holds the cursor. That an allowed turn ends is proven by the sibling `interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation`, where no other chat is routed. No production change.

### 2026-10-01 — a reconnect clear no longer retires the don't-ask-again draft; the push live guard is listed

Driven by the verifier's third review. SHOULD FIX: a background/foreground cycle unticked the box, because the reconnect marker emptied `hostModals` and `PermissionDraftStore.retireStale` retired every draft its host no longer held, though the daemon then re-sends the same `modal_id` with the same rules. New contract for `PermissionDraftStore`: a draft is retired once its `modalId` is in the host's `resolved`, or once its conversation holds outstanding prompts and none is that request with that offer. A conversation holding no prompt keeps its draft, so the reconnect clear alone retires nothing. A draft whose request never returns stays heap-only and invisible, because `ThreadViewModel.alwaysAllowAccepted` and `grantsAlwaysAllow` match it against the shown prompt's `modalId` and rules; at most one draft per conversation, and the next prompt in that conversation retires or overwrites it. `a_reconnect_clear_retires_the_hosts_drafts` becomes `a_reconnect_clear_keeps_the_draft_for_an_unchanged_resend`, plus `a_different_request_after_a_reconnect_clear_retires_the_draft`.

MUST FIX: `interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect` is the live guard on the push path, whose input (`coordinator.currentModal`) now goes `Open` → `Hidden` → `Open` across a reconnect, so `HostConversationSource` re-emits the alert and only `AttentionNotifier`'s ledger drops it. The PR's `## Live tests` now lists it and `interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool`; its two comments that described a retained modal are corrected. The first Revisions entry's "rises by one to 41" is superseded: the floor is counted from the curated list.
