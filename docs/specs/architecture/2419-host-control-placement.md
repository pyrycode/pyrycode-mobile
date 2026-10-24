# Host control placement (#2419)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt`: `TreeHostRow`, `FoldableTreeRow` and `TreeRowControl` define the trailing cluster and independent action regions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRowsTest.kt`: existing native paint, action and fold regressions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt`: `assertTrailingControlsFormOneColumn` protects the connected pen column; existing host-specific action tests remain applicable.
- `app/src/androidTest/java/de/pyryco/mobile/design/ListDesignCaptureTest.kt`: both viewport walks capture connected and three terminal host states.
- `docs/knowledge/features/channel-list-screen.md`, `channel-list-screen-tree-and-controls.md`, and `development-verification-api35-selection.md`: persistent non-owner Edit host entry, exact capture selection, and counted real-bar evidence.
- `docs/specs/architecture/2418-host-status-glyphs.md`: accepted artwork remains unchanged; its comparisons deliberately did not accept placement.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=672-3493 and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Fresh context and screenshots show the disconnected/re-pair/update cluster centered 48/24 dp inside the toolbar rule's right edge, vertically centered in a 28 dp band. The connected pen is centered 10 dp inside the edge; its existing 12 dp center is within the allowed 2 dp tolerance. Keep the existing Pair/Update/pen vectors, primary tint, error host accent and titleSmall/bodySmall typography. The current connected design includes the permanent pen preserved by #1525.

## Change

The existing two-control cluster has centers 36/12 dp inside the edge. Add a 12 dp trailing spacer only when a relay-disconnected or update-required host draws its status control; leave connected placement and the separate thread-missing treatment unchanged. No shared row constant, caption layout, glyph, signature, action or accessible name changes. The 27/28 px caption rhythm remains untouched. One behavior, forecast approximately 180 written lines including plan, regression and evidence receipt; zero exported types, caller migrations or new error branches. Remote #2175 overlaps the same file with thread-missing behavior already present here; preserve it with a local placement edit.

## Testing strategy

First add a regression cycling the four states and both content widths with fixed density, asserting centers against the row edge and band center within 2 dp. Exercise physical pointer taps at each control's edges and the adjacent fold edge, proving that status, edit and fold callbacks stay independent and host-specific. Observe the old cluster fail, then run `ConversationTreeRowsTest`, `TreeHostRowThreadMissingTest` and affected `ChannelListScreenTest` geometry/action methods. There is no new lifecycle or identity state.

Run both existing `ListDesignCaptureTest` viewport methods before and after the repair using full API35 with real bars. These tests require hardware framebuffer and system-bar evidence. Retain all four states, unscaled before/after comparisons, raw counted XML, source/APK/runtime identities, screenshots and sidecars in durable private evidence, with a content-free receipt under the existing androidTest design assets. Preserve failed and zero-execution history. Compare only the requested host controls against the two approved frames; other visual mismatches stay outside this ticket.

Run lint, assembleDebug, shared-test instrumentation compilation and Spotless. Commit and push before the final main merge checks; run final assembleDebug and `scripts/pre-verify.py --gradle`. Complete configured gates and family #1650 acceptance remain dispatcher/verifier-owned. Linux is this ticket's verification scope.
