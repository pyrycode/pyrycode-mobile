# Hold a streaming reply still for a reader (#1942)

## Files read

- `ThreadScreen.kt`: `ThreadMessageList`, `ordinaryMessageRestAdjustment`, and `LocalThreadListCompositionObserver`; viewport reservations and production test seam.
- `ThreadListFollow.kt`: `followStep`, `FollowNewestEnd`, and `pinToNewest`; existing follow, accepted-send and prompt-departure rules.
- `ThreadRow.kt`: `listKey`; #1940 / PR #1965 is merged and all anchors retain its displayed-identity keys.
- `MessageBubble.kt`: `AssistantMessage` and progressive reveal; preserve streaming and settled renderers.
- `ComposerFileTileTintTest.kt`: explicit native `View.draw(Canvas)` evidence under JVM and Android.
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
| Identical complete source changes height between renderers, independently of progressive reveal | `settlementFixtures_changeHeightWithoutProgressiveReveal` verifies unfinished-link growth and fenced-block shrink with the caret on and off |
| Streaming switches to taller or shorter settled markdown after complete reveal | `settledMarkdown_holdsTopForGrowthAndShrink` |
| Finger held without movement during reveal and verified settlement growth/shrink | `restingTouch_holdsReaderEveryFrame` |
| Drag or fling while geometry changes, including verified settlement growth/shrink | `movingReader_preservesConsumedMovement` |
| Ordinary spacing changes 4dp → 16dp → 4dp at index zero or in history | `endSpacing_preservesReaderInBothDirections` |
| Reader moves to another keyed anchor | `endSpacing_preservesReaderInBothDirections` moves from the reply to a history key; refresh rather than restore a stale position |
| Following growth, accepted send, prompt departure, save/restore | existing `ThreadListFollowTest` and `ThreadScreenFollowTest` |
| Thread exits/reopens or list identity changes | remembered geometry resets with list; existing restoration coverage |

## Error handling

No I/O or new error surface. A missing/retired anchor establishes a fresh baseline; never restore a removed key. Compensation clamped by a real list end records only consumed displacement. Emit content-free debug lifecycle logging for correction (pixel delta and static event), without keys or message text.

## Testing strategy

Write the real-screen regressions first and confirm they fail on the existing implementation. Use an overflowing thread with a pre-existing reply taller than the viewport, then move to its top. Capture layout at rendered frames, advance progressive reveal explicitly, and exercise both markdown height directions and both end-spacing directions. Motion checks account for consumed movement and prove touch/fling continuity. Shared tests run under AndroidJUnit4 on JVM; Android-visible overrides select the same named methods through the UI gate, mirroring `ThreadReadViewportDeviceTest`. The probes exist because acceptance explicitly requires platform rendered-frame and gesture evidence in the routine gate.

Run focused new regressions and existing follow/layout tests, lint, assembleDebug, androidTest compilation, formatting and final pre-verify. Run the affected device probe class during development; dispatcher owns the full UI and fresh full live gate. Preserve `InteractiveStreamE2ETest#interactiveTurn_pingPrompt_streamsPingReplyIntoThread` and request `all` in the PR's Live tests section because acceptance requires a fresh full live gate; no claim of live success until fresh counted evidence exists.

## Open Questions

- Resolved after verifier rework: the six named geometry/fixture methods each executed once with zero failures and zero skips on JVM and managed Android 13. Complete identical-source settlement genuinely grows and shrinks, including resting touch and active drag/fling. Existing focused follow and viewport coverage also passed (53 JVM methods total).

## Documentation handoff

- Pending for the documentation stage: `docs/knowledge/features/thread-screen-how-it-works-list-and-status-row.md`, list reservations and following: keyed row-size and newest-end padding compensation, consumed compensation excluded from reader movement, and preservation of active scroll mutation.
- Pending for the documentation stage: `docs/knowledge/features/thread-screen-testing.md`, viewport regression coverage: rendered-frame shared cases and Android-visible probe selection, including independently verified settlement fixtures and fresh counted geometry evidence.
- Pending for the documentation stage: record actual fresh full UI/live results in the thread testing overview, including `InteractiveStreamE2ETest.interactiveTurn_pingPrompt_streamsPingReplyIntoThread`; these dispatcher-owned checks remain pending at builder handoff.

## Revisions

- 2026-10-09: Capture the anchor's row-height delta in `ThreadRowContent` measurement before the lazy measure publishes a possibly different anchor after a shrink; apply size and reservation deltas in `ThreadMessageList`'s placement callback. Read the old lazy geometry without snapshot observation to avoid making item measurement depend on its own measure result. `dispatchRawDelta` preserves the active scroll mutation; `ListFrame.compensatedScroll` distinguishes that displacement from reader input. The five initial real-screen negative controls executed and failed with zero skips (40px reveal drift, 60px attachment drift and 3032px markdown drift).
- 2026-10-09: Native JVM drawing follows `ComposerFileTileTintTest`'s explicit `View.draw(Canvas)` pattern. A root draw modifier alone records no JVM frames, and `captureToImage` times out; explicit draws observe the real composed bubble coordinates at each advanced frame. Paragraph breaks provide a genuine settled-markdown shrink; heading syntax provides growth of the same text at settlement. Progressive reveal also ends into settled markdown while the reader's finger remains down.
- 2026-10-09: Motion assertions now take the fling baseline after finger release, require a live fling at each geometry update, and finish into settled markdown during that fling. A final burst supplies fresh velocity samples after the frame-by-frame drag; without it the gesture can produce no fling. A resting finger does not set `isScrollInProgress`, so its guarantee is the recorded geometry while the injected pointer remains down, rather than that flag.
- 2026-10-09, verifier finding 1: Withdraw the earlier heading-growth and paragraph-shrink fixture claims. Both heading renderers share styles, and a five-frame baseline can still hold unrevealed text. Independently measure direct streaming/settled renderers with identical complete input and both caret states: an unfinished link hides its target while streaming and grows when settled literally; a fenced block reserves a caret line that disappears on settlement. Before each real-screen settlement baseline, advance every rendered frame beyond the 495ms catch-up budget and keep the source unchanged at completion. Assert actual height growth/shrink as well as top/older-row stability, including resting touch and active drag/fling.

- 2026-10-09, Android rework probe: Select the reply, older row and history anchor by fixture identity and require the older/history bubble to be displayed. A prefetched lazy item retained an overlapping y coordinate and was incorrectly selected by the largest y below the reply; it then appeared to drift on the first consumed drag before any content update. The focused Android motion method passes when observing the actual displayed row. Preserve the frame samples, consumed-movement normalization and 1-physical-pixel bound.

- 2026-10-09, verifier finding 2: Preserve the fresh six-method Android XML at `/tmp/builder-1942/rework-496b797884cb/geometry-device.xml` before running the scripted scenario. Verify the exact geometry method names and executed/failed/skipped counts (6/0/0); the previous `/tmp/builder-1942/device-probes.xml` contains only a scripted-stream method and is withdrawn as geometry evidence. The PR references the preserved geometry file separately from fresh scripted evidence.
