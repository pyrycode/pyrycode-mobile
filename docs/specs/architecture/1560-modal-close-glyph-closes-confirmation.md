# #1560 — the X closes Edit host and Edit workspace from their confirmation step

## Files read

- `ui/components/MobileModal.kt` — `MobileModal` and `MobileModalShell`: one `dismiss` lambda feeds the close glyph's `IconButton`, the `Dialog`'s Back and the footer Cancel.
- `ui/components/EditHostModal.kt` — `EditHostModal` routes `onDismissRequest` to `onUnpairDeclined` while `confirmingUnpair`; its KDoc records that choice.
- `ui/components/EditWorkspaceModal.kt` — `EditWorkspaceModal` does the same with `onArchiveDeclined` while `confirmingArchive`.
- `ui/host/HostEditor.kt` — `HostEditorController.dismiss` publishes `null`, unguarded, so a dismissal from the confirmation (even mid-removal) closes the editor and the next `open` starts with `confirmingUnpair = false`.
- `ui/conversations/list/ChannelListViewModel.kt` — `dismissWorkspaceEditor` sets `workspaceEditor` to `null`; `openWorkspaceEditor` builds a fresh `WorkspaceEditorState` with `confirmingArchive = false`.

## Design source

N/A — behaviour only. The close glyph, its placement and every pixel stay as drawn; only which callback the X fires changes.

## Change

`MobileModal` gains `onCloseRequest: () -> Unit = onDismissRequest`, passed to `MobileModalShell`, whose close-glyph `IconButton` calls it (logged as `close_requested`) instead of the shared `dismiss`. Back and the footer Cancel keep `dismiss`. `EditHostModal` and `EditWorkspaceModal` pass their own `onDismissRequest` as `onCloseRequest`, so in the confirmation the X dismisses the whole editor while Cancel and Back still decline back to it. In the editor step both callbacks are already `onDismissRequest`, so nothing changes there. Every other modal keeps the default, and the gate, read-only and dismiss shells draw the glyph with the default too. No view model changes: both dismissals already null the editor state, so reopening starts on the editor. The KDoc on both edit modals is updated to say the X closes.

## Testing strategy

- `EditHostModalTest.unpairConfirmationReplacesTheContentInPlaceAndEveryDismissalRouteDeclines`: Cancel and Back decline (2), the X dismisses (1), nothing unpairs.
- `EditWorkspaceModalTest.archiveAsksInPlace_okConfirms_cancelDeclinesBackToTheTypedName`: the trailing X in the confirmation records `dismiss`, not `decline`; a new check that Back declines in the confirmation.
- `HostChannelListViewModelTest`: after dismissing from the unpair and archive confirmations, reopening the editor yields `confirmingUnpair`/`confirmingArchive` false.
