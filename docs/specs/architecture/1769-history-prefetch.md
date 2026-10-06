# #1769 — Prefetch older thread history during reader movement

## Files read

- `ThreadHistoryRows.kt`: `OlderHistoryGesture`, `olderHistoryPull`, `isNearOldestEnd` own touch provenance and the reversed-list distance predicate.
- `ThreadScreen.kt`: the message-region branches, prompt row indices, gap routing and stable-key list preserve empty pulls and reader anchors.
- `ThreadViewModel.kt`: `onDemandOlderHistory`, `fetchHistoryPage`, `launchNewestPageSideAsk`, `onDemandHistoryGap`, `claimHistorySlot` share the outstanding request slot.
- `ThreadHistoryDemand.kt`: `canAsk`, `settled`, `cursorRefused` remain authoritative for single flight and terminal stops.
- `ThreadScreenHistoryTest.kt`, `ThreadOldestEndBandTest.kt`, `ThreadViewModelTest.kt`: existing demand, position, retry and reconnect coverage.
- `docs/knowledge/INDEX.md`, `features/thread-screen.md`, `features/thread-screen-oldest-end-history-demand.md`: semantics scroll can emit UserInput without a pointer; gap pulls must retain their target and one-page-per-touch policy.
- `docs/knowledge/features/development-verification-gates.md`: shared screen coverage runs under Robolectric; counts must come from fresh XML.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read design context and screenshot of the thread frame. It has a reversed chronological message area under the title overlay and above the composer, with blue Material role-token bubbles and body typography. This change retains that layout and the shipped history-tail spinner/label and error surfaces; the frame has no separate history-loading visual.

## Context

The one-shot 200dp gesture-start predicate misses a drag or fling entering the loaded end later. Prefetch approximately two current list viewport heights ahead, requesting 200 durable entries, to give responses time to arrive. It cannot eliminate stalls when the reader outruns the response. No protocol or history merge contract changes, and no decision record is needed.

Non-blocking overlaps: #1827 changes row rendering; the older `1283-notice-placement` branch changes connection chrome and history-row comments. Changes here stay local to demand wiring. Former blocker #1832 is merged and its gap targeting and newest-arrival scheduling remain intact.

## Design

- Keep `OlderHistoryGesture` as a non-consuming nested-scroll observer. A real pointer down starts touch provenance; pointer completion closes the drag window. Only a drag's own positive-velocity `onPreFling` transfers provenance to its fling, and `onPostFling` clears it. UserInput checks require an active touch; SideEffect checks require that attributed fling.
- Check position on each qualifying movement after the list consumes it, using consumed plus available delta so an end pull still works. No layout observer or request-completion observer initiates prefetch. A subsequent movement can ask after the single-flight slot is released during the same drag/fling.
- `isNearOldestEnd` uses the current list viewport height as its two-viewport threshold. When the oldest row is visible, its edge gives exact remaining distance including after-content padding. Otherwise extrapolate from the highest visible history row and the mean measured visible history-row height plus item spacing. Exclude prompt and tail rows. This is an estimate for unmeasured content, recalculated on movement for current geometry and mixed-height rows.
- `ThreadScreen` retains loading suppression and visible gap-marker priority. Reset a per-touch gap-demand latch on touch start so one gesture still asks at most one gap page; ordinary oldest-end prefetch can ask again after arrival when movement continues. No new UI state or exported type is needed.
- Supply a private shared thread page-size constant of 200 at all three merged request paths: backwards/retry, newest side asks, and gaps. Preserve cursor, server clamp, short/empty page and atStart behavior.
- Drop prefetch before history-position seeding finishes instead of queuing it. Opening/reconnect newest asks already wait for the seed and remain available, as does explicit Retry.

## State and concurrency model

Pointer and fling provenance live only in the remembered screen-local observer and are cleared on completion/cancellation. The modifier owns no coroutine beyond its cancellable pointer input. Distance reads current layout synchronously. Existing ViewModel-scope history jobs, StateFlows, repository availability and atomic slot claims remain authoritative; closing the ViewModel cancels them. Background socket closure remains the existing lifecycle driver's responsibility.

## Error handling

Keep `ThreadHistoryDemand` failure and termination classification unchanged. Prefetch issues no wire operation offline or at a terminal stop; existing failures, Retry, invalid-cursor recovery and content-free structured logging remain in the ViewModel. No new failure branches or dependencies.

## Testing strategy

Write failing tests first. Unit tests drive the real gesture observer through drag entry, attributed fling entry, direction and provenance rejection, pointer end/cancellation, repeated movement and idle page/geometry changes. Layout fakes pin two-viewport boundaries, hidden oldest mixed heights, spacing/padding, prompt/tail exclusion and viewport changes. ViewModel tests capture limit=200 across opening, older, Retry, reconnect side asks and gap asks; retain single-flight and termination tests.

Update and extend `ThreadScreenHistoryTest` under sharedTest for real pointer drags/flings entering from outside, suppression, controlled page arrival, unchanged visible-row bounds, and scrolling into newly loaded older rows. Run the affected full class plus history ViewModel and demand coverage, lint, assembleDebug, Android-test Kotlin compilation and forced Spotless. After the last main merge run the entire unit/shared suite and pre-verify with Gradle. The dispatcher runs `./gradlew check` and owns the fresh full live gate; record its named history-reload method as pending, never as passed. No device-only tests or stream fixtures change.

Existing rung-3 proof: `InteractiveStreamE2ETest.interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload`; no new real-Claude scenario is needed.

## Open Questions

None. Estimated total written work is 750–1000 lines across three production files, four test files and this plan; zero new exported types, fewer than ten consumers, five acceptance criteria, and no new history-walk reject branches.

## Documentation handoff

Completed: `docs/knowledge/features/thread-screen-oldest-end-history-demand.md`, “The oldest-end history demand”, covers position-based two-viewport prefetch, 200-entry thread pages and retained gap selection. The Documentation evidence section below records fresh full live-gate counts and the named attachment history-reload pass, the counted live rerun, and dispatcher `./gradlew check` counts for `ThreadScreenHistoryTest`.

## Revisions

- 2026-10-06 — Verifier finding 1 exposed a gap-to-backwards fallthrough after the selected gap's marker disappeared or left the viewport. The per-touch gap latch now gates every history demand before marker lookup, retaining the selected walk throughout the drag and its continuing fling until a fresh touch resets it. Four controlled-response `ThreadScreenHistoryTest` cases cover marker removal and movement offscreen during both a held drag and a continuing fling, and prove a fresh touch can request again. All four first failed by issuing a backwards page after the gap settled. Ordinary oldest-end prefetch still allows subsequent movement to ask after settlement when the gesture has not selected a gap. No geometry, request-slot or repository changes are needed.

## Documentation evidence (2026-10-06)

The evergreen handoff is recorded in
[The oldest-end history demand](../../knowledge/features/thread-screen-oldest-end-history-demand.md#the-oldest-end-history-demand-777):
movement-based two-current-viewport prefetch, hidden-row estimation, touch/fling provenance,
suppression, 200-entry thread pages and gap selection retained independently of marker visibility.
No live scenario or harness changed, so the existing real-Claude ladder coverage remains applicable.

The dispatcher's fresh full live-gate JUnit report, latest output
`2026-10-06T18-56-51-114Z`, records **61 executed, 59 passed, 2 failed, 0 skipped**.
It explicitly lists
`InteractiveStreamE2ETest.interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload`
as executed and passed in that full suite; this was not a separate focused run.
The [issue's live-gate evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1769#issuecomment-6023826810)
identifies branch `408305c720` merged with `origin/main` at `369823ade0` and the command
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live`.
The two failures were
`InteractiveStreamE2ETest.interactiveTurn_finishedReply_systemCopyCopiesSelectedWord` and
`InteractiveStreamE2ETest.interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`.
The [latest verifier verdict](https://github.com/pyrycode/pyrycode-mobile/pull/1839#issuecomment-6024497888)
records its independent reading of the counted same-tree rerun JUnit report:
**2 executed, 2 passed, 0 failed, 0 skipped**, with both methods executed and passed.
The dispatcher accepted the rerun and removed `needs-real-claude`. The original full run
still had two failures; the rerun does not change its counts or supply a separate focused
attachment history-reload run.

**Fresh dispatcher `./gradlew check` evidence is complete.** The same verifier verdict
records the fresh report
`app/build/test-results/testDebugUnitTest/TEST-de.pyryco.mobile.ui.conversations.thread.ThreadScreenHistoryTest.xml`,
timestamp `2026-10-06T19:38:17.021Z`: **24 executed, 24 passed, 0 failed, 0 skipped**.
All four controlled-response gap-selection regressions each executed once and passed,
without failures or skips:

- `gapPageRemovingItsMarkerKeepsTheHeldDragOnTheSelectedWalk`
- `gapPageSettlingThenDraggingPastItsMarkerKeepsTheSelectedWalk`
- `gapPageRemovingItsMarkerKeepsTheContinuingFlingOnTheSelectedWalk`
- `gapPageSettlingThenFlingingPastItsMarkerKeepsTheSelectedWalk`

The dispatcher pass record names commit `a904157f76665e54f228f3152982e0494b00f30e`
and completion at `2026-10-06T20:04:27.056Z`; `./gradlew check` exited 0.
The full unit/shared XML totals **4,506 executed and passed, 0 failed, 0 skipped**
across 374 classes. This fresh dispatcher evidence resolves the previous compiler-OOM
gap and supersedes the earlier 19-method gate and builder-reported 23-method result
for this handoff. Documentation records the dispatcher report and verifier's counted
evidence; no unit, device or live tests were run by documentation.
