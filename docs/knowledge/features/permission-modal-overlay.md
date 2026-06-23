# Permission-modal overlay — the render half of the permission/choice modal

The **render half of the permission/choice-modal UI surface**: how the hoisted
[`currentModal`](current-modal-state.md) state is drawn as a separate-surface overlay over the active
conversation thread, and how its resolution is surfaced on dismissal. Landed in
[#446](../codebase/446.md) (split from #443, the render half of #439), part of the Phase 3 permission-modal
feature (epic pyrycode#597, ADR 025). The state/projection half it consumes is the sibling slice
[#445](../codebase/445.md) (blocked this one); **answering / cancelling** the modal — wiring the inert
option-tap / cancel hooks to outbound `modal_answer` / `modal_cancel` sends — is the sibling slice **#444**,
split into the behavior half [#451](../codebase/451.md) ([shipped](modal-answer-flow.md) — the VM
fail-safe-deny decision logic + outbound send) and the render half **#452** (the armed/second-confirm
affordance + error snackbar + route-host forward, `blockedBy` #451); a read-only device without
remote-permission rights is **#440** (re-pointed onto #452).

This slice is **render-only**: a pure function of `modalState`, with no VM change, no new public type, and
no data-layer change. It owns the two render-time obligations [#445](../codebase/445.md) deliberately
deferred to it — **output-encoding** (inert text) and **screen-capture hardening** (own-window
`FLAG_SECURE`).

## Where it lives

All in `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` (private composables,
inline per the `DeleteConfirmationDialog` precedent — **not** a new file): `PermissionModalOverlay`,
`ModalOptionButton`, `dismissReasonText`. The state type is [`ModalUiState`](current-modal-state.md)
(`ModalUiState.kt`, from #445). Collected in the route host at
[`MainActivity.kt`](thread-screen.md#destination-block). Strings in `res/values/strings.xml`
(`modal_*`). See [Thread screen](thread-screen.md) for how it sits among the other `Scaffold` siblings.

## The render path

```
ThreadViewModel.currentModal : StateFlow<ModalUiState>   ◀── #445 fold (app-level, modalId-keyed)
        │  collectAsStateWithLifecycle in the route host (MainActivity), like isThinking / isStalled
        ▼
ThreadScreen(state, …, modalState: ModalUiState = Hidden, onModalOption = {}, onModalCancel = {})
        │  when (modalState):
        ├─ Open      → PermissionModalOverlay  ── a separate-surface BasicAlertDialog (NOT a LazyColumn row)
        ├─ Dismissed → LaunchedEffect(modalId) { snackbarHostState.showSnackbar(dismissReasonText(source)) }
        └─ Hidden    → Unit
```

`modalState` is a **defaulted** parameter (`= ModalUiState.Hidden`), so every existing preview and
androidTest call site stays inert — no call-site cascade. The route host forwards it exactly like the
`isThinking` / `isStalled` collect-and-forward; `onModalOption` / `onModalCancel` are **not** wired yet
(they default to `{}`). [#451](modal-answer-flow.md) added the VM's `onModalOption` / `onModalCancel`
decision methods (the answer/cancel + arm logic); the render slice **#452** forwards these screen hooks to
`vm::onModalOption` / `vm::onModalCancel` in the route host and draws the armed affordance.

App-level by construction: modal events carry **no `conversation_id`** ([Modal events](modal-events.md)),
so there is **one** `currentModal` across the app and the overlay shows over **whichever thread is active**
— not scoped per conversation.

## The overlay (`Open`)

`PermissionModalOverlay(open: ModalUiState.Open, onOption, onCancel)` is a **`BasicAlertDialog`** (M3,
`@ExperimentalMaterial3Api` — `ThreadScreen` already opts in), **not** the opinionated two-button
`AlertDialog`: the option count is variable (`permission` = 4, `trust` = 2), so all options render
uniformly in a `Column` to preserve array order and a single highlight path.

```kotlin
BasicAlertDialog(
    onDismissRequest = onCancel,                    // inert in this slice
    properties = DialogProperties(
        securePolicy = SecureFlagPolicy.SecureOn,   // FLAG_SECURE on the dialog's OWN window — see Security
        dismissOnBackPress = false,                 // a permission gate must not treat a stray back / outside
        dismissOnClickOutside = false,              //   tap as an implicit answer (cancel is #444)
    ),
) {
    Surface(shape = AlertDialogDefaults.shape, color = .containerColor, tonalElevation = .TonalElevation) {
        Column(Modifier.padding(24.dp)) {
            Text(open.title, style = headlineSmall)   // verbatim, plain Text — never MarkdownText
            Text(open.prompt, style = bodyMedium)     // verbatim, plain Text
            open.options.forEach { option ->          // wire array order = canonical display/selection order
                ModalOptionButton(option.label, isDefault = option.id == open.defaultOptionId, …)
            }
        }
    }
}
```

`open.modalClass` is **not** branched on — there is no per-class layout; the `title` / `prompt` already
carry the human text (the class is carried but unused here, available to #444 / the later visual spec).

### The fail-safe-deny highlight

`ModalOptionButton(label, isDefault, onClick)`: the **default** option (`isDefault == true`) renders as a
high-emphasis filled `Button`; non-defaults render as `OutlinedButton`. This is the **fail-safe-deny
highlight** — the producer (pyrycode#716) marks **no** option "destructive"; its safety design is that the
`defaultOptionId` is always the deny/safe option (`reject_once` / `exit`), so the visually prominent button
is the **safe** one and a careless confirm denies rather than grants. The highlight is driven **solely** by
`option.id == open.defaultOptionId` — **no option-id semantics are interpreted**.

The default also carries `Modifier.semantics { stateDescription = modal_default_option_desc }` ("Default"):
a screen reader announces it, and the screen test locates the default by this semantics value, **not** by
fragile colour inspection.

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

## Security — the two render-time obligations #445 deferred

`security-sensitive`. The verbatim `title` / `prompt` / option `label`s may name a sensitive command or
path; #437 carries them verbatim and #445 keeps them solely in a transient `StateFlow` (no persistence), so
both render-time output-encoding **and** the screen-capture hardening land here.

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
- **No persistence** — no modal-derived text reaches `rememberSaveable` / `SavedStateHandle` / DataStore;
  `modalState` is a hoisted param, the dialog holds no saved state, the snackbar shows a mapped local
  string. No server text survives process death.
- **Accepted residuals (named, not skipped):** `FLAG_SECURE` blocks screen *capture*, not the accessibility
  node tree — a malicious accessibility service can read the verbatim text, but suppressing the a11y tree
  would break legitimate TalkBack users, so it is a platform-level tradeoff, not a regression here.
  Tapjacking on the answer is the render slice **#452**'s concern (taps are inert here, so moot; [#451](modal-answer-flow.md)
  ships the answer logic but not the live taps).

## Non-dismissable here, and the stale-`Open` question

The overlay is purely **state-driven**: `dismissOnBackPress` / `dismissOnClickOutside` are `false`, so it
leaves composition **only** when `currentModal` transitions away from `Open` (a daemon `Dismissed`,
including timeout). `onModalCancel` is declared but inert — the live cancel/back wiring is the render slice
**#452**'s ([#451](modal-answer-flow.md) ships the VM `onModalCancel` logic; #452 forwards the screen hook).
The [#445 open question](current-modal-state.md#lifecycle-errors-edge-cases) — should a connection drop clear
a stale `Open`? — is **not** built here, nor in #451: the daemon validates `modalId` server-side so a stale
answer is rejected (surfacing via #451's error signal), so a proactive stale-clear is a UX nicety deferred to
#452 + the connection signal.

## Testing

Instrumented screen test `app/src/androidTest/.../thread/ThreadScreenModalTest.kt` (`./gradlew
connectedAndroidTest`, device required), mirroring `ThreadScreenOverflowTest`'s idiom — 8 tests:

- **render** — `Open` with a 4-option `permission` list (`defaultOptionId = "reject_once"`): title /
  prompt / all four labels displayed; options render top-to-bottom in **array order** (asserted via
  `boundsInRoot.top` ordering); **exactly one** option carries the `stateDescription` default marker and
  it is `reject_once`.
- **inert hook** — tapping an option invokes only `onModalOption(id)` with the tapped option's `id`
  (locks the contract #444 consumes); no crash.
- **dismissed × 3 sources** — `Dismissed(source ∈ {remote, local, timeout})`: the overlay is gone
  (title `assertDoesNotExist`) and the mapped reason string is displayed.
- **forward-compat** — `Dismissed(source = "some_future_value")` → `modal_dismissed_resolved` (locks the
  no-coercion fallback).
- **hidden** — `Hidden` (the default) → no title / option nodes exist.

No unit test (pure UI; the fold logic is unit-tested in #445). `connectedAndroidTest` was **not** run in
the build environment (no device — the project norm); the test compiles under the green `assembleDebug` /
`check` gates. `FLAG_SECURE` is not assertable via the Compose test API — it is a code-review-verified
invariant.

## Visual spec status

Design source [`16-8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) is the host
**Conversation Thread** frame; the modal overlay itself is **not yet drawn** there — **design-owed**, same
treatment as the sibling Phase-3 interactive surfaces [#407](../codebase/407.md) (thinking indicator) /
[#388](../codebase/388.md) (tool-row status) / [#396](../codebase/396.md) (stall promotion). The behaviour
+ M3 structure are built now against
M3 dialog defaults; the modal's visual spec (and the placeholder string copy + the snackbar-vs-inline
dismiss affordance) reconcile when it lands.

## Related

- [#446 implementation notes](../codebase/446.md) — files, line refs, lessons, code-review NITs.
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
- Sibling slices: **#444** answering / cancelling (blocked by this), split into
  [**#451**](modal-answer-flow.md) the behavior (shipped) + **#452** the armed-affordance render · **#440**
  read-only device mode (re-pointed onto #452).
- Producer SSOT: pyrycode#716 (`permission` / `trust` classes; fail-safe-deny `default_option_id`, no
  per-option destructive marker); ADR 025 § Phase 3 modals, EPIC pyrycode#597.
