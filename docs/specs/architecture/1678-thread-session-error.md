# Thread session errors and local send settlement

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `isStalled`, `sendInLocalWindow`, `closeLocalSendWindow` and the unconditional turn-state/availability collectors establish the observation and generation contracts.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt`: `PyryNavHost` collects destination-owned signals and passes them to the stateless screen.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadScreen` hosts the overlay independently of `ThreadStatusArea`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlay.kt`: `ThreadTopOverlay` orders usage, MCP and pairing/offline notices.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/NoticePill.kt`: `NoticePill` already provides the required inert Error variant and theme tokens.
- `app/src/main/res/values/strings.xml`: existing agent-specific status resources establish `_codex` naming.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionErrorProjection.kt`: `observe`, `clear` and `reset` hold only a conversation-scoped code and discard daemon prose.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `onInbound` routes errors; both `sendMessage` overloads clear the held code before sending.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt`: `observeSessionError` switches delegates and supplies null while disconnected.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelLocalSendTest.kt`: `GatedRepository` separates send completion from turn-state arrival.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt`: `start` wires the real repository, ViewModel and screen; `pushEnvelope` keeps scenario scripting with its consumer.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedLocalSendTest.kt`: correlated acknowledgements prove Sending/Waiting through the graph.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlayTest.kt`: existing notices, touch geometry and stacking coverage must remain green.
- `docs/knowledge/features/thread-screen.md`, `thread-top-overlay.md`, `notice-pill.md` and `remote-conversation-repository.md`: notices overlay messages without displacing turn status; reuse the common pill and host-owned repository.
- `docs/knowledge/features/development-verification-gates.md`: screen tests belong in sharedTest and need AndroidJUnit4; native graphics are needed for exact text/pixel assertions.
- Sibling `pyrycode/docs/protocol-mobile.md`, Application message types, Error codes and Security model: canonical frame and terminal versus retained-backlog semantics.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6619

Inspected the design context and screenshot on 2026-10-04. The existing Error pill uses `errorContainer`/`error`, right-aligned `bodySmall` text, 8dp horizontal and 4dp vertical padding, and 6dp corners. Reuse `NoticePill` in the overlay's right-aligned 12dp-gap stack, omitting the optional X and any leading icon as the ticket specifies; the text may wrap to fit the fixed client-owned sentences.

## Context

The daemon can abandon queued delivery or keep restarting a failing child while the local send window remains open indefinitely. #1677 has landed and exposes the latest nullable error code without daemon prose. #1641 has also landed: the window now progresses Sending → Waiting, with a generation guard already preventing late acknowledgements from reviving a closed window.

This is one deliverable: explain a daemon session failure and settle its local send indicator. Estimated total written work is approximately 650–1000 lines including tests and this plan, with five production files, no new exported production types, two live graph call sites to wire plus one internal overlay call, five acceptance criteria and no new rejection state machine. All sizing boundaries hold. The codegraph index omitted live Compose callers; repository search supplies their concrete locations and confirms default parameters preserve the other callers.

Overlapping branches #1631 and #1642 touch the screen or ViewModel but change separate app-bar/queue/settings blocks. Keep edits additive and local; neither is a dependency. Outcome/failure notice moves #1603/#1604 are absent from this tree and remain outside this ticket.

## Design

Expose `ThreadViewModel.sessionError: StateFlow<String?>` sourced exclusively from `repository.observeSessionError(conversationId)`. Before publishing each non-null reading, close the local send window using `closeLocalSendWindow("session_error")`. This increments the existing generation, so an acknowledgement for that closed send cannot advance it to Waiting or alter a later send's window.

Collect the signal in `PyryNavHost` with the other destination flows. Add a nullable defaulted `sessionError` parameter to `ThreadScreen`; pass it and `state.agent` to `ThreadTopOverlay`. No UiState/Event restructuring or new dependency is needed. Add the pill after existing persistent usage/MCP/pairing/offline notices, leaving a future transient failure below it.

The overlay maps exact `session.blocked` and `session.child_crashing` matches to local resource ids; every other non-null code uses generic stopped-responding copy. Six resources provide the three sentences for Claude and `_codex` variants. No raw code or daemon message is rendered, incorporated into semantics, or interpolated into logs. The pill has no click, dismiss, timeout, leading icon or side effect.

## State and concurrency model

Use one eagerly started `stateIn(viewModelScope, SharingStarted.Eagerly, null)` over the repository observation with the closure in `onEach`. Eager ownership is essential: a subscriber-bound collector would miss the required closure while the screen is stopped. Both window writes and acknowledgement completion run in the Main-bound ViewModel scope, with no suspension inside the generation check or closure. ViewModel clearing cancels observation; screen lifecycle controls only rendering.

Repository clearing remains authoritative: same-conversation sends, decoded non-idle turn states and reconnect clear the pill; idle and unrelated frames do not. A new send starts a fresh generation. Existing failed-send, any own turn-state and availability close rules stay intact. The lifecycle driver still closes the transport on background; no socket or repository lifetime is changed.

## Error handling

This is observable state, not an I/O request, and introduces no exception or retry path. The remote repository already drops malformed frames and supplies null while disconnected. Known errors distinguish dropped backlog from retained queued messages through fixed copy; unknown codes receive generic copy. Session errors never trigger a resend or claim a queued message was delivered.

Emit content-free debug lifecycle logs for presentation with static classifications only, alongside the existing `local_send_window` close log. Never log raw codes, daemon prose, conversation ids, message contents, credentials or decrypted bytes.

## Testing strategy

Write tests first and observe their failure before production edits. Extend `ThreadViewModelLocalSendTest` with conversation-keyed held error state: current-value observation and clearing, closure without any screen/flow subscriber, errors before and after acknowledgement, unknown codes, other-conversation isolation, fresh sends and old acknowledgements while a newer window is open. Preserve and run its existing turn-state, failure, reconnect and attachment cases.

Add `ScriptedSessionErrorTest` under sharedTest using AndroidJUnit4 and the real `ScriptedThreadHarness`. Keep session-error and acknowledgement envelope builders private to this test. Drive both agents through known and hostile unknown codes, assert exact client-owned copy and absence of raw code/prose and dismiss/click actions, verify conversation isolation, persistence without timeout, and clear on own send/non-idle turn state. Drive errors both before and after ack; use an inbound barrier before late-ack assertions and prove the Sending/Waiting labels disappear.

Extend `ThreadTopOverlayTest` to assert existing persistent notices remain above the session pill, inert semantics, and Error theme colors. Run existing overlay, local-send, status-arm, chrome and re-pair screen coverage. Compile sharedTest for Android as well. No device-only test or scripted daemon scenario is changed in this ticket.

File and link a Mobile rung-3 follow-up naming one `InteractiveStreamE2ETest` method for real daemon → live relay → phone error/recovery. Explicitly identify the absent reproducible daemon failure trigger and daemon-owned test-control prerequisite; this ticket claims rung-2 proof only. The follow-up owns live execution and any deterministic daemon twin once the trigger exists.

Run focused `testDebugUnitTest`, lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply and forced spotlessCheck. Compare the reused pill tokens and absence of optional decorations against the inspected Figma reference.

## Open Questions

None. The current tree settles the #1641 integration order, and the ticket supplies exact copy and clearing semantics. The rung-3 follow-up issue number will be recorded in the PR after filing.

## Security review

**Verdict:** PASS

- [Trust boundaries] SHOULD FIX: hostile codes may contain prose or control characters. Exact matching in the overlay must select only the six client resources; unknown codes get the generic resource. Graph tests inject hostile code and message strings and assert neither reaches UI semantics.
- [Tokens, secrets and credentials] No new credential flow: the ViewModel receives only a code. Pairing keys, revocation and Keystore storage stay with their existing owners; no token reaches new logs.
- [Files and storage] No new persistence or path construction. Error and send-window state are transient heap state; no backup, cache-key or attachment storage change.
- [Android attack surface] The inert pill adds no intent, exported component, provider, deep link or WebView; its semantics contain only client copy.
- [Cryptography] No handshake, key, nonce, random generation or secret comparison changes; vendored Noise remains the authenticated transport.
- [Network and I/O] No new outbound operation or parsing. #1677's capability-gated decoder owns malformed frames and discards prose. Existing framing caps, TLS, timeouts and reconnect backoff remain intact.
- [Errors, logs and telemetry] SHOULD FIX: log presentation/closure using static event names and classifications only. Never interpolate raw code, message text, daemon prose, tokens, keys or decrypted payload. Debug logging stays with RelayLog.
- [Concurrency] SHOULD FIX: eager scope ownership and the existing generation increment must precede state publication; tests must cover no subscribers and an error before acknowledgement, including an old acknowledgement during a newer send. Cancellation belongs to viewModelScope; no new mutex or dispatcher.
- [Threat model] Malicious relay delay/drop is handled by existing connection supervision without plaintext leakage; this feature never retries. Hostile daemon frames select fixed copy through the exact-match mapping. Rooted-device credential theft and UI leakage during token entry remain under the existing Keystore/pairing controls; the new screenshot/accessibility content contains no daemon bytes or secrets. Protocol prompt-injection, server-id races, replay, static-key compromise and rate limiting introduce no new surface in this UI observation.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-04
