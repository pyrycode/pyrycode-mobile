# #1315 — Described tool header for shell commands only

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt` → `HeaderRow` — picks the described header on any non-empty `description`; this is the rule that changes.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolRowFormat.kt` → `toolRowSubject`, `BASH_TOOL_NAME`, `BASH_SUBJECT_FIELDS` — the pure text rules mirrored from desktop; the new header decision joins them.
- Desktop `src/renderer/src/screens/conversation/toolHeadline.ts` → `toolHeadlineRuns` — the rule mirrored here: tool test first, key test only inside it, `===` so `BashOutput` is not `Bash`.
- `app/src/sharedTest/.../components/ToolCallRowTest.kt` → `a_call_without_a_description_uses_the_simple_name_and_subject` — asserts `Bash` beside a command; the new rule changes it.
- `app/src/androidTest/.../e2e/DeterministicInteractiveStreamE2ETest.kt` → `interactiveTurn_seededChannel_toolStepRunsThenCompletes` — asserts the resolved row by the text `Bash`. Its fixture (`scripts/e2e-fixtures/tool-open.jsonl`) is `Bash` with `{"command":"echo hello"}` and no description, so under the new rule the row shows `echo hello` and no `Bash`.
- `docs/knowledge/features/tool-call-row.md` § Collapsed — the 160dp name cap exists to keep a long MCP name from taking the subject's width.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Thread frame; per the ticket this uses the existing described header (body-medium description plus chevron) and simple header (tertiary monospace lead plus body-medium subject). No new visuals.

## Change

Add a pure `toolHeadline(toolName, inputFields, input): ToolHeadline` to `ToolRowFormat.kt` beside `toolRowSubject`, returning a sealed `ToolHeadline`:

- `Described(description)` — only when `toolName == BASH_TOOL_NAME` exactly and `description` is non-empty.
- `Simple(lead = command, subject = "")` — `Bash` with a non-empty `command` and no description.
- `Simple(lead = toolName, subject = toolRowSubject(...))` — every other call, including `BashOutput`, an `Agent`/`Task` with a description, and a `Bash` call with neither field.

`HeaderRow` switches on it instead of reading `description` itself. The simple header's 160dp lead cap applies only when a subject follows, since the cap exists to leave room for one; a command lead with no subject ellipsizes at the row's available width, as desktop's lead cuts at its group boundary.

## Testing strategy

- `ToolRowFormatTest` (unit): `toolHeadline` for Bash+description, Bash command only, `BashOutput` with both fields, `Agent` with a description, and Bash with neither field (falls back to the précis).
- `ToolCallRowTest` (Robolectric): replace the Bash-command-only expectation with command visible, no `Bash`, no chevron; add rows for a non-Bash call with a description (name + subject, no chevron), `BashOutput` with a description (name shown, no chevron), and a call with neither field (name + précis).
- `DeterministicInteractiveStreamE2ETest` rung 4 `tool` scenario: assert the resolved row by its command `echo hello` instead of `Bash`. The substring holds whether the daemon forwards input fields (command lead) or not (name + précis).

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/tool-call-row.md` § Collapsed should say the described header is `Bash`-only, a `Bash` command without a description is the monospace lead with no subject, and the 160dp cap applies only when a subject follows.
