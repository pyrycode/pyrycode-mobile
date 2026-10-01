# #1325 — Say why a file upload failed

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/AttachmentUpload.kt` → `AttachmentUploadResult`, `AttachmentUploadTransfer` (`fail` is first-outcome-wins), `AttachmentUploadLimit.MAX_BYTES`, `MALFORMED_REFUSAL` — the result type gaining two members.
- `app/src/main/java/de/pyryco/mobile/data/repository/MessageCommands.kt` → `uploadAttachment` (the chunk loop's send-failure branch), `beginUpload`, `endAttachmentUploads` — the three places that today all say `ReconnectRequired`.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `uploadAttachment` — keeps returning `ReconnectRequired` with no live repository; no edit.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `sendWithAttachments`, `upload`, `attachmentSendFailed`, `attachmentRefusals` — where the silent stop happens and the one-shot channel idiom to copy.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AttachmentReader.kt` → `AttachmentRead` (`Unreadable`, `TooLarge`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → the `attachmentRefusals` `LaunchedEffect` on `snackbarHostState` — the collector the new one sits beside.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `ThreadScreen(attachmentRefusals = vm.attachmentRefusals, …)` binding.
- `app/src/main/res/values/strings.xml` → `thread_attachments_*` strings.
- `app/src/test/.../data/repository/RemoteConversationRepositoryAttachmentTest.kt` → `refusedSend_failsAsReconnectRequired_withoutFurtherChunks`, `connectionDropMidUpload_failsAsReconnectRequired_andALaterUploadSendsNothing`, `progress_reportsNothingForAFailedSend` — the tests that change expectation.
- `app/src/test/.../ui/conversations/thread/ThreadViewModelAttachmentTest.kt` → `RecordingRepository`, `FakeReader`, `aFailedUpload_keepsEverything_andARetryUploadsOnlyWhatIsMissing` — fakes the new tests reuse.
- `app/src/sharedTest/.../ui/conversations/thread/ComposerAttachmentStripTest.kt` → the refusal-snackbar screen test pattern.
- `app/src/androidTest/.../e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes` — must pass unchanged. It asserts on composer contents and taps Send; the snackbar host sits above the Scaffold's `bottomBar`, where the composer lives, so the notice never covers Send.
- `docs/knowledge/features/attachment-upload.md` § "The transfer", § `MessageCommands`.

In-flight overlaps: #1311 (`ThreadViewModel.sendWithAttachments` near the repository send, `ThreadScreen`, `MainActivity`, `strings.xml`) and #1321, #1341, #1359 (`ThreadScreen`). None adds what this needs or rewrites the upload block; edits here stay additive.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=390-7181

No visual change (per the ticket): the notice is a plain M3 `Snackbar` in the thread's existing `SnackbarHost`, the same one the pick-time refusals use. The Figma MCP was not authorised in this session; nothing new is drawn, so nothing is read from the node.

## Context

A send with attachments that fails a read or an upload stops silently: `ThreadViewModel.upload` logs and returns `null`, text and tiles stay, and the only visible change is the strip's "Sending" ending. The daemon's refusal code is never read, and `MessageCommands.uploadAttachment` folds "not connected", "connection dropped mid-upload" and "the socket would not take a chunk" into `ReconnectRequired`. Desktop shows one sentence per reason; this copies those reasons and sentences (ticket table).

## Design

### Data — `AttachmentUploadResult` (`AttachmentUpload.kt`)

Two new `Failed` members beside the existing ones:

- `data object ConnectionLost : Failed` — the inbound collector ended while this transfer was live.
- `data object SendFailed : Failed` — a chunk's `send` returned `false` or threw.
- `ReconnectRequired` narrows to "not connected before the first chunk" (kdoc updated): `beginUpload` refused, or `StableConversationRepository` has no live repository.

`MessageCommands`: the chunk loop's `!sent` branch fails the transfer `SendFailed`; `endAttachmentUploads` fails the active transfer `ConnectionLost`; `beginUpload` false still returns `ReconnectRequired`. The two failures race (a closed socket refuses a chunk while the collector ends); `AttachmentUploadTransfer.fail` is first-outcome-wins, so the result is whichever settled first, and both are honest. The settle log keeps `outcome=${outcome::class.simpleName}` — now also `ConnectionLost` / `SendFailed`, static class names.

### UI — `AttachmentSendFailure` (new `ui/conversations/thread/AttachmentSendFailure.kt`)

```kotlin
enum class AttachmentSendFailure(@StringRes val message: Int) {
    UNREADABLE, TOO_LARGE, NOT_CONNECTED, CONNECTION_LOST, SEND_FAILED,
    HOST_INVALID_CHUNK, HOST_INTEGRITY_FAILED, HOST_TOO_LARGE, HOST_TOO_MANY_UPLOADS,
    HOST_STORAGE_FAILED, HOST_MESSAGE_TOO_LONG, HOST_INCOMPLETE, UNCLASSIFIED,
}
fun attachmentSendFailure(read: AttachmentRead): AttachmentSendFailure?          // null for Bytes
fun attachmentSendFailure(result: AttachmentUploadResult.Failed): AttachmentSendFailure
fun formatMegabytes(bytes: Int): String                                          // "8", "8.5"
fun AttachmentSendFailure.text(resources: Resources): String
```

- One enum entry per sentence in the ticket table; `not_found` and `stream_aborted` share `HOST_INCOMPLETE`.
- `Refused` maps by a `when (code)` against private `const val` codes (`"attachment.invalid_chunk"`, …). Anything else, `MALFORMED_REFUSAL` included, is `UNCLASSIFIED`. The code is never a map key, format argument, or log field.
- `formatMegabytes`: decimal megabytes rounded to one decimal, trailing `.0` dropped (desktop's `formatByteLimit`), integer arithmetic so locale cannot change it. `8_010_000` → `"8"`.
- `text` resolves `message`; `TOO_LARGE` alone passes `formatMegabytes(AttachmentUploadLimit.MAX_BYTES)` into `%1$s MB`.

### `ThreadViewModel`

- `private val attachmentSendFailureChannel = Channel<AttachmentSendFailure>(Channel.BUFFERED)`; `val attachmentSendFailures: Flow<AttachmentSendFailure>` beside `attachmentRefusals`.
- `upload` maps a read failure or a non-`Stored` result through the functions above and passes the failure to `attachmentSendFailed(outcome, failure)`, which keeps its existing log line (`read_too_large` / `read_failed` / `upload_failed`) and `trySend`s the failure. The send stops at the first failure, so one send emits at most one notice. Text, tiles and acknowledged ids are untouched (existing behaviour), so a second Send re-uploads only entries without an id.

### `ThreadScreen` + `MainActivity`

- New parameter `attachmentSendFailures: Flow<AttachmentSendFailure> = emptyFlow()` next to `attachmentRefusals`; a `LaunchedEffect(attachmentSendFailures, snackbarHostState)` shows `failure.text(resources)`.
- `MainActivity` binds `attachmentSendFailures = vm.attachmentSendFailures`.
- `strings.xml`: twelve `thread_attachment_send_*` strings with the ticket's sentences (one, `too_large`, with `%1$s MB`).

## State + concurrency model

No new jobs. The channel is written from `viewModelScope` (Main.immediate) inside the existing `launchGuardedRepoCall`; `trySend` on a buffered channel never suspends. Collected by the screen's `LaunchedEffect`, cancelled with the composition; a notice emitted while no screen collects waits in the buffer, as `attachmentRefusals` does.

## Error handling

Every failure of the upload phase surfaces as exactly one fixed local sentence. A failure of the final `sendMessage` after every upload succeeded stays as today (out of scope). `uploadAttachment` still never throws except on cancellation.

## Testing strategy

- `RemoteConversationRepositoryAttachmentTest`: `refusedSend_…` and `progress_reportsNothingForAFailedSend` expect `SendFailed`; the mid-upload drop expects `ConnectionLost` and the later upload (inbound already ended) `ReconnectRequired` — all three members proven. Tests renamed to match.
- New `AttachmentSendFailureTest` (unit): every `Refused` code row, an unknown code and `error.malformed_reply` → `UNCLASSIFIED`; each local `Failed` member; `formatMegabytes(MAX_BYTES) == "8"` plus a fractional and a round case.
- `ThreadViewModelAttachmentTest`: one test per table row — `Unreadable`, read `TooLarge`, upload `TooLarge`, `ReconnectRequired`, `ConnectionLost`, `SendFailed`, and each `Refused` code row (including unknown and malformed) — each asserting exactly one emitted failure, draft text kept, all tiles kept. `aFailedUpload_keepsEverything_andARetryUploadsOnlyWhatIsMissing` additionally asserts one notice and that the retry emits none.
- `ComposerAttachmentStripTest` (Robolectric): a `TOO_LARGE` failure on the new flow shows "Too large to attach — this app sends files up to 8 MB." in the snackbar (proves wiring and the derived figure).
- Live: `interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes` unchanged; the dispatcher's live gate runs it (`needs-real-claude`). No new rung-3 scenario: the operator-facing flow (send → fail → retry) already has one; the notice is a snackbar text on it.

## Open questions

- None blocking. Whether the e2e's cut settles `ConnectionLost` or `SendFailed` depends on which side notices first; the test asserts neither.

## Documentation handoff (pending — documentation stage)

- `docs/knowledge/features/attachment-upload.md` § "The transfer": the new `ConnectionLost` / `SendFailed` members and narrowed `ReconnectRequired`.
- `docs/knowledge/features/thread-screen-composer-drafts-and-attachments.md` § "Composer pending attachments": the failure notice.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the daemon's `code` (already bounded to 64 chars in `AttachmentUploadTransfer.refusal`) crosses into UI only through `attachmentSendFailure(result)`, which compares it with `when` against fixed constants and returns an enum; the enum is all that leaves the function. Unknown text collapses to `UNCLASSIFIED`. No daemon text reaches a string resource argument, snackbar, or log.
- [Tokens] No findings — no tokens, keys or credentials touched.
- [File / storage] No findings — no new file or path handling; the reader and its content-URI boundary are unchanged.
- [Inter-process] No findings — no intents, deep links, providers or WebViews; the snackbar is fixed local text in the existing Activity window, same posture as the other thread snackbars.
- [Crypto] No findings — Noise and chunk digests untouched.
- [Network & I/O] No findings — no new frames; the chunk loop still stops at the first failure, and the 8 MB local bound is unchanged.
- [Logs] No findings — the VM log keeps its static `outcome=` codes; `MessageCommands` logs only result class names. SHOULD FIX check in Phase B: no test or log line may print `Refused.code`; `logs_neverCarryAUriOrAName` stays green.
- [Concurrency] No findings — the `ConnectionLost` / `SendFailed` race is resolved by `CompletableDeferred.complete` (first wins) under the existing `@Synchronized` helpers; no new shared state.
- [Threat model] Hostile daemon frame: an arbitrary or oversized code yields `UNCLASSIFIED` or the existing malformed-refusal path; a flooding daemon cannot emit more than one notice per send, because the transfer settles once and the send stops.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-01

## Revisions

### 2026-10-01 — live gate rework (inherited failures)

The live gate ran 43 tests: 38 passed, 5 failed, 0 skipped. `interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes` executed and passed unchanged. All five failures also fail on unmerged `main` (`ca43a4c7`). They are settings and footer scenarios, which #1325 does not touch. Cause: since #1320 the settings subscription starts with the held, invalidated reading, and the e2e helper `freshSettings` takes that head as the live reply. Filed as #1397. Repairing it belongs to #1320's area, so this branch isolates the five with `@Ignore("blocked on #1397 …")`; #1397 re-enables them. No production or design change.
