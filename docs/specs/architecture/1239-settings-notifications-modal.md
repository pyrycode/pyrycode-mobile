# Settings notifications modal (#1239)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt` → `SettingsScreen`, `SettingsRow`, `ThemeMode.label` — current seven-section page; `SettingsRow` and the theme label still have other callers.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `PyryNavHost` settings destination, `rememberNotificationPermissionRequest` — navigation and permission wiring.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModalShell`, `MobileReadOnlyModal`, `ModalSubmitButton` — shared dialog chrome, Back handling, and filled action.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt` → `SettingsScreenTest` — former section assertions to replace with modal behavior.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/settings/SettingsNavigationTest.kt` → `SettingsNavigationTest` — production graph harness; former host-row flows are obsolete.
- `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsDensityDeviceTest.kt` → `SettingsDensityDeviceTest` — real viewport and font-scale capture harness.
- `docs/knowledge/features/settings-screen.md` § What it does — push persistence and the legacy sections being removed from this surface.
- `docs/knowledge/features/navigation.md` § Settings: an optionally-owned destination — route compatibility and separate Archive entry.
- `docs/knowledge/features/mobile-modal.md` and `docs/knowledge/features/mobile-modal-callers.md` — shell geometry and caller inventory.
- `docs/knowledge/features/development-verification.md` § Where a screen test goes / Compose evidence — Robolectric and actual-device evidence rules.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2 (inspected 2026-09-28). Shared shell `533:2369`; component `489:1942`.

At 412 × 892 the dark, full-height rounded modal has a 28 dp inset, Settings title, circular close glyph and separator. Notifications begins immediately below the header: a two-line push row with an M3 switch, then Notification sound / Default with a small chevron; a single filled Done button sits at bottom right. The Settings instance omits the 4 dp outer top inset present in the generic shell frame.

## Context

The Settings destination still renders removed sections as a page. The existing push preference and notification-permission request already meet the state contract; the change is presentation and routing only. The remembered-model behavior from #1194 remains outside this surface. Archive remains reachable from the channel-list sidebar. No in-flight feature branch overlaps the planned files.

## Design

- Keep the optional `Routes.SETTINGS` destination so the existing gear opens it and Back returns to its previous entry. Simplify its `PyryNavHost` block to collect `SettingsViewModel.pushNotifications` and pass its existing toggle callback plus the permission launcher to `SettingsScreen`.
- Make `SettingsScreen(pushNotifications, onTogglePushNotifications, onDismissRequest)` render only the two notification rows inside a new `MobileDismissModal` shell variant. The sound row keeps the design chevron but has no value mutation or picker. Keep `SettingsRow` for `AboutScreen` and `ThemeMode.label` for `ThemePickerDialog` and its tests; remove only obsolete Settings content.
- Extend `MobileModalShell` with caller-controlled content vertical alignment and footer alignment, preserving existing defaults. `MobileDismissModal` selects top content and end-aligned single filled Done action. Close, Done, and dialog Back call the same dismissal callback. The shell remains responsible for system insets, compact-height scrolling and modal event logging.
- Use the existing M3 theme roles, typography, switch and close glyph. Use the design's 20 dp chevron asset if no exact local asset exists. Preserve 12 dp vertical row padding and 16 dp row gap. The content column and shell scrolling must allow wrapping at compact width and 2× text.

## State and concurrency

`SettingsViewModel.pushNotifications` remains the persisted `StateFlow` backed by `AppPreferences`. `onTogglePushNotifications` keeps its `viewModelScope` persistence path. The destination requests Android notification permission only when enabling. No new job or state owner is introduced; dismissing the dialog pops the route and clears its ViewModel with the destination.

## Error handling

The existing preference write and permission request behavior remains. Notification sound is deliberately inert. The modal makes no network or parsing calls. The existing shell emits content-free lifecycle logs.

## Testing strategy

- Replace obsolete screen assertions with focused shared Compose tests for only the two notification rows, absent sections, switch callback, inert sound row, all dismissal controls, and readable content at compact width / 2× font scale.
- Update the production-graph navigation test to assert the gear opens the modal, dismissal returns to the prior view, persisted push state survives destination recreation, and the separate Archive entry remains. Keep the existing preference unit coverage.
- Replace the obsolete settings density device scenario with a focused 412 × 892 and compact / enlarged-text capture and geometry check. Run the affected device class and inspect executed XML. Compare its real emulator capture with the current Figma frame at the same logical viewport using a labelled overlay or difference image. Existing modal-shell coverage remains applicable.
- This local presentation change introduces no new daemon interaction and needs no real-Claude scenario.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/settings-screen.md` under “What it does” and `docs/knowledge/features/navigation.md` under “Settings: an optionally-owned destination” for the notifications-only modal and dismissal. Align `docs/knowledge/features/mobile-modal-callers.md` under “Callers” with the new modal caller.

## Open questions

- Determine whether an exact chevron asset is already present before adding the Figma SVG.
- Confirm the available emulator produces usable pixels for the required visual comparison; if the managed ATD image returns black pixels, use the configured full-image capture path and report its evidence location.

## Size self-check

Three production Kotlin files, about 700 written lines including tests/capture/plan, one new internal composable, one call site, five acceptance criteria, zero new reject branches.

## Revisions

- 2026-09-28: The Figma chevron has no exact local asset. Converted the supplied SVG path into an Android vector with the same 20 dp viewport and stroke; its tint comes from the dark scheme's `onSurfaceVariant` role.
- 2026-09-28: Exact text measurement in the dialog must run on the device. The first real capture found `Notification sound` reporting overflow at its fractional intrinsic width; making its label and subtitle fill the available column width cleared both device and Robolectric checks. The compact 2× font proof therefore lives in `SettingsDensityDeviceTest`, not in a `DeviceConfigurationOverride` around the dialog's separate window.
- 2026-09-28: Espresso Back in the production-graph harness selected its unfocused activity root while the dialog owned focus. `SettingsDensityDeviceTest` sends the real system Back key to the focused modal and checks dismissal. The shared screen test also checks that the shell invokes its dismissal callback for Back.
- 2026-09-28: Verifier's full UI gate found `MainActivityInsetsDeviceTest.exercise` still asserted the removed Settings Back control and Theme row. Its Settings stop now checks that the modal title, Close, Notifications, and Done are displayed, then uses Done before continuing to the thread. The dialog's semantics bounds are relative to its own window, so the activity-window system-bar helper cannot measure them. `SettingsDensityDeviceTest` retains the focused Back-key and real-window text proof.
