# #1017 — Live proof that an interrupted attachment transfer recovers without duplicates or cross-host mix-ups

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes` and `interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart` (#1016: the shapes the new methods copy), `interactiveTurn_twoHostsCollidingConversationId_stayPerHost` (#847: the seeded collision and host B's pairing), `setHostLink` / `cycleHostLink` (#850: the cut drive), and the helpers reused as they are: `runningToolPeer`, `answerChat`, `assertPeerAnswers`, `openChatRow`, `openRow`, `leaveThread`, `sendFromPhone`, `allowPromptsUntil`, `userMessages`, `awaitCachedSentAttachmentIds`, `readyAttachmentRow`, `awaitReadyAttachmentRow`, `assertOpensAndSaves`, `documentFixture`, `insertDownload`, `deleteFixtures`, `sha256`, `offerPrompt`, `pairHostByCode`, `hostRepository`, `scrollListTo`, `composerText`.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt` → `history`, `retrieveAttachment`, `recorded` — used unchanged.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/ActivityIntentStub.kt` → answers the document picker and the viewer.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayLog.kt` → `RelayLog.sink` (internal, test-reassignable) and `enabled` (`BuildConfig.DEBUG`, so on in the androidTest build). This is the cut seam.
- `app/src/main/java/de/pyryco/mobile/data/repository/MessageCommands.kt` → `uploadAttachment`: logs `event=attachment_chunk id=… index=i total=n` after each chunk's send succeeds, and a later failed send or an ended inbound settles `ReconnectRequired`.
- `app/src/main/java/de/pyryco/mobile/data/repository/AttachmentRetrieval.kt` → `AttachmentRetrievals.fetch`: logs `event=attachment_request id=…` after the transfer is registered and before the request is sent; a refused send or `end()` settles `Unavailable`.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt` → `close()` is synchronous and `@Synchronized`, and closes the live connection at once.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `sendWithAttachments` (a failed upload keeps the text and every entry; nothing is sent), `onAttachmentShown` / `onRetryAttachment` (no automatic retry on reconnect; only Retry reloads a failed row).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageAttachments.kt` → `AttachmentFileRow`: `MESSAGE_ATTACHMENT_FILE_TEST_TAG`, the name, `R.string.thread_attachment_failed`, and the Retry `TextButton` (`R.string.thread_attachment_retry`); the row merges and takes clicks only once ready.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ComposerDraftStore.kt` → app `single`, keyed by host and conversation.
- `docs/knowledge/features/attachment-upload.md`, `attachment-retrieval.md` — the upload lock, the partial upload the daemon discards with its connection, the retrieval's `Unavailable` on a dropped connection.
- `docs/e2e-interactive-stream.md` § the #1016 paragraph — history drops a user message's `attachment_ids` (#1020); the LIVE list only filters, and JUnit orders by name hash.
- Issue #1020 — also states that interactive mode streams no user-message event live, so another client's file cannot reach the phone at all until it lands.
- `scripts/e2e-emulator.sh` → `seed_collision_conversation` (unbound, `cwd` the daemon's `$HOME`, which the daemon confines and trust-marks) and the LIVE `TEST_TARGET` list; `scripts/android-test-gate.py` → `LIVE_MINIMUM`; `scripts/test_android_test_gate.py` already asserts the list and the minimum agree.
- `../pyrycode/cmd/pyry/main.go` → `resolveSpawnDir` (a `$HOME` cwd is allowed and trust-marked); `../pyrycode/internal/attachments/storage.go` (stored files live under the instance directory, not the workspace).

In-flight overlap: `feature/1021` and `feature/955` also append LIVE entries, `LIVE_MINIMUM` lines and new methods to the same three files. That is additive, not a dependency; edits here stay appends.

## Design source

**Figma:** N/A — test-only ticket; no UI changes.

## Context

The phone's recovery from a dropped link is proved only against fakes. This adds three rung-3 methods on the live harness. No production code changes.

**AC-2's file source is claude's offered file, not another client's.** A file another client names on a message reaches the phone neither live (interactive mode streams no user-message event) nor by history replay (the daemon's log drops `attachment_ids`). Both are #1020, which is open. The intent of AC-2 is that an interrupted retrieval fails visibly with Retry, and that Retry then brings the right bytes. The retrieval path does not depend on the file's origin: an offered file is fetched with the same `request_attachment`, `AttachmentStore` and row states. So the method uses the file claude hands over with `send_file`, which #1016 proved reaches the phone live. Once #1020 lands, a peer-sent source can replace it. The PR states this substitution.

**The cut is deterministic, not a race.** Both cuts go through the `RelayLog` sink. The test installs a wrapping sink that still forwards every line. When it sees the line it is armed for, it calls `RelayConnectionSupervisor.close()` for the first host once, synchronously, on the thread that logged it. So:

- **Upload:** the cut comes right after chunk 1 of a 3-chunk document has been handed to the socket. Chunk 2 is never sent, so the daemon cannot complete the set, cannot answer `attachment_stored`, and drops the partial upload with the connection. The next send is refused or the ended inbound settles it, and either way the upload settles `ReconnectRequired`. The ticket's large-fixture idea only widens a timing window. A cut before the last chunk is sent is the one point that cannot race the acknowledgement.
- **Retrieval:** the cut comes at `event=attachment_request`, after the transfer is registered and before the request is sent. The row is `Loading` at that moment, and the retrieval settles `Unavailable` without the daemon being asked.

The log lines carry only ids, indices and totals, so matching them reads no user content.

## Design

### Cut helper (test class, private)

`cutLinkOn(serverId, matches: (String) -> Boolean): LinkCut`. It swaps `RelayLog.sink` for a wrapper that forwards to the previous sink and, the first time a message passes `matches`, closes that host's supervisor (`RelayConnectionRegistry.connectionFor(serverId).supervisor`) and counts down a latch. `LinkCut.await(timeoutMs)` waits for the latch and then calls `setHostLink(serverId, up = false)`, which is idempotent and waits until the coordinator's repository is gone. The failure names the step. `LinkCut.close()` restores the previous sink. It is called right after the cut and again in `finally`.

### Test methods (one real-claude turn each)

1. **`interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes`** (AC-1). First host, peer from `runningToolPeer`, fresh chat X from `answerChat`. The phone attaches a ~100 KB `documentFixture` (3 chunks) through **Attach files**, stubbed as in #1016. The test arms a cut for `event=attachment_chunk ` with `index=1 total=3` and sends `PING_PROMPT`.
   - After the cut, the send has failed. The composer is idle, meaning the "Sending" indicator is gone. The composer still holds `PING_PROMPT`, and the strip tile still shows the file's name. The peer's `history(X)` holds 0 user messages.
   - Then the test restores the link, waits for `awaitConnected`, and taps Send again. `allowPromptsUntil` waits for X's `turn_end`.
   - The peer's `history(X)` holds exactly 1 user message. The phone's cached sent row names exactly 1 id (`awaitCachedSentAttachmentIds`, since history drops ids, #1020). The peer's `retrieveAttachment` of that id matches the fixture's SHA-256.
2. **`interactiveTurn_interruptedRetrieval_retryLoadsTheOfferedFile`** (AC-2). First host, fresh chat X, opened on the phone. The test arms a cut for `event=attachment_request ` and sends `offerPrompt(content, fileName)`. `allowPromptsUntil` waits for X's `turn_end`, and the peer allows the Bash and `send_file` prompts.
   - The peer recorded X's `attachment_offered` naming `fileName`. The cut fired, and the link is down. The row for `fileName` shows the failed text and a Retry button (`failedAttachmentRow`).
   - Then the test restores the link and waits for `awaitConnected`. It taps that row's Retry, and the row becomes ready (`awaitReadyAttachmentRow`). `assertOpensAndSaves` yields the content's digest on open, and on save as well.
3. **`interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost`** (AC-3). This test pairs host B by code as #847 does, and removes it in `finally`. It is the same code-reuse pattern #966's two methods use. The current names of the seeded collision conversation on each host are read by id from that host's repository (`heldName`), since #847's method renames A's copy and may run first. The peer is on host A.
   - Open A's copy, attach a document fixture, and wait for the strip tile. Leave, then open B's copy. It shows no strip tile and no file row with that name. Leave, then reopen A's copy. The tile is still there. Send `PING_PROMPT`, and `allowPromptsUntil` waits for A's `turn_end`.
   - A's side: the peer's `history` holds exactly 1 user message. The phone's cached sent row on A names 1 id, and the peer's `retrieveAttachment` of it matches the digest. The phone draws the ready row in A's thread.
   - B's side: after leaving A, B's copy shows no row and no tile with that name. B's thread cache holds no message naming the id. Host B's own `fetchAttachment(collisionId, id)` is `AttachmentRetrievalResult.NotFound`.

New private helpers: `cutLinkOn` / `LinkCut`, `failedAttachmentRow(name)` (merged tree: the Retry button inside the file row carrying `name`), `assertComposerHoldsFile(name)` / `assertNoFileNamed(name)` (strip tile by content description, file row by text, unmerged tree), and `heldName(serverId, conversationId)`. A shared private `attachDocument(stub, name, bytes, inserted)` picks one fixture and waits for its tile.

Method names were chosen for their place in JUnit's name-hash order (see `docs/e2e-interactive-stream.md`). The collision method runs after #847's, but before `backgroundTask`, where #1016 once saw dead peers.

### Scripts

- `scripts/e2e-emulator.sh`: three appends to the LIVE list, with a `#1017` comment (32 methods, 34 turns). The header count is updated to match.
- `scripts/android-test-gate.py`: `LIVE_MINIMUM += 3`, with a comment naming #1017. The existing agreement test covers it.

## State + concurrency model

Test code only. The wrapping sink runs on whatever thread logs, which here is the view model's coroutine. It calls only `supervisor.close()`, which is synchronous and holds no lock the logging code holds. The upload loop and the retrieval hold a `Mutex`, not a monitor, and the inbound collector's `end()` runs later on its own coroutine. The sink's "fired" flag is an `AtomicBoolean`. The previous sink is captured before the swap and restored in `finally`.

## Error handling

Waits time out into `AssertionError`s that name the step: the cut never fired, the send did not fail, no failed row, and so on. Failures name counts, digests of generated fixtures and static codes, never claude text. The monitor, the `MediaStore` fixtures, the peer, the sink and host B are released in `finally`.

## Testing strategy

The deliverable is itself three rung-3 methods, run by the dispatcher's post-verifier `needs-real-claude` LIVE gate. Builder-side proof covers `compileDebugAndroidTestKotlin`, `assembleDebug`, `lint`, and `python3 -m unittest scripts/test_android_test_gate.py` (the list and minimum agree). There is no rung-4 twin. The scripted `fakeclaude` cannot call `send_file` or read attachments, and the upload cut needs a real daemon's reassembly. The cut itself is deterministic.

## Open questions

- Can the peer's history show the failed attempt's user message? No. The upload fails before `send_message`, which the "0 user messages" check after the cut asserts.
- Does a reconnect's history merge keep the offered row in AC-2? #1016 showed that an offered row survives a restart's merge. If the live run shows otherwise, that is a product bug for a separate ticket.

## Documentation handoff

Pending for the documentation stage: add the interrupted-transfer and cross-host scenario to `docs/e2e-interactive-stream.md`. Cover its three curated-list entries, the three real-claude turns and the `RelayLog`-sink cut drive, including the AC-2 offered-file substitution and why (#1020). Also record the mobile and daemon revisions and the result of its first live run.

## Revisions

- 2026-09-24, during implementation: the helper names settled as follows. `failedAttachmentRow` became `attachmentRetry(name)`, built on `inAttachmentRow(name, node)`, and both match in the unmerged tree, since a row that is not ready takes no clicks and may not be a merged node of its own. `assertComposerHoldsFile` became `awaitComposerHolds(text, name)`, and the sign that the send has ended is the tile's **Remove** control, which is drawn only while no send is under way. `LinkCut.await` takes a `poll` that AC-2 uses to keep the offered row on screen until the cut. Every method restores its host's link in `finally`, so a failure between cut and restore cannot leave later methods offline. No contract changed.
