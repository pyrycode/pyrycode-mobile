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
for how the source writes and seeds from it. #797 adds the thread-row family, #798 wires the
removal operations to unpair and to permanent deletion.

## The contract

```kotlin
interface ConversationCache {
    suspend fun readConversations(serverId: String): List<Conversation>
    suspend fun writeConversations(serverId: String, conversations: List<Conversation>): Result<Unit>
    suspend fun removeHost(serverId: String): Result<Unit>
    suspend fun removeConversation(serverId: String, conversationId: String): Result<Unit>
}

class ConversationCacheException(message: String) : Exception(message)
```

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
```

A server id is daemon-supplied and opaque, so it is never pasted into a path: the host
directory name is the lowercase hex SHA-256 of the id's UTF-8 bytes. That means no `/`, no
`..`, no NUL and no reserved name can ever reach a path component, and no server id enters the
filesystem namespace at all — proven by a test that writes a `../../etc/passwd`-shaped server
id and asserts every stored file stays under the canonical root. Conversation ids never touch a
path in this slice; they live inside the document. The hash is namespace derivation, not a
security boundary — the property relied on is collision resistance, not secrecy.

The stored document is a versioned envelope (`version: Int`, `conversations: List<...>`),
mirroring `StoredPairings`. The cache-local record type — not `@Serializable` annotations on
`Conversation` itself — is deliberate: see
[data model § What's deliberately absent](data-model.md#whats-deliberately-absent). `lastUsedAt`
is stored as `Instant.toString()` / parsed back with `Instant.parse(...)`, not epoch millis, so
the round-trip is exact to the nanosecond rather than truncated to millisecond precision.

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

## Concurrency

One `kotlinx.coroutines.sync.Mutex` per `FileConversationCache` instance, held across each
whole operation. `removeConversation` is read-modify-write and would otherwise lose an update
against a concurrent `writeConversations`; holding the same lock for reads too keeps a read
from observing a half-finished replace. **The lock is per instance** — the app must resolve a
single shared `FileConversationCache`, the same requirement the paired-server store records for
its own mutations. No coroutine is launched and no scope is owned; every operation runs in the
caller's coroutine on the injected dispatcher (default `Dispatchers.IO`), so cancellation stays
the caller's.

Writes land through a temp file (`conversations.json.tmp`) plus `Files.move(..., ATOMIC_MOVE)`
onto `conversations.json`, so process death mid-write leaves either the previous document or
the new one, never a torn one. If the write itself throws before the move, the temp file can be
left behind in the host directory; it is bounded to one stale sibling per host, invisible to
every reader, and cleared by the next successful write or by `removeHost`.

## What's deliberately not here

- **No encryption at rest.** Conversation content is not a credential; the app-private
  directory already excludes other apps, and the residual — code running as this app's uid, or
  an attacker with root — is the same one [ADR 0006](../decisions/0006-keystore-wrap-at-rest-device-static-key.md)
  already accepts for the wrapped pairing blob, since anything that can read this directory can
  also ask the Keystore to unwrap it. What encryption would additionally buy is protection
  against offline disk imaging of a locked device, which is a device-wide FBE property, not
  this file's — out of scope unless the threat model changes.
- **No thread-row restore yet.** #796 wired the host-list cache and its one Koin binding into
  `HostConversationSource`; the thread-row family and thread restore are #797, and removal on
  unpair / permanent deletion is #798.

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

No Compose UI test and no emulator scenario in this slice — #796's restored rows draw through
the same `TreeHostRow` / `TreeConversationRow` composables a live row does, so the screen needs
no cache-specific coverage; see [dependency injection § Testing](dependency-injection.md#testing)
for `HostConversationSourceTest`'s restore/live-race cases and for why every other instrumented
container built from `appModule` overrides this binding with a shared `InertConversationCache`
fake rather than supplying a real `Context`. #797 carries the rung-3 coverage for the thread-row
family this cache makes visible there.

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
- Split from [#647](https://github.com/pyrycode/pyrycode-mobile/issues/647); downstream:
  [#796](https://github.com/pyrycode/pyrycode-mobile/issues/796) (done — host list restore, see
  [dependency injection § Restore from the on-disk cache](dependency-injection.md#restore-from-the-on-disk-cache-796)),
  #797 (thread-row family + thread restore), #798 (removal wiring)
