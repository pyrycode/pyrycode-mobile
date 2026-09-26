# Reference blue for outlined modal actions

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` — `ModalCancelButton`: shared editing Cancel, gate Cancel and read-only Close.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt` — `UnpairAction`: outlined host action.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditChatModal.kt` — `ArchiveAction`: enabled and disabled archive styling.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditWorkspaceModal.kt` — `ArchiveAction`: workspace archive styling.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditChannelModal.kt` — `ArchiveChannelAction`: channel archive styling.
- `app/src/main/java/de/pyryco/mobile/ui/components/AddWorkspaceModal.kt` — `CreateFolderAction`: icon and text inherit the button content colour.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadPermissionModal.kt` — `ModalOptionButton`: outlined non-default, unarmed choices.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` — `PyrycodeMobileTheme`: existing primary roles across light, dark and dynamic schemes.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/components/MobileModalFillTest.kt` — `sheetCentre`: native Robolectric dialog rendering pattern.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/components/EditHostModalTest.kt`, `EditChatModalTest.kt`, `EditWorkspaceModalTest.kt` — existing action, disabled-state and touch-target assertions.
- `docs/knowledge/features/mobile-modal.md` — Layout and theme / caller contracts: preserve native shapes, 48dp targets and caller-owned state.
- `docs/knowledge/features/host-editor.md` and `development-verification.md` — shared editor ownership and focused Robolectric verification.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

The inspected context and screenshot show a navy modal column with centred footer actions. Cancel and Unpair host use `Schemes/Primary` for both their thin outline and medium body-large text; the dark reference is pale blue. Preserve existing geometry and assets.

## Change

Set `ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)` at the seven outlined button sites above. Leave all other colour defaults, enabled flags, callbacks, borders and geometry intact. No new state, signatures, concurrency, error handling or logging is needed. The current GitHub issue explicitly includes permission choices and shared task Close in addition to the supplied initial body. No overlapping remote feature branches were found after fetching origin.

One styling deliverable: seven production files, approximately 14 production lines plus under 150 test/plan lines, zero new exported production types, zero consumer signature updates, two acceptance criteria, zero new error branches. This remains within all six sizing limits.

## Testing strategy

Extend the existing native Robolectric modal rendering test to check rendered Cancel text against primary in light and dark themes, and the gate's disabled Cancel against native outlined disabled content. Observe the colour assertion fail before implementation. Use a temporary render from that fixture for visual comparison with the Figma screenshot. Run the existing host/chat/workspace modal tests for callbacks, disabled controls and target sizes, then lint, debug assembly, test-source compilation and Spotless. No new operator flow or daemon interaction is introduced; no new live scenario is required.

## Documentation handoff

No documentation requirement or documentation-only acceptance criterion is specified by #1156. Shared feature documentation remains owned by the documentation stage.
