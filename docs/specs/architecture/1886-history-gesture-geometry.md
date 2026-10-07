# #1886 — Density and viewport independent history gestures

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenHistoryTest.kt`: the four named regressions, `setPrefetchList`, `rows` and the screen gesture helpers.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryRows.kt`: `isNearOldestEnd` measures remaining history in pixels against two current viewports; `olderHistoryPull` requires actual touch movement.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadMessageList` draws beneath measured chrome and preserves reverse-layout anchors.
- `docs/knowledge/features/thread-screen-oldest-end-history-demand.md`: semantics positioning and page settlement cannot request history; further movement during a held drag can.
- `docs/knowledge/features/thread-screen-testing.md`: start physical gestures in the clear area between header and composer.
- `docs/knowledge/features/development-verification-gates.md` and the builder's `device-tests.md`: retain shared tests and read fresh executed/failure/skip counts on both runners.
- `docs/specs/architecture/1769-history-prefetch.md`: nearest shipped test repair and distance contract.

## Change

Repair only the four named methods and their local fixture helpers. Give full-thread tests enough uniform rows to position three current viewports away from the oldest end, and prove their range from measured visible row pitch and the oldest row's extrapolated position relative to the header. Assert inside/outside geometry before and after gestures, with margins from the two-viewport boundary. Position the isolated uniform list from measured viewport and row sizes; express touch movement as viewport fractions so runtime density scales both fixture and gesture. Prove a held drag starts outside, crosses inside while the oldest remains hidden, keeps its index/offset anchor when two rows arrive, and requests again only after further movement. Keep all demand-count assertions. Production paging, other history tests, shared fixture defaults and the retry geometry method stay unchanged. No in-flight feature branch currently overlaps this file. Expected total written work is about 250 lines, with no new exported types or production call sites and two acceptance criteria.

## Testing strategy

Reproduce the four existing tests before repair, including the managed-device density failure. Run the four repaired methods on Robolectric and the managed Pixel 2 API 33 ATD device, retaining fresh XML counts for each. Run the whole history class on Robolectric to cover shared helper integration; unrelated retry geometry is owned by #1887. Run lint, assembleDebug, androidTest Kotlin compilation, formatting and the final whole unit/shared suite plus `scripts/pre-verify.py --gradle`. This is test-only work with no UI change, operator-facing flow, live scenario or documentation requirement.

## Revisions

### 2026-10-07 — Use measured row jumps for full-thread positioning

The first repaired managed-device run passed three methods, but the two-viewport method's second setup measured only 0.55 viewports after requesting a three-viewport animated semantics scroll. Keep the range assertion and replace that setup animation with an immediate `ScrollToIndex` target calculated from measured row pitch, viewport and header geometry. Assert the fixture contains the target row and wait for each jump to settle. Both requested ranges remain independently measured before the actual gesture; production paging stays unchanged.
