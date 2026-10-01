# Permission-modal overlay — the render half of the permission/choice modal

The **render half of the permission/choice-modal UI surface**: how the hoisted
[`currentModal`](current-modal-state.md) state is drawn inside the owning conversation's own message stream,
and how its resolution is surfaced on dismissal. **[#1306](../../specs/architecture/1306-inline-permissions.md)
moved this from a blocking dialog window into three `LazyColumn` items** in the conversation's scrollable
history — the reader can go Back, scroll older messages or switch conversations without answering or
cancelling; see § *What #1306 moved* below. Everything else on this page that predates #1306 (the fail-safe-deny
highlight, the decision context, the always-allow offer) is otherwise unchanged in substance — only the
container changed. Landed in
[#446](../codebase/446.md) (split from #443, the render half of #439), part of the Phase 3 permission-modal
feature (epic pyrycode#597, ADR 025). The state/projection half it consumes is the sibling slice
[#445](../codebase/445.md) (blocked this one); **answering / cancelling** the modal — wiring the
option-tap / cancel hooks to outbound `modal_answer` / `modal_cancel` sends — is the sibling slice **#444**,
split into the behavior half [#451](../codebase/451.md) ([shipped](modal-answer-flow.md) — the VM
fail-safe-deny decision logic + outbound send) and the render half [**#452**](../codebase/452.md)
(**shipped** — the armed/second-confirm affordance + Cancel button + error snackbar + tapjacking net +
route-host forward, which **made these taps live**); a read-only device without remote-permission rights is
**#440** (`blockedBy` #452).

This doc covers the **complete render surface**: the base overlay shipped in [#446](../codebase/446.md)
(render-only — taps inert, no answer wiring), and [#452](../codebase/452.md) then made it **live** (drew the
armed second-confirm affordance, added the Cancel button + send-error snackbar + a tapjacking net, and wired
the route-host hooks to the VM). Throughout it is **render-only with no decision logic**: a pure function of
`(modalState, armedOptionId)` plus a payload-free error event, with no VM change, no new public type, and no
data-layer change. It owns the render-time obligations [#445](../codebase/445.md) / [#451](../codebase/451.md)
deferred to it — **output-encoding** (inert text), **screen-capture hardening** (own-window `FLAG_SECURE`),
and (since #452, now that taps are live) **tapjacking** + **send-error confidentiality**.

## What #452 made live

[#446](../codebase/446.md) drew the overlay but left the option-tap / cancel hooks **inert** and the overlay
non-dismissable. [#452](../codebase/452.md) closed the interaction loop, consuming [#451](modal-answer-flow.md)'s
two signals **verbatim — no UI-side re-derivation**:

- **Armed second-confirm affordance.** `ModalOptionButton` became a 3-way: the VM's armed non-default option
  (`armedOptionId`, a new `String?` param threaded from the route host) renders as a `FilledTonalButton`
  (kept **below** the default's filled emphasis so the safe default stays dominant) + a
  `stateDescription = modal_armed_option_desc` ("Tap again to confirm") marker. The UI computes
  `isArmed = option.id == armedOptionId` — a plain `==` against the VM's already-scoped projection; it never
  re-derives the arm. Every tap forwards verbatim via `onClick = { onOption(option.id) }`; the
  single-tap-default / second-confirm gate stays entirely in the VM ([#451](modal-answer-flow.md)).
- **Explicit Cancel.** A low-emphasis `TextButton` below the options — the **only** path to `onModalCancel`
  (back-press / outside-tap dismissal stay disabled, see below). Since #815 this moved into the shared
  `MobileGateModal` footer as an outlined `ModalCancelButton`; since #1306 it renders as its own inline
  `ModalCancelButton` item — see § The inline request.
- **Send-error snackbar.** A failed `modal_answer` / `modal_cancel` surfaces on the existing
  `snackbarHostState` via a **fixed local string** `modal_send_failed` — see § Send-error confidentiality.
- **Tapjacking net.** Now that the taps are live, `filterTouchesWhenObscured = true` on the dialog's own
  window — see § Security.
- **Route-host wiring.** `MainActivity` collects `vm.armedOptionId`, forwards it + `vm.modalSendErrors`, and
  binds `onModalOption` / `onModalCancel` to `vm::onModalOption` / `vm::onModalCancel`.

## What #1306 moved

[#1306](../../specs/architecture/1306-inline-permissions.md) replaced the `BasicAlertDialog` (wrapped since \#815
in [`MobileGateModal`](mobile-modal.md#the-hardened-gate-mobilegatemodal)) with three `LazyListScope`
items drawn directly inside `ThreadScreen`'s message list, following the #1305 question-batch precedent
([Question batch modal](question-batch-modal.md)) almost exactly:

- **Container.** `permissionRequestItems` replaces `PermissionModalOverlay`, emitting (under the list's
  reverse layout, newest end first) Cancel, the card, then the title — see § The inline request below. The
  request also renders in an **empty thread** (the `EmptyThreadState` branch now also gates on
  `openRequest == null`) and no longer blocks Back, chat switching or history scrolling.
- **Hardening moved from the dialog window to the activity surface.** `FLAG_SECURE` and
  `filterTouchesWhenObscured` were the dialog's own window properties; with no dialog, the thread mounts
  [`QuestionPromptProtection`](question-batch-modal.md#rendering) — the #1305 shared-owner guard — for as
  long as a request is open, including while it is scrolled offscreen. See § Security below.
- **Every decision callback now carries the rendered request's `modalId`.** `onOption(modalId, optionId)` /
  `onCancel(modalId)` / `onAlwaysAllowChanged(modalId, accepted)` all forward the id the composed item was
  drawn for, and `ThreadViewModel` rejects a mismatch — closing a real stale-tap hole a dialog's one-window
  recomposition used to close for free. See [Modal answer flow § Stale
  taps](modal-answer-flow.md#stale-taps-carry-the-wrong-modalid-1306).
- **The session-grant checkbox moved to process lifetime.** It used to live on the VM and die on Back; it
  now lives in an app-scoped `PermissionDraftStore`, so returning to the same outstanding request for the
  life of the app process restores the tick. See [Modal answer flow § The session-grant
  draft](modal-answer-flow.md#the-session-grant-draft-818-moved-to-process-lifetime-in-1306).
- **Leaving the conversation still clears an armed non-default option.** `ThreadViewModel.onConversationLeft()`,
  fired by `MainActivity`'s `DisposableEffect` on Back or on opening another thread, nulls the arm only —
  never the request, never the grant draft. Returning to allow needs two fresh taps.
- **`ModalOptionButton`, `AlwaysAllowOffer`, `PermissionContext` and `dismissReasonText` are unchanged.** The
  fail-safe-deny highlight, the armed second-confirm marker, the decision context and the always-allow offer
  all render exactly as before — only their container moved.

## Where it lives

`permissionRequestItems`, `PermissionRequestCard`, `ModalOptionButton`, `AlwaysAllowOffer` (#818) and
`dismissReasonText` live in
`app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadPermissionModal.kt`. Since
[#1306](../../specs/architecture/1306-inline-permissions.md) the request is no longer a dialog: it renders
directly inside `ThreadScreen`'s `LazyColumn`, and [`MobileGateModal`](mobile-modal.md#the-hardened-gate-mobilegatemodal)
is no longer its container (that shell still gates `CreateChatModal`). The process-lifetime session-grant
draft lives in the new `ui/conversations/thread/PermissionDraftStore.kt`, bound per host by
`ThreadDestinationFactory.thread` in `di/AppModule.kt` alongside `QuestionDraftStore`. The state type is
[`ModalUiState`](current-modal-state.md) (`data/model/ModalUiState.kt`, from #445 — moved from
`ui/conversations/thread/` to `data/model` in [#492](../codebase/492.md) when the fold hoisted to the
coordinator, so `ThreadScreen` now imports it). Collected in the route host at
[`MainActivity.kt`](thread-screen.md#destination-block). Strings in `res/values/strings.xml`
(`modal_*`). See [Thread screen § list and status row](thread-screen-how-it-works-list-and-status-row.md#inline-permission-rows-and-the-shared-reveal-1306)
for where the items sit among the message rows, and [Thread screen § overlays](thread-screen-how-it-works-overlays-and-app-bar.md#permission-modal-placement-post-446-moved-inline-in-1306)
for what still reaches the old `Scaffold`-sibling `when` block (`Dismissed` only).

## The render path

```
ThreadViewModel.currentModal : StateFlow<ModalUiState>      ◀── #445 fold (host-level, modalId-keyed; #1337 holds every outstanding prompt, not one), scoped to this thread's own conversation since #816
ThreadViewModel.armedOptionId : StateFlow<String?>          ◀── #451 arm projection (VM-scoped)
ThreadViewModel.alwaysAllowAccepted : StateFlow<Boolean>    ◀── #818 offer-acceptance projection, now backed by PermissionDraftStore (#1306)
ThreadViewModel.modalSendErrors : Flow<Unit>                ◀── #451 payload-free one-shot
        │  MainActivity: collectAsStateWithLifecycle(currentModal, armedOptionId, alwaysAllowAccepted) like isThinking/isStalled;
        │  modalSendErrors forwarded BY REFERENCE (single-consumer — collected in ThreadScreen, #452);
        │  DisposableEffect(vm) { onDispose { vm.onConversationLeft() } } clears the arm on the way out (#1306)
        ▼
ThreadScreen(state, …, modalState = Hidden, armedOptionId = null, modalSendErrors = emptyFlow(),
             alwaysAllowAccepted = false, onAlwaysAllowChanged = { _, _ -> },
             onModalOption = { modalId, optionId -> vm.onModalOption(optionId, modalId) },
             onModalCancel = { modalId -> vm.onModalCancel(modalId) })   ◀── all live since #452/#818/#1306
        │  LaunchedEffect(modalSendErrors){ collect → snackbar(modal_send_failed) }   (#452 error collect)
        │  val openRequest = modalState as? ModalUiState.Open   (#1306)
        │  LazyColumn(reverseLayout = true) { openRequest?.let { permissionRequestItems(it, ...) } ; … }
        │  when (modalState):   ── the request itself no longer reaches this block (#1306)
        ├─ Open      → Unit                                                  -- rendered inline above instead
        ├─ Dismissed → LaunchedEffect(modalId) { snackbarHostState.showSnackbar(dismissReasonText(source)) }
        └─ Hidden    → Unit
```

`modalState` / `armedOptionId` / `modalSendErrors` / `alwaysAllowAccepted` / `onAlwaysAllowChanged` are all
**defaulted** parameters (`= Hidden` / `= null` / `= emptyFlow()` / `= false` / `= { _, _ -> }`), so every
existing preview and androidTest call site stays inert — no call-site cascade. The
route host collects `currentModal` + `armedOptionId` + `alwaysAllowAccepted` exactly like the `isThinking` /
`isStalled` collect-and-forward and binds `onModalOption` / `onModalCancel` / `onAlwaysAllowChanged` to the
VM. [#451](modal-answer-flow.md) added the VM's decision methods (the answer/cancel + arm logic); the render
slice [**#452**](../codebase/452.md) forwarded those screen hooks to `vm::onModalOption` /
`vm::onModalCancel` in the route host and threaded `armedOptionId` into the overlay to draw the armed
affordance; [**#1306**](../../specs/architecture/1306-inline-permissions.md) widened both hooks to
two-argument lambdas that pass the rendered `modalId` alongside the tap, so a tap composed before a
replacement cannot reach it (see [Modal answer flow § Stale
taps](modal-answer-flow.md#stale-taps-carry-the-wrong-modalid-1306)). `ThreadScreen` still collects
`modalSendErrors` internally (not the route host) because the snackbar — its effect — is a screen concern,
while `navigationEvents` stays in `MainActivity` because navigation is a host concern. `modalSendErrors` is a
single-consumer `Channel.receiveAsFlow()`, so `MainActivity` only forwards the reference.

Host-level by construction: the coordinator's fold holds **every outstanding prompt** on a host in one
[`HostModalState`](current-modal-state.md#2-the-hostmodalstate-fold-1337--the-viewmodel-re-exposure), keyed on `modalId`
([#1337](../../specs/architecture/1337-hold-every-outstanding-prompt.md) — before it, the fold held a single
modal and a second chat's prompt replaced the first's). `ThreadViewModel` filters the host's prompts down to
its own conversation (`HostModalState.scopedTo`, driven by `Shown.conversationId` — see [Modal
events](modal-events.md)) before this overlay ever sees them, so the overlay only draws in the thread whose
conversation raised a given prompt — never in a second open thread for another conversation on the same
host, and (since #1337) never pre-empted by a second chat's prompt arriving: chat A's card stays mounted
while chat B raises and even resolves its own.

## The inline request (`Open`)

Since [#1306](../../specs/architecture/1306-inline-permissions.md), `permissionRequestItems(open:
ModalUiState.Open, armedOptionId, onOption, onCancel, alwaysAllowAccepted, onAlwaysAllowChanged, gutter)` is
an `internal fun LazyListScope.` extension that emits three keyed items directly into `ThreadScreen`'s
message `LazyColumn` — no dialog, no `MobileGateModal`. Under the list's `reverseLayout = true`, items are
emitted **newest end first**: Cancel (`permission-cancel:$modalId`), the card
(`permission-card:$modalId`), then the title (`permission-title:$modalId`). `MobileGateModal` — the #815
hardened dialog shell — is no longer this surface's container; it still gates `CreateChatModal`. Every
server string renders through plain `Text` bounded by `MAX_PERMISSION_TEXT` (8192 chars). The option count is
variable (`permission` = 4, `trust` = 2), so all options render uniformly in a `Column` to preserve array
order and a single highlight path.

```kotlin
internal fun LazyListScope.permissionRequestItems(
    open: ModalUiState.Open,
    armedOptionId: String?,
    onOption: (modalId: String, optionId: String) -> Unit,
    onCancel: (modalId: String) -> Unit,
    alwaysAllowAccepted: Boolean,
    onAlwaysAllowChanged: (modalId: String, accepted: Boolean) -> Unit,
    gutter: Modifier,
) {
    item(key = "permission-cancel:${open.modalId}") {
        Box(gutter.testTag("permission-request-cancel")) {
            ModalCancelButton(label = stringResource(R.string.modal_cancel), onClick = { onCancel(open.modalId) })
        }
    }
    item(key = "permission-card:${open.modalId}") {
        Box(gutter) { PermissionRequestCard(open, armedOptionId, onOption, alwaysAllowAccepted, onAlwaysAllowChanged) }
    }
    item(key = "permission-title:${open.modalId}") {
        Box(gutter) { Text(text = open.title.take(MAX_PERMISSION_TEXT), style = MaterialTheme.typography.titleMedium) }
    }
}
```

`PermissionRequestCard` — the #1305 question card's own container (`background` fill, 1dp
`primaryContainer` border, `modalControl` shape, 16dp padding) — holds the verbatim prompt, the decision
context, the always-allow offer and the options, every tap forwarding `open.modalId` alongside it:

```kotlin
Text(text = open.prompt.take(MAX_PERMISSION_TEXT), style = MaterialTheme.typography.bodyLarge)
if (!open.context.isEmpty) PermissionContext(open.context)             // #817, only when the frame carried any
if (open.offersAlwaysAllow)                                            // #818, only when the daemon offered it
    AlwaysAllowOffer(open.alwaysAllowRules, alwaysAllowAccepted, onChanged = { onAlwaysAllowChanged(open.modalId, it) })
open.options.forEach { option ->          // wire array order = canonical display/selection order
    ModalOptionButton(option.label.take(MAX_PERMISSION_TEXT),
        isDefault = option.id == open.defaultOptionId,
        isArmed   = option.id == armedOptionId,             // #452: reflects the VM's armed option
        onClick   = { onOption(open.modalId, option.id) })  // every tap forwards verbatim, with the request id
}
```

`open.modalClass` is **not** branched on — there is no per-class layout; the `title` / `prompt` already
carry the human text (the class is carried but unused here, available to the later visual spec).

### The fail-safe-deny highlight + the armed affordance

`ModalOptionButton(label, isDefault, isArmed, onClick)` is a stateless 3-way (`isArmed` taking precedence),
extended from #446's 2-way by [#452](../codebase/452.md):

| State | Button | Marker |
|---|---|---|
| `isArmed` — the VM's armed non-default, awaiting its second confirm (#452) | `FilledTonalButton` (kept **below** the default's filled emphasis so the safe default stays dominant) | `stateDescription = modal_armed_option_desc` ("Tap again to confirm") |
| `isDefault` — the fail-safe-deny default | high-emphasis filled `Button` | `stateDescription = modal_default_option_desc` ("Default") |
| neither — a resting non-default | `OutlinedButton`, with primary text and a primary 1 dp border, matching [the shared action palette](mobile-modal.md#layout-and-theme) and footer Cancel | none (a first tap arms it via the VM) |

The dark button reference gives the default and resting options 6 dp corners, body-large medium labels,
and a 40 dp visible action inside a 48 dp touch region. Full-width option labels take the available row
width and wrap. Exact text-layout checks use Robolectric native graphics: its default graphics mode
reported a clipped long label as one line. The armed tonal treatment stays below the filled default.
That move also changed the surface the armed button sits on, from `surfaceContainerHigh` to
`primaryContainer`: in the static light scheme the armed `FilledTonalButton`'s `secondaryContainer` fill
(`#D6E4F7`) now sits close to the container colour (`#CFE4FF`), so on screen the armed option reads closer
to a resting option than before, though the `stateDescription` marker (the load-bearing, testable contract)
is unaffected. #815's verifier review flagged this as non-blocking and worth fixing before or alongside its
three sibling tickets — no ticket owns the fix yet.

The **fail-safe-deny highlight** — the producer (pyrycode#716) marks **no** option "destructive"; its safety
design is that the `defaultOptionId` is always the deny/safe option (`reject_once` / `exit`), so the visually
prominent button is the **safe** one and a careless confirm denies rather than grants. The highlight is
driven **solely** by `option.id == open.defaultOptionId`, and `isArmed` **solely** by
`option.id == armedOptionId` — a plain `==` against the VM's already-scoped projection. **No option-id
semantics are interpreted, and the composable holds no `remember`-based arm** (the arm lives on the VM,
[#451](modal-answer-flow.md); the render only reflects `armedOptionId`). The visible armed cue (the
`FilledTonalButton` emphasis) is **design-owed**; the load-bearing testable contract is the
`stateDescription` marker + the two-tap behavior.

Both markers are **accessible + test-observable** (a screen reader announces them; the screen test locates
the armed / default option by the `stateDescription` value, **not** by fragile colour inspection). Only the
local markers are added — the verbatim server `label` stays the sole server text, rendered through plain
`Text`.

### The decision context (#817)

`open.context : ModalContext` ([Modal events](modal-events.md#the-four-decision-context-fields-817)) is
claude's own optional decision context for a permission ask — `reason`, `reasonType`, `blockedPath`,
`description`, each `null` when the frame carried none. `PermissionContext(context)` is a private
composable, drawn between the prompt and the option `Column` **only when `!context.isEmpty`** — a
context-free modal renders exactly as before #817, with no empty area or extra spacing left behind. It is a
`Column` (12 dp spacing, the `533-2369` frame's content-row gap) of up to three `ModalContextRow(label,
value)` rows in the desktop's order — reason, description, blocked path — each the frame's "Input large"
stacked shape (label above value, 8 dp gap) rather than its single-line read-only row, because a reason or
description is prose an ellipsis would hide. A row merges its semantics so a screen reader reads label and
value as one node.

The reason row is the only one with label logic, since `reasonType` is an open vocabulary the daemon can
extend: `classifier` and `rule` map to a local sentence (`modal_context_reason_classifier` /
`_reason_rule`), any other non-null value formats the local `modal_context_reason_type` string around the
**raw** value (never dropped — an unrecognised category still renders as `Reason type: <raw>`), and `null`
falls back to the bare `modal_context_reason` label. The row appears when either `reason` or `reasonType` is
non-null, and renders the label alone when only the type arrived. The description and blocked-path rows are
unconditional on their own field. Every value is plain `Text(String)` — no `MarkdownText`, no
`AnnotatedString` link handling, no `SelectionContainer`, no `remember`/`rememberSaveable` — the same
render-time obligations the rest of this surface owns (§ Security). Labels are local string resources; the
only server text is the value and the raw `reason_type` inside the fallback label.

**Accepted residual (named, not skipped):** a hostile `reason_type` can put arbitrary text after the local
"Reason type:" prefix, and a hostile `reason`/`description` can read like an instruction ("Safe, tap
Allow"). The prefix and labels are local and visually distinct from the value (`labelLarge` SemiBold vs
`bodyMedium`), the context rows sit below the daemon's own `prompt` and never replace an option `label` or
the fail-safe-deny highlight (still driven solely by `option.id == open.defaultOptionId`), and the
second-confirm gate ([#451](modal-answer-flow.md)) still guards any allow — the same residual the `prompt`
field already carries.

### The always-allow offer (#818)

`open.offersAlwaysAllow` (`ModalUiState.Open`, [Current-modal state](current-modal-state.md)) gates a private
`AlwaysAllowOffer(rules, accepted, onChanged)`, drawn between `PermissionContext` and the option `Column` —
`permission` only, and only when the daemon's `modal_shown.always_allow` offer decoded to a non-empty rule
list. The offer retains the container's 8 dp stacked spacing above its body-medium rule lines. Its dark
checkbox row follows the component's 20 dp tertiary box, 12 dp label gap and label-medium text:

```kotlin
Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = accepted, role = Role.Checkbox, onValueChange = onChanged),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.size(20.dp).border(2.dp, colorScheme.tertiary, MaterialTheme.shapes.extraSmall)) {
        if (accepted) Icon(ic_permission_checkbox_check, tint = colorScheme.tertiary)
    }
    Text(stringResource(R.string.modal_always_allow_label), style = labelMedium, fontWeight = SemiBold)
}
rules.forEach { rule -> Text(text = rule, style = MaterialTheme.typography.bodyMedium) }   // one per line, wire order
```

- **The whole row toggles**, via `Modifier.toggleable(role = Role.Checkbox)` on the `Row`, not just the
  visible box — the box and check are pure indicators, so there is exactly one tap
  target and one accessibility node.
- **`onChanged` carries `open.modalId`**, forwarded to [`ThreadViewModel.onAlwaysAllowChanged`](modal-answer-flow.md#the-session-grant-draft-818-moved-to-process-lifetime-in-1306)
  — the render never decides acceptance itself, it only reports which prompt the tap landed on. This is the
  same "render reflects, VM decides" posture as `isArmed` below, and it closes the security review's MUST
  FIX: a tap that lands after a `Shown` replaces the frame carries the *old* `modalId`, so the VM's guard
  rejects it instead of silently accepting the new prompt.
- **The rules render as plain `Text`, one per line, in wire order** — no markdown, no `AnnotatedString` link
  handling, no `SelectionContainer`, no saved state. They are claude-authored display text (the daemon's own
  retained-rule strings), so they carry the same output-encoding obligation as `prompt` / option `label`s /
  `PermissionContext` values (§ Security) — never a materially different sink.
- **Toggling never arms, sends or answers.** The checkbox only feeds `alwaysAllowAccepted`; the phone still
  sends nothing until an `allow_once` / `allow_always` tap, and that tap still needs its own single- or
  second-confirm tap under the unchanged fail-safe-deny gate. Accepting the offer is not that confirmation.

## The dismissal (`Dismissed`)

When `currentModal` transitions to `Dismissed`, **no overlay renders** (it is removed — AC #3) and a
snackbar surfaces the resolution reason via a `LaunchedEffect(modalState.modalId)`. Keying on `modalId`
fires it **exactly once per composition of that `LaunchedEffect`** — not once per resolution overall.
Through #1337, `Dismissed` was a sticky terminal state that a thread, once scoped onto it, never left until
superseded by a new `Open`; since #1337, `HostModalState.scopedTo` keeps returning that conversation's most
recent `Dismissed` from `resolved` (see [Current-modal state § the `HostModalState`
fold](current-modal-state.md#2-the-hostmodalstate-fold-1337--the-viewmodel-re-exposure)) for as long as the **current connection** lasts,
so **leaving the thread and reopening it re-runs `LaunchedEffect(modalId)` with the same id and the snackbar
fires again.** This is a deliberate consequence of #1337's reconnect-only clear, not a regression: the
snackbar is a one-shot *per view*, not a one-shot *per device*, and it stops the moment a reconnect empties
`resolved`. The Scaffold gains a `snackbarHostState = remember { SnackbarHostState() }` + `snackbarHost`,
mirroring the [`ArchivedDiscussionsScreen`](archived-discussions-screen.md) dismiss-reason precedent.

`dismissReasonText(source)` maps the verbatim wire token to a **local** string resource:

| `Dismissed.source` | string | |
|---|---|---|
| `"remote"` | `modal_dismissed_remote` | "Resolved on another device" |
| `"local"` | `modal_dismissed_local` | "Resolved on this device" |
| `"timeout"` | `modal_dismissed_timeout` | "Request timed out" |
| any other (forward-compat) | `modal_dismissed_resolved` | "Request resolved" — the graceful fallback |

The mapping is **load-bearing for confidentiality, not just UX**: the snackbar draws in the **un-secured
Activity window** (`FLAG_SECURE` covers only the *dialog's* window), so the reason must be a mapped local
string — **never** the raw `source` token. An unknown forward-compat value yields the generic fallback, not
the echoed value. (Copy is design-owed placeholder, reconciled when the visual spec lands.)

## Send-error confidentiality (`modalSendErrors`, #452)

When a `modal_answer` / `modal_cancel` send fails ([#451](modal-answer-flow.md) catches server `error` /
not-connected), the VM emits a one-shot `modalSendErrors: Flow<Unit>`. [#452](../codebase/452.md) collects
it **inside `ThreadScreen`** (the snackbar is a screen concern) onto the **same** `snackbarHostState` as the
dismiss reason, via the `ArchivedDiscussionsScreen` VM-event→snackbar idiom:

```kotlin
val modalSendFailedMessage = stringResource(R.string.modal_send_failed)   // fixed local string, never the payload
LaunchedEffect(modalSendErrors, snackbarHostState) {
    modalSendErrors.collect { snackbarHostState.showSnackbar(modalSendFailedMessage) }
}
```

Confidentiality is **structural, not by discipline**: the event carries `Unit` (#451) and the message is a
fixed local string, so it is **impossible** for a `modalId` / `optionId` / command / path to reach the
snackbar — which, like the dismiss reason, draws in the un-secured Activity window. `currentModal` stays
`Open` after a failure (#451), so the user can re-answer; there is no retry or error-code interpretation here
(the reactive read-only degrade off the error code is the **#440** extension point). This is the exact
mirror of the dismiss-reason mapped-not-echoed posture above.

## Security — the render-time obligations deferred to this surface

`security-sensitive`. The verbatim `title` / `prompt` / option `label`s may name a sensitive command or
path; #437 carries them verbatim and #445 keeps them solely in a transient `StateFlow` (no persistence), so
the render-time output-encoding, the screen-capture hardening, the send-error confidentiality, the
tapjacking net and (since [#1306](../../specs/architecture/1306-inline-permissions.md)) the stale-tap guard
all land here.

- **Output-encoding / injection sink** — every server string renders through plain `Text(String)`, bounded
  by the `MAX_PERMISSION_TEXT` (8192-char) constant (literal, no markup / HTML / active-content
  interpretation). The injection sink would be routing them through [`MarkdownText`](markdown-text.md)
  (which parses) — explicitly forbidden, mirroring the retired `LiteralScreenSurface`'s verbatim-`Text` rule
  (#381, [retired by #883](../../specs/architecture/883-retire-literal-screen.md)). No `buildAnnotatedString`
  parse, no `SelectionContainer` (text selection is a clipboard-exfiltration path past `FLAG_SECURE`).
- **Screen-capture hardening and tapjacking moved to the activity surface (#1306).** #815 through #452 owned
  both as **dialog-window** properties (`DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)` +
  `filterTouchesWhenObscured = true` on `MobileGateModal`'s own window). With the request now three
  `LazyColumn` items and no dialog, neither property has a window to attach to; the thread instead mounts
  [`QuestionPromptProtection`](question-batch-modal.md#rendering) — the #1305 shared-owner guard that sets
  `FLAG_SECURE` and the obscured-touch filter on the **activity** window — from one call site whenever a
  question batch **or** an open permission/trust request is present. This is the same seam
  [`LiteralScreenSurface.SecureScreen()`](../codebase/381.md) originally hardened (the Activity window, before
  [#883](../../specs/architecture/883-retire-literal-screen.md) retired the file), reused rather than
  reinvented. `ThreadPermissionCaptureTest` (device-only, since a window flag and real `MotionEvent` dispatch
  need a live window) asserts `FLAG_SECURE` on the activity window and the decor obscured-touch filter while
  a request is present, that an obscured tap on an option or the grant row is dropped while an unobscured tap
  at the same point acts, and that the prior window policy returns once the request is removed — the same
  contract #815's `MobileModalTest` pair asserted for the retired dialog window.
- **Stale taps carry the wrong `modalId` — closed by #1306.** A dialog recomposed as one window, so a tap
  could only ever reach the modal that composed it; a `LazyColumn` row has no such guarantee. Every decision
  callback — `onOption`, `onCancel`, `onAlwaysAllowChanged` — now carries the rendered request's `modalId`,
  and `ThreadViewModel` rejects a mismatch against its synchronous `scopedModal()` read. See [Modal answer
  flow § Stale taps](modal-answer-flow.md#stale-taps-carry-the-wrong-modalid-1306) for the guard itself; this
  was the security review's one MUST FIX on #1306.
- **Fail-safe-deny preserved; the UI cannot make an allow easier** ([#452](../codebase/452.md)) — the render
  only *reflects* the VM-owned `armedOptionId` (scoped, #451); it never re-derives the arm, interprets
  option-id semantics, or auto-answers. Every tap forwards verbatim via `onClick = { onOption(open.modalId,
  option.id) }`; the second-confirm gate is the VM's. Cancel sends `modal_cancel` — a withdrawal, never an
  allow.
- **No persistence** — no request-derived text reaches `rememberSaveable` / `SavedStateHandle` / DataStore;
  `modalState` / `armedOptionId` are hoisted params, the list items hold no saved state, the snackbar shows a
  mapped local string, and the session-grant draft in `PermissionDraftStore` is heap-only (see [Modal answer
  flow § The session-grant draft](modal-answer-flow.md#the-session-grant-draft-818-moved-to-process-lifetime-in-1306)).
  No server text survives process death.
- **The always-allow offer ([#818](#the-always-allow-offer-818)) widens no grant on the render side** — the
  offered rules are claude-authored display text under the same output-encoding rule as `prompt` / option
  `label`s (plain `Text`, no markup), inherit the same activity-window `FLAG_SECURE` as the rest of the card,
  and the checkbox row inherits the same obscured-touch net as the options. The render sends nothing itself —
  it only reports acceptance to the VM, which computes and sends the one boolean (see [Modal answer
  flow § The session-grant draft](modal-answer-flow.md#the-session-grant-draft-818-moved-to-process-lifetime-in-1306)).
- **Accepted residuals (named, not skipped):** `FLAG_SECURE` blocks screen *capture*, not the accessibility
  node tree — a malicious accessibility service can read the verbatim text, but suppressing the a11y tree
  would break legitimate TalkBack users, so it is a platform-level tradeoff, not a regression here. Unlike
  the retired dialog, the inline request is not separately announced to screen readers as a modal interrupt —
  the #1306 verifier review flagged this as matching the #1305 inline questions and not required by the
  ticket, not as a regression to fix here.

## Not answerable by a stray gesture; leaving the chat is not cancelling

The old dialog was **non-dismissable**: `MobileGateModal`'s `dismissOnBackPress` / `dismissOnClickOutside`
were `false` and it drew no close glyph, so back-press / outside-tap / close were all ignored. The inline
request keeps the same guarantee by a different mechanism (#1306): it is ordinary list content with no
dismiss surface to disable, so Back, opening another conversation, or tapping a message row simply cannot
reach it. Cancel remains the **only** path to `onModalCancel` → `modal_cancel` — the explicit outlined
button below the card (`permission-request-cancel`), unchanged since [#452](../codebase/452.md) introduced
it. `ThreadScreenModalTest`'s adapted case asserts Back invokes the screen's own `onBack` without answering
or cancelling the request.

**Leaving the chat clears the arm, not the request or the grant.** `ThreadViewModel.onConversationLeft()`
(fired by `MainActivity`'s `DisposableEffect` on Back and on pushing a new destination) nulls any armed
non-default option, so a returning reader needs two fresh taps to allow — but the request itself keeps
rendering exactly as before, and an accepted session-grant draft survives in `PermissionDraftStore` for the
same outstanding request. See [Modal answer flow § Leaving the conversation clears the arm, not the
grant](modal-answer-flow.md#leaving-the-conversation-clears-the-arm-not-the-grant-1306).

The [#445 open question](current-modal-state.md#lifecycle-errors-edge-cases) — should a connection drop clear
a stale `Open`? — stayed unbuilt through #451/#452/#1306: the daemon validates `modalId` server-side so a
stale answer is rejected (surfacing via #451's error signal). [#1337](current-modal-state.md#lifecycle-errors-edge-cases)
answers the related question for a **new connection** (not a drop): the daemon's guaranteed connect-time
re-send of every still-outstanding prompt means a held prompt can be safely cleared the moment a fresh
connection is published, so it is cleared then — but a plain teardown with no new connection yet still
retains, exactly as #445/#492 left it. The session-grant checkbox survives this clear for the same request:
`PermissionDraftStore`'s `keeps` rule (see [Modal answer flow § The session-grant
draft](modal-answer-flow.md#the-session-grant-draft-818-moved-to-process-lifetime-in-1306)) retires a draft
only once its request is resolved or its conversation shows a different one, never merely because a
reconnect emptied `hostModals` — so a daemon re-send of the same `modal_id` with the same rules after a
background/foreground cycle finds the checkbox still ticked.

## Testing

Split into [Permission-modal overlay — testing](permission-modal-overlay-testing.md) (2026-10-01, to stay
under the docs-guard size cap): the shared `ThreadScreenModalTest` coverage, the device-only capture test,
and the rung-3 live scenarios (`interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation` and
[#1337](../../specs/architecture/1337-hold-every-outstanding-prompt.md)'s
`interactiveTurn_permissionPrompts_heldPerConversation`).

## Visual spec status

Design source [`16-8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) is the host
**Conversation Thread** frame and still does not draw the request directly — same treatment as the sibling
Phase-3 interactive surfaces [#407](../codebase/407.md) (thinking indicator) / [#388](../codebase/388.md)
(tool-row status) / [#396](../codebase/396.md) (stall promotion). Through #815 the overlay's container was
`MobileGateModal`, built against the landed [`533-2369`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369)
generic mobile-modal frame; **[#1306](../../specs/architecture/1306-inline-permissions.md) dropped that
container** and instead follows the [`639-2242`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=639-2242)
family (states `639-2451`/`639-2666`/`639-2882`/`639-3099`/`639-3308`/`640-2437`/`640-2838`) — unreachable in
that run because the Figma MCP connector was unauthenticated, so the layout instead follows the sibling \#1305
inline-question card (`app/src/androidTest/assets/question-1305/question-normal.png`): a `titleMedium`
heading, the bordered card, then Cancel centred below it. The PR flags this as a deviation risk for the
verifier's fidelity check against #1220; authorizing the Figma connector (`/mcp` in an interactive session)
would let a later pass compare directly. The prompt copy itself (title / prompt / option `label`s) is still
server-authored placeholder text, not a designed string.

The dark component comparison inspected [checkbox `347:6771`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6771),
its label child `347:6215`, [button states `489:1876`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=489-1876)
and [mobile modal `533:2369`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369)
on **2026-09-30**. The committed 412 × 892 emulator captures and
[labelled comparison](../../../app/src/androidTest/assets/permission-1300/comparison-labelled-412x892.png)
use 1 px per dp. They verify the tertiary checkbox outline/check, label spacing, primary filled safe
default, primary outlined resting choices and shared navy shell against those components. Figma still
has **no full-screen permission frame** and **no armed non-default state**; the tonal armed treatment is a
design gap, not an exact component match.

## Related

- [Permission-modal overlay — testing](permission-modal-overlay-testing.md) — the shared screen test, the
  device-only capture test, and the rung-3 live scenarios, split out on 2026-10-01 to stay under the
  docs-guard size cap.
- [#446 implementation notes](../codebase/446.md) — the base overlay: files, line refs, lessons, NITs.
- [#452 implementation notes](../codebase/452.md) — the live armed affordance + Cancel + send-error +
  tapjacking + route-host wiring: files, line refs, the tapjacking pattern, lessons.
- [1306 architecture doc](../../specs/architecture/1306-inline-permissions.md) — the inline move: design
  source, the stale-tap security review, the two mid-build revisions (the `awaitReadPrompt` rescope and the
  `ForcedSize` Robolectric-density lesson).
- [Shared mobile modal](mobile-modal.md) ([#815](mobile-modal.md#the-hardened-gate-mobilegatemodal)) — the
  overlay's container **through #1306**; still `CreateChatModal`'s hardened entry point.
- [Question batch modal](question-batch-modal.md) ([#661](question-batch-modal.md), inline since #1305) —
  the sibling prompt kind this surface now shares a `QuestionPromptProtection` owner and a reveal effect
  with; `PermissionDraftStore` follows its `QuestionDraftStore`'s process-lifetime, app-scoped precedent.
- [Modal answer flow](modal-answer-flow.md) ([#451](../codebase/451.md)) — the behavior half whose
  `armedOptionId` / `modalSendErrors` signals this renders and whose `onModalOption` / `onModalCancel`
  decision methods the route host wires; since [#818](modal-answer-flow.md#the-session-grant-draft-818-moved-to-process-lifetime-in-1306)
  also `alwaysAllowAccepted` and `onAlwaysAllowChanged`, which `AlwaysAllowOffer` reflects the same way; since
  [#1306](modal-answer-flow.md#stale-taps-carry-the-wrong-modalid-1306) the `modalId` guard every callback
  carries, and [`onConversationLeft`](modal-answer-flow.md#leaving-the-conversation-clears-the-arm-not-the-grant-1306).
- [#818 architecture doc](../../specs/architecture/818-permission-always-allow.md) and
  [PR #903](https://github.com/pyrycode/pyrycode-mobile/pull/903) — the always-allow offer: design source,
  the security review, the deliberate 10-file overage.
- [Current-modal state](current-modal-state.md) ([#445](../codebase/445.md)) — the hoisted `currentModal`
  this renders; the projection/state half this consumes.
- [Modal events](modal-events.md) ([#437](../codebase/437.md)) — the upstream decode seam; since #817 it
  also decodes the four `ModalContext` fields this overlay's `PermissionContext` renders.
- [Thread screen § list and status row](thread-screen-how-it-works-list-and-status-row.md#inline-permission-rows-and-the-shared-reveal-1306) —
  the host; through #1306 the `Open` case was the seventh `Scaffold` sibling alongside `WorkspacePicker` /
  `RenameDialog` / `SaveAsChannelDialog` / `StatusSheet` / `ChannelInfoSheet` / `DeleteConfirmationDialog` —
  it now renders inline instead, and only `Dismissed` still reaches that `when` block (see [§
  overlays](thread-screen-how-it-works-overlays-and-app-bar.md#permission-modal-placement-post-446-moved-inline-in-1306)).
- `LiteralScreenSurface` ([#381](../codebase/381.md), retired by [#883](../../specs/architecture/883-retire-literal-screen.md)) — the **Activity-window**
  `FLAG_SECURE` precedent; #446–#452 mirrored it with a **dialog-window** `securePolicy` variant, and
  [#1306](../../specs/architecture/1306-inline-permissions.md) returned to the Activity-window shape via
  `QuestionPromptProtection`. Also the source of the verbatim-`Text` (never `MarkdownText`) rule.
- [Archived discussions screen](archived-discussions-screen.md) — the snackbar dismiss-reason precedent
  (`remember { SnackbarHostState() }` + `LaunchedEffect` + `Scaffold(snackbarHost = …)`).
- Sibling slices: **#444** answering / cancelling, split into [**#451**](modal-answer-flow.md) the behavior
  (shipped) + [**#452**](../codebase/452.md) the armed-affordance render + route-host wiring (shipped) ·
  **#440** read-only device mode (`blockedBy` #452 — the next consumer of this surface) ·
  [**#1306**](../../specs/architecture/1306-inline-permissions.md) the inline move (shipped) · **#1220** the
  full application-wide comparison across the #1300/#1306 evidence, still in Inbox.
- Producer SSOT: pyrycode#716 (`permission` / `trust` classes; fail-safe-deny `default_option_id`, no
  per-option destructive marker); ADR 025 § Phase 3 modals, EPIC pyrycode#597.
