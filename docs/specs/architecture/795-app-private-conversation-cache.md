# #795 — Store one host's conversation content in an app-private cache

## Files read

| Path | Symbol | Why it matters |
| --- | --- | --- |
| `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` | `Conversation` | The record to persist: ten fields, one `kotlinx.datetime.Instant`, no `@Serializable`. |
| `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt` | `PairedServerCollectionStore`, `PairedServerStoreException` | The nearest shipped `data/` store contract: Android-free interface, redacting models, a cause-free exception type. |
| `app/src/main/java/de/pyryco/mobile/data/crypto/KeystorePairedServerStore.kt` | `decodeEntries`, `mutate`, `failureCode` | The shipped failure model this cache mirrors: graceful reads, classified static codes, versioned envelope, no cause attached to the thrown exception. |
| `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt` | `MobileJson` | The project's one configured `Json`. `ignoreUnknownKeys`/`explicitNulls = false` decide what the cache record's nullable fields must default to. |
| `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` | `editWorkspace`, `setDefaultWorkspace`, `workspaceKey` | The `Result<Unit>` + `RelayLog` idiom for a fallible `data/` write, and the precedent of a per-host key derived from a server id. |
| `app/src/main/java/de/pyryco/mobile/data/network/RelayLog.kt` | `RelayLog.d`, its MUST-NOT-log list | The only logging facility this layer may use, and the reason tests swap `sink` to assert what a line contains. |
| `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` | `HostConversationSnapshot`, `reconcile` | The eventual reader (#796): its `channels`/`chats` are `List<Conversation>`, which is why this contract speaks the domain type rather than a record. |
| `app/src/main/AndroidManifest.xml`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` | `android:allowBackup="true"` and two **empty** rule files | Decides `filesDir` vs `noBackupFilesDir`: with the shipped defaults, anything under `filesDir` is carried into both cloud backup and device-to-device transfer. |
| `docs/knowledge/features/paired-server-store.md` | § Failure model, § Edge cases | The prior lesson that a graceful read must never be read as "the blob is gone", and that a backup restore leaves credentials unreadable on the new device. |
| `docs/knowledge/features/data-model.md` | § Why `kotlinx.datetime.Instant`, § What's deliberately absent | Records that the domain model is deliberately free of serialization annotations — the reason for a cache-local record type. |
| `app/src/test/java/de/pyryco/mobile/data/preferences/AppPreferencesTest.kt` | `tmp: TemporaryFolder` | The off-device temp-root test idiom this cache's proof reuses. |
| `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt` | `oldSink`/`oldEnabled` set-up | The shipped way to capture `RelayLog` output in a JVM unit test, which is how AC 4's log assertion is made real. |

## Design source

**Figma:** N/A — this slice ships a storage contract and one implementation with no production consumer and no rendered surface. The parent #647 and the consumer #796 carry the Channel List node; visual fidelity is judged there.

## Context

Everything the phone has loaded is connection-scoped: `RelayConnectionRegistry` builds a
`RemoteConversationRepository` per live connection and `HostConversationSource` holds a host's rows
only while one is live, so a restart or an unreachable host draws every saved host empty. #647 wants
that content to survive; this slice is its storage layer alone — a contract plus one app-private
implementation, covering the conversation-metadata family and the two removal operations. #796 reads
it, #797 adds the thread-row family, #798 wires removal to unpair and to permanent deletion.

No ADR is warranted. The storage-scope decision below is a consequence of ADR 0006's threat model
(credentials do not survive a device transfer) rather than a new architectural choice, and it is
recorded in this plan and in the implementation's KDoc.

## Sizing

Two production files, three new exported types, zero consumer call sites, four acceptance criteria,
three classified failure codes — every one inside the one-ticket boundary. Total written work is the
one line that does not fit: this plan is 300 lines and the code plus its proof is about 530, so the
ticket lands near 830 against a 800-line ceiling.

It is built anyway, and the reason is the floor rather than an appeal to easy edits. The only split
available is contract-from-implementation, and the contract's sole consumer would be the
implementation — a child that nothing outside the family calls, which the floor rule forbids and
which no verifier could check on its own. The floor wins over the ceiling: a budget miss costs a
continuation leg, an unverifiable ticket costs more. The overage is prose, not fan-out — there is no
cascade here, so the wall-clock risk the ceiling exists to bound is not what this overage measures.

## Design

New package `de.pyryco.mobile.data.cache`, beside the other `data/` contracts.

### `ConversationCache.kt` — the portable contract

```kotlin
interface ConversationCache {
    /** Never throws for an absent, truncated or otherwise unreadable host; yields an empty list. */
    suspend fun readConversations(serverId: String): List<Conversation>

    /** Replaces this host's whole stored set. The daemon's list is authoritative for the host. */
    suspend fun writeConversations(serverId: String, conversations: List<Conversation>): Result<Unit>

    /** Removes every cached artefact belonging to this host; an unknown host is a success no-op. */
    suspend fun removeHost(serverId: String): Result<Unit>

    /** Removes every cached artefact keyed by this conversation; an unknown id is a success no-op. */
    suspend fun removeConversation(serverId: String, conversationId: String): Result<Unit>
}

/** Carries an operation and a static failure code only — never a cause, never a cached value. */
class ConversationCacheException(message: String) : Exception(message)
```

The contract speaks `Conversation`, not a record: its reader is `HostConversationSnapshot`, whose
`channels`/`chats` are already `List<Conversation>`. No `android.*` import; the serialization record
is an implementation detail and stays `private` to the implementation file.

**Identity is exact, case-sensitive string equality on `serverId` and on `Conversation.id`** — the
same rule `PairedServerCollectionStore` states for its own ids. Neither is normalized or validated.

`writeConversations` is a whole-host replace rather than an upsert, which is what makes #796's
"a conversation the reconnected daemon no longer reports stops drawing" fall out of the write it
already performs, with no second call and no diffing in the consumer.

### `FileConversationCache.kt` — the app-private implementation

Constructor `FileConversationCache(root: File, ioDispatcher: CoroutineDispatcher = Dispatchers.IO)`.

`java.io.File` is the only platform type the implementation is handed; the Android dependency is
narrowed to *who supplies the root*. Its KDoc states the binding contract for #796's wiring: the root
must be under `Context.noBackupFilesDir`, never `filesDir` (see § Storage scope below). That narrowing
is what keeps the proof a plain JVM unit test rather than the 502-line instrumented test the nearest
analogue needs.

Layout:

```
<root>/<sha256hex(serverId)>/conversations.json
```

The host directory name is the lowercase SHA-256 hex of the server id's UTF-8 bytes. A daemon-supplied
id is opaque: hashing means no `/`, no `..`, no NUL, no length or reserved-name hazard ever reaches a
path component, and no server id is written into the filesystem namespace at all. Conversation ids
never touch a path in this slice — they live inside the file.

The stored document is a versioned envelope, mirroring `StoredPairings`:

```kotlin
@Serializable private data class CachedConversations(val version: Int, val conversations: List<CachedConversation>)
@Serializable private data class CachedConversation(/* Conversation's ten fields; lastUsedAt as an ISO-8601 String */)
```

`lastUsedAt` is `Instant.toString()` / `Instant.parse(...)`, not epoch millis: the round-trip is exact
to the nanosecond, which is what AC 1's "same field values" asks for. Every nullable field carries a
`= null` default so `MobileJson`'s `explicitNulls = false` omission decodes back to `null`.
`MobileJson` is the serializer, per the ticket; its `ignoreUnknownKeys` also lets a downgraded app
read a newer file's extra keys instead of discarding the host's whole list.

Mapping between `Conversation` and `CachedConversation` is two private extension functions in this
file. Nothing else in the app sees the record type.

### Recomposition / UI seams

None. This slice has no composable, no ViewModel and no `StateFlow`.

## State + concurrency model

No coroutine is launched and no scope is owned: every operation runs in the caller's coroutine, on
`withContext(ioDispatcher)`, so cancellation is the caller's and there is no job to leak.

One `kotlinx.coroutines.sync.Mutex` per instance, held across the whole of each of the four
operations. `removeConversation` is read-modify-write, so two concurrent calls without it could lose
an update or resurrect a removed conversation; holding the same mutex for the writes and the read
also keeps a read from observing a half-finished replace. Single mutex, so no ordering question.

The write is `write temp file → Files.move(ATOMIC_MOVE)` onto `conversations.json`. Process death
mid-write therefore leaves either the previous document or the new one, never a truncated one — and a
truncated one would read as empty anyway (§ Error handling), which is the weaker of the two
guarantees this keeps.

## Error handling

Two different contracts, deliberately, following the shipped paired-server store:

- **Reads are graceful.** A missing root, a missing host directory, a missing file, invalid JSON, an
  unsupported `version`, or an unparseable `lastUsedAt` all yield `emptyList()` after one
  `RelayLog.d` line carrying the operation and a static failure code. A read never repairs, rewrites
  or deletes what it could not parse.
- **Mutations report.** `writeConversations`, `removeHost` and `removeConversation` return
  `Result<Unit>` — the idiom `AppPreferences.editWorkspace` already uses for a fallible `data/`
  write. A failure carries `ConversationCacheException("<operation> failed: <code>")` with **no
  cause attached**: a kotlinx-serialization or IO message can embed a conversation name or cwd, and
  attaching it would put cached content into any crash report that prints the stack trace. This is
  the same reasoning already recorded on `PairedServerStoreException`.

Failure classification, as a single private function: `IOException` → `io`;
`IllegalArgumentException` (which kotlinx-serialization failures and `Instant.parse` failures both
are) → `invalid_data`; `SecurityException` → `denied`. Anything else, cancellation included,
propagates unchanged rather than being classified.

Removing an unknown host or an unknown conversation is a **successful no-op**, matching
`PairedServerCollectionStore.remove`; #798 must not have to check existence first.

### Storage scope — `noBackupFilesDir`, not `filesDir`

The manifest ships `android:allowBackup="true"` with an empty `backup_rules.xml` and an empty
`data_extraction_rules.xml`, so by default everything under `filesDir` goes into both cloud backup
and device-to-device transfer. Conversation content under `filesDir` would therefore be carried onto
a *different* device — while the pairing credentials that authorize reading it would not, because
they are Keystore-wrapped and the Keystore key does not transfer (recorded in
`docs/knowledge/features/paired-server-store.md` § Edge cases). The restored device would render one
machine's conversations to a user who never paired it and cannot reach the host. `noBackupFilesDir`
is excluded from both paths by definition, which makes the cache's transferability match the
credentials' — a stronger and more local guarantee than adding an `<exclude>` to a shared XML rule
file that a later edit could widen.

## Testing strategy

`app/src/test/java/de/pyryco/mobile/data/cache/FileConversationCacheTest.kt` — a plain JVM unit test
on `TemporaryFolder`, no Robolectric, no device. `runTest` with `UnconfinedTestDispatcher` as the
injected `ioDispatcher`. `RelayLog.sink`/`enabled` captured in `@Before` and restored in `@After`,
the idiom `SettingsViewModelTest` uses.

Scenarios, one per bullet:

- Round-trip: a conversation with every field populated (including a non-null `name`,
  `workspaceLabel`, a multi-entry `sessionHistory`, `isSleeping`/`archived` true, and a `lastUsedAt`
  carrying sub-second precision) written, then read back **through a second `FileConversationCache`
  constructed over the same root** — a fresh instance is the off-device stand-in for the fresh
  process AC 1 names, and this implementation holds no in-memory state that could fake it.
- Round-trip of a conversation with every nullable field null, proving `explicitNulls = false`
  omission decodes back to `null` rather than failing.
- Never-written host reads empty. Truncated file reads empty. Not-JSON file reads empty. Unsupported
  `version` reads empty. Unparseable `lastUsedAt` reads empty. None of these throws.
- An unreadable host's file is still on disk after the graceful read (the read repairs nothing).
- Host isolation: three hosts written, one removed, the other two read back intact; and removing an
  unknown host succeeds and disturbs nothing.
- Conversation isolation: one conversation removed from a host holding several leaves the rest
  readable in their original order; removing an unknown id succeeds and changes the stored bytes'
  meaning not at all.
- Replace semantics: a second `writeConversations` for the same host drops a conversation the new
  list omits.
- Ids stay out of the namespace: no path component anywhere under the root contains the server id,
  and no file name contains a conversation id.
- AC 4, content: the written bytes contain no pairing token, relay URL or server static key — asserted
  by writing conversations for a host whose id and fields are distinctive and scanning every file
  under the root for the credential strings and for the raw server id.
- AC 4, logs: every captured `RelayLog` line from a successful and a failed operation of each of the
  four methods contains neither the server id, nor a conversation id, nor a conversation name or cwd.

No Compose UI test and no emulator scenario: this slice has no operator-facing flow. #796 and #797
carry the rung-3 coverage for what the cache makes visible, and #673 verifies live behaviour.

## Documentation handoff

The ticket body has no **Documentation handoff** section and no documentation-only acceptance
criterion. The documentation phase owns whether this lands as a new
`docs/knowledge/features/conversation-cache.md` or a section elsewhere; nothing in this slice writes
under `docs/knowledge/`. Pending for the documentation stage.

## Open questions

1. **Does `writeConversations` need to preserve list order?** Resolved during implementation: it
   stores and returns the caller's order verbatim, since `HostConversationSource` hands it whatever
   `observeConversations` emits and re-sorting in the cache would invent an ordering the consumer
   never asked for.
2. **Should this slice add the Koin binding?** No production consumer exists, so #796 wires it
   together with the reader that needs it; a binding added here would be an unused singleton whose
   root-directory choice no test would exercise. The `noBackupFilesDir` requirement is carried in the
   implementation's KDoc so the wiring ticket cannot miss it.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** The boundary is `readConversations`: bytes on disk become domain
  `Conversation` objects there, and nowhere else — decoding lives in one private function in
  `FileConversationCache`, not scattered across call sites. Everything it returns is daemon-authored
  text that was already untrusted when it arrived over the wire; passing it through the cache neither
  raises nor lowers its trust, and the contract's KDoc says so, so #796 cannot mistake a cached name
  for a validated one. The renderers that bound and escape that text are unchanged and already
  shipped. **SHOULD FIX:** the decoder must not trust a stored list's *self-consistency* either —
  a duplicate `Conversation.id` inside one host's document is rejected as unreadable (empty result),
  the way `decodeEntries` rejects duplicate paired ids, rather than letting a later
  `removeConversation` delete one of two rows and leave the other drawing.
- **[Tokens, secrets, credentials]** No finding by construction: the cache never receives a token,
  relay URL or static key — its only inputs are a server id and `Conversation` values, and the
  serialization record enumerates `Conversation`'s ten fields explicitly rather than reflecting over
  some wider type, so a later field added to a *credential* type cannot drift into this document.
  AC 4 is proven by a test that scans the written bytes for credential strings. The cache stores no
  secret, so there is no lifecycle, rotation or revocation question to answer here; removal on unpair
  is #798's.
- **[File / storage operations]** The category with the real findings, all addressed in the design
  above: **path traversal** — a daemon-supplied server id is never concatenated into a path; the
  directory name is its SHA-256 hex, so `..`, `/` and NUL cannot appear, and conversation ids never
  reach a path at all. **Storage scope** — app-private `noBackupFilesDir`, never `filesDir`,
  `getExternalFilesDir` or `MediaStore`; the manifest's `allowBackup="true"` with empty rule files is
  precisely why, and the KDoc binds the wiring ticket to it. **Atomic writes** — temp file plus
  `Files.move(ATOMIC_MOVE)`, so process death mid-write cannot leave a partial document. **TOCTOU** —
  reads are `read-and-catch`, never `exists()`-then-open; the absent-file case is the same catch as
  the unreadable-file case. **Encryption at rest is deliberately not used**, and this is the one place
  where that deserves a stated decision rather than silence: conversation content is not a credential,
  the app-private directory already excludes other apps, and the residual — code running as this
  app's uid, or an attacker with root — is exactly the residual ADR 0006 already accepts for the
  wrapped blob, since anything that can read this directory can also ask the Keystore to unwrap. What
  encryption would buy is protection against offline disk imaging of a locked device; that is a
  device-wide FBE property, not this file's. **OUT OF SCOPE**, named for a future ticket if the threat
  model changes.
- **[Inter-process / Android attack surface]** Not applicable, with the reason stated rather than
  assumed: this slice adds no `Activity`, `Service`, `BroadcastReceiver`, `ContentProvider`,
  `intent-filter`, `PendingIntent` or `WebView`, and the cache is reachable only in-process through a
  Kotlin interface. Nothing it writes is exported, and the root it is handed is app-private, so no
  other app has a handle to the files at all.
- **[Cryptographic primitives]** SHA-256 via `MessageDigest.getInstance("SHA-256")` — a standard
  primitive, not hand-rolled, and used for *namespace derivation*, not as a security boundary: the
  property relied on is collision resistance over daemon-supplied ids, so two distinct hosts can
  never share a directory. No RNG, no key, no nonce, no comparison against a secret, so no
  constant-time question arises; the Noise stack is untouched.
- **[Network & I/O]** Not applicable — no socket, no URL, no frame, no timeout to set. The cache is
  handed already-decoded values by a caller that owns the connection.
- **[Error messages, logs, telemetry]** Two findings, both designed in. `RelayLog` lines carry an
  operation name, a static failure code and a count — never a server id, a conversation id, a name or
  a cwd — and a test asserts that over captured output, which is what makes AC 4's log half real
  rather than a convention. `ConversationCacheException` carries no cause: a serialization or IO
  message can embed a conversation name or cwd, and a crash reporter prints causes. No telemetry is
  added. `RelayLog` is debug-gated at `BuildConfig.DEBUG`, so nothing reaches release Logcat.
- **[Concurrency]** One instance-level `Mutex` held across each whole operation, because
  `removeConversation` is read-modify-write and would otherwise lose an update against a concurrent
  `writeConversations` — the same hazard `KeystorePairedServerStore` answers by reading inside
  `dataStore.edit`. **The residual worth naming:** the mutex is per *instance*, so two
  `FileConversationCache` objects over one root would not serialize against each other. The
  implementation's KDoc states that the app must resolve a single instance, which is what the DI
  binding in #796 will supply — the same "use the shared DI store" requirement already recorded for
  the paired-server store. No coroutine is launched, so there is no scope to leak and cancellation
  stays the caller's. Shutdown safety is the atomic move above.
- **[Threat model alignment]** **Hostile relay:** content-blind and on-path; it can cause a host to
  go unreachable, which now leaves *stale* content readable — the honest risk this feature creates,
  and #796 owns it by keeping the host row's disconnected status truthful beneath restored rows, which
  is an explicit acceptance criterion there. **Hostile daemon frame:** a malformed frame is rejected
  before it reaches the cache, and anything that does get cached is re-decoded defensively on read.
  **Token theft from disk:** unchanged — no token is stored here. **Device-transfer leakage:** the
  threat this design's storage-scope choice answers directly. **UI-side leakage** (screenshots,
  accessibility, overlays) is unchanged by a storage layer and belongs to the rendering surfaces —
  **OUT OF SCOPE** for this ticket.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22
