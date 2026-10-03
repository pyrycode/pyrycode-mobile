# #1634 — "Collapse assistant tool uses" switch in a new Thread section

## Files read

- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` — `notificationsEnabled` / `setNotificationsEnabled` and the `NOTIFICATIONS_ENABLED` key, the pattern the new preference mirrors.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt` — `pushNotifications` and `onTogglePushNotifications`.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt` — `SettingsScreen`, its Notifications heading and push row, `FrameLineBox`, the dark preview.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` — the `Routes.SETTINGS` composable that calls `SettingsScreen`.
- Callers of `SettingsScreen` to update: `SettingsScreenTest`, `SettingsScreenGeometryTest` (sharedTest) and `SettingsDensityDeviceTest` (androidTest).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=726-8150

The shared Settings modal gains a second section below Notification sound in the same 12 dp-gap content column: a "Thread" heading in `labelLarge` (semibold, 20 px line) in `Schemes/on-primary-container`, identical to "Notifications", then a "Collapse assistant tool uses" row copying the push row — `bodyLarge` label in `Schemes/on-surface`, weighted, 16 dp gap, trailing Material 3 `Switch` (52×32), 12 dp vertical padding — shown on. No new assets.

## Change

`AppPreferences` gains `collapseToolUses: Flow<Boolean>` (defaulting to `true` when unset) and `setCollapseToolUses(Boolean)` under a new `booleanPreferencesKey("collapse_tool_uses")`, phone-local and never sent to the daemon. `SettingsViewModel` exposes `collapseToolUses: StateFlow<Boolean>` (same `stateIn(WhileSubscribed)` lift, initial `true`) and `onToggleCollapseToolUses(Boolean)` that launches the write. `SettingsScreen` takes two new required parameters, `collapseToolUses` and `onToggleCollapseToolUses`, placed after the push pair, and renders the heading and row after the Notification sound row with the existing styles. `MainActivity` collects the flow and passes both through; the toggle does not request notification permission. Nothing reads the value in the thread yet (follow-up from #1576). Overlapping tickets, if any, touch these files additively only.

## Testing strategy

- `AppPreferencesTest`: `collapseToolUses_defaultsToTrue`, `setCollapseToolUses_roundTripsBothValues`.
- `SettingsViewModelTest`: initial `true` with nothing stored, mirrors a persisted `false`, and `onToggleCollapseToolUses` writes the store and the flow re-emits.
- `SettingsScreenTest` (sharedTest, Robolectric): the "Thread" heading, the label and a second `Role.Switch` are displayed and the new switch is on; toggling it calls `onToggleCollapseToolUses` and leaves the push callback untouched. The existing push-switch test now selects the push switch specifically, since two switches exist.
- `SettingsScreenGeometryTest`: adds frame offsets for "Thread" (279) and "Collapse assistant tool uses" (323), derived from the Notification sound row (201 + 66) plus the 12 dp content gap; existing offsets and Done's bottom gap stay.
- `SettingsDensityDeviceTest`: call site updated and the two new labels added to its clipping list; run focused on the managed device.

## Revisions

- 2026-10-03: The collapse row uses 4 dp vertical padding, not the push row's 12 dp. Its one-line label leaves the switch's 48 dp touch target as the tallest child, so 12 dp gave a 72 px row and put the label 8 px below the frame. 4 dp plus the target's own 8 dp above and below the 32 px track reproduces the frame's 56 px row and 12 px inset. Geometry expectations corrected accordingly: label top 327 (centred on the track, not 323), switch track top 323. `SettingsNavigationTest` now picks switches by index and gains a reopen test for the collapse switch, covering the "shows the stored value when Settings is opened again" criterion end to end.
