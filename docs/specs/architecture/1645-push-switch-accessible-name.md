# #1645 — Name the push-notifications switch

## Files read
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt`: `SettingsScreen` renders the push switch and its separate label.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt`: `pushSwitchUpdatesAndSoundDoesNothing` covers toggling, callback count and the inert sound row.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/McpServersSection.kt`: `ShowBuiltInRow` and `McpServerRow` label switches with content-description semantics.
- `docs/knowledge/features/settings-screen.md`: the collapse switch already follows that convention; preserve the push row's existing geometry.

## Design source
**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

The design-context screenshot shows the dark shared modal, Notifications heading, two-line push label beside a Material switch, sound row and Done footer. Existing Material theme typography and colours remain; this change only names the existing switch in semantics.

## Change
Add `Modifier.semantics { contentDescription = "Push notifications when claude responds" }` to the push switch. Replace the existing test's indexed switch lookup with the same content description combined with `isToggleable()`. No new types, state, failure branches or consumer signature changes; no overlapping in-flight branches.

## Testing strategy
Run `SettingsScreenTest.pushSwitchUpdatesAndSoundDoesNothing` before the production change and confirm its name lookup fails. After the repair, run the complete `SettingsScreenTest` and existing `SettingsScreenGeometryTest`; retain off-to-on, callback-count and inert sound-row assertions. Run lint, debug assembly, Android-test compilation and the required formatting/pre-verification checks. This semantics repair adds no new live operator flow or device-only test.
