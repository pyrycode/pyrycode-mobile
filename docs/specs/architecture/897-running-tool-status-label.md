# #897 — Name the running tool and its elapsed time in the thread status area

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadStatusArea` — the one status slot and its precedence ladder; the thinking arm is its `else` branch. `ThreadScreen` already receives `state.items` and `isBusy`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ThinkingIndicator.kt` → `ThinkingIndicator` — the arm's composable. Its KDoc records #803's rule: one `Row`, one `CircularProgressIndicator`, only the `Text` argument and the content description vary, so the spinner keeps its composition identity when the label changes. The tool label must follow the same rule.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolRowFormat.kt` → `formatToolElapsed` — the shared elapsed format from #895 (`12s`, `1m 05s`, keeps a negative sign).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt` → `ToolCallRow` — renders `toolName` as one ellipsized line of inert `Text`. The status label uses the same treatment.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` → `ToolCall`, `ToolCallStatus` — `elapsedSeconds` is non-null only while `Running`. A `Done`, `Failed` or `Denied` row is not open.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt` → `withToolProgress` and the result and denial helpers — the reducer already clears `elapsedSeconds` when a call closes and ignores a late reading. This ticket reads that state and does not re-derive it.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `ThreadItem` — `state.items` is chronological (the screen reverses it for the reversed `LazyColumn`), so the latest row is the last one.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `isBusy` — the `thinking`-or-`responding` flag the AC gates on. It is already passed to `ThreadScreen` by `MainActivity`.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThinkingIndicatorTest.kt` — the screen-test shape for this arm.
- `docs/knowledge/features/thinking-indicator.md` — the single-spinner-call-site lesson.
- `docs/knowledge/features/tool-call-row.md` § Subject and elapsed text — `formatToolElapsed` was split out so this status area could call it.
- Desktop `docs/knowledge/features/conversation-shell-working-indicator.md` § Tool elapsed reading — one `openToolCall(items)` lookup supplies both the name and the seconds: the latest pending, non-denied call. The whole label is one ellipsizing text run.

Overlapping in-flight branches: #883, #884 and #896 also edit `ThreadScreen.kt` and #878, #883, #884 and #896 edit `strings.xml`. None of them touches `ThreadStatusArea`. The edits here are additive, so a later merge may touch those files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8 (`Status area`, `I533:1957;111:3525`)

A single row in the composer's input area: a small leading status glyph, then a `M3/body/small` label (`Thinking...` in the frame), with the trailing contextual-action slot at the end. The ticket says to keep the current `ThinkingIndicator` visual, so the tool label reuses the same spinner, `bodySmall` and `onSurfaceVariant`, and changes only the label text.

## Context

The status band says nothing while claude runs a tool. It shows at most "Thinking…", and often nothing during the `responding` phase, so a long `Bash` call looks the same as a stalled turn. Desktop names the open tool and appends claude's latest `tool_progress` reading. This ticket brings that label to mobile.

No ADR is warranted.

## Design

**Open-call selector.** A pure function in the thread package:

```kotlin
internal fun openToolCall(items: List<ThreadItem>): ToolCall?
```

It returns the `toolCall` of the last `ThreadItem.MessageItem` whose `message.toolCall?.status == ToolCallStatus.Running`, or `null`. It returns one `ToolCall`, so the name and the seconds always come from the same call. A newer open call wins because it is later in the list. A subagent's nested call is still a row in `items`, so the latest open call wins whatever its parent is, as on desktop.

**Screen wiring (`ThreadScreen.kt`).** `ThreadScreen` computes `runningTool = if (isBusy) openToolCall(state.items) else null`. The lookup is cached with `remember(state.items)`, and the `isBusy` gate is applied outside the cache. It passes `runningTool` to `ThreadStatusArea`, whose `else` arm passes it on to `ThinkingIndicator`. The arms above it do not change, so api-retry, usage limit, resetting, compaction and turn outcome still take precedence without any extra code.

The selector lives in the screen rather than in a new `ThreadViewModel` flow. The screen already holds both inputs (`state.items` and `isBusy`), and the #782 row fold and the session-boundary cutoff are derived the same way. A ViewModel flow would also need a new `ThreadScreen` parameter and new `MainActivity` wiring, with no behaviour to show for it. This departs from the ticket's estimate, which listed `ThreadViewModel.kt`: the production files become `ThreadScreen.kt`, `ThinkingIndicator.kt` and `strings.xml`.

**Indicator (`ThinkingIndicator.kt`).** The composable gains a defaulted `runningTool: ToolCall? = null` parameter:

- Visibility: `isThinking || runningTool != null`. A running tool raises the arm during the `responding` phase as well. The `isBusy` gate upstream prevents a stale `Running` row from raising it after the turn has ended.
- Label, in priority order: a running tool gives `Running <name>…`, followed by ` <formatToolElapsed(elapsedSeconds)>` when a reading is present. Otherwise the #803 token-reading label is used, and otherwise the plain `Thinking…` label. The content description follows the same selection.
- The label is still one `Text` in the same `Row`, next to the same spinner call site, so the spinner is not recomposed from scratch when a tool opens or closes (#803's identity rule).
- When a tool is named, the `Text` is limited to `maxLines = 1` with `TextOverflow.Ellipsis`. This matches the tool row and desktop's single ellipsizing run, and bounds a long or multi-line daemon name. The thinking labels keep their current wrapping.
- There is no timer, `LaunchedEffect`, `remember` or `derivedStateOf`. The seconds change only when a new `ToolCall` value arrives.

**Strings.** Four new resources:

- `thread_tool_running_label`: `Running %1$s…`
- `thread_tool_running_elapsed_label`: `Running %1$s… %2$s`
- `cd_thread_tool_running`: `Claude is running %1$s`
- `cd_thread_tool_running_elapsed`: `Claude is running %1$s, %2$s elapsed`

The tool name is always a format argument and never part of the format string.

## State + concurrency model

No new flows, jobs or coroutines. The derivation is a pure function of `state.items` (already a `StateFlow` collected with lifecycle) and `isBusy` (already hoisted). Cancellation, background close and reconnect behave exactly as those two inputs already do. A background close does not stick the label either: `isBusy` falls on `turn_end`/`idle`, and the reducer closes the row on its result.

## Error handling

There are no new failure modes. With no reading (`elapsedSeconds == null`), the label shows no time. That is expected, because claude's first heartbeat comes about 30 seconds in. A negative or backwards reading is shown as sent, in the shared format, the same as the tool row. No timing is inferred from it and nothing logs it.

## Testing strategy

- **Unit test** `app/src/test/.../ui/conversations/thread/OpenToolCallTest.kt` (pure, no Compose), covering:
  - no tool rows gives `null`
  - only `Done`, `Failed` or `Denied` rows give `null`
  - one `Running` row is returned
  - with two `Running` rows the later one wins, including its own `elapsedSeconds`, even when the older one has a reading and the newer one does not
  - a later closed row does not hide an earlier still-running one
- **Screen test** `app/src/sharedTest/.../ui/conversations/thread/RunningToolIndicatorTest.kt` (Robolectric, `ThreadScreen` driven through `mutableStateOf` items and flags). It matches on content descriptions and text:
  - one test drives the lifecycle through each state:
    - open: `Running Bash…` with no time
    - progress 65 gives `Running Bash… 1m 05s`
    - a second reading replaces the first
    - the result (`Done`) removes the tool label and returns to `Thinking…` while `isThinking`, or to nothing while only responding
  - a denial removes the label in the same way
  - a newer open call replaces the older one's name and seconds
  - the label is absent when `isBusy` is false, even with a `Running` row
  - the label replaces the token-reading label
  - the compaction and turn-outcome arms still pre-empt it
- The existing `ThinkingIndicatorTest` stays unchanged and green, which proves the thinking arm without a tool is untouched.
- No device-only test is needed.
- **Real-Claude e2e.** This is an operator-facing label. A durable rung-3 assertion needs a tool call that runs past claude's ~30-second heartbeat, and the elapsed half is transient. The follow-up is a single `@Test` on `InteractiveStreamE2ETest`, `@Ignore`-gated if the signal cannot be held. A rung-4 twin can hold a scripted `tool_use` open with a `tool_progress` frame. That follow-up is filed rather than landed here (#481/#482 shape), because the scenario needs harness fixture work outside this ticket's three files.

## Open questions

- Should the elapsed reading survive truncation of a very long name? Desktop keeps one ellipsizing run, so the reading can be cut off. Mobile follows desktop, and a tool name long enough to trigger this has not been observed.

## Documentation handoff

The ticket body has no Documentation handoff section. Pending for the documentation stage: fold the running-tool label into `docs/knowledge/features/thinking-indicator.md` (label selection and visibility) and `thread-screen-how-it-works-list-and-status-row.md` (status-area ladder).

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The tool name is daemon-authored text from an open set. It crosses into Compose in one place, the `Text` in `ThinkingIndicator`, as a format argument to a local string resource. It is limited to one ellipsized line and never used as markup, a URL, a path, a filename, a cache key or a log field. The seconds are an `Int` formatted by `formatToolElapsed`, which bounds their rendered length at 12 characters for any `Int`. The selector compares `status` against the closed `ToolCallStatus` enum and never branches on the name.
- [Tokens, secrets] No findings. No credentials are touched.
- [File / storage] No findings. Nothing is persisted, and the label is recomputed from in-memory state.
- [Android surface] No findings. There are no intents, deep links, WebViews or providers. The content description exposes the tool name to accessibility services, but the tool row above already shows that same name on screen, so nothing new leaks.
- [Crypto] Not applicable. No primitives are involved.
- [Network & I/O] No findings. There is no new frame or decode. The reading arrives through #812's existing `tool_progress` path, whose reducer already drops a late reading after the call has closed.
- [Logs] No findings. The design adds no log call, and the name must never be logged. The verifier should check that the diff adds no `RelayLog` or `Log` call that mentions the tool.
- [Concurrency] No findings. There are no coroutines. The label cannot tick locally because no timer exists.
- [Threat model] A hostile daemon could send a very long, multi-line or confusable tool name. `maxLines = 1` with ellipsis bounds its layout. Confusable text (for example a name imitating a system message) is only shown inside the fixed `Running …` frame, so it is attributed to claude. OUT OF SCOPE: any daemon-side cap on `tool_name` length belongs to the pyrycode repo's protocol.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24
