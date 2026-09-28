# Shared mobile modal

[`MobileModal`](../../../app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt)
is the shared full-height editing shell. `MobileGateModal` applies decision-gate
window security; `MobileReadOnlyModal` omits submit; `MobileDismissModal` provides a single filled dismissal action for Settings. All four share the same
header, content area and footer layout. Callers own visibility, form values,
validation, submission and operation cancellation.

- [Caller forms and panels](mobile-modal-callers.md#callers)
- [Design and test plan for the Figma alignment](../../specs/architecture/1232-shared-mobile-modal-figma.md)

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

Both footers' Cancel button share one private `ModalCancelButton(label, onClick)` — the shared
outlined action styling — so `cancelLabel` is the only
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

Added in #678 for [`BackgroundTaskPanel`](mobile-modal-callers.md#callers), the shell's third entry point — `MobileModal` calls
`MobileModalShell` with its own Cancel/OK footer, `MobileGateModal` with a decision-gate footer, and this
one with a footer of a single `ModalCancelButton(label = closeLabel, onClick = dismiss)`, all three on the
same private `MobileModalShell(gate = false, error = null)` call for the two non-gate variants. The close
glyph, that one footer button and Back all route to `onDismissRequest`, exactly as `MobileModal`'s Cancel
does — there is no submit action, so a caller with nothing to send never inherits an unused OK button.
Outside taps still do not dismiss, matching every other entry point on this shell.

## The single-action dismissal: `MobileDismissModal`

[Settings](settings-screen.md) uses this fourth entry point. It keeps the shared header, divider, safe-area padding, Back handling and scrolling. Its content starts at the top, its lone filled Done action sits at the footer’s right edge, and that action uses the same dismissal callback as Close and Back. The other entry points retain their centered content and footer defaults.

## Layout and theme

The dialog disables platform default width and decor fitting. Safe-drawing and
IME padding consume insets around the full-size surface. Supply content for the
non-lazy `ColumnScope`; the shell already owns vertical scrolling.

`MobileModalShell` measures its own height with a `BoxWithConstraints` and picks one of two
layouts (#1135):

- **Pinned** (available height ≥ `MinPinnedShellHeight`, 280 dp — roughly the chrome's own
  200 dp plus room for one outlined text field): the header and centered Cancel/OK footer sit
  outside a weighted inner scroll area; short content centers unless a caller adds a weighted
  spacer, and overflow can scroll to the final item. This is the layout Figma draws and the one every caller sees in
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
| `onPrimaryFixed` background | `colorScheme.modalContainer` (`#001D34` dark / `primaryContainer`, `#CFE4FF`, light) | (#1142) Static dark now maps Material's `onPrimaryFixed` to the same navy, but the shell keeps its scoped `modalContainer` slot so light retains a readable fill behind `onPrimaryContainer` content. `ModalColors.kt` provides that slot as a `CompositionLocal` + `ColorScheme` extension, like [`success`](success-color.md). `contentColor` stays `onPrimaryContainer` in both themes. |
| 44 dp shell / 6 dp action corners | Local `RoundedCornerShape(44.dp)` / `RoundedCornerShape(6.dp)` | Match the reference in both themes without changing global theme shapes. |
| 28 dp close / 40 dp actions | Centered, invisible 48 dp touch regions | Match the visible reference while keeping Close, Cancel and OK reachable. |

Outlined actions explicitly set
`ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)`:
a primary border alone leaves the default text grey (#1156). This covers shared
Cancel/Close, Unpair, Archive chat/channel/workspace, Add workspace's new-folder
text and icon, and unarmed non-default permission choices. Let content inherit
the button colour so Material's disabled content colour still applies; keep the
existing borders, shapes and 48 dp targets. Permission default and armed choices
retain their filled treatments.

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

The [412 × 892 Figma reference](../../specs/architecture/1232-shared-mobile-modal-figma.md#design-source)
(`533:2369` and component `489:1942`, inspected 2026-09-28)
uses 28 dp horizontal padding, a 24 dp top inset, 44 dp corners and a flexible
content area. The `titleLarge` header holds the exported 28 dp close vector
(`489:1898`, `primary` over `onPrimary`) in a 28 dp layout slot. Its centered
48 dp hit region fits within the title and divider gaps; it does not enlarge
the row. A 12 dp gap leads to the 1 dp `inversePrimary` divider at 60% opacity.
The footer is centered with a 20 dp action gap. The shared `bodyLarge` medium
actions have 40 dp visible surfaces and 6 dp corners: the primary uses 20 × 8 dp
internal padding and `primary`/`onPrimary`; the outlined secondary uses
19 × 7 dp plus a 1 dp `primary` border. `minimumInteractiveComponentSize`
reserves 48 dp invisibly around each action. The shell's 20 dp bottom padding
and footer's 4 dp top padding place the visible action bottom 24 dp above the
safe-area edge. Keep that spacing on the parent: an action-local visual offset
made compact-landscape scrolling stall when `performScrollTo` targeted the
footer. The controlled hover fills follow Figma `489:1876`; suppress the
extra Material hover ripple while retaining native press feedback. Figma has
no disabled, loading or pressed reference; the existing loading indicator,
native disabled content colour and press feedback remain.
Light and dark previews remain at 412 × 892 dp.

The shared [type ramp](shared-typography.md) supplies the modal text metrics;
Edit host keeps each identity label within one weighted share of its row and
reserves two shares for the value, with a 10 dp gap. At 320 dp and 1.5× Android
text, both labels wrap within their bounds without overlapping the values.
Long identity and relay display text remains clamped before layout and
ellipsized to one line in the value slot. The shell's scrolling keeps the name
field and Unpair, Cancel and OK actions reachable.

## Focus and verification

[`MobileModalFillTest`](../../../app/src/sharedTest/java/de/pyryco/mobile/ui/components/MobileModalFillTest.kt)
checks Cancel's `TextLayoutResult.layoutInput.style.color` against primary in both
themes, then toggles gate `sending` and checks native disabled content colour
and the 40 dp visible height. It also samples the header/divider and action
pixels, including Hover. Enabled-state or border assertions alone miss the grey
text regression; inspect the composed text's colour.

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

The [question batch modal's device test](question-batch-modal.md#testing) must positively identify
the dialog's own focused window before it opens the IME. On the managed API 33 image, it sends
`CLOSE_SYSTEM_DIALOGS` while waiting for focus, then rechecks and retains that view for keyboard
inset assertions; unresolved focus loss reports the process-window focus states. The #1235 full UI gate
ran 78 tests with no failures or skips, including the question modal's 320 × 640 dp keyboard case.
The external dialog did not recur during that run, so recovery itself remains unobserved there.

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

[`MobileModalCaptureTest`](../../../app/src/androidTest/java/de/pyryco/mobile/ui/components/MobileModalCaptureTest.kt)
checks header, close and footer order at 412 × 892 on the API 33 ATD device;
its black framebuffer can pass geometry checks, so strict pixel and navy-fill
proof runs on the full API 35 image with `requireRealSystemBars=true`. The
[labelled Figma/emulator comparison](../../../app/src/androidTest/assets/modal-1232/comparison-412x892.png)
includes an overlay; the [source captures](../../../app/src/androidTest/assets/modal-1232/)
show the real 24 dp status and navigation bars omitted by the Figma render.
