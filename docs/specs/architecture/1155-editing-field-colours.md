# Editing-field colours (#1155)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/theme/ModalColors.kt` → `ModalColors`, `modalContainer`: existing shared modal colour slots.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` → `PyrycodeMobileTheme`: explicit light/dark choice, including dynamic schemes.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt` → `HostNameField`: host field fill and foreground.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditChatModal.kt` → `ChatNameField`: chat field fill and foreground.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditWorkspaceModal.kt` → `WorkspaceNameField`: workspace fill and validation presentation.
- `app/src/main/java/de/pyryco/mobile/ui/components/ChannelFormFields.kt` → `wellColors`: shared Edit/Create/Save-as channel fields, including disabled and error states.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/components/MobileModalFillTest.kt` → `sheetCentre`: native Robolectric dialog rendering avoids the blank `captureToImage` result.
- `docs/knowledge/features/mobile-modal.md` → Layout and theme, Callers: retain the light mapping and the identity-keyed editing buffers.
- `docs/knowledge/features/development-verification.md` → Where a screen test goes: shared tests and native graphics.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

The inspected frame and screenshot show a navy column modal, a label above a rounded filled name well, and body-medium entered text. The field uses `Schemes/On Primary` (`#003355`) at 41% over the shell (`#001D34`), with `Schemes/On Background` (`#E0E2E8`) text; labels retain `onPrimaryContainer`. Layout, icons, and typography already exist and do not change.

## Change

Extend `ModalColors` with field-container and field-text colours, exposed as `ColorScheme` extensions. `PyrycodeMobileTheme` supplies dark `onPrimary.copy(alpha = 0.41f)` / `onBackground` and light `onPrimaryContainer.copy(alpha = 0.12f)` / `onPrimaryContainer`, derived from the active scheme. Keep the shell's existing mapping. The four field components consume the shared roles for their current container overrides and focused/unfocused text, removing the obsolete local alpha constants. Preserve default error/disabled text, cursor, indicators, validation, keyboard actions, logging and all state ownership. No new state, jobs, failure modes, dependencies or operator flow.

Size check: one deliverable, six production files, approximately 300 total written lines including tests and plan, no new exported types/composables, two existing constructor calls updated, two acceptance criteria, no reject branches. This is slightly more than the refiner's 24-line/five-file sketch because the existing theme mapping spans two files; it remains a small palette change. Codegraph found no constructor callers, so source search confirmed both are in `Theme.kt`. No overlapping remote feature branches after fetch.

## Testing strategy

Add native-graphics shared Compose regression tests beside `MobileModalFillTest`: render the actual channel fields inside the shell, verify dark and unchanged light well pixels and entered-text layout colour in focused and unfocused states. Check the supplied theme roles as well. Run the test RED before implementation, then GREEN. Existing host/chat/workspace/create/save-as tests cover editing, validation, disabled controls and focus; run these scoped classes. Run Spotless, lint, assembleDebug and androidTest compilation. Inspect a rendered field against the Figma target. This styling-only adjustment adds no daemon flow or real-Claude scenario; full regression/device acceptance remains with the dispatcher.

## Documentation handoff

The ticket has no explicit documentation requirement. Pending documentation stage: update `docs/knowledge/features/mobile-modal.md`, “Layout and theme” and the Edit host “Callers” colour lesson, to describe the shared dark field roles and preserved light mapping.

## Open questions

None.
