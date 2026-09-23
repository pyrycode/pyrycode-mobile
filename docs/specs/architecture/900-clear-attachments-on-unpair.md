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
