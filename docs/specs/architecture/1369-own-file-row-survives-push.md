# #1369 — The phone's own file row survives the daemon's push of the sent message

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `onInbound`'s `TYPE_MESSAGE` arm. Since #1351 (merged after this ticket was written) a role-`user` push maps `attachment_ids` through `storedAttachmentReferences` and folds through `ThreadProjection.appendLiveMessage`. No change.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadProjection.kt` → `appendLiveMessage` keeps a held `message_id` unchanged; `appendMessages` still upserts. No change.
- `app/src/main/java/de/pyryco/mobile/data/repository/MessageCommands.kt` → `sendMessage`'s confirmed insert after the ack, carrying the cleaned names and MIME hints. No change.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryAttachmentTest.kt` → `sendWithAttachments_theThreadRowCarriesOneReferencePerIdInSendOrder_withItsNameAndMimeType`, the harness (`startSend`, `collectThread`, `ack`) the new tests reuse.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` → `observeMessages_liveUserMessage_appendsRowWithAttachmentReferences` (#1351), which already covers AC 2.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost`, ignored by #1305.
- `scripts/e2e-emulator.sh` → the LIVE curated list, where #1305 dropped the method.
- `scripts/android-test-gate.py` → `LIVE_MINIMUM`, counted from the curated list since 2026-10-01 (`curated_live_methods`), so #1305's `-= 1` is already gone.
- `scripts/test_android_test_gate.py` → `test_live_curated_list_matches_the_runnable_methods` and `test_live_curated_list_excludes_ignored_methods`. #1305's pinned 43 and its `assertFalse` for this method went with the counted floor.
- `../pyrycode/docs/protocol-mobile.md` → the `message` row: a client de-duplicates its own echo by `message_id`.

## Change

The production fix the ticket describes landed with #1351: the live arm keeps a held row and maps a new row's `attachment_ids`, as the history arm does. This ticket adds no production code. It adds repository-level coverage for the phone's own send, which #1351 tested only at the `ThreadProjection` level, and reverts the #1305 isolation now that the cause is fixed.

- Tests: send with named attachments, ack, then the push of the same `message_id`, and the same envelope again as a reconnect replay. Also the push arriving before the ack.
- Revert: remove the `@Ignore` and the ignore paragraph from the method's KDoc, and put the method back on the LIVE list in `scripts/e2e-emulator.sh` where it stood, replacing the #1305 comment. The floor follows the list on its own, and the gate's tests check that the list matches the runnable methods, so the two edits have to land together. `scripts/android-test-gate.py` and `scripts/test_android_test_gate.py` no longer carry a #1369 site and need no edit.

## Testing strategy

New tests in `RemoteConversationRepositoryAttachmentTest`:

- `sendWithAttachments_thenPushOfTheSameMessageId_keepsTheNamedRow_alsoOnReplay`: one row, with the names and MIME hints of the send, after the push and again after a replay of the same push (AC 1, idempotence).
- `pushBeforeAck_theConfirmedInsertLeavesTheNamedRow`: the push appends a row with bare references, then the ack's confirmed insert replaces it with the named row. Still one row.

AC 2 (a push for an id the thread does not hold draws a row from `attachment_ids`) is `observeMessages_liveUserMessage_appendsRowWithAttachmentReferences`, unchanged.

Run `./gradlew testDebugUnitTest --tests` on `RemoteConversationRepositoryAttachmentTest`, `RemoteConversationRepositoryTest` and `ThreadProjectionTest`, then `python3 -m unittest scripts/test_android_test_gate.py` and `./gradlew compileDebugAndroidTestKotlin`. AC 4, the method passing in the full live gate, is the dispatcher's live run.
