# Session-error recovery on macOS (#2344)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/SessionErrorRecoveryScenario.kt`: `runCase` and `run` own both recovery arms and preceding-host restoration.
- `scripts/e2e-session-error.py`: `Case.action`, `start` and `close` own dedicated daemons, independent child counts and private captures.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `onInbound`, `applyAuthoritativeLive` and `routeInbound` distinguish supplied admission from legacy decoding.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionLiveAdmission.kt`: `admit` validates session ownership before routing live readings.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionErrorProjection.kt`: `apply`, `clear` and `reset` hold connection-local errors.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositorySessionErrorTest.kt`: collector-level validation and clearing tests.
- `docs/knowledge/INDEX.md`, remote repository overview and state/errors topic: non-idle clears; content and idle preserve errors.
- `docs/knowledge/features/development-verification-gates.md` and `docs/e2e-interactive-stream.md`, Session-error recovery: counted focused evidence, original identities, deadlines and teardown.
- Sibling `pyrycode/docs/protocol-mobile.md`: authoritative source for session-scoped live state and the security model.
- Agents working practice, scope policy, device tests and security review: focused admission, private evidence custody and role boundaries.

## Design source

N/A: restore existing behavior and improve bounded diagnostics; no visual design change, as the refinement comment specifies.

## Context

The exact deterministic method failed on main and in #2340's verifier. Retained bundle `5cf72c492c81db8a8c83e6bf9734b35c49485de97814f664b84617cb2c7c7bc9` proves one execution/failure but contains no assertion stack. Dedicated historical controller actions stopped after retained release. Linux #2338 reproduces a retained status timeout, but this does not establish the macOS cause.

One deliverable is trustworthy session-error recovery, including the regression and repair. Forecast: approximately 700 written lines, no signature migrations or new exported types, four criteria and fewer than ten new error branches. No independent helper-only slice or decision record is warranted.

Remote overlaps: #1947, #2175 and #2325 touch the repository; #2338 adds scenario/test diagnostics. Keep edits local and additive, compare their diffs, and do not depend on their unproved hypotheses or restructure their diagnostic blocks.

## Design

Run the unchanged exact method through `scripted session-error` on macOS first. Preserve failed XML, phone logcat, dedicated recovery controller/daemon logs, Mobile/agents/daemon revisions and effective APK hashes privately. Historical outer daemon logs are not evidence of the recovery daemon.

Add a debug-only observation at the decoded `TurnState` boundary in `routeInbound`, using only the enum phase. Its test requires thinking/responding/idle and forbids arbitrary state or routing text. This observation applies after authoritative admission as well as legacy decoding; it does not change admission, error clearing or UI state. Add collector-level supplied-path contract coverage to distinguish admission rejection from projection failure.

Correlate phone phase/error events, existing static scenario stages and dedicated fixture actions. If those cannot distinguish the cause, add bounded allowlisted peer observations, explicitly labeled legacy. Peer receipt alone never proves phone delivery. Choose the smallest demonstrated Mobile repair and record its concrete design under Revisions before implementation. An external cause requires a linked owning-repository repair, not an edit outside Mobile. No skip, retry, longer deadline or synthetic recovery is permitted.

## State and concurrency model

The existing single inbound collector owns ordering. No new jobs, mutable application state or I/O are introduced by enum logging. Existing scopes, connection teardown and fixture process owners retain cancellation and cleanup. Diagnostic observations must not drive readiness or consume another collector's frames.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Error followed by recognized non-idle state | Existing `eachRecognizedNonIdleTurnStateClearsOnlyItsConversation` plus supplied-path collector coverage |
| Error followed by idle, malformed state or assistant content | Existing negative assertions plus supplied-path coverage |
| Repeated error/recovery within the same session and unrelated conversation | Collector tests preserve per-conversation clearing and error replacement |
| Retained child restart and queued delivery | Exact native method retains queue/message identities and completed-child counts |
| Give-up, recovery and fresh delivery | Same native method's dropped arm retains no-replay assertions |
| Fixture removal and preceding-host restoration | Same native method's final saved-pairing and connection-identity assertions |
| Connection closes or repository switches | Existing completion, cancellation and facade tests |

## Error handling

Keep original failures and cleanup assertions. Diagnostic logging must contain no raw frame, prompt, daemon prose, pairing material or routing identifier. Zero-test results and environment failures remain unverified. Preserve unsuccessful attempts; a focused pass does not erase a failed suite. If sequence/load requires wider execution, request recovery assessment using retained evidence rather than running the complete gate.

## Testing strategy

Write and run the failing enum-diagnostic test before implementation. Run the affected repository session-error class, retaining executed counts; add the controlled repair regression once evidence establishes the cause. Native transport, real device UI timing and host restoration require the existing device-only scenario rather than Robolectric. Run `python3 scripts/android-test-gate.py scripted session-error`, inspect fresh raw XML and preserve private evidence beneath agents `logs/evidence/2344/` with verified hashes and owner-only permissions.

Run focused tests, lint, debug assembly, instrumented Kotlin compilation if instrumentation/shared files change, formatting and final pre-verify after the last main merge. Push before final checks. Complete configured gates and repaired-main acceptance remain dispatcher-owned; Linux is outside this ticket's verification scope.

## Open Questions

- Does macOS reproduce the retained status deadline, or a different phase?
- Does the phone admit a non-idle recovery reading, and is a later error applied?
- Are effective inputs, cold state or prior sequence relevant? Establish from retained captures before repair.

## Security review

**Verdict: PASS** for bounded diagnostics; review any concrete repair before implementing it.

- Trust boundaries: keep `SessionLiveAdmission.admit` and `decodeLiveSessionEvent` unchanged; only decoded enum phases enter the new debug log. Cover invalid state and private routing values.
- Tokens: reuse existing fixture authorization privately; no credential acquisition or storage changes. Never publish tokens, pair codes, keys or transcripts.
- Files/storage: raw evidence stays in private persistent directories with owner-only files and verified copies. No app storage, backup or daemon-derived filename changes.
- Android surface: instrumentation uses existing components and permissions; no new exported surface, intents or WebView.
- Cryptography: existing Noise implementation and nonce/session lifecycle remain unchanged.
- Network/I/O: preserve capability and session gates, frame validation, timeouts and fixture authentication; no new endpoint or retry.
- Logs: only enum phase and static outcomes; debug-only through `RelayLog.d`. Hostile prose and unknown fields never enter published diagnostics.
- Concurrency: observations are synchronous within the owned collector; no new suspension across state updates, locks or jobs.
- Threat model: relay drop/delay/reorder cannot justify clearing on idle/content; hostile frames retain admission/decoding checks. Token theft and UI screenshot/accessibility risks gain no new surface; production mitigations remain outside this diagnostic change.
