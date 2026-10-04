# Capture composer images before the input grant expires

## Files read

- `AttachmentPicker.kt`: `rememberPastedImageReceiver`, `describePastedImage` and the unchanged foreign-provider boundary.
- `AttachmentReader.kt`: `ContentResolverAttachmentReader`, `readBounded`, `isForeignContentUri`; bounded external reads remain the capture boundary.
- `PendingAttachment.kt`: pending metadata and the opaque owned-copy reference added beside it.
- `ComposerDraftStore.kt`: `addAttachment`, `editAttachments`, host/conversation eviction and sent originals; this process-scoped owner survives destinations.
- `ThreadViewModel.kt`: `sendWithAttachments`, `upload`, `addPickedAttachments`, `loadAttachment`; snapshot removal precedes the send's completion.
- `ComposerAttachmentStrip.kt`: `rememberThumbnail`; preserve crop geometry while changing the image source.
- `ThreadScreen.kt`: paste receiver and attachment notice collectors.
- `PyryApp.kt`: process initialization, the only startup cleanup location.
- `ThreadViewModelAttachmentTest`, `ComposerDraftStoreTest`, `AttachmentPasteTest`, `ComposerPasteTest`, `ComposerImagePasteDeviceTest`: existing boundaries and regression seams.
- `InteractiveStreamE2ETest`: `interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes`; retain document picker, digest and conversation-isolation checks.
- `docs/knowledge/features/thread-screen-composer-drafts-and-attachments.md`: Composer pending attachments; process-local drafts and pre-send consumption are existing contracts.
- `docs/knowledge/features/attachment-upload.md`, `attachment-retrieval.md`: upload bound and daemon retrieval fallback.
- `docs/e2e-interactive-stream.md`: rung-3/4 harness ownership.
- Sibling `pyrycode/docs/protocol-mobile.md`: unchanged wire contract and Security model.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Design context and screenshot inspected. Keep the existing 45 × 60dp cropped thumbnails, 12dp spacing, overlapping remove discs and composer notices, using the current Material theme tokens. No geometry, typography, colour or interaction styling changes.

## Context

A pasted provider URI can lose its read grant when the clipboard changes. Capture its bounded bytes before publishing an entry, rather than depending on the grant until Send. Sent-image retention stays with existing daemon retrieval; no new persistent cache or decision record is needed.

## Design

Add `OwnedPasteCopy`, an opaque capability minted only by the capture helper, with a private constructor and private file. Files use generated names in a dedicated app-private `noBackupFilesDir` directory, never provider names or paths. Pending and picked metadata carry an optional capability; ordinary picker entries continue to carry just their URI. Raw external own-provider, file and resource URIs remain refused.

The paste helper describes and re-checks image type, then uses the existing bounded external reader and creates a copy before calling the pending-entry sink. Successful copies replace reported size with the actual byte count. Failure emits the existing too-large or unreadable notice. Refused, failed, partially written or abandoned captures delete their files. Provider metadata is clamped before publication.

The draft store accepts ownership only for added entries; count refusals release the captured copy. Removed entries and host/conversation evictions release exactly their owned capabilities. Send snapshots retain references before asynchronous work starts, and release them in `finally`, after the complete send attempt. Pre-send pending removal releases draft ownership but cannot delete the send's retained copy. Retryable read/upload failure leaves draft ownership intact. `recordSentOriginals` skips owned paste copies, so sent loading uses daemon retrieval after cleanup.

Thumbnail and upload reads use the capability, never resolve its URI to an app path. Thumbnail decoding samples to tile dimensions. Startup deletes only the dedicated leftover directory's files during `PyryApp.onCreate`, before any current-process captures exist; activity recreation does not clean it.

Overlaps: #1642, #1666, #1682, #1689, #1690, #1691, #1693, #1695, #1747 and #1753 touch shared screen/ViewModel/live-test files. Their changes affect other blocks; keep this diff local and additive.

Sizing: one deliverable, five criteria, two new exported types at most, fewer than ten simultaneous consumer updates, about 1200 written lines including tests/plan and fewer than ten reject branches. Rechecked against the written plan; below 1600 lines.

## State and concurrency model

Capture runs on IO in a composition-owned coroutine. A cancellation-safe outer `finally` releases all copies not transferred synchronously to the sink, including cancellation when IO returns. Destination disposal cancels unfinished capture; a completed published entry belongs to the app-scoped draft store. No cleanup runs on ViewModel clearance or navigation.

Each copy serializes reading, retain and release with its own monitor; the last reference deletes it only after an active read completes. The draft store serializes attachment edits so cleanup occurs once outside StateFlow CAS retries. Send retains its exact snapshot synchronously and releases in coroutine `finally`. Newly added entries and other pairs are never part of that cleanup. No new hot flow, dispatcher dependency or connection job is introduced; transport closure preserves retryable draft ownership.

## Error handling

External reads keep `AttachmentRead.Bytes`, `TooLarge` and `Unreadable`. Capture failures and private read failures use these same classified results, and exceptions/paths never reach notices or logs. Existing count-limit refusal remains unchanged. Capture cancellation is rethrown after cleanup. Lifecycle logs use static outcomes only, without bytes, metadata, URIs or paths.

## Testing strategy

Test first with focused JVM ownership/capture tests: grant revoked after capture still sends exact bytes, actual-size limit despite dishonest/unknown metadata, rejection/partial-write/cancellation cleanup, explicit removal, host/conversation isolation, send lease during eviction, retry retention, newly added entries surviving send, and sent retrieval after deletion. Existing reader, draft, paste, attachment send/retrieval and strip screen tests remain focused coverage.

Extend the real-clipboard `ComposerImagePasteDeviceTest` to replace clipboard and remove the original provider item before reading the pending copy, and run that class on the managed device. This remains device-only because it crosses the real clipboard/provider boundary. Extend the named rung-3 method to paste its PNG, replace clipboard, type and Send while picking its document. Dispatcher owns fresh full-live suite execution and counts. The deterministic capture-to-send test is the twin at the changed local boundary; no new scripted daemon state is needed.

## Open Questions

None.

## Documentation handoff

Pending for documentation stage: update `docs/knowledge/features/thread-screen-composer-drafts-and-attachments.md`, “Composer pending attachments”, with owned paste source lifetime, backup exclusion and sent-retrieval fallback. Record the named rung-3 paste regression under `docs/e2e-interactive-stream.md`. Before documentation completes, dispatcher full-live evidence must include executed/failed/skipped counts and confirmation that `InteractiveStreamE2ETest.interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes` ran and passed.

## Security review

**Verdict:** PASS

- [Trust boundaries] Private constructor and typed capability distinguish app-created copies from arbitrary external URIs; `isForeignContentUri` and provider image-type checks still precede capture. No external string selects a private file.
- [Tokens] No credentials are created or changed. Captured image content is sensitive temporary user data, not a bearer credential.
- [Files/storage] Dedicated app-private `noBackupFilesDir` with generated names excludes cloud/device transfer. Publication follows a completed write; partial files are deleted, and process death leftovers are purged before startup capture. No name, URI or draft key influences a path.
- [Android surface] No new exported component or provider. Clipboard and IME inserts share the validated receiver. Third-party keyboards already see inserted image content; no broader permission is added.
- [Cryptography] Noise and Keystore are untouched; temporary copies rely on Android app-private storage and device encryption, with no rooted-device confidentiality guarantee.
- [Network/I/O] Existing upload byte bound and wire contract remain intact. Read size is authoritative; a provider cannot bypass the cap with metadata. No new network operation.
- [Errors/logs] Static outcomes only; provider exception messages, bytes, metadata, URI and private path are never logged or included in errors/toString.
- [Concurrency] Reads and final deletion serialize per capability; send retains before removal and releases after completion. Capture finally covers cancellation during dispatcher return and rejected publication. No mutex nesting.
- [Threat model] Malicious relay and hostile daemon remain covered by unchanged Noise/decoder/upload/retrieval boundaries. Token theft remains Keystore-owned. Screenshot/accessibility/keyboard image visibility is the existing UI trust model; no new image export. Rooted storage access is outside this clipboard-lifetime fix.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-04
