# Native notice touch separation (#1760)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlay.kt`: `ThreadTopOverlay` owns the local touch configuration and visible 12dp stack gap.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/NoticePill.kt`: `NoticePill` centers an independent dismiss target inside the padded visible surface.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlayTest.kt`: `theUsagePill_sitsAboveThePairingPill_whichStartsRePair` already compares visible and touch bounds, but activates Re-pair semantically.
- `docs/knowledge/features/thread-top-overlay.md` and `notice-pill.md`: visible surfaces and invisible targets must be measured independently; text height varies with native fonts and scaling.
- `docs/knowledge/features/development-verification-gates.md`: the shared test must also be selected explicitly on the managed device; its default density is 2.625.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-3139

The screenshot and design context show a right-aligned Default usage pill above the Error Re-pair pill, with 12dp between visible surfaces. Existing `NoticePill` supplies bodySmall typography, primaryContainer/onPrimaryContainer and errorContainer/error tokens, and the exported X; this touch-only correction retains those visuals.

## Change

Replace the fixed 36dp vertical minimum with the existing stack gap. Symmetric minimum-target expansion then reaches at most half that gap beyond any clickable node, so two adjacent targets cannot cross regardless of native text height; actual larger clickable surfaces keep their full height. Preserve the platform horizontal minimum and visible spacing. This deliberately trades excess vertical expansion for unambiguous routing, without assuming a 24dp rendered pill. No new type, signature, state or failure mode is needed. Overlaps with #1603, #1747 and #1755 affect other overlay blocks or additive tests; keep this edit local.

## Testing strategy

Strengthen the existing named shared regression with physical center and facing-edge taps for both actions, exact callback counts, dismissal-state checks and the existing visible-gap/nonoverlap assertions. Assert the dismiss target retains its platform horizontal minimum. First run the named method on managed Android 13 against unchanged production to observe the native failure, then run it after the fix and run the full affected class on JVM, plus NoticePill and attention coverage. Retain fresh JVM/native XML and commands/counts under `app/src/androidTest/assets/touch-1760/`. Run lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply and forced spotlessCheck. This is local hit geometry, with no new daemon action or real-Claude scenario.
