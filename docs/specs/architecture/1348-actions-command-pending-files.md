# #1348 — Send pending files with an Actions menu command

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` — `onComposerCommand` (the change), `sendMessage` and `sendWithAttachments` (the pending-files path it joins).
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelComposerActionsTest.kt` — the existing command tests the new ones sit beside.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelAttachmentTest.kt` — the recording repository and fake reader shape the new tests mirror.
- `docs/knowledge/features/thread-composer-footer-actions-menu.md` — "Dispatch and send" currently documents that a command leaves pending attachments untouched.

## Design source

Figma node 533-1958 (Options overlay). Visuals do not change: no composable, row or strip rendering is touched, so the visual check is skipped on purpose. The strip empties after the send through the existing `pendingAttachments` flow.

## Change

`onComposerCommand` keeps its connection gate, null-command return and absent-command refusal. After those it reads `draftStore.attachmentsFor(serverId, conversationId)`. With entries, it sends the command through `sendWithAttachments`, as desktop's `sendText` hands `takeAttachments` to both of its callers; with none, it sends text-only as today. `sendWithAttachments` today clears the typed draft when it equals the sent text, which a command must never do, so that line moves into a new `onSent: () -> Unit` parameter: `sendMessage` passes the guarded draft clear, `onComposerCommand` passes its `outcome=sent` log. The attachment snapshot removal stays inside `sendWithAttachments` for both. While an attachment send is already in flight (`_attachmentsSending`), the command is refused with a static `outcome=busy` log, as `sendMessage` refuses: the in-flight send owns those files, and sending them again would upload and name them twice. Nothing else moves.

In-flight overlap on `ThreadViewModel.kt`: #1311, #1329, #1337, #1342, #1346, #1410. Only #1311 edits `sendWithAttachments`, wrapping the repository send on the line beside the draft clear this ticket moves; that re-merges, it does not redesign.

## Testing strategy

New tests in `ThreadViewModelComposerActionsTest`, with a recording repository that overrides the three-argument `sendMessage` and `uploadAttachment` and a fake `AttachmentReader`:

- pending files: Compact session uploads them, sends `/compact` naming their ids, empties `pendingAttachments`, and the typed draft (including one equal to `/compact`) stays.
- no files: unchanged, covered by the existing `compact_sendsItsCommandToThisConversation_andLeavesTheDraftAlone`.
- greyed command with files pending: sends and uploads nothing, the files stay.
- in-flight attachment send: a command sends nothing and logs `outcome=busy`.

The existing `ThreadViewModelAttachmentTest` covers `sendMessage`'s draft clear through the new `onSent` parameter.

## Documentation handoff

- `docs/knowledge/features/thread-composer-footer-actions-menu.md`, "Dispatch and send": pending for the documentation stage. It says a command leaves pending attachments untouched; it now carries them through `sendWithAttachments`, refuses while an attachment send is in flight, and still leaves the draft alone. A command sent with files opens the local send window; a text-only command does not.

## Revisions

### 2026-10-02: rework after review on PR #1414

- A command sent with files goes through `sendWithAttachments`, so it opens the local "Thinking…" send window and emits `sentMessages` (follow newest), as a typed message with files does. A text-only command still does neither. "Nothing else moves" in Change excludes this side effect of reusing the attachment path; it is intended, and the documentation handoff now names it.
- The real-Claude scenario for a command sent with files is filed as a follow-up, #1460, rather than landed here. The daemon appends its attachment block after the text, so real Claude receives `/compact` followed by the file paths, and only a live run shows how Claude Code treats that. The existing `interactiveTurn_reconnect_slashCommandsAndCompactStillWork` and `interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes` each cover one half.
