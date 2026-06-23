# #446 — Render the permission-modal overlay

**Ticket:** [#446](https://github.com/pyrycode/pyrycode-mobile/issues/446) · **Size:** S · **Labels:** `security-sensitive`
**Split from #443** (this is slice B, the render overlay; #445 is slice A, projection/state). **Blocked by #445** (merged, PR #447). The interaction half (answer / second-confirm / cancel sends) is the sibling **#444**, blocked by this one. Depends on #437's decoded `modalEvents` (shipped), reached via #445's `currentModal` projection.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

`16-8` is the **host Conversation Thread** frame (dark theme: right-aligned blue user bubbles, left-aligned dark assistant bubbles, a tool-call chip, a session-boundary horizontal-rule delimiter, the `Opus 4.7 · high · 73% used` status row, and the message input bar). The permission-modal overlay itself is **not yet drawn** — it is **design-owed**, same treatment as the sibling Phase-3 interactive surfaces #386 / #388 / #396. Build the behaviour + M3 structure now (a centered M3 dialog surface floating over this thread frame); the modal's visual spec lands later and reconciles then. Do not invent decorative detail — match M3 dialog defaults and the existing dialog precedents (`DeleteConfirmationDialog`, `StatusSheet`).

## Files to read first

| Path / lines | What to extract |
|---|---|
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ModalUiState.kt` (full, ~95 lines) | The sealed type this slice consumes: `Hidden` / `Open(modalId, modalClass, title, prompt, options, defaultOptionId)` / `Dismissed(modalId, outcome, source)`. Read the KDoc: every field is verbatim/inert, output-encoding + fail-safe-deny highlight are **this slice's** responsibility. |
| `app/src/main/java/de/pyryco/mobile/data/model/ModalEvent.kt:60-78` | `ModalOption(id, label)` — the option shape rendered in the list. `id` = opaque echo token (#444), `label` = operator text shown verbatim. `options` preserves wire array order = canonical display order. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:227-317` | The **inline-dialog precedent**: conditional dialogs (`RenameDialog`, `SaveAsChannelDialog`, `StatusSheet`, `ChannelInfoSheet`, `DeleteConfirmationDialog`) rendered as siblings after the `Scaffold`; `DeleteConfirmationDialog` (:296-317) is the private-composable shape to mirror. Also the `Scaffold` (:89-115) you add a `snackbarHost` to. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:66-86` | The `ThreadScreen` signature — add the new defaulted params here so existing previews/tests stay inert. |
| `app/src/main/java/de/pyryco/mobile/MainActivity.kt:341-375` | The thread destination route host: `collectAsStateWithLifecycle` of `isThinking` (:349) / `isStalled` (:350) and forwarding them into `ThreadScreen`. Add `currentModal` the same way. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:289-300` | `val currentModal: StateFlow<ModalUiState>` — the hoisted source this slice collects in the route host. No VM change in this slice. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/LiteralScreenSurface.kt:144-226` | `SecureScreen()` FLAG_SECURE precedent (#381) **and** the verbatim-render rule (`SnapshotContent`, :144-170: plain `Text`, never `MarkdownText`). Note the contrast: that surface flags the **Activity** window; a dialog draws in its **own** window, so this slice uses `DialogProperties.securePolicy` instead (see Design §2). |
| `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsScreen.kt:46-73` | The dismiss-reason **snackbar precedent**: `remember { SnackbarHostState() }`, `LaunchedEffect(…) { snackbarHostState.showSnackbar(resources.getString(...)) }`, `Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) })`. Mirror this. |
| `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenOverflowTest.kt:1-60` | The screen-test idiom: `createComposeRule()`, `setContent { PyrycodeMobileTheme { ThreadScreen(...) } }`, `string(resId)` helper, `onNodeWithText` / `onNodeWithContentDescription` + `assertIsDisplayed`. The AC#5 test mirrors this. |
| `app/src/main/res/values/strings.xml` | Add the dismiss-reason + default-option-semantics strings here; follow the existing `delete_dialog_*` / `literal_screen_*` key naming. |

## Context

#445 (merged) folds the `replay = 0` `modalEvents` stream (#437) into a single hoisted **app-level** observable, `ThreadViewModel.currentModal: StateFlow<ModalUiState>` — a sibling to the existing transient thread signals (`isThinking`, `isStalled`). `ModalUiState` is `Hidden` / `Open` / `Dismissed`; every field is carried verbatim and inert.

This slice is **render-only**: collect `currentModal` in the route host, forward it into the **stateless** `ThreadScreen`, and render the `Open` state as a separate-surface overlay (M3 dialog, **not** a `LazyColumn` row) — title, prompt, options in array order, the `defaultOptionId` option highlighted as the producer's fail-safe-deny default. On `Dismissed`, the overlay is gone and the resolution `source` is surfaced. Option taps / cancel / the `modal_answer`·`modal_cancel` sends are the sibling **#444** (this slice leaves those hooks inert).

The modal carries **no `conversation_id`** (sole key is `modalId`), so the overlay is app-level — it shows over whichever thread is active.

## Design

Two production Kotlin files change; no new public type, no VM change, no data-layer change.

### 1. `ThreadScreen.kt` — new defaulted params + the overlay + the dismiss snackbar

**Signature additions** (all defaulted ⇒ previews and existing androidTests stay inert — no call-site cascade):

```kotlin
modalState: ModalUiState = ModalUiState.Hidden,
onModalOption: (String) -> Unit = {},   // INERT in this slice; #444 wires it (passes ModalOption.id)
onModalCancel: () -> Unit = {},          // INERT in this slice; #444 wires it
```

**Scaffold** — add a snackbar host (mirror `ArchivedDiscussionsScreen`):
`val snackbarHostState = remember { SnackbarHostState() }` near the top of `ThreadScreen`; pass `snackbarHost = { SnackbarHost(snackbarHostState) }` to the existing `Scaffold`. This is the only change to the existing Scaffold.

**Overlay + dismiss handling** — a `when (modalState)` block rendered as a sibling **after** the existing dialog blocks (after `ThreadScreen.kt:293`), exactly where `DeleteConfirmationDialog` is conditionally rendered:

- `is ModalUiState.Open` → `PermissionModalOverlay(open = modalState, onOption = onModalOption, onCancel = onModalCancel)`.
- `is ModalUiState.Dismissed` → a `LaunchedEffect(modalState.modalId) { snackbarHostState.showSnackbar(message = …) }` that surfaces the resolution reason **once** on entry (`Dismissed` is a sticky terminal state in #445's fold, so keying on `modalId` fires exactly once per resolution). No overlay rendered ⇒ overlay removed (AC #3).
- `ModalUiState.Hidden` → nothing.

**`PermissionModalOverlay`** (new private composable, inline in `ThreadScreen.kt` per the `DeleteConfirmationDialog` precedent):

```kotlin
@Composable
private fun PermissionModalOverlay(open: ModalUiState.Open, onOption: (String) -> Unit, onCancel: () -> Unit)
```

Behaviour contract (developer writes the body; the AC#5 test is the oracle):

- Use **`BasicAlertDialog`** (M3, `@ExperimentalMaterial3Api` — `ThreadScreen` already opts in), **not** the opinionated 2-button `AlertDialog`: the option count is variable (permission = 4, trust = 2), so render all options uniformly in a `Column` to preserve array order and a single highlight path. The `DeleteConfirmationDialog` precedent is the *inline-private-composable structural* pattern, not the exact `AlertDialog` API.
- `properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn, dismissOnBackPress = false, dismissOnClickOutside = false)` — see §2 (securePolicy) and §3 (non-dismissable rationale).
- Inside, a `Surface` (M3 dialog shape/color/tonal-elevation — `AlertDialogDefaults.shape` / `.containerColor` / `.TonalElevation`) wrapping a `Column(Modifier.padding(24.dp))`:
  - `Text(open.title, style = MaterialTheme.typography.headlineSmall)` — **verbatim, plain `Text`** (never `MarkdownText`; see Security).
  - `Text(open.prompt, style = MaterialTheme.typography.bodyMedium)` — verbatim, plain `Text`.
  - `open.options.forEach { option -> ModalOptionButton(option, isDefault = option.id == open.defaultOptionId, onClick = { onOption(option.id) }) }` — iterate in array order (canonical display order).
- `open.modalClass` is **not** branched on — no per-class layout; title/prompt already carry the human text. Carried but unused here (available to #444 / the later visual spec).

**`ModalOptionButton`** (new private composable): the **default** option (`isDefault == true`) renders as a high-emphasis filled `Button`; non-default options render as `OutlinedButton`. This is the fail-safe-deny highlight — the producer's `defaultOptionId` is always the deny/safe option (`reject_once` / `exit`), so the visually prominent button is the safe one (AC #1). **Also** attach a test-observable + accessible marker to the default option: `Modifier.semantics { stateDescription = <R.string.modal_default_option_desc> }` (a screen reader announces "default"; the AC#5 test locates the default by this semantics, not by fragile colour inspection). `onClick = onClick` — inert in this slice. Do **not** interpret option-id semantics; only `id == defaultOptionId` decides the highlight.

**Dismiss-reason mapping** (new private helper): map `Dismissed.source` → a **local** string resource, never echoing the raw wire token:

```kotlin
@Composable private fun dismissReasonText(source: String): String   // when(source){ "remote"→…, "local"→…, "timeout"→…, else→<generic fallback> }
```

- `"remote"` → `R.string.modal_dismissed_remote`, `"local"` → `R.string.modal_dismissed_local`, `"timeout"` → `R.string.modal_dismissed_timeout`, **else** → `R.string.modal_dismissed_resolved` (the graceful forward-compat fallback — AC #3). Mapping to local strings (not echoing `source`) mirrors `LiteralScreenSurface.ErrorContent`'s "no server-supplied string is shown" posture.

### 2. Screen-capture hardening — `DialogProperties.securePolicy`, not the Activity flag

The overlay renders verbatim `title` / `prompt` / option `label`s that may name a sensitive command or path, so its window must block screenshot/recording/cast (AC #2). The #381 `LiteralScreenSurface.SecureScreen()` precedent flags the **Activity** window — but a Compose dialog draws in its **own** window, so an Activity-window flag would **not** cover it (the ticket's Technical Notes call this out explicitly). The deterministic fix is `DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)` on the `BasicAlertDialog`, which sets `FLAG_SECURE` on the dialog's own window.

`SecureFlagPolicy.SecureOn` is **load-bearing**: the default `Inherit` would secure the dialog only if the host Activity window already had `FLAG_SECURE` — and the app uses `FLAG_SECURE` nowhere on the thread screen ("nowhere else" per `SecureScreen` KDoc). Must be `SecureOn`, not `Inherit`. (This is a deterministic Compose property — a code-level safety net, not a stochastic rule.)

No modal-derived text reaches `SavedStateHandle` / `rememberSaveable` / DataStore: `modalState` is a hoisted parameter (not local saved state), the `BasicAlertDialog` holds no `rememberSaveable`, and the snackbar shows a **mapped local string** (not the verbatim `source`), so no server text is persisted via saved-instance state. #445 deliberately kept the projection solely in its `StateFlow` for this reason; this slice must not introduce a persisted copy.

### 3. Non-dismissable in this slice (cancel is #444)

`dismissOnBackPress = false` / `dismissOnClickOutside = false`: a permission gate must not treat a stray back-press or outside-tap as an implicit answer/cancel. In this slice the overlay is purely **state-driven** — it leaves composition only when `currentModal` transitions away from `Open` (a daemon `Dismissed`, including timeout). `onModalCancel` is declared but inert; local cancel/back wiring is #444's concern. The deferred "clear a stale `Open` on connection drop" question (#445 open question) is **not** built here — leave it to #444 + the connection signal; the daemon validates `modalId` server-side, so a stale answer is rejected.

### 4. `MainActivity.kt` — collect + forward (route host)

In the thread destination (~:347-365), mirror the `isThinking`/`isStalled` collect-and-forward:

```kotlin
val modalState by vm.currentModal.collectAsStateWithLifecycle()
// …
ThreadScreen(/* …existing args… */, modalState = modalState)
```

`onModalOption` / `onModalCancel` are **not** passed yet (they default to `{}`); #444 will wire them to the VM's answer/cancel methods. No other route-host change.

### 5. String resources (`strings.xml`)

Add (copy is **design-owed** — placeholders, reconciled with the visual spec later):

- `modal_dismissed_remote` — e.g. "Resolved on another device"
- `modal_dismissed_local` — e.g. "Resolved on this device"
- `modal_dismissed_timeout` — e.g. "Request timed out"
- `modal_dismissed_resolved` — e.g. "Request resolved" (generic forward-compat fallback)
- `modal_default_option_desc` — e.g. "Default" (semantics `stateDescription` for the highlighted option)

## State + concurrency model

- **No new state holder.** `ThreadScreen` stays stateless; it receives `modalState: ModalUiState` as a parameter and renders a pure function of it. The single source of state is `ThreadViewModel.currentModal` (#445), collected once in the route host via `collectAsStateWithLifecycle`.
- `snackbarHostState = remember { SnackbarHostState() }` is local UI plumbing (the standard M3 host), not modal-derived persistent state — correct to `remember` (not `rememberSaveable`).
- The dismiss snackbar fires from a `LaunchedEffect(modalState.modalId)` inside the `Dismissed` branch — keyed on `modalId` so it shows exactly once per resolution and never re-fires on unrelated recomposition. No `viewModelScope` / dispatcher work in this slice (all in `viewModelScope` upstream; UI collects on the composition's lifecycle scope).
- App-level by construction: a single `currentModal` shared across thread navigations (#445's design); no per-conversation filter (there is no `conversationId` to filter on).

## Error handling

No new failure modes. `currentModal` is a total `StateFlow<ModalUiState>` over a sealed type; the `when (modalState)` is exhaustive (`Hidden` / `Open` / `Dismissed`). An unknown `source` value cannot crash — `dismissReasonText` has an `else` fallback (AC #3). No network/IO/parse here (the trust boundary is upstream at #437's decode seam; #445 confirmed no new parse). The snackbar `showSnackbar` is suspend-cancellation-safe under the composition scope. No banner/dialog-for-errors; the overlay *is* the surface.

## Security posture (`security-sensitive`)

This slice owns two render-time obligations #445 deliberately deferred to it: **output-encoding** (inert text) and **screen-capture hardening**. Walked against the trust boundary — untrusted, operator/daemon-supplied verbatim strings (`title`, `prompt`, option `label`s) rendered on a phone screen that may name a sensitive command or path.

- **Output-encoding / injection sink.** Every server string renders through plain Compose `Text(String)`, which draws the value literally — no markup/HTML/active-content interpretation. The injection sink would be routing these through `MarkdownText` (which parses) — explicitly forbidden here, mirroring `LiteralScreenSurface`'s verbatim-`Text` rule. No `buildAnnotatedString` parsing, no string interpolation into a format that re-interprets, no `SelectionContainer` (text selection is a clipboard exfiltration path past `FLAG_SECURE` — `LiteralScreenSurface` avoids it for the same reason; the modal needs none).
- **Confidentiality — screen capture.** `DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)` blocks screenshot/recording/cast of the dialog's own window (§2). `SecureOn`, not `Inherit`, is the load-bearing choice. Deterministic Compose property — a real safety net.
- **Confidentiality — no persistence.** No modal-derived text written to `rememberSaveable` / `SavedStateHandle` / DataStore; the dismiss snackbar shows a mapped local string, not the verbatim `source`. No server text survives process death via saved-instance state.
- **Fail-safe-deny preserved.** The highlight is driven solely by `option.id == open.defaultOptionId` — the producer's fail-safe-deny default (pyrycode#716 marks no option "destructive"; the safety is that the prominent default denies). No option-id semantics interpreted, no auto-answer (answering is #444), no path bypasses the user's explicit choice.
- **No new trust boundary.** Operates on already-decoded in-process `ModalUiState`; introduces no parse of untrusted input. The dismiss `source` is mapped through a closed `when` with a generic fallback — an unexpected value yields a benign generic message, never an interpreted/echoed token.

**Verdict: PASS.** Output-encoding is inert by construction (plain `Text`, no markdown/selection), confidentiality is held (dialog-window `FLAG_SECURE` via `SecureOn` + no-persist + mapped-not-echoed reason), and the producer's fail-safe-deny default is faithfully surfaced without interpretation. (Self-review per the `security-sensitive` gate — see § below.)

## Testing strategy

Instrumented screen test (`./gradlew connectedAndroidTest`, device required) — new `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenModalTest.kt`, mirroring `ThreadScreenOverflowTest`'s idiom (`createComposeRule()`, `setContent { PyrycodeMobileTheme { ThreadScreen(...) } }`, `string(resId)` helper, `onNodeWithText` / `assertIsDisplayed` / `assertDoesNotExist`). No unit test (this is pure UI; the fold logic is already unit-tested in #445). Scenarios (AC #5 — bullets, not pre-written bodies):

- **render with options + default highlighted** — `modalState = Open` with a `permission`-shaped 4-option list (`allow_once`/`allow_always`/`reject_once`/`reject_always`, `defaultOptionId = "reject_once"`); assert `title`, `prompt`, and all four option `label`s are displayed; assert the option list renders in array order; assert exactly the `reject_once` option carries the default semantics (`stateDescription` = `modal_default_option_desc`) and no other option does.
- **dismissed clears + surfaces reason × 3 sources** — for `source ∈ {"remote", "local", "timeout"}`: `modalState = Dismissed(source = …)`; assert the overlay is gone (an option label / the title `assertDoesNotExist()`) and the mapped reason string (`modal_dismissed_remote` / `_local` / `_timeout`) is displayed. (Snackbar shows on `LaunchedEffect` entry; assert immediately — `createComposeRule`'s clock keeps it on screen for the assertion.)
- **forward-compat source → fallback** — `Dismissed(source = "some_future_value")`; assert `modal_dismissed_resolved` is displayed (locks the no-coercion fallback; AC #3).
- **hidden → no overlay** — `modalState = Hidden` (the default); assert no modal title / option nodes exist.
- **inert hooks** — tapping an option in the `Open` overlay does not crash and (in this slice) invokes only the inert `onModalOption(id)` no-op; optionally assert the lambda receives the tapped option's `id` to lock the contract #444 consumes.

`FLAG_SECURE` cannot be asserted via the Compose test API (no semantics node for a window flag); the `securePolicy = SecureOn` hardening is a **code-review-verified** invariant, not a runtime assertion.

## Open questions

- **Dismiss-reason affordance shape.** Spec uses a snackbar (the `ArchivedDiscussionsScreen` / #177 precedent) because it is transient and matches "the overlay is removed and the reason is surfaced." Design-owed; the later visual spec may replace it with an inline confirmation. The mapping + the three-source distinction are the load-bearing AC#5 behaviour regardless of the surface.
- **Stale `Open` across reconnect** — explicitly out of scope (deferred from #445); owned by #444 + the connection signal. Not built here.

## Acceptance criteria → design mapping

1. open modal → separate-surface overlay (M3 `BasicAlertDialog`, **not** a `LazyColumn` row) with title/prompt/options in array order + `defaultOptionId` highlighted → §1 `PermissionModalOverlay` + `ModalOptionButton`.
2. verbatim strings render as inert text (plain `Text`, no markup) **and** the surface is screen-capture-hardened (own-window `FLAG_SECURE`) + no saved-instance persistence → §2 + Security posture.
3. cleared-with-reason → overlay removed + `source` surfaced (`remote`/`local`/`timeout` + graceful forward-compat fallback) → §1 `Dismissed` branch + `dismissReasonText`.
4. `currentModal` collected in the route host, forwarded to the stateless screen as a param; app-level (no `conversation_id`) → §4 `MainActivity` collect-and-forward.
5. screen test driving render-with-default-highlight + dismissed-clears-with-reason for all three `source` values → Testing strategy.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No MUST FIX. No new untrusted→trusted boundary is crossed — `ModalUiState` arrives already-decoded (the parse boundary is #437's `data/network` seam, confirmed by #445). The relevant boundary for *this* slice is the **render sink**: operator/daemon-supplied verbatim strings (`title`, `prompt`, option `label`) drawn on screen. The sink is inert by construction — plain `Text(String)` (literal, no markup), explicitly **never** `MarkdownText`, no `buildAnnotatedString` parse, no `SelectionContainer` (a clipboard-exfiltration path past `FLAG_SECURE`). Option labels render as button text (intended — the user must see the choice) and are never interpolated into a format pattern.
- **[Error messages / logs / format strings]** SHOULD FIX (developer guardrail; code-review verifies). No modal field (`title` / `prompt` / `label` / `outcome` / `source`) may be logged (`Log.*` / `Timber`) or passed as the **format pattern** of `stringResource(id, …)` / `String.format` — a `%` in server text used as a pattern throws `IllegalFormatException` (a server-triggerable crash) and would also defeat the verbatim-render rule. The spec mandates direct `Text(open.title)` / `Text(open.prompt)`, which avoids both; this finding just hardens the rule for the implementer.
- **[Confidentiality — the snackbar renders OUTSIDE the secured window]** No MUST FIX (addressed by design). `FLAG_SECURE` via `securePolicy = SecureOn` covers only the **dialog's own window**; the dismiss snackbar draws in the **un-secured Activity window**. The spec therefore surfaces a **mapped local string** (`modal_dismissed_*`), never echoing the verbatim `source` or any server-controlled token (a forward-compat `source` yields the generic fallback, not the raw value). The mapping is thus a **confidentiality requirement**, not merely UX — no server text reaches the capturable surface.
- **[Confidentiality — screen capture]** No MUST FIX. `DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)` blocks screenshot/recording/cast of the dialog window. `SecureOn` (not the default `Inherit`) is load-bearing because the host Activity window carries no `FLAG_SECURE`. A deterministic Compose property — a real code-level net, not a stochastic rule. (Cannot be asserted via Compose test API; code-review-verified.)
- **[Confidentiality — no persistence]** No MUST FIX. No modal field reaches `rememberSaveable` / `SavedStateHandle` / DataStore; `modalState` is a hoisted param, the dialog holds no saved state, and the snackbar message is a mapped local string. No server text survives process death.
- **[Fail-safe-deny integrity]** No MUST FIX. The highlight is driven solely by `option.id == open.defaultOptionId` (a UI-highlight equality, not a secret comparison — plain `==` is correct). No option-id semantics interpreted, no auto-answer (answering is #444), no path bypasses the user's explicit choice. The prominent default is the producer's deny/safe option.
- **[Tokens / crypto / file-storage / network / IPC]** N/A — this slice handles no tokens or secrets, no RNG/crypto, no filesystem path (a path that merely appears *as text* in `title`/`prompt` is never used as a path), no network/IO, and adds no exported component / intent / deep link / `PendingIntent` / `WebView`. Each category is structurally inapplicable, not merely unaddressed.
- **[Android attack surface — tapjacking]** OUT OF SCOPE → **#444** (interaction slice). A screen-overlay attack (tapjacking) over the fail-safe-deny default is **moot here** because option taps are inert (no answer is sent). When #444 makes taps live, it should resist tapjacking on the answer (e.g. `setFilterTouchesWhenObscured` / Compose touch-obscured filtering) so an obscuring overlay cannot turn a "deny" tap into an "allow". Named here so #444 inherits it.
- **[Threat model — accessibility-service eavesdropping]** OUT OF SCOPE (accepted residual). `FLAG_SECURE` blocks screen *capture*, not the accessibility node tree — a malicious accessibility service can read the modal's verbatim text. Suppressing the a11y tree would break legitimate TalkBack users (who must hear the prompt and options), so it is **not** mitigated here. AC #2 deliberately scopes the hardening to screenshots/recording; this residual is a platform-level tradeoff, not a regression this slice introduces. Named, not silently skipped.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-23
