# #915 — Move sending, uploads, snapshots and debug bundles into MessageCommands

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → the source of the move: `sendMessage` (both overloads), `uploadAttachment`, `requestScreenSnapshot`, `dropQueuedMessage`; the upload transfer (`uploadLock`, `activeUpload`, `uploadInboundEnded`, `beginUpload`, `finishUpload`, `endAttachmentUploads`, `routeAttachmentUpload`); the debug bundle transfer (`debugBundle`, `bundleInboundEnded`, `requestDebugBundle`, `endDebugBundle`, `routeDebugBundle`). The re-pointed sites: the `init` collector's `finally` and the first two lines of `onInbound`. The companion constants `TYPE_SEND_MESSAGE`, `TYPE_ATTACHMENT_CHUNK`, `TYPE_REQUEST_SNAPSHOT`, `TYPE_DEQUEUE_MESSAGE` stay there.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationCommands.kt` → `ConversationCommands`, the #914 shape this follows: `internal class`, one instance per repository, `requests: RelayRequests` + `send: (Envelope) -> Boolean` constructor, header KDoc naming what it owns, companion constants imported as `RemoteConversationRepository.Companion.TYPE_…`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRequests.kt` → `nextRequestId`, `sendAndAwaitReply`, the only request plumbing the moved code uses.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadProjection.kt` → `appendMessages`, `recordMinted`, `recordDrop`, `withdrawDrop`, the thread writes `sendMessage` and `dropQueuedMessage` make.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationListProjection.kt` → `recordLastMessage`, `sendMessage`'s preview write.
- `app/src/main/java/de/pyryco/mobile/data/repository/QueueProjection.kt` → `current`, which `dropQueuedMessage` reads to resolve the echo id before the send.
- `app/src/main/java/de/pyryco/mobile/data/repository/AttachmentUpload.kt`, `DebugBundleTransfer.kt` → `AttachmentUploadTransfer`, `AttachmentUploadLimit`, `DebugBundleTransfer`: the per-transfer types the moved state holds; unchanged.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` → calls the repository's `internal` `requestDebugBundle()` and `endDebugBundle()`; unchanged.
- `app/src/test/java/de/pyryco/mobile/data/repository/DebugBundleTransferTest.kt`, `RemoteConversationRepositoryAttachmentTest.kt`, `RemoteConversationRepositoryTest.kt` → reach every moved path through the repository surface.
- `docs/specs/architecture/914-relay-requests-conversation-commands.md` → the previous move in this family.

In-flight check: no remote `feature/*` branch touches `RemoteConversationRepository.kt` or a `MessageCommands` file.

## Design source

N/A: data-layer refactor with no UI.

## Context

`RemoteConversationRepository` still carries the message sends and the two chunked transfers, each with its own `@Synchronized` state. This ticket moves them into `MessageCommands`, so a ticket about sending or attachments edits neither the routing nor the repository. It is a move: behaviour, names, KDoc and ordering stay. No ADR is needed. #916 continues the split on the same class.

## Design

`MessageCommands` (`MessageCommands.kt`), `internal`, in `data/repository/`, constructed once in the repository's property list, so its state stays connection-scoped (one repository per connection, #351).

Constructor: `MessageCommands(requests: RelayRequests, send: (Envelope) -> Boolean, conversationList: ConversationListProjection, threadProjection: ThreadProjection, queueProjection: QueueProjection)`; the repository passes `pump::send`.

| Member | Visibility | Notes |
|---|---|---|
| `sendMessage(conversationId, text)` / `sendMessage(conversationId, text, attachmentIds)` | public `suspend` | full KDoc moves; the two-arg overload still delegates to the three-arg one with `emptyList()` |
| `uploadAttachment(…)` | public `suspend` | body verbatim under `uploadLock.withLock` |
| `requestScreenSnapshot(conversationId)` | public `suspend` | verbatim |
| `dropQueuedMessage(conversationId, queuedMessageId)` | public `suspend` | verbatim, including resolve-then-record-then-send order |
| `debugBundle`, `bundleInboundEnded` | private `var` | moved |
| `requestDebugBundle()`, `endDebugBundle()` | public, `@Synchronized` | moved |
| `routeDebugBundle(envelope)` | public, `@Synchronized` | was private; the repository's `onInbound` calls it |
| `uploadLock`, `activeUpload`, `uploadInboundEnded` | private | moved |
| `beginUpload`, `finishUpload` | private, `@Synchronized` | moved |
| `endAttachmentUploads()`, `routeAttachmentUpload(envelope)` | public, `@Synchronized` | were private; the repository calls them |

Bodies move unchanged apart from `relayRequests.` → `requests.`, `pump.send` → `send`, and `conversationListProjection` → `conversationList`. Every `try { send(…) } catch (_: Exception) { false }` stays as written. Every `RelayLog` line moves verbatim; nothing new logs.

### Repository wiring and hand-offs

`messageCommands` replaces the debug-bundle and upload state block, declared just after `conversationCommands`, so every dependency it names is already initialised. Its KDoc names what it owns, as `conversationCommands`' does.

- `onInbound` keeps its first two lines in order: `if (messageCommands.routeDebugBundle(envelope)) return`, then `if (messageCommands.routeAttachmentUpload(envelope)) return`, ahead of `recordReplayCursor` and the demux.
- The `init` collector's `finally` keeps its order: `endDebugBundle()` (the repository hand-off), `messageCommands.endAttachmentUploads()`, `relayRequests.failAllPending()`.
- Each moved public function stays as a one-line hand-off with a one-line KDoc naming its owner, in its current position (e.g. `override suspend fun requestScreenSnapshot(conversationId: String): String = messageCommands.requestScreenSnapshot(conversationId)`). `internal fun requestDebugBundle()` and `internal fun endDebugBundle()` stay on the repository as hand-offs for `RelayRepositoryCoordinator` and `DebugBundleTransferTest`.

KDoc links elsewhere (`ThreadProjection`, `QueueProjection`, `ConversationListProjection`, `ModelMenuProjection`, `ConversationCommands`) name the repository's public or `internal` functions, which still exist as hand-offs, so they stay. Imports the move leaves unused in the repository go.

## State + concurrency model

Unchanged. The upload and debug-bundle state is still guarded by one monitor per connection, now `MessageCommands`' instance rather than the repository's; nothing else took the repository's monitor (these were its only `@Synchronized` members), so no lock is split or merged. `uploadLock` is still one `Mutex` per connection held from the first chunk until the upload settles. The routes run on the single inbound collector; the endings run in its `finally`; `requestDebugBundle` and `uploadAttachment` run on the caller. No new coroutine or scope.

## Error handling

Unchanged. `sendMessage` still rethrows the attachment-bound `IllegalArgumentException` before taking a request id, and inserts only after the ack. `uploadAttachment` never throws except on cancellation. `requestScreenSnapshot` decodes caller-side after the await. `dropQueuedMessage` withdraws its pending drop and throws the not-connected `IllegalStateException` when the send fails. A malformed transfer frame is still handled by the transfer's own `accept`, never thrown into the collector.

## Testing strategy

The existing tests reach every moved path through the repository surface: `RemoteConversationRepositoryTest` (send, snapshot, dequeue and the drop-settle ledger), `RemoteConversationRepositoryAttachmentTest` (chunking, one-at-a-time, refusal, disconnect), `DebugBundleTransferTest` (request, busy/reconnect/unavailable, malformed frames, teardown), `RelayRepositoryCoordinatorTest` and `StableConversationRepositoryTest` through the facade. No assertion changes; a green `testDebugUnitTest --tests "de.pyryco.mobile.data.repository.*"` is the proof, then `lint` and `assembleDebug`. No new test class: `MessageCommands` exposes no behaviour the repository tests do not already reach.

## Documentation handoff

Pending for the documentation stage: the feature overviews under `docs/knowledge/features/` that describe these functions or transfers name `MessageCommands` as their owner, in the place each describes them — at least `remote-conversation-repository-conversation-writes.md`, and also `remote-conversation-repository-send-create-promote-rename.md` (`sendMessage`), `remote-conversation-repository-control-sends.md` (`requestScreenSnapshot`, `dropQueuedMessage`), `remote-conversation-repository-state-errors-and-handoff.md` (the debug bundle retention and `endDebugBundle`), `attachment-upload.md` (`uploadLock` and `routeAttachmentUpload`) and `relay-debug-bundle-transfer.md`.

## Open questions

- None.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. Inbound transfer frames still cross into the process at the same two gates, `routeDebugBundle` then `routeAttachmentUpload`, called first in `onInbound`; each hands the envelope to the transfer's own `accept` (`DebugBundleTransfer`, `AttachmentUploadTransfer`), which is unchanged and decides correlation and validity. The snapshot reply is still decoded once through `ScreenSnapshotPayloadDto` caller-side after the await, so a malformed reply throws on the caller's coroutine, never the collector. `dropQueuedMessage` still resolves the echo id from this connection's own `QueueProjection` snapshot and removes a thread row only against the minted-id ledger, so another device's message cannot be removed.
- [Tokens, secrets, credentials] No findings. No token, key or pairing material is touched. The attachment id is a `UUID.randomUUID()` (a `SecureRandom`-backed v4), as before; it is an identifier, not a secret, and was already logged.
- [File / storage] No findings. The upload's bytes arrive already in memory from the caller; the debug bundle's bytes stay inside `DebugBundleTransfer`, whose save path is unchanged UI code. Nothing moved touches the filesystem.
- [Inter-process / Android surface] No findings. No manifest, intent, push or WebView change; `MessageCommands` is `internal` with no Android import, keeping `data/` portable.
- [Cryptographic primitives] No findings. No crypto is touched; request ids remain correlation counters from the one `RelayRequests` counter.
- [Network & I/O] No findings. Chunk size, the upload size limit (`AttachmentUploadLimit.fits` before the lock) and the debug-bundle cap live in unchanged types. There is still one counter per connection, so a chunk id, a bundle request id and a reply waiter can never collide. The upload stays single-flight under `uploadLock`, so the socket's send queue never carries two uploads.
- [Logs] No findings. The three `RelayLog` lines move verbatim: the attachment count on `send_message`, and the attachment id, chunk index, total and outcome class on uploads. No text, bytes, filename, digest, MIME type, snapshot text, queued id or message id is logged, as today.
- [Concurrency] No findings. Every accessor of the two transfers' state keeps `@Synchronized`; the monitor becomes the `MessageCommands` instance, which only these accessors take, so mutual exclusion among them is exactly as before and no other repository code shared that monitor. The teardown order in the collector's `finally` (debug bundle, uploads, pending requests) is preserved, and the `ended` flags still make a late `requestDebugBundle` or `beginUpload` reject rather than register a transfer nothing will end.
- [Threat model] No findings. A hostile relay can still only drop, delay or reorder; a stalled transfer is still released by the connection teardown. A hostile daemon frame is still dropped by the transfer's `accept` without ending the single inbound collector. Behaviour is unchanged, so no new threat is introduced.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
