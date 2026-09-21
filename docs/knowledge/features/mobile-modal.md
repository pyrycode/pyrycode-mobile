# Shared mobile modal

[`MobileModal`](../../../app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt)
is the reusable full-height editing shell in `de.pyryco.mobile.ui.components`.
It supplies presentation and callbacks; callers own visibility, form values,
validation, submission and operation cancellation. Its first production consumer is
[`EditHostModal`](#callers) (#743), first driven onto a screen by
[`ChannelListScreen`](channel-list-screen.md#host-row-edit-control-744)'s host-row edit
control (#744). Existing dialogs such as
[CreateFolderDialog](create-folder-dialog.md) remain separate; consumer tickets own
their migration and operation-specific acceptance.

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

[`EditHostModal`](../../../app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt)
(#743) draws the shell's content with two inert identity rows, a name field
pre-filled from the caller, and an outlined unpair action. It is stateless and
caller-driven like the shell itself — no storage, connection or navigation — which
is what lets more than one screen compose the same component instead of a second
removal flow. [`ChannelListScreen`](channel-list-screen.md#host-row-edit-control-744)
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
removal itself. Settings' host entry (#713) is planned as a second caller of the same
component; not yet in this codebase as of #745. Patterns worth reusing for the next
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
