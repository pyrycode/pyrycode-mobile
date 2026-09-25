# #1142 — Modal sheet: paint the frame's deep navy in dark theme

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModalShell` — its `Surface` paints `colorScheme.primaryContainer`; the only line whose fill changes. Every `MobileModal` / `MobileGateModal` / `MobileReadOnlyModal` caller goes through it.
- `app/src/main/java/de/pyryco/mobile/ui/theme/SuccessColors.kt` → `SuccessColors`, `LocalSuccessColors`, `ColorScheme.success` — the app-slot pattern this ticket mirrors one-for-one.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` → `PyrycodeMobileTheme`, `lightSuccessColors` / `darkSuccessColors` — where the new holder is selected and provided.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt` → `primaryContainerLight` (`#CFE4FF`), `primaryContainerDark` (`#134A74`) — palette literals.
- `docs/knowledge/features/success-color.md` — "a new sibling holder, never a wider existing one".
- `docs/knowledge/features/mobile-modal.md` § token table — the `onPrimaryFixed` row this ticket makes partly obsolete (documentation handoff below).
- `app/src/sharedTest/java/de/pyryco/mobile/ui/components/EditHostModalTest.kt` — Robolectric `@GraphicsMode(NATIVE)` screen-test shape.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

Edit host full-height sheet: a rounded column filled with `Schemes/On Primary Fixed` (`#001D34`), light title and body text, a divider under the header, a close glyph top-right and a centred Cancel / OK footer. Only the fill is in scope; content colours, shapes and layout are unchanged.

## Context

M3's fixed roles are meant to be the same in both themes, and `#001D34` behind today's light-theme content colour (`#134A74`) would be unreadable, so `onPrimaryFixed` cannot carry this. The app already grafts theme-dependent slots (`warning`, `success`) through a `CompositionLocal` + `ColorScheme` extension; a third sibling lets light and dark differ.

## Change

- New `ui/theme/ModalColors.kt`: `@Immutable data class ModalColors(val container: Color)`, `internal val LocalModalColors` (`staticCompositionLocalOf` that throws when unprovided, as `LocalSuccessColors` does), and `val ColorScheme.modalContainer: Color` (`@Composable @ReadOnlyComposable`).
- `Color.kt`: one literal, `modalContainerDark = Color(0xFF001D34)`.
- `Theme.kt`: `lightModalColors = ModalColors(primaryContainerLight)`, `darkModalColors = ModalColors(modalContainerDark)`, selected by `darkTheme` like `successColors` and added to the existing `CompositionLocalProvider`.
- `MobileModalShell`: `Surface(color = MaterialTheme.colorScheme.modalContainer, …)`; `contentColor` stays `onPrimaryContainer`. The comment above the `Surface` is updated to say why the fill is a custom slot.

Light keeps the literal `#CFE4FF`. `dynamicColor` is never enabled in the app; under it the sheet would keep the static palette rather than the dynamic container, the same as `success`. No caller changes.

## Testing strategy

New `app/src/sharedTest/java/de/pyryco/mobile/ui/components/MobileModalFillTest.kt` (Robolectric, `@GraphicsMode(NATIVE)`): render `MobileModal` with empty content in `PyrycodeMobileTheme(darkTheme = false)` and `(darkTheme = true)`, capture the sheet node (matched by its `paneTitle`) and sample an empty interior pixel:

- dark → `#001D34`
- light → `#CFE4FF`
- title text colour is covered by the unchanged `contentColor`; the test also asserts the resolved `LocalContentColor` inside `content` is `onPrimaryContainer` in each theme.

## Documentation handoff (pending — documentation stage)

`docs/knowledge/features/mobile-modal.md`, token table row "`onPrimaryFixed` background": dark theme now paints the frame's `#001D34` through `colorScheme.modalContainer`; light theme keeps `primaryContainer` (`#CFE4FF`); content colour stays `onPrimaryContainer` in both.

## Revisions

- **Test capture (Phase B).** `captureToImage` on the sheet node times out under Robolectric: it waits for a redraw of the dialog window that Robolectric never performs. `MobileModalFillTest` instead records the dialog's `LocalView` from inside `content` and draws it into a bitmap by hand, then samples the centre pixel. The assertions are unchanged.
