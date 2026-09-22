# #815 — Render the permission prompt in the shared mobile modal shell

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModal`, `logModalEvent`: the shared shell (#638). Its `Dialog` disables outside-tap only, draws a close glyph in the header and a fixed Cancel/OK footer.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `PermissionModalOverlay`, `ModalOptionButton`, the `when (modalState)` block in `ThreadScreen`: the current `BasicAlertDialog` prompt that this ticket re-houses. Its KDoc records the four window properties that must stay.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt` → `EditHostModal`; `app/src/main/java/de/pyryco/mobile/ui/settings/DebugBundleDownload.kt` → `DebugBundleModal`. These are the two production `MobileModal` callers. Neither may change on screen or in behaviour.
- `app/src/androidTest/.../ui/conversations/thread/ThreadScreenModalTest.kt`: render/order/default/armed/two-tap/cancel coverage that must stay green. It finds Cancel by `R.string.modal_cancel`.
- `app/src/androidTest/.../ui/components/MobileModalTest.kt` → `ModalContent`, `close_cancel_and_back_only_request_dismissal_once_each`: this test already captures `LocalView` inside the dialog and uses `Espresso.pressBack()`. The new window-property assertions use the same seam.
- `docs/knowledge/features/permission-modal-overlay.md` § Security, § Non-dismissable here: `SecureOn`, not `Inherit`, is required because the host has no `FLAG_SECURE`. The tapjacking net is `filterTouchesWhenObscured` on the dialog's **own** window, and the old docs say both are "code-review-verified". This ticket makes both runtime-asserted.
- `docs/knowledge/features/mobile-modal.md` § Callers: in #745 the fixed "Cancel / OK" footer labels were kept, not restyled per caller. The prompt uses a different footer through a separate entry point, and the existing API stays as it is.
- `docs/specs/architecture/638-mobile-modal.md` § Design source: the token mapping reused here.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

The generic mobile-modal container that #638 built. It is a full-height rounded column with 28/24 dp padding and a `titleLarge` header over a 60%-opacity `inversePrimary` divider. The content area is centred and scrolls, and the actions are centred in the footer. The #638 adaptations still apply (`primaryContainer`/`onPrimaryContainer`, `shapes.extraLarge`/`small`, 48 dp targets). For the prompt, the server title fills the header and the prompt text and wire-order options fill the content area. The footer holds only the outlined Cancel. The header close glyph is left out because Cancel must remain the only dismissal control. This is a deliberate difference from the frame.

## Context

The permission prompt predates the shell and still uses its own `BasicAlertDialog` with M3 alert defaults. This ticket moves it into the shared container without weakening the gate. The shell differs from the gate in three ways:

1. The gate needs `FLAG_SECURE` (`SecureFlagPolicy.SecureOn`), `filterTouchesWhenObscured` on the dialog window, and back dismissal disabled. The shell provides only `dismissOnClickOutside = false`.
2. The gate must have no close glyph because Cancel is the only dismissal control.
3. The gate cannot use the fixed Cancel/OK footer because the server's options are the actions.

Edit host and Log data download must render and behave exactly as before, so the public `MobileModal` signature and behaviour stay unchanged.

**File overlap (§ A2):** `origin/feature/803` also touches `ThreadScreen.kt`, but only in its import block and `ThreadStatusArea` (the `thinkingProgress` argument). This ticket edits `PermissionModalOverlay`, `ModalOptionButton` and the dialog imports, so the hunks do not overlap. #803 has already passed verification. Instead of parking the ticket, the builder uses a deterministic `git merge-tree` trial merge against `origin/feature/803` before opening the PR. A conflict would route the ticket through the normal blocker procedure.

## Design

### `MobileModal.kt`

- The body of today's `Dialog { Surface { … } }` moves into a **private** `MobileModalShell(title, onDismissRequest, gate: Boolean, modifier, error, footer: @Composable RowScope.() -> Unit, content)`.
  - `gate = false` keeps today's `DialogProperties` (`usePlatformDefaultWidth = false`, `decorFitsSystemWindows = false`, `dismissOnClickOutside = false`) and the header close glyph.
  - `gate = true` adds `dismissOnBackPress = false` and `securePolicy = SecureFlagPolicy.SecureOn` and leaves out the close glyph. Inside the dialog content it also uses a `SideEffect` to set `filterTouchesWhenObscured = true` on the decor view of the dialog's **own** window (`(LocalView.current.parent as? DialogWindowProvider)?.window`). This is the pattern moved from `PermissionModalOverlay`.
  - The `opened`/`closed`/`dismiss_requested` debug log calls stay in the shell, so both entry points log the same content-free events.
- `MobileModal(...)` keeps its exact signature and delegates with `gate = false`. Its footer is the current Cancel + OK row unchanged, including the literal labels, the loading spinner and the `submit_requested` log.
- The Cancel `OutlinedButton` styling (small shape, primary 1 dp border, 48 dp minimum, `bodyLarge` Medium) moves into a private `ModalCancelButton(label, onClick)`. Both footers use it.
- New entry point:

  ```kotlin
  /** Hardened decision gate: FLAG_SECURE, obscured-touch filter, no Back/outside/close dismissal.
   *  [onCancel] (the footer's Cancel) is the only dismissal control; [content] carries the actions. */
  @Composable
  internal fun MobileGateModal(
      title: String,
      cancelLabel: String,
      onCancel: () -> Unit,
      modifier: Modifier = Modifier,
      content: @Composable ColumnScope.() -> Unit,
  )
  ```

  `cancelLabel` is a parameter so the shared component does not depend on a thread-screen string. The prompt passes `stringResource(R.string.modal_cancel)`. `error` is always `null` for the gate, because send errors stay on the thread's snackbar.

### `ThreadScreen.kt`

- `PermissionModalOverlay` becomes a `MobileGateModal(title = open.title, cancelLabel = modal_cancel, onCancel = onCancel)`. Its content is the prompt `Text` (`bodyLarge`, plain `Text`), followed by a `Column` of `ModalOptionButton`s in `open.options` order. The `isDefault`/`isArmed` derivation and `onClick = { onOption(option.id) }` stay verbatim. The `BasicAlertDialog`, `SideEffect` and `DialogProperties` code moves into the shell. The KDoc changes to say that the shell now carries the four window properties.
- `ModalOptionButton` keeps its three-way render and `stateDescription` markers unchanged. It gets the shell's action geometry: `MaterialTheme.shapes.small` and `heightIn(min = 48.dp)`. The resting `OutlinedButton` gets the shell's primary 1 dp border so the options match the footer Cancel. The default option stays a filled `Button` (most emphasis), and the armed option stays `FilledTonalButton`.
- Unused imports are removed (`BasicAlertDialog`, `AlertDialogDefaults`, `SecureFlagPolicy`, `DialogWindowProvider`, `SideEffect`, and `LocalView` when nothing else uses them).

No new strings: `modal_cancel` and the two `stateDescription` markers already exist. The estimate listed `strings.xml`, but that file does not need to change.

## State + concurrency model

No change. The overlay remains a pure function of `(ModalUiState.Open, armedOptionId)`. It has no `remember` state or coroutine, and it does not use `rememberSaveable`. The `SideEffect` sets a view flag on each recomposition, as it does today.

## Error handling

No new failure modes. `DialogWindowProvider` lookup failure (`as?` null) leaves the filter unset. This matches today's code. The new instrumented test turns that silent null into a red test.

## Testing strategy

Instrumented tests (managed API 33 device) that the builder runs as a focused set:

- `MobileModalTest` (shell):
  - **gate window hardening:** `MobileGateModal` captures `LocalView` in its content. Assert that the dialog window's `attributes.flags` contains `FLAG_SECURE` and that `decorView.filterTouchesWhenObscured` is set. `Espresso.pressBack()` leaves the cancel count at 0 and the content displayed. No `Close` content description exists, and tapping Cancel once cancels once.
  - **plain shell unchanged:** the existing `show()` host has no `FLAG_SECURE` on its window, and `filterTouchesWhenObscured` is false. This protects the Edit host and Log data download from the hardening leaking across.
- `ThreadScreenModalTest`: all existing tests stay green unchanged. Add **back press neither answers nor cancels the prompt** (no option or cancel callback, prompt still displayed, no Close glyph).
- `EditHostModalTest`: this suite is not edited. Running it proves AC3.
- Outside-tap is not testable because the full-size surface leaves no tappable outside region. It is guaranteed by `dismissOnClickOutside = false`, which both variants have, and the code review verifies it.
- There are no unit tests because the change is pure UI. This is not an operator-facing flow change: the live answer path is unchanged and #679 owns the live verification. No rung-3 scenario is needed.

## Documentation handoff

Pending for the documentation stage:
- `docs/knowledge/features/permission-modal-overlay.md`: § The overlay, § Security, § Testing and § Visual spec status. The container is now `MobileGateModal`. `FLAG_SECURE` and the obscured-touch filter are runtime-asserted in `MobileModalTest` and are no longer only code-review-verified. The visual spec is now the 533-2369 container.
- `docs/knowledge/features/mobile-modal.md`: § Caller contract and § Callers. Document the `MobileGateModal` entry point and its hardening. The permission prompt is its caller.

## Open questions

- Does `DialogProperties` accept `securePolicy` together with `decorFitsSystemWindows` in the pinned Compose version? It should, because the five-argument constructor exists. The compile resolves this.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The daemon-authored `title`, `prompt` and option `label`s reach Compose only as plain `Text` strings: the shell's header `Text` for the title, and `Text` inside `ModalOptionButton` for the options. They never go through `MarkdownText`, `SelectionContainer`, a URL or a log line. The shell also uses the title as its `paneTitle` semantics. The a11y tree already exposed that text in the old dialog, as the accepted residual below records. The length bound is still the decode-time behaviour, and the scroll area absorbs a long prompt. A long title wraps in the header. This matches the old dialog, which had no clamp either.
- [Tokens] Not applicable. The change handles no tokens or secrets.
- [File / storage] Not applicable. There is no I/O. Modal text still reaches no `rememberSaveable` or other persisted state, because the gate holds no state.
- [Inter-process / Android surface] The main risk area of this ticket.
  - **Screen capture:** `SecureOn` stays on the dialog's own window. `Inherit` is not used, because the host Activity is not secure.
  - **Tapjacking:** `filterTouchesWhenObscured` stays on the dialog's own decor view.
  - **Gestures as answers:** back dismissal and outside-tap dismissal stay disabled, and the close glyph is left out, so the explicit Cancel is the only dismissal.
  - **Regression risk:** one flag could drop without notice. Previously that could be caught only by code review. It is now a runtime assertion in `MobileModalTest`, which is a deterministic net.
  - **Cross-caller leakage:** the hardening could leak into Edit host, or its absence could reach the gate. Hardening is controlled by one private `gate` flag that only `MobileGateModal` sets, and a test asserts that the plain shell stays un-hardened.
- [Crypto] Not applicable.
- [Network & I/O] Not applicable. The send path (`onModalOption`/`onModalCancel` → VM) is unchanged.
- [Logs] No findings. The shell logs only static event names (`opened`/`closed`/`dismiss_requested`) in debug builds, with no title, prompt or option id.
- [Concurrency] No findings. There are no new coroutines. The `SideEffect` is synchronous and composition-bound.
- [Threat model] Fail-safe deny is preserved. The default option stays the filled, visually dominant action. Arming and deciding stay in the VM, and every tap forwards its id verbatim. OUT OF SCOPE: an accessibility-service reading the verbatim text is a platform tradeoff and remains as before (see permission-modal-overlay § Security). Live end-to-end verification belongs to #679.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22
