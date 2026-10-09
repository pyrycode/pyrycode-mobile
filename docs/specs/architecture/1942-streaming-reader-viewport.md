# Hold a streaming reply still for a reader (#1942)

## Files read

- `ThreadScreen.kt`: `ThreadMessageList`, `ordinaryMessageRestAdjustment`, and `LocalThreadListCompositionObserver`; viewport reservations and production test seam.
- `ThreadListFollow.kt`: `followStep`, `FollowNewestEnd`, and `pinToNewest`; existing follow, accepted-send and prompt-departure rules.
- `ThreadRow.kt`: `listKey`; #1940 / PR #1965 is merged and all anchors retain its displayed-identity keys.
- `MessageBubble.kt`: `AssistantMessage` and progressive reveal; preserve streaming and settled renderers.
- `ThreadScreenFollowTest.kt` and `ThreadReadViewportDeviceTest.kt`: real-screen regressions and inherited Android-visible probes.
- `docs/knowledge/features/thread-screen.md` and `thread-screen-how-it-works-list-and-status-row.md`: reverse-layout bottom anchoring, short-thread top alignment and measured reservations.
- `docs/knowledge/features/development-verification-gates.md`: shared tests, physical-pixel bounds and device probe selection.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Inspected design context and screenshot: dark thread with translucent header/composer, alternating message bubbles, side actions and session delimiters. Preserve existing Material typography, colour roles, bubble shapes, 20dp gutters and all spacing values; only viewport behavior changes.

## Context

A reversed lazy list anchors the bottom of its first visible row. A growing streaming reply therefore moves its top and older rows despite following being disabled. Settled markdown can grow or shrink the same row. Existing rest-spacing compensation also skips index-zero history readers and active input. One deliverable is stable reader geometry through both sources of displacement.

## Design

Keep the reverse list and existing renderers. Share composition-local viewport bookkeeping between following and list geometry compensation in `ThreadListFollow.kt`, with one screen consumer. Track only the previous visible keyed row geometry and reservation, never message content. On each layout update, compensate the anchored row's size delta and newest-end padding delta when the reader is not following. Use raw relative scroll displacement to preserve an active input mutation and its velocity, without cancelling or launching a competing scroll. Correct geometry before the rendered frame is drawn; frame-level regressions are the acceptance gate for this timing.

Following must distinguish geometry compensation from reader movement: record applied compensation and subtract it from the offset delta used by `followStep`. Keep newest-end tolerance, accepted-send resumption, prompt-size masking and #1449 prompt departure intact. Remove the screen's separate idle-only rest adjustment so the same geometry change cannot be applied twice. Use #1940 keys without index-based identity.

No in-flight numeric feature branch overlaps either production file after fetching origin. Forecast: approximately 800–1100 written lines including plan, tests and device probes; at most three new internal types/composables, two production consumers, five acceptance criteria and fewer than ten decision branches. No public API migration or new dependency.

## State and concurrency model

Bookkeeping belongs to the remembered list composition; no ViewModel or repository changes. Existing composition-owned effects collect layout snapshots and accepted sends and cancel on screen exit. Relative geometry correction must bypass scroll-mutation arbitration so resting touch, drag and fling remain active. Read changing layout only in layout callbacks/collectors, never in list-host composition. No background jobs or new dispatchers.

## State transitions and identity reuse

| Event | Required regression |
| --- | --- |
| Appended delta and every progressive reveal frame, tall reply at index zero | `streamingReader_holdsTopAndOlderRowsEveryFrame` |
| Streaming switches to taller or shorter settled markdown | `settledMarkdown_holdsTopForGrowthAndShrink` |
| Finger held without movement during growth | `restingTouch_holdsReaderEveryFrame` |
| Drag or fling while geometry changes | `movingReader_preservesConsumedMovement` |
| Ordinary spacing changes 4dp → 16dp → 4dp at index zero or in history | `endSpacing_preservesReaderInBothDirections` |
| Reader moves to another keyed anchor | motion regression; refresh geometry rather than restore stale position |
| Following growth, accepted send, prompt departure, save/restore | existing `ThreadListFollowTest` and `ThreadScreenFollowTest` |
| Thread exits/reopens or list identity changes | remembered geometry resets with list; existing restoration coverage |

## Error handling

No I/O or new error surface. A missing/retired anchor establishes a fresh baseline; never restore a removed key. Compensation clamped by a real list end records only consumed displacement. Emit content-free debug lifecycle logging for correction (pixel delta and static event), without keys or message text.

## Testing strategy

Write the real-screen regressions first and confirm they fail on the existing implementation. Use an overflowing thread with a pre-existing reply taller than the viewport, then move to its top. Capture layout at rendered frames, advance progressive reveal explicitly, and exercise both markdown height directions and both end-spacing directions. Motion checks account for consumed movement and prove touch/fling continuity. Shared tests run under AndroidJUnit4 on JVM; Android-visible overrides select the same named methods through the UI gate, mirroring `ThreadReadViewportDeviceTest`. The probes exist because acceptance explicitly requires platform rendered-frame and gesture evidence in the routine gate.

Run focused new regressions and existing follow/layout tests, lint, assembleDebug, androidTest compilation, formatting and final pre-verify. Run the affected device probe class during development; dispatcher owns the full UI and fresh full live gate. Preserve `InteractiveStreamE2ETest#interactiveTurn_pingPrompt_streamsPingReplyIntoThread` and list it in the PR's Live tests section; no claim of live success until fresh counted evidence exists.

## Open Questions

- Confirm synchronous relative correction settles before drawing on JVM and Android through rendered-frame assertions. If timing requires a different layout hook, record the evidence and revised contract here before handoff.
