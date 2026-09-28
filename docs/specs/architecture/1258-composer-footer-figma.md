# 1258 — Composer footer alignment

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt` → `ThreadComposerFooter`, `FooterButton`, `FooterTextRow`, `ContextSegment`: current layout, anchors, semantics and callbacks.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`: bottom-bar gutter, spacing, picker, Actions overlay and Run configuration host.
- `app/src/main/res/drawable/ic_attach_file.xml` → exact existing Figma attachment path and dimensions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooterTest.kt` → existing action and sheet coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooterWidthTest.kt` → compact width and target geometry coverage.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` and `Type.kt` → static dark scheme and M3 body-small mapping.
- `docs/knowledge/features/thread-composer-footer.md` and `thread-composer-footer-context-usage.md` → preserved menu, context reading and remembered model contracts.
- `docs/knowledge/features/development-verification.md` → shared Compose/device test and real-pixel capture guidance.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1957

Inspected on 2026-09-28: `533:1957` Input area and `115:3677` footer button. The dark input footer has 16 px horizontal inset and 4 px top inset; Actions and context share a left cluster with 16 px gap. Both labels use M3 body small / `Schemes/Primary`; Actions has an 8 × 4 px upward chevron at a 4 px gap. The paperclip is the supplied 11 × 12 px path at the right. The reference has no Run configuration opener; the issue explicitly keeps it, so this is a documented reference conflict and the existing Tune opener remains after Attach.

## Context

The removed model, effort and permission footer selectors are already gone. This ticket aligns the remaining footer presentation and preserves its three entry points. The source node's attached files and thinking state are examples; footer work does not change them.

## Design

Retune `ThreadComposerFooter`'s visual geometry locally: exact upward chevron path and size, theme-based body-small text and primary color, existing paperclip path, left text spacing, trailing 32 dp tap regions. Keep `FooterTextRow`'s width allocation so context ellipsizes before icons are squeezed. Keep Actions' bounds reporting and callbacks, Attach's picker callback, and the Run configuration callback. `ThreadScreen` keeps the current bottom bar host and 20 dp gutter, with only a local adjustment if the measured comparison requires it. No ViewModel, repository or wire change.

State and concurrency stay as implemented: the footer is stateless; `ThreadScreen` owns the menu and sheet state and the picker launcher. No new job or error branch is introduced. Existing model recall and context reporting remain upstream of this UI.

## Testing strategy

- First add a shared Compose assertion for Actions chevron visual bounds and footer control spacing, and run it red before production edits. Extend the compact width check for context and all three actions.
- Run focused `ThreadComposerFooterTest` and `ThreadComposerFooterWidthTest` for menu, picker callback, sheet choices and bounds at 412 dp and compact width; exercise enlarged text.
- Capture real emulator dark screenshots at 412 × 892 and compact width, including keyboard-raised state; retain them and same-viewport Figma references under `app/src/androidTest/assets/1258-footer/` with a labelled comparison or difference image. Device-only pixels justify the capture path. Record missing Figma states, node IDs and inspection date in the PR.
- Run scoped Gradle test, lint, debug assembly and Android test compilation. No rung-3 scenario: the existing operator actions are unchanged and this ticket retunes presentation.

## Open questions

- The live Figma node lacks the required Run configuration icon. Keep the existing opener as product contract, and identify its unmatched region in the comparison.
- The supplied Input area is a cropped 372 × 180 component, not a full 412 × 892 screen or compact/IME state. Compare its footer at the same logical scale and label surrounding screen areas as unavailable reference.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/thread-composer-footer.md` in the visual design and testing sections with the current `533:1957` footer geometry, the required Run configuration conflict, and capture evidence. No shared documentation changes in this builder run.
