# Host-scoped diagnostic archive transfer

## Context

#682 delivers one data-layer API for downloading the caller-selected host's opaque
diagnostic archive. #683 owns the native action and saving. The contract is the
daemon's `docs/protocol-mobile.md`, **Debug bundle (v2)**; no wire contract is
invented here. No UI, dependency, crypto or persistence changes are needed.

## Files read

- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` — `connectionFor`, `reconcile`: exact identity and removal ownership.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionFactory.kt` — `RelayConnectionBundle.close`: permanent per-host teardown.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` — `onConnection`, `teardownActive`: per-socket scope, pump and repository ownership.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` — `onInbound`, `requestId`, `failAllPending`: sole inbound consumer and request correlation.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionPump.kt` — `SessionPump.send`: synchronous Boolean enqueue contract.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt` — `Envelope`: required generic payload today.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt` — `MobileJson`, `base64StdDecode`: default encoding and standard alphabet.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayLog.kt` — `RelayLog.d`: debug-only injectable logging.
- `app/src/test/java/de/pyryco/mobile/di/RelayConnectionFactoryTest.kt` — `Fixture`, `PeerTransport`: real Noise two-host test fixture.
- `app/src/test/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinatorTest.kt` — `FakeManagedPump`: deterministic lifetime testing pattern.
- `docs/knowledge/features/relay-repository-coordinator.md` — Configuration and scope ownership: selection must never redirect a one-shot action.
- `docs/knowledge/features/remote-conversation-repository-state-errors-and-handoff.md` — State & concurrency model and Error handling: malformed frames must preserve the single consumer.
- `docs/knowledge/features/mobile-protocol-v2-wire-layer.md` — Envelope and codec: preserve generic non-null payload consumers.
- `docs/knowledge/features/development-verification.md` — JVM logging and scoped Gradle evidence.
- Sibling desktop `src/main/transport/bundleReassembler.ts` and `src/main/debugBundleDownload.ts` — terminal-once reassembly and separation from saving.

## Design

Add `DebugBundleTransfer.kt` under `data/repository`, containing five types:
`DebugBundleTransfer`, `DebugBundleState`, `DebugBundleStatus`, `DebugBundleRetry`,
and `DebugBundleArchive`. A transfer exposes a hot read-only state flow containing
accepted chunk count, static status, and retry condition. `takeArchive()` hands off
the completed archive once; state never contains bytes. The archive streams its
ordered bytes to a caller-owned output stream without unpacking or inspecting them.
The save owner handles output-stream failures in #683.

`RelayConnectionRegistry.requestDebugBundle(serverId)` resolves `connectionFor`
under the existing registry lock. The coordinator admits only its current Open
pump on the still-current transport. The concrete repository reserves the transfer
before sending using its existing request-id allocator. Unknown, removed,
handshaking or disconnected hosts return UNAVAILABLE; there is no selection fallback
or capability gate. A second request returns BUSY without replacing the first.

Make `Envelope.payload` default to `JsonNull`, annotated `EncodeDefault.NEVER`:
omitted payloads remain non-null generic JSON to existing consumers, while actual
object/string payloads keep their serialized representation. No consumer migration.
The new request supplies only id, type and timestamp.

`onInbound` offers bundle chunk/done frames and bundle-correlated errors to the
transfer before existing handlers. Strict primitive type checks reject malformed
integer/string fields; standard-base64 decoding plus canonical re-encoding rejects
unpadded/nonstandard encodings. Only accepted contiguous chunks advance progress.
Only matching completion transfers ownership to the opaque archive. Failure clears
and wipes partial chunks; late frames are inert and ordinary events keep routing.

## State + concurrency model

Use synchronized, non-suspending admission and terminal transitions. The repository
retains at most one transfer for its connection lifetime. Every attempted send
consumes that lifetime, including success, refusal and send failure: chunk/done
frames lack correlation, so allowing any same-connection retry would risk accepting
late data. Retry is explicit: WHEN_AVAILABLE for unavailable, AFTER_TRANSFER for
busy (then re-evaluate), and AFTER_RECONNECT for settled attempts. No automatic retry.

The existing connection scope and injected dispatcher own the only inbound job.
Its finally block settles incomplete transfers and permanently disables admission.
Coordinator teardown settles synchronously before scope cancellation and pump close;
new repositories on reconnect start empty and never replay the request. Host removal
uses this same teardown. No new coroutine jobs, screen state, or timeout is added.

## Error handling

Static statuses: UNAVAILABLE, BUSY, RECONNECT_REQUIRED, SEND_FAILED, REFUSED,
INVALID_STREAM and DISCONNECTED, alongside RECEIVING and COMPLETE. No exception,
daemon code/message, host identifier or content is carried into transfer state.
Every classified failure and start/completion emits only static event/status and
accepted-count fields through `RelayLog.d`. Correlated refusal checks `in_reply_to`
without decoding the error body; unrelated errors retain existing routing.

## Testing strategy

- Focused JVM tests first prove missing payload omission, then receiver APIs (RED before production changes).
- Cover zero/one/multiple chunks, exact opaque bytes, progress, malformed field/base64 tables, gaps/duplicates/reordering/count mismatch, and terminal-once behavior.
- Cover correlated versus unrelated errors, ordinary events after malformed frames, false/throwing sends, incomplete inbound completion/exception/cancellation, and fresh-connection retry isolation.
- Extend the real Noise registry fixture for two independent hosts, selection changes, exact-id rejection, removal and serialized request omission.
- Run touched JVM classes, Spotless, lint and assembleDebug. No UI/device or real-Claude scenario: this is the data-layer prerequisite for #683.

## Sizing and overlap

One deliverable; estimate approximately 740 written lines (270 production, 380 tests,
90 plan), five production files, five new types, zero required consumer updates,
four acceptance criteria and ten reject branches. This is within the refiner's
780-line forecast. Codegraph found entry points but no useful Envelope callers;
source inspection confirms the additive default needs no caller edits. Refreshed
remote feature branches have no overlap with the intended files.

## Open questions

None. A retry always requires a fresh connection after an attempted send; no
arbitrary timeout or undocumented archive-size limit is introduced.

## Documentation handoff

Pending documentation stage: describe the host-scoped transfer API and retry lifetime
in `docs/knowledge/features/relay-repository-coordinator.md` under Configuration,
linking the daemon's Debug bundle contract.

## Security review

**Verdict:** PASS

- Trust boundaries: `DebugBundleTransfer.accept` validates untrusted frame fields before accumulation. Completed bytes remain opaque and never enter UI state or filenames.
- Tokens/secrets: no credential generation or storage changes. Archives may contain secrets; partial bytes are wiped on failure, and archive/state string representations reveal no content.
- Files/storage: no filesystem operation, path construction, archive extraction or persistence. Save destination and storage lifecycle belong to #683.
- Android surface: no exported component, intent, provider, WebView or daemon-authored UI text.
- Cryptography: existing paired Open Noise pump is required; no handshake, key, nonce, TLS or pairing changes.
- Network/I/O: existing per-frame cap and supervisor backoff remain. Total archive memory follows received bytes, never claimed seq/total; no allocation from daemon counts. Large aggregate archives remain a memory limitation shared with the current desktop receiver; resource policy is not invented here.
- Logs/errors: debug-gated static status/count logs only. Refusal and send exceptions are discarded without stringification; tests capture a content sentinel.
- Concurrency: admission, frame acceptance and teardown settle once under locks. No suspensions under locks; lock order is registry, coordinator, repository, transfer. Fresh repository identity fences all retry data.
- Threat model: the relay remains content-blind through Noise; malformed authenticated daemon frames fail transfer without killing ordinary routing. Rooted-device memory access remains outside this transfer API; screen/save exposure belongs to #683.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21

## Revisions

- 2026-09-21: implementation keeps the current transport identity in the coordinator's
  private `Connection`, so a request during a pending connection-state emission
  returns unavailable immediately. Tests cover this gap and non-interactive Noise
  sessions. The five production files and five-type boundary remain unchanged.
