# #1564 — Message trail: each sent message's states, kept in release builds

## Files read

- `data/repository/MessageCommands.kt` — `sendMessage` mints the `message_id`, draws the echo and awaits the reply through `RelayRequests.sendAndAwaitReply`; `dropQueuedMessage` records a drop. The sent, acknowledged and failed points live here.
- `data/repository/RelayRequests.kt` — `sendAndAwaitReply` does the send and the await together; `failAllPending` completes every waiter with `IllegalStateException(PENDING_REQUEST_TORN_DOWN)`. Needs a hook between the send and the await.
- `data/repository/ThreadProjection.kt` — `recordMinted` (the ledger of ids this device sent), `settleQueuedEchoes` (queued and drained echoes, #1558), `appendLiveMessage` (the pushed `message` that delivers an echo), `settleDrops` / `removeOwnEcho` (a confirmed user drop). The queued, delivered and dropped points live here, keyed on the minted ledger.
- `data/repository/RemoteConversationRepository.kt` — constructs `ThreadProjection`, `RelayRequests` and `MessageCommands`; the `TYPE_QUEUE_STATE` arm runs `settleDrops` then `settleQueuedEchoes`; the collector's `finally` runs `failAllPending`.
- `data/repository/RelayRepositoryCoordinator.kt` — `onConnection` builds one repository per connection while the pump is still `Handshaking`, the shape `negotiatedCapabilities` already handles with a supplier over `pump.state`; `PumpState.Open.connId` is otherwise discarded.
- `di/RelayConnectionFactory.kt` — `RelayConnectionBundle` builds the coordinator; the factory is the `AppModule` single.
- `di/AppModule.kt` — where `noBackupFilesDir` stores are bound; the trail binds here with `getExternalFilesDir`.
- `data/network/RelayLog.kt` — `redactConnId`, and the `BuildConfig.DEBUG` gate this ticket must leave alone.
- `docs/knowledge/features/relay-log.md` — the must-not-log list and why the redacted token is 8 hex characters. Lesson carried: R8 keeps `Log.i`, and `proguard-rules.pro` has no `-assumenosideeffects` for `Log`, so a `Log.i` sink reaches release logcat.
- `docs/knowledge/features/queued-backlog.md` and the #1558 spec — the minted-ledger rule: only an id this connection minted may be acted on.

No in-flight `feature/*` branch touches these files (checked 2026-10-03).

## Context

The daemon can prove only that a message never arrived. When a message goes missing on the Play test-track release build, nothing on the phone says whether it was sent, acknowledged, queued, or lost to a torn-down connection. Juhana decided on 2026-10-02 that the trail must survive in release, so `RelayLog` (debug-gated by construction) cannot carry it. This adds a second, release-kept facility whose inputs are restricted to a message id, a redacted connection token, a fixed state, a fixed reason and a sanitised daemon error code.

A decision record is warranted ("a release-kept diagnostic channel beside the debug-only `RelayLog`, and why it cannot take free text"); the documentation stage should consider one.

## Design

### `data/diagnostics/MessageTrail.kt` (new, one top-level class)

```kotlin
class MessageTrail(
    file: () -> File? = { null },            // resolved once, on the writer
    writerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val now: () -> Instant = Clock.System::now,
    private val logcat: (String) -> Unit = {},
) {
    enum class Failure { NOT_CONNECTED, DAEMON_ERROR, TORN_DOWN }
    fun sent(messageId: String, connToken: String?)
    fun acknowledged(messageId: String)
    fun queued(messageId: String)
    fun delivered(messageId: String)
    fun failed(messageId: String, failure: Failure, errorCode: String? = null)
    fun dropped(messageId: String)          // reason=user_dropped
    fun dispose()
    companion object { const val FILE_NAME = "message-trail.log"; const val LOG_TAG = "PyryMessageTrail"; DEFAULT_MAX_BYTES = 512 KiB }
}
```

- **Fixed inputs only.** No method takes text. `messageId` must match the lowercase UUID shape this app mints (`UUID.randomUUID().toString()`); anything else records nothing. `connToken` must match `^[0-9a-f]{8}$` (the `redactConnId` shape) or the line says `conn=none`. `errorCode` must match `^[a-z0-9_.]{1,64}$` or the line says `code=unknown`. State and reason are internal enums rendered as fixed lowercase labels.
- **Line format**, one per state: `<ISO-8601 instant> id=<uuid> state=<state>[ conn=<token>][ reason=<reason>][ code=<code>]`. States: `sent`, `acknowledged`, `queued`, `delivered`, `failed`, `dropped`. Reasons: `not_connected`, `daemon_error`, `torn_down`, `user_dropped`.
- **Once per state.** A `@Synchronized` check against a bounded `LinkedHashMap<messageId, Set<State>>` (eldest evicted past 256 ids) drops a repeat of a state already recorded for that id. The same synchronized block hands the line to `logcat` and to the writer channel, so the file order is the record order.
- **No build-type gate.** Nothing in the class reads `BuildConfig`. `RelayLog` is untouched.
- **Bounded file.** A `Channel<String>(1024, DROP_OLDEST)` feeds one writer coroutine on `writerDispatcher`, in a scope the trail owns and `dispose()` cancels. The writer resolves `file()` once; `null` means logcat only. Before an append that would pass `maxBytes`, it keeps the newest whole lines that fit in half the cap, writes them to `<name>.tmp` and renames it over the file, then appends. Every write is inside `try/catch (IOException|SecurityException)`; a failure drops that line and the writer carries on.

### Hooks

- `RelayRequests.sendAndAwaitReply(request, onSent: () -> Unit = {})` — `onSent` runs after `send` returned `true` and before the await. Every other caller is unchanged.
- `MessageCommands(…, trail: MessageTrail, connToken: () -> String?)` — `sendMessage` passes `onSent = { sent = true; trail.sent(messageId, connToken()) }`. On return, `trail.acknowledged`. On a thrown exception other than `CancellationException`: not sent → `NOT_CONNECTED`; `RelayErrorException` → `DAEMON_ERROR` with its `code`; `IllegalArgumentException` (the mapped `conversation.not_found`) → `DAEMON_ERROR` with `ERROR_CONVERSATION_NOT_FOUND`; any other after the send → `TORN_DOWN`. Then rethrow unchanged. The too-many-attachments refusal mints no id and records nothing.
- `ThreadProjection(trail: MessageTrail = MessageTrail())` —
  - `settleQueuedEchoes(conversationId, …)`: every id in the new `OwnEchoQueue.queued` → `queued`; every drained id still in the minted ledger → `delivered` (a dropped id has already left the ledger in `removeOwnEcho`).
  - `appendLiveMessage`: a pushed `message` whose id is in the minted ledger → `delivered`.
  - `removeOwnEcho`, after its minted check passes → `dropped`.
- `RemoteConversationRepository(…, messageTrail: MessageTrail = MessageTrail(), connToken: () -> String? = { null })` — passes the trail to `ThreadProjection` and `MessageCommands`.
- `RelayRepositoryCoordinator(…, messageTrail: MessageTrail = MessageTrail())` — `onConnection` passes `messageTrail` and `connToken = { (pump.state.value as? PumpState.Open)?.connId?.let(RelayLog::redactConnId) }`, the `negotiatedCapabilities` supplier shape. Only the 8-hex token leaves the coordinator.
- `RelayConnectionFactory(…, messageTrail: MessageTrail = MessageTrail())` → `RelayConnectionBundle` → coordinator.
- `AppModule`: `single { MessageTrail(file = { androidContext().getExternalFilesDir(null)?.let { File(it, MessageTrail.FILE_NAME) } }, logcat = { Log.i(MessageTrail.LOG_TAG, it) }) } onClose { it?.dispose() }`, and `messageTrail = get()` on the factory. `data/` stays free of `Context` and `android.*`.

Operator retrieval: `adb pull /sdcard/Android/data/de.pyryco.mobile/files/message-trail.log`. Confirmed on the managed SDK 33 emulators that `shell` (uid 2000) is in group `ext_data_rw` (1078) and app-specific external dirs are `drwxrws--- <app> ext_data_rw`, so the shell user can read files the app creates there.

## State and concurrency model

- `record` paths run on the caller's coroutine (send) and the inbound collector (projection). They are non-suspending: a synchronized map check, a `logcat` call and a `trySend` that cannot fail on a `DROP_OLDEST` channel. No file I/O on the send path, so a slow or failing disk cannot delay or reorder a send.
- One writer coroutine per trail, on `Dispatchers.IO` in production, owned by the trail's own `SupervisorJob` scope and cancelled by `dispose()` (Koin `onClose`). One process-wide trail, so the file has one writer.
- The connection token is read lazily at the send, after `Open`; the pump never revisits `Handshaking`.
- A caller cancelled mid-await (the view model's scope dying) records no failed line: the last line stays `sent`. Teardown on background goes through `failAllPending`, which is not cancellation, so it records `torn_down`.

## Error handling

- File unavailable (`getExternalFilesDir` null, unmounted storage, a directory in the way, a full disk): the line still reaches logcat; the file write is skipped; nothing propagates.
- Invalid id: no line. Invalid token or code: a fixed placeholder.
- `sendMessage` keeps its exact exception contract; the trail only observes.

## Testing strategy

- `app/src/test/.../data/diagnostics/MessageTrailTest.kt` (temp files, `StandardTestDispatcher`):
  - each state writes one timestamped line in the documented format; a repeat of a state writes nothing.
  - append after restart: a second trail on the same file appends below the first trail's lines.
  - the cap: after many records the file is at most `maxBytes`, ends with the newest line and no longer holds the oldest.
  - unwritable target (a directory at the path): records still reach logcat and nothing throws.
  - non-UUID id records nothing; a hostile conn token and error code render as `none` / `unknown`.
  - emits with `RelayLog.enabled = false`, and `RelayLog.enabled` still equals `BuildConfig.DEBUG`; the class runs under `testReleaseUnitTest` (where `BuildConfig.DEBUG` is false) in `./gradlew check`.
- `app/src/test/.../data/repository/RemoteConversationRepositoryMessageTrailTest.kt` (fake pump, real trail with a temp file):
  - sent → acknowledged, sent carrying the token; not connected → failed `not_connected`; daemon `error` → failed `daemon_error code=…`, never the error message; teardown before the ack (pump inbound closes) → last line failed `torn_down`.
  - queued from a `queue_state` carrying the id, logged once across two snapshots; delivered on the drain and not again on the pushed `message`; delivered on a pushed `message` with no queue; dropped on a confirmed `dropQueuedMessage`, with no delivered line.
  - distinctive text and attachment names appear in no emitted line and nowhere in the file.
- `RelayRepositoryCoordinatorTest`: a send through the coordinator's repository records `conn=<redactConnId(connId)>` and never the raw `conn_id`.

No device test: every criterion is JVM-observable. Not operator-facing, so no rung-3 scenario.

## Open Questions

- Does `testReleaseUnitTest` compile and run in this project? If not, the release proof rests on the `RelayLog.enabled = false` test plus the absence of any `BuildConfig` read.

## Documentation handoff

Pending for the documentation stage: add a message-trail section to `docs/knowledge/features/relay-log.md` (or a new topic linked from `docs/knowledge/INDEX.md`) stating that the trail is kept in release builds and why (the 2026-10-02 decision), the states and reasons, the never-log list, and the retrieval path `adb pull /sdcard/Android/data/de.pyryco.mobile/files/message-trail.log`.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. Daemon-authored data reaches the trail at two points only: a `message_id` from `queue_state` / `message`, which is acted on only when it is in this connection's minted ledger (`ThreadProjection.mintedMessageIds`), so it is a UUID this app generated; and the `error` code, which `MessageTrail.failed` accepts only as `^[a-z0-9_.]{1,64}$`. The daemon's error message never reaches the trail.
- [Tokens] No findings. The full `conn_id` stays in the coordinator; only `RelayLog.redactConnId`'s 8-hex BLAKE2s token is passed down, and the trail rejects any other shape. No pairing token, relay host or key material is in scope of any trail input.
- [Files and storage] SHOULD FIX, addressed in the design. `getExternalFilesDir` is deliberately not app-private, which is acceptable only because the content is non-sensitive (random ids, a 32-bit correlation token, fixed labels). The shape checks in `MessageTrail` are what make that true, and must land with tests. The path is a constant name under a system directory, so there is no traversal. On SDK 33 other apps cannot read the directory (scoped storage); adb can. The file is in Auto Backup scope, which is harmless for this content; no backup rule change. A kill mid-trim leaves either the old file or the renamed new one, plus at worst a stray `.tmp` that the next trim overwrites.
- [Android attack surface] No findings. No component, intent filter or provider is added.
- [Cryptography] No findings. Reuses `redactConnId` (vendored BLAKE2s); `message_id` minting is unchanged.
- [Network and I/O] No findings. No new frame or socket path. The trail adds no I/O to the send path (`trySend` only).
- [Errors, logs, telemetry] SHOULD FIX, addressed in the design. This is a release logcat channel by decision. It must stay free of message text, attachment names, relay host, pairing token and full `conn_id`: the API has no text parameter and every string input is shape-checked; a test sends distinctive text and attachment names and asserts their absence from every line and the file. `RelayLog`'s gate is not touched.
- [Concurrency] No findings. The writer has an owning scope cancelled by `dispose()`. Record ordering is fixed by the synchronized block that also enqueues. The bounded `DROP_OLDEST` channel caps memory if the disk stalls, at the cost of losing old lines, never blocking a send.
- [Threat model] A malicious relay can drop or delay frames; the trail then records exactly what the phone saw (sent with no ack, or torn down), which is its purpose, and it cannot inject text. A hostile daemon frame cannot put text into the trail (ledger and shape checks). Token theft from disk: the file holds no secret. UI leakage: no UI.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-03
