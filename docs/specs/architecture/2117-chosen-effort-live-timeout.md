# Chosen effort first-turn live timeout (#2117)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: chosen-effort scenario, `prepareChat`, `hostRepository`, `freshSettings`, `awaitFooter`, and `restoreSettings` define the proof and cleanup.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/RememberedEffortStage.kt`: mirror static operation diagnostics without logging settings or exception text.
- `app/src/test/java/de/pyryco/mobile/e2e/RememberedEffortStageTest.kt`: existing result, cause and cleanup controls.
- `docs/knowledge/features/thread-composer-footer-effort-recall.md`: acknowledged writes and fresh readings must remain distinct from applied effort.
- `docs/knowledge/features/development-verification-gates.md`: focused tests and Android compilation requirements.
- `docs/e2e-interactive-stream.md`: What rung 3 is made of / model and effort settings round trip defines the existing live contract.
- `docs/specs/architecture/2075-run-configuration-acknowledgement-wait.md`: preserve the section-scoped acknowledgement barrier.

## Context

Recovered #2057 primary and retry reports under `/tmp/builder-2117/evidence/`: Mobile `187cd0e2139da0c510be4a08efe0c9561c550de9`, main `cfc5911723bad305dcf2486283521915dce91b84`, daemon `7a1daa57bae0c88a0c96b2b4e62c990fa76f47ca`, Claude 2.1.280. Primary ran 65 methods with 38 failures and zero skips; chosen effort timed out in a coroutine after 30000 ms. Retry ran 38 with 27 failures and zero skips; chosen effort passed. The retained short stack cannot identify the operation. Original raw phone captures are unavailable. The fresh unmodified focused baseline passed 1/1, zero failures/skips, on Mobile `e7a553c3d6d374ed47fabe5c2079fa9e7df80ae1`, daemon `8091a4730d42f43029eb0b955442dc300106030f`, Claude 2.1.280. Neither pass is a diagnosis.

#2095 / PR #2111 pins inherited launches to Sonnet; this scenario chooses a non-inherited model. #2106 is waiting on #2101 for fixture connection teardown; no shared cause is established for this timeout. Remote overlap is extensive across other scenario blocks, including #2009, #2032, #2057 and #2112; keep edits local. #2101 and #2106 have no pushed repair. No decision record is needed.

## Design

Add private static operation diagnostics local to chosen effort before attempting any mechanism repair. Label readiness, creation/rename, initial settings, published menu, opening, model/effort tap and settlement, saved/pre-turn reading, send, displayed reply, applied reading, leaving/reopening, reopened observation and cleanup. Keep shared helpers and signatures unchanged. A wrapper records start/complete/failure using fixed labels and wraps failures with their original cause. It never logs names, models, levels, daemon text, payloads or exception messages.

Retain a fresh failing operation capture and correlate it with phone and daemon connection/request events. Repair only the demonstrated localized Mobile mechanism and append its contract and red/green regression to Revisions before handoff. If the evidence requires another owner's repair, link the owner issue and wait instead of changing speculative waits. Diagnostics alone do not complete this ticket.

Preserve the published non-inherited effort-capable model and its first level chosen through the phone before any message, settled choices, saved effort acknowledgement, no pre-turn applied string, exactly that applied level after the first real turn, unchanged reopened level/no fallback note, and finally cleanup. No retries within the scenario, extra turn, assertion relaxation or deadline increase.

## State and concurrency model

No production state, flow, job or connection lifecycle changes are planned. Test calls retain their existing deadlines and execution scopes. Any demonstrated stateful repair requires a revision with explicit lifecycle coverage.

## State transitions and identity reuse

None in the diagnostic wrapper: it encloses existing operations and carries no identity state. The live scenario remains the proof of a fresh conversation, first turn and reopen under the same conversation. Wrapper controls cover success, exception/assertion cause retention and finally cleanup.

## Error handling

The earliest operation failure is identified by a fixed operation label while retaining its cause. Existing `restoreSettings` still attempts settings restoration and remembered-effort clearing after failure. A connection timeout is not classified as an effort mismatch. Raw captures stay outside the worktree until sanitized evidence is ready.

## Testing strategy

Write wrapper regression controls first and observe missing stage diagnosis fail, then add operation instrumentation. Run focused chosen-effort live capture, adding its preceding inherited-effort method only if needed to investigate suite interference. These are investigation runs, never retry-until-pass acceptance. The live method needs the device for its authenticated daemon and on-device settings; no new device-only method is planned. A causal mechanism fix needs its own failing-before/passing-after regression.

Run focused wrapper and affected existing tests, lint, debug assembly, Android-test compilation and forced Spotless. After the final merge of main, push and run debug assembly and `scripts/pre-verify.py --gradle`. Dispatcher owns the fresh full live gate; require explicit named execution/pass, Mobile/daemon revisions, Claude version, raw XML/logcat references and executed/failed/skipped counts before documentation and merge.

## Open Questions

- Which operation caused the original timeout? Recover or reproduce before assigning causality.
- Does a fresh failure require a Mobile fix or the setup repair owned by #2101? Decide from correlated evidence.

## Documentation handoff

- Pending documentation stage: `docs/e2e-interactive-stream.md`, What rung 3 is made of / model and effort settings round trip: record the demonstrated cause, repair and fresh dispatcher full-live proof with counts and revisions. Do not describe a focused pass as full acceptance.

## Security review

**Verdict:** PASS

- Trust boundaries: diagnostics consume fixed local labels only; existing authenticated settings and published model boundaries remain unchanged.
- Tokens and credentials: use the approved isolated live launcher; never fetch, copy or log credentials in the scenario.
- Files and storage: raw captures remain in private build or task scratch directories; no product persistence, path construction or backup changes.
- Android attack surface: no manifest, exported component, intent, permission or WebView changes.
- Cryptography: reuse existing Noise session without changing identity, key, cipher or nonce handling.
- Network and I/O: no timeout, retry, frame cap, TLS or authentication changes; a lost connection cannot be treated as a successful settings reply.
- Errors and telemetry: static operation/outcome logs only; causes are retained for test failure diagnosis but their text is never copied into new logs.
- Concurrency: synchronous wrapper introduces no jobs, locks or state; existing finally cleanup remains required.
- Threat model: a delaying relay or missing daemon reply fails at the unchanged deadline. No new plaintext exposure or frame interpretation. Rooted-device theft and UI capture remain outside this test-only change.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-12

## Revisions

### 2026-10-12 — fresh failure establishes a daemon dependency

The operation enum/wrapper lives in sharedTest so pure JVM controls can test it, following `RememberedEffortStage`; it has no product API or other scenario consumers. The chosen scenario locally expands `prepareChat` solely to label repository acquisition, creation, rename and initial settings. Creation and rename retain their original single deadline. Shared helpers and every behavior assertion remain unchanged. Four JVM diagnostic controls pass; the initial passthrough failed all three initial controls. The additional real coroutine-deadline control checks the timeout through Kotlin's copied exception wrappers.

Fresh focused captures, including the six-method settings/reconnect sequence, passed against daemon `7a1daa57bae0c88a0c96b2b4e62c990fa76f47ca`. The exact historical prefix through chosen effort then ran 27 methods: 21 passed, 6 failed, zero errors/skips. Chosen effort failed at Readiness with a 30000 ms coroutine timeout, before clearing preferences or creating its fixture. `awaitChannelList` uses a Compose timeout; this coroutine timeout is from `awaitConnected`. Cleanup completed. Its own logcat records peer close 4413, and the preceding reconnect logcat records repeated 4413 closes after successful first-turn rendering. The separate background-agent failure is already owned by #2112.

Daemon source routes ordinary reconnect output through `forwardEnvelopeSource` / `hostedEnvelope` into the shared 16-slot hosted queue. Open `pyrycode/pyrycode#3214`, PR #3232, owns the independently reproduced missing capacity admission: ordinary pushes leave their bounded upstream queue before destination capacity is available. Its repair reserves capacity before the pop and resumes on completion. This is a required daemon repair, not a Mobile effort or footer change. A native cross-repository blocker was added and verified. No limit, wait, retry, assertion or production code was changed here.

The original primary's short stack still does not establish its exact operation or associate its 4410 daemon records with chosen effort. The fresh prefix has zero missing-session rejection records and demonstrates a readiness failure with 4413; do not conflate those captures. `app/src/test/resources/e2e/2117/investigation.json` retains sanitized provenance, counts, source/artifact hashes and raw references. Raw XML/logcat/daemon copies remain private under `/tmp/builder-2117/evidence/`.

Wait for #3214 to merge before continuing. Re-run the named method and the evidenced cumulative prefix against a daemon containing that repair, reassess any remaining Mobile mechanism, then complete the Mobile PR and required final checks. The fresh dispatcher full-live named pass remains required before documentation/merge; diagnostic controls and focused passes do not satisfy it. The current branch is an investigation checkpoint, not a completed repair.
