# 1257 — Shared options overlay Figma match

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/OptionsOverlay.kt` → `OptionsOverlay`, `AnchoredAbove`, `OptionsColumn`: shared presentation, focus-safe layer, anchor placement and row semantics.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/OptionsOverlayColoursTest.kt` → `staticDarkMenuUsesBoundFixedAndOnPrimaryRoles`: pixel proof for selected and idle fills.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooterTest.kt` → `actionsButton_opensTheThreeRowsInOrder`: action rows, dispatch, disabled state and dismissal coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/SlashCommandTypeAheadScreenTest.kt` → `SlashCommandTypeAheadScreenTest`: slash detail, completion and dismissal coverage.
- `docs/knowledge/features/options-overlay.md` → placement and row-mode history, including intentional 10dp row padding and 3dp shadow deviations.
- `docs/knowledge/features/development-verification.md` → shared screen test and device capture conventions.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1958

Inspected 2026-09-28: overlay `533:1958`, idle `121:3880`, selected `121:3891`, input area `533:1957`, thread viewport `16:8`. The overlay is an 81×144 column: 6dp radius, 2dp vertical outer inset and five 28dp rows with 12dp horizontal/6dp vertical padding. `M3/body/small` is Roboto 12sp/16sp, regular, 0.4sp tracking. In static dark, `Schemes/On Primary` (#003355) is the surface and selected fill, `Schemes/On Primary Fixed` (#001d34) the idle fill, and `Schemes/Primary` (#9dcbfc) the text. The component contains no shadow layer. The direct overlay screenshot endpoint currently returns 1×1; the individual state renders and metadata are available. Neither a disabled option nor a slash detail row is represented in these nodes.

## Context

`OptionsOverlay` deliberately enlarged rows and added elevation when first built. The current design calls for the compact treatment across both Actions and slash suggestions. Both consumers already provide the data and interaction contracts.

## Design

Change only `OptionsOverlay` presentation: row vertical padding to 6dp, shadow elevation to zero, and surface/idle/selected fills through the design's `onPrimary`/`onPrimaryFixed` roles in every theme path. Keep the 6dp shape, 2dp outer inset, bodySmall typography, 12dp row inset, focus-safe scrim, anchor text alignment and 4dp gap. Preserve disabled semantics and the existing secondary detail limit; Figma has no states for those, so their appearance remains the nearest existing semantic treatment and is not claimed as an exact match. Keep width and scroll limits so long labels, enlarged text and compact viewports remain reachable.

## State + concurrency model

No new state or jobs. The existing `rememberScrollState`, `rememberUpdatedState` dismissal callback, and composition-bound gesture handling remain.

## Error handling

This is local presentation code with no I/O. Existing inert text and disabled selection guards stay in place.

## Testing strategy

- Add a shared Compose geometry test that first fails on 36dp rows and 3dp shadow, then checks the live 28dp row treatment, anchor gap, and compact/enlarged-text reachability. Keep the existing Actions and slash interaction tests as regression proof.
- Add a focused API 33 capture test for the open menu at 412×892 and a compact/enlarged-text case; run it on the managed emulator and keep actual screenshots with same-viewport Figma renders and a labelled comparison or difference artifact under the test source tree. The direct overlay render's 1×1 response and absent slash-detail/disabled design states must be disclosed beside that evidence.
- Run focused shared tests, focused device capture, `lint`, `assembleDebug`, `compileDebugAndroidTestKotlin`, and `spotlessApply`. The dispatcher owns the complete gates. No new real-Claude scenario: menu presentation changes no live phone-to-daemon path.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/options-overlay.md` sections “Colour deviation from the design”, “Row height deviation”, “Testing” and “Previews” for the new Figma match and visual evidence; also note the unavailable slash-detail/disabled Figma states.

## Open questions

- Does the direct `533:1958` screenshot become available during capture? If it remains 1×1, compare against its component states and mark the full-menu pixel comparison unverified.

## Revisions

- 2026-09-28: `533:1958` still renders as 1×1. Device captures and the available idle/selected state renders are attached under `app/src/androidTest/assets/options-1257/`; the full-menu pixel match remains unverified, and `capture-context.txt` identifies the missing disabled and slash-detail states. Compose measures the bodySmall text line at 14dp on the test device, so `OptionsColumn` enforces a 28dp minimum while allowing rows to grow for enlarged text.
- 2026-09-28: Verifier found that applying `onPrimaryFixed` to idle rows in every theme made light labels and slash detail fail contrast. `OptionsColumn` now uses the Figma roles only when `LocalStaticDarkPalette` is true; static light and dynamic themes keep their prior `surfaceContainerLowest` and transparent idle rows, with `primaryContainer` for selection. The 28dp row geometry and zero shadow remain shared. A shared screen test checks the rendered idle fill and label/detail contrast in static light and dynamic light. The compact 280×400, 1.6× text emulator capture was added after the first review.
