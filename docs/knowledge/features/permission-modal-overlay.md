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
- **Explicit Cancel.** A low-emphasis `TextButton` (`Modifier.align(Alignment.End)`) below the options — the
  **only** path to `onModalCancel` (back-press / outside-tap dismissal stay disabled, see below).
- **Send-error snackbar.** A failed `modal_answer` / `modal_cancel` surfaces on the existing
  `snackbarHostState` via a **fixed local string** `modal_send_failed` — see § Send-error confidentiality.
- **Tapjacking net.** Now that the taps are live, `filterTouchesWhenObscured = true` on the dialog's own
  window — see § Security.
- **Route-host wiring.** `MainActivity` collects `vm.armedOptionId`, forwards it + `vm.modalSendErrors`, and
  binds `onModalOption` / `onModalCancel` to `vm::onModalOption` / `vm::onModalCancel`.

## Where it lives

All in `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` (private composables,
inline per the `DeleteConfirmationDialog` precedent — **not** a new file): `PermissionModalOverlay`,
`ModalOptionButton`, `dismissReasonText`. The state type is [`ModalUiState`](current-modal-state.md)
(`data/model/ModalUiState.kt`, from #445 — moved from `ui/conversations/thread/` to `data/model` in
[#492](../codebase/492.md) when the fold hoisted to the coordinator, so `ThreadScreen` now imports it).
Collected in the route host at
[`MainActivity.kt`](thread-screen.md#destination-block). Strings in `res/values/strings.xml`
(`modal_*`). See [Thread screen](thread-screen.md) for how it sits among the other `Scaffold` siblings.

## The render path

```
ThreadViewModel.currentModal : StateFlow<ModalUiState>      ◀── #445 fold (app-level, modalId-keyed)
ThreadViewModel.armedOptionId : StateFlow<String?>          ◀── #451 arm projection (VM-scoped)
ThreadViewModel.modalSendErrors : Flow<Unit>                ◀── #451 payload-free one-shot
        │  MainActivity: collectAsStateWithLifecycle(currentModal, armedOptionId) like isThinking/isStalled;
        │  modalSendErrors forwarded BY REFERENCE (single-consumer — collected in ThreadScreen, #452)
        ▼
ThreadScreen(state, …, modalState = Hidden, armedOptionId = null, modalSendErrors = emptyFlow(),
             onModalOption = vm::onModalOption, onModalCancel = vm::onModalCancel)   ◀── all live since #452
        │  LaunchedEffect(modalSendErrors){ collect → snackbar(modal_send_failed) }   (#452 error collect)
        │  when (modalState):
        ├─ Open      → PermissionModalOverlay(open, armedOptionId, onOption, onCancel)  ── BasicAlertDialog
        ├─ Dismissed → LaunchedEffect(modalId) { snackbarHostState.showSnackbar(dismissReasonText(source)) }
        └─ Hidden    → Unit
```

`modalState` / `armedOptionId` / `modalSendErrors` are all **defaulted** parameters (`= Hidden` / `= null` /
`= emptyFlow()`), so every existing preview and androidTest call site stays inert — no call-site cascade. The
route host collects `currentModal` + `armedOptionId` exactly like the `isThinking` / `isStalled`
collect-and-forward and binds `onModalOption` / `onModalCancel` to the VM. [#451](modal-answer-flow.md) added
the VM's decision methods (the answer/cancel + arm logic); the render slice [**#452**](../codebase/452.md)
forwards those screen hooks to `vm::onModalOption` / `vm::onModalCancel` in the route host, threads
`armedOptionId` into the overlay to draw the armed affordance, and collects `modalSendErrors` **inside
`ThreadScreen`** (not the route host) because the snackbar — its effect — is a screen concern, while
`navigationEvents` stays in `MainActivity` because navigation is a host concern. `modalSendErrors` is a
single-consumer `Channel.receiveAsFlow()`, so `MainActivity` only forwards the reference.

App-level by construction: modal events carry **no `conversation_id`** ([Modal events](modal-events.md)),
so there is **one** `currentModal` across the app and the overlay shows over **whichever thread is active**
— not scoped per conversation.

## The overlay (`Open`)

`PermissionModalOverlay(open: ModalUiState.Open, armedOptionId, onOption, onCancel)` is a
**`BasicAlertDialog`** (M3, `@ExperimentalMaterial3Api` — `ThreadScreen` already opts in), **not** the
opinionated two-button `AlertDialog`: the option count is variable (`permission` = 4, `trust` = 2), so all
options render uniformly in a `Column` to preserve array order and a single highlight path.

```kotlin
BasicAlertDialog(
    onDismissRequest = onCancel,                    // inert while both dismiss flags are false
    properties = DialogProperties(
        securePolicy = SecureFlagPolicy.SecureOn,   // FLAG_SECURE on the dialog's OWN window — see Security
        dismissOnBackPress = false,                 // a permission gate must not treat a stray back / outside
        dismissOnClickOutside = false,              //   tap as an implicit answer (cancel = the explicit button)
    ),
) {
    // #452: now that the taps are live, harden the dialog's OWN window against tapjacking.
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { dialogWindow?.decorView?.filterTouchesWhenObscured = true }
    Surface(shape = AlertDialogDefaults.shape, color = .containerColor, tonalElevation = .TonalElevation) {
        Column(Modifier.padding(24.dp)) {
            Text(open.title, style = headlineSmall)   // verbatim, plain Text — never MarkdownText
            Text(open.prompt, style = bodyMedium)     // verbatim, plain Text
            open.options.forEach { option ->          // wire array order = canonical display/selection order
                ModalOptionButton(option.label,
                    isDefault = option.id == open.defaultOptionId,
                    isArmed   = option.id == armedOptionId,        // #452: reflects the VM's armed option
                    onClick   = { onOption(option.id) })           // every tap forwards verbatim
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancel, Modifier.align(Alignment.End)) {   // #452: the explicit Cancel
                Text(stringResource(R.string.modal_cancel))
            }
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
| neither — a resting non-default | `OutlinedButton` | none (a first tap arms it via the VM) |

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
  [`MarkdownText`](markdown-text.md) (which parses) — explicitly forbidden, mirroring
  [`LiteralScreenSurface`](literal-screen-surface.md)'s verbatim-`Text` rule. No `buildAnnotatedString`
  parse, no `SelectionContainer` (text selection is a clipboard-exfiltration path past `FLAG_SECURE`).
- **Screen-capture hardening** — `DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)` sets
  `FLAG_SECURE` on the **dialog's own window**. A Compose dialog draws in its own window, so the
  [#381](../codebase/381.md) `LiteralScreenSurface.SecureScreen()` precedent (which flags the **Activity**
  window) would **not** cover it; `SecureOn`, **not** the default `Inherit`, is the load-bearing choice
  because the host thread screen carries `FLAG_SECURE` nowhere. A deterministic Compose property — a real
  code-level net, not a stochastic rule. (Cannot be asserted via the Compose test API — there is no
  semantics node for a window flag; **code-review-verified**.)
- **Tapjacking** ([#452](../codebase/452.md), now that the taps are live) — `filterTouchesWhenObscured =
  true` on the dialog's **own** window (`(LocalView.current.parent as? DialogWindowProvider)?.window` →
  `SideEffect { decorView.filterTouchesWhenObscured = true }`), the deterministic View-level analog of
  `SecureOn` (min SDK 33). It drops touches delivered while another window obscures the dialog —
  **different fabric** from the second-confirm UX belt (#451): a single obscured tap can at worst arm/deny,
  and the filter additionally hardens the deliberate two-tap tapjack of an *allow*. Like a window flag, it
  has no Compose-test semantics node ⇒ **code-review-verified by inspection**.
- **Fail-safe-deny preserved; the UI cannot make an allow easier** ([#452](../codebase/452.md)) — the render
  only *reflects* the VM-owned `armedOptionId` (scoped, #451); it never re-derives the arm, interprets
  option-id semantics, or auto-answers. Every tap forwards verbatim via `onClick = { onOption(option.id) }`;
  the second-confirm gate is the VM's. Cancel sends `modal_cancel` — a withdrawal, never an allow.
- **No persistence** — no modal-derived text reaches `rememberSaveable` / `SavedStateHandle` / DataStore;
  `modalState` / `armedOptionId` are hoisted params, the dialog holds no saved state, the snackbar shows a
  mapped local string. No server text survives process death.
- **Accepted residuals (named, not skipped):** `FLAG_SECURE` blocks screen *capture*, not the accessibility
  node tree — a malicious accessibility service can read the verbatim text, but suppressing the a11y tree
  would break legitimate TalkBack users, so it is a platform-level tradeoff, not a regression here.

## Non-dismissable here, and the stale-`Open` question

The overlay never leaves composition on a **stray** gesture: `dismissOnBackPress` /
`dismissOnClickOutside` are `false`, so back-press / outside-tap are ignored (a permission gate must not read
them as an implicit answer) and it leaves composition only when `currentModal` transitions away from `Open`
(a daemon `Dismissed`, including timeout) **or** the user makes a deliberate choice. Cancel is the
**explicit** low-emphasis `TextButton` ([#452](../codebase/452.md)) → `onModalCancel` → `modal_cancel`;
`onDismissRequest` stays bound to `onCancel` but is inert while both dismiss flags are `false`.

The [#445 open question](current-modal-state.md#lifecycle-errors-edge-cases) — should a connection drop clear
a stale `Open`? — is **not** built here, nor in #451/#452: the daemon validates `modalId` server-side so a
stale answer is rejected (surfacing via #451's error signal), so a proactive stale-clear is a UX nicety
deferred to **#440** + the connection signal.

## Testing

Instrumented screen test `app/src/androidTest/.../thread/ThreadScreenModalTest.kt` (`./gradlew
connectedAndroidTest`, device required), mirroring `ThreadScreenOverflowTest`'s idiom — the #446 set
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

No unit test (pure UI; the fold logic is unit-tested in #445, the decision logic in #451).
`connectedAndroidTest` was **not** run in the build environment (no device — the project norm); the test
compiles under the green `assembleDebug` / `check` / `compileDebugAndroidTestKotlin` gates ([[androidtest-not-compiled-by-mandatory-gates]]).
`FLAG_SECURE` and `filterTouchesWhenObscured` are window flags with **no** Compose-test semantics node —
code-review-verified invariants, not runtime-asserted.

> **Known test-strength NIT (code review, optional):** the send-error confidentiality test drives the error
> over a `Hidden` modal, so the `prompt` (`rm -rf …`) is never composed and the `assertDoesNotExist("rm -rf")`
> passes **vacuously**. The contract is enforced structurally (the event is `Unit` + a fixed local string), so
> not a real gap — but the assertion would be stronger driven over an **`Open`** modal where the payload is
> actually on screen. A candidate strengthening when **#440** next touches this test.

## Visual spec status

Design source [`16-8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) is the host
**Conversation Thread** frame; the modal overlay itself is **not yet drawn** there — **design-owed**, same
treatment as the sibling Phase-3 interactive surfaces [#407](../codebase/407.md) (thinking indicator) /
[#388](../codebase/388.md) (tool-row status) / [#396](../codebase/396.md) (stall promotion). The behaviour
+ M3 structure are built now against
M3 dialog defaults; the modal's visual spec (and the placeholder string copy + the snackbar-vs-inline
dismiss affordance) reconcile when it lands.

## Related

- [#446 implementation notes](../codebase/446.md) — the base overlay: files, line refs, lessons, NITs.
- [#452 implementation notes](../codebase/452.md) — the live armed affordance + Cancel + send-error +
  tapjacking + route-host wiring: files, line refs, the tapjacking pattern, lessons.
- [Modal answer flow](modal-answer-flow.md) ([#451](../codebase/451.md)) — the behavior half whose
  `armedOptionId` / `modalSendErrors` signals this renders and whose `onModalOption` / `onModalCancel`
  decision methods the route host wires.
- [Current-modal state](current-modal-state.md) ([#445](../codebase/445.md)) — the hoisted `currentModal`
  this renders; the projection/state half this consumes.
- [Modal events](modal-events.md) ([#437](../codebase/437.md)) — the upstream decode seam.
- [Thread screen](thread-screen.md) — the host; the overlay is the seventh `Scaffold` sibling, alongside
  `WorkspacePicker` / `RenameDialog` / `SaveAsChannelDialog` / `StatusSheet` / `ChannelInfoSheet` /
  `DeleteConfirmationDialog`.
- [Literal screen surface](literal-screen-surface.md) ([#381](../codebase/381.md)) — the `FLAG_SECURE`
  **Activity-window** precedent this slice mirrors with the **dialog-window** `securePolicy` variant; also
  the verbatim-`Text` (never `MarkdownText`) rule.
- [Archived discussions screen](archived-discussions-screen.md) — the snackbar dismiss-reason precedent
  (`remember { SnackbarHostState() }` + `LaunchedEffect` + `Scaffold(snackbarHost = …)`).
- Sibling slices: **#444** answering / cancelling, split into [**#451**](modal-answer-flow.md) the behavior
  (shipped) + [**#452**](../codebase/452.md) the armed-affordance render + route-host wiring (shipped) ·
  **#440** read-only device mode (`blockedBy` #452 — the next consumer of this surface).
- Producer SSOT: pyrycode#716 (`permission` / `trust` classes; fail-safe-deny `default_option_id`, no
  per-option destructive marker); ADR 025 § Phase 3 modals, EPIC pyrycode#597.
