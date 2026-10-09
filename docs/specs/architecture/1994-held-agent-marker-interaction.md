# Restore the held Agent marker interaction

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_backgroundAgent_followsBottomUntilFinished` waits on the peer's newer turn end before its first unguarded marker click; preserve placement, hold/release and cleanup.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/AgentRunNavigationProof.kt`: `verifyAgentRunNavigation` proves scroll-only navigation and owned closed/open/closed membership.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/QuestionAnswerPhone.kt`: `questionAnswerTarget`, merged in #1973, corrects moving chrome geometry but assumes the target remains composed.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentBlocksScreenTest.kt`: existing real-screen marker and collapsed-run fixtures.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentProseScreenTest.kt`: #1973 growth regression and owned paragraph proof.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadMessageList` draws under chrome; `ThreadScreen` derives markers and keyed lazy rows.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadListFollow.kt`: `FollowNewestEnd` reacts to accepted sends and newest content growth.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadRow.kt`: `listKey` identifies the owned marker as `agent-start:<agentId>`.
- `docs/knowledge/features/thread-screen-subagent-tool-rows.md`, `development-verification-compose-evidence.md` and `docs/e2e-interactive-stream.md`: ownership, lazy disposal and counted gate evidence differ.
- #1992 original gate stderr: the first physical tap fails before input injection because the marker matcher has zero nodes. #1942 instead times out awaiting the root after that tap; neither trace establishes the disappearance cause.

## Context

Repair this existing test proof without changing product or daemon behavior. #1973 / PR #1978 is merged and its geometry repair remains intact. No decision record is needed. Diagnose projection membership versus composition before choosing the harness fix; a product defect requires refinement.

## Design

Use a controlled real `ThreadScreen` fixture to exercise updates between marker reveal and tap, logging only static stages, counts, list indexes and geometry. Repair the demonstrated test synchronization/selection boundary, preserving one chrome-clear physical tap and the exact held Agent destination. Keep shared-helper defaults compatible and all existing proof assertions. Approximately 300–400 written lines, zero production files, zero new production API, three acceptance criteria and one deliverable; below all sizing limits.

Shared live-file overlaps: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1888 and #1900. Their separate methods do not create a dependency; keep edits local to this method.

## State and concurrency model

Existing Compose synchronization and test clock only; no application jobs or state. A peer turn end is not a fence for phone composition. A missing target must be classified by its lazy-list key, never treated as completion or collapse. Add no blind tap retry, sleep or timeout extension.

## State transitions and identity reuse

| Event | Required regression |
| --- | --- |
| Newer phone content or accepted-send work arrives after an initial reveal | Controlled first-marker reveal/tap regression; record held task, turn state, key membership and composition |
| More than one Agent marker exists | Owned marker selection regression; reveal the held Agent only |
| Settled navigation followed by open and close | Existing `settledNavigationThenOwnedPointerTapsOpenAndCloseALongChildRun` and #1973 prose regressions |

## Error handling

Missing projected marker, ambiguous ownership, covered tap and absent destination remain assertion failures. Evidence contains no daemon prose, payloads or raw identities.

## Testing strategy

Execute the reproducing regression red before repair and green afterward. Run `BackgroundAgentBlocksScreenTest`, `BackgroundAgentProseScreenTest` and `ThreadInlineQuestionTest`. Existing live/scenario tests need a real device for daemon/relay IO; add new regressions to sharedTest. Run the focused scripted `background-agent` scenario and inspect fresh XML for the named deterministic method and executed/failed/skipped counts. Dispatcher owns fresh full live-gate acceptance of the named rung-3 method and full scripted acceptance after verification; this ticket explicitly assigns live execution there.

Run lint, assemble, Android-test compilation and Spotless. Commit/push after the final main merge, then assemble and `scripts/pre-verify.py --gradle` with the reviewed PR body.

## Open Questions

- Which controlled update reproduces loss of marker composition while the Agent stays held and its marker remains projected? Resolve with evidence in Revisions before handoff.

## Revisions

2026-10-10 — Resolved the composition-loss mechanism with `lateNewerReplyDisposesMarkerButKeyedRevealStillNavigatesHeldAgent`. A short streaming newer reply initially leaves the running Agent's marker composed; replacing that reply with its longer finished content while following the newest end disposes the older marker. At the first reveal, marker bounds are `(20,80)-(300,125)` with chrome `69..336` and zero requested correction. After the reply update, the Agent remains running, its marker key remains at index 4, and matching composed nodes fall to zero. The unmodified #1973 helper fails fetching that disposed target before any tap: 1 executed, 1 failed, 0 skipped, retained in `/tmp/builder-1994/marker-red.xml`. Thus adopting that helper alone does not repair the original missing-node interaction.

The harness cause is a stale reveal across asynchronous phone composition, not removal of the Agent marker by the product projection. The peer's newer `turn_end` is not a synchronization fence for phone rendering. The historical stderr identifies the first tap but has no composition/list samples, so the particular frame responsible for the historical occurrence cannot be identified. The controlled real-screen regression demonstrates the missing-node mechanism independently of the same-tree rerun. The separate #1942 timeout after clicking is consistent with an unguarded covered tap, but is not used as proof of this mechanism.

Add a defaulted `lazyKey` to `questionAnswerTarget`. Within its existing three-measurement budget, a zero-node sample must still have that key in `IndexForKey`; reveal the key and take a fresh geometry sample before returning. Missing projected keys and ambiguous selectors fail. Do not add a click, sleep or timeout. The repaired sample restores bounds `(20,97)-(300,142)` and physical center 119.5, clearing chrome `69..336`; one pointer tap reveals the held Agent and retains closed child membership.

The live first tap and the shared settled navigation proof supply `agent-start:<agentId>`. The live matcher also includes the held task's bounded launch description, preserving inert rendering/selection without logging its text. `keyedMarkerWithHeldDescriptionNavigatesOnlyItsAgent` composes two equally labelled markers with distinct descriptions, establishes the held root is off screen, and proves a single pointer tap reveals that root while the other stays off screen. `removedMarkerFailsInsteadOfBeingTreatedAsLazyDisposal` proves a disappearing projected key fails rather than hiding a product removal. Shared-helper consumers retain their defaults. First-reveal evidence records only static phases, peer/phone turn state, task terminal status, marker key index, composition count and geometry. No production behavior changes.

The state-transition table's first row is covered by `lateNewerReplyDisposesMarkerButKeyedRevealStillNavigatesHeldAgent`; its multiple-Agent row is covered by `keyedMarkerWithHeldDescriptionNavigatesOnlyItsAgent`. The additional removal regression covers projection deletion. All Open Questions are resolved. Written work remains approximately 300 lines, with zero production edits or new production API.

2026-10-10 — Tightened synchronization at the identified boundary: wait for the phone repository's finalized main `newer1783` reply and Idle phase before revealing the marker, using the existing timeout. This fences the newer reply on the phone connection rather than assuming the peer's completion synchronized both clients. Keyed samples take composition count and bounds from one synchronized semantics read, preventing a fresh zero-node result from racing a separate target-bounds fetch. Remaining late layout disposal still uses the owned key and a fresh sample; the single physical tap remains unchanged.
