# Shared mobile modal

[`MobileModal`](../../../app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt)
is the reusable full-height editing shell in `de.pyryco.mobile.ui.components`.
It supplies presentation and callbacks; callers own visibility, form values,
validation, submission and operation cancellation. It currently has no production
consumers. Existing dialogs such as [CreateFolderDialog](create-folder-dialog.md)
remain separate; consumer tickets own their migration and operation-specific
acceptance.

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
