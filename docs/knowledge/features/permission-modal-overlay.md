# Permission-modal overlay — the render half of the permission/choice modal

The **render half of the permission/choice-modal UI surface**: how the hoisted
[`currentModal`](current-modal-state.md) state is drawn as a separate-surface overlay over the active
conversation thread, and how its resolution is surfaced on dismissal. Landed in
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
  `MobileGateModal` footer as an outlined `ModalCancelButton`; see § The overlay.
- **Send-error snackbar.** A failed `modal_answer` / `modal_cancel` surfaces on the existing
  `snackbarHostState` via a **fixed local string** `modal_send_failed` — see § Send-error confidentiality.
- **Tapjacking net.** Now that the taps are live, `filterTouchesWhenObscured = true` on the dialog's own
  window — see § Security.
- **Route-host wiring.** `MainActivity` collects `vm.armedOptionId`, forwards it + `vm.modalSendErrors`, and
  binds `onModalOption` / `onModalCancel` to `vm::onModalOption` / `vm::onModalCancel`.

## Where it lives

`PermissionModalOverlay`, `ModalOptionButton`, `AlwaysAllowOffer` (#818) and `dismissReasonText` live in
`app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadPermissionModal.kt`. Since #815, the overlay's
dialog chrome and window hardening are no longer its own: `PermissionModalOverlay` draws its content inside
[`MobileGateModal`](mobile-modal.md#the-hardened-gate-mobilegatemodal), the hardened entry point
[Shared mobile modal](mobile-modal.md) exposes on its shell. `ModalOptionButton` and `dismissReasonText`
stay in `ThreadPermissionModal.kt`. The state type is [`ModalUiState`](current-modal-state.md)
(`data/model/ModalUiState.kt`, from #445 — moved from `ui/conversations/thread/` to `data/model` in
[#492](../codebase/492.md) when the fold hoisted to the coordinator, so `ThreadScreen` now imports it).
Collected in the route host at
[`MainActivity.kt`](thread-screen.md#destination-block). Strings in `res/values/strings.xml`
(`modal_*`). See [Thread screen](thread-screen.md) for how it sits among the other `Scaffold` siblings.

## The render path

```
ThreadViewModel.currentModal : StateFlow<ModalUiState>      ◀── #445 fold (host-level, modalId-keyed), scoped to this thread's own conversation since #816
ThreadViewModel.armedOptionId : StateFlow<String?>          ◀── #451 arm projection (VM-scoped)
ThreadViewModel.alwaysAllowAccepted : StateFlow<Boolean>    ◀── #818 offer-acceptance projection (VM-scoped)
ThreadViewModel.modalSendErrors : Flow<Unit>                ◀── #451 payload-free one-shot
        │  MainActivity: collectAsStateWithLifecycle(currentModal, armedOptionId, alwaysAllowAccepted) like isThinking/isStalled;
        │  modalSendErrors forwarded BY REFERENCE (single-consumer — collected in ThreadScreen, #452)
        ▼
ThreadScreen(state, …, modalState = Hidden, armedOptionId = null, modalSendErrors = emptyFlow(),
             alwaysAllowAccepted = false, onAlwaysAllowChanged = { _, _ -> },
             onModalOption = vm::onModalOption, onModalCancel = vm::onModalCancel)   ◀── all live since #452/#818
        │  LaunchedEffect(modalSendErrors){ collect → snackbar(modal_send_failed) }   (#452 error collect)
        │  when (modalState):
        ├─ Open      → PermissionModalOverlay(open, armedOptionId, onOption, onCancel,
        │                                      alwaysAllowAccepted, onAlwaysAllowChanged)  ── MobileGateModal (#815)
        ├─ Dismissed → LaunchedEffect(modalId) { snackbarHostState.showSnackbar(dismissReasonText(source)) }
        └─ Hidden    → Unit
```

`modalState` / `armedOptionId` / `modalSendErrors` / `alwaysAllowAccepted` / `onAlwaysAllowChanged` are all
**defaulted** parameters (`= Hidden` / `= null` / `= emptyFlow()` / `= false` / `= { _, _ -> }`), so every
existing preview and androidTest call site stays inert — no call-site cascade. The
route host collects `currentModal` + `armedOptionId` + `alwaysAllowAccepted` exactly like the `isThinking` /
`isStalled` collect-and-forward and binds `onModalOption` / `onModalCancel` / `onAlwaysAllowChanged` to the
VM. [#451](modal-answer-flow.md) added the VM's decision methods (the answer/cancel + arm logic); the render
slice [**#452**](../codebase/452.md) forwards those screen hooks to `vm::onModalOption` /
`vm::onModalCancel` in the route host, threads `armedOptionId` into the overlay to draw the armed affordance,
and collects `modalSendErrors` **inside `ThreadScreen`** (not the route host) because the snackbar — its
effect — is a screen concern, while
`navigationEvents` stays in `MainActivity` because navigation is a host concern. `modalSendErrors` is a
single-consumer `Channel.receiveAsFlow()`, so `MainActivity` only forwards the reference.

Host-level by construction: the coordinator's fold holds **one** modal per host, keyed on `modalId`, not a
per-conversation map. Since [#816](current-modal-state.md), `ThreadViewModel` filters that single modal
down to its own conversation (`ModalUiState.scopedTo`, driven by `Shown.conversationId` — see [Modal
events](modal-events.md)) before this overlay ever sees it, so the overlay only draws in the thread whose
conversation raised the modal — never in a second open thread for another conversation on the same host.

## The overlay (`Open`)

Since [#815](mobile-modal.md#the-hardened-gate-mobilegatemodal), `PermissionModalOverlay(open:
ModalUiState.Open, armedOptionId, onOption, onCancel, alwaysAllowAccepted, onAlwaysAllowChanged)` (the last
two added by #818) draws inside
[`MobileGateModal`](mobile-modal.md#the-hardened-gate-mobilegatemodal) — the shared mobile-modal shell's
hardened decision-gate entry point — rather than its own `BasicAlertDialog`. The dialog chrome, the window
hardening (`SecureOn`, the obscured-touch filter, disabled back/outside dismissal, no close glyph) and the
Cancel-only footer all now live in `MobileGateModal` / the private `MobileModalShell` it shares with
`MobileModal`; see that document for the shell-side contract. This surface supplies only its content: the
server title (passed as `MobileGateModal`'s `title`), the prompt and the option list — the option count is
variable (`permission` = 4, `trust` = 2), so all options render uniformly in a `Column` to preserve array
order and a single highlight path.

```kotlin
MobileGateModal(
    title = open.title,
    cancelLabel = stringResource(R.string.modal_cancel),
    onCancel = onCancel,
) {
    Text(text = open.prompt, style = MaterialTheme.typography.bodyLarge)   // verbatim, plain Text
    if (!open.context.isEmpty) PermissionContext(open.context)             // #817, only when the frame carried any
    if (open.offersAlwaysAllow)                                            // #818, only when the daemon offered it
        AlwaysAllowOffer(open.alwaysAllowRules, alwaysAllowAccepted, onChanged = { onAlwaysAllowChanged(open.modalId, it) })
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        open.options.forEach { option ->          // wire array order = canonical display/selection order
            ModalOptionButton(option.label,
                isDefault = option.id == open.defaultOptionId,
                isArmed   = option.id == armedOptionId,        // #452: reflects the VM's armed option
                onClick   = { onOption(option.id) })           // every tap forwards verbatim
        }
    }
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
- **`onChanged` carries `open.modalId`**, forwarded to [`ThreadViewModel.onAlwaysAllowChanged`](modal-answer-flow.md#the-always-allow-session-grant-818)
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
fires it **exactly once** per resolution (`Dismissed` is a sticky terminal state in #445's fold) and never
re-fires on unrelated recomposition. The Scaffold gains a `snackbarHostState = remember {
SnackbarHostState() }` + `snackbarHost`, mirroring the [`ArchivedDiscussionsScreen`](archived-discussions-screen.md)
dismiss-reason precedent.

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
the render-time output-encoding, the screen-capture hardening, the send-error confidentiality, and (since
[#452](../codebase/452.md), now that taps are live) the tapjacking net all land here.

- **Output-encoding / injection sink** — every server string renders through plain `Text(String)` (literal,
  no markup / HTML / active-content interpretation). The injection sink would be routing them through
  [`MarkdownText`](markdown-text.md) (which parses) — explicitly forbidden, mirroring the retired
  `LiteralScreenSurface`'s verbatim-`Text` rule (#381, [retired by #883](../../specs/architecture/883-retire-literal-screen.md)).
  No `buildAnnotatedString` parse, no `SelectionContainer` (text selection is a clipboard-exfiltration path
  past `FLAG_SECURE`).
- **Screen-capture hardening** — since [#815](mobile-modal.md#the-hardened-gate-mobilegatemodal), owned by
  [`MobileGateModal`](mobile-modal.md#the-hardened-gate-mobilegatemodal)'s private `gate = true` shell
  branch: `DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)` sets `FLAG_SECURE` on the **dialog's
  own window**. A Compose dialog draws in its own window, so the [#381](../codebase/381.md)
  `LiteralScreenSurface.SecureScreen()` precedent (which flagged the **Activity** window, before [#883](../../specs/architecture/883-retire-literal-screen.md) retired the file) would **not** cover
  it; `SecureOn`, **not** the default `Inherit`, is the load-bearing choice because the host thread screen
  carries `FLAG_SECURE` nowhere. A deterministic Compose property — a real code-level net, not a stochastic
  rule. Previously this had no Compose-test semantics node and was code-review-verified only; #815's
  `MobileModalTest.gate_window_is_secure_filters_obscured_touches_and_only_cancel_dismisses` now asserts
  `window.attributes.flags and FLAG_SECURE` at runtime by reaching the dialog window through
  `(LocalView.current.parent as DialogWindowProvider).window` — the same seam this render captures.
  `plain_shell_window_is_not_hardened` asserts in the other direction, that `MobileModal`'s plain shell
  (Edit host, Log data download) carries neither flag, so the hardening cannot leak across the private
  `gate` switch.
- **Tapjacking** ([#452](../codebase/452.md), now that the taps are live; owned by `MobileGateModal` since
  #815) — `filterTouchesWhenObscured = true` on the dialog's **own** window, the deterministic View-level
  analog of `SecureOn` (min SDK 33). It drops touches delivered while another window obscures the dialog —
  **different fabric** from the second-confirm UX belt (#451): a single obscured tap can at worst arm/deny,
  and the filter additionally hardens the deliberate two-tap tapjack of an *allow*. Like `FLAG_SECURE`, this
  is now runtime-asserted by the same #815 `MobileModalTest` pair rather than code-review-verified only.
- **Fail-safe-deny preserved; the UI cannot make an allow easier** ([#452](../codebase/452.md)) — the render
  only *reflects* the VM-owned `armedOptionId` (scoped, #451); it never re-derives the arm, interprets
  option-id semantics, or auto-answers. Every tap forwards verbatim via `onClick = { onOption(option.id) }`;
  the second-confirm gate is the VM's. Cancel sends `modal_cancel` — a withdrawal, never an allow.
- **No persistence** — no modal-derived text reaches `rememberSaveable` / `SavedStateHandle` / DataStore;
  `modalState` / `armedOptionId` are hoisted params, the dialog holds no saved state, the snackbar shows a
  mapped local string. No server text survives process death.
- **The always-allow offer ([#818](#the-always-allow-offer-818)) widens no grant on the render side** — the
  offered rules are claude-authored display text under the same output-encoding rule as `prompt` / option
  `label`s (plain `Text`, no markup), inherit the same `FLAG_SECURE` window (they draw inside
  `MobileGateModal`, never a separate surface), and the checkbox row inherits the same
  `filterTouchesWhenObscured` tapjacking net as the options. The render sends nothing itself — it only
  reports acceptance to the VM, which computes and sends the one boolean (see [Modal answer
  flow § The always-allow session grant](modal-answer-flow.md#the-always-allow-session-grant-818)).
- **Accepted residuals (named, not skipped):** `FLAG_SECURE` blocks screen *capture*, not the accessibility
  node tree — a malicious accessibility service can read the verbatim text, but suppressing the a11y tree
  would break legitimate TalkBack users, so it is a platform-level tradeoff, not a regression here.

## Non-dismissable here, and the stale-`Open` question

The overlay never leaves composition on a **stray** gesture: `MobileGateModal`'s `dismissOnBackPress` /
`dismissOnClickOutside` are `false` and it draws no close glyph (#815), so back-press / outside-tap / close
are all ignored (a permission gate must not read them as an implicit answer) and it leaves composition only
when `currentModal` transitions away from `Open` (a daemon `Dismissed`, including timeout) **or** the user
makes a deliberate choice. Cancel is the **explicit** outlined button in the shell's footer ([#452](../codebase/452.md)
introduced it as a low-emphasis `TextButton`; [#815](mobile-modal.md#the-hardened-gate-mobilegatemodal)
moved it into the shared `ModalCancelButton` styling, matching `MobileModal`'s own Cancel) → `onModalCancel`
→ `modal_cancel`; `onDismissRequest` stays bound to `onCancel` but is inert while both dismiss flags are
`false`. `ThreadScreenModalTest.back_press_neither_answers_nor_cancels_and_no_close_glyph_is_offered` (#815)
asserts the no-close-glyph and inert-back-press guarantees at the screen layer.

The [#445 open question](current-modal-state.md#lifecycle-errors-edge-cases) — should a connection drop clear
a stale `Open`? — is **not** built here, nor in #451/#452: the daemon validates `modalId` server-side so a
stale answer is rejected (surfacing via #451's error signal), so a proactive stale-clear is a UX nicety
deferred to **#440** + the connection signal.

## Testing

Shared screen test `app/src/sharedTest/.../thread/ThreadScreenModalTest.kt`, available to both unit and
device suites, mirrors `ThreadScreenOverflowTest`'s idiom — the #446 set
(render array-order, exactly-one-default-highlight, dismissed × {remote, local, timeout}, forward-compat
fallback, hidden) **extended by [#452](../codebase/452.md)** with the AC#4 interaction tests:

- **armed affordance** — `armedOptionId = "allow_once"`: **exactly one** option carries the
  `modal_armed_option_desc` marker and it is `allow_once`; with `armedOptionId = null` **none** does; the
  fail-safe-deny default (`reject_once`) **never** carries it even when a non-default is armed.
- **tap forwarding** — tapping the default forwards `onModalOption("reject_once")`; tapping the explicit
  Cancel button invokes `onModalCancel`.
- **two-tap confirm (the AC#4 core)** — driven through a small **stateful VM-mimicking stand-in** (a
  `var armed by remember { mutableStateOf<String?>(null) }` whose `onModalOption` mimics #451's branch order),
  recomposing `armedOptionId = armed`: the **first** tap of a non-default arms it (no send recorded) **and**
  renders the armed affordance; the **second** tap of the same option confirms (send recorded). This
  exercises "single tap does not confirm, second tap confirms" at the render layer without re-implementing
  the VM (whose rule is unit-tested in #451).
- **send-error confidentiality** — a `Channel<Unit>` fed into `modalSendErrors` emits once: `modal_send_failed`
  is displayed and no payload substring (`rm -rf`) appears in the snackbar.
- **decision context** (#817) — a populated `context` shows the reason label and
  value, the description and the blocked path, each with its own label; the `classifier` / `rule` sentence
  labels and the `Reason type: <raw>` fallback for an unrecognised `reasonType` all render; a type-only
  reason (`reason == null`, `reasonType` set) shows the label alone; a non-string reason's stringified text
  (`"false"`) still displays; a context-free `Open` shows none of the context labels.
- **always-allow offer** (#818) — a `permission` prompt with rules shows the label and each rule between the
  context and the options; neither a `trust` prompt with rules nor a `permission` prompt without any renders
  the offer; tapping the row calls `onAlwaysAllowChanged("m1", true)` and **not** `onModalOption`; with
  `alwaysAllowAccepted = true` the checkbox renders checked.

The compact-width case scrolls to every decision at 1.5× text and checks long labels for wrapping,
overflow and ellipsis. The device-only `ThreadPermissionCaptureTest` saves unchecked, checked and armed
412 × 892 surfaces for visual comparison. The fold logic remains unit-tested in #445, the decision logic
in #451/#818, and decode in [Modal events](modal-events.md#the-four-decision-context-fields-817).

Since [#815](mobile-modal.md#the-hardened-gate-mobilegatemodal), `FLAG_SECURE` and `filterTouchesWhenObscured`
are **runtime-asserted**, not only code-review-verified: a window flag has no Compose-test semantics node,
but content composed inside a Compose `Dialog` can capture `LocalView.current`, and
`(view.parent as DialogWindowProvider).window` exposes `attributes.flags` and
`decorView.filterTouchesWhenObscured` to an instrumented test. `MobileModalTest`'s
`gate_window_is_secure_filters_obscured_touches_and_only_cancel_dismisses` and
`plain_shell_window_is_not_hardened` assert both flags in both directions (present on the gate, absent on
the plain shell) using this seam, and `ThreadScreenModalTest.back_press_neither_answers_nor_cancels_and_no_close_glyph_is_offered`
covers the same guarantee at the screen layer. In the #1300 dispatcher full device report,
`MobileModalTest` ran 12 cases with 0 failures and 0 skips, including the gate-window, compact-scroll
and IME cases; `ThreadPermissionCaptureTest` ran its viewport case with 0 failures and 0 skips. The
full report contains 133 cases, 0 failures, 0 errors and 1 unrelated skip.

> **Known test-strength NIT (code review, optional):** the send-error confidentiality test drives the error
> over a `Hidden` modal, so the `prompt` (`rm -rf …`) is never composed and the `assertDoesNotExist("rm -rf")`
> passes **vacuously**. The contract is enforced structurally (the event is `Unit` + a fixed local string), so
> not a real gap — but the assertion would be stronger driven over an **`Open`** modal where the payload is
> actually on screen. A candidate strengthening when **#440** next touches this test.

## Visual spec status

Design source [`16-8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) is the host
**Conversation Thread** frame and still does not draw the modal overlay directly — same treatment as the
sibling Phase-3 interactive surfaces [#407](../codebase/407.md) (thinking indicator) /
[#388](../codebase/388.md) (tool-row status) / [#396](../codebase/396.md) (stall promotion). Since
[#815](mobile-modal.md#the-hardened-gate-mobilegatemodal), the overlay's *container* is no longer built
against bare M3 dialog defaults: it is drawn inside `MobileGateModal`, which follows the same landed
[`533-2369`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369) generic mobile-modal
frame [#638](mobile-modal.md) built the shared shell from (full-height rounded column, `titleLarge` header
over an `inversePrimary` divider, centred scrolling content, centred footer). The close glyph that frame
draws is deliberately left out here, since Cancel must stay the only dismissal control. The prompt copy
itself (title / prompt / option `label`s) is still server-authored placeholder text, not a designed string,
and the snackbar-vs-inline dismiss affordance remains design-owed.

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

- [#446 implementation notes](../codebase/446.md) — the base overlay: files, line refs, lessons, NITs.
- [#452 implementation notes](../codebase/452.md) — the live armed affordance + Cancel + send-error +
  tapjacking + route-host wiring: files, line refs, the tapjacking pattern, lessons.
- [Shared mobile modal](mobile-modal.md) ([#815](mobile-modal.md#the-hardened-gate-mobilegatemodal)) — the
  overlay's current container: the `MobileGateModal` entry point that now owns the dialog chrome, the four
  window-hardening properties and the Cancel-only footer this document used to describe as the overlay's own.
- [Question batch modal](question-batch-modal.md) ([#661](question-batch-modal.md)) — `MobileGateModal`'s
  second caller, the first to use its submit/sending/error extension. Its lock/single-send/late-completion
  idiom mirrors this overlay's own armed-answer send, but it is drawn from `MainActivity` beside
  `ThreadScreen` rather than as an eighth `Scaffold` sibling inside it.
- [Modal answer flow](modal-answer-flow.md) ([#451](../codebase/451.md)) — the behavior half whose
  `armedOptionId` / `modalSendErrors` signals this renders and whose `onModalOption` / `onModalCancel`
  decision methods the route host wires; since [#818](modal-answer-flow.md#the-always-allow-session-grant-818)
  also `alwaysAllowAccepted` and `onAlwaysAllowChanged`, which `AlwaysAllowOffer` reflects the same way.
- [#818 architecture doc](../../specs/architecture/818-permission-always-allow.md) and
  [PR #903](https://github.com/pyrycode/pyrycode-mobile/pull/903) — the always-allow offer: design source,
  the security review, the deliberate 10-file overage.
- [Current-modal state](current-modal-state.md) ([#445](../codebase/445.md)) — the hoisted `currentModal`
  this renders; the projection/state half this consumes.
- [Modal events](modal-events.md) ([#437](../codebase/437.md)) — the upstream decode seam; since #817 it
  also decodes the four `ModalContext` fields this overlay's `PermissionContext` renders.
- [Thread screen](thread-screen.md) — the host; the overlay is the seventh `Scaffold` sibling, alongside
  `WorkspacePicker` / `RenameDialog` / `SaveAsChannelDialog` / `StatusSheet` / `ChannelInfoSheet` /
  `DeleteConfirmationDialog`.
- `LiteralScreenSurface` ([#381](../codebase/381.md), retired by [#883](../../specs/architecture/883-retire-literal-screen.md)) — the `FLAG_SECURE`
  **Activity-window** precedent this slice mirrored with the **dialog-window** `securePolicy` variant; also
  the verbatim-`Text` (never `MarkdownText`) rule.
- [Archived discussions screen](archived-discussions-screen.md) — the snackbar dismiss-reason precedent
  (`remember { SnackbarHostState() }` + `LaunchedEffect` + `Scaffold(snackbarHost = …)`).
- Sibling slices: **#444** answering / cancelling, split into [**#451**](modal-answer-flow.md) the behavior
  (shipped) + [**#452**](../codebase/452.md) the armed-affordance render + route-host wiring (shipped) ·
  **#440** read-only device mode (`blockedBy` #452 — the next consumer of this surface).
- Producer SSOT: pyrycode#716 (`permission` / `trust` classes; fail-safe-deny `default_option_id`, no
  per-option destructive marker); ADR 025 § Phase 3 modals, EPIC pyrycode#597.
