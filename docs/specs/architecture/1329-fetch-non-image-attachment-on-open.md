# #1329 — Download a non-image file only when it is opened

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `onAttachmentShown`, `onRetryAttachment`, `claimAttachment`, `loadAttachment`, `retrieved`, `attachmentRefusals` (the one-shot `Channel` + `receiveAsFlow` idiom the new effect copies). The fetch decision and the pending intent live here.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageAttachments.kt` → `MessageAttachments`, `MessageAttachmentItem` (the `LaunchedEffect(id)` that reports a row shown; the absent-id-draws-`Loading` default), `AttachmentFileRow`, `AttachmentTarget`, `attachmentActions` modifier. The deferred drawing goes here.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AttachmentActions.kt` → `AttachmentActions`, `rememberAttachmentActions`: open (markdown → in-app reader, #1027; else `openAttachment`) and save (create-document picker, source resolved from live states on the result). Both guard on the live `states` map being `Ready`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt` → `MessageBubble` attachment parameters.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen` attachment parameters, `rememberAttachmentActions` call, the `MessageBubble` call in the thread list.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `ThreadScreen` wiring to `vm::onAttachmentShown` / `vm::onRetryAttachment`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt` → `isMarkdownAttachmentName`, the name-suffix idiom.
- `app/src/test/.../thread/ThreadViewModelAttachmentRetrievalTest.kt` → `RetrievingRepository` (counts `retrieveAttachment` calls), `ProbingReader`; the tests extend it.
- `app/src/sharedTest/.../components/MessageAttachmentsTest.kt` → existing screen coverage; `loading_saysSo`, `eachComposedAttachment_isReportedShownByItsId` and `loadingFailedAndNotFound_offerNeitherOpenNorSave` rely on "no state = loading" for typed non-image files and must be reconciled.
- `app/src/androidTest/.../e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart`, `readyAttachmentRow`, `awaitReadyAttachmentRow`, `assertOpensAndSaves`.
- `docs/knowledge/features/message-bubble-attachment-slot.md` § "Attachment slot", "Load lifecycle" — the claim is a compare-and-set inside `_attachmentStates.update`; only `Ready` acts; `rememberUpdatedState(states)` in the actions.
- Desktop `src/renderer/src/screens/conversation/attachmentIsImage.ts` (per the ticket body): png, jpg, jpeg, gif, webp, avif, bmp; exact, case-insensitive, text after the last dot; no dot → not an image.

In-flight overlap: #1311, #1320, #1321, #1325, #1327, #1341, #1359, #1386 touch `ThreadViewModel`, `ThreadScreen` or `MainActivity`. #1325 and #1327 touch the upload side of attachments only; none touches retrieval or the message-attachment slot. Edits to shared files stay additive.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=132-4605 (File field) and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8 (Thread attachments)

No visual change. A file not yet fetched draws as the existing ready `File field` row: the 45 × 60 page glyph with its type label, 12dp, then the name on one line, no status line. The Figma MCP was not authorised in this session, so the summary is taken from the shipped row (#984, #1290's capture), which the ticket names as the target.

## Context

Every attachment row reports itself shown on composition, and the view model retrieves on that report whatever the type, so scrolling a thread of PDFs pulls each one over the relay. Desktop fetches on sight only what it draws as a picture. This ticket copies that rule.

## Design

### The fetch-on-show rule

New in `MessageAttachments.kt` (components package, next to `AttachmentTarget`):

- `internal fun isImageAttachmentName(name: String): Boolean` — text after the last `.` compared case-insensitively and exactly against `png, jpg, jpeg, gif, webp, avif, bmp`. No dot → `false`. Mirrors desktop; no other types.
- `internal fun loadsOnShow(attachment: MessageAttachment): Boolean`:
  - non-blank `mimeType` → `startsWith("image/", ignoreCase = true)`;
  - else non-blank `displayName` → `isImageAttachmentName(displayName)`;
  - else (neither) → `true` (the history-replayed row; its kind is only learnt by retrieving it).
- `internal fun attachmentTarget(attachment: MessageAttachment, ready: AttachmentViewState.Ready?): AttachmentTarget` — the reference's non-blank name and type, each filled from `ready`'s hints. Extracted from `MessageAttachmentItem` so the view model builds the same target the row shows.
- `enum class AttachmentAction { OPEN, SAVE }` — what a tap on a not-yet-fetched file asked for.

### View model (`ThreadViewModel`)

- `fun onAttachmentShown(attachment: MessageAttachment)` (signature changes from the id): returns unless `loadsOnShow(attachment)`; otherwise today's claim-from-absent and load.
- `fun onAttachmentRequested(attachment: MessageAttachment, action: AttachmentAction)`: claims the id from absent (`claimAttachment { it == null }`); only when it did, records `pendingRequests[id] = attachment to action` and starts `loadAttachment(id)`. A tap on an id already loading, ready or failed claims nothing and records nothing.
- `loadAttachment` unchanged up to settling the state. After writing the settled state it removes the id's pending request; when one existed and the state is `Ready`, it sends one `AttachmentLoaded(attachmentTarget(attachment, ready), ready.source, action)` to a buffered channel. `Failed` and `NotFound` drop the request silently. A later Retry claims from `Failed` and records no request, so a retried load never opens or saves by itself.
- `val attachmentLoads: Flow<AttachmentLoaded>` — `Channel(BUFFERED).receiveAsFlow()`, the `attachmentRefusals` idiom.
- `pendingRequests` is a plain map touched only on the main thread (every call site is a main-thread entry point or `viewModelScope`'s `Main.immediate` continuation), documented as such, like `_attachmentsSending`.

### Effect type (`AttachmentActions.kt`)

- `data class AttachmentLoaded(val target: AttachmentTarget, val source: AttachmentSource, val action: AttachmentAction)` with a redacted `toString` (id and action only).
- `AttachmentActions` gains `val loaded: (AttachmentLoaded) -> Unit`. OPEN runs the same routing as `open` (markdown name → `onOpenMarkdown(id)`, else `openAttachment`), but with the source the effect carries rather than one read from the live states: the effect can reach the collector before the recomposition that updates `rememberUpdatedState(states)`, so a states lookup would miss and the tap would silently do nothing. SAVE launches the picker as `save` does, without its `Ready` guard for the same reason; the picker result still resolves the source from the live states, which by then are current. `open` is refactored to look up the source and then call the shared routing.

### Drawing (`MessageAttachments`)

- `MessageAttachments` passes `states[id]` (nullable) to the item instead of defaulting to `Loading`, and gains `onRequest: (MessageAttachment, AttachmentAction) -> Unit = { _, _ -> }`. `onShown` becomes `(MessageAttachment) -> Unit`; the item still reports every composed row, and the view model decides.
- In `MessageAttachmentItem`: no state and `loadsOnShow` → `Loading` exactly as today. No state and not `loadsOnShow` → the file row with no status line, its click modifier calling `onRequest(attachment, OPEN)` on tap and `onRequest(attachment, SAVE)` on long-press, with the same open/save click labels. `AttachmentFileRow` takes a nullable state, where `null` draws like `Ready`.
- `MessageBubble` gains `onRequestAttachment` and changes `onAttachmentShown` to `(MessageAttachment) -> Unit`. `ThreadScreen` does the same, adds `attachmentLoads: Flow<AttachmentLoaded> = emptyFlow()`, and collects it in a `LaunchedEffect(attachmentLoads, attachmentActions)` into `attachmentActions.loaded`. `MainActivity` wires `vm::onAttachmentRequested` and `vm.attachmentLoads`.

## State + concurrency model

- One `viewModelScope` job per claimed load, as today; the claim stays the compare-and-set in `_attachmentStates.update`, so a row shown twice or tapped twice starts one load and records at most one request.
- A request is settled exactly once: it is removed in the same continuation that writes the settled state, so one load yields at most one effect.
- The effect is a cold-per-collector `receiveAsFlow` over a buffered channel. If the thread is not composed when the load settles (the operator navigated forward to another destination with the view model alive), the effect waits and runs when the thread recomposes. Leaving the thread back to the list clears the view model, its jobs and its channel.

## Error handling

- `Failed` / `NotFound` after a tap: today's failed row with Retry, or not-found; the request is dropped; nothing opens or saves.
- Open and save failures after a delivered effect: today's notices (`NO_APP`, `OPEN_FAILED`, `SAVE_FAILED`) through the same snackbar.
- Logs: `event=thread_attachment_request id=<A> action=open|save` when a tap claims a load, and `event=thread_attachment_request id=<A> outcome=delivered|dropped` when it settles. Id and static codes only; never a name, type, URI or path. The existing `thread_attachment_load` line is unchanged.

## Testing strategy

Unit (`ThreadViewModelAttachmentRetrievalTest`, counting `RetrievingRepository.retrievals`):
- An image and a PDF with known types, both shown → only the image retrieved.
- Name-only `.png` (and `.PNG`) shown → retrieved; name-only `.pdf` and a dotless name → not.
- No type and no name shown → retrieved.
- A deferred PDF tapped → one retrieval, one `AttachmentLoaded` with OPEN, the reference's name and the retrieved type, `Kept` source; a second tap while loading → still one retrieval and one effect.
- Long-press → SAVE effect.
- Tapped load ends `Failed` → no effect; Retry then succeeds → `Ready`, still no effect. Tapped load ends `NotFound` → no effect.
- A deferred file this phone sent, still readable → no retrieval, effect with the `Original` source.
- Existing tests switch to typeless, nameless references (`MessageAttachment(id)`), which load on show as before.
- Logs carry the id and static codes only.

Pure (`app/src/test/.../components/AttachmentLoadsOnShowTest`): `isImageAttachmentName` over each desktop extension, case, last-dot, dotless, trailing-dot; `loadsOnShow` type-wins-over-name.

Screen (`app/src/sharedTest`, Robolectric):
- `MessageAttachmentsTest`: a typed non-image file with no state draws its name, no "Loading…", and has a click action; tap → `onRequest(OPEN)`, long-press → `onRequest(SAVE)`, `onOpen`/`onSave` untouched. A nameless typeless reference with no state still draws loading with no click action. Reconcile `loading_saysSo` (use a nameless typeless reference), `eachComposedAttachment_isReportedShownByItsId` (ids from the reported references) and `loadingFailedAndNotFound_offerNeitherOpenNorSave` (give the fourth row an explicit `Loading` state), each keeping its intent.
- New `ThreadScreenAttachmentLoadTest`: a deferred PDF row in `ThreadScreen` tapped → `onRequestAttachment(OPEN)`; an `AttachmentLoaded` OPEN for a markdown name → `onOpenMarkdownAttachment(id)` once, with `attachmentStates` deliberately not yet holding `Ready` (proves the carried source is used); an `AttachmentLoaded` SAVE → the create-document picker launched once, observed through a fake `ActivityResultRegistryOwner` (works under Robolectric and on device); a failed row shows Retry and no load is delivered.

Live: `interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart` — the offer is name-only `.txt`, so it now draws deferred. `assertOpensAndSaves` already taps the row before waiting for `ACTION_VIEW`; its wait grows from `THREAD_TIMEOUT_MS` to `REPLY_TIMEOUT_MS` because the tap now includes the retrieval, and the helper and scenario docs say the row may be fetched by that tap. The other attachment scenarios use history rows (no name, no type, still load on show) or the phone's own send. The dispatcher runs the live suite after verifier (`needs-real-claude`).

## Open questions

- None blocking. If `ActivityResultRegistryOwner` injection proves awkward inside `ThreadScreen` under Robolectric, the SAVE delivery is proven at the `AttachmentActions` level instead and recorded in Revisions.

## Documentation handoff (pending — documentation stage)

- `docs/knowledge/features/message-bubble-attachment-slot.md` § "Attachment slot": the fetch-on-open rule (image by type, else desktop's extension set for name-only references, else load on show) and the history-row exception.
- `docs/knowledge/features/attachment-retrieval.md` § "Facade": the same rule from the retrieval side.
- `docs/e2e-interactive-stream.md`: the claude-offered-file scenario's note — the offered row is fetched by its first tap.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the new decision reads `MessageAttachment.displayName` / `mimeType`, which a daemon offer or a peer can set. `isImageAttachmentName` only compares the suffix against a fixed set; the worst a hostile name achieves is choosing fetch-on-show versus fetch-on-tap for its own row, both of which were already reachable (today everything fetches on show). The name keeps its existing render path: `Text` with `maxLines = 1`, never a URL, filename or log field. The save picker's suggested name and the view intent's type keep their existing sanitisation (`attachmentIntentType`, the picker's title extra).
- [Tokens] No findings — no tokens, keys or credentials touched.
- [File / storage] No findings — sources are unchanged (`Kept` through the non-exported `FileProvider` with its single root; `Original` only when `isForeignContentUri`). The effect carries an `AttachmentSource` the view model produced, never a path from the wire. The pending save still keeps only the id in the saved-state bundle.
- [Inter-process] No findings — no new intents, filters or exported components; open and save reuse `openAttachment` and the create-document contract unchanged. An effect can only start an open or save for a row the operator tapped.
- [Crypto] Not applicable — no cryptographic material.
- [Network & I/O] No findings — this reduces relay traffic; a retrieval still goes through `retrieveAttachment` with its existing size cap. A tap claims at most one load (compare-and-set), so repeated taps cannot multiply requests.
- [Logs] No findings — new lines carry the id and static codes only; `AttachmentLoaded.toString` is redacted; tests assert no name or URI in logs.
- [Concurrency] SHOULD FIX (handled in design) — a delivered effect must not race the states recomposition; the effect carries the source, and `loaded` does not re-guard on live states. Double open is prevented by settling each request once in the same continuation as the state write; an open after a failure is prevented by dropping the request on `Failed`/`NotFound` and by Retry never recording one.
- [Threat model] OUT OF SCOPE — a hostile host can still serve any bytes for an id the operator taps; that is the #985 open/save threat model, unchanged here.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-01
