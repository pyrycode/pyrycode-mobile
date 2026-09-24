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

## Revisions

- 2026-09-24, during implementation: helper names settled as `readyAttachmentRow` / `awaitReadyAttachmentRow` (the file-row tag, the name and a click action — a row only merges its name and takes clicks once ready), `assertOpensAndSaves`, `awaitPeerHistory`, `sentMessages`, `awaitCachedOffer` and `deleteFixtures`. `sentMessages` reads `attachment_ids` straight from each `send_message` entry's JSON rather than decoding the whole stored payload, so a stored field this client does not model cannot fail the count. Chats are created with the existing `answerChat` helper on the first test daemon. No contract changed.
- 2026-09-24, rework after the first live gate run (5 of 30 failed). Evidence: that run's daemon log and claude transcripts on the dispatcher host.
  - **The daemon does not replay attachment ids.** `newOperatorMessageHistory` in `../pyrycode/cmd/pyry/operator_message_history.go` logs the operator's turn as a `message` entry with role `user` and text only. No `send_message` entry is written, and no `attachment_ids`. The ticket's first daemon fact does not hold. The daemon did enqueue the phone's message with `attachment_count=2`. The test waited for a `send_message` entry that never comes, and that caused the AC-1 timeout.
  - **AC-1, revised contract.** Wait for X's `turn_end` first, since the host logs the user turn on delivery. Then read the peer's history once: exactly one user message (`userMessages` counts `message`/`user` and any `send_message`). The two ids come from the phone's own sent row in its thread cache (`awaitCachedSentAttachmentIds`). These are the ids the phone minted and named. The peer's `request_attachment` of each still has to return the fixtures' digests, and Y still gains no user message. The 1-second history poll (`awaitPeerHistory`) is gone. It sent about 88 history requests of about 24 KB each through the live relay.
  - **AC-2 is blocked on #1020.** With no ids in replay, no row can appear after a cleared cache. The method is `@Ignore`d against #1020 and left out of the LIVE list. The list holds 29 methods and 31 turns, and `LIVE_MINIMUM` rises by 2, not 3.
  - **AC-3, unchanged.** Its turn hung on the Bash permission prompt. The prompt stayed pending until teardown (`denied_timeout`), because the peer never saw it. In that run, every peer opened on the first daemon after about 19:42 was dead: the daemon logged its handshake, and then nothing it sent arrived. That covers `peerStartedTurn`'s send, this prompt, and the last method's "peer session is not open". `stopRunningTurn` also opens a peer there, and it failed too. It fails on other branches' gate runs as well. The cause was not established from the logs. The next gate run will show whether it recurs without the history flood.
- 2026-09-24, rework after the second live gate run (2 of 29 failed: `peerStartedTurn` and AC-3). Evidence: that run's daemon log, the claude transcripts, and the other live gate logs of the day on the dispatcher host.
  - **Same failure as before.** Two peers on the first daemon were dead: `peerStartedTurn`'s and AC-3's. The daemon logged each handshake and then nothing from either peer. `peerStartedTurn`'s `send_message` was never enqueued. AC-3's Bash prompt was never seen and stayed pending until teardown. The peers opened before and after them, including AC-1's, worked. AC-1 passed, and claude did not read the attached files in its turn.
  - **Attribution is not established.** `peerStartedTurn` has failed only on this branch's two runs today. But both runs used a daemon that includes pyrycode #2592 (19:12) and #2593 (19:44), and every passing run that day used an older one. In both runs the first dead peer is the one opened right after `backgroundTask`. The gate made no base comparison, and the builder may not run the live suite.
  - **The LIVE list does not order.** JUnit's default sorter runs methods by name hash. AC-1 ran third, the only method from this branch before `peerStartedTurn`. AC-3 ran after it in both runs, so AC-3 cannot have caused its failure.
  - **Change: rename to place the methods.** AC-1 is now `interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes`, which runs last. AC-3 is now `interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart`, which runs between `offlineRead` and `permissionHeldTool`. In both runs every peer opened in that part of the order worked. The next gate run then tells the causes apart. If `peerStartedTurn` still fails, this branch did not cause it.
  - **Change: `assertPeerAnswers`.** After opening its peer and creating X, each method requires one `request_history` round trip. A dead peer then fails within `THREAD_TIMEOUT_MS` and is named as a relay or daemon fault. It no longer times out after 240 s as a `send_file` failure.
  - Contracts unchanged. The Design section's method names 1 and 3 are superseded by the names above.
- 2026-09-24, triage of the third live gate run (1 of 29 failed: `reconnect_slashCommandsAndCompactStillWork`). No code change.
  - **Every method from this branch passed**, including `peerStartedTurn` and AC-3. With the methods renamed, no peer on the first daemon was dead.
  - **The failure is not this branch's.** The failing method uses no peer, and this branch does not change it or any helper it calls. The same assertion failed on both of #955's live runs, earlier the same day, on a tree without this branch. It passed on this branch's two earlier runs with identical code, and on the runs of #967, #996 and #1002. Filed as #1029 with the kept daemon log's lead: claude restarted for a settings change 0.14 s before the phone's reconnect handshake.
