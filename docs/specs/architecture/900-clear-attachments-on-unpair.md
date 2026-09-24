# #900 — Clear a host's retained attachment files on unpair

## Files read

- `app/src/main/java/de/pyryco/mobile/di/ObservablePairedServerStore.kt` → `forgetRemovedHost`, `ObservablePairedServerStore.remove` — the awaited unpair hook this ticket extends; #798's cache step is the pattern to follow.
- `app/src/main/java/de/pyryco/mobile/data/cache/AttachmentStore.kt` → `AttachmentStore`, `contentFile`, `sha256Hex` — #899's store; one host is one `<root>/<sha256hex(serverId)>` directory, so removal is one recursive delete.
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt` → `FileConversationCache.removeHost`, `mutate` — the delete-then-check-exists rule and the `Result` shape a host removal reports.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `ObservablePairedServerStore` binding and the `AttachmentStore` `single` — the one production caller of `forgetRemovedHost`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` → `Fixture`, `confirmingUnpairRemovesThatHostsCachedContentAndOnlyAfterTheRemovalSucceeded` — the JVM unpair test that binds `forgetRemovedHost` itself; the only other caller.
- `app/src/test/java/de/pyryco/mobile/data/cache/AttachmentStoreTest.kt` → `store()`, `fetched` — helpers the new store tests reuse.
- `docs/knowledge/features/attachment-retrieval.md` § layout — "a future per-host removal on unpair is one recursive delete".

No in-flight branch touches these files.

## Design source

N/A — no UI change; the unpair confirmation's behaviour is unchanged apart from what it clears.

## Context

Unpair already takes a host's composer drafts (#790) and cached conversation content (#798) with it through `forgetRemovedHost`. #899 added retained attachment files per host under `noBackupFilesDir`; nothing removes them yet, so a forgotten machine's files would stay on the phone. Clearing a permanently deleted conversation's files is out of scope.

## Design

- **`AttachmentStore.removeHost(serverId: String): Result<Unit>`** — on `ioDispatcher`, `deleteRecursively` the host directory `<root>/<sha256hex(serverId)>`; failure when the directory still exists afterwards (the `FileConversationCache.removeHost` rule), or on `IOException`/`SecurityException`. An unknown host is a successful no-op. Never throws except on cancellation; the failure carries a static message only (no path, no id). The store itself logs nothing here — the hook logs.
- **`forgetRemovedHost(drafts, cache, attachments: Lazy<AttachmentStore>)`** — a third, lazy parameter for the same reason `cache` is lazy (the store's root is a Context directory; resolving the paired-server store must need no Context). After the cache step, inside the same `withContext(NonCancellable)`, it runs `attachments.value.removeHost(serverId)`; a failure logs the static `event=host_attachments_remove_failed` and is not surfaced. The attachment removal runs whether or not the cache removal failed — each step's failure is independent.
- **`appModule`** passes `lazy { get() }` for the attachment store.

## State + concurrency model

Runs inside `ObservablePairedServerStore.remove`, which is awaited by the unpair controller, so the confirmation closes only after the delete returns. `NonCancellable` so a cleared view model mid-cleanup cannot leave the files on disk. Known, unaddressed window: a `retrieve` whose fetch completed just before the revision bump closed the host's connection could `keep` its file after the delete. Not observed; not defended here.

## Error handling

`removeHost` returns `Result.failure` rather than throwing; the hook logs a static event name with no id and returns normally, so `confirmUnpair` reports success (the pairing is gone).

## Testing strategy

Unit tests, JVM only:

- `AttachmentStoreTest`: removing host A deletes every file kept for A (across two conversations) and leaves host B's kept file readable without a fetch; an unknown host is a success; a host directory that cannot be deleted (root made read-only) reports failure without throwing.
- `HostChannelListViewModelTest`, through the fixture's `forgetRemovedHost` binding (now with a real `AttachmentStore` on its own `StandardTestDispatcher` sharing the test scheduler):
  - confirming unpair of "Host" removes its kept files and leaves "host"'s; before the store's queued removal runs, the editor is still open and the files still present — after `runCurrent`, both are gone. No id reaches a log line.
  - an attachment removal that fails still closes the confirmation as a successful unpair and logs `event=host_attachments_remove_failed` without an id.

## Open questions

None.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the only input is the server id the unpair controller already holds.
  `AttachmentStore.removeHost` never uses it as a path component: it is hashed by `sha256Hex` into 64
  lowercase hex characters, which cannot spell `..`, a separator or an empty name, so the delete target is
  always a direct child of `root` and never `root` itself or anything outside it. No conversation id,
  attachment id or remote file name takes part in the removal.
- [Tokens] No findings — `forgetRemovedHost` receives only the `serverId`, never the `PairedServer`
  record; the token and static key never reach the attachment store or a log.
- [File / storage] No findings on symlinks, by the store's write discipline: Kotlin's `deleteRecursively`
  descends through `File.isDirectory`, which follows a link to a directory, but nothing creates a link
  under the host directory. `keep` writes only regular files, named by `isAttachmentIdShape`-checked ids
  plus fixed suffixes, through `outputStream` and `Files.move`. An attacker able to plant a link there
  already writes app-private storage as the app's uid, and the delete runs as that same uid, so it can
  reach nothing the attacker could not already delete. No `NOFOLLOW` walk is added for an unobserved
  failure.
- [File / storage] No findings on scope — the files stay under `noBackupFilesDir` (#899, unchanged); the
  point of this ticket is that they leave it on unpair. Failure is decided by the directory's existence
  after the delete, the `FileConversationCache.removeHost` rule, so a partial delete is reported rather
  than claimed as success.
- [File / storage] OUT OF SCOPE — residual window: the revision bump in `ObservablePairedServerStore.remove`
  closes the host's connection asynchronously, so a `retrieve` whose verified fetch completed just before
  that close could `keep` its file after `removeHost` returned. Not observed. The file is unreachable
  unless the same server id is re-paired, and it holds bytes the daemon itself sent and whose digest was
  verified. A deterministic fix needs a per-host tombstone or generation in the store contract; file a bug
  if it is observed.
- [Inter-process] No findings — no manifest, intent, provider or pending-intent change.
- [Crypto] No findings — `MessageDigest` SHA-256 for the directory name is #899's existing primitive, used
  for addressing, not secrecy; nothing else touched.
- [Network & I/O] No findings — no frame, URL or socket change.
- [Logs] No findings — one new line, the static `event=host_attachments_remove_failed`, with no id and no
  path. The failure's `IOException` message is a static string naming neither, and the hook never logs
  it. The unpair tests assert that no id reaches a log line.
- [Concurrency] No findings — no new scope. The removal runs inside the hook's existing
  `withContext(NonCancellable)`, after the credential removal and the revision bump, so a view model
  cleared mid-cleanup cannot strand the forgotten host's files. The work is a bounded local delete with no
  network wait, so `NonCancellable` cannot hang the unpair. The attachment step runs whether or not the
  cache step failed.
- [Threat model] OUT OF SCOPE — forensic recovery of deleted file blocks on a rooted device; the files are
  unencrypted app-private storage by #899's design, and removal is `File.delete`, not secure erase.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24

## Revisions

- **2026-09-24, during implementation.** The unpair test fixture also holds a second `AttachmentStore` over the same root on the test's unconfined dispatcher, used only to seed and read back files. Seeding through the queued store resumed the test body inside that dispatcher's task, where the view model's unconfined launches never started, so the editor never opened. Production design unchanged.
- **2026-09-24, rework.** Added the `## Security review` section, which the verifier's review of PR #964 found missing on this `security-sensitive` ticket. Verdict PASS; it changes no design, and the shipped code already matches its conclusions.
