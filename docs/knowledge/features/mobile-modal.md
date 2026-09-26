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
outlined archive action, not yet drawn by any screen. Since #904
[`AddWorkspaceModal`](#callers) draws this shell directly as its fourth caller — desktop's
host-row Add workspace dialog on the phone, a folder list in place of a typed path, replacing
the host row's own long-press into [`WorkspacePicker`](workspace-picker.md). Since #905
[`EditWorkspaceModal`](#callers) draws this shell directly as its fifth caller — desktop's
`EditWorkspaceDialogView` on the phone, a name field plus an outlined archive action, driven by
every workspace row's own pencil in both tree sections. Since #957
[`SaveAsChannelDialog`](save-as-channel-dialog.md) draws this shell directly as its sixth caller —
a channel name field plus an optional system prompt field, replacing the dialog's earlier
`AlertDialog`-with-workspace-radios shape, driven by the thread overflow's **Save as channel…** item.
Since #958 [`CreateChannelModal`](#callers) draws this shell directly as its seventh caller — the same
name-plus-system-prompt form reused by construction (both share `ChannelFormFields`), driven by a plus on
every Channels-section workspace row instead of the thread overflow, and creating a new promoted channel
rather than promoting an existing chat. Since #667 [`EditChannelModal`](#callers) draws this shell
directly as its eighth caller — the same `ChannelFormFields` form once more, plus an outlined `Archive
channel` action in `EditChatModal`'s shape, driven by the permanent pen every Channels row now carries
(mirroring the Chats row pen #827 added) and editing that row's own name and already-**stored** prompt
in place, rather than creating or promoting anything. Existing dialogs
such as [CreateFolderDialog](create-folder-dialog.md) remain separate; consumer tickets own
their migration and operation-specific acceptance — `CreateFolderDialog` itself is now reused
unchanged as a second window stacked over `AddWorkspaceModal`, described below.

Since [#815](#the-hardened-gate-mobilegatemodal), the same file also exposes
[`MobileGateModal`](#the-hardened-gate-mobilegatemodal), a hardened decision-gate entry point sharing this
shell's private structure. Its first caller is the
[permission-modal overlay](permission-modal-overlay.md#the-overlay-open)'s `PermissionModalOverlay`; since
[#661](question-batch-modal.md) [`QuestionBatchModal`](question-batch-modal.md) is its second, adding the
optional submit/sending/error parameters described below.

Since [#678](#the-read-only-panel-mobilereadonlymodal), the same file also exposes
[`MobileReadOnlyModal`](#the-read-only-panel-mobilereadonlymodal), a plain read-only variant of the editing
shell with no submit action, sharing the same private `MobileModalShell`. Its first caller is
[`BackgroundTaskPanel`](#callers).

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

## The read-only panel: `MobileReadOnlyModal`

```kotlin
@Composable
internal fun MobileReadOnlyModal(
    title: String,
    closeLabel: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
)
```

Added in #678 for [`BackgroundTaskPanel`](#callers), the shell's third entry point — `MobileModal` calls
`MobileModalShell` with its own Cancel/OK footer, `MobileGateModal` with a decision-gate footer, and this
one with a footer of a single `ModalCancelButton(label = closeLabel, onClick = dismiss)`, all three on the
same private `MobileModalShell(gate = false, error = null)` call for the two non-gate variants. The close
glyph, that one footer button and Back all route to `onDismissRequest`, exactly as `MobileModal`'s Cancel
does — there is no submit action, so a caller with nothing to send never inherits an unused OK button.
Outside taps still do not dismiss, matching every other entry point on this shell.

## Layout and theme

The dialog disables platform default width and decor fitting. Safe-drawing and
IME padding consume insets around the full-size surface. Supply content for the
non-lazy `ColumnScope`; the shell already owns vertical scrolling.

`MobileModalShell` measures its own height with a `BoxWithConstraints` and picks one of two
layouts (#1135):

- **Pinned** (available height ≥ `MinPinnedShellHeight`, 280 dp — roughly the chrome's own
  200 dp plus room for one outlined text field): the header and centered Cancel/OK footer sit
  outside a weighted inner scroll area; short content centers vertically and overflow can
  scroll to the final item. This is the layout Figma draws and the one every caller sees in
  portrait, keyboard or not.
- **Compact** (below 280 dp — landscape with the keyboard up, on the phones this shell has
  been measured on): the header, content and footer scroll together as one column instead,
  since the pinned layout's weighted region would otherwise collapse to zero height and hide
  whatever is focused. Figma has no landscape-with-keyboard frame; this mode is a deliberate
  deviation the ticket accepted because reachability there matters more than matching a frame
  that doesn't exist.

Both modes render the same composition tree — only modifiers change — so a caller's `remember`ed
form state and the focused field survive the flip. Two structural rules keep that safe, because
the mode flips *while the operator is typing* (the IME rising is what shrinks the shell):

1. The tree shape never branches between modes; only the modifiers on it change. A branch that
   called `content()` from two different composition positions would reset the caller's
   `remember`ed values and drop focus on the flip, which would hide the keyboard and flip the
   mode back.
2. The outer `verticalScroll` is applied unconditionally (with a fixed `height` and so zero
   scroll range while pinned), rather than added only in compact mode. The same scroll node
   therefore sees its viewport shrink when the IME rises, and Compose's scrollable machinery
   keeps the focused child in view — a scroll modifier added only at the mode flip would be a
   new node with no prior size and would not reveal the field.

The [design mapping](../../specs/architecture/638-mobile-modal.md#design-source)
uses `PyrycodeMobileTheme` with these deliberate adaptations:

| Reference | Shell | Reason |
| --- | --- | --- |
| `onPrimaryFixed` background | `colorScheme.modalContainer` (`#001D34` dark / `primaryContainer`, `#CFE4FF`, light) | (#1142) A fixed role is the same colour in both themes, and `#001D34` behind light theme's `onPrimaryContainer` content colour would be unreadable. `modalContainer` is a third app colour slot, built like [`success`](success-color.md) — a `CompositionLocal` + `ColorScheme` extension (`ui/theme/ModalColors.kt`) that lets dark paint the frame's navy while light keeps the unchanged container. `contentColor` stays `onPrimaryContainer` in both themes. |
| 44 dp shell / 6 dp action corners | `MaterialTheme.shapes.extraLarge` / `small` | Reuse theme shapes; custom reference shapes are not configured. |
| Smaller action geometry | At least 48 dp action targets | Keep Close, Cancel and OK accessible to touch. |

Editing fields use two more shared roles from
[`ModalColors`](../../../app/src/main/java/de/pyryco/mobile/ui/theme/ModalColors.kt)
([#1155](../../specs/architecture/1155-editing-field-colours.md)):

| Role | Dark | Light (unchanged) |
| --- | --- | --- |
| `colorScheme.modalFieldContainer` | `onPrimary` at 41% | `onPrimaryContainer` at 12% |
| `colorScheme.modalFieldText` | `onBackground` | `onPrimaryContainer` |

`PyrycodeMobileTheme` derives these field roles from the active colour scheme,
including wallpaper colours. `EditHostModal`, `EditChatModal`, `EditWorkspaceModal`
and `ChannelFormFields` (Edit channel, Create channel and Save as channel) use them
for focused and unfocused fields. Their existing disabled-container overrides,
and workspace/channel error-container overrides, use the same well; error and
disabled text retain Material defaults. Labels keep `onPrimaryContainer`, and
cursor, indicators, validation and keyboard handling retain their existing behaviour.

The exported close vector uses `primary` over `onPrimary`. The shell retains
28 dp horizontal / 24 dp vertical padding, 20 dp section/action gaps,
`titleLarge` and an `inversePrimary` divider at 60% opacity. Light and dark
previews are defined at 412 × 892 dp.

## Focus and verification

[`ModalFieldPaletteTest`](../../../app/src/sharedTest/java/de/pyryco/mobile/ui/components/ModalFieldPaletteTest.kt)
renders both `ChannelFormFields` wells inside `MobileModal` in dark and light themes,
checks entered-text layout colour, and samples well pixels before and after focus
moves between fields. Checking theme roles alone would miss a field that never
consumes them. Like `MobileModalFillTest`, it uses native Robolectric graphics and
draws the dialog's own view into a bitmap: `captureToImage` does not redraw that
dialog window under Robolectric.

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

`landscape_ime_keeps_focused_field_following_content_and_actions_reachable` (#1135) proves the
same reachability in the compact layout: `@WithTestIme @Landscape`, a real 90° rotation, asserts
the focused field is non-empty and sits above the IME top *before* any `performScrollTo` (so the
always-applied outer scroll, not the test, is what keeps it in view), then scrolls to the final
item, OK and Cancel and asserts each above the keyboard top in turn. The unchanged portrait IME
test and the fixed-chrome overflow test keep proving the pinned layout untouched. The `@Landscape`
annotation only restores rotation afterward; each test must call `rotateToLandscape()` itself, and
a test that forgot to would silently run in portrait (the KDoc gap is a tracked verifier nit, not
yet fixed).

Three fixture lessons from that test, specific to the `pixel2Api33Atd` managed device:

- Rotating before the Compose rule launches its activity does nothing — the portrait-only
  launcher is still on top, so the display stays at rotation 0. Rotate in the test body after the
  host activity launches and before `setContent`, then wait for the relaunched host to report
  landscape and window focus.
- Use `createAndroidComposeRule<ComponentActivity>()` rather than `createComposeRule()` so the
  test can read the relaunched activity back through its scenario.
- That image shows a "Bluetooth keeps stopping" system crash dialog after rotation, which steals
  window focus and stalls a `hasWindowFocus()` wait. Broadcasting
  `android.intent.action.CLOSE_SYSTEM_DIALOGS` from the instrumentation shell clears it.

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
- **Field colours need an explicit light/dark mapping.** The reference's `onPrimary`
  at 41% works over the dark navy shell, but turns white in light. Applying the light
  fallback (`onPrimaryContainer` at 12%) to both themes made dark wells too light
  after the navy shell fix. Use the [shared field roles](#layout-and-theme): dark
  keeps the reference well and `onBackground` entered text; light keeps its previous
  tint and foreground. Check the composited well in both themes when changing either
  the field or its shell.
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

[`AddWorkspaceModal`](../../../app/src/main/java/de/pyryco/mobile/ui/components/AddWorkspaceModal.kt)
(#904) is the shell's fourth direct caller — like `DebugBundleModal` and `EditChatModal`, it draws
`MobileModal` itself. It is desktop's host-row Add workspace dialog on the phone: a list of that
host's recent folders as `selectable(role = RadioButton)` rows (a `labelLarge` SemiBold "Recent"
section label, paths in `bodyMedium` monospace, following `EditHostModal`'s field-label styling)
plus an outlined "Create new folder under pyry-workspace…" action styled like `EditChatModal`'s
Archive action, which opens the existing [`CreateFolderDialog`](create-folder-dialog.md) stacked
as a second window over the shell. OK needs a selected folder and an available host; `MobileModal`
also disables it while `loading`. It replaces the host row's long-press into the bottom-sheet
[`WorkspacePicker`](workspace-picker.md#consumers), which stays for the thread and Settings
pickers — see [ChannelListScreen § Add controls](channel-list-screen-tree-and-controls.md#add-controls-738)
and [ChannelListViewModel](channel-list-viewmodel.md#wiring) for the host-resolved state machine
this caller is bound to.

A created folder becomes the caller's `selected` value without starting anything — creating and
submitting are two separate transitions, so a folder made in the stacked dialog does not fire OK
on its own. `selected` and `recent` are daemon-authored paths: rendered only as `Text`, clamped to
`MAX_PATH_DISPLAY_CHARS` (512) without splitting a surrogate pair — the same round-trip-safe clamp
`EditChatModal`'s field uses, load-bearing here too since the raw (unclamped) path is what
`onSelect` reports back and what the caller eventually sends to `createDiscussion` — and the
caller caps the recents list itself (`MAX_ADD_WORKSPACE_RECENTS = 50` in `ChannelListViewModel`)
since this shell's content column is not lazy. A selection absent from `recent` (the just-created
folder) draws in its own "New folder" section, so the current selection is always visible even
before the next reopen re-fetches recents.

**A caller-scoped recents list is a `combine` pairing hazard, not just a fetch.** The first draft
paired an untagged `flatMapLatest`-derived recents flow with the open modal's target in one
`combine`, so for one emission after retargeting to a different host the new target's state could
still carry the previous target's daemon-authored folder list — the security review's one MUST
FIX on this ticket. The fix tags each emission with the host it was fetched for and publishes it
only when that tag matches the currently open target; see
[ChannelListViewModel § the tagged recents combine](channel-list-viewmodel.md#wiring) for the
mechanism. Worth checking for any future caller that derives a host-scoped list alongside a
host-scoped open/close flag through the same `combine`.

[`EditWorkspaceModal`](../../../app/src/main/java/de/pyryco/mobile/ui/components/EditWorkspaceModal.kt)
(#905) is the shell's fifth direct caller — like `EditChatModal`, it draws `MobileModal` itself. It is
desktop's `EditWorkspaceDialogView` on the phone: one "Workspace name (optional):" field seeded from the
caller and an outlined "Archive workspace" action, in the same field and action styling `EditChatModal`
established. `serverId` and `cwd` key the edit buffer and are never rendered, logged or reported — the
same identity-keyed-buffer pattern `EditHostModal` established. OK is enabled on an available host and a
label the daemon would accept; a blank name is allowed, since it clears the label rather than failing
validation. The field's `supportingText` shows the trimmed name's size against the daemon's own unit,
"UTF-8 bytes: n/128" (chosen over the plan's "n/128 bytes" because Android Lint's `PluralsCandidate` flags
a bare number-then-word as a pluralizable string; leading with the unit avoids that without a plurals
resource for what is really a counter). Archive workspace swaps the content for a confirmation in place —
`EditHostModal`'s unpair shape again — naming the workspace and warning that every active chat and channel
there moves to Archive; the shell's own footer carries the decision (OK confirms, every dismissal route
declines), and the typed name survives a decline because the buffer is keyed on identity, not on the
confirmation flag. [ChannelListScreen](channel-list-screen-tree-and-controls.md#workspace-row-edit-and-archive-control-905)
(#905) is its first and only caller: every workspace row's own pencil, in both sections, opens it on that
row's own host and exact `cwd` — see that section and
[ChannelListViewModel](channel-list-viewmodel.md#wiring) for the label rule and the write targeting.

The seed and the confirmation's name are both daemon-authored (the row's shown name) and both clamped once
by `clampWorkspaceText` inside the modal before they reach layout or the prompt's format argument — the
same round-trip-safe, surrogate-pair-aware clamp `workspaceDisplayName` uses. The label rule
(`workspaceLabelFor`, `ui/workspace/WorkspaceDisplayName.kt`) treats that same clamped cut as the folder's
own name too, so an untouched OK on an overlong folder seed clears the label instead of storing the cut as
a new one — but only when the clamp actually cut the folder name; an uncut name is compared exactly and
untrimmed, so a folder whose real name carries trailing whitespace is not silently treated as matching its
own trimmed display. This asymmetry was a two-round fix during verification: the first attempt trimmed
every folder-name comparison, which cleared labels for names it should not have matched.

**A Compose semantics trap in this field's test.** `TextField`'s `supportingText` composes into the
field's own merged `Text` semantics, so `assertTextEquals(typed)` fails against the byte-count line even
when the typed value is correct. Use `assertTextContains(typed)` for any field in this shell that pairs a
value with supporting text.

[`SaveAsChannelDialog`](save-as-channel-dialog.md#shape)
(`ui/conversations/components/SaveAsChannelDialog.kt`, #957) is the shell's sixth direct caller — like
`EditChatModal`, it draws `MobileModal` itself. It replaces a channel name and system prompt
`AlertDialog`-with-workspace-radios pair with this shell's fixed Cancel/OK footer: a "Channel name:"
field seeded from the conversation's own name (or "New channel"), clamped to `MAX_WORKSPACE_LABEL_CHARS`
the same way `EditChatModal`'s field is, and an optional multi-line "Channel system prompt:" field that
always opens empty. Both fields are pulled into a standalone, reusable `ChannelFormFields` composable
(`ui/components/ChannelFormFields.kt`) rather than kept private to this caller, since
[`CreateChannelModal`](#callers) (#958) reuses the same form. OK promotes the conversation in place
(`ConversationRepository.promote(id, name, workspace = null)` — no dedicated-folder choice any more,
following desktop's pyrycode-desktop#1436) and, once that is confirmed, writes a non-blank prompt
verbatim with `setSystemPrompt`; a blank prompt writes nothing. `nameEditable = false` locks the name
field once the promote leg is confirmed, so a retry after a prompt-write failure never repeats the
promote. See [Save as channel](save-as-channel-dialog.md) for the full two-write state machine, its
`compareAndSet` terminal transitions, and why `SaveAsChannelSubmit`'s `toString` redacts the prompt.
[ThreadOverflowMenu](thread-overflow-menu.md)'s discussion-only **Save as channel…** item is its only
caller.

[`CreateChannelModal`](../../../app/src/main/java/de/pyryco/mobile/ui/components/CreateChannelModal.kt)
(`ui/components/CreateChannelModal.kt`, #958) is the shell's seventh direct caller — like
`SaveAsChannelDialog`, it draws `MobileModal` itself around `ChannelFormFields`, and desktop's
`CreateChannelDialog` is its analogue. Both fields open empty (there is no existing conversation to seed
from), and `nameEditable = false` locks the name once the create leg is confirmed — the identical
retry-never-repeats-the-first-write shape `SaveAsChannelDialog` uses, with `createChannel` in the first
leg's place instead of `promote`. `serverId` and `cwd` key both buffers (`remember`, not
`rememberSaveable` — the prompt may hold a pasted secret) and are never rendered: the title is the static
string "Create channel," never the target path. [ChannelListScreen § Workspace row create-channel
control](channel-list-screen-tree-and-controls.md#workspace-row-create-channel-control-958) is its only
caller: every Channels-section workspace row's own plus opens it on that row's own host and exact `cwd`
— see that section and [ChannelListViewModel](channel-list-viewmodel.md#wiring) for the two-write state
machine and why a second host sharing the same `cwd` is never addressed.

[`EditChannelModal`](../../../app/src/main/java/de/pyryco/mobile/ui/components/EditChannelModal.kt)
(`ui/components/EditChannelModal.kt`, #667) is the shell's eighth direct caller — like `EditChatModal`, it
draws `MobileModal` itself around `ChannelFormFields`, a private `MuteNotificationsRow` (#1021, between
the prompt field and Archive) and a private outlined `Archive channel` action
copied from `EditChatModal`'s `ArchiveAction` (a verifier SHOULD FIX left for a follow-up: a shared
`internal` action taking a `@StringRes` label would keep the two from drifting apart). `MuteNotificationsRow`
is the app's second whole-row checkbox after `ThreadPermissionModal`'s `AlwaysAllowOffer` — a `toggleable`
`Row` with `Role.Checkbox`, an M3 `Checkbox(onCheckedChange = null)` in `colorScheme.tertiary` and a
label-medium SemiBold label, at the shell's 48dp touch floor — and a `muted` buffer, `remember(conversationId)
{ mutableStateOf(initialMuted) }`, the same per-conversation keying the name and prompt buffers use. It edits an
**existing** channel's own name and already-stored system prompt in place, unlike `CreateChannelModal`
and `SaveAsChannelDialog`, which only ever write a system prompt into a conversation with no stored one.
The name buffer is `remember(conversationId)`, prefilled from the caller's `initialName` — the row's own
host's snapshot name, clamped to `MAX_WORKSPACE_LABEL_CHARS` the same surrogate-safe way `EditChatModal`'s
field is. The prompt buffer is `remember(conversationId) { mutableStateOf<String?>(null) }`: the field
shows `typed ?: read.prompt.orEmpty()` and stays **disabled** — with a static reading line under it in
`ChannelFormFields`'s new `promptNote` slot — until the caller's `prompt: ChannelPromptReading` reading
arrives as `Read`, at which point it shows the stored prompt verbatim and a `Differs` status adds a
static next-session line in the same slot. Until the field is enabled, `onSubmit` reports the prompt as
`null` rather than an empty draft, so nothing the operator never saw can be written. OK needs an
available host, a non-blank trimmed name and (when the prompt is showing) a draft within
`SystemPromptLimit.MAX_BYTES`; Archive needs only the host and no write in flight, independent of either
field, with no confirmation step — an archived channel comes back through Archive's own Restore, the
same parity `EditChatModal`'s Archive established. [ChannelListScreen](channel-list-screen.md) is its
only caller: the Channels row's own permanent pen — the same pen shape #827 gave Chats rows, now
generalised behind `TreeConversationRow`'s `editDescription: @StringRes Int` parameter — opens it on
that row's own host and conversation, reads the stored prompt once the row's host has a live
repository, opens the checkbox at that host's own stored `Conversation.muted` (#1021), and OK writes
only what changed — a rename, then a mute write, then the prompt, each independently, in that order —
through the
repository resolved **at the press** — see [ChannelListScreen § Channels row edit control
(#667)](channel-list-screen-tree-and-controls.md#channels-row-edit-control-667) and
[ChannelListViewModel § Wiring](channel-list-viewmodel.md#wiring) for the two target-tagged state flows
that keep a prompt read from ever landing on a write's own `compareAndSet`, and for why this caller
resolves the repository at the press rather than binding one at construction the way
[`SystemPromptEditor`](system-prompt-editor.md) does.

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

[`BackgroundTaskPanel`](../../../app/src/main/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanel.kt)
(#678, redrawn to its Figma frames by #1041) is
[`MobileReadOnlyModal`](#the-read-only-panel-mobilereadonlymodal)'s first and so far only caller. Unlike
`PermissionModalOverlay` and `QuestionBatchModal`, which use `MobileGateModal`, it draws inside
`ThreadScreen` itself, behind screen-local `remember(state.conversationId)` visibility the Actions menu's
background-tasks row flips — see [Thread composer footer § Actions
menu](thread-composer-footer-actions-menu.md#actions-menu-884) for the row and its live-count label, and [Thread screen —
overlays § Background-tasks panel placement](thread-screen-how-it-works-overlays-and-app-bar.md#background-tasks-panel-placement-post-678)
for where it mounts. It lists the open conversation's `BackgroundTaskRoster?` (#677) read-only, with three
readings: `null` draws a dashed ring, "No background-task report yet" and "The daemon has not reported on
this conversation since the app connected."; an empty roster draws a solid ring, "No background tasks" and
"Claude has nothing running in the background for this conversation."; a listed roster splits `tasks` into a
"Running · n" group (`filterNot { it.isFinished }`) and a "Finished · n" group (`filter { it.isFinished }`),
each in claude's order and each undrawn when empty — `droppedTasks > 0` both raises a filled
`secondaryContainer` partial-list notice above the groups and switches both counts to "n shown".

Each task is a card: the raw `taskType` in monospace beside a
[`TaskStatusTag`](../../../app/src/main/java/de/pyryco/mobile/ui/conversations/thread/TaskStatusTag.kt) pill
(the Figma "Task status tag" component; Running `primaryContainer`/`onPrimaryContainer`, Completed
`colorScheme.success` on a 16% tint of itself — the [success slot](success-color.md#usage)'s second consumer
— Failed `errorContainer`/`onErrorContainer`, Stopped `secondaryContainer`/`onSecondaryContainer`, capped to
160 dp and one line so a long word cannot widen the row), then the description (monospace when `taskType ==
"local_bash"`, a shell command line), the finish summary, and, only when the task was updated mid-life, a
"Latest update" label over a `surface` code block holding the latest patch — italic "No change reported"
when the patch is empty, no label or block at all when `latestUpdate` is `null`. The tag resolves from
`finish`: unfinished reads Running; `finish == null` (the reconnect case — a task marked finished with no
terminal frame ever arriving) reads Finished in the Stopped style; the wire's three known terminal words
(`completed`/`failed`/`stopped`, exact match) read Completed/Failed/Stopped; any other word is shown as
itself in the Stopped style — except a blank word, or one that spells "running" in any case once trimmed,
which falls back to Finished instead. That fallback is a security-review fix, not a style choice: an early
draft showed an unknown terminal status raw, so a daemon-sent status of `"running"` on a *finished* task
would have painted a Running tag — a claude-authored word passing for the app's own claim, and the one real
trust-boundary risk this redraw introduced. No terminal status can read Running now.

A partial-list notice, a "Truncated by the daemon" marker on a field the daemon's own `truncatedFields`
names, and one this client cuts for display at the same `MAX_PANEL_TEXT_CHARS = 4096` bound, are unchanged
in meaning from #678 — the task's own list (`description`/`task_type`) and an update's own list
(`patch`/`summary`) still read independently and never cross — only their look changed: the cut marker is
now a dashed `tertiary` chip and the partial notice a filled row, both still their own element straight
after the field they describe, never text joined onto it. Every field, the tag's word included, still
reaches only a plain `Text` through `printableText` + the 4096-char bound: no link, click, clipboard, parse,
`key()`, test tag or log. `printableText` drops ISO control characters but keeps Unicode bidi format
characters (e.g. U+202E), so a field can still be visually reordered to spell another word — an accepted,
pre-existing limit since #678 and not widened by this redraw, since a tag's style is chosen by exact match
on the raw word rather than on what renders. Closing the panel — any of the three routes above — sends
nothing and changes no task or conversation state.

A running card's progress (#1044, the Figma Populated frame) draws directly under the description and
above the finish summary / "Latest update", gated on `!task.isFinished && task.progress != null` — the
panel gates on `isFinished` itself rather than trusting that the #1042 projection already nulls `progress`
on finish. The block is the activity line (the held frame's `description`, `bodyMedium`/`onSurfaceVariant`,
through the same `TaskField` + cut-marker treatment as every other field) then a meta line
(`bodySmall`/`outline`) joining the last tool name and three client-formatted counters with " · ", e.g.
"Bash · 4 tools · 18k tokens · 2m 41s". `subagentType` is decoded onto the held frame but never rendered.

The progress frame carries its own `truncatedFields` — a *third* independent list alongside the task's own
and an update's own, never crossing either: the task's own list naming `description` does not mark the
activity line, only the progress frame's own list naming `description` does, and naming `last_tool_name`
marks the meta line instead. An empty last-tool-name drops its segment rather than leaving a stray leading
separator. The three counters (`BackgroundTaskProgressFormat.progressCounters`) format purely from the
frame's three `Long` readings, never a daemon string: singular exactly at 1, tokens whole under 1000 then
half-up-rounded thousands with a "k" suffix (division/remainder, not `+500`, so it cannot overflow), elapsed
as `Ns` under a minute, `Nm SSs` under an hour, `Nh MMm` (seconds dropped) beyond, and any negative reading
clamps to zero since the wire's counters are not guaranteed monotonic. Sharing one `Text` for the tool name
and the counters is an accepted limit, not an oversight: a hostile tool name could imitate a counter segment
or bidi-reorder the line, but the same author supplies the integers being formatted, so this grants no new
capability — the same accepted-limit shape as the tag's raw-word display above.
