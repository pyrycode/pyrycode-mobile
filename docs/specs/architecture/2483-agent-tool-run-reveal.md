# Recorded Agent tool-run reveal

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedMobileSuiteTest.kt`: the original scenario delegate and recording/cleanup rules.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `backgroundAgentReplyProof`, especially evidence readiness and the first owned run scroll.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/AgentReplyEvidence.kt`: `heldAgentReplyEvidence` binds the exact Agent, child prose, tool run and settled main reply.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadEvidence.kt`: `authoritativeThreadEvidence` and `toolRunIds` derive run identity from production projection.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/QuestionAnswerPhone.kt`: existing keyed reveal and chrome-clear pointer geometry.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadRowContent` and the ToolRun rendering branch expose separate owned run semantics.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolRunRow.kt`: clickable merged header and expand/collapse labels.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/AuthoritativeAgentOwnershipTest.kt`: exact-owner collapse coverage.
- `docs/recorded-tests.md`, What replay proves and Complete native suite: unchanged payload, causal, lifecycle and teardown contracts.
- `docs/knowledge/features/thread-screen.md`, `thread-screen-subagent-tool-rows.md`, `development-verification.md`, `development-verification-compose-evidence.md` and `development-verification-agent-reveal-evidence.md`: projection, composition and geometry are distinct evidence; a valid key alone cannot establish a clickable descendant.

## Design source

N/A: investigate native test reliability; no visual change requested.

## Context

The historical macOS attempt at `1a77b632eabbed27f95986ea4e59332a44f7e21e` failed before graph restart. Its cause is unproved. This ticket owns one behavior: reliably locating and opening the exact held Agent's collapsed run while keeping the original scenario proof intact. No decision record is needed.

## Design

First run the original recorded method unchanged on Linux. Diagnose its first run lookup at three independent boundaries: admitted owned evidence, the exact projected `msg:<runId>` key, and the clickable header under `tool-run:<runId>`. Add bounded content-free diagnostics if the unchanged failure lacks these observations. Log counts, index and static stages only; do not dump message text, raw identities or entire semantics trees.

The proposed test-only repair uses the already proven exact lazy key to reveal the owned row before resolving its existing clickable descendant. Preserve the exact ancestor, text, click label and uniqueness checks; an unrelated run cannot qualify. If evidence demonstrates a different cause, record the revised design before implementing it. Do not change production placement, collapse behavior, request matching, recordings, timeouts or cleanup. Add a controlled real-screen regression beside the existing ownership tests for the demonstrated boundary, then reuse the repaired operation in `backgroundAgentReplyProof`.

Overlaps: remote branches #2462 (later `restartApp`), #2373 (marker helper), #2175 (earlier held-evidence helper) and historical feature branches touch these shared files. Their inspected relevant changes supply no prerequisite to this pre-restart repair. Keep edits local and additive.

## State and concurrency model

No production job or flow changes. Native assertions continue reading passive evidence on the current negotiated connection. The existing Compose synchronization, scenario deadlines and three geometry measurements retain ownership of waits and pointer reveal. No detached coroutine or additional physical tap is introduced.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Held Agent and intervening settled main reply, collapse enabled | Original `RecordedMobileSuiteTest.interactiveTurn_earlierSessionAgent_survivesRestartAndSettlementReconnect`, plus the controlled screen regression chosen from the failure observations |
| Reset after exact owned-key readiness | `AgentReplyEvidenceScreenTest.heldRunRebindsAfterResetBetweenReadinessAndReveal` uses the real store reset, proves the old key absent and current owned key present, then opens the current exact header |
| Two resets reuse the same raw Agent and turn identities | `AgentReplyEvidenceScreenTest.heldRunRebindsAfterTwoConsecutiveResets` requires both replacements and opens only the final current run |
| Closed → expanded → closed owned run | Original scenario's exact click-label, prose visibility/uniqueness and owned outlined-tool assertions; existing `AuthoritativeAgentOwnershipTest` |
| Graph/cache replacement and settlement reconnect | Original scenario and `proveNegotiatedAgentRestart`, unchanged |
| Missing or unrelated owned row | Controlled regression must reject a missing exact key or wrong run rather than accepting any clickable header |

## Error handling

Missing evidence or projection remains a bounded failure. A key present without the required semantics is reported separately. Preserve the first scenario error and every suppressed teardown error. Retain all failed attempts; a later pass does not recategorize the historical failure.

## Testing strategy

Watch the original focused recorded method fail, or add observations and a controlled regression that demonstrates the same mechanism before repair. Run the controlled regression red then green, the affected existing ownership/helper coverage, and the original method end to end. Native replay requires real Android storage, Activity graph replacement and background replay delivery, so its existing device-only location remains appropriate; new screen-only coverage belongs in `app/src/sharedTest`.

Run focused JVM tests, lint, debug assembly, instrumented Kotlin compilation, formatting, then assembly and `scripts/pre-verify.py --gradle` after the last merge of main. Retain counted XML, phone/replay logs, effective APK/source identities, exit status and cleanup receipts privately with a hash manifest. Verification is Linux only; historical macOS evidence stays failed and macOS execution is outside scope. Complete configured acceptance belongs to the dispatcher.

## Open Questions

- Does the original method lose its run in admitted evidence, projection, or semantics discovery? Resolve with fresh focused observations before selecting the final repair.
- Does a controlled key-first reveal reproduce and repair that same boundary? Require evidence rather than assuming the earlier marker or restart failures share a cause.

## Revisions

- 2026-10-23: the unchanged Linux method passed once. The newly available #2462 rework bundle `cb449f5a3d1fa6d20b59efdf3f9e08086130bc96d78ab2c5c099fbc8bd98f121` retains the original failure: its matcher names generation 0, while the phone trace records a reset and a fresh 12-row projection before lookup fails. Projection scope includes generation. Add `HeldAgentRunReveal.revealHeldAgentRun` as an extraction of the original wait/scroll, with `AgentReplyEvidenceScreenTest.heldRunRebindsAfterResetBetweenReadinessAndReveal` replacing the generation after the exact original key is ready. Observe the extracted old lookup fail before repairing it. The revised design re-resolves the same raw Agent/turns within one original readiness deadline, validates and scrolls its current exact key together, then requires the exact clickable descendant and current identity before returning. Rebinding never changes production placement or ownership. The original trace does not record failure-time keys; distinguish the controlled demonstrated mechanism from any unsupported historical inherited/regression classification.
- 2026-10-23: the controlled screen run executed two methods: the existing proof passed and the reset probe failed at the original generation-0 clickable-header matcher. Its real store reset retained the same admitted family and current projected run, while removing the obsolete key. This resolves the diagnosis questions: the defect is retaining a generation-scoped lookup identity, not absence of the owned tools or their current header. The scenario body and fixture hashes match between the historical failing revision and the unchanged Linux baseline; broader branch regression classification remains unproved. Preserve both outcomes and the exact red patch under agents `logs/evidence/2483/`.
- 2026-10-23: the repaired lookup passed the reset probe and existing screen proof (2/2). Added the consecutive-reset case through the same real-store control; final focused classes passed 14/14 with zero failures/errors/skips: `AgentReplyEvidenceScreenTest` 3, `AgentReplyEvidenceTest` 9 and `AuthoritativeAgentOwnershipTest` 2. One intermediate compile attempt executed zero tests because of a missing semantics import, corrected before these passes. The earlier formatting failure was corrected and Spotless apply passed. Native replay and final build checks remain to be recorded before handoff.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/development-verification-agent-reveal-evidence.md`, “Geometry, disposal and proof-entry readiness”: document current-generation held-run lookup across a recorded reset, the controlled old-key failure, and the final counted Linux proof. Keep historical macOS failure and source-comparison limits distinct.
