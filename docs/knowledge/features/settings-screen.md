# Settings modal

The Settings entry opens a dark, full-height modal at the optional `settings` route. Its current design is [Figma node `17:2`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2); the [shared mobile modal](mobile-modal.md) supplies its header, close control, separator, scrolling and safe-area handling.

## What it does

**Notifications is the only section.** It contains a “Push notifications when claude responds” Material switch and a “Notification sound” row with “Default” and a chevron. The sound row has no click action or picker. Connection, Appearance, Defaults, Memory, Storage and About have no Settings rows. The separate [sidebar Archive entry](navigation.md#archive-a-required-owner-destination-two-doors-715) remains available; removing the old Settings rows does not clear saved preferences.

The switch reflects `SettingsViewModel.pushNotifications`, backed by `AppPreferences.notificationsEnabled` (default `true`). Toggling persists the choice across modal reopen and app restart. Turning it on also invokes the existing Android notification-permission request in `PyryNavHost`; turning it off does not request permission. See [push messaging](push-messaging-service.md#attention-alerts-and-the-tap-route-685) for the delivery path.

The title reads “Settings”. The header Close control, filled Done action at bottom right, and Android Back all call `onDismissRequest`, which pops this destination to the previous view. Outside taps do not dismiss. The shared shell starts this caller's content directly below the header and lets it scroll at compact height; the two rows wrap at narrow width and enlarged text. The Settings instance has no generic frame's extra 4 dp outer top inset. Figma omits Android system bars, while the actual dialog respects safe drawing insets. See the [labelled 412 × 892 comparison](../../../app/src/androidTest/assets/settings-1239/comparison-412x892.png) and [compact 2× capture](../../../app/src/androidTest/assets/settings-1239/emulator-320x640-scale-2.png).

## Wiring and verification

`SettingsScreen(pushNotifications, onTogglePushNotifications, onDismissRequest)` is stateless. Its route collects the preference flow, forwards the toggle to the ViewModel, requests Android notification permission only on enable, and dismisses with `navController.popBackStack()`. The optional route argument remains for existing entry points but supplies no content to this modal. `SettingsRow` and `ThemeMode.label()` remain in the settings package for the separate [About screen](about-screen.md) and theme picker; neither adds a Settings row.

The focused [screen tests](../../../app/src/sharedTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt) check the two rows, absence of removed sections, the inert sound row, switch callback and dismissal. [Navigation tests](../../../app/src/sharedTest/java/de/pyryco/mobile/ui/settings/SettingsNavigationTest.kt) exercise the production graph and preference persistence across reopening; [device tests](../../../app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsDensityDeviceTest.kt) check focused system Back and text layout at 412 × 892 and 320 × 640 with 2× text. A semantics text assertion can pass when fractional intrinsic width causes visible overflow: the sound label and subtitle fill their available column width, and the device test checks `TextLayoutResult.hasVisualOverflow`. Dialog semantics use the dialog window's coordinates, so activity-window system-bar helpers cannot assess its bounds.

## Related

- [Navigation](navigation.md#settings-an-optionally-owned-destination) — entry and return behavior.
- [Mobile modal callers](mobile-modal-callers.md#callers) — the `MobileDismissModal` caller.
- [App preferences](app-preferences.md) and [Settings ViewModel](settings-viewmodel.md) — persisted values retained after the Settings surface changed.
- [Settings notifications modal plan](../../specs/architecture/1239-settings-notifications-modal.md) — design source and implementation revisions.
- [Earlier Settings implementation](settings-screen-how-it-works.md) and [edge cases](settings-screen-previews-and-edge-cases.md) — historical descriptions of the removed sectioned page.
