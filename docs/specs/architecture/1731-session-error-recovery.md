# Session-error recovery through the phone (#1731)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `pairHostByCode`, phone send and semantic wait patterns.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: real app rule and scripted scenario selection.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open`, `awaitQueue`, `awaitFrame`, `recorded` provide an independent wire observer; sends stay on the phone.
- `scripts/e2e-emulator.sh`: private profiles, pairing, cleanup and curated live methods.
- `scripts/android-test-gate.py`: `SCENARIOS` and fresh counted XML retention.
- `scripts/e2e-daemon-fault.py`: stop/start changes daemon identity and cannot prove this recovery.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedSessionErrorTest.kt`: exact copy and local send settlement already proved at rung 2.
- `docs/knowledge/features/remote-conversation-repository-state-errors-and-handoff.md`: error holds clear on local send and recognized non-idle turn activity, not daemon release itself.
- `docs/e2e-interactive-stream.md`: ladder and evidence boundaries.
- Daemon `docs/knowledge/features/e2e-realclaude.md`, Test infrastructure, and `internal/e2e/realclaude/session_error_recovery_test.go`: tagged selection contract, close-stdin readiness, first-exit fence, default retained timeout and dropped-only 3s timeout.
- Daemon `docs/protocol-mobile.md`: single source of truth for session_error, queue_state and delivered message envelopes.

## Design source

N/A — test-only coverage of the existing Error pill anchored by #1678 at https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6619; no new UI.

## Context

#1678 proves rendering and settlement at rung 2. #1731 adds one recovery contract with two necessary arms on the real daemon/relay/app path. No app production behavior, wire command or daemon behavior changes. The daemon control shipped in pyrycode#2859 / PR #2863. Its contract remains owned by the daemon documentation; this plan consumes it, not duplicates a new contract. No decision record is needed.

## Design

Add `SessionErrorRecoveryScenario` under androidTest, called by the named live method and the identically named deterministic twin. It pairs each fixture-owned host using the existing paste-code UI, opens its bound conversation, and sends via the composer. `SecondClientPeer` records wire frames using its own pairing and key, without repository/ViewModel/UI injection.

Add `scripts/e2e-session-error.py`, a loopback-only, authenticated consumer fixture. It starts one private tagged daemon per arm at scenario entry, seeds one bootstrap session and bound promoted conversation, and mints separate phone and observer pairings. Executable selection is private and replaced atomically. The failing executable closes stdin, advertises readiness, then waits on the first-exit gate. The phone waits for readiness before sending, observes the original queue ID and absent delivery, then opens the exit gate. Later failing children exit immediately.

Retained: require conversation-scoped child_crashing, exact Error copy and absent Sending/Waiting, with the same undelivered queue ID. Release to real Claude (or scripted Claude), requiring that ID delivered once, empty backlog, an assistant reply, completion and cleared pill, without resend or blocked.

Dropped: require child_crashing plus blocked, exact blocked copy, absent local status, empty backlog and no user delivery. Release, observe a running recovered child before sending a fresh prompt. Require only the fresh ID delivered once, reply/completion, empty backlog and cleared pill. The completed child transcript independently proves expected prompt occurrence and dropped prompt absence.

The fixture records the unchanged daemon PID, bootstrap status/session identity and persisted conversation binding before and after release. No stop/start, signal or restart request is exposed. A single persistent bootstrap Runner follows its existing supervisor loop; session rotation or daemon exits fail identity checks. The fixture observes its control-plane child PID after release, with executable identity checked against the recovery executable.

The shell harness builds a separate e2e_realclaude-tagged binary only when this scenario is selected, starts the fixture and passes its port/authorization to instrumentation. Other daemons keep ordinary binaries and environments. Register session-error in SCENARIOS/scripted-all and the live method in the curated selector. Retain daemon logs, control observations and completed transcript evidence in build output independently of temporary HOME cleanup.

Shared files overlap #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1817, #1833 and #1866; their changes affect other methods or additive selector entries. Keep edits local.

Forecast: about 1100–1400 written lines including Python contract tests and plan; at most 3 new test types, 2 wrapper consumers, 5 acceptance criteria, no production state-machine branches. Recount before implementation commits.

## State and concurrency model

The host fixture owns and cleans up all case daemons and private HOME directories. Each arm progresses start → stdin-ready → queued → first exit → error → release → recovered → completed. A bounded state poll observes readiness, child recovery and transcripts; polling intervals are not evidence or fixed recovery delays. Requests have bounded I/O. Kotlin runBlocking is test-only; SecondClientPeer owns and cancels its observer scope in use/finally. No production coroutine changes.

## Error handling

Missing source controls, tagged build failure, unavailable recovery executable, failed pairing or child readiness fail explicitly, never ignore/skip. Fixture errors are static categories and private evidence paths, not credentials or payloads. A failed arm still stops only its owned daemon and retains evidence. Recovery cannot silently fall back to production settings.

## Testing strategy

Test first: Python tests exercise atomic selection and the closed-stdin/readiness/exit fence, identity rejection and transcript prompt accounting with controlled child processes. Watch their failure before implementing the fixture. Both instrumentation methods compile. Run focused scripted session-error on the managed device and inspect fresh named XML counts; it uses real daemon plus fakeclaude, zero real-Claude turns. Device-only is necessary for real relay, cross-process child faults and native phone input; Robolectric cannot provide this stack. Run existing ScriptedSessionErrorTest for rendering regression coverage, lint, assembleDebug, androidTest compilation and forced Spotless. After final main merge run the entire unit/shared suite, assembleDebug and pre-verify.py --gradle. Dispatcher owns fresh live and scripted-all results; compilation and rung 4 do not establish live acceptance.

## Open Questions

- Resolved before implementation: local daemon checkout predates the prerequisite. Read fetched origin/main in a private /tmp source snapshot for focused execution, leaving the live checkout untouched. Harness must fail clearly on an outdated configured source.
- Resolved during implementation below: use bootstrap control status and private real-Claude JSONL or fakeclaude per-child stdin transcripts.

## Documentation handoff

Pending documentation stage: update `docs/e2e-interactive-stream.md` under The ladder, Live mode (rung 3, live relay), and Session-error recovery — pending (#1731). Replace obsolete unfiled-prerequisite/no-trigger statements; identify both methods and scripted session-error; link daemon Test infrastructure contract; record actual counted dispatcher live/scripted-all XML and daemon/control evidence and remaining evidence boundaries. Documentation consumes results; it does not produce live evidence.

## Security review

**Verdict:** PASS

- Rework review (2026-10-07): the deterministic reply fixture contains static non-secret output, is created at a fixed path inside the case's private HOME with mode 0600 before child spawn, and overrides inherited replay settings only for the scripted case. Live recovery inherits no replay fixture. The exact-text matcher rejects both sent prompts; the existing bounded wire, UI and transcript waits remain mandatory. No new production trust boundary, credentials, Android surface or concurrency path is introduced.

- [Trust boundaries] SHOULD FIX: host control can change daemon-wide executable selection. Bind only loopback, require a randomly generated bearer authorization, use a fixed action/arm allowlist, and return only fixture-owned pairings. Never accept filesystem paths or executable names from HTTP.
- [Tokens] Existing phone/observer pairing flow remains Noise-authenticated. Keep pair output and fixture authorization private; omit request/body/header logging. Live credentials are inherited from the gate, never fetched by this scenario or retained as evidence.
- [Files/storage] SHOULD FIX: create private short HOME directories and 0600 regular selection files, use same-directory atomic rename, and copy only owned transcript evidence. Cleanup must remove only fixture-created directories, excluding credentials from retained artifacts.
- [Android surface] Only androidTest code and instrumentation arguments change; no exported production component, provider, intent filter, WebView or new UI.
- [Cryptography] Reuse SecondClientPeer/NoiseIkSession and existing key stores. Authorization uses secrets.token_hex, no new cryptographic protocol.
- [Network/I/O] Bounded host requests and polls; loopback HTTP is a test-only channel. Live relay keeps existing TLS/Noise, local scripted relay keeps its existing explicit insecure-test path.
- [Logs/telemetry] Retain controlled test prompts and child transcripts privately for delivery proof, never credentials or decrypted wire dumps. Public diagnostics report counts, codes and evidence paths. No production telemetry changes.
- [Concurrency] Atomic selection prevents partial spawn snapshots. First-exit gate prevents crash notice racing observation; readiness is emitted after stdin closure. Each daemon stays alive through release; fixture teardown owns process termination.
- [Threat model] Relay drop/reorder cannot make delivery assertions pass without IDs plus completed child evidence. Rooted-device key theft, hostile frame decoding and UI accessibility leakage remain with existing production security boundaries; this test introduces no changes to them. Unauthorized local control requests fail before mutation.

**Reviewer:** builder (self-review per builder/security-review.md)
**Date:** 2026-10-07

## Revisions

- 2026-10-07: concrete daemon status exposes `started_at` for the lifetime of `Runner.Run` and a monotonic child restart count. Pair these with the unchanged daemon PID and persisted bound session to prove continuity without adding daemon instrumentation. The deterministic child uses fakeclaude's existing stream-json echo and per-child stdin transcript; the observed assistant delta followed by idle supplies completion, while the input transcript independently counts prompt markers. No new fixture stream format or real-Claude call is needed.

- 2026-10-07: the first device run exposed a test selector error before sending: pairing text and click actions exist together in merged semantics. Use merged waits for controls and unmerged waits only for assistant prose inside its bubble; this preserves the existing helper pattern and avoids a false timeout.

- 2026-10-07: the dropped device arm confirmed the daemon contract's documented ordering: the 3s give-up can publish `session.blocked` before `session.child_crashing`. Observe blocked copy and empty backlog immediately after blocked, then require the crash notice before release. Waiting for crash first misses the already-rendered blocked pill; both wire signals remain mandatory and neither assertion is weakened.

- 2026-10-07: measured scope remains below 1000 written lines including the plan and all tests, with one new Kotlin test helper and two wrapper consumers. The fixture isolation test also proves inherited selector/give-up/replay input is removed, only the dropped private daemon receives 3s, and scripted children receive no Claude credential.

- 2026-10-07: the dropped arm retains the shipped optimistic message text after the queue drains (`ThreadProjection.settleQueuedEchoes`). Assert absence of its queued state, matching the empty-backlog contract, rather than adding a text-removal requirement. Delivery frames and actual child prompt counts remain the independent proof of non-delivery and non-replay; an optimistic row is not delivery evidence.

- 2026-10-07: verifier finding 1 exposed that the substring matcher accepts either finished user prompt because both roles share bubble tags and click semantics. Both arms now use `sessionErrorReplyMatcher` to require the exact standalone `recovered1731` reply inside a finished bubble. The deterministic recovery child consumes a case-private first-turn replay fixture through fakeclaude's existing control, returning that reply rather than echoing the prompt; the live prompt already requests the same reply. `SessionErrorReplyMatcherTest` mounts the real clickable user bubbles and first proves zero matches with both prompts alone, then exactly one displayed match when the assistant is added. Python fixture coverage checks the private replay path, permissions, assistant content and success result. Rework must run both the focused scripted scenario and the named live method; full dispatcher live/scripted-all evidence remains separate.
