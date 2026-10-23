# Development verification — Agent reveal evidence

Part of [Compose evidence](development-verification-compose-evidence.md#compose-evidence).

## Geometry, disposal and proof-entry readiness

A valid earlier geometry sample also does not establish a completed reveal while
content is growing. In #1973, following the newest end let later owned Agent prose
growth move an earlier paragraph behind the header between measurement and the
result check. An Agent progress frame does not prove that the fixture's HTTP hold
has been reached. `questionAnswerTarget` keeps the owned matcher, takes at most
three fresh measurements/corrections, and rechecks the target and both chrome
edges after each displacement, including a zero displacement. It returns only
when the physical center clears the measured header and composer; otherwise the
chrome guard still fails. This adds no tap, sleep or timeout. Its defaulted evidence
sink records only geometry and static stages, without content or identifiers.

Repository completion also precedes rendered readiness during reset/catch-up (#2373).
Before the Agent pointer proof, sample one merged list and require its exact root,
marker and closed representative keys together through `IndexForKey`. Missing content
or another Agent must time out; ambiguity fails. Keep the existing pointer and membership
proof after readiness. A failure diagnostic's later unmerged lookup is not a simultaneous
tree snapshot: the controlled reset had no list in either tree, so switching trees
would not fix it. Separate the original lookup assertion from suppressed replay-close
teardown failures. See [the causal regression and counted Linux evidence](thread-screen-agent-navigation-evidence.md#agent-navigation-proof-readiness-2373).

Held tool-run readiness must also survive generation replacement (#2483). With
collapse enabled, a held Agent and an intervening settled main reply, the original
lookup could retain a generation-scoped run identity after its indexed-key check.
`revealHeldAgentRun` re-resolves the same raw Agent and turns from current admitted
evidence within one original absolute deadline. It requires one message list,
checks `msg:<runId>` through `IndexForKey`, then validates that key and dispatches
`ScrollToIndex` together on the UI thread. Detached lists or missing keys remain
unready. After scrolling it resolves again and requires unchanged current Agent
and run identities plus one clickable “Using tools:” descendant under the exact
`tool-run:<runId>` ancestor. Multiple lists or matching headers fail as ambiguous.
The caller retains the original click-label, ownership, visibility and uniqueness
assertions; diagnostics contain only bounded static readiness/reveal stages.

`AgentReplyEvidenceScreenTest.heldRunRebindsAfterResetBetweenReadinessAndReveal`
uses the real store reset after the exact old key is ready. The same admitted
family and current run remain available, but the obsolete key leaves the real
projection. The extracted original lookup failed at its generation-0 clickable
header: 2 executed, 1 passed, 1 failed, zero errors/skips. This demonstrates stale
lookup identity across reset, rather than missing owned tools or a missing current
header. After repair, this probe and `heldRunRebindsAfterTwoConsecutiveResets` open
only the final current run, including when both resets reuse raw Agent/turn IDs.

Key existence independently fails to prove header existence: the first owned tool
can survive as a singleton under the same message key without a tool-run header.
The real-screen `heldRunRejectsMissingProjectedKey` and
`heldRunRejectsUnrelatedClickableRunWithOwnedKeyPresent` keep complete admitted
evidence fixed while removing the owned run or retaining only its first tool.
Both invoke the reveal helper with a 1-second deadline, require
`ComposeTimeoutException` and no successful reveal, and leave a second Agent's
clickable run collapsed. They subsequently open that unrelated run and prove its
uniquely attributed prose, establishing a working control. Removing the initial
key-readiness guard and exact header ancestor made both negatives fail: 2 executed,
2 failed, zero errors/skips, exit 1. A single-run positive fixture cannot detect
substitution of another Agent's clickable header.

Final Linux evidence on 2026-10-23, after restoring the helper byte-for-byte:

- Separate focused class runs passed 16/16, zero failures/errors/skips, exits 0:
  `AgentReplyEvidenceScreenTest` 5 (both resets, both negatives and collapse/reopen),
  `AgentReplyEvidenceTest` 9 and `AuthoritativeAgentOwnershipTest` 2.
- The original `RecordedMobileSuiteTest.interactiveTurn_earlierSessionAgent_survivesRestartAndSettlementReconnect`
  passed a separate focused `python3 scripts/android-test-gate.py ui --tests
  de.pyryco.mobile.recording.RecordedMobileSuiteTest#interactiveTurn_earlierSessionAgent_survivesRestartAndSettlementReconnect`
  run at `ef3cb7cf58e658cc0ad1ecdc89e3f181f366c0ab`: 1 executed, 1 passed, zero
  failures/errors/skips, gate/native exits 0. Phone diagnostics recorded one owned
  readiness/reveal and managed cleanup finished. Original placement, parent
  attribution, collapse/expand, uniqueness, main-reply, graph/cache restart,
  settlement/reconnect, strict request/payload matching and teardown proofs remain
  active. Raw XML, phone/replay diagnostics, source/effective APK identities,
  unchanged packaged recording, failed attempts and cleanup receipts were retained
  with a SHA-256 manifest at agents `logs/evidence/2483/rework-rejection-ef3cb7cf5/`.
- At verifier head `f758c83853d79442f563a80adbe5859dc358be2b`, configured gates
  passed: JVM 5,357 executed/passed, zero failures/errors/skips; UI 333
  executed/passed, zero failures/errors, plus 1 skipped
  (`RenameDialogCaptureTest.renameAtFigmaViewport`); scripted-all 24 executed/passed,
  zero failures/errors/skips. The UI run includes the original recorded method;
  JVM includes all four reset/rejection methods. These are full-gate results,
  separate from the focused proof above. See [the accepted verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/2485#issuecomment-6356839392).

The unchanged Linux baseline separately passed 1/1. The historical macOS pair at
`1a77b632eabbed27f95986ea4e59332a44f7e21e` remains 2 executed, 1 passed, 1 failed,
zero errors/skips. Its matcher names generation 0 and its phone trace records a
reset and fresh 12-row projection, but no failure-time projected keys were captured.
Historical and baseline scenario bodies and recording bytes match; this comparison
does not establish the historical interleaving or broader inherited/regression
classification. Earlier controlled/compile failures and zero-test selector refusals
remain retained under agents `logs/evidence/2483/`; none is a pass. macOS execution
is outside this Linux ticket's verification scope. Replay supplies no fresh provider
observation; see [what replay proves](../../recorded-tests.md#what-replay-proves).

Peer `turn_end` is not a phone render fence (#1994). Before the first held-Agent
marker reveal, wait for the phone repository's finalized main newer reply and Idle
phase using the existing timeout. Later layout can still dispose the revealed row.
Supply `agent-start:<agentId>` as `questionAnswerTarget`'s optional `lazyKey`:
a zero-node sample must retain that key in `IndexForKey`, then reveal the same key
and take fresh geometry within the existing three-measurement budget. Read node
count and bounds together from one synchronized semantics sample. A removed key
or ambiguous selector fails; neither is a reason to retry a physical click.
The live matcher also includes the held task's bounded, inert launch description,
which must not be logged. Default helper callers retain their existing behavior.

`BackgroundAgentBlocksScreenTest` supplies three independent real-screen probes:
`lateNewerReplyDisposesMarkerButKeyedRevealStillNavigatesHeldAgent` grows the newer
reply after the first reveal and establishes a running task, a projected marker
and zero composed nodes before recovery;
`keyedMarkerWithHeldDescriptionNavigatesOnlyItsAgent` uses two equally labelled
markers and proves one chrome-clear pointer tap reaches only the held Agent;
`removedMarkerFailsInsteadOfBeingTreatedAsLazyDisposal` rejects projection removal.
The recovery probe fails before repair, before any tap, and passes afterward.
The historical missing-node trace establishes the first failed tap but contains
no projection/composition samples, so it cannot identify that occurrence's exact
update. See [counted evidence](../../e2e-interactive-stream.md#held-agent-marker-reveal-1994).

Use one center pointer tap per open/close transition and prove the resulting state
independently of lazy disposal. Reveal the owned prose before checking that it
appears exactly once, then require its lazy-list key absent through `IndexForKey`
after closing. Semantics absence alone can mean that an open paragraph went off
screen. `BackgroundAgentProseScreenTest.lateOwnedProseGrowthIsRemeasuredBeforeClosingItsRun`
injects growth after the first sample on the real screen with a scoped 320×480dp
fixture and positively asserts that growth covers the paragraph.
`expandedOwnedRunBehindHeaderIsCorrectedBeforeItsCloseTap` separately covers the
owned expanded control behind the header and its guarded physical close. See
[the Agent proof and counted evidence](../../e2e-interactive-stream.md#verification-status).

## Finished-marker generations and atomic scroll dispatch (#2373)

The later finished-marker display check runs before the navigation proof's entry guard.
A guard at proof entry therefore cannot fix that earlier reveal. Repository settlement
also cannot freeze layout or an authoritative generation. Resolve the same raw Agent
against current admitted evidence containing the settled later reply, and resample its
rendered marker key before each geometry read. Keep identity and content out of diagnostics;
static readiness counts and identity-change booleans are enough.

Refreshing keys at geometry boundaries still leaves a check-to-action gap. In the opt-in
`questionAnswerTargetWithCurrentKey` path, validate the current key's index and dispatch
`ScrollToIndex` in one UI-thread operation. Detached lists and missing keys dispatch no
action and remain unready within the same absolute deadline. Ambiguity fails. The native
repository wait and readiness consume one original timeout budget; the three geometry
measurements, default callers' strict removed-key failure, owned finished-label check and
physical tap count remain unchanged.

Successful atomic keyed placement does not keep the list mounted for a later matcher
scroll. The subsequent #2387 recurrence failed that second `performScrollToNode` after
the owned finished marker had already been revealed. The opt-in current-key bridge
therefore uses its existing keyed placement directly; its three geometry measurements
continue to resample current identity/readiness and correct position. Only that bridge
omits the redundant matcher scroll. Default `questionAnswerTarget` and `questionAnswerTap`
paths keep it and their existing strict removed-key behavior. This adds no tap, sleep,
scenario retry, geometry attempt, deadline extension or recording change.

`resetAfterKeyedRevealWaitsForTheOwnedFinishedMarker` empties the real screen after the
successful keyed reveal, restores the same owned family only at the next identity sample,
and positively checks its displayed finished marker and collapsed children.
`resetAfterKeyedRevealCannotAcceptAnotherFinishedAgent` instead supplies a different
finished Agent and requires timeout rejection even though that other label is displayed.
These controls cover the gap after atomic dispatch; they do not replace the earlier
growth, generation, missing-list and wrong-owner probes. See the
[post-reveal cause and evidence](thread-screen-agent-navigation-evidence.md#post-keyed-reveal-reset-recurrence-2373).

Independent Linux acceptance at `a32c07ca8450d4b2065c2bb0b3366e94d4cdb63d`
executed/passed both post-reveal controls in the full JVM gate: 5388 executed/passed,
zero failures/errors/skips. The configured UI gate executed/passed the original recorded
Agent method once among 333 executed/passed, zero failures/errors and one unrelated
rename-capture skip. Scripted-all executed/passed 24 methods, zero failures/errors/skips,
including both background-Agent placement/settlement and restart/reconnect scenarios.
The [independent verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/2454#issuecomment-6360402269)
confirms unchanged helper defaults, absolute deadline, three geometry measurements,
owned finished-label assertion and tap count. These full-gate results are separate
from the earlier manual acceptance and five historical native passes. Source/recording
identities, retained failed controls and the unavailable historical timeline remain in
the linked navigation evidence.

The controlled growth, generation-during-measurement and readiness-to-scroll probes each
failed once before repair, with zero errors/skips, then passed in the final 27-method
block class. Preserve the reset and wrong-Agent/running-label negatives. A keyed reveal
can prevent displacement by anchoring history; the final growth probe accepts prevention
or correction only with positive display and chrome-clear geometry. Do not require an
intermediate hidden state that the repaired operation prevents.

See [Agent navigation evidence](thread-screen-agent-navigation-evidence.md#finished-marker-reveal-recurrence-2373)
for the exact red controls, final source and unchanged recording hash, five separate
one-execution native passes, 41 focused JVM passes, configured UI counts and unrelated
accepted gate failures. Historical failed/passing XML establishes the first finished-label
assertion and same-tree pass; it contains no failure-time geometry, and the selected archive
omitted that scenario's phone log. Controlled mechanisms do not recover the historical
interleaving. All recorded acceptance here is Linux; macOS was outside scope.
