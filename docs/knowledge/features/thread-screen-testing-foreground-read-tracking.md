# Thread screen — foreground read tracking tests

Part of [Thread screen — testing](thread-screen-testing.md).

## Foreground read tracking (#1912, #1953)

The supplied authoritative probes (#2085) exercise the production presentation,
viewport and ViewModel/facade retry path without activating authoritative sync.
`AuthoritativeReadSightTest` distinguishes the store's certified checkpoint from
partial batches/highest revisions, bounds sight by available shown metadata, and
rejects hidden/state-only, duplicate, foreign and retired evidence.
`unseenIntermediateAppendMustWithholdRead` and
`offscreenIntermediateAppendRequiresItsOwnSightBeforeNewestCheckpoint` require the
older unread append's exact revision even when the newest row is visible. Long-thread
fixtures must explicitly mark filler rows as previously read; treating all held rows
as seen would conceal this defect.

`equalContentNonMessageRowsMustNotShareSight`, `equalBannersMustNotShareNewestSight`
and `productionViewModelRequiresCarriedScopedRevisionSight` pin scoped key/revision
identity through candidates, callbacks, ledger and dispatch. Equal displayed banners,
refusals, dividers and compactions are distinct rows. The candidate probe covers delayed
reveal and retired-generation callbacks; viewport probes cover partial completion,
background/foreground, overlays, scrolling and unavailable summary. Connection-owner
replacement rejects new sight from an active old composition while qualified retries
remain host-owned. `ShownVersionReadMarksTest`, `ConversationReadMarksTest` and
`ReadCheckpointRetriesTest` retain omitted/zero/invalid summary, peer confirmation,
session replacement and failed/clamped/no-op retry coverage. The existing
`ThreadPacedReadViewportTest.readVersionInvariant_pendingAndSkippedContent_waitForFrameLifecycleAndReveal`
keeps its assertions and legacy/local fallback with an unavailable host.

The [#2085 verifier evidence](https://github.com/pyrycode/pyrycode-mobile/pull/2086#issuecomment-6111774667)
for `dc589df719bc997336c9c8b3be12e95c44a642da` records 5,440 unit tests executed and
passed, 0 failed/errors and 0 skipped. Retained fresh repair XML under
`/tmp/verifier-2086/rereview/` confirms `AuthoritativeReadSightTest` (10),
`AuthoritativeReadViewportTest` (5), `AuthoritativeReadCandidateTest` (1) and the
unchanged paced legacy method (1): 17 executed/passed, 0 failed/skipped. Both named
intermediate-append methods, equal-content identity methods and production ViewModel
proof-validation method are present and passed. The builder's retained focused XML
under `/tmp/builder-2085/focused-results/` records 177 executed/passed, 0 failed/skipped,
including summary, attention, notification, read-mark and retry suites.

The dispatcher UI gate records 258 executed/passed, 0 failed and 1 skipped
(`RenameDialogCaptureTest.renameAtFigmaViewport`, providing no capture evidence).
Scripted-all records 22 executed/passed, 0 failed/skipped and zero real Claude turns.
These counts do not establish authoritative daemon activation or live acceptance.
\#1988 owns `InteractiveStreamE2ETest.interactiveTurn_attentionDot_followsARealTurn`
and combined partial-reconnect/unseen-content acceptance; the supplied seam remains
dormant. No new ladder scenario or separate focused live run was performed here.

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
between those content edges and whole-row padding/spacing. In each metadata state,
compare the message edge with that state's measured bubble bottom minus
`BubbleVerticalPadding`, within one pixel, and assert it is inside the bubble.
`MessageContainer` has a minimum surface height (`MessageActionPairHeight`), so
showing metadata need not enlarge the surface or move the absolute edge downward.
Keep the tool's collapsed/expanded surface comparisons and expansion assertion.
A Robolectric pass alone can miss the invalid metadata-growth assumption: the
original #1964 focused Robolectric run passed while managed Android failed it.
Shared tests need focused managed Android execution because the normal UI gate
selects device-only tests; see [verification gates](development-verification-gates.md#where-a-screen-test-goes).
Read qualification remains
the [foreground presentation contract](thread-screen.md#what-it-does).

The [#1964 focused evidence](https://github.com/pyrycode/pyrycode-mobile/pull/1974#issuecomment-6060893133)
records fresh Robolectric and managed Android runs of `ThreadReadContentEdgeTest`,
each with 2 executed, 2 passed, 0 failed and 0 skipped. Both methods passed in each:
`messageEdge_isInsideBubblePaddingWithAndWithoutMetadata` and
`toolEdge_usesToolSurfaceIncludingExpandedContent`. The preserved Android XML is
timestamped `2026-10-08T12:56:24`; the verifier also confirmed both methods in fresh
dispatcher Robolectric XML at `2026-10-08T13:07:42.017Z`. These focused runs establish
the repair's coverage; the later full Android main sweep remains dispatcher-owned.

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
