# Modal answer flow — the fail-safe-deny answer/cancel behavior on the thread ViewModel

The **interaction/behavior half of the permission/choice-modal surface**: how an option tap or a cancel on
the open modal becomes an outbound `modal_answer` / `modal_cancel` over the live encrypted wire, with the
**fail-safe-deny single-tap / second-confirm** UX belt and a non-crashing error signal. Landed in
[#451](../codebase/451.md) (split from #444, the interaction half of #439), part of the Phase 3
permission-modal feature (epic pyrycode#597, ADR 025). This slice adds **no UI** — it drives the
`onModalOption` / `onModalCancel` screen hooks from the ViewModel and exposes the arm signal; the
**sibling render slice [#452](../codebase/452.md)** (`blockedBy` this) then **shipped** the render half that
draws the armed affordance off `armedOptionId` and wires these hooks into the route host so the taps go
live. [#1340](#local-close-on-tap-and-the-in-chat-rejection-notice-1340) later replaced #452's send-error
snackbar with the local close-on-answer behaviour and an in-chat rejection notice — see that section.

It is the fourth and final half of the modal family:

| Half | Slice | Doc |
|---|---|---|
| decode (`modal_shown`/`modal_dismissed` → `ModalEvent`) | [#437](../codebase/437.md) | [Modal events](modal-events.md) |
| project (`ModalEvent` → hoisted `currentModal`) | [#445](../codebase/445.md) | [Current-modal state](current-modal-state.md) |
| render (`currentModal` → overlay + dismiss snackbar) | [#446](../codebase/446.md) | [Permission-modal overlay](permission-modal-overlay.md) |
| **answer (taps/cancel → outbound send + arm + error)** | **#451** | **this doc** |

The concrete outbound send methods themselves ship in [#438](../codebase/438.md) (see
[Remote conversation repository § `answerModal` / `cancelModal`](remote-conversation-repository.md)); this
slice is the **glue** from the screen hooks to those methods.

## The data path

```
ThreadScreen onModalOption(modalId, optionId) / onModalCancel(modalId)   ◀── route host wires them to the VM (#452, #1306)
        │  (since #1306 the UI also passes the rendered request's modalId as a guard — never a target)
        ▼
ThreadViewModel.onModalOption / onModalCancel            ◀── reads modalId from scopedModal() (#816: hostModal filtered to this VM's own conversationId, read synchronously — not the collected currentModal)
        │  fail-safe-deny decision: default → answer ; non-default → arm → 2nd confirm → answer ; cancel
        ▼
sendAnswer / sendCancel  ──▶  recordModalAction(AnsweredHere(modalId))   ◀── #1340, before the send launches:
        │                          = coordinator::recordModalAction (AppModule) — the card leaves at once
        ▼
sendAnswer / sendCancel  ──▶  answerModal / cancelModal  (defaulted suspend lambdas)
        │                          = coordinator::answerModal / ::cancelModal  (AppModule)
        ▼
RelayRepositoryCoordinator.answerModal / cancelModal     ◀── #451 outbound passthrough (null-guard only)
        │  reaches the connection-scoped concrete repo via activeConnection.value?.repo
        ▼
RemoteConversationRepository.answerModal / cancelModal   ◀── #438 (mints answer_token, awaits ack/error, throws)
        │  RelayErrorException (daemon refused) ──▶ recordModalAction(Rejected(conversationId))   ◀── #1340
        │  IllegalStateException (unsent)        ──▶ nothing recorded; the next connection re-sends it
```

Two seams, both the **outbound mirror** of the inbound modal path: the coordinator passthrough mirrors #445's
`modalEvents` seam (a suspend *call*, not a `Flow`, because answer/cancel are request/reply control
messages), and the VM's defaulted suspend-lambda injection mirrors #445's defaulted `modalEvents` flow param.

## The fail-safe-deny belt — the one real behavior decision

The producer (pyrycode#716) ships **no per-option "destructive" marker**. Its safety design is the
fail-safe-deny `default_option_id`: the highlighted default is always the deny/safe option (`reject_once` for
`permission`, `exit` for `trust`), so a careless confirm denies rather than grants. The phone **never defines
a destructive vocabulary and never inspects option-id semantics** — it keys the second-confirm purely off
`Open.defaultOptionId` (carried verbatim through #445):

```kotlin
fun onModalOption(optionId: String, modalId: String? = null) {
    val open = scopedModal() as? ModalUiState.Open ?: return   // #816: this thread's own modal, read synchronously
    // #1306: a tap composed for a request that has since been replaced carries the old id.
    if (modalId != null && modalId != open.modalId) return
    when {
        // #818: the session-grant flag is computed at the point of sending, not stored on the arm.
        optionId == open.defaultOptionId -> sendAnswer(open.modalId, optionId, grantsAlwaysAllow(open, optionId))
        armedModalOption.value == ArmedModalOption(open.modalId, optionId) ->
            sendAnswer(open.modalId, optionId, grantsAlwaysAllow(open, optionId))        // 2nd confirm
        else -> armedModalOption.value = ArmedModalOption(open.modalId, optionId)       // (re-)arm, no send
    }
}

fun onModalCancel(modalId: String? = null) {
    val open = scopedModal() as? ModalUiState.Open ?: return
    if (modalId != null && modalId != open.modalId) return
    armedModalOption.value = null
    grantDrafts.set(serverId, conversationId, null)   // #1306: a cancel drops the session-grant draft too
    sendCancel(open.modalId)
}

// #816: reads the host flow directly rather than the collected `currentModal`, so a modal raised by
// another conversation can never be answered from this thread — not even in the instant before
// `currentModal`'s own stateIn catches up.
private fun scopedModal(): ModalUiState = hostModal.value.scopedTo(conversationId)
```

### Stale taps carry the wrong `modalId` (#1306)

Inline rendering removed the property a dialog window gave for free: a `BasicAlertDialog` recomposed as one
window, so the modal it drew from was always the one a tap could reach. As a `LazyColumn` row, a tap
composed against request `m1` can be delivered by the time `m2` has replaced it — with the pre-#1306
signatures (option id only), that tap would answer, cancel or grant **`m2`** in one shot, no second confirm
needed if `m2`'s default happened to be the tapped option. `ThreadScreen`'s `onModalOption` / `onModalCancel`
/ `onAlwaysAllowChanged` all gained the rendered item's `modalId` as a parameter for exactly this reason,
and the VM guards on it: `modalId != null && modalId != open.modalId` is a no-op, never a fallback to some
other request. The `modalId` argument is bound at the call site to the closure's own `open.modalId` — never
re-read at tap time — so the guard actually reflects what was on screen when the finger landed, not what is
open when the callback finally runs. The parameter defaults to `null` so a caller with no id (an existing
test, a preview) keeps today's unguarded behavior; only `ThreadScreen`'s inline items supply one.

| tap | result |
|---|---|
| the **default** option (`optionId == defaultOptionId`) | **single-tap** answer — sends immediately |
| a **non-default** option, first tap | **arms** it (`armedModalOption = (modalId, optionId)`); **no send** |
| the **same** armed option again | **second confirm** — sends |
| a **different** non-default option | **re-arms** to the new option; **no send** |
| cancel | clears the arm + sends `modal_cancel` |

The belt over-captures `reject_always` as needing a confirm, which is harmless (a deny variant). The
authoritative deny-on-timeout / first-answer-wins / per-device-grant enforcement is server-side
(pyrycode#702/#703/#717); this is the phone-side UX belt **only** — it can make the action *harder* (never
easier) than the wire allows.

### Not connected refuses the tap, before the arm or grant change (#1321)

A permission decision must not reach a closed socket believing it sent. `onModalOption` and `onModalCancel`
each gate on `promptSendAllowed(kind)` right after the #1306 stale-id guard and before anything else changes:
`onModalOption` before the default-send, the second-confirm send and the (re-)arm; `onModalCancel` before
`armedModalOption.value = null` and the grant-draft clear. A refused tap is a pure early return — the arm,
the grant draft and the rendered state are exactly as they were, so the same tap answers once the host
reconnects. `AlwaysAllowOffer` (the session-grant checkbox) is a local choice and is not gated.

`promptSendAllowed` reads a private `hostConnection: StateFlow<ConnectionState?>`, collected `Eagerly` in
`viewModelScope` and seeded `null` — never the optimistic `Connected` that the public `connectionState`
(used only for the banner) starts with. `null`, `Connecting`, `Reconnecting` and `Offline` all refuse; only
`ConnectionState.Connected` allows. This is deliberately a **second** eager connection collector alongside
[`connectedFor`](thread-composer-footer.md) (#1319, the composer/footer's own tap-time gate) rather than a
shared helper: `connectedFor` seeds optimistically `Connected` to match the footer's pre-#1319 behavior, and
changing that seed to fail closed would alter #1319's footer gating, which is out of this ticket's scope.
Merging the two was flagged by the verifier as a follow-up, not done here.

On the UI side, `permissionRequestItems`/`PermissionRequestCard`/`ModalOptionButton` and `ModalCancelButton`
all take a `connected: Boolean` that `ThreadScreen` derives the same way as the #1319 footer gate
(`connectionState == ConnectionState.Connected`) and pass straight to each control's `enabled`; a disabled
button never calls back into the VM, so a tap on a greyed-out option neither sends nor arms. The VM-side gate
is still required — the race this closes is the tap landing in the instant *after* the screen read
`connected = true` but *before* `ThreadViewModel` observes the drop.

## The arm state — transient, modalId-scoped, structurally stale-safe

The arm lives in a single private `MutableStateFlow<ArmedModalOption?>` (the private `data class
ArmedModalOption(modalId, optionId)`), exposed to #452 as a **scoped** derived sibling:

```kotlin
val armedOptionId: StateFlow<String?> =
    combine(currentModal, armedModalOption) { modal, arm ->
        if (modal is ModalUiState.Open && arm?.modalId == modal.modalId) arm.optionId else null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
```

- **Transient** — never persisted (no `rememberSaveable` / `SavedStateHandle`); it resets on resolve /
  cancel / re-tap.
- **modalId-scoped, so a stale arm is structurally inert** — `armedOptionId` derives `null` for any
  non-matching modal (a stale arm can't be *displayed*), and the second-confirm equality in `onModalOption`
  requires a modalId match (a stale arm can't *auto-confirm* a fresh modal). **No reactive arm-clear
  coroutine** — the scoping *is* the "resets on resolve" property. This is the deterministic guard (a plain
  equality), not a stochastic clear.
- **`Eagerly` matches `currentModal`** so `.value` is always the true projection and a resolve immediately
  nulls the affordance.
- **The arm clears on the send *attempt*** (`sendAnswer` nulls it *before* launching), not on success — the
  second-confirm gesture is consumed whether the send succeeds or fails. Through #1337, `currentModal`
  stayed `Open` until the daemon resolved it, so the user could answer again after a failure (no auto-retry
  — first-answer-wins is server-side). **Since [#1340](#local-close-on-tap-and-the-in-chat-rejection-notice-1340)
  the prompt closes with the arm, before the send even launches** — there is no answering again after a
  failure from this card; a refused answer surfaces as the chat's rejection notice, and an unsent one is
  re-sent by the daemon on the next connection.

### Leaving the conversation clears the arm, not the grant (#1306)

The arm stays exactly as transient as above — it lives on the VM and dies with it — but a thread's
`ThreadViewModel` now outlives its own screen: navigating to another conversation, or opening a second
thread on top of this one, disposes the Compose destination without clearing the VM. `onConversationLeft()`
is the explicit signal for that moment (`MainActivity`'s `DisposableEffect(vm) { onDispose {
vm.onConversationLeft() } }`, fired by Back and by pushing a new destination alike):

```kotlin
fun onConversationLeft() {
    armedModalOption.value = null
}
```

It nulls only the arm. Coming back to the same outstanding request always needs two fresh taps on a
non-default option — leaving never leaves a half-made "Allow" waiting to be confirmed by a later,
unrelated tap. The session-grant draft below is **not** touched here: it belongs to the request, not to the
visit, and the store that owns it retires it on its own terms (§ below).

## The session-grant draft (#818, moved to process lifetime in #1306)

A permission prompt can offer "don't ask again this session" (daemon #2364's `modal_shown.always_allow`,
decoded into [`ModalUiState.Open.alwaysAllowRules`](current-modal-state.md) and its derived
`offersAlwaysAllow`). Accepting the offer is a **separate, sibling state** to the arm above — it never arms
and never sends by itself; it only changes what `sendAnswer` carries on the answer that *does* send.

**#818 shipped it as a VM-private `MutableStateFlow<AcceptedAlwaysAllow?>`, so Back threw the tick away.**
[#1306](../../specs/architecture/1306-inline-permissions.md) moved it into `PermissionDraftStore`
(`ui/conversations/thread/PermissionDraftStore.kt`), an app-scoped singleton with the same process-lifetime
precedent as [`QuestionDraftStore`](question-batch-modal.md#batch-ownership-process-lifetime-drafts-source--and-request-bound-sends) and
[`ComposerDraftStore`](thread-screen-composer-drafts-and-attachments.md#composer-draft-ownership), so the
checkbox survives Back for the same outstanding request:

```kotlin
internal data class PermissionGrantDraft(val modalId: String, val rules: List<String>)

class PermissionDraftStore(dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate) {
    private val drafts = MutableStateFlow<Map<Pair<String, String>, PermissionGrantDraft>>(emptyMap())
    // bind(serverId, owner, modals) starts one collector per host that retires a draft once its request
    // resolves or its conversation shows a different one (see below); observe/current/set are keyed on
    // (serverId, conversationId); dispose() cancels everything.
}
```

`ThreadViewModel` takes an optional `permissionDraftStore: PermissionDraftStore? = null` constructor
parameter (a private store stands in for direct test/preview construction, mirroring `questionDraftStore`);
`AppModule`'s `single { PermissionDraftStore() } onClose { it?.dispose() }` supplies the real one, and
`ThreadDestinationFactory.thread` binds it to `bundle.coordinator.hostModals` beside the question-draft
binding. **[#1337](../../specs/architecture/1337-hold-every-outstanding-prompt.md) changed `bind`'s
`modals` parameter from `StateFlow<ModalUiState>` to `StateFlow<HostModalState>`** so the store follows
every outstanding prompt on the host, not the single most-recent one — see the retirement rule below for
why that matters.

```kotlin
val alwaysAllowAccepted: StateFlow<Boolean> =
    combine(currentModal, grantDrafts.observe(serverId, conversationId)) { modal, accepted ->
        modal is ModalUiState.Open && modal.offersAlwaysAllow && accepted == modal.alwaysAllowKey()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

fun onAlwaysAllowChanged(modalId: String, accepted: Boolean) {
    val open = scopedModal() as? ModalUiState.Open ?: return
    if (open.modalId != modalId || !open.offersAlwaysAllow) return
    grantDrafts.set(serverId, conversationId, if (accepted) open.alwaysAllowKey() else null)
}

private fun grantsAlwaysAllow(open: ModalUiState.Open, optionId: String): Boolean =
    optionId in ALWAYS_ALLOW_OPTION_IDS &&        // "allow_once" / "allow_always" only — never a deny
        open.offersAlwaysAllow &&
        grantDrafts.current(serverId, conversationId) == open.alwaysAllowKey()
```

- **Keyed on `(modalId, rules)`, not just `modalId`.** `alwaysAllowKey()` is `PermissionGrantDraft(modalId,
  alwaysAllowRules)`. A new prompt, a replaced prompt, or the *same* `modalId` re-shown with a different rule
  list all read as unaccepted by construction — the same modalId-scoping discipline as `armedOptionId`, one
  field wider.
- **Isolated by server, conversation, request identity and offer.** The map key is `(serverId,
  conversationId)`; the stored value additionally carries `(modalId, rules)`, so two conversations, two
  servers, or the same conversation's next request can never read each other's draft.
- **A bound host retires a draft once its request is resolved, or its conversation shows a different
  one — never on a reconnect clear alone.** `bind(serverId, owner, modals)` starts one `SupervisorJob`-scoped
  collector per host over the coordinator's `hostModals: StateFlow<HostModalState>`
  ([#1337](../../specs/architecture/1337-hold-every-outstanding-prompt.md) — before it,
  `currentModal: StateFlow<ModalUiState>`, the host's single most-recent prompt); on every emission,
  `retireStale` drops a draft for that server when private `keeps(modals, conversationId, draft)` is `false`:
  retired once the draft's `modalId` is in `modals.resolved`, or once the draft's conversation holds any
  `outstanding` prompt and **none** of them is `Open`, offers always-allow, and matches the draft's exact
  `(modalId, rules)` pair — so replacement by a different offer, resolution, or the same request re-offered
  with different rules all retire it, whether or not a thread for that conversation is open. **A conversation
  holding no `outstanding` prompt at all keeps its draft** — the rework that closed a verifier SHOULD FIX on
  #1337: the reconnect marker (§ [Current-modal state](current-modal-state.md#lifecycle-errors-edge-cases))
  empties `hostModals` on every new connection, and retiring on "not currently held" alone untucked the
  checkbox the instant the daemon's connect-time re-send of the same `modal_id` had not yet landed. A stale
  surviving draft can never grant more than intended: it is only read through `grantsAlwaysAllow`'s own
  `(modalId, rules)` equality check against the *shown* prompt, so it stays invisible until that exact
  request reappears, and the next prompt in that conversation either matches it or retires it.
  **Because the conversation-holds-prompts branch scans the whole `outstanding` list instead of one value, a
  second conversation's prompt arriving no longer retires the first's draft** — the original #1337 fix:
  before it, `retireStale` compared against the host's single current modal, so chat B's `modal_shown`
  evicted chat A's tick the instant it replaced A's entry in the old fold, even though A's prompt was still
  open. `bind` is idempotent per `owner` (the coordinator instance); a different owner cancels the old
  collector and clears that host's drafts before starting fresh. An **unbound** store (a test, the demo host)
  keeps every entry — the VM's own `(modalId, rules)` equality check in `grantsAlwaysAllow` still hides a
  stale one from actually being used, so an unbound store is inert-but-correct, never unsafe.
- **`onAlwaysAllowChanged`'s `modalId` argument is a guard, never a target.** It only stops a tap that lands
  after the rendered prompt was replaced from silently accepting the replacement's offer — the toggle can
  never accept a prompt other than the one currently open. This was a security-review MUST FIX on #818 and
  remains one under #1306's stale-tap guard above.
- **A deny never carries the grant**, even with the offer accepted — `grantsAlwaysAllow` requires `optionId`
  to be `allow_once` or `allow_always`. This matches the desktop's `confirmPrompt`, and the contract treats
  `true` as a no-op on a deny anyway, so sending it there would carry no information.
- **`onModalCancel` clears the draft** alongside the arm. **A send attempt does not** — `currentModal` stays
  `Open` until the daemon resolves it, so a retry after a failed send still carries the same accepted intent
  (mirrors the arm's own "clears on attempt, not on success" rule, just for a different field).
- **Accepting never arms and never sends.** Ticking the checkbox only moves the draft; it takes effect the
  next time `onModalOption` decides to send. This keeps the existing arm-then-confirm gesture for a
  non-default option completely unchanged — accepting the offer is not that second tap.
- **Heap only.** The draft lives in the store's `MutableStateFlow`; nothing here reaches `rememberSaveable`,
  `SavedStateHandle` or disk, so process death discards it exactly as before #1306 — only Back-and-reopen for
  the life of the process survives now, not a restart. Debug logs (`event=permission_grant_draft
  action=set|cleared|retired`) carry the action only, never a `modalId`, rule text or request text.
- The flag itself never carries rule bytes — the phone sends only a boolean (`ModalAnswerPayloadDto
  .alwaysAllow: Boolean?`, `null` on an ordinary answer, `true` set at all only when the answer is a grant);
  the daemon decides what "the rules it retained for this modal" means and grants them, never the phone.

## Local close on tap, and the in-chat rejection notice (#1340)

Through #452 a failed send surfaced as a one-shot `modalSendErrors` snackbar and the card otherwise stayed
open until the daemon's own `modal_dismissed` arrived — which, for the phone's own tap, the daemon names
`source: remote`, so the dismissal snackbar read "Resolved on another device" for an answer the user had
just given. [#1340](../../specs/architecture/1340-close-prompt-on-answer.md) closes the card **locally, at
once**, and replaces the snackbar with a rejection notice that lives in the chat until dismissed — following
desktop's `answerPrompt`/`cancelPrompt` (`modalResolution.ts`) and `reduceModal`'s `rejectionOwners`
(`modalPrompts.ts`).

The fold gains a second, local input (`ModalAction` — [Current-modal state §
2](current-modal-state.md#2-the-hostmodalstate-fold-1337--the-viewmodel-re-exposure) has the type and the
`reduce`/`reconnected` details):

```kotlin
sealed interface ModalAction {
    data class AnsweredHere(val modalId: String) : ModalAction   // this phone answered or cancelled modalId
    data class Rejected(val conversationId: String) : ModalAction        // the daemon refused that chat's answer
    data class RejectionDismissed(val conversationId: String) : ModalAction  // the user dismissed the notice
}
```

`RelayRepositoryCoordinator.recordModalAction(action)` folds one into `hostModals` synchronously (the same
`update {}` the wire collector uses), and `AppModule` hands it to `ThreadViewModel` as a new, by-name
constructor parameter `recordModalAction: (ModalAction) -> Unit = {}` (defaulted inert for direct
test/preview construction and the twenty-odd existing test files that build a VM positionally).

`sendAnswer` / `sendCancel` record `AnsweredHere(modalId)` **before** launching the send — the same point
the arm already clears (§ above) — so the card is gone before the daemon can possibly reply:

```kotlin
private fun sendAnswer(modalId: String, optionId: String, alwaysAllow: Boolean) {
    armedModalOption.value = null
    recordModalAction(ModalAction.AnsweredHere(modalId))
    viewModelScope.launch {
        try {
            answerModal(modalId, optionId, alwaysAllow)
        } catch (e: CancellationException) {
            throw e // MUST precede the typed catches: j.u.c.CancellationException extends ISE on the JVM
        } catch (e: RelayErrorException) {
            RelayLog.d { "event=permission_answer outcome=rejected" }
            recordModalAction(ModalAction.Rejected(conversationId))
        } catch (e: IllegalStateException) {
            RelayLog.d { "event=permission_answer outcome=unsent" }
        }
    }
}
```

`sendCancel` is the same shape minus `optionId`/`alwaysAllow`, and its `RelayErrorException` catch records
nothing — a refused cancel shows nothing, same as an unsent answer or cancel. Nothing is lost either way:
the daemon re-sends every still-outstanding prompt on the next connection (`protocol-mobile.md` § Reconcile
on (re)connect), so an answer that never left the phone just gets asked again. The debug logs are
content-free — a static outcome code only, never a `modalId`, `optionId`, conversation id or the exception
message.

`HostModalState.reduce(ModalAction.AnsweredHere)` removes the held prompt and appends a `resolved` entry
with `answeredHere = true`; `HostModalState.scopedTo` skips an `answeredHere` entry rather than returning it,
so the user's own tap raises **no** dismissal snackbar, and the existing `Shown`-ignores-a-resolved-id fold
row keeps a repeated `modal_shown` for it from reopening the card on this connection. The daemon's own later
`modal_dismissed` for the same id is already a no-op by the pre-existing "not held" rule — it has nothing
left to resolve. See [Current-modal state](current-modal-state.md#2-the-hostmodalstate-fold-1337--the-viewmodel-re-exposure)
for the fold table and the `takeUnless { it.answeredHere }` trap it documents (skipping the newest
resolution does not fall back to an older one).

**The rejection notice.** `ThreadViewModel.answerRejected: StateFlow<Boolean>` reads `conversationId in
hostModal.value.rejectedConversations`, seeded synchronously so a VM recreated for the same chat (Back and
reopen) sees an existing rejection immediately. `onAnswerRejectionDismissed()` records
`RejectionDismissed(conversationId)`. Because `rejectedConversations` lives in the coordinator's
process-scoped fold and — since #1340 — `HostModalState.reconnected()` keeps it instead of clearing it like
`outstanding`/`resolved`, the notice **survives a reconnect and leaving the chat**; only the user's own X or
the end of the pairing (coordinator `close()`) removes it. This is the one place the fold's per-connection
reset does **not** apply, and it is deliberate: a rejection is not a property of the connection that
produced it.

`ThreadScreen` renders the notice as the Default `NoticePill` (Figma
[347:6617](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6617), unshadowed) in the slot the
permission card occupied, keyed `"permission-rejection"`, counted in `promptRowCount`; the empty-thread
branch gives way to it the same way it already did for an open request. No frame draws the rejection itself
— the ticket's own instruction, since it reuses an existing component unchanged.

### Catch order is still load-bearing

The `catch (CancellationException) { throw e }` **must precede** the typed catches, unchanged by #1340. On
the JVM/Android `kotlinx.coroutines.CancellationException` is a `typealias` for
`java.util.concurrent.CancellationException`, which **`extends IllegalStateException`** — so without the
leading rethrow, the `IllegalStateException` catch would swallow coroutine cancellation (VM teardown
mid-send → `deferred.await()` throws `CancellationException`), break structured cancellation, and (pre-#1340)
would have fired a spurious error signal; post-#1340 it would instead record nothing extra, but the
cancellation would still fail to propagate, which is the actual bug this order prevents. This was the
[#451](../codebase/451.md) rework defect; see [[catch-illegalstate-swallows-cancellation]]. A broad
`catch (Exception)` / `catch (Throwable)` is forbidden for the same reason. A VM cleared mid-send (the
cancellation path) records nothing beyond the local `AnsweredHere` already recorded before `launch`.

## The coordinator passthrough

[`RelayRepositoryCoordinator`](relay-repository-coordinator-seams-and-passthroughs.md#outbound-modal-send-passthrough-451) gains two
**suspend** methods reaching the connection-scoped concrete repo through the coordinator's single
`activeConnection` source (`activeConnection.value?.repo`; [#493](../codebase/493.md) consolidated the former
`activeRemoteRepo` mirror into it) — the outbound mirror of the inbound `modalEvents` seam:

```kotlin
suspend fun answerModal(modalId: String, optionId: String, alwaysAllow: Boolean = false) {
    val repo = activeConnection.value?.repo ?: throw IllegalStateException("no active connection")
    repo.answerModal(modalId, optionId, alwaysAllow)
}
// cancelModal is the same, minus optionId (and never took alwaysAllow — a cancel can't carry a grant).
```

It needs **only the null-guard** — both not-connected paths funnel to `IllegalStateException`: when
`activeConnection.value == null` (between connections) the guard throws; when a connection exists but the
pump is pre-`Open`, the concrete `answerModal` → `sendAndAwaitReply` → `pump.send` returns false →
`IllegalStateException` already (the #438 precedent). A redundant `Open` gate would be needless complexity. A
server `error` propagates as `RelayErrorException` unchanged. **No log** — the `modalId`/`optionId` may name
a sensitive command/path.

`alwaysAllow` (#818) is a straight pass-through with a `= false` default, so `RelayConnectionRegistry
.answerModal`, which has no production caller, compiles unchanged and needed no edit.

## Wiring

`AppModule` resolves the coordinator once and binds the two send lambdas as suspend method references at the
`ThreadViewModel` factory — **no new Koin binding** (mirrors the `liveSessionEvents` / `modalEvents` args):

```kotlin
viewModel {
    val coordinator = get<RelayRepositoryCoordinator>()
    ThreadViewModel(
        get(), get(), get(), get(),
        coordinator.liveSessionEvents,
        coordinator.hostModals, // #1337: every outstanding prompt (was coordinator.currentModal, #492's single projection)
        // #818: a lambda, not a bare method reference, since the VM's answerModal now takes the grant.
        answerModal = { modal, option, grant -> coordinator.answerModal(modal, option, grant) },
        cancelModal = coordinator::cancelModal,
        // ..., // every other named ctor param unaffected
        // #1340: last ctor param, by name — keeps every positional test-file caller unshifted.
        recordModalAction = coordinator::recordModalAction,
    )
}
```

The two send-lambda ctor params default to no-ops (`{ _, _ -> }` / `{ _ -> }`) for direct test/preview
construction that omits them; `recordModalAction` (#1340) defaults to `{}` the same way, and is the
**last** constructor parameter rather than sitting beside `cancelModal`, specifically so the twenty-odd
existing test files that build a `ThreadViewModel` positionally do not have their argument lists shifted.
`AppModule` supplies the coordinator's send methods and `recordModalAction` in both real and demo builds;
the [repository build option](dependency-injection.md#how-it-works) does not gate them.
Taps on a VM with no open modal no-op via the `as? Open ?: return` guard.

## Edge cases / limitations

- **No modal open** — `onModalOption` / `onModalCancel` are no-ops (the `scopedModal() as? Open ?:
  return` guard — since #816 reading the host flow through `ModalUiState.scopedTo`, not the collected
  `currentModal`; see below), including tests that keep `currentModal` at `Hidden`.
- **Send failure** — since [#1340](#local-close-on-tap-and-the-in-chat-rejection-notice-1340) the card is
  already gone by the time a send can fail, so there is no re-answering from it. A daemon refusal
  (`RelayErrorException`) records a `Rejected` for the owning chat, shown as an in-chat notice until
  dismissed; an unsent answer or cancel (`IllegalStateException`) records nothing, and the daemon re-sends
  the still-outstanding prompt on the next connection. No auto-retry, no error-code interpretation, no
  read-only degrade (that is #440/#452), no daemon text in the notice.
- **VM teardown mid-send** — cancellation propagates cleanly (the rethrow); nothing is recorded beyond the
  local close that already happened before `launch`.
- **Stale `Open` across a plain disconnect, still persists; across a new connection, cleared since #1337,
  except rejection notices (since #1340)**
  (deferred from #445/#446, revised by [#1337](../../specs/architecture/1337-hold-every-outstanding-prompt.md)
  and [#1340](../../specs/architecture/1340-close-prompt-on-answer.md)) —
  a connection *drop* alone (`activeConnection → null`) still pushes no "clear" event, so a prompt held at
  that moment persists exactly as before. Answering a held prompt that the daemon has in fact already
  resolved is rejected server-side (stale `modalId`) and now shows the rejection notice regardless, so that
  backstop was never conditional on this projection. What #1337 added: when `activeConnection` switches to a
  **new** connection, the coordinator's `hostModals` fold resets its outstanding/resolved prompts before
  that connection's first `modal_shown` is collected (see [Current-modal state §
  Lifecycle](current-modal-state.md#lifecycle-errors-edge-cases)), and only the daemon's connect-time
  re-send of every still-outstanding prompt (`protocol-mobile.md` § Reconcile on (re)connect) brings a prompt
  back — one it does not re-send stays gone, including a prompt this phone itself just answered. What #1340
  adds on top: the reset (`reconnected()`) keeps `rejectedConversations`, so a rejection notice is **not**
  one of the things a reconnect clears — only the user's X, or the end of the pairing, clears it. A proactive
  stale-clear on a *plain* disconnect remains a UX nicety, not a correctness requirement, for the same reason
  as before: the daemon validation is the deterministic backstop.
- **Scoped to this thread's conversation (#816), not app-level.** The coordinator's fold holds every
  outstanding prompt per host, keyed on `modalId` ([#1337](../../specs/architecture/1337-hold-every-outstanding-prompt.md)
  — before it, one modal per host), but `onModalOption` / `onModalCancel` read it through a private
  `scopedModal()` helper (`hostModal.value.scopedTo(conversationId)`) rather than through the collected
  `currentModal`, so a tap in one thread can never answer a modal raised by another conversation on the
  same host — not even in the instant before `currentModal`'s own `stateIn` catches up, and (since #1337)
  not even while that other conversation's own prompt is simultaneously held. See [Current-modal
  state](current-modal-state.md).

## Testing

Unit only (`./gradlew testDebugUnitTest --tests "…ThreadViewModelTest"` /
`"…RelayRepositoryCoordinatorTest"` / `"…PermissionDraftStoreTest"`; bare `test --tests` is rejected —
[[gradle-single-test-class-task]]). No instrumented test (no UI). `ThreadViewModelTest` drives an `Open`
modal by setting the injected `StateFlow<ModalUiState>`'s `.value` directly (via the `vmWithModal` /
`openModal(...)` helpers — renamed in [#492](../codebase/492.md) from the pre-hoist `vmWithModalEvents` /
`modalShown` that emitted a raw `ModalEvent.Shown`), captures the send path with recording lambdas
(`vmWithModalSendPath`), and asserts `armedOptionId.value` + (since #1340) `currentModal`/`answerRejected`
rather than the removed `modalSendErrors`: default→answer, non-default→arm, second-tap→send+clear,
re-tap→re-arm, cancel→cancel+clear, stale-arm scoping, inert-with-no-modal, and a
`scopeCancellationMidSend...` regression (hosts the VM in a real `ViewModelStore`, suspends a send on a
never-completing deferred, `store.clear()`s the scope, asserts no rejection is recorded after the local
close). `RelayRepositoryCoordinatorTest` mirrors the `register_push_token` quartet for the passthrough
(delegate-over-active-connection + no-connection-throws), driven with `runCurrent()`
([[remote-repo-test-runcurrent-not-advanceuntilidle]]), plus (#1340) a case showing `recordModalAction`
updates `hostModals.value` synchronously and a rejection survives a reconnect that brings a re-sent prompt
back.

**#1340 added:** `ModalUiStateTest` cases for `HostModalState.reduce(ModalAction)` (an `AnsweredHere` for a
held id closes it and is ignored by a later `Shown`/wire `Dismissed`; an unknown id is unchanged; `Rejected`/
`RejectionDismissed` add/remove, a blank owner ignored) and for `reconnected()` (keeps rejections, drops
outstanding/resolved, so a re-sent `Shown` returns); `ThreadViewModelTest` cases for the default tap, the
armed second tap and Cancel each closing the prompt while the send is still in flight (gated on a
`CompletableDeferred`), a refused answer setting `answerRejected` only for the owning chat and surviving a
reconnect and VM recreation until `onAnswerRejectionDismissed`, a refused cancel and an unsent answer/cancel
recording nothing, and cancellation mid-send recording no rejection; and `ThreadScreenModalTest` cases for
the rejection pill (text + X calling `onDismissAnswerRejection`) appearing, including in an otherwise empty
thread, and for nothing appearing when there is no rejection. The `destinationBindingsKeepCollidingIdsOnTheirHostAcrossSelectionAndReconnect`
case in `RelayConnectionFactoryTest` (DI-level, not in this doc's own test classes) needed a second prompt
raised between an answer and a cancel on the same host, since the first prompt's cancel now has nothing open
to act on — a caller the #1340 plan's own testing strategy did not name, caught only by the full
`./gradlew check`, not a `--tests`-filtered run.

`ThreadViewModelTest` (#818) extends the same recording-lambda pattern to a `Triple(modalId, optionId,
alwaysAllow)`: accept-then-allow (default tap and the armed second confirm) sends `true`; allow without
accepting, accept-then-reject, and accept-then-cancel all send `false`; a replaced `modalId` or the same
`modalId` re-shown with different rules reads unaccepted; a toggle carrying a stale `modalId` is ignored; and
accepting never arms or sends by itself. `RemoteConversationRepositoryTest` covers the decode
(`toAlwaysAllowRules`, offered/malformed/oversized/over-count cases) and the encode (`alwaysAllow = true`
adds the wire key; the default call stays exactly the three original keys).

**#1306 added four `ThreadViewModelTest` cases (207/207 total) and a new `PermissionDraftStoreTest`
(7/7).** The VM cases: a `PermissionGrantDraft` shared through a common `PermissionDraftStore` survives VM
recreation (Back and reopen) and the recreated VM's allow carries it; `onConversationLeft` clears the arm so
an allow after returning needs two fresh taps (the grant itself is untouched); a stale `modalId` on
`onModalOption` / `onModalCancel` is ignored once the scoped modal has moved on; and a replaced request
never inherits the previous one's grant. `PermissionDraftStoreTest` covers isolation by server and
conversation, a bound collector retiring a draft on replacement / dismissal / a changed rule list / an owner
rebind, and the matching request keeping its draft through all of that. `ThreadScreenModalTest` (31/31 at
\#1306; 33/33 after #1340 added the rejection-pill cases above — see [Permission-modal overlay —
testing](permission-modal-overlay-testing.md)) covers the render side: no dialog, an empty thread, live
Back, the history anchor across arrival and the grant toggle, and the two-tap flow driven through the
rendered `modalId`.

## Related

- [#451 implementation notes](../codebase/451.md) — files, line refs, the rework lesson.
- [#818 architecture doc](../../specs/architecture/818-permission-always-allow.md) and
  [PR #903](https://github.com/pyrycode/pyrycode-mobile/pull/903) — the always-allow session grant: the
  `acceptedAlwaysAllow` state, the `onAlwaysAllowChanged` guard, and the security review that added it.
- [Current-modal state](current-modal-state.md) ([#445](../codebase/445.md)) — the hoisted `currentModal` /
  `Open.defaultOptionId` this reads at tap time; the projection half.
- [Permission-modal overlay](permission-modal-overlay.md) ([#446](../codebase/446.md) base + [#452](../codebase/452.md)
  live + [#1306](permission-modal-overlay.md) inline) — the render of the request + dismiss snackbar; the
  render slice **#452** extended it with the armed affordance + Cancel button + (removed by #1340) send-error
  snackbar + tapjacking net and wired these VM hooks (`vm::onModalOption` / `vm::onModalCancel`) +
  `armedOptionId` into the route host; [**#818**](permission-modal-overlay.md#the-always-allow-offer-818)
  added `AlwaysAllowOffer`, which reflects this doc's `alwaysAllowAccepted` the same way the options reflect
  `armedOptionId`; **#1306** moved the whole render out of its dialog window into the conversation's message
  stream and widened every decision callback to carry the rendered `modalId`; **#1340** replaced the
  send-error snackbar with the rejection-notice `NoticePill` described above.
- [Question batch modal § Batch ownership](question-batch-modal.md#batch-ownership-process-lifetime-drafts-source--and-request-bound-sends)
  — the process-lifetime, app-scoped draft-store precedent `PermissionDraftStore` follows for the
  session-grant checkbox.
- [Remote conversation repository § `answerModal` / `cancelModal`](remote-conversation-repository.md)
  ([#438](../codebase/438.md)) — the concrete outbound send methods the passthrough delegates to.
- [Relay repository coordinator § Outbound modal-send passthrough](relay-repository-coordinator-seams-and-passthroughs.md#outbound-modal-send-passthrough-451)
  — hosts the passthrough; the outbound mirror of its [§ Modal event seam](relay-repository-coordinator-seams-and-passthroughs.md#modal-event-seam-445-and-the-hoisted-currentmodal-fold-492).
- [Modal events](modal-events.md) ([#437](../codebase/437.md)) — the upstream decode seam.
- [Thread screen](thread-screen.md) — the `ThreadViewModel` host; `armedOptionId` / `answerRejected` (#1340,
  replacing `modalSendErrors`) join `currentModal` / `isThinking` / `isStalled` / `navigationEvents` as
  VM-exposed signals.
- Sibling slices: [**#452**](../codebase/452.md) the render of the armed/second-confirm affordance +
  snackbar + route-host forward (shipped) · **#440** read-only device mode (`blockedBy` #452).
- Producer SSOT: pyrycode#716 (fail-safe-deny `default_option_id`, no per-option destructive marker), #702
  (per-device answer gate) / #703 (first-answer-wins) / #706 (stale-id reject); ADR 025 § Phase 3 modals,
  EPIC pyrycode#597.
