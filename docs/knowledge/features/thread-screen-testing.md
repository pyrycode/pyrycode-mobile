# Thread screen — testing

Split out of [Thread screen](thread-screen.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Thread screen](thread-screen.md); see that document for what it does, its edge cases and its links.

## Foreground read tracking (#1912, #1953)

See [foreground read tracking tests](thread-screen-testing-foreground-read-tracking.md#foreground-read-tracking-1912-1953)
for production-host composition isolation, content-edge measurement and retained gate evidence.

## Frame-paced content (#1968)

`ThreadFramePacingTest` drives explicit 60 Hz and 120 Hz frame arrivals and held
worker execution. Retain final delivery without another input, both finalized
message/turn-end orders, repeated ids, boundaries, reconnect overlap and held row
identity. The saturated raw-order probe uses the production-shaped 64-slot
`DROP_OLDEST` source plus coordinator switching seam and 1,000 deltas. A larger
suspending fake or a burst below combined buffer capacity can pass while losing
real inputs. The boundary probe queues 200 snapshots before a boundary and newer
outcome; a two-snapshot probe cannot expose delayed intake bookkeeping.

`ThreadPacedReadViewportTest.readVersionInvariant_pendingAndSkippedContent_waitForFrameLifecycleAndReveal`
presents one version while newer content waits, then exercises the real screen's
lifecycle, overlay, viewport and reveal barriers. Pair it with the ViewModel
read-version and receipt-barrier probes: old displayed rows must never receive
newer receipt claims, including evidence-only updates.

`ThreadFramePacingDeviceTest.productionDestination_burstPublishesOncePerFrame_finalDelivers_andRecollectionCleansUp`
uses the production destination factory and real Android frame clock. Controlled
probes establish the rate bound; the retained live ping and scripted multi-delta
scenarios establish streaming integration. See
[counted dispatcher evidence](../../e2e-interactive-stream.md#verification-status).

## Folded-row composition reuse (#1954)

`ThreadRowContentTypeTest.foldedRows_exposeDistinctKindsAndSharedMessageTypeInActualListLayout`
(in `sharedTest`) mounts the real `ThreadScreen` and obtains its `LazyListState`
through `LocalThreadListCompositionObserver`. Scroll to each keyed entry before
reading `layoutInfo.visibleItemsInfo.contentType`: the queued row, collapsed tool-run
header, individual tool and message bubble must have four distinct, non-null types,
and two different user/assistant messages must share a type. A pure helper test alone
can pass while the screen omits the `itemsIndexed` content-type lambda.

`ThreadRowContentTypeMappingTest` (in `test`) covers every folded and delivered
sealed arm, nonempty distinct kind tokens, and stability across message identity,
text/role, tool identity, queue correlation, run expansion, agent completion and
banner level/text. Preserve both levels of coverage when adding a row kind.
These assertions establish [reuse metadata](thread-screen-how-it-works-list-and-status-row.md#folded-row-content-types-1954),
not a measured scrolling speedup or physical-device frame-time improvement.
See the [verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/1962#issuecomment-6057266904)
for acceptance evidence and its limits.

## Stable row anchoring (#1940)

The real-screen shared `ThreadRowAnchorTest` and Android-visible `ThreadRowAnchorDeviceTest`
prove singleton-to-run anchoring with queued rows below. See
[the key contract, counted red/JVM/device evidence and navigation assertion lesson](thread-screen-subagent-tool-rows.md#collapsing-runs-of-consecutive-tool-rows-1635).

## Detailed coverage

See [thread screen testing coverage](thread-screen-testing-coverage.md#testing)
for screen/ViewModel regressions, device evidence, session-error races, short-stream
anchoring and ViewModel re-sourcing. Split out after #1968 to keep this parent
under the overview size cap; the coverage headings retain their anchors.
