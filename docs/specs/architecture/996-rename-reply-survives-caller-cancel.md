# #996 — A rename reply reaches the list even when its caller is gone

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_deleteConversation_removesFromListAndClosesThread` step 4–5 (Save, then Back at once) vs `interactiveTurn_renameConversation_relabelsTopBarAndListRow` step 6 (waits for the top bar before Back). This is the difference between the flaky method and the one that never flakes.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/GuardedRepoLaunch.kt` → `launchGuardedRepoCall`: rename runs on `viewModelScope`, which the Back pop cancels.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRequests.kt` → `sendAndAwaitReply`, `waiter`: the waiter is removed only in the caller's `finally`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationCommands.kt` → `rename`, `sendArchiveToggle`, `promote`: every `conversation_updated` caller upserts the decoded reply verbatim.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `onInbound`, `TYPE_CONVERSATION_UPDATED` arm: **the file this ticket changes**.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationListProjection.kt` → `upsertConversation` (idempotent; an equal list does not re-emit).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ConversationTree` / `treeSection` and `ChannelListViewModel.observeHostEntry`: checked for cause 1.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` → the `rename_*` block and `startRename` / `collectConversations`: harness for the new test.

In-flight overlap: `feature/983` also edits `RemoteConversationRepository.kt`, in different blocks (attachment offer). A later merge may touch the file. It is not a dependency.

## Design source

N/A: no UI change. What the list looks like does not change.

## Root cause (investigated before fixing)

**Cause 1 ruled out.** The failing gate's daemon store (`~/.pyry/e2e-auto-7356a1d2/conversations.json`, fresh per run) held only four conversations when step 5 ran: one channel and three chats. That is two host rows, two workspace rows and four conversation rows, which all fit the viewport. The daemon is per-run, so conversations do not pile up from earlier runs. The same store shows `200b0a5a` named `e2e554-…`, so the rename committed on the daemon.

**Cause 2 confirmed: the rename reply lost its only writer.** The `conversation_updated` arm hands a correlated reply to its waiter and does **not** fold it. The fold happens only in `ConversationCommands.rename`, after `deferred.await()` resumes on the caller's `viewModelScope`. The delete method taps Back straight after Save, and the pop clears `ThreadViewModel`. That leaves a window where the reply has already completed the waiter, but the caller's resumption has not run yet. When the scope is cancelled in that window, the prompt-cancellation guarantee turns the resumption into a `CancellationException`. The upsert never runs. And because the waiter was still registered when the frame arrived, the arm's "no waiter" fold does not fire either. Nothing else re-sends the record: the daemon replies only to the renaming connection, and it answers frames on one connection in order, so no stale snapshot is involved. The list keeps the auto-name until the next `conversations` snapshot, and the 30 s wait times out.

The rename method never flakes because its step 6 waits for the top bar to relabel before tapping Back. The top bar reads the same projection, so by then the upsert has run. Step 7's unscrolled list wait is therefore **not** the same cause, and it stays unchanged.

## Change

In `onInbound`'s `TYPE_CONVERSATION_UPDATED` arm, decode and fold every well-formed `conversation_updated` into the list projection, correlated or not, **before** completing any waiter. Then complete the waiter as before. A malformed payload still folds nothing and still reaches its waiter verbatim, so the caller keeps throwing its decode error. The callers' own upserts stay: they write the same value, so the list does not re-emit. Folding the reply on the single inbound consumer is what makes the list independent of the caller's lifetime. The same applies to promote, archive, unarchive, change_workspace and set_system_prompt, which share the arm. Rewrite the arm's comment, which currently says folding a correlated reply would be redundant.

No change to the e2e test: the unscrolled wait is not the cause, and after the fix step 5 passes whatever the Back timing. The method KDoc's "always inside the visible recents" is stale wording from before #731. It is not the fault, so it is left alone (Simplicity First).

## Testing strategy

A new unit test in `RemoteConversationRepositoryTest`, `rename_callerCancelledAfterReplyArrives_stillFoldsRenamedConversationIntoList`. Load the `MIXED_FIXTURE` snapshot and launch `rename` in its own job. Push the correlated `conversation_updated` reply, then cancel the job before `runCurrent()`. On the test dispatcher the collector's frame runs first, finding the still-registered waiter. The caller's cancellation runs after it. Assert that the list shows the new name. This is red before the fix and green after. The existing `rename_*`, `promote_*` and malformed-reply tests pin that nothing else moves.

Live proof: `python3 scripts/android-test-gate.py live` is the dispatcher's gate for this method.

## Revisions

### 2026-09-24 — Security review added (verifier finding on PR #998)

The ticket carries `security-sensitive`, and the plan was committed without its `## Security review`. The verifier failed the PR on that alone. This entry adds the section below; the design and the code are unchanged. The review found no MUST FIX, so no code moves.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The boundary is unchanged: `MobileJson.decodeFromJsonElement<ConversationResponseDto>` is the single decode-and-validate step for a `conversation_updated` payload, and `ConversationResponseDto.toConversation` is a total field copy with no second throw site. What moves is *where* a correlated payload is decoded first: on the single inbound collector in `onInbound`, not only on the caller's coroutine. The collector already ran exactly this decode for the unsolicited half, so it adds no new parser, no new field and no new render path. The name and cwd still reach Compose only as text through the existing list rows.
- [Trust boundaries — hostile relay or daemon] No findings. A correlated reply now updates the list by the payload's own `id`, before the waiter completes and even when the caller is gone. This gives a lying peer nothing new. Every correlated caller (`ConversationCommands.rename` / `promote` / `sendArchiveToggle`, `WorkspaceCommands.changeWorkspace`, `SessionSettingsCommands.setSystemPrompt`) already upserted the decoded reply by its own `id`, without checking it against the id it asked about. And the unsolicited arm already accepted any `id` with no `in_reply_to` at all. So a peer that can put a frame inside the Noise session could already write any row this way. The relay is outside the session and cannot forge or alter a frame; it can only drop or delay one, which leaves the list on the old name until the next snapshot, as before.
- [Malformed payload / collector liveness] No findings. Decode failure is `SerializationException` or kotlinx-datetime's `DateTimeFormatException`, both subtypes of `IllegalArgumentException`, which the arm catches. The fold is skipped, the collector stays alive, and the payload still reaches its waiter verbatim, so the caller's own decode throws as before. The existing `rename_onMalformedUpdatedReply_throwsAndLeavesListUnchanged`, `promote_…`, `archive_…` and `changeWorkspace_onMalformedUpdatedReply_throwsAndLeavesListUnchanged` tests pin the correlated half, and the unsolicited malformed-push test pins collector survival.
- [Tokens, secrets, credentials] No findings — the change touches no key, token or pairing state.
- [File / storage] No findings — nothing is written to disk. The list projection is in memory.
- [Inter-process / Android surface] No findings — no intent, deep link, push path or WebView is touched.
- [Cryptographic primitives] No findings — the Noise session and `MobileWireCodec` are unchanged.
- [Network & I/O] No findings — no new frame, size limit or connection setting. A correlated reply is one frame already bounded by the transport.
- [Error messages, logs] No findings. No branch of the arm logs anything: the payload carries the conversation's name and cwd, and a decode exception's message can quote it, so the catch drops it silently. The waiter receives the raw payload, not the exception.
- [Concurrency] No findings. The fold runs on the single inbound collector, which is the only other writer ordering with the caller. `ConversationListProjection.upsertConversation` is a `MutableStateFlow.update`, so the collector's write and the caller's later write of the same value cannot lose each other, and an equal list does not re-emit. The fold is applied before `complete`, so the caller resumes onto a list that already carries the reply, matching the `TYPE_WORKSPACE_UPDATED` arm.
- [Threat model] OUT OF SCOPE — checking that a correlated reply's `id` matches the requested conversation. The unsolicited path must accept any `id` by protocol (#721), so such a check would not close anything; it would need a protocol change first, which no ticket proposes.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24
