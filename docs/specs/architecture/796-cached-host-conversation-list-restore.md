# #796 — keep each host's conversation list readable while it is disconnected

## Files read

- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `HostConversationSource`,
  `reconcile`, `update`, `publish`, `Held`, `relay`, `demo` — the whole change lives here. `update`
  already carries the stale-entry and superseded-repository guards a cache-seeded write has to satisfy.
- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCache.kt` → `ConversationCache`,
  `readConversations`, `writeConversations` — the contract this slice becomes the first consumer of.
  Reads are graceful (an unreadable document yields an empty list, never an exception); mutations
  return `Result<Unit>`.
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt` → `FileConversationCache`,
  `hostDirectory`, `store`, `mutate` — confirms the per-instance `Mutex` (so the app must resolve one
  shared instance), the atomic temp-file move, and that the root is a caller-supplied `File`.
- `docs/knowledge/features/conversation-cache.md` § "Root and storage scope" — records that the root
  **must** be `Context.noBackupFilesDir` and that establishing it is *this* ticket's proof obligation,
  because #795 ships no binding. Directly shapes § Design and § Security review below.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `appModule`, `hostConversationModule` — where
  the `ConversationCache` binding and the `relay(...)` pass-through go; `androidContext()` is already
  in scope beside the DataStore and Keystore singles.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → `hostConnections`, `reconcile` —
  confirms the saved-host list is published from the paired-server store, so a host entry exists
  before any connection does. That is what makes a restore-on-entry-creation possible at all.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` →
  `TreeHostRow`, `TreeConversationRow`, `boundedRowText` — the host row already renders
  `connectionStatus` through `ConnectionLegPair`, and both row names are clamped by `boundedRowText`.
  No UI change is needed, and restored rows inherit the live rows' clamp.
- `app/src/test/java/de/pyryco/mobile/di/HostConversationSourceTest.kt` → `Host`, `ManualRepository`,
  `row` — the fixtures the new cases extend, and the `RelayLog` capture/restore idiom that makes the
  "no identifier reaches a log" assertion real.
- `app/src/androidTest/java/de/pyryco/mobile/di/RepositoryBindingInstrumentedTest.kt` — the precedent
  for asserting a Koin binding against a real `Context`; the `noBackupFilesDir` proof follows its shape.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` → `Conversation` — `archived` and
  `isPromoted` are the two fields the snapshot filter reads.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Channel List Screen. The frame draws the same host twice: an upper variant where `Pyrybox` and
`Macbook` carry connected leg dots with their workspace and conversation rows beneath, and a lower
variant where `Pyrybox` is rendered in the error colour with a plug glyph and a red relay leg — **and
its conversation rows still drawn underneath it**. That lower variant is this ticket's target state
exactly: cached rows read normally while the host row alone carries the disconnected treatment. No new
composable, token or indicator is introduced; `TreeHostRow` already renders `connectionStatus` via
`ConnectionLegPair`, and this slice never writes `connectionStatus`.

## Context

`HostConversationSource` derives each `HostConversationSnapshot` from `observeConversations` on that
host's live repository. Rows therefore exist only while a repository does, so the teardown
`RelayRepositoryCoordinator` performs on background — and the process death that may follow — leaves
every saved host drawing as an empty node even though the phone had its list minutes earlier.

#795 shipped `ConversationCache` with no consumer and no Koin binding. This slice makes
`HostConversationSource` that first consumer: it writes each host's list as it arrives and seeds the
snapshot from the cache when no live list has come yet. It also adds the one binding the cache has
been waiting for, which is where the `noBackupFilesDir` requirement #795 recorded becomes real.

No ADR is warranted: the storage decisions were all made and recorded by ADR 0006 and #795's plan;
this slice only wires them.

## Design

**One new collaborator, nullable.** `HostConversationSource`'s internal constructor gains
`cache: ConversationCache? = null` as its **last** parameter, so the three existing positional
`relay(registry, dispatcher)` call sites in `RelayConnectionFactoryTest` do not move. `relay(...)`
gains the same trailing parameter and passes it through; `demo(...)` is unchanged and passes none —
the demo path has no cache to read, and a null cache disables both the restore and the write.

**One filter, two paths.** The `isPromoted`/`archived` split currently inlined in the live collector
becomes a single private extension, `HostConversationSnapshot.withRows(rows: List<Conversation>)`,
applied by both the live path and the restore path. The two cannot drift, and the restored snapshot is
filtered by exactly the rule the live snapshot uses.

**The cache stores the daemon's list verbatim, archived rows included.** The write takes the raw
`rows` the repository emitted, not the filtered channels/chats, so the document is a faithful mirror
of what the daemon reported and the filter is applied identically on both sides of it. Archive
surfaces read the repository rather than the snapshot, so they are untouched — this slice changes no
archive behaviour, as the ticket requires.

**Write only what was accepted.** `update` starts returning `Boolean`: `false` when any of its
existing guards rejects the write, `true` when the snapshot was actually transformed. The live
collector writes to the cache only on `true`, so a retired generation's list — already rejected for
the snapshot by the superseded-repository check — is not written to disk either.

**The restore, and the guard that keeps it from overwriting a live list.** When `reconcile` creates a
new `Held` and a cache is present, it launches one more coroutine under that entry's `Job`:
read `readConversations(serverId)`, and if the result is non-empty, apply `withRows` through `update`.
`Held` gains `var live: Boolean`, set inside `update` when a live-list write is accepted (that is, when
the `repository` argument is non-null). `update` gains a `restore` flag and rejects a restoring write
when `entry.live` is already set. Because `live` is both written and read under the existing
`@Synchronized` monitor, and the cache read suspends *outside* it, the check-and-set is atomic against
the live path: a slow restore that completes after the daemon's list has landed is dropped.

**No merge, no dedup, no diffing — deliberately.** The restore *seeds*; a live list *replaces*
wholesale, the way it does today. That is what satisfies two acceptance criteria without any code: a
conversation id already drawn cannot gain a second row because there is no union to build, and a
conversation the reconnected daemon no longer reports stops drawing because the replacement is total.
The same whole-host replace semantics in `writeConversations` drop it from the cache in the same beat.

**Host isolation.** The restore reads `connection.serverId` off the same `Held` it writes back into,
and `publish()` maps entries by server id, so restored rows are structurally confined to their own
host. Several disconnected hosts restore independently.

**Koin.** `appModule` gains `single<ConversationCache> { FileConversationCache(File(androidContext().noBackupFilesDir, …)) }`
— a `single`, because `FileConversationCache`'s `Mutex` is per instance and #795 records that the app
must resolve exactly one. `hostConversationModule`'s relay branch passes it as `cache = get()`; the
demo branch resolves nothing.

## State + concurrency model

- The restore coroutine is `scope.launch(entry.job)`, a child of the source's `SupervisorJob`, so it
  is cancelled by `dispose()` and by the entry's removal in `reconcile` — the same cancellation path
  the status and repository collectors already have. No new scope, no `GlobalScope`.
- No suspension happens inside the `@Synchronized` monitor. `readConversations` and
  `writeConversations` both suspend before/after `update`, never during it.
- The cache runs its own IO dispatcher internally (`FileConversationCache` wraps every operation in
  `withContext(ioDispatcher)`), so the source's `Dispatchers.Default` is never blocked on disk.
- Writes for one host are ordered by that host's sequential `collect`. A live list arriving during a
  background teardown simply does not arrive; the last successful write stands, which is the point.

## Error handling

- **Restore failure** needs no handling: `readConversations` is contractually graceful — a missing,
  truncated, unparseable or wrong-version document all yield an empty list after the cache's own coded
  log line, and the non-empty guard means an empty result changes nothing. Only cancellation
  propagates, which is the correct behaviour for a cancelled entry.
- **Write failure** surfaces as `Result.failure`. The collector logs one static
  `event=host_snapshot_cache_write_failed` line and continues; a failed write leaves the previous
  document intact and the live snapshot is unaffected. The failure's message is deliberately **not**
  logged — this file's `RelayLog` lines stay content-free.
- No new user-facing error surface. A host whose cache cannot be read draws exactly as it does today.

## Testing strategy

Unit (`app/src/test/.../di/HostConversationSourceTest.kt`), extending the existing fixtures with a
recording in-memory `ConversationCache` fake:

- A host with a seeded cache and no live repository draws its cached channels and chats, filtered the
  same way, while its `connectionStatus` remains the disconnected value it was given — one assertion
  covering both halves of AC 1.
- Two disconnected hosts with different seeded lists each draw their own rows and none of the other's
  (AC 4).
- A live list arriving after a restore replaces it wholesale: the retired id is gone, the still-reported
  id appears exactly once (AC 2, AC 3).
- A restore whose read completes *after* a live list has landed does not overwrite it — the `live`
  guard, driven by ordering the fake's read completion behind the live emission.
- The accepted live list is written to the cache verbatim including archived rows; a list rejected by
  the superseded-repository guard is not written.
- A failing write logs the static event and leaves the snapshot intact; no server id, conversation id,
  name or cwd appears in captured `RelayLog` output.
- `demo(...)` touches no cache.

Instrumented (`app/src/androidTest/.../di/ConversationCacheBindingInstrumentedTest.kt`), new: resolve
`ConversationCache` from the live Koin container, write one host through it, and assert the document
landed under `Context.noBackupFilesDir` and that nothing was created under `Context.filesDir`. This is
the property #795 named as this ticket's obligation and the only one that needs a real `Context`; it
runs in the dispatcher's `ui` gate (package `de.pyryco.mobile.di`, outside the excluded `e2e` package).

No new rung-3 scenario: AC 5 delegates live behaviour to #673, and the focused regression coverage for
this change is the unit suite above.

## Documentation handoff

The ticket carries no **Documentation handoff** section and no documentation-only acceptance criterion.
Pending for the documentation stage: fold this slice into
`docs/knowledge/features/conversation-cache.md` — it has three standing "not here" claims that this
ticket closes (**No Koin binding**, **No production consumer**, and the storage-scope note that names
#796 as the ticket whose tests must establish `noBackupFilesDir`) — and into
`docs/knowledge/features/channel-list-screen.md` for the restored-rows-under-a-disconnected-host
behaviour. Not done here; this role does not write those files.

## Open questions

1. Should the cache store the filtered channels/chats or the daemon's raw list? — **Resolved in
   § Design:** the raw list, so the document mirrors the daemon and one filter serves both paths.
2. Should a restore that reads empty clear an existing snapshot? — **Resolved:** no. A failed read is
   contractually indistinguishable from "never written", so an empty read must never be treated as
   "the daemon has no conversations"; only a live list may empty a host.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No new boundary. Daemon-authored names and cwds now round-trip through disk, but
  restored rows land in the *same* `HostConversationSnapshot.channels`/`chats` fields as live rows and
  render through the *same* composables — `TreeConversationRow` clamps its name with `boundedRowText`
  exactly as it does for a live row. Persisting does not launder untrusted text into trusted text, and
  #795's contract states the same rule. No new render, log, path, URL or filename sink is added.
- [File / storage] **The load-bearing finding.** Binding the root to `Context.filesDir` would be
  exploitable as designed: the manifest ships `android:allowBackup="true"` with empty backup and
  data-extraction rules, so the cache would ride cloud backup *and* device-to-device transfer, while
  the Keystore-wrapped pairing credentials that authorize reading that content would not — rendering
  one machine's conversation names and cwds on a device that never paired the host and cannot reach
  it. The plan binds `Context.noBackupFilesDir` and proves it with a dedicated instrumented test
  asserting both the presence under `noBackupFilesDir` and the absence under `filesDir`. Path traversal
  and torn writes are already closed inside `FileConversationCache` (sha256 directory derivation,
  atomic move) and are not re-opened here.
- [File / storage] Cross-host attribution is a confidentiality property, not just correctness: a read
  keyed by the wrong server id would draw one host's conversations under another. The restore reads
  `connection.serverId` from the same `Held` it writes into, and the cache derives its directory from
  that id; AC 4's two-disconnected-hosts case is the test that holds it.
- [Tokens, secrets, credentials] Not applicable by design — the cache stores conversation metadata,
  never pairing keys or tokens, and this slice adds no credential path. Encryption at rest was
  considered and declined in #795 against ADR 0006's accepted residual (anything that can read the
  app-private directory can also ask the Keystore to unwrap the pairing blob); not re-litigated here.
- [Error messages, logs, telemetry] One new log line, `event=host_snapshot_cache_write_failed`, static
  and content-free. The `ConversationCacheException` message is deliberately not logged even though it
  carries only a static code, and no per-host conversation count is emitted — the restore stays silent
  because its effect is already observable in the snapshot. A unit assertion scans captured `RelayLog`
  output for the fixture's identifiers.
- [Concurrency] A narrow, named race: between `update` returning `true` and the cache's mutex being
  acquired, the emitting repository can be superseded, so a retired generation's list can in principle
  commit after a fresher one. Consequence is bounded staleness for one host — the next live list
  replaces both the snapshot and the document — and never a cross-host leak. Accepted rather than
  fixed, on #795's own framing that this is "a cache, never a source of truth"; adding a second
  post-write identity check would buy self-healing staleness at the cost of the design's simplicity.
  The `live`-flag check-and-set is *not* in this class: it is read and written only under the existing
  `@Synchronized` monitor, with no suspension inside it.
- [Threat model — hostile relay] The relay is content-blind and on-path: it can drop or delay, but it
  cannot inject conversation rows, which arrive inside the Noise session. Its best attack here is to
  make a host unreachable, which is precisely the designed path — and a hostile relay **cannot make a
  stale list look live**, because `connectionStatus` is driven only by the connection and this slice
  never writes it. The Figma's disconnected variant is the intended rendering of that state.
- [Threat model — disk exposure on a rooted device] Genuinely new: conversation names and cwds that
  were memory-only now persist. This is the residual ADR 0006 already accepts for app-private storage,
  and `noBackupFilesDir` keeps the blast radius at "this device" rather than "anywhere this backup is
  restored", which is the change that mattered.
- [Network & I/O] No wire change, no new frame, no new URL. A hostile or compromised daemon could
  report an enormous conversation list, which would now be written to disk rather than held in memory
  only — but `writeConversations` is a whole-host replace, so the footprint is bounded by the current
  list rather than accumulating across writes, and it is confined to app-private storage reclaimed on
  uninstall. No size cap added; out of scope for this slice.
- [Inter-process / Android attack surface] Not applicable — nothing exported, no intent filter, no
  pending intent, no content provider, no WebView, and the push path is untouched.
- [Cryptographic primitives] Not applicable — this slice adds none. The SHA-256 namespace derivation
  is internal to `FileConversationCache` and is relied on for collision resistance, not secrecy.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22
