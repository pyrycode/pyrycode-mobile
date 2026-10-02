# #1503 — Settings Notifications rows and Done spacing

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt` — `SettingsScreen`, the four Notifications texts.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` — `MobileDismissModal` and its `bottomPadding`; `MobileModalShell`'s footer row; `ModalSubmitButton`'s `minimumInteractiveComponentSize`.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt` — `AppTypography` sets no `lineHeightStyle`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadPermissionModal.kt` — `ContextLineBox`, the existing local `LineHeightStyle(Center, Trim.None)` pattern to mirror.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt` — the screen test the new geometry test sits beside.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

The shared modal shell (28 px gutters, 24 px top and bottom padding, 28 px title row, 12 px gap, 1 px rule, 20 px gap) with a 12 px-gap content column: `labelLarge` "Notifications" (20 px line box), a `bodyLarge` push row with py 12 and a 52x32 switch, and a "Notification sound" row with py 12, `bodyLarge` headline, 2 px gap, `bodySmall` "Default" and a 20 px chevron. The footer has `pt 4` and a 40 px filled Done whose bottom edge sits 24 px above the sheet's bottom. Frame `600:1694` (Run configuration) uses the same footer geometry: footer at y 824, Done 828–868 in an 892 sheet.

## Change

Two causes. First, `AppTypography`'s styles carry no `lineHeightStyle`, so Compose applies its default `Trim.Both` and each single text box loses the half-leading above its first line and below its last. A 24 px `bodyLarge` line measures about 19 px, so the label, the push text and the sound row all shrink and the rows below creep up (the audit's -3, -6 and -10 px). `SettingsScreen` gives its four Notifications texts a local `LineHeightStyle(Center, Trim.None)`, as `ThreadPermissionModal` does, so each text keeps the frame's full line box; the theme stays untouched because changing it would move every screen. Second, `ModalSubmitButton` keeps a 48 dp layout around its 40 dp surface, so the footer row ends 4 dp below the visible Done; `MobileDismissModal` adds `bottomPadding = 24.dp` on top, leaving Done 28 dp above the sheet's edge. `MobileDismissModal` drops that override and takes the shell's default 20 dp, as `MobileModal` already does, putting Done's visible bottom 24 dp above the sheet. This moves the Run configuration sheet's Done the same 4 dp, onto its own frame's 24 px. The title row is unchanged: it already matches at 28 dp because the close box sets its height.

## Testing strategy

A new `@GraphicsMode(NATIVE)` test in `SettingsScreenTest`'s package, `SettingsScreenGeometryTest`, renders `SettingsScreen` at 412x892 and density 1 and asserts, relative to the pane's top, the frame's text-box offsets (label 85, push text 129, sound 213, Default 239, within 1 dp) and that Done's bottom sits 24 dp above the pane's bottom. It fails on the current tree. `SettingsScreenTest` and `StatusSheetTest` still run for behaviour and Done reachability.
