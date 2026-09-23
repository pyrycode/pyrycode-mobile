# Conversation cache — app-private custody of one host's conversation content

The phone keeps what it has already loaded from each paired host, so a restart or an
unreachable connection does not draw a saved host empty. Everything else the app holds is
connection-scoped — `RelayConnectionRegistry` builds a `RemoteConversationRepository` per
live connection, and a host's rows exist only while that connection is live — so this cache
is the one thing that survives process death.

Package: `de.pyryco.mobile.data.cache`. The portable contract is
[`ConversationCache.kt`](../../../app/src/main/java/de/pyryco/mobile/data/cache/ConversationCache.kt);
[`FileConversationCache.kt`](../../../app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt)
is the one app-private implementation, added in
[#795](../../specs/architecture/795-app-private-conversation-cache.md). #795 shipped the storage
layer alone; [#796](https://github.com/pyrycode/pyrycode-mobile/issues/796) made
`HostConversationSource` its first production consumer and added the cache's one Koin binding —
see [dependency injection § Restore from the on-disk cache](dependency-injection.md#restore-from-the-on-disk-cache-796)
for how the source writes and seeds from it. [#797](../../specs/architecture/797-thread-row-cache.md)
adds the thread-row family and the thread restore — see
[Caching conversation repository](caching-conversation-repository.md) for the wrapper that reads and
writes it. [#798](../../specs/architecture/798-clear-cache-on-removal.md) wires `removeHost` and
`removeConversation` to unpair and to permanent deletion — see
[§ Removal on unpair](#removal-on-unpair--forgetremovedhost) below and
[Caching conversation repository § delete](caching-conversation-repository.md#delete--removing-the-cache-alongside-the-daemon-798).

## The contract

```kotlin
interface ConversationCache {
    suspend fun readConversations(serverId: String): List<Conversation>
    suspend fun writeConversations(serverId: String, conversations: List<Conversation>): Result<Unit>
    suspend fun readThread(serverId: String, conversationId: String): List<ThreadItem> = emptyList()
    suspend fun writeThread(serverId: String, conversationId: String, rows: List<ThreadItem>): Result<Unit> =
        Result.success(Unit)
    suspend fun removeHost(serverId: String): Result<Unit>
    suspend fun removeConversation(serverId: String, conversationId: String): Result<Unit>
}

const val MAX_CACHED_THREAD_ROWS = 200

fun cacheableThreadRows(rows: List<ThreadItem>): List<ThreadItem>
fun settledThreadRows(rows: List<ThreadItem>): List<ThreadItem>

class ConversationCacheException(message: String) : Exception(message)
```

**The thread-row family (#797).** `readThread`/`writeThread` follow the same graceful-read,
reporting-mutation shape as the conversation family, over `List<ThreadItem>` (`data/repository/`,
not the cache package) instead of `List<Conversation>`. Both carry a **default body on the
interface** — `emptyList()` / `Result.success(Unit)`, the precedent
`ConversationRepository.refreshSessionSettings` already sets — so a double that does not exercise
threads (`InertConversationCache`, and the fakes in `HostConversationSourceTest` and
`RelayConnectionFactoryTest`) needed no edit for this ticket. `writeThread` is a **whole-thread
replace**, like `writeConversations` is a whole-host replace, and it always stores
`cacheableThreadRows(rows)` — never the caller's raw list — so no caller can persist an
unrecognized, streaming or in-flight-tool row by constructing a `ThreadItem` list itself.

Two top-level functions in `ConversationCache.kt` define what a thread may hold, used by both the
cache (enforced on write) and [`CachingConversationRepository`](caching-conversation-repository.md)
(compared against on every emission), so the two can never disagree about what "settled" means:

- **`settledThreadRows(rows)`** drops only the in-flight rows — a `Message` with `isStreaming` or
  whose `toolCall?.status == ToolCallStatus.Running` — leaving `UnrecognizedMessage` rows and the
  row count untouched. This is what a thread may keep **drawing** once its connection is gone, not
  what the cache may **hold**: it is also the caching repository's merge-base rebase on a
  disconnect (see that doc), where the bound would otherwise shrink a long thread on screen the
  moment it goes offline.
- **`cacheableThreadRows(rows)`** is `settledThreadRows(rows)` with `UnrecognizedMessage` rows
  additionally dropped (unbounded, model-adjacent JSON; its KDoc forbids persisting it) and the
  result bounded to the newest `MAX_CACHED_THREAD_ROWS` (200) via `takeLast` — the thread is in
  arrival order, so "newest" is the tail. This is what may reach disk.

**Identity is exact, case-sensitive string equality** on `serverId` and on `Conversation.id` —
the same rule [`PairedServerCollectionStore`](paired-server-store.md#the-contract) states for
its own ids. Neither is normalized or validated. The contract speaks the domain `Conversation`
type, not a persistence record: its eventual reader, `HostConversationSource`'s
`HostConversationSnapshot`, already types `channels`/`chats` as `List<Conversation>`.

`writeConversations` is a **whole-host replace, not an upsert** — the daemon's list is
authoritative for a host, so a conversation it no longer reports stops being cached by the
same call that stores the rest, with no diffing or second call in the eventual consumer. Order
is preserved verbatim; the cache does not re-sort.

Values read back are the same daemon-authored text that arrived over the wire. Passing through
the cache neither validates nor bounds them — a cached conversation name is exactly as
untrusted as a live one, and the render path owns length-bounding and escaping either way.

No `android.*` type appears in the contract. `data/` is portable by project rule (root
`CLAUDE.md` § Don't); only `FileConversationCache` may reach for a platform storage handle, and
even there the only platform type is `java.io.File` — see § Root and storage scope below.

## Layout

```
<root>/<sha256hex(serverId)>/conversations.json
<root>/<sha256hex(serverId)>/threads/<sha256hex(conversationId)>.json   (#797)
```

A server id is daemon-supplied and opaque, so it is never pasted into a path: the host
directory name is the lowercase hex SHA-256 of the id's UTF-8 bytes. That means no `/`, no
`..`, no NUL and no reserved name can ever reach a path component, and no server id enters the
filesystem namespace at all — proven by a test that writes a `../../etc/passwd`-shaped server
id and asserts every stored file stays under the canonical root. A **conversation id is hashed
the same way** and filed under its host's directory rather than in a flat namespace, so
`removeHost`'s existing recursive delete of the host directory covers `threads/` for free with
no separate removal step. The hash is namespace derivation, not a security boundary — the
property relied on is collision resistance, not secrecy.

The stored document is a versioned envelope (`version: Int`, `conversations: List<...>`),
mirroring `StoredPairings`. The cache-local record type — not `@Serializable` annotations on
`Conversation` itself — is deliberate: see
[data model § What's deliberately absent](data-model.md#whats-deliberately-absent). `lastUsedAt`
is stored as `Instant.toString()` / parsed back with `Instant.parse(...)`, not epoch millis, so
the round-trip is exact to the nanosecond rather than truncated to millisecond precision.

The thread document is the same shape, file-private to `FileConversationCache.kt`:
`CachedThread(version: Int, rows: List<CachedThreadRow>)`, `CachedThreadRow(message:
CachedMessage? = null, boundary: CachedBoundary? = null)` — exactly one of the two is set, mapping
`ThreadItem.MessageItem` / `ThreadItem.SessionBoundary` (never `UnrecognizedMessage`, which
`cacheableThreadRows` drops before a `CachedThreadRow` is ever built).
`CachedMessage(id, sessionId, role, content, timestamp, tool: CachedToolCall? = null)` carries no
`isStreaming` field — a restored row is always settled, so the field would have nothing to encode.
`CachedToolCall(toolName, input, output, status)` and `CachedBoundary(previousSessionId,
newSessionId, reason, occurredAt, workspaceCwd: String? = null)` round out the two row kinds.
Enums serialize by name; `Instant` fields (`timestamp`, `occurredAt`) follow `lastUsedAt`'s
ISO-text convention, not epoch millis.

## Root and storage scope — `noBackupFilesDir`, never `filesDir`

`FileConversationCache` takes a `File` root rather than a `Context`; the caller decides where
it lives. **That root must be under `Context.noBackupFilesDir`, never `Context.filesDir`.**

The manifest ships `android:allowBackup="true"` with `backup_rules.xml` and
`data_extraction_rules.xml` both still the empty AGP templates, so anything under `filesDir`
goes into cloud backup *and* device-to-device transfer by default. The pairing credentials
that authorize reading a host's conversations are Keystore-wrapped and do **not** transfer
(see [paired-server store § Backup](paired-server-store.md#edge-cases--limits)). Content under
`filesDir` would therefore survive a restore onto a machine whose Keystore cannot unwrap the
matching credentials — one host's conversations rendered to someone who never paired it and
cannot reach it. `noBackupFilesDir` is excluded from both paths by definition, which keeps the
cache exactly as transferable as the credentials it belongs to. This is a stronger and more
local guarantee than an `<exclude>` added to a shared XML rule file, which a later edit to that
file could silently widen back open.

This requirement was not provable by #795's own test suite, since it took a bare `File` and
shipped no Koin binding. #796's `ConversationCacheBindingInstrumentedTest`
(`app/src/androidTest/java/de/pyryco/mobile/di/`) establishes it: it resolves `ConversationCache`
from the live Koin container, writes one host through it, and asserts the document lands under
`Context.noBackupFilesDir` and that nothing is created under `Context.filesDir`.

Taking a `File` rather than a `Context` is also what makes the whole contract — persistence
included — provable in a plain JVM unit test on a `TemporaryFolder`, rather than the
instrumented, on-device test the nearest analogue (`KeystorePairedServerStoreTest`, real
Android Keystore) needs. Narrowing the Android dependency to *who supplies the root* is the
whole difference.

## Failure model — graceful reads, reporting mutations

The same two-sided contract [paired server store](paired-server-store.md#failure-model--graceful-reads-strict-mutations)
uses:

- **Reads are graceful.** A missing root, missing host directory, missing file, invalid JSON,
  an unsupported stored `version`, an unparseable `lastUsedAt`, or a **duplicate
  `Conversation.id` inside one host's document** all yield `emptyList()` after one `RelayLog.d`
  line carrying the operation and a static failure code. A read never repairs, rewrites or
  deletes what it could not parse — a truncated document is still on disk, byte-for-byte,
  after the read that failed to parse it.
- **Mutations report.** `writeConversations`, `removeHost` and `removeConversation` return
  `Result<Unit>`, the same idiom `AppPreferences.editWorkspace` uses for a fallible `data/`
  write. A failure carries `ConversationCacheException("<operation> failed: <code>")` with
  **no cause attached** — a serialization or IO message can embed a conversation name or cwd,
  and a crash reporter prints causes, so attaching one would put cached content into a report.
  Same reasoning as `PairedServerStoreException`.

Failure classification: `SecurityException` → `denied`; `IOException` → `io`;
`IllegalArgumentException` (kotlinx-serialization failures and `Instant.parse` failures are
both this) → `invalid_data`. Anything else, cancellation included, propagates unchanged.

**`readThread` (#797) rejects the same tampered-or-buggy shapes a duplicate `Conversation.id`
rejects, for the same reason: a document that would hand the thread's `LazyColumn` two rows under
one key reads empty instead of drawing them.** Beyond the version check every family shares, a
thread document is rejected (→ `invalid_data` → empty) when: a row carries both or neither of
`message`/`boundary`; any two messages share an id; any two boundaries share their full
`(previousSessionId, newSessionId, occurredAt)` identity (#775 — the pair alone is *not* rejected,
since an idle-evicted session keeps its id and a session evicted twice legitimately sends the same
pair twice with different instants); or any tool carries `ToolCallStatus.Running` — a restored row
is defined to always be settled, so a running status on disk is itself a corrupt document, not a
row to filter.

Removing an unknown host or an unknown conversation is a **successful no-op**, matching
`PairedServerCollectionStore.remove` — #798 does not need to check existence first.

**Duplicate ids are rejected on read but not on write.** `readConversations` rejects a document
whose conversations do not have distinct ids — the same rule `KeystorePairedServerStore`'s
decoder applies to its own ids, chosen so a later `removeConversation` can never delete one of
two same-id rows and leave the other drawing. `writeConversations`, however, stores whatever
list it is handed, including one with duplicate ids: the write reports success and the *next*
read of that host comes back empty, because the bytes it just wrote fail the same check. This
was flagged in review as a should-fix (the write should reject what the read would reject, the
way `KeystorePairedServerStore.save` structurally cannot produce a duplicate) and is not yet
closed. With no production consumer yet the practical exposure is bounded to "the host reads
empty until the next successful write," but a future caller building `conversations` from
something other than a daemon's already-deduplicated list should not rely on the write to
catch it.

### `removeConversation` against an unreadable document

One rule covers all three shapes a target host's document can be in: the removal rewrites the
host's document from what is currently **readable**, minus the target conversation.

- A healthy document loses exactly its target row.
- An unreadable document (truncated, invalid JSON, wrong version, duplicate id) already reads
  as empty, so the rewrite replaces those bytes with a valid empty document — the removal still
  takes effect on content that could not otherwise be isolated, which is what a permanent
  deletion (#798) needs.
- A host that was never written is skipped entirely, so a removal never conjures a directory
  for an unknown id.

This is the one place a mutation touches bytes that a read declined to repair — reads still
never repair anything; it is the removal, asked for explicitly, that rewrites.

`removeConversation` and `removeHost` (#797) also remove the thread family: a target
conversation's thread document is deleted alongside its metadata rewrite (absent → no-op;
present-but-undeletable → `IOException` → `io`), and `removeHost`'s recursive delete of the host
directory already covers `threads/`, since the thread family is filed under it (see § Layout).
`ConversationCache.removeConversation`'s KDoc records this as the rule any family added later must
follow, so a permanently deleted conversation never leaves content behind under a family that
forgot to extend the two removal operations.

## Removal on unpair — `forgetRemovedHost`

[#798](../../specs/architecture/798-clear-cache-on-removal.md) wires `removeHost` to the one place a
pairing is actually removed: `internal fun forgetRemovedHost(drafts: ComposerDraftStore, cache:
Lazy<ConversationCache>): suspend (String) -> Unit` in `di/ObservablePairedServerStore.kt` is the
production `onHostRemoved` hook `ObservablePairedServerStore.remove` runs once `delegate.remove` and
the revision bump have both succeeded — see [paired server store § Wiring &
usage](paired-server-store.md#wiring--usage) for the hook's own contract. It clears the host's
composer drafts first (`ComposerDraftStore.clearHost`, see [Thread screen § Composer draft
ownership](thread-screen.md#composer-draft-ownership)), then calls `cache.value.removeHost(serverId)`
inside `withContext(NonCancellable)` so a view model cleared mid-cleanup cannot strand the forgotten
host's content on disk, and logs a static `event=host_cache_remove_failed` line on failure without
surfacing it — the pairing is already gone by then, so reporting a failure would claim the host is
still paired when it is not. Never logs the id.

`cache` is `Lazy<ConversationCache>`, not `ConversationCache`, so resolving the paired-server store
binding never constructs the cache: the cache's root is `Context.noBackupFilesDir` (see § Root and
storage scope above), and the JVM tests that resolve `appModule`'s paired-server store without a
`Context` would otherwise fail with `MissingAndroidContextException` the moment that binding runs. The
Koin binding is `single { ObservablePairedServerStore(KeystorePairedServerStore(get()), forgetRemovedHost(get(), lazy { get() })) }`.

Named rather than written inline in `appModule`, for the same reason the #790 draft eviction was: a
JVM test that restates the hook as its own lambda stays green if production forgets a step, while one
that binds `forgetRemovedHost` itself cannot. `HostChannelListViewModelTest`'s fixture binds
`forgetRemovedHost(drafts, lazyOf(cache))` over a real `FileConversationCache` on a `TemporaryFolder`
for exactly this reason.

Permanent deletion does not go through this hook — see [Caching conversation repository §
delete](caching-conversation-repository.md#delete--removing-the-cache-alongside-the-daemon-798) for
`removeConversation`'s call site, which is a `CachingConversationRepository` override, not a paired-
server-store hook.

Archive and unarchive call neither removal. Both stay plain `by delegate` forwarding on
`CachingConversationRepository`, so an archived conversation's cached content is unreachable through
either code path this section or the linked one describes.

## Concurrency

One `kotlinx.coroutines.sync.Mutex` per `FileConversationCache` instance, held across each
whole operation. `removeConversation` is read-modify-write and would otherwise lose an update
against a concurrent `writeConversations`; holding the same lock for reads too keeps a read
from observing a half-finished replace. **The lock is per instance** — the app must resolve a
single shared `FileConversationCache`, the same requirement the paired-server store records for
its own mutations. No coroutine is launched and no scope is owned; every operation runs in the
caller's coroutine on the injected dispatcher (default `Dispatchers.IO`), so cancellation stays
the caller's.

Writes land through a temp file (`conversations.json.tmp`, `<hash>.json.tmp` for a thread) plus
`Files.move(..., ATOMIC_MOVE)` onto the target document, so process death mid-write leaves either
the previous document or the new one, never a torn one. Both families share one small write
helper for this temp-file-plus-move step, and both run under the same per-instance `Mutex` (see
below). If the write itself throws before the move, the temp file can be left behind in the host
directory; it is bounded to one stale sibling per host per family, invisible to every reader, and
cleared by the next successful write of that document or by `removeHost`.

## What's deliberately not here

- **No encryption at rest.** Conversation content is not a credential; the app-private
  directory already excludes other apps, and the residual — code running as this app's uid, or
  an attacker with root — is the same one [ADR 0006](../decisions/0006-keystore-wrap-at-rest-device-static-key.md)
  already accepts for the wrapped pairing blob, since anything that can read this directory can
  also ask the Keystore to unwrap it. What encryption would additionally buy is protection
  against offline disk imaging of a locked device, which is a device-wide FBE property, not
  this file's — out of scope unless the threat model changes.
- **No byte cap on a thread document, only a row-count cap.** `cacheableThreadRows` bounds a
  thread to its newest 200 rows, not to a byte size, so a hostile or unusual daemon that sends
  very large message contents is bounded only by what the live projection already holds in memory
  for that thread. Accepted in #797's security review as a residual, deferred until observed.

## Testing

[`FileConversationCacheTest.kt`](../../../app/src/test/java/de/pyryco/mobile/data/cache/FileConversationCacheTest.kt)
is a plain JVM unit test on a `TemporaryFolder` — no Robolectric, no device — because the
implementation takes a root `File` rather than a `Context`. 20 cases cover field-for-field and
null-field round-trips, write-order preservation, every graceful-empty-read shape (never
written, truncated, non-JSON, unsupported version, unparseable timestamp, duplicate id), host
and conversation isolation, replace semantics, the traversal-shaped server id, absence of any
identifier from the filesystem namespace, absence of credential-shaped strings and identifiers
from stored bytes, absence of identifiers from `RelayLog` output across both success and
failure paths, and a coded, causeless exception on a forced write failure.

**Every persistence assertion reads through a second `FileConversationCache` constructed over
the same root**, not the instance that wrote it. Nothing in this implementation caches
in-memory, so a same-instance read happens to be honest today — but it proves nothing about "a
fresh process that did not write it," and the first in-memory field anyone adds would make a
same-instance assertion pass while lying. This is the JVM-root equivalent of the paired-server
store's ["recreating the store over the same DataStore can pass on cached preferences"](paired-server-store.md#testing)
lesson. `RelayLog.sink`/`enabled` are captured and restored around each test, the same idiom
`SettingsViewModelTest` uses, which is what makes the "no identifier reaches a log" assertion
real rather than a convention.

[`FileConversationCacheThreadTest.kt`](../../../app/src/test/java/de/pyryco/mobile/data/cache/FileConversationCacheThreadTest.kt)
(#797) is a sibling file rather than an extension of the test above, with the same second-instance
and log-capture discipline. 14 cases cover: a field-for-field round trip (message, a tool call in
both a settled and a failed status, a boundary with and without `workspaceCwd`); that unrecognized,
streaming and running-tool rows are dropped on write; the newest-200 bound; per-conversation and
per-host isolation; whole-thread replace on a second write; that two boundaries sharing a session
pair but differing in `occurredAt` read back rather than being rejected (#775, a double idle-evict
of the same session); every graceful-empty-read shape including a duplicate message id, a duplicate
boundary identity (the full triple, not the pair) and a running-status document (left on disk,
unrepaired); `removeConversation` removing one thread and leaving siblings; removal still
working against host metadata that was never written; `removeHost` removing every thread under it
and no other host's; no conversation id in a path or a log line, success or failure; and a coded,
causeless exception on a forced write failure.

No Compose UI test and no emulator scenario for either family — #796's restored conversation rows
and #797's restored thread rows both draw through the same composables a live row does, so the
screen needs no cache-specific coverage. See [dependency injection §
Testing](dependency-injection.md#testing) for `HostConversationSourceTest`'s restore/live-race
cases and for why every other instrumented container built from `appModule` overrides this binding
with a shared `InertConversationCache` fake rather than supplying a real `Context`. See [Caching
conversation repository § Testing](caching-conversation-repository.md#testing) for the restore
merge's own unit coverage. Live continuity across a real reconnect — a loaded conversation staying
readable while its host link is cut and reconciling a peer's turn once the link is restored — is
proven live by [#850](https://github.com/pyrycode/pyrycode-mobile/issues/850)
(`InteractiveStreamE2ETest.interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect`), not this
cache's or the wrapper's own unit suite.

## Related

- [Ticket #795](https://github.com/pyrycode/pyrycode-mobile/issues/795) and its plan,
  `docs/specs/architecture/795-app-private-conversation-cache.md` (design, security review,
  and the `removeConversation` revision above)
- [Data model](data-model.md#whats-deliberately-absent) — why the persistence record is
  cache-local rather than annotations on `Conversation`
- [Paired server store](paired-server-store.md) — the nearest sibling in shape: graceful reads,
  reporting mutations, a versioned envelope, a causeless exception, and the backup-exclusion
  reasoning this cache's storage-scope decision extends
- [Relay log](relay-log.md) — the only logging facility this layer uses
- [Caching conversation repository](caching-conversation-repository.md) — the wrapper that reads
  and writes the thread-row family this doc's § The contract and § Layout describe (#797)
- Split from [#647](https://github.com/pyrycode/pyrycode-mobile/issues/647); downstream:
  [#796](https://github.com/pyrycode/pyrycode-mobile/issues/796) (done — host list restore, see
  [dependency injection § Restore from the on-disk cache](dependency-injection.md#restore-from-the-on-disk-cache-796)),
  [#797](../../specs/architecture/797-thread-row-cache.md) (done — thread-row family + thread
  restore), [#798](../../specs/architecture/798-clear-cache-on-removal.md) (done — `removeHost` wired
  to unpair via [`forgetRemovedHost`](#removal-on-unpair--forgetremovedhost), `removeConversation`
  wired to permanent deletion via
  [`CachingConversationRepository.delete`](caching-conversation-repository.md#delete--removing-the-cache-alongside-the-daemon-798);
  archive and unarchive call neither)
