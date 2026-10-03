# #1575 — Restored tool rows keep their input fields

## Files read

- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt`: `CachedToolCall`, `ThreadItem.toRecord`, `CachedThreadRow.toDomain`. The record drops `ToolCall.inputFields`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolRowFormat.kt`: `toolRowSubject`, `toolHeadline`. With no fields the subject falls back to the `input` précis.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt`: `ToolCall.inputFields` (defaulted `emptyMap()`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt`: the only production caller of `toolHeadline`; its expanded Input section uses `input` directly and stays as it is.
- Tests: `FileConversationCacheThreadTest`, `ToolRowFormatTest`, and the shared screen test `ToolCallRowTest`, whose `a_call_with_neither_field_uses_the_simple_name_and_precis` asserts the précis in the header.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The existing described and simple tool headers from #1315; no new visuals. A field-less row draws the simple header with the tool name alone.

## Change

`CachedToolCall` gains `inputFields: Map<String, String> = emptyMap()`, written by `ThreadItem.toRecord` and read back by `CachedThreadRow.toDomain`. The default keeps a document written before this change readable, with empty fields: a missing key decodes to the default, as `CachedMessage.attachments` does for #983. `parentToolUseId`, `denial` and `resultDetail` stay uncached, as the ticket asks.

In `toolRowSubject`, when `inputFields` is empty the subject is `""` instead of the `input` précis, so `toolHeadline` yields `Simple(toolName, "")` and the header reads as the tool name alone. A row that has fields but none of the probed keys keeps today's précis fallback, so rows with fields are unchanged. The expanded body's Input section is untouched (out of scope).

## Testing strategy

- `FileConversationCacheThreadTest`: a tool row with `inputFields` (a `Bash` with `command` and `description`) round-trips field-for-field through a fresh instance; a pre-change tool record, verbatim JSON without the `inputFields` key, reads back with empty fields.
- `ToolRowFormatTest`: replace the two précis-fallback assertions for empty fields with field-less `Bash` and `Read` cases asserting `Simple(toolName, "")` and an empty subject; keep the "only empty or unknown fields" précis case.
- `ToolCallRowTest`: the field-less row shows `Bash` and no longer shows the précis in the collapsed header; a justified expectation change.

## Documentation handoff

None. The ticket names no documentation requirements; the thread-screen overview's tool-row notes may want a line that cached rows keep their fields, at the documentation stage's discretion.
