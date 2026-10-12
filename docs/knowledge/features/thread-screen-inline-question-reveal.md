# Thread screen — inline question rows and reveal

Split from [thread-screen-how-it-works-list-and-status-row.md](thread-screen-how-it-works-list-and-status-row.md) for #1985 to keep the overview under its size cap.
Existing sections and evidence are preserved.

### Inline question rows and the newest-end reveal (#1305)

[`AskUserQuestion`'s held batch](question-batch-modal.md) moved from its own `Dialog` into this
`LazyColumn` in #1305. `questionState?.let { pending -> ... }` emits, immediately before the message-row
`itemsIndexed(...)` block (so, under `reverseLayout = true`, drawn **below** every message row — the
newest content on screen), an actions item, one item per question in original order, then a title item;
see [Question batch modal § Rendering](question-batch-modal.md#rendering) for what each renders and the
`"question-actions:$generation"` / `"question:$generation:$it"` / `"question-title:$generation"` key
scheme. The empty-thread branch (`!state.hasMessages && state.queuedMessages.isEmpty()`, [Empty-state
branch](thread-screen-how-it-works-list-and-status-row.md#empty-state-branch-post-138)) also gates on `questionState == null`, so a thread whose only content is a pending batch
renders the `LazyColumn` with the prompt rows, not `EmptyThreadState`. [#1306](permission-modal-overlay.md)
adds the same `openRequest == null` gate beside it for a pending permission or trust request — see [Inline permission rows and the shared reveal](thread-screen-how-it-works-list-and-status-row.md#inline-permission-rows-and-the-shared-reveal-1306), which reuses every mechanism this section describes.

**Focus relocation uses the clear area, not the drawing viewport (#1646).** Content
padding only reserves resting space; Compose otherwise treats an Other field behind
chrome as already visible. `ThreadMessageList` supplies a `BringIntoViewSpec` that
uses the default minimal-distance relocation within measured header/composer bounds.
`QuestionBlock` retries the focused field's reveal on IME inset or relocation-spec
changes, including attachment and multiline composer resizing, while retaining the
last-field action reveal. Limit this spec to the list: `ThreadRowContent` restores
the inherited spec for nested message scrollables, and the Other field restores
default relocation within its own well. Without that isolation, a field-local
scroller incorrectly subtracts the thread's chrome from its own small viewport.

**Prompt rows count as a fixed prefix in the [oldest-end history predicate](thread-screen-how-it-works-list-and-status-row.md#the-oldest-end-history-demand-and-retry-777-778-1569-1572), never as loaded
history.** `val promptRowCount = questionState?.let { it.batch.questions.size + 2 } ?: 0` (`+2` for the
actions and title items) is added to `rows.size` for `historyRowCount`, and a new `hasHistoryRows =
rows.isNotEmpty()` — **not** `historyRowCount > 0` — gates the demand instead. Without `hasHistoryRows`, a
thread with a pending batch and zero loaded message rows would have `historyRowCount > 0` true from the
prompt rows alone and could fire `onDemandOlderHistory()` against a thread that has no history to page.

**Prompt arrival and edits are excluded from the growth signature while a batch is mounted (#1304), so field
sizing never drags a reader down.** Originally the streaming size-driven pin gained `questionState != null`
to its key and early-return guard, because item 0 while a batch is held is the prompt actions row, not a
streaming message bubble, and its field sizing (the Other `BringIntoViewRequester`, the IME) must not read as
growth. [#1314](https://github.com/pyrycode/pyrycode-mobile/issues/1314) carries the same rule forward:
`FollowNewestEnd`'s `promptPresent` parameter masks the newest row's content and the anchor's size out of the
growth signature while a prompt is mounted, so editing a field or the IME opening still cannot pull a history
reader down — see [Thread screen — subagent tool-row nesting § The newest-end follow rule
(#1314)](thread-screen-subagent-tool-rows.md#the-newest-end-follow-rule-1314).

**A newly arrived batch reveals itself to a reader already at the newest end, on the same rule that follows
a new message row.** A verifier pass on #1305 found this gap live, before the #1314 rework below existed:
an operator watching the newest end saw only the composer's "Waiting for answers" status reading (below)
until they thought to scroll further, because prompt rows insert below the anchored first visible row the
same way a new message row would, landing off-screen until something scrolls back to index 0. #1305 closed
it with a dedicated effect keyed on `questionState?.generation`, shaped like the #981 newest-row pin it sat
beside. **[#1314](https://github.com/pyrycode/pyrycode-mobile/issues/1314) folded that effect into the one
following rule** described in [Thread screen — subagent tool-row nesting § The newest-end follow rule
(#1314)](thread-screen-subagent-tool-rows.md#the-newest-end-follow-rule-1314): the prompt's identity
(`shownQuestion?.generation to openRequest?.modalId` — see the [permission section](thread-screen-how-it-works-list-and-status-row.md#inline-permission-rows-and-the-shared-reveal-1306) for why it reads
`shownQuestion`) is part of `FollowNewestEnd`'s growth signature, so a new generation pins a reader who is
following exactly as a new message row would, and does nothing to a reader who has scrolled into history.
`scrollToItem(0)` reveals the actions row and the latest question, not the title first: under reverse
layout, scrolling to the title of a batch taller than the viewport would need a second post-measure scroll,
and a batch that already fits the viewport already shows its title at `scrollToItem(0)`. Covered by
`ThreadInlineQuestionTest.arrival_reveals_the_batch_to_a_reader_at_the_newest_end`
(`app/src/sharedTest/.../thread/`); `arrival_and_edits_preserve_a_history_reader_and_prompt_rows_do_not_advance_history_demand`
in the same file covers the complementary case — a reader scrolled into history is not pulled forward by
batch arrival or edits, and prompt rows do not move the oldest-end predicate.

**The composer status band gets a fourth, static reading.** `ThreadStatusArea` takes a new
`waitingForAnswers: Boolean` parameter (`questionState != null && connectionState ==
ConnectionState.Connected`) that, when true, renders a fixed "Waiting for answers" row (`R.string.question_waiting_for_answers`,
the question glyph tinted `primary`) in place of the usual `StatusReading` dispatch — this is what the
verifier's Rework 3 absence check additionally waits to disappear (`awaitNoInlineQuestion`, see
[Real-claude e2e coverage](../../e2e-interactive-stream.md)), since it is always composed and not subject to
the lazy list's offscreen-vs-absent ambiguity the title and row tags have.
[#1483](../../specs/architecture/1483-permission-request-frames.md) adds the sibling
`waitingForPermission: Boolean` (`openRequest != null && connectionState == ConnectionState.Connected`),
which reads `thread_status_waiting_for_permission` ("Waiting for permission") in the same box, behind the
same snowflake glyph, when `waitingForAnswers` is false. The two never race: `shownQuestion` is withheld
whenever a request is open ([Inline permission rows](thread-screen-how-it-works-list-and-status-row.md#inline-permission-rows-and-the-shared-reveal-1306)), so a question's own "Waiting for answers"
reading cannot show while a permission is open.

**`QuestionPromptProtection`** (the `FLAG_SECURE` / obscured-touch guard the old dialog window used to own)
is mounted directly by `ThreadScreen` — right after the screen's `rememberSaveable` block, so it is active
for the composition's whole life while any batch is held, independent of which prompt rows happen to be
visible. Since [#1306](permission-modal-overlay.md) the guard's condition is `questionState != null ||
openRequest != null` from **one call site**, so a question batch handing off to a permission request (or
the reverse) keeps the same owner throughout — see [Inline permission rows and the shared reveal](thread-screen-how-it-works-list-and-status-row.md#inline-permission-rows-and-the-shared-reveal-1306).
See [Question batch modal § Rendering](question-batch-modal.md#rendering) for the shared-ownership
mechanism this guards against overlapping navigation transitions. [#1341](https://github.com/pyrycode/pyrycode-mobile/issues/1341)
later withholds the question from the rows it draws while a request is open, but this condition still reads
`questionState`, not the withheld view — see [Inline permission rows and the shared reveal](thread-screen-how-it-works-list-and-status-row.md#inline-permission-rows-and-the-shared-reveal-1306) for why.
