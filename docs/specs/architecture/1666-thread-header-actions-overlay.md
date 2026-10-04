# Thread header Actions overlay (#1666)

## Files read

- `ThreadOverflowMenu.kt`: `ThreadOverflowMenu` owns the ordered, client-owned rows, mutation/promotion/memory gates and dismiss-before-action routing.
- `ThreadTopAppBar.kt`: `ThreadTopAppBar` owns the accessible three-dot control; its only production caller is `ThreadScreen`.
- `ThreadScreen.kt`: `openControl`, `footerAnchors` and `layerOrigin` demonstrate same-window overlay hosting and live window-to-layer conversion.
- `OptionsOverlay.kt`: `OptionsOverlay` already supplies Actions semantics, focus-preserving scrim, Back handling and Below placement.
- `docs/knowledge/features/thread-screen.md`, `thread-overflow-menu.md`, `options-overlay.md`: current memory report must be read on every recomposition; same-window overlays consume outside taps without taking composer focus.
- `ThreadOverflowMenuTest`, `ThreadScreenOverflowTest`, `ThreadFrameTest`, `ThreadComposerFooterTest`: existing routing, gates and frame/overlay regression coverage.
- `ThreadDesignCaptureTest`: retained overflow-menu and compact-overflow-menu device captures.

## Design source

Style: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1958

Read through Figma MCP. The direct render is 1 × 1, as recorded in the ticket. Reuse the existing composer Actions component: bodySmall text, 12/6 dp row insets, 2 dp column inset, 6 dp corners and the existing theme roles. Actions mode has neither radio selection nor subset caption. The old shadowed Material menu in https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=675-5883 is deliberately superseded by Juhana's #1666 decision; the existing composer component is the appearance reference.

## Context

The header and composer dropdowns should share their style. No transport or product action contract changes. Overlaps #1642, #1747 and #1753 touch separate screen blocks; keep this change local.

## Design

Keep the ordered action construction and routing in `ThreadOverflowMenu`, replacing DropdownMenu with OptionsOverlay in Actions mode and Below placement. An optional anchor after modifier keeps isolated test hosts source-compatible; production supplies live bounds. `ThreadTopAppBar` becomes chrome only and reports the overflow IconButton bounds through a callback. `ThreadScreen` hosts the menu beside its footer overlay over the full Scaffold, translating window bounds by layerOrigin. Opening the header clears the footer control; opening a footer clears the header. Slash suggestions are suppressed while either menu is open. Preserve cd_more_actions, labels, events, background panel callback and memory URL.

## State and concurrency model

Header visibility stays UI-local in the screen. Rows are derived from current state on every recomposition, including conversation changes. Bounds update through onGloballyPositioned; no new jobs, flows or dispatcher. Same-window overlay leaves composer focus and keyboard ownership alone. Selection calls dismissal before its existing action; the shared scrim and BackHandler dismiss without routing.

## Error handling

No new failure modes; existing action handlers and memory URI behavior remain unchanged.

## Testing strategy

Add a red presentation assertion before implementation. Extend shared tests for button roles without selection, complete order/gating, live anchor movement and gap, outside pointer dismissal with no tap-through, Back, focused composer preservation and menu exclusivity. Run the three affected classes plus footer, slash and shared overlay regressions. Retain both existing ThreadDesignCaptureTest captures; run the affected device capture methods because pixels and real IME cannot be proved by Robolectric. Compile shared/device tests, lint, assemble and forced spotless check. Dispatcher owns fresh full live suite: all methods, explicitly including InteractiveStreamE2ETest.interactiveTurn_newSession_rendersSessionBoundaryDelimiter; executed/failed/skipped counts and named pass remain pending.

## Open Questions

None.

## Documentation handoff

Pending documentation stage:
- `app/src/androidTest/assets/design-1220/README.md`, Overflow menu row: replace audited 675:5883 match with 533:1958 Actions style by Juhana's decision on #1666; retain both captures and #1631 approved addition.
- `docs/knowledge/features/thread-overflow-menu.md` and linked wiring topic; `thread-screen.md` and overlay topic; `options-overlay.md`: update hosting and presentation consistently.
- Record dispatcher-produced fresh full live evidence, counts and named #541 method pass; documentation does not produce evidence.

Sizing: approximately 600 written lines, no new exported types, one production consumer for each changed host, five acceptance criteria, no new reject branches; below all ticket limits.
