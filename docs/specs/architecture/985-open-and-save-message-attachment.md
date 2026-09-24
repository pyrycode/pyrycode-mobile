# #985 — Open and save a message attachment on the phone

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageAttachments.kt` → `MessageAttachments`, `MessageAttachmentItem`, `ImageAttachment`, `AttachmentFileRow`, `AttachmentSource`, `AttachmentViewState` — #984's slot; the item already merges the reference's name and MIME with `Ready`'s, which is exactly what open and save need.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt` → `MessageBubble` — the parameters #984 threads to `MessageAttachments`; this ticket adds two more beside them.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`, its `snackbarHostState` one-shot notices, `rememberAttachmentPicker` use, and the `MessageItem` arm calling `MessageBubble`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AttachmentPicker.kt` → `rememberAttachmentPicker` — the precedent for a launcher hosted in the thread screen with `rememberCoroutineScope` and IO work.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AttachmentReader.kt` → `isForeignContentUri` — refuses `file://`, this app's own authorities (`de.pyryco.mobile` and `de.pyryco.mobile.*`, so also the new provider), and non-content schemes.
- `app/src/main/java/de/pyryco/mobile/ui/settings/DebugBundleDownload.kt` → `documentArchiveDestination`, `DebugBundleDownloadController.onDestination` — the save precedent: `openOutputStream(uri, "wt")`, `DocumentsContract.deleteDocument` on a failed write, never logging the provider's message.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `Routes.CONVERSATION_THREAD` destination — already binds `attachmentStates`; unchanged here, because the launcher lives in `ThreadScreen`.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `AttachmentStore` single over `File(noBackupFilesDir, "attachments")` — the one root the provider may serve.
- `app/src/main/AndroidManifest.xml` — no provider yet.
- `docs/knowledge/features/attachment-retrieval.md` § Host store — layout `<root>/<sha256(serverId)>/<conversationId>/<attachmentId>`, ids shape-checked; name and MIME are sanitised hints.
- `docs/knowledge/features/options-overlay.md` — the overlay anchors to composer controls through `ThreadScreen`'s layer coordinates; no message-level caller exists in code.
- `docs/specs/architecture/984-message-attachments-in-bubbles.md` § Revisions — the image slot's sizing and `@GraphicsMode(NATIVE)` in `MessageAttachmentsTest`.
- androidx.core 1.16 `FileProvider` — supports `files-path`, `cache-path`, `external-*-path` and `root-path` only; there is no `no-backup-path` tag.

In-flight overlap check: no other `feature/*` branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The thread frame's `Message area` bubbles carry the attachment `Slot` (a 160 × 160 image or the `File field` row) between body and meta row; that slot is the tap target. The frame draws no save control and no per-message options overlay, so the save action is the slot's long-press, exposed to TalkBack as a labelled action; nothing visual is added to the bubble, so the #984 rendering and its tokens are unchanged.

## Context

#984 draws attachments and keeps retrieved files in `AttachmentStore`; nothing opens or saves them. This ticket adds both. Opening shares one file with another app through a non-exported `FileProvider`; saving copies it into a document the user picks. No storage permission is involved. The live open-and-save after history reload is proven by #674, so no rung-3 scenario lands here. No ADR needed.

## Design

### `MessageAttachments.kt` (components)

- New value `data class AttachmentTarget(val attachmentId: String, val displayName: String?, val mimeType: String?)` with a redacted `toString` (id only). The item builds it from the merged name and MIME it already computes.
- `MessageAttachments` and `MessageBubble` gain `onOpen: (AttachmentTarget) -> Unit = {}` and `onSave: (AttachmentTarget) -> Unit = {}` (named `onOpenAttachment` / `onSaveAttachment` on `MessageBubble`).
- **Only a `Ready` state is actionable.** The image slot and the file row get `Modifier.combinedClickable(onClick = open, onLongClick = save, onClickLabel = "Open", onLongClickLabel = "Save")` exactly when the state is `Ready`; `Loading`, `NotFound` and `Failed` get no click modifier at all, so they offer neither action (the failed row keeps its Retry button only).

### New file `ui/conversations/thread/AttachmentActions.kt`

Pure pieces (unit-tested):

- `internal fun attachmentProviderAuthority(packageName: String): String` = `"$packageName.attachments"`.
- `internal fun attachmentIntentType(hint: String?): String` — the hint lowercased and trimmed when it is a concrete `type/subtype` of RFC token characters, ≤ 127 chars, with no wildcard and not `application/vnd.android.package-archive`; otherwise `application/octet-stream`.
- `internal fun attachmentViewIntent(uri: Uri, mimeHint: String?): Intent` — `ACTION_VIEW`, `setDataAndType(uri, attachmentIntentType(mimeHint))`, flags exactly `FLAG_GRANT_READ_URI_PERMISSION`. Never write, persistable or prefix grants.
- `internal fun attachmentContentUri(context: Context, source: AttachmentSource): Uri?` — `Kept` → `FileProvider.getUriForFile(context, authority, file)`, `null` on `IllegalArgumentException` (a file outside the provider's one root); `Original` → the parsed URI only when `isForeignContentUri` accepts it, else `null`. Never built from a display name.
- `internal fun openAttachment(context: Context, source: AttachmentSource, mimeHint: String?): AttachmentNotice?` — builds the URI and the intent and calls `context.startActivity`; `null` on success, `NO_APP` on `ActivityNotFoundException`, `OPEN_FAILED` on no URI or any other exception.
- `internal fun copyAttachment(openInput: () -> InputStream, openOutput: () -> OutputStream, discard: () -> Unit): Boolean` — opens the input first, then the output, `copyTo` + `flush`, both closed by `use`. Any exception, including cancellation of the caller, runs `discard()` best-effort and returns `false` (cancellation rethrown). Exceptions are dropped unread.
- `enum class AttachmentNotice(@StringRes val message: Int) { NO_APP, OPEN_FAILED, SAVED, SAVE_FAILED }`.

Composable binding:

- `@Composable internal fun rememberAttachmentActions(states: Map<String, AttachmentViewState>, onNotice: (AttachmentNotice) -> Unit): AttachmentActions`, where `class AttachmentActions(val open: (AttachmentTarget) -> Unit, val save: (AttachmentTarget) -> Unit)`.
  - `open`: looks up `states[id] as? Ready` (else inert), runs `openAttachment` from a `rememberCoroutineScope` launch (URI building on `Dispatchers.IO`, `startActivity` back on Main); a non-null notice goes to `onNotice`.
  - `save`: `Ready` only. Stores the id in `rememberSaveable` (`pendingSaveId`) and launches a private `CreateAttachmentDocument` contract (`ACTION_CREATE_DOCUMENT`, `CATEGORY_OPENABLE`, type `attachmentIntentType(mime)`, `EXTRA_TITLE` = display name or the generic unnamed label).
  - Result: `null` → clear the pending id, nothing written, no notice. A URI → resolve the pending id against the **current** `states` (the ViewModel survives rotation; the source is never put in the saved-state bundle). No `Ready` state → `deleteDocument` + `SAVE_FAILED`. Otherwise `copyAttachment` on IO with input `FileInputStream(file)` for `Kept` or `openInputStream(uri)` for a foreign `Original`, output `openOutputStream(dest, "wt")`, discard `DocumentsContract.deleteDocument`; outcome `SAVED` or `SAVE_FAILED`.

### `ThreadScreen.kt`

Calls `rememberAttachmentActions(attachmentStates) { notice -> snackbar(resources.getString(notice.message)) }` beside `rememberAttachmentPicker`, and passes `actions.open` / `actions.save` to `MessageBubble`. No new `ThreadScreen` parameters; `MainActivity` is unchanged.

### Manifest and resources

- `<provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.attachments" android:exported="false" android:grantUriPermissions="true">` with `android.support.FILE_PROVIDER_PATHS` → `@xml/attachment_paths`.
- `res/xml/attachment_paths.xml`: one entry, `<files-path name="attachments" path="../no_backup/attachments/" />`. FileProvider canonicalises the root, so this is exactly `File(noBackupFilesDir, "attachments")` (`files` and `no_backup` are siblings under the data dir). A test pins that equivalence rather than trusting it.
- Strings: open / save click labels, no-app, open-failed, saved, save-failed.

## State + concurrency model

No ViewModel change. The pending save id is `rememberSaveable` in the thread screen; the launched work runs in the screen's `rememberCoroutineScope` and is cancelled when the thread leaves composition, and `copyAttachment` discards the half-written document on that cancellation. File I/O is on `Dispatchers.IO`; `startActivity` and notices on Main.

## Error handling

Every failure is a static snackbar sentence: no app → "No app can open this file"; no URI / grant refused → "Couldn't open file"; write failure or a vanished source → "Couldn't save file" and the created document is deleted. Cancel is silent. Logging is `RelayLog.d` only: `event=thread_attachment_open id=<A> outcome=opened|no_app|failed` and `event=thread_attachment_save id=<A> outcome=saved|cancelled|failed`. Never a name, URI, path, MIME type or exception message.

## Testing strategy

- `test/.../thread/AttachmentActionsTest` (new, Robolectric `@RunWith(AndroidJUnit4)`):
  - view intent: action, data, type, and flags exactly `FLAG_GRANT_READ_URI_PERMISSION` (no write, persistable or prefix bit);
  - type: a valid hint kept (lowercased); null, blank, malformed, wildcard and the package-archive type → `application/octet-stream`;
  - content URI for a file under `noBackupFilesDir/attachments/<h>/<c>/<a>` has the `<pkg>.attachments` authority; a file directly in `noBackupFilesDir`, in `filesDir`, and in `cacheDir` → `null`;
  - `Original`: a foreign content URI is used as is; `file://` and an own-package authority → `null`;
  - `openAttachment` with no matching activity → `NO_APP`; with a registered viewer → `null` and the started intent carries the content URI, type and read grant;
  - `copyAttachment`: exact bytes for a multi-buffer payload; an input failure and an output failure each call `discard` and return `false`; success never calls `discard`.
- `sharedTest/.../components/MessageAttachmentsTest` (extended): a `Ready` file row's tap sends `onOpen` with the id, name and MIME; its long-press sends `onSave`; a `Ready` image slot's tap sends `onOpen`; `Loading`, `Failed` and `NotFound` items have no click action.

## Documentation handoff

The ticket names none. Pending for the documentation stage: `docs/knowledge/features/message-bubble.md` (the slot's open and save actions, `onOpenAttachment` / `onSaveAttachment`) and `docs/knowledge/features/attachment-retrieval.md` § Host store (the non-exported provider over the store root, and the `../no_backup/` path trick).

## Open questions

- Does Robolectric resolve the manifest's `FileProvider` meta-data for `getUriForFile`? If not, the root-path equivalence test falls back to parsing `attachment_paths.xml` and canonicalising the root by hand.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The untrusted inputs are the reference's and retrieval's name and MIME hints and the file bytes. The name is used only as `EXTRA_TITLE` for the picker and as the save target's text; the provider that creates the document owns its sanitising. It is never a path. The MIME hint passes `attachmentIntentType`, which admits only a concrete `type/subtype` and falls back to `application/octet-stream`. The bytes are copied, never interpreted.
- [Trust boundaries] MUST FIX (applied in plan): a hostile MIME hint could choose a handler that installs or executes. `application/vnd.android.package-archive` and wildcards map to the fallback type. The stored file has no extension, so the provider itself also reports `application/octet-stream` to a receiver that asks. The app holds no `REQUEST_INSTALL_PACKAGES` either.
- [File / storage] No findings. URIs come only from `AttachmentSource.Kept.file`, which `AttachmentStore` built from shape-checked ids, through `FileProvider.getUriForFile`, which canonicalises and refuses anything outside the one root. The root is only `noBackupFilesDir/attachments`, never `filesDir`, `noBackupFilesDir` or the conversation cache; a test pins that and the refusals. No storage permission is requested. The save writes only to the document the user created (`"wt"`), and a failure or cancellation deletes it.
- [Inter-process] No findings. The provider is `exported="false"` with `grantUriPermissions="true"`; a receiver gets one `FLAG_GRANT_READ_URI_PERMISSION` grant on one URI, with no write, persistable or prefix flag. The `ACTION_VIEW` intent is implicit by design (the user picks the viewer); it carries only that URI. `Original` sources are re-checked with `isForeignContentUri`, so a `file://` URI or this app's own authority is never handed out. A remote URI is never launched; nothing from the wire becomes an intent's data. The receiving app can see the content path, which names the hashed host, the conversation id and the attachment id; these are identifiers, not secrets, and the grant ends with the receiving task.
- [Tokens] No findings: none touched.
- [Crypto] No findings: none used.
- [Network & I/O] No findings: no network; the copy is bounded by the stored file, itself bounded by `AttachmentRetrievalLimit`.
- [Logs] No findings: two `RelayLog.d` events carrying a shape-checked id and a static outcome.
- [Concurrency] SHOULD FIX (applied in plan): a save cancelled mid-copy (leaving the thread) would leave a truncated document. `copyAttachment` discards on any throw, cancellation included. The pending id is resolved against the live state map at result time, so a stale or recreated screen writes nothing unexpected.
- [Threat model] OUT OF SCOPE: what the chosen viewer does with the file is that app's concern, as with any share. The recents screenshot exposure is unchanged from #984.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24
