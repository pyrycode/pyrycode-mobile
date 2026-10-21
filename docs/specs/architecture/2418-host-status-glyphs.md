# Host status glyphs (#2418)

## Files read

- `ConversationTreeRows.kt`: `TreeHostRow` selects the status painter; `TreeRowControl` centers separately sized artwork in its existing 24 dp slot and supplies the primary tint.
- `ConversationTreeRowsTest.kt`: existing host action/fold/name checks and native-graphics pixel sampling provide the focused regression seam.
- `ListDesignCaptureTest.kt`: `listFramesAt412By892` and `listFramesAt320By700LargeText` already capture all three host states using the assembled app.
- `app/src/main/res/drawable/ic_tree_edit.xml`: shared, theme-tinted vector asset convention.
- `docs/knowledge/features/channel-list-screen.md` and `channel-list-screen-tree-and-controls.md`: host-qualified actions and bounded accessible names must stay intact.
- `docs/knowledge/features/development-verification-compose-evidence.md` and `development-verification-emulator-evidence.md`: native bitmap sampling, real bars, exact viewport metadata and durable counted evidence.
- Parent #1650's archived original and sibling #2419: this child owns glyphs only; placement, connected-pen comparison and final family acceptance stay with their owners. Historical failures and reliability holds remain in force.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=672-3493

The inspected frame context and screenshot show Pair (`486:995`, instances `672:4055` and `672:4086`) as 9 × 10.8 dp plug artwork centered in a 12 dp design wrapper. Update (`581:1606`, instance `672:4117`) uses 12 × 12 dp artwork. Convert the supplied SVGs directly to shared Android vector drawables, retaining their paths and viewport clipping; `TreeRowControl` supplies `MaterialTheme.colorScheme.primary`. Keep the #1525 permanent host pen exception and current cluster placement.

## Change

Replace the Power and Download painters in `TreeHostRow` with shared Pair and Update resources, specifying Pair's dimensions explicitly. No signature, state, callback, caption, fold or control-slot change is needed. The remote overlap with #2175 adds the thread-missing treatment, which is already present on this checkout; this additive painter change preserves that branch's semantics. One observable behavior (status-to-approved-artwork mapping), approximately 180 written lines including plan and regression, three production files, zero new exported types, zero consumer migrations and zero new error branches: within all sizing ceilings.

## Testing strategy

Add a native-graphics pixel regression beside the existing `ConversationTreeRowsTest` host checks, before changing the painters. Render disconnected, pairing-rejected and update-required rows at a fixed density; measure primary-tinted artwork separately from the control bounds, check approved dimensions and centering, and distinguish plug pins/body and update arrow/tray. Observe the old substitutes fail. Run the entire affected class and `TreeHostRowThreadMissingTest`, preserving all existing action and accessibility assertions. No new lifecycle or identity state is introduced.

Run the two existing `ListDesignCaptureTest` viewport methods on full `pixel8Api35` with real bars required (device-only because hardware framebuffer/system bars are the evidence). Retain source/APK identities, raw counted XML, screenshots and viewport sidecars; compare 1:1 glyph crops against Figma without treating the separately owned cluster offset as this child's repair. Run focused lint, APK assembly, shared-test instrumentation compilation, Spotless, then final assembly and `scripts/pre-verify.py --gradle` after the last main merge. Complete configured acceptance remains dispatcher-owned; unavailable or zero-execution evidence stays pending.
