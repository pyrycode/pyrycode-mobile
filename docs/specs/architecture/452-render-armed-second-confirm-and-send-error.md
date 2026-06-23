# #452 — Render the armed second-confirm affordance + send-error; wire the route host

**Ticket:** [#452](https://github.com/pyrycode/pyrycode-mobile/issues/452) · **Size:** S · **Labels:** `security-sensitive`, `size:s`
**Split from #444** (this is the **render** half — armed/second-confirm affordance + send-error snackbar + route-host forward; no decision logic). The sibling **behavior** half **#451** (VM arm/confirm decision + outbound send) **shipped** (PR #453, merged) and is this slice's `blockedBy`. Builds on **#446** (base overlay, shipped) and **#445** (`currentModal` projection, shipped). Leaf dependent: **#440** (reactive read-only on ungranted-device reject) is `blockedBy` this slice.

**Consumes (shipped, verbatim — no re-scoping in the UI):**
- `ThreadViewModel.armedOptionId: StateFlow<String?>` (`ThreadViewModel.kt:323`) — the non-default option of the *currently-open* modal that is armed, or `null`. Already scoped by the VM (`null` unless the arm's `modalId` matches the open modal; cleared on resolve/cancel/re-tap).
- `ThreadViewModel.modalSendErrors: Flow<Unit>` (`ThreadViewModel.kt:337`) — one-shot "a modal send failed" event, **payload-free** (`Unit`), same idiom as `navigationEvents`.
- `ThreadViewModel.onModalOption(optionId: String)` (`:392`) / `onModalCancel()` (`:404`) — the decision methods the route host binds to the (currently-inert) screen hooks.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

`16-8` is the **host Conversation Thread** frame the modal overlays; the permission-modal surface itself is **not yet drawn** in the locked file — **design-owed**, the same treatment #446 used for the base overlay and the sibling Phase-3 interactive surfaces (#386 / #388 / #396). Build the behavior + M3 structure now (extend the existing centered M3 `BasicAlertDialog` over the thread frame); the visual spec for the armed/confirm step + the cancel/error affordances lands later and reconciles then. Match M3 defaults and the in-file precedents (`DeleteConfirmationDialog`, the #446 `PermissionModalOverlay`) — do **not** invent decorative detail.

## Files to read first

(Generated from `codegraph_context` + the reads done for this spec; off-topic hits pruned.)

| Path / lines | What to extract |
|---|---|
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:81-103` | `ThreadScreen` signature. Add the two new **defaulted** params here (`armedOptionId`, `modalSendErrors`) so previews + the existing androidTests stay inert (no call-site cascade). Note `onModalOption`/`onModalCancel` already exist, defaulted + inert (`:102-103`). |
| `…/ThreadScreen.kt:105-110` | `snackbarHostState = remember { SnackbarHostState() }` (`:107`) + the `Scaffold(snackbarHost = …)` wiring (#446). The error collect targets **this** host — do not add a second host. |
| `…/ThreadScreen.kt:314-333` | The `when (modalState)` overlay block + the existing `Dismissed` → `LaunchedEffect(modalId){ showSnackbar }` snackbar (#446). The armed param threads into `PermissionModalOverlay`; the error collect lives near here. |
| `…/ThreadScreen.kt:354-397` | `PermissionModalOverlay` — add the `armedOptionId` param, the per-option `isArmed`, the explicit Cancel affordance, and the tapjacking filter. Keep `Text(open.title)`/`Text(open.prompt)` verbatim-plain (security). |
| `…/ThreadScreen.kt:405-425` | `ModalOptionButton(label, isDefault, onClick)` — add `isArmed`; turn the 2-way (`Button`/`OutlinedButton` + default `stateDescription`) into the 3-way in §2. |
| `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsScreen.kt:44-55` | **The precedent to mirror for the error snackbar:** a screen takes `effects: Flow<…> = emptyFlow()` (`:44`) and collects it internally via `LaunchedEffect(effects, snackbarHostState){ effects.collect { … showSnackbar(localString) } }` (`:48-52`). Copy this shape for `modalSendErrors`. |
| `app/src/main/java/de/pyryco/mobile/MainActivity.kt:344-376` | Thread route host. `navigationEvents` one-shot collect (`:352-358`) + the `isThinking`/`isStalled`/`currentModal` collect-and-forward (`:349-351`, `:367`). Add `armedOptionId` the same way; forward the 4 new args. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:323-337` | `armedOptionId: StateFlow<String?>` + `modalSendErrors: Flow<Unit>` — the shipped signals this slice consumes verbatim (read the KDoc: already scoped / payload-free). |
| `…/ThreadViewModel.kt:392-447` | `onModalOption` / `onModalCancel` / `sendAnswer` / `sendCancel` — the authoritative two-tap rule (default → single-tap send; non-default → arm, then second-tap send; cancel → clear + `modal_cancel`). The AC#4 screen-test stand-in mimics this; the VM is the oracle, unit-tested in #451. |
| `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenModalTest.kt` (full, ~165 lines) | The screen-test idiom to **extend** (`createComposeRule`, `setContent { PyrycodeMobileTheme { ThreadScreen(...) } }`, `string(resId)`, `onNodeWithText`/`SemanticsMatcher` on `stateDescription`, `performClick`). The default-highlight test (`:100-114`) is the template for the armed-marker assertion. |
| `app/src/main/res/values/strings.xml:85-89` | Existing `modal_*` keys (`modal_default_option_desc`, `modal_dismissed_*`). Add the 3 new keys in this block, same naming. |
| `docs/specs/architecture/446-render-permission-modal-overlay.md` | The base overlay design + security posture this slice extends (FLAG_SECURE via `SecureOn`, verbatim plain `Text`, dismiss-reason confidentiality, the named tapjacking hand-off → this slice). |
| `docs/specs/architecture/451-wire-modal-answer-cancel-arming-error.md` | The behavior half: exact shape of `armedOptionId`/`modalSendErrors` and the two-tap fail-safe-deny rule. |

## Context

pyrycode#597 Phase 3 (ADR 025) surfaces a permission/choice modal over the encrypted mobile wire. The prior slices shipped:

- **#437** decodes `modal_shown`/`modal_dismissed` → `RemoteConversationRepository.modalEvents`.
- **#445** folds that to the hoisted app-level `ThreadViewModel.currentModal: StateFlow<ModalUiState>` (`Hidden`/`Open(…, defaultOptionId)`/`Dismissed`).
- **#446** renders the `Open` overlay (M3 `BasicAlertDialog`, options in array order, fail-safe-deny default highlighted) + the `Dismissed` snackbar; leaves the screen's `onModalOption`/`onModalCancel` hooks **inert** and the overlay non-dismissable.
- **#451** adds the VM decision logic: `onModalOption`/`onModalCancel` turn taps into `answerModal`/`cancelModal` sends with the **fail-safe-deny single-tap / second-confirm** belt, exposing `armedOptionId` (the armed non-default option) + `modalSendErrors` (a payload-free failure event).

This slice is the **render + wire** glue. It (1) draws the **second-confirm affordance** for the armed non-default option (off the `armedOptionId` input — no UI-side arming), (2) adds an explicit **Cancel** affordance so `onModalCancel` becomes reachable, (3) surfaces the **send-error** on the existing snackbar via a **mapped local string** (the payload-free event + a local string ⇒ nothing sensitive reaches the un-secured Activity window), and (4) **wires the route host** so the inert hooks go live (`onModalOption`/`onModalCancel`) and the two new signals reach the screen.

**The producer (pyrycode#716) carries no per-option "destructive" marker.** The safety design is the fail-safe-deny `default_option_id`: the highlighted default answers on a single tap; every other option requires a deliberate second confirm. That decision lives entirely in #451 — this slice only **renders** the armed state #451 reports and forwards taps verbatim. The UI never inspects option-id semantics and never re-derives the arm.

## Design

Two production Kotlin files change (`ThreadScreen.kt`, `MainActivity.kt`) + 3 string resources. **No new public type. No VM change. No data-layer change.** The arm state stays hoisted on the VM; the composables remain stateless functions of their inputs.

### 1. `ThreadScreen.kt` — two new defaulted params + thread `armedOptionId` into the overlay + collect the error

**Signature additions** (defaulted ⇒ previews + existing androidTests stay inert):

```kotlin
armedOptionId: String? = null,                       // #452: the armed non-default option, or null (VM-scoped)
modalSendErrors: Flow<Unit> = emptyFlow(),           // #452: payload-free one-shot send-failure signal
```

**Error snackbar collect** (mirror `ArchivedDiscussionsScreen:44-55` exactly — the established VM-event→snackbar idiom). Add inside `ThreadScreen`, after `snackbarHostState` is created:

```kotlin
val sendFailedMessage = stringResource(R.string.modal_send_failed)   // mapped LOCAL string, never the payload
LaunchedEffect(modalSendErrors, snackbarHostState) {
    modalSendErrors.collect { snackbarHostState.showSnackbar(sendFailedMessage) }
}
```

- **Why collect here, not in `MainActivity`** (a deliberate deviation from the ticket's Technical Note): the `snackbarHostState` is owned inside `ThreadScreen` (#446, `:107`), and the codebase's established pattern (`ArchivedDiscussionsScreen`) collects a VM one-shot `Flow` into the screen's own host. Hoisting the host up to `MainActivity` would fork the #446 `Dismissed` snackbar across two files for no gain. `navigationEvents` stays in `MainActivity` because its effect (`navController`) is a host concern; `modalSendErrors`' effect (the snackbar) is a screen concern — each one-shot is collected where its effect lives. The "mirrors the `navigationEvents` one-shot idiom" AC is satisfied: a `LaunchedEffect`-scoped `Flow.collect`. **Single consumer** — `modalSendErrors` is a `Channel.receiveAsFlow()`; only `ThreadScreen` collects it (`MainActivity` only forwards the reference).
- **Confidentiality (load-bearing):** the message is a fixed local string (`modal_send_failed`); the event carries `Unit` (#451), so **structurally** no `modalId`/`optionId`/command/path can reach the snackbar — which draws in the un-secured Activity window. This mirrors #446's `dismissReasonText` posture (map to local, never echo the wire token).

**Forward `armedOptionId` into the overlay** — in the existing `is ModalUiState.Open` branch (`:318-323`), pass it through:

```kotlin
is ModalUiState.Open ->
    PermissionModalOverlay(open = modalState, armedOptionId = armedOptionId, onOption = onModalOption, onCancel = onModalCancel)
```

### 2. `PermissionModalOverlay` + `ModalOptionButton` — the armed affordance, the Cancel button, the tapjacking filter

**`PermissionModalOverlay`** gains an `armedOptionId: String?` param. Inside the option loop, compute `isArmed = option.id == armedOptionId` alongside the existing `isDefault`:

```kotlin
open.options.forEach { option ->
    ModalOptionButton(
        label = option.label,
        isDefault = option.id == open.defaultOptionId,
        isArmed = option.id == armedOptionId,         // VM guarantees this is never the default option
        onClick = { onOption(option.id) },             // unchanged: every tap forwards verbatim; the VM decides arm vs send
    )
}
```

After the option `Column`, add an **explicit Cancel affordance** (`onModalCancel` is otherwise unreachable — back/outside-dismiss stay disabled per #446's "no implicit answer" rule):

```kotlin
TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
    Text(stringResource(R.string.modal_cancel))
}
```

- Keep `dismissOnBackPress = false` / `dismissOnClickOutside = false` (#446) — a permission gate must not read a stray back-press/outside-tap as cancel. Cancel is the **explicit** low-emphasis `TextButton` only. `onDismissRequest = onCancel` stays as-is (inert while both flags are false; minimize the diff).

**`ModalOptionButton`** — extend the 2-way to a deterministic 3-way on `(isArmed, isDefault)`. Behaviour contract (developer writes the body; the AC#4 test is the oracle):

| State | Emphasis | Semantics marker | Notes |
|---|---|---|---|
| `isArmed` (armed non-default, pending confirm) | elevated vs its resting `OutlinedButton` — e.g. `FilledTonalButton` (kept **below** the default's filled `Button` so the safe default stays visually dominant) | `stateDescription = R.string.modal_armed_option_desc` ("Tap again to confirm") | visible cue (emphasis change ± a confirm hint) is **design-owed**; the load-bearing testable contract is the marker + behavior. Never reconstruct/echo the command — the verbatim `label` already shows the choice. |
| `isDefault` (fail-safe-deny default) | filled `Button` (unchanged, #446) | `stateDescription = R.string.modal_default_option_desc` ("Default") | single-tap answers; never armed (VM). |
| neither (resting non-default) | `OutlinedButton` (unchanged, #446) | none | first tap arms it (VM); render is unchanged until `armedOptionId` flips. |

- `isArmed`/`isDefault` are disjoint in practice (`armedOptionId != defaultOptionId`, guaranteed by #451's branch order). The composable still drives off the two booleans with `isArmed` taking precedence in the `when` — no assumption needed, no UI-side semantics.
- **Stateless** — the composable holds **no** `remember`-based arming. It is a pure function of `(label, isDefault, isArmed, onClick)`; the arm transition is the VM's (`armedOptionId` re-renders the affordance).

**Tapjacking filter (deterministic security net — owned by this slice per #446/#451's named hand-off).** The option taps are now **live** (they answer the permission gate — a high-consequence action). Set touch-obscured filtering on the dialog's **own** window so an obscuring overlay cannot drive a confirm. Inside `PermissionModalOverlay`, obtain the dialog window via the standard Compose idiom and filter:

```kotlin
val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window   // androidx.compose.ui.window.DialogWindowProvider
SideEffect { dialogWindow?.decorView?.filterTouchesWhenObscured = true }
```

- This is the direct analog of `android:filterTouchesWhenObscured` (View API, fully supported on min SDK 33), set on the dialog's decorView — a **deterministic** net, distinct fabric from the second-confirm UX belt (belt-and-suspenders). It complements (does not replace) the fail-safe-deny + second-confirm: those already mean a single obscured tap can at worst arm/deny, never allow; this additionally hardens the deliberate two-tap tapjack on an *allow*.
- **Code-review-verified invariant** — like #446's `securePolicy = SecureOn`, a window flag has no Compose-test semantics node, so it cannot be asserted at runtime. Verify by inspection.

### 3. String resources (`strings.xml:85-89` block — copy is **design-owed** placeholders)

- `modal_send_failed` — e.g. "Couldn't send your answer. Try again." (the error snackbar; **no** payload, generic by construction)
- `modal_armed_option_desc` — e.g. "Tap again to confirm" (`stateDescription` for the armed option; a11y + test marker, mirrors `modal_default_option_desc`)
- `modal_cancel` — e.g. "Cancel" (the explicit Cancel `TextButton`)

### 4. `MainActivity.kt` — collect + forward (route host, `:344-376`)

In the thread destination, add the `armedOptionId` state collect alongside `isThinking`/`isStalled`/`currentModal`, then forward the four new args:

```kotlin
val armedOptionId by vm.armedOptionId.collectAsStateWithLifecycle()
// …existing navigationEvents LaunchedEffect unchanged…
ThreadScreen(
    /* …existing args… */,
    modalState = modalState,
    armedOptionId = armedOptionId,
    modalSendErrors = vm.modalSendErrors,        // forwarded reference; ThreadScreen collects it (§1)
    onModalOption = vm::onModalOption,            // was defaulting to {} (inert) — now live
    onModalCancel = vm::onModalCancel,            // was defaulting to {} (inert) — now live
)
```

No other route-host change. `modalSendErrors` is **forwarded, not collected** here (single-consumer; the screen owns the collect).

## State + concurrency model

- **Single source of state.** The arm state lives only on the VM (`armedModalOption` → scoped `armedOptionId`, #451). `ThreadScreen` stays stateless — it renders a pure function of `(modalState, armedOptionId)` and forwards taps. No `remember`/`rememberSaveable` of any modal-derived value (the modal text may name a sensitive command/path).
- `armedOptionId` is collected once in the route host via `collectAsStateWithLifecycle` (the `isThinking`/`currentModal` precedent) and passed as a plain `String?` param.
- `modalSendErrors` is collected once, inside `ThreadScreen`, in `LaunchedEffect(modalSendErrors, snackbarHostState)` (stable keys ⇒ no restart on recomposition; the `ArchivedDiscussionsScreen` precedent). Runs on the composition's lifecycle scope; no `viewModelScope`/dispatcher work in this slice.
- `snackbarHostState = remember { SnackbarHostState() }` is local UI plumbing (correct to `remember`, not `rememberSaveable` — no modal text persisted).
- App-level by construction (#445/#446): one overlay over whichever thread is active; the modal carries no `conversation_id`, so no per-conversation filter.

## Error handling

No new failure modes are introduced by this slice. The send failures themselves are produced + caught in #451 (server `error` → `RelayErrorException`; not-connected → `IllegalStateException`; cancellation re-thrown); they arrive here only as a payload-free `modalSendErrors` `Unit`, surfaced as a single transient snackbar with a fixed local string.

| Surface | Behavior |
|---|---|
| `modalSendErrors` emits | one transient snackbar (`modal_send_failed`); `currentModal` stays `Open` (#451) so the user may re-answer. No retry, no state mutation here. |
| `armedOptionId` flips to `null` (resolve/cancel/re-tap, VM) | the armed affordance disappears on the next recomposition; deterministic, no UI logic. |
| Unknown/empty `armedOptionId` not matching any option | no option is armed (the `==` simply never matches); benign. |
| `when (modalState)` | already exhaustive over the sealed `ModalUiState` (#446); unchanged. |

## Security posture (`security-sensitive`)

See the adversarial pass in **§ Security review**. Load-bearing properties this slice owns or preserves:

- **No payload to the un-secured window.** The error snackbar shows a fixed local string; the `modalSendErrors` event is `Unit` (#451) ⇒ no command/path can reach the snackbar (which draws outside the dialog's `FLAG_SECURE` window). Same posture as #446's mapped dismiss reason.
- **Inert output-encoding preserved.** The armed affordance and Cancel button add only **local** strings; the verbatim `title`/`prompt`/`label` still render through plain `Text` (#446) — never `MarkdownText`, no `SelectionContainer`, no format-pattern interpolation.
- **Fail-safe-deny preserved; the UI cannot make an allow easier.** The render only *reflects* `armedOptionId` (VM-owned, scoped); it never re-derives the arm, never interprets option-id semantics, never auto-answers. Every tap forwards verbatim to `onModalOption(id)`; the second-confirm gate is the VM's. Cancel sends `modal_cancel` (cannot grant).
- **Tapjacking — deterministic net added.** `filterTouchesWhenObscured = true` on the dialog's own window (§2) — the render-time mitigation #446/#451 named as owed to this slice, now that taps are live.
- **No new trust boundary, no persistence, no minted value.** Operates on already-decoded in-process state; nothing modal-derived reaches `rememberSaveable`/`SavedStateHandle`/DataStore; no token/key/nonce handled (the `answer_token` is #438's, server-validated).

## Testing strategy

Instrumented screen test (`./gradlew connectedAndroidTest`, device required) — **extend** `ThreadScreenModalTest.kt`, mirroring its idiom. The VM's authoritative arm/confirm rule is already unit-tested in #451's `ThreadViewModelTest`; this slice tests the **rendered affordance + the hook forwarding**. For the two-tap flow, back `armedOptionId` + `onModalOption` with a small **stateful stand-in** that faithfully mimics #451's rule (`var armed by mutableStateOf<String?>(null)`; default→record send; non-default→arm, second tap→record send + clear), recomposing `armedOptionId = armed` — so "single tap does not confirm, second tap confirms" is observable at the screen layer (AC#4) without re-implementing the VM.

Scenarios (AC#4 — bullets, not pre-written bodies):

- **armed non-default renders the second-confirm affordance** — `Open` modal, `armedOptionId = "allow_once"`; assert exactly the `allow_once` option carries the armed `stateDescription` (`modal_armed_option_desc`) and **no** other option (incl. the default `reject_once`) does. Mirror the existing default-highlight test (`:100-114`) but on the armed marker.
- **default shows no arm step** — `armedOptionId = null` (and again with `armedOptionId = "allow_once"`); assert the default option never carries the armed marker (it answers on a single tap).
- **tap forwarding** — recording `onModalOption`: tap the default → `onModalOption("reject_once")`; tap a non-default → `onModalOption("allow_once")` (extends the existing `:116-124` test). Tap Cancel → `onModalCancel()` fires.
- **two-tap confirm via the VM-mimicking stand-in** — default tap → a send is recorded immediately, no arm; first tap of `allow_once` → **no** send recorded **and** the armed affordance now renders on `allow_once`; second tap of `allow_once` → send recorded. (This is the AC#4 "single tap does not confirm, second tap confirms" at the screen layer.)
- **send-error → snackbar with the local string, no payload** — drive `modalSendErrors` (e.g. a `MutableSharedFlow<Unit>`/`Channel` fed into the param) to emit once; assert `modal_send_failed` is displayed and that **no** modal payload substring (e.g. the `prompt`'s `rm -rf …`) appears in the snackbar. (Locks the confidentiality contract.)

`filterTouchesWhenObscured` and `FLAG_SECURE` (#446) are window flags with no Compose-test semantics node — **code-review-verified**, not runtime-asserted.

## Open questions

- **Visible armed cue.** The load-bearing contract is the `stateDescription` marker + the two-tap behavior; the exact visible treatment (emphasis change, confirm-hint text, icon) is **design-owed** and reconciles with the later visual spec. The spec's `FilledTonalButton` suggestion keeps the safe default visually dominant; a designer may revise.
- **Arm-on-failed-answer.** #451 clears the arm on the send *attempt* (gesture consumed), so a failed answer leaves the option un-armed; the user re-arms with two taps. Keeping it armed for a one-tap retry is a deferred UX call (#440/#452-followup), not an AC here.
- **Distinguishing the #702 ungranted-device reject** (the #440 read-only extension point). This slice surfaces a generic failure snackbar off `Flow<Unit>`; #440 may extend the event payload to carry the non-sensitive `RelayErrorException.code` to drive a reactive read-only mode. Additive — the seam is #451's; not built here (evidence-based: no consuming behavior yet).

## Acceptance criteria → design mapping

1. armed `armedOptionId` (non-null) renders a distinct second-confirm affordance; the highlighted default shows no arm step → §2 `ModalOptionButton` 3-way + armed `stateDescription`.
2. route host collects `vm.armedOptionId` + forwards it; wires the inert `onModalOption`/`onModalCancel` to `vm::onModalOption`/`vm::onModalCancel` → §4 `MainActivity`.
3. failed send (`modalSendErrors`) surfaces on the existing snackbar via a mapped local string, never echoing the payload; one-shot collect idiom → §1 error collect (the `ArchivedDiscussionsScreen` precedent) + payload-free `Unit` event.
4. screen test drives tap-default → `onModalOption(default)`; non-default → armed affordance renders, single tap ≠ confirm, second tap confirms; cancel → `onModalCancel` → § Testing strategy.

## Scope (size self-check)

**Production source files modified** (excluding `*Test.kt`, `*.md`, `*.xml`, this spec): `ThreadScreen.kt`, `MainActivity.kt` = **2** (well below the ≥5 gate). **New files: 0. New exported types: 0** (params on existing functions; `ModalOptionButton`/`PermissionModalOverlay` stay `private`). **Consumer cascade: 0** — the two new `ThreadScreen` params are defaulted (only `MainActivity` passes them); `ModalOptionButton`/`PermissionModalOverlay` have one internal call site each. **Decision/reject branches: 1** render branch (`isArmed`) — no state machine; the arm/confirm/error branches are all #451's. **Total written LOC** (~35 `ThreadScreen.kt`, ~5 `MainActivity.kt`, ~3 strings, ~120 androidTest, this spec) ≈ **~165 production+test** — below the ~400 S line and the ~600 split line. **No red line tripped — solidly S.**

## Security review

**Reviewer:** architect (self-review; `agents/architect/security-review.md` is not synced into this worktree — performing the pass inline using the standard adversarial categories, per the #446/#445/#451 precedent).
**Date:** 2026-06-23
**Verdict:** PASS

Run adversarially against the spec above, assuming it has holes. This slice makes the permission-answer taps **live** and adds two new render surfaces (the armed affordance + the Cancel button) plus a snackbar fed by a failure event. The adversarial questions: can the new render surfaces leak the modal payload; can the live taps or the Cancel button bypass the fail-safe-deny belt or be tapjacked into an allow; does the UI re-derive or weaken any VM-owned safety property; does anything modal-derived get persisted or logged.

**Findings:**

- **[Output redaction — the snackbar draws OUTSIDE the secured window].** No findings (addressed by design, and reinforced by #451's event shape). The dialog's `FLAG_SECURE` (#446 `SecureOn`) covers only the dialog window; the snackbar draws in the un-secured Activity window. This slice surfaces a **fixed local string** (`modal_send_failed`) and the source event is `Unit` (#451) — so it is **structurally impossible** for a `modalId`/`optionId`/command/path to reach the snackbar. The `RelayErrorException.message` is caught and dropped in #451; it is never threaded here. Mirrors #446's mapped-not-echoed dismiss-reason posture. (Tested: the send-error scenario asserts no payload substring appears.)
- **[Output-encoding / injection sink — the new render surfaces].** No findings. The armed affordance, the Cancel button, and the snackbar render only **local** string resources (`modal_armed_option_desc`, `modal_cancel`, `modal_send_failed`). The verbatim server strings (`title`/`prompt`/option `label`) continue to render through plain `Text` (#446) — never `MarkdownText`, no `SelectionContainer` (a clipboard-exfiltration path past `FLAG_SECURE`), no `String.format`/`stringResource(id, …)` pattern that could re-interpret a `%` in server text. The armed render does **not** reconstruct or re-emit the command/path — the already-shown verbatim `label` is the only server text on the armed button.
- **[Fail-safe-deny integrity — can the live taps or the render bypass the second-confirm].** No findings. The UI is a pure reflector: `isArmed = option.id == armedOptionId` (a plain `==` against the VM-owned, scoped projection — a render highlight, not a secret comparison) and every tap forwards verbatim via `onClick = { onOption(option.id) }`. The screen never decides arm-vs-send, never interprets option-id semantics, never auto-answers, and cannot turn a single non-default tap into a send (the VM's branch order is the gate, #451). A stale arm cannot render on a fresh modal because `armedOptionId` is VM-scoped to the open `modalId` (#451) — the UI inherits that property, it does not need to re-enforce it.
- **[Cancel affordance — does it grant anything].** No findings. The new `TextButton` calls `onModalCancel` → `cancelModal` (#451) → `modal_cancel` on the wire — a withdrawal, never an allow. Back-press/outside-tap dismissal stay disabled (#446), so cancel is a deliberate explicit tap; there is no implicit-answer path. `onDismissRequest` remains inert while both dismiss flags are false.
- **[Android attack surface — tapjacking].** **Mitigated here (the named hand-off from #446/#451 is now discharged).** The taps are live and high-consequence, so this slice sets `filterTouchesWhenObscured = true` on the dialog's own window (§2) — a deterministic, non-experimental View-level net (min SDK 33) that drops touches delivered while an overlay obscures the dialog. This is *different fabric* from the fail-safe-deny + second-confirm UX belt (belt-and-suspenders): the belt means a single obscured tap can at worst arm/deny; the touch filter additionally hardens the deliberate two-tap tapjack of an *allow*. Code-review-verified (window flag, no test semantics node), matching #446's treatment of `SecureOn`.
- **[Confidentiality — no persistence].** No findings. `armedOptionId` is a transient param, `modalSendErrors` a one-shot event, the snackbar message a mapped local string, `snackbarHostState` a plain `remember`. No modal-derived value reaches `rememberSaveable`/`SavedStateHandle`/DataStore; no server text survives process death via saved-instance state (#446's no-persist invariant preserved).
- **[Mint / trust-bearing values, logs, replay].** N/A — this slice mints nothing (the `answer_token` is #438's, server-validated), adds no `Log.*`/`Timber`, and introduces no replay surface (it forwards taps to #451, which owns no-auto-retry). The error event carries `Unit`; nothing to log.
- **[Secrets / crypto / file ops / subprocess / network / DoS / new IPC].** N/A — no secret/key, no crypto (transport's), no I/O or path handling (a path that appears *as text* in `prompt` is never used as a path), no execution, no new socket/intent/deep link/exported component, no attacker-controlled unbounded allocation (fixed render per modal). `data/` stays portable (no `data/` change at all).
- **[Threat model alignment].** Aligned with `protocol-mobile.md § Security model` + ADR 025. The render pushes all authority to the daemon (unguessable `modal_id` #706, per-device gate #702, first-answer-wins #703, all server-side), faithfully surfaces the producer's fail-safe-deny default + the VM's second-confirm without re-interpretation, adds a deterministic tapjacking net now that taps are live, and introduces no trust boundary, persisted copy, or sensitive log/echo.

**Producer / upstream dependencies (already owned, not this slice's work):** the arm/confirm decision + payload-free error event + cancellation-safe send (#451); the FLAG_SECURE dialog window + verbatim-Text rule + dismiss-reason confidentiality (#446); unguessable `modal_id` + dedup + per-device gate + AEAD session (#702/#703/#706/#571). All merged.
