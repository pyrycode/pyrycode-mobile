# #1016 — Live proof that attachments cross between the phone and another client

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_peerStartedTurn_continuesOnPhone`, `interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect` (peer + chat shape), `interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel` (destroy activity → `rebuildGraph` → relaunch), `allowPromptsUntil`, `runningToolPeer`, `hostRepository`, `openChatRow`, `leaveThread`, `sendFromPhone`, `awaitCachedAssistantReply` — every helper the new methods reuse.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt` → `sendMessage`, `request`, `send`, `awaitFrame`, `recorded` — where the upload, retrieve and history helpers go.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt` → `rebuildGraph` — the restart the reload criteria use.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ComposerImagePasteDeviceTest.kt` → `insertPng` — the `MediaStore` fixture source.
- `app/src/main/java/de/pyryco/mobile/data/network/AttachmentPayloads.kt` → `AttachmentChunkPlan`, `AttachmentChunkPayloadDto`, `AttachmentOfferedPayloadDto`, `RequestAttachmentPayloadDto`, `ATTACHMENT_CHUNK_BYTES` — the peer builds its frames with the app's own types.
- `app/src/main/java/de/pyryco/mobile/data/network/HistoryPayloads.kt` → `RequestHistoryPayloadDto`, `HistoryPagePayloadDto` — the peer's view of a conversation.
- `app/src/main/java/de/pyryco/mobile/data/network/MessagePayload.kt` → `SendMessagePayloadDto.attachmentIds`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AttachmentPicker.kt` → `rememberAttachmentPicker` (`OpenMultipleDocuments`, `ACTION_OPEN_DOCUMENT`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AttachmentReader.kt` → `isForeignContentUri` — why fixtures must come from `MediaStore`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AttachmentActions.kt` → `openAttachment` (`ACTION_VIEW` via `startActivity`), `CreateAttachmentDocument` (`ACTION_CREATE_DOCUMENT`), `rememberAttachmentActions`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageAttachments.kt` → `MESSAGE_ATTACHMENT_FILE_TEST_TAG`, `AttachmentFileRow` (name text; tap opens, long-press saves).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ComposerAttachmentStrip.kt` → strip tile content description is the display name.
- `app/src/main/java/de/pyryco/mobile/data/repository/AttachmentOfferProjection.kt` → `attachmentOfferRow` — an offer is an assistant `Message` whose one attachment carries the announced name; that row is what the cache keeps.
- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCache.kt` → `writeThread`, `readThread`.
- `../pyrycode/docs/protocol-mobile.md` § Attachments — `attachment_chunk` / `attachment_stored` / `request_attachment` / `attachment_offered`; retrieval chunks correlate by `in_reply_to`.
- `../pyrycode/internal/e2e/realclaude/interactive_stream_send_file_test.go` and `cmd/pyry/attach_file.go` — `send_file` accepts only a path **inside the conversation's workspace**, so the file must be created there first.
- `scripts/e2e-emulator.sh` (LIVE `TEST_TARGET`, header), `scripts/android-test-gate.py` (`LIVE_MINIMUM`), `scripts/test_android_test_gate.py` (list/minimum agreement test).

No in-flight branch touches these files.

## Design source

**Figma:** N/A — test-only ticket; no UI changes.

## Context

The attachment slices (#983, #984, #985) are proved against fakes only. This adds the live proof on rung 3: the phone and `SecondClientPeer` (the desktop stand-in, paired `--allow-remote-permissions` on the first test daemon) exchange files through a real daemon over the live relay. No production code changes.

## Design

### `SecondClientPeer` additions

- `sendMessage(conversationId, text, timeoutMs, attachmentIds: List<String>? = null)` — additive default parameter; existing callers unchanged.
- `suspend fun uploadAttachment(conversationId, filename, mimeType, bytes, timeoutMs): String` — mints a lowercase UUIDv4, sends every chunk of an `AttachmentChunkPlan` as `attachment_chunk`, waits for the `attachment_stored` whose payload `attachment_id` is that id; an `error` replying to any of its chunks fails naming its `code`. Returns the id.
- `suspend fun retrieveAttachment(conversationId, attachmentId, timeoutMs): RetrievedAttachment` — sends `request_attachment`, collects `attachment_chunk` frames with `in_reply_to` equal to the request, reassembles by `index` until `total_chunks` distinct indices; an `error` reply fails naming its `code`. `RetrievedAttachment(filename, bytes)`; `toString` names only the size.
- `suspend fun history(conversationId, timeoutMs): List<HistoryEntryDto>` — walks `request_history` pages (cursor echoed verbatim) until `at_start`.
- `request` is split: a private `exchange` returns the correlated reply envelope; `request` keeps its ack check on top of it.

### Picker and viewer stub — `ActivityIntentStub` (new file under `e2e/`)

An `Instrumentation.ActivityMonitor` built with the no-arg constructor, whose `onStartActivity(intent)` answers intents by action and records them. Registered with `Instrumentation.addMonitor` and removed in `finally`. Platform API only, so no new test dependency (Espresso-Intents would have been one). It answers:

- `ACTION_OPEN_DOCUMENT` → `RESULT_OK` with the fixtures' `MediaStore` URIs in `clipData` (what `OpenMultipleDocuments.parseResult` reads);
- `ACTION_CREATE_DOCUMENT` → `RESULT_OK` with an empty `MediaStore.Downloads` entry the test inserted as the save target;
- `ACTION_VIEW` → `RESULT_CANCELED`; the recorded intent's data URI is read back through the app's own resolver (the test runs in the app's process, so the non-exported attachment provider serves it).

### Fixtures

Generated in memory, inserted into `MediaStore.Downloads` under run-unique names, deleted in `finally`. Digests are SHA-256 of the in-memory bytes.

- Phone → host: a 4 × 4 PNG (`Bitmap.compress`) and a ~100 KB `text/plain` document (three chunks).
- Host → phone: a ~100 KB `text/plain` document the peer uploads (three chunks).
- Offered file: a short ASCII string claude writes with `printf` (no newline).

### Test methods (all on the first test daemon, peer from `runningToolPeer`)

1. `interactiveTurn_attachmentsFromPhone_reachPeerWithTheirBytes` (AC-1). Create X and Y by repository (named, as `answerChat` does), open X, attach both fixtures through the composer's **Attach files** action (stub answers the picker), wait for both strip tiles, send `PING_PROMPT`. `allowPromptsUntil` the X `turn_end` (claude may Read the named files). Peer `history(X)`: exactly one `send_message` entry, naming exactly two ids; `retrieveAttachment` of each returns bytes whose digest set equals the fixtures' digest set. `history(Y)` holds no `send_message`. **One real-claude turn.**
2. `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload` (AC-2). Create X by repository; the phone never opens it. Peer uploads the document and sends `PING_PROMPT` naming its id; `allowPromptsUntil` `turn_end`. Destroy the activity, `rebuildGraph`, then `writeThread(serverId, X, emptyList())` on the new graph and confirm `readThread` is empty; relaunch, open X. The file row shows the uploaded filename exactly once. Tap → the recorded `ACTION_VIEW` content URI's bytes match the digest. Long-press → the save target's bytes match once **File saved** shows. **One real-claude turn.**
3. `interactiveTurn_offeredAttachment_opensAndSavesAfterRestart` (AC-3). Create X by repository, open it (the phone must be attached when the tool runs), send a prompt that runs `printf '<content>' > <name>` with Bash and then calls `send_file` from `pyry_files` on `<name>`. `allowPromptsUntil` `turn_end` answers both prompts. The peer's `attachment_offered` for X names `<name>`; the phone draws a file row with that name, and its thread cache holds an assistant message carrying the offered id. Leave, destroy, `rebuildGraph` (cache kept), relaunch, open X: the row is still there once; open and save each yield the content's digest. **One real-claude turn.**

Shared test helpers: `insertDownload(name, mime, bytes)`, `readUri(uri)`, `sha256(bytes)`, `attachmentRow(name)` (file-row tag with the name, unmerged tree), `awaitAttachmentRow(name)`, `openAndSaveAttachment(stub, name, digest)`, and `restartApp()` (destroy activity → `rebuildGraph` on main → optional hook → relaunch, returning the scenario for `finally`).

Failure messages name counts, digests (of generated fixtures) and static codes; never claude-authored text.

### Scripts

- `scripts/e2e-emulator.sh`: three methods appended to the LIVE list with a `#1016` comment; header and list comments updated to 30 methods and 32 real-claude turns.
- `scripts/android-test-gate.py`: `LIVE_MINIMUM += 3` with a comment naming #1016. `scripts/test_android_test_gate.py` already asserts list/minimum agreement, so it needs no edit.

## State + concurrency model

Test code only. Peer helpers are `suspend` and bounded by `withTimeout`; the test drives them through `runBlocking`, as existing peer calls do. The stub's recorded intents are a `CopyOnWriteArrayList` (written on the main thread, read by the test thread).

## Error handling

Timeouts surface as `AssertionError`s naming the step. A daemon `error` reply fails naming its static `code`. The `MediaStore` entries, the monitor and the peer are released in `finally`.

## Testing strategy

The deliverable is itself rung-3 tests; they run only under `scripts/e2e-emulator.sh` LIVE (the dispatcher's post-verifier `needs-real-claude` run). Builder-side proof: `compileDebugAndroidTestKotlin`, `assembleDebug`, `lint`, and `python3 -m unittest scripts/test_android_test_gate.py` (list/minimum agreement). No rung-4 twin: the scripted `fakeclaude` cannot call `send_file` or read attachments, and nothing here is a transient signal.

## Open questions

- Does `onStartActivity` intercept `ActivityResultRegistry` launches and plain `startActivity`? Both go through `Instrumentation.execStartActivity`, which consults no-arg monitors first; confirmed only on the first live run.
- Does the offered row survive the history merge after a restart? The ticket asserts it does; if the live run shows otherwise, that is a product bug for a separate ticket (Scope Discipline).

## Documentation handoff

Pending for the documentation stage: add the attachment-exchange scenario to `docs/e2e-interactive-stream.md` — its three curated-list entries, three real-claude turns, the offered-file-is-cache-only limit, and the mobile and daemon revisions plus the result of its first live run (the ticket's "record the test revisions and results").
