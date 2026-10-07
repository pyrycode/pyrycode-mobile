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
see [dependency injection § Restore from the on-disk cache](dependency-injection-host-conversation-source.md#restore-from-the-on-disk-cache-796)
for how the source writes and seeds from it. [#797](../../specs/architecture/797-thread-row-cache.md)
adds the thread-row family and the thread restore — see
[Caching conversation repository](caching-conversation-repository.md) for the wrapper that reads and
writes it. [#798](../../specs/architecture/798-clear-cache-on-removal.md) wires `removeHost` and
`removeConversation` to unpair and to permanent deletion — see
[§ Removal on unpair](#removal-on-unpair--forgetremovedhost) below and
[Caching conversation repository § delete](caching-conversation-repository.md#delete--removing-the-cache-alongside-the-daemon-798).

## The contract

**Visible markers do not certify saved coverage (#1917).** Restored fragmented spans and their
unresolved gaps remain authoritative even when projection suppresses most markers. Internal markers
require delivered content in both immediately adjacent spans; at the oldest displayed row only the
nearest unresolved older edge is shown, preferring a known gap over unknown coverage on a tie.
Lifecycle evidence and queued echoes establish neither occupied spans nor targets, and empty eligible
history shows no markers. Hiding a marker changes no anchor, opaque cursor or coverage claim;
restoration and reconnect retain hidden gaps for later displayed content. See
[marker projection](thread-screen-oldest-end-history-demand.md#the-oldest-end-history-demand-777).
This display repair changes no cache schema, retention bound or persistence policy. The suspected
cache-retention source of fragmentation remains unconfirmed and outside this repair.

See [The contract](conversation-cache-contract.md#the-contract) for this part of the cache contract.

## Layout

See [Layout](conversation-cache-layout.md#layout) for this part of the cache contract.

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

See [Failure model — graceful reads, reporting mutations](conversation-cache-failure-model.md#failure-model--graceful-reads-reporting-mutations) for this part of the cache contract.

## Removal on unpair — `forgetRemovedHost`

See [Removal on unpair — forgetRemovedHost](conversation-cache-removal.md#removal-on-unpair--forgetremovedhost) for this part of the cache contract.

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
  thread to its newest 100000 rows, not to a byte size, so a hostile or unusual daemon that sends
  very large message contents is bounded only by what the live projection already holds in memory
  for that thread. Accepted in #797's security review as a residual, deferred until observed. The
  row count moved from 200 to 100000 in #1353, matching desktop's own saved-timeline cap, once
  banners, compaction dividers and model refusals started counting toward it (see § The contract
  above) — raising it is what made [`mergeCachedRows`](caching-conversation-repository.md#how-the-restore-merges-with-live-rows)'s
  per-emission cost worth a look; see that section for the fix and why a bound this large changed
  what "cheap enough to run on every streaming delta" means.

## Testing

See [Testing](conversation-cache-testing.md#testing) for this part of the cache contract.

## Related

See [Related](conversation-cache-related.md#related) for this part of the cache contract.
