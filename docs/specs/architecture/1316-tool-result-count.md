# #1316 — Tool result line count, first result and first denial win

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` → `ToolResultPayloadDto`, `ToolResultPayloadDto.toEvent` — the decode that gains `result_detail`.
- `app/src/main/java/de/pyryco/mobile/data/model/LiveSessionEvent.kt` → `LiveSessionEvent.ToolResult` — the live event that carries the value to the reducer.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` → `ToolCall`, `ToolCallStatus` — the row model that holds the value.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt` → `withToolResult`, `withToolDenied` — the shared fold used by both history pages and the live lane (`ThreadProjection.applyToolResult` / `applyToolDenied`); today last-write-wins.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt` → `HeaderRow`, `TrailingStatus` — the row's trailing group, measured before the weighted headline.
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt` → `CachedToolCall` — unchanged; restored rows carry no count.
- `app/src/test/.../data/repository/HistoryPageReducerTest.kt`, `app/src/test/.../data/network/ToolPayloadsTest.kt`, `app/src/sharedTest/.../ui/conversations/components/ToolCallRowTest.kt` — where the new tests sit.
- `app/src/test/.../data/repository/RemoteConversationRepositoryTest.kt` → `toolCall_duplicateToolResult_isIdempotent` and siblings — assert whole `ToolCall` equality after a live fold, so the new field's value after a fold matters to them.
- Desktop: `fillResult` and the `toolDenied` arm in `src/renderer/src/store/threadTimeline.ts`; `ToolRow` and `.tool-row__right { max-width: 50% }` / `.tool-row__count` in the conversation screen — the reference behaviour.
- `docs/knowledge/features/tool-call-row.md` § trailing status — #895's "no result count" note this ticket makes obsolete (documentation stage).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The collapsed tool instance draws the result count ("184 lines") in the trailing status group, before the status glyph, in the same muted label style as the running row's elapsed reading. Nothing else on the row changes. (The Figma MCP was not authenticated in this session; the summary comes from the ticket and the existing row's documented Figma mapping, and the count reuses the elapsed reading's `labelMedium` / `onSurfaceVariant` tokens.)

## Context

The daemon sends `result_detail` on every `tool_result` (pyrycode#2024), e.g. `"265 lines"`, `"110 of 1676 lines"`, or `""` when there is no count. Mobile does not decode it. Desktop draws it before the chevron with the right group capped at half the row, and its reducer is first-result-wins and first-denial-wins; mobile's is last-write-wins.

## Design

**Wire and model.**
- `ToolResultPayloadDto` gains `@SerialName("result_detail") val resultDetail: String = ""` — lenient-defaulted like `parentToolUseId`, so an older daemon still decodes. Absent and empty both mean "no count" on mobile.
- `LiveSessionEvent.ToolResult` gains `val resultDetail: String = ""`; `toEvent()` copies it verbatim.
- `ToolCall` gains `val resultDetail: String? = null`. `null` means no `tool_result` has been folded into this row (a running call, a denial with no result yet, or a row restored from the disk cache); a non-null value, empty included, means the first result arrived. This is desktop's `result === null` test, carried on one field.

**First result wins** (`withToolResult`): fold only when `call.status == Running`, or `call.status == Denied && call.resultDetail == null`. Otherwise return the list unchanged. The fold sets `resultDetail = event.resultDetail` alongside the existing output/status/parent/elapsed updates. The status gate also keeps a duplicate frame from rewriting a cache-restored `Done`/`Failed` row, whose `resultDetail` is `null`.

**First denial wins** (`withToolDenied`): return the list unchanged when `call.denial != null`. A denial after a result still marks the row `Denied` and keeps its output and `resultDetail`; a result after a denial still fills output, as today.

**Row.** `TrailingStatus` draws `resultDetail` as one-line, ellipsized `labelMedium` / `onSurfaceVariant` text before the glyph on `Done`, `Failed` and `Denied` rows when it is non-null and non-empty; never on `Running`. Not drawing it leaves no gap, because `spacedBy` gaps fall only between children. The count `Text` takes `Modifier.weight(1f, fill = false)` so it is the group's only shrinkable child, and the trailing `Row` is capped at half the width the header row offers it (a small private `Modifier.layout` that halves `maxWidth`), mirroring desktop's `max-width: 50%`. The glyph is measured first and can never be pushed off; the headline keeps at least half the row. The count is inert text: no click, no link, no parsing, no log.

## State + concurrency model

No new state or jobs. Both folds are pure list transforms already called from the projection's existing update path and the page reducer.

## Error handling

No new failure mode. A missing `result_detail` decodes to `""`; a wrong-typed one fails the envelope's decode exactly as any other wrong-typed field does today.

## Testing strategy

- `ToolPayloadsTest`: `result_detail` absent → `""`, empty → `""`, present → verbatim (including a non-numeric value).
- `HistoryPageReducerTest` (pure folds, `withToolResult` / `withToolDenied` and `reduceHistoryPage`):
  - a page result carries its `resultDetail` onto the row;
  - result → result: second changes nothing (output, status, detail);
  - denial → denial: second changes nothing;
  - result → denial: row `Denied`, output and detail kept;
  - denial → result: output and detail filled, still `Denied`;
  - denial → result → result: second result changes nothing;
  - a result on a `Done` row with `resultDetail == null` (cache-restored shape) changes nothing.
  - The existing `withToolResult_onDeniedRow_keepsDeniedAndAttachesOutput` stays green.
- `ToolCallRowTest` (shared Robolectric): count shown on `Done`, `Failed`, `Denied`; not on `Running` even with a value; empty and `null` draw nothing; a 3000-character value keeps the status glyph and tool name displayed within the row bounds.
- Existing `RemoteConversationRepositoryTest` equality assertions after a live result fold gain `resultDetail = ""` where they compare a whole `ToolCall`; `toolCall_duplicateToolResult_isIdempotent` keeps passing.
- No rung-3 scenario: the count is display of an existing frame's field on an existing flow, not a new operator-facing flow; the scripted tool scenarios keep covering the row.

## Documentation handoff

Pending for the documentation stage:
- `docs/knowledge/features/tool-call-row.md` — trailing status section and the "No result count" bullet: remove #895's note; describe the count, its half-row cap, and first-result/first-denial semantics.
- `docs/knowledge/features/live-tool-call.md` — the `tool_result` fold: `result_detail` decode, first-result-wins and first-denial-wins.

## Open questions

- None blocking. If the half-width `layout` modifier misbehaves under the header `Row`'s measurement, fall back to `BoxWithConstraints` around `HeaderRow` and record it under Revisions.
