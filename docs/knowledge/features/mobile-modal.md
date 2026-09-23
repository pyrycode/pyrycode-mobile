# Shared mobile modal

[`MobileModal`](../../../app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt)
is the reusable full-height editing shell in `de.pyryco.mobile.ui.components`.
It supplies presentation and callbacks; callers own visibility, form values,
validation, submission and operation cancellation. Its first production consumer is
[`EditHostModal`](#callers) (#743), first driven onto a screen by
[`ChannelListScreen`](channel-list-screen-tree-and-controls.md#host-row-edit-control-744)'s host-row edit
control (#744), and since #751 also driven by [Settings](settings-screen.md)'s owner
row — both through the shared [`HostEditorModal`](host-editor.md) binding. Since #683
the Storage section's Log data download draws `DebugBundleModal` (#683) directly on
this shell, its second direct caller. Since #826 [`EditChatModal`](#callers) draws this shell
directly as its third caller — desktop's `EditChatDialogView` on the phone, a name field plus an
outlined archive action, not yet drawn by any screen. Existing dialogs such as
[CreateFolderDialog](create-folder-dialog.md) remain separate; consumer tickets own
their migration and operation-specific acceptance.

Since [#815](#the-hardened-gate-mobilegatemodal), the same file also exposes
[`MobileGateModal`](#the-hardened-gate-mobilegatemodal), a hardened decision-gate entry point sharing this
shell's private structure. Its first caller is the
[permission-modal overlay](permission-modal-overlay.md#the-overlay-open)'s `PermissionModalOverlay`; since
[#661](question-batch-modal.md) [`QuestionBatchModal`](question-batch-modal.md) is its second, adding the
optional submit/sending/error parameters described below.

## Caller contract

```kotlin
@Composable
internal fun MobileModal(
    title: String,
    onDismissRequest: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    submissionEnabled: Boolean = true,
    loading: Boolean = false,
    error: String? = null,
    content: @Composable ColumnScope.() -> Unit,
)
```

Close, Cancel and Back delivered to the dialog each invoke `onDismissRequest`
once without submitting. Android Back can first hide the IME. Outside taps do not
dismiss. The caller removes the modal from composition to close it.

OK invokes `onSubmit` only when `submissionEnabled && !loading`; it never closes
the modal automatically. Loading preserves the OK label beside a progress
indicator and leaves all dismissal routes available. Set loading in the caller
while work is in progress; the shell does not start or cancel operations.

The content stays composed across loading and error changes. A non-null `error`
appears after the content in the scroll area, with error color, error semantics
and a polite live region. Showing an error does not reset entered values or
replace the form.

## The hardened gate: `MobileGateModal`

```kotlin
@Composable
internal fun MobileGateModal(
    title: String,
    cancelLabel: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    submitLabel: String? = null,
    onSubmit: () -> Unit = {},
    submissionEnabled: Boolean = true,
    sending: Boolean = false,
    error: String? = null,
    content: @Composable ColumnScope.() -> Unit,
)
```

Added in #815 for the [permission-modal overlay](permission-modal-overlay.md#the-overlay-open), the shell's
first caller whose actions are not a fixed Cancel/OK pair but a server-supplied option list. `MobileModal`
and `MobileGateModal` both delegate to one private `MobileModalShell(title, onDismissRequest, gate: Boolean,
modifier, error, footer: @Composable RowScope.(dismiss: () -> Unit) -> Unit, content)` — `gate` is the only
switch between them, so the editing shell's behaviour cannot drift by editing the gate path and vice versa.
`MobileModal` calls it with `gate = false` and its own Cancel + OK footer; `MobileGateModal` calls it with
`gate = true` and a footer built from its own parameters — `content` supplies any non-footer actions.

Since [#661](question-batch-modal.md), five parameters are defaulted inert (`submitLabel = null`,
`onSubmit = {}`, `submissionEnabled = true`, `sending = false`, `error = null`) so the permission prompt —
whose actions live entirely in `content`, not the footer — is unchanged. A caller whose gate content is a
form instead passes `submitLabel` and the footer adds a filled `ModalSubmitButton` (the same styling
`MobileModal`'s OK reuses, with a progress indicator while `sending`) beside `ModalCancelButton`. While
`sending`, **both** footer buttons are disabled — on a gate, Cancel is itself a decision the caller sends,
not a free dismissal, so it must not double-send any more than submit can. `error`, unlike the plain
shell's, is meant for a **send failure** rather than a validation message — the gate has no other error
slot, since it surfaces send failures nowhere else. [`QuestionBatchModal`](question-batch-modal.md) is the
first and so far only caller of this five-parameter extension.

`gate = true` changes four things over the plain shell, all on the dialog's own window:

- `DialogProperties(dismissOnBackPress = false, securePolicy = SecureFlagPolicy.SecureOn)` — the plain shell
  already sets `dismissOnClickOutside = false`; the gate adds no-back-dismissal and `FLAG_SECURE` (`SecureOn`,
  not the default `Inherit`, because the host Activity carries no `FLAG_SECURE` of its own).
- A `SideEffect` sets `filterTouchesWhenObscured = true` on the dialog's own decor view, reached through
  `(LocalView.current.parent as? DialogWindowProvider)?.window` — the tapjacking net moved here from the
  permission overlay's own `BasicAlertDialog`.
- No close glyph in the header, so the footer's Cancel is the only dismissal control.

Both footers' Cancel button share one private `ModalCancelButton(label, onClick)` — the small-shape,
primary-1dp-border, 48dp-minimum `OutlinedButton` styling that predates #815 — so `cancelLabel` is the only
thing a caller supplies; `MobileModal` passes the literal `"Cancel"`, and `MobileGateModal` takes it as a
parameter (the permission prompt passes `stringResource(R.string.modal_cancel)`) so this shared component
does not depend on a caller-owned string resource.

[`MobileModalTest`](../../../app/src/androidTest/java/de/pyryco/mobile/ui/components/MobileModalTest.kt)'s
`gate_window_is_secure_filters_obscured_touches_and_only_cancel_dismisses` and
`plain_shell_window_is_not_hardened` assert the four properties in both directions at runtime — present on
the gate, absent on the plain shell — replacing what used to be a code-review-only guarantee. See
[Permission-modal overlay § Security](permission-modal-overlay.md#security--the-render-time-obligations-deferred-to-this-surface)
for the gate's own security rationale.

## Layout and theme

The dialog disables platform default width and decor fitting. Safe-drawing and
IME padding consume insets around the full-size surface. The header and centered
Cancel/OK footer sit outside a weighted scroll area; short content centers
vertically and overflow can scroll to the final item. Supply content for the
non-lazy `ColumnScope`; the shell already owns vertical scrolling.

The [design mapping](../../specs/architecture/638-mobile-modal.md#design-source)
uses `PyrycodeMobileTheme` with these deliberate adaptations:

| Reference | Shell | Reason |
| --- | --- | --- |
| `onPrimaryFixed` background | `primaryContainer` / `onPrimaryContainer` | The existing theme configures the adaptive container pair, not the fixed slot. |
| 44 dp shell / 6 dp action corners | `MaterialTheme.shapes.extraLarge` / `small` | Reuse theme shapes; custom reference shapes are not configured. |
| Smaller action geometry | At least 48 dp action targets | Keep Close, Cancel and OK accessible to touch. |

The exported close vector uses `primary` over `onPrimary`. The shell retains
28 dp horizontal / 24 dp vertical padding, 20 dp section/action gaps,
`titleLarge` and an `inversePrimary` divider at 60% opacity. Light and dark
previews are defined at 412 × 892 dp.

## Focus and verification

The dialog provides its own focus window and restores the launching control's
focus on dismissal. The shell makes no focus requests on recomposition. If a
caller needs initial field focus, place its focus effect inside `content`, in
the same dialog subcomposition as the field; see
[CreateFolderDialog's focus contract](create-folder-dialog.md#internal-state).

[`MobileModalTest`](../../../app/src/androidTest/java/de/pyryco/mobile/ui/components/MobileModalTest.kt)
uses local editable hosts to cover callback counts, disabled/loading states,
retained values and focus, live-region semantics, 48 dp button roles/targets,
scrolling at 320 × 640 dp, actual IME insets and Tab/Enter navigation with launcher
focus restoration. The IME case checks positive keyboard visibility and a
nonzero inset alongside final-item reachability and footer position;
passing the no-keyboard overflow case cannot establish keyboard avoidance.

Keep the keyboard-mode and IME lifecycle setup described in
[Compose evidence](development-verification.md#compose-evidence) when extending
these fixtures. The test-only IME exercises platform insets independently of a
consumer's operation. The [plan revisions](../../specs/architecture/638-mobile-modal.md#revisions)
record the activity-recreation failure and the required setup order.

## Callers

**`DebugBundleModal`** (`ui/settings/DebugBundleDownload.kt`, #683) is the shell's second direct
caller — reusing `MobileModal` itself rather than going through `EditHostModal`/`HostEditorModal`.
It draws the Settings Storage section's Log data download: present exactly while its state is
non-null (the same presence rule `HostEditorModal` uses), `loading` mapped from
`receiving || saving`, and `error` from one of nine static failure sentences. Worth reusing for the
next caller with prose longer than a bare value: an early draft applied `maxLines = 1` to every line
of its content, including a two-clause sentence the acceptance criteria required in full — the clamp
that actually bounds an externally authored value (a scanned host name, a picked document's
`DISPLAY_NAME`) is applied where that value enters state, not at render, and `maxLines` never
substitutes for it since Compose measures the whole string regardless of what paints. `maxLines = 1`
stays correct for a bare value in a fixed-height row — `EditHostModal`'s `IdentityRow`, this shell's
own design source — but a caller composing a full sentence into this shell's content column should
let it wrap; the column already scrolls, so wrapping costs height only. A `hasText` Compose assertion
passes on text clipped this way, since it matches semantics rather than the painted layout — assert
`TextLayoutResult.hasVisualOverflow` (via `SemanticsActions.GetTextLayoutResult`) instead wherever a
caller's copy might outrun its column. See [SettingsViewModel — how it works § Log data
download](settings-viewmodel-how-it-works.md#log-data-download-683) for the full state machine.

[`EditHostModal`](../../../app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt)
(#743) draws the shell's content with two inert identity rows, a name field
pre-filled from the caller, and an outlined unpair action. It is stateless and
caller-driven like the shell itself — no storage, connection or navigation — which
is what lets more than one screen compose the same component instead of a second
removal flow. [`ChannelListScreen`](channel-list-screen-tree-and-controls.md#host-row-edit-control-744)
(#744) is its first driving caller: the tree's host row opens it on that row's own
host, [`ChannelListViewModel`](channel-list-viewmodel.md) reads the identity and relay
address with `PairedServerCollectionStore.loadById` at open time and saves the entered
name with `setDisplayName`, mapping a blank name to `null`. `Unpair host` was wired to
an empty lambda there until #745, which gives it a `confirmingUnpair` flag plus
`onUnpairConfirmed` / `onUnpairDeclined` callbacks: while confirming, the shell's four
content children are replaced **in place** by a prompt naming the host (never its
identity or relay address), and the shell's own `title`, `onSubmit` and
`onDismissRequest` are swapped so its existing Cancel/OK footer carries the decision
instead of a second `Dialog` stacking over the first — `MobileModal` is itself one, so
a stacked confirmation would give the phone two back targets for one decision. The
prompt names the host from this component's own already-clamped `boundedName`, the
same fallback the host row uses, so a caller cannot bypass the clamp by formatting an
unbounded name into the confirmation. Declining returns to the editor rather than
closing it — `onDismissRequest` is unreachable while confirming, so no route out of a
destructive step (Cancel, the close glyph, system Back) is ambiguous about whether it
removed anything. See [ChannelListViewModel](channel-list-viewmodel.md#wiring) for the
removal itself.

**Settings landed as the second caller in #751** — the same component, the same
`EditHostModal`, reached through a shared binding rather than a second inline call.
The Edit host state machine (open/save/request-unpair/decline/confirm/dismiss) that
used to live only in `ChannelListViewModel` moved to `HostEditorController` in
`ui/host/HostEditor.kt`, and a new `HostEditorModal` composable in that same file is
now the *only* place either screen calls `EditHostModal` — `ChannelListScreen` swapped
its own inline call for it, and `SettingsScreen` never had one. That binding owns the
presence rule (drawn exactly while a `HostEditorState?` is non-null), the
`loading = saving` mapping, and the `failed`/`unpairFailed` → generic-string resolution
this document described as the channel list's own responsibility until #751 — see
[Host editor](host-editor.md) for the full contract and why a plain controller, not a
second `ViewModel`, is the seam. Settings' own caller is `SettingsViewModel`'s
`openOwnerHostEditor()`: it opens the modal on the destination's own captured host —
never a row's own id, never selection — so the row that opens it is "the owner's, and
only the owner's" as the ticket required. Patterns worth reusing for the next
caller that pre-fills an editable field inside this shell:

- **Key a pre-filled edit buffer on the identity of the thing being edited, not on
  its current value.** `EditHostModal` keys its `remember`ed `TextFieldValue` on the
  raw `serverIdentity`, never the display name and never a value clamped for layout.
  Keying on the display value would discard what the operator typed the moment the
  caller's stored copy changed underneath it (an error or loading flip must not lose
  typed input); keying on a clamped value risks two different edited entities
  collapsing onto one buffer if their unclamped identities happen to share a long
  prefix.
- **A dark-only Figma frame can hide a scheme-inverting fill token.** `on-primary` at
  low alpha reads as a recessed well only because the reference frame never renders
  in light, where that token turns white and the fill would vanish — composite the
  candidate colour against the real light and dark `Color.kt` values before
  committing to an alpha, rather than eyeballing a single dark preview.
- **Proving a row is read-only means asserting the absence of field semantics**
  (`EditableText`, `SetText`, `Focused`), not asserting that its text is displayed —
  the latter passes identically against a real editable field seeded with the same
  value.
- Attacker-influenceable display text (here, a server identity and relay address
  from a scanned QR payload) is clamped through the same
  [`MAX_WORKSPACE_LABEL_CHARS`](../../../app/src/main/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayName.kt)
  bound `boundedRowText` uses on the host row this modal opens from, applied before
  layout and before any merged-semantics description is built — including the
  pre-filled seed value, not only the two fields the acceptance criteria named.
- **A confirmation step inside this shell is a content swap, not a second `Dialog`
  (#745).** `MobileModal` is itself a `Dialog`; stacking a second one over it gives the
  phone two back targets and two dismiss-outside behaviours for what is really one
  decision. The caller instead branches its own content and passes a different
  `title` / `onSubmit` / `onDismissRequest` triple for the confirming state, so the
  shell's single footer and single dismissal funnel keep deciding for both steps. The
  cost: the shell's footer labels are fixed ("Cancel" / "OK"), so a destructive
  confirmation is confirmed by a button reading "OK" — restyling per caller would
  touch a component with other callers, so the prompt copy has to carry that weight
  instead of the button.

[`EditChatModal`](../../../app/src/main/java/de/pyryco/mobile/ui/components/EditChatModal.kt)
(#826) is the shell's third direct caller — like `DebugBundleModal`, it draws `MobileModal`
itself rather than going through `EditHostModal`/`HostEditorModal`. It is desktop's
`EditChatDialogView` on the phone: a "Channel name:" field pre-filled from the caller, clamped to
`MAX_WORKSPACE_LABEL_CHARS`, and an outlined "Archive chat" action, following `EditHostModal`'s
identity-keyed buffer and content-free debug-log patterns. Its two actions read different halves
of one guard, matching desktop: OK needs a non-blank trimmed name, an available host and no write
in flight; Archive needs only the host and no write in flight, independent of the field's content,
and takes no confirmation step, since an archived chat comes back through Archive's Restore.
[ChannelListScreen](channel-list-screen.md) is its first caller (#827): each Chats row's pencil
opens it on that row's own host and conversation, and OK renames through that host's
`ConversationRepository.rename`, resolved at the press — see
[ChannelListScreen § tree and controls](channel-list-screen-tree-and-controls.md#chat-row-edit-control-827)
and [ChannelListViewModel](channel-list-viewmodel.md#wiring). Archive chat was wired in #828, on the
same guard's other half: `live.archive(conversationId)` on the same host-resolved-at-the-press
repository, no confirmation step (desktop parity — Archive's Restore undoes it), and no read of the
name field either way, success or failure.

A clamp on attacker-influenceable text must not split a UTF-16 surrogate pair when the clamped
value can round-trip back into a write unedited. `EditChatModal` seeds its field with
`initialName.take(MAX_WORKSPACE_LABEL_CHARS)`, then drops a trailing lone high surrogate — a plain
`take(N)` can land mid-pair, and OK sends the field back exactly as typed, so a split pair would
reach `rename_conversation` as a malformed tail. `EditHostModal`'s `boundedText` and
`workspaceDisplayName` (`ui/workspace/WorkspaceDisplayName.kt`) now drop the same trailing high
surrogate (#851), closing the round-trip gap for daemon-written host names and workspace labels.
The `HostEditor.submitName` save clamp (`name.trim().take(MAX_WORKSPACE_LABEL_CHARS)`, see
[host editor](host-editor.md)) still applies a plain `take` and can split a pair in an
operator-*typed* name at the 128-char boundary; #851's security review flagged this, alongside
`HostIdentityRow`'s `boundedRowText`, `ArchivedDiscussionsScreen` and `DebugBundleDownload`, as
out of that ticket's scope and left for a follow-up.

**`PermissionModalOverlay`** (`ui/conversations/thread/ThreadPermissionModal.kt`, #815) is the first of
[`MobileGateModal`](#the-hardened-gate-mobilegatemodal)'s two callers, and the only one using it rather than
`MobileModal` before #661. It draws the [permission-modal overlay](permission-modal-overlay.md): the server
`title` fills the gate's header, the prompt and the wire-order option list fill `content`, and the footer's
only action is Cancel — the server's own options are the actions, so this caller cannot use the fixed
Cancel/OK footer `MobileModal`'s other callers share. Landing it before the three sibling tickets it was
split from (see the plan's Context) was deliberate, so those write their content into the final gate
container instead of one about to be replaced.

**`QuestionBatchModal`** (`ui/conversations/thread/QuestionBatchModal.kt`, #661) is
[`MobileGateModal`](#the-hardened-gate-mobilegatemodal)'s second caller, and the first to use its
submit/sending/error extension: `submitLabel` = "Continue", `submissionEnabled` = every question answered,
`sending` = a send in flight or already succeeded (locked until the daemon's dismissal, not just until the
send settles), and `error` a fixed string while the last send failed. Unlike `PermissionModalOverlay`, which
draws its own gate call inline in `ThreadScreen.kt`, this caller is drawn directly from `MainActivity`
beside `ThreadScreen` rather than inside it — `MobileGateModal` opens its own `Dialog` window, so its place
in the composition tree does not affect what it draws over. See
[Question batch modal](question-batch-modal.md) for the full caller contract.
