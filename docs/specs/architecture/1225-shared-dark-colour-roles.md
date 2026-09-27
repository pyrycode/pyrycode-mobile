# #1225 — Shared dark colour roles

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt` → dark palette constants — the static dark role values and the current success placeholder.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` → `darkScheme`, `PyrycodeMobileTheme` — Material role wiring and the distinct static, dynamic and light paths.
- `app/src/main/java/de/pyryco/mobile/ui/theme/SuccessColors.kt` → `ColorScheme.success` — the sidebar attention dot reads this custom role.
- `app/src/main/java/de/pyryco/mobile/ui/theme/ThreadColors.kt`, `BubbleColors.kt`, `ComposerColors.kt`, `ModalColors.kt` → scoped colour locals — existing screen-specific fills must retain their own behaviour.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `TreeConversationRow`, `ConversationStatusDot` — selected fill and unread-success consumer.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/OptionsOverlay.kt` → `OptionsColumn` — menu background and selected/unselected row mapping.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListColoursTest.kt` → `darkPanelAndBarMatchTheReferenceWithOneBlueGreyRule` — pixel-level sidebar precedent.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/theme/WarningColorSlotTest.kt` → `darkTheme_resolvesWarningToDarkValue` — theme-role assertion pattern.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/WelcomeAppearanceDeviceTest.kt` → `darkAt412By892` — physical emulator capture and density setup pattern.
- `docs/knowledge/features/options-overlay.md` § Colour deviation from the design — records the deliberate approximation this ticket removes.
- `docs/knowledge/features/success-color.md` § Authoritative values — records the placeholder that the live `Schemes/Success` variable supersedes.
- `docs/knowledge/features/development-verification.md` § Compose evidence — real screenshot requirements and artifact retention.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Inspected on 2026-09-28: channel-list frame `15:8`, sidebar `132:3902`, options overlay `533:1958`, shared modal `533:2369`, thread `16:8`. The 412 × 892 dark frame has a `Schemes/Surface` (#101418) base with a dark translucent sidebar layer, `Schemes/On Surface` (#e0e2e8) text, `Schemes/Inverse Primary` (#32628d) rule, `Schemes/Primary Container` (#134a74) hover, `Schemes/On Primary` (#003355) selected row and `Schemes/Success` (#2fc038) status dot. The options component uses `Schemes/On Primary` for its outer fill and selected row, with `Schemes/On Primary Fixed` (#001d34) for unselected rows; `Schemes/Primary` (#9dcbfc) labels remain as drawn. These are M3 roles except the existing custom success slot. The modal and thread references also bind `On Primary Fixed` to #001d34, but their scoped treatment remains local.

## Context

The static dark theme already matches most live bindings, but its custom success slot still holds a pre-Figma placeholder and the menu and selected sidebar row use approximate Material surfaces. This change maps the observed roles to the static dark palette while preserving theme paths that the ticket does not redesign.

## Design

- Set `successDark` to the live `Schemes/Success` value and add a static-dark `onPrimaryFixed` palette value. Retain the previous dark success value for the dynamic-colour path so the setting keeps its current appearance.
- Wire `onPrimaryFixed` through `darkScheme`. `PyrycodeMobileTheme` publishes an internal static-dark indicator alongside its existing scoped locals. It is true only when `darkTheme && !dynamicColor`; no public theme signature changes.
- `TreeConversationRow` uses `colorScheme.onPrimary` for a selected row in static dark mode. Every other mode keeps the existing translucent `primaryContainer` selection.
- `OptionsColumn` uses `onPrimary` as its surface and `onPrimaryFixed` as unselected row fill in static dark mode; its selected row shows the outer `onPrimary` fill. Every other mode keeps the existing surface and selected-fill mapping. Text, geometry and interaction stay the same.
- Existing thread, bubble, composer and modal scoped colours are not remapped. In particular, `MobileModal`'s dark fill matches the fixed-role hex already, while its light fill intentionally differs.

## State and concurrency model

Palette selection is synchronous composition-local state from `PyrycodeMobileTheme`. There are no jobs, flows or new cancellation paths.

## Error handling

These are compile-time colour values and UI rendering choices; no I/O or new failure result is introduced. The device capture may be absent if the emulator lacks physical system bars; the focused run will report that as an evidence gap instead of claiming a match.

## Testing strategy

- Add a focused shared Compose test for `onPrimaryFixed`, `success`, static-dark selection and options-row pixels, plus checks that light and dynamic paths retain their prior mappings. Run it red before production edits, then green.
- Use a focused API 35 managed-device capture at density 1 and 412 × 892 logical size, retaining the emulator PNG, Figma render, labelled comparison, result XML and capture context under `app/src/androidTest/assets/colors-1225/`. The capture fixture uses a matching sidebar state and does not assert geometry that belongs to another ticket. Check the actual test execution count and image dimensions.
- Run focused unit tests, Android Lint, debug assembly and androidTest compilation. No real-Claude scenario is needed for a palette-only change.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/options-overlay.md` § Colour deviation from the design to describe the exact static-dark role mapping, and `docs/knowledge/features/success-color.md` § Authoritative values to replace the placeholder dark-success rationale with the live `Schemes/Success` binding. Record the role choice for the sidebar in `docs/knowledge/features/channel-list-screen-tree-and-controls.md` § Selection. Do not change those shared documents in the builder stage.

## Open questions

- Confirm the installed Material 3 `ColorScheme` exposes `onPrimaryFixed` to Kotlin; if not, keep the same semantic mapping through a custom role with a revision entry.
- Confirm the managed API 35 device can render a true density-1 412 × 892 capture with content suitable for comparison; record any seed-data or system-bar gap in the evidence and PR.
