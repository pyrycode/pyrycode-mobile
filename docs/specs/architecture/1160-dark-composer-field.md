# Align the dark composer field and text (#1160)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBar.kt` → `ThreadInputBar` — field surface, both text styles, existing sizing and input behavior.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` → `PyrycodeMobileTheme` — effective app theme and dynamic palette selection.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt` → `onPrimaryDark` — existing static `#003355` token.
- `app/src/main/java/de/pyryco/mobile/ui/theme/ModalColors.kt` → `LocalModalColors`, `modalFieldContainer` — composition-local colour extension pattern; keep editing forms separate.
- `app/src/main/java/de/pyryco/mobile/ui/theme/SuccessColors.kt` → `LocalSuccessColors` — single-colour local pattern.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt` → `AppTypography` — standard M3 bodyMedium already supplies 14sp/20sp.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/components/ModalFieldPaletteTest.kt` → `assertPalette` — native Canvas colour sampling precedent.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBarDraftBindingTest.kt` → `typedText_neverReachesSavedInstanceState` — existing heap-only draft protection.
- `docs/knowledge/features/thread-input-bar.md` → field sizing and draft binding — retain five-line cap, send/stop and attachment paths.
- `docs/knowledge/features/mobile-modal.md` → Layout and theme — composer must not reuse the modal mapping, which also changes wallpaper colours.
- `docs/knowledge/features/development-verification.md` → Where a screen test goes, Compose evidence — shared tests with native graphics for text and colour checks.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1957

Read the Input area and its `I533:1957;347:6635` child context and screenshots. The field is a 6dp rounded navy rectangle with `#003355` at 41% opacity, M3/body/medium text (14sp/20sp), 16dp leading inset and a trailing 48dp send target with a 28dp glyph. This ticket changes fill and typography only; existing icons, layout and behavior remain as the acceptance criteria require.

## Change

Add `composerFieldContainer`, a `ColorScheme` extension backed by a single composition-local colour in `ui/theme/ComposerColors.kt`, following existing app colour roles without adding a wrapper type. `PyrycodeMobileTheme` provides `onPrimaryDark.copy(alpha = 0.41f)` only for resolved dark theme with wallpaper colours off; all other modes provide the selected scheme's existing `surfaceContainerHigh`. `ThreadInputBar` consumes that role and changes both entered text and placeholder to `bodyMedium`. No Material palette or typography values, public function signatures, input state, jobs, error branches or logging lifecycle events change.

One deliverable, three production files, approximately 220 total written lines including tests and this plan, zero new exported types, zero consumer signature updates, two acceptance criteria, zero error/reject branches. The #1155 analogue wrote 196 added lines across its plan and implementation; this change has fewer field consumers. All six sizing bounds hold. Remote feature branches refreshed and checked: no overlap with the proposed production files.

## Testing strategy

First add a shared `ThreadInputBarStyleTest` and run it RED against current code. Native graphics will check rendered field colour when empty, focused and typed; opposite app/system theme selection; unchanged light and both wallpaper mappings; actual placeholder and entered layout styles; 52dp minimum, 48dp target, wrapping and the five-line height cap. Use the existing draft and paste tests as regression coverage. This is a visual retune of an existing flow, not a new operator action or daemon path, so no new live scenario is needed.

Run the focused shared tests GREEN, Spotless, lint, assembleDebug and compileDebugAndroidTestKotlin. Compare the field render with the Figma child screenshot. Dispatcher owns full regression and device gates.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/thread-input-bar.md`, sections “What it does” and “How it works” (root Surface and BasicTextField), to describe the composer-specific static-dark fill and bodyMedium text. The ticket has no separate documentation acceptance criterion. No shared documentation is edited by this builder.
