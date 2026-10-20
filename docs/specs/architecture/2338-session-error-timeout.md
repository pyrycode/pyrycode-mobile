# Retained session-error timeout investigation (#2338)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/SessionErrorRecoveryScenario.kt`: `run`, `runCase`, `diagnosticSnapshot` and `completed` preserve both arms and teardown identity.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `recorded` supplies ordered per-conversation observations without changing delivery.
- `scripts/e2e-session-error.py`: `Case.start`, `action`, `close` and `record` own isolated recovery daemons and independent child-input counts.
- `scripts/e2e-emulator.sh`: session-error setup and cleanup determine effective tagged daemon and retained evidence.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `routeInbound` clears session errors only for recognized non-idle turn state.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionErrorProjection.kt`: `apply`, `clear`, `reset` and `observe` hold connection-local errors.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositorySessionErrorTest.kt`: existing error validation and clearing contract coverage.
- `docs/knowledge/INDEX.md`, `features/remote-conversation-repository.md`, `features/remote-conversation-repository-state-errors-and-handoff.md` and `features/development-verification.md`: connection-local projection ownership and counted native evidence boundaries.
- `docs/e2e-interactive-stream.md`, Session-error recovery (#1731): assertions, control readiness, cleanup and evidence privacy.
- Agents `docs/working-practice.md`, `docs/ticket-scope-policy.md`, `dispatcher/docs/builder-test-scope.md`, `builder/device-tests.md` and `builder/security-review.md`: managed focused execution, evidence custody and scope.

## Design source

N/A: investigation and regression repair of an existing scripted assertion; no UI redesign. The refinement-evidence comment supplies this reason.

## Context

The retained arm rendered its reply but retained a recovery status beyond the existing 20-second deadline. Historical failures and a baseline pass have identical executable Mobile sources and do not establish a cause. The historical dispatcher revision is `1f8d5e30508561adb2fb7eb5c20e2dfc42fb67f9`; the current agents checkout is `e2f17c0ca3173e2991cfbd5d853fd3f6dc8239d0`. Neither substitutes for the other's execution identity.

One useful behaviour is reliable retained-backlog recovery, including the diagnosis and regression proving its repair. No independent implementation slice is justified before identifying the cause. Forecast remains approximately 700 written lines, zero new exported types or signature migrations, three criteria and fewer than ten diagnostic branches. No decision record is needed.

Remote branches #1947, #2175 and #2325 overlap the remote repository. Their separate state/route and host-allowance work is not needed for this investigation; any eventual repository edit remains local and additive.

## Design

First run the unchanged `session-error` scenario through `scripts/android-test-gate.py` under managed admission on Linux. This worktree starts without build/install outputs; a second bounded invocation compares reused build inputs while the managed device installation remains under gate control. Record actual task/cache and install behaviour, never infer reuse from a revision alone. The current container exposes readable/writable `/dev/kvm`, unlike the historical builder.

Retain XML, phone logcat, tagged daemon/control logs, source revisions, source/APK hashes, managed preparation and cleanup evidence beneath agents `logs/evidence/2338/`, with private permissions. Capture peer frame order through content-free allowlisted observations if existing diagnostics cannot distinguish the recovery events. Compare the phone's accepted non-idle state and session-error apply/clear logs with peer order and dedicated daemon output. Preserve every failed and passing attempt.

Select a repair only from that evidence. Missing non-idle delivery, later error delivery, stale UI projection and mismatched effective inputs are competing hypotheses. Assistant content and idle remain insufficient to clear a session error. No retry, timeout increase, skip, synthetic recovery injection or production-daemon change is allowed. If the owning cause is outside Mobile, file/reuse a native owner blocker and retain the diagnosis rather than implementing outside this repository. Record any concrete repair design under Revisions before implementing it.

## State and concurrency model

No new application state or job is planned. The repository's single inbound consumer owns frame ordering; `SessionErrorProjection` uses atomic map updates and connection teardown resets errors. Diagnostics observe immutable peer snapshots and do not drive readiness, consume frames or restart a Runner. Existing controller and managed gate retain process/cleanup ownership.

## State transitions and identity reuse

| Event | Required coverage |
| --- | --- |
| Failing child exits, retained queue recovers in the same daemon/session | Exact deterministic recovery method, retained arm, including delivery identity and child counts |
| Give-up drops backlog, fresh prompt recovers | Same method, dropped arm, including independent counts and no replay |
| Recognized thinking/responding arrives after error | `RemoteConversationRepositorySessionErrorTest` clearing assertions |
| Idle/content arrives, malformed state or another conversation progresses | Existing repository contract assertions preserve the error |
| Both fixture pairings are removed and preceding host resumes | Exact method's existing final pairing/connection restoration assertions |
| Same candidate runs with fresh then reused build inputs | Two bounded managed scenario runs with effective input and APK hashes |

## Error handling

Keep original scenario failures and their diagnostic causes, including secondary cleanup failures. Diagnostic publication never includes daemon-authored prose, prompt content, identifiers, pairing payloads or keys. Missing native artifacts or a zero-test run remain unverified. If bounded evidence cannot resolve sequence/load effects, submit retained failure references and the smallest proposed scope through recovery assessment; complete acceptance stays dispatcher-owned.

## Testing strategy

Run the exact method via `python3 scripts/android-test-gate.py scripted session-error`, retaining both normal arms and original assertions. A device is necessary for actual encrypted relay delivery, native UI projection, timing and host restoration. Confirm nonzero raw XML counts and inspect failures before selecting changes. Use affected Python/controller and Kotlin tests for any demonstrated repair, observing red before implementation and green afterward. Run focused repository session-error tests, lint, APK assembly, instrumentation compilation if touched, forced formatting and final `scripts/pre-verify.py --gradle` before PR handoff. Full UI/scripted gates and the later main sweep remain pending with the dispatcher.

## Open Questions

- Which frame ordering reaches the phone during retained recovery, and does a later error replace a clear?
- Do fresh and reused effective inputs differ, and does the named method reproduce the timeout?
- Does the cause belong to Mobile, the daemon, or managed infrastructure? Resolve through evidence before repair.

## Documentation handoff

Pending for documentation stage: `docs/e2e-interactive-stream.md`, “Session-error recovery (#1731)”: record demonstrated cause, repair boundaries, named Linux results and persistent evidence paths/hashes. Preserve historical failures and distinguish focused proof from pending complete acceptance. Documentation does not produce native evidence.

## Security review

Verdict: PASS for bounded diagnosis; any demonstrated repair requires review of its concrete boundary before implementation. Reviewer: builder self-review, 2026-10-20.

- Trust boundaries: keep `routeInbound` and existing decoders as admission boundaries; diagnostic snapshots never promote peer data into production state.
- Tokens and credentials: reuse fixture-generated credentials privately. Never publish controller authorization, pair codes, keys, raw frames or prompts.
- Files and storage: private persistent evidence directory and owner-only files; only checked content-free summaries enter Git. No new app storage or user-derived filenames.
- Android surface: instrumentation only; no exported component, intent, provider or permission change.
- Cryptography: use existing Noise transport without new primitives, nonce handling or handshake changes.
- Network/I/O: existing fixture authentication, bounded calls, capability gate and managed admission stay intact; no endpoint or TLS changes.
- Logs/telemetry: only static arm/phase/type/state outcomes; allowlist state values and never log untrusted error codes or text. Raw diagnostics remain private.
- Concurrency: observe immutable snapshots without suspension across production writes; existing scopes and cleanup remain owners. Do not retry denied cleanup operations.
- Threat model: relay delay/drop/reorder remains a diagnostic hypothesis, never grounds for weakening assertions. Hostile daemon frames retain existing decoding and text boundaries. Disk-token theft, release screenshots/accessibility and production resource exhaustion receive no new surface from this test-only investigation.
