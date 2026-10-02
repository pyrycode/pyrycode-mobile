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

## Live tests

The ticket asks for the dispatcher's full live gate, so the PR lists `all`.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The pushed `message` crosses into the app at one place, the `MessagePayloadDto` decode in `onInbound`'s `TYPE_MESSAGE` arm, which drops a malformed frame. Its `attachment_ids` pass through `storedAttachmentReferences`, which keeps only lowercase-UUIDv4 ids, removes repeats and bounds the count at `MessageAttachmentIds.MAX`. A pushed row carries bare references and never a name or hint from the wire. This ticket changes no production code, so the boundary stays as #1351 left it, and the new tests pin it from the send side.
- [Trust boundaries] No findings on id collision. A push for a held `message_id` cannot overwrite the phone's named row, because `appendLiveMessage` keeps a held row. A push that arrives before the ack is replaced by the confirmed insert (`appendMessages` upserts), so the phone's own copy wins in both orders. To claim the phone's row first, a push would have to guess the id `sendMessage` mints with `UUID.randomUUID()`, which draws from `SecureRandom`, before the send leaves the phone.
- [Tokens, secrets and credentials] Not applicable by design: the ticket touches no token, key or credential path.
- [Files and storage] No findings. Display names come only from the phone's own picker, cleaned by `attachmentDisplayName` in `sendMessage`. No path is built from a pushed id or name here.
- [Android attack surface] Not applicable by design: no component, intent filter, pending intent or WebView changes. The e2e revert is test-only.
- [Cryptography] Not applicable by design: the Noise session and `NoiseIkSession` are untouched.
- [Network and I/O] No findings. The existing inbound frame cap and supervisor are unchanged. The live list gains one method, which runs only on the test daemons the harness owns.
- [Errors, logs and telemetry] No findings. The live arm logs nothing from the payload. The new tests add no logging, and `sendMessage` keeps logging only the attachment count.
- [Concurrency] No findings. The send's confirmed insert and the inbound push are two writers on `ThreadProjection`, and both go through an atomic `MutableStateFlow.update`. Push before ack and push after ack each end with one named row, and the new tests cover both orders.
- [Threat model] Handled: a malicious relay may replay or reorder the push and the ack. A replayed push is a no-op (`appendLiveMessage` returns the map unchanged), and a reordered one is covered by the push-before-ack test. A hostile frame is bounded as described under trust boundaries.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-02
