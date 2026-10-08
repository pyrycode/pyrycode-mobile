# Thread screen — foreground read tracking tests

Part of [Thread screen — testing](thread-screen-testing.md).

## Foreground read tracking (#1912, #1953)

`ThreadReadViewportTest.scrolling_doesNotRecomposeProductionListHost` uses 60 fixed
rows and `LocalThreadListCompositionObserver`, invoked by a `SideEffect` in the
production scope constructing the list and read observer. Counting an outer test
wrapper misses invalidations within that scope. Wait for the initial checkpoint,
layout and reveal to settle before capturing the count. Assert nonzero consumed
scroll and a changed newest-row offset while it remains visible, then scroll into
older rows and assert the newest is offscreen. Both phases must leave the host count
unchanged. This proves composition isolation with fixed inputs, not a runtime profile
or physical-phone smoothness.

Keep the existing lifecycle, offscreen-update, rename-overlay and tall-row assertions.
`foregroundCheckpoint_replacementToolVersionGetsFreshMeasurementWithoutGeometryChange`
uses the production screen to prove an equal-sized collapsed tool replacement gets
fresh measurement. Settle UI-state replacement and dialog closure with `waitForIdle()`
before polling checkpoints; otherwise the observer may still see the old composition.
In direct `ThreadReadCandidateTest` probes, externally mutated snapshot readiness
fields need `Snapshot.sendApplyNotifications()` because composition no longer reads
those fields. Retired layout/edge/reveal callbacks must not qualify a replacement or
a removed/reintroduced equal version.

`ThreadReadContentEdgeTest` checks the message's inner-column edge with and without
metadata, and the tool surface edge collapsed and expanded. Preserve the distinction
between those content edges and whole-row padding/spacing. Read qualification remains
the [foreground presentation contract](thread-screen.md#what-it-does).

The [#1953 verifier evidence](https://github.com/pyrycode/pyrycode-mobile/pull/1959#issuecomment-6056075065)
and dispatcher's per-method gate report for `26a910dc6c65` confirm the UI gate ran
211 tests: 211 passed, 0 failed, 1 skipped. `ThreadReadViewportDeviceTest` ran six,
all passed, with 0 failed/skipped:

- `scrolling_doesNotRecomposeProductionListHost`
- `foregroundCheckpoint_requiresResumedDestinationAndDoesNotRepeat`
- `foregroundCheckpoint_excludesScrolledAwayRowUpdates`
- `foregroundCheckpoint_excludesReplyArrivingBehindRenameDialog`
- `foregroundCheckpoint_replacementToolVersionGetsFreshMeasurementWithoutGeometryChange`
- `viewportEdgeExcludesComposerAndImeAndAllowsTallRowTrailingEdge`

The scripted-all gate ran 22 tests, all passed, 0 failed/skipped. Its `ping` report
ran two, both passed, 0 failed/skipped:
`DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread`
retains the daemon-confirmed foreground read checkpoint, alongside
`interactiveTurn_seededChannel_durableGapCatchUp`. The
[dispatcher-owned live run](https://github.com/pyrycode/pyrycode-mobile/issues/1953#issuecomment-6056382576)
(`2026-10-08T08-40-06-488Z`, branch `26a910dc6c65` merged with main `5341e4f55c24`)
ran 65 tests, all passed, 0 failed/skipped; its per-method report confirms
`InteractiveStreamE2ETest.interactiveTurn_pingPrompt_streamsPingReplyIntoThread`
passed in the full suite. This was not a separate focused live run. No new ladder
scenario was introduced; see [phone read-mark proof](../../e2e-interactive-stream.md#phone-read-mark-proof-1912).
