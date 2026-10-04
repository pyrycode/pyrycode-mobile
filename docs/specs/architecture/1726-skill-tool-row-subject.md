# #1726 — Skill tool rows show the skill name

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolRowFormat.kt`: `TOOL_SUBJECT_FIELDS`, `toolRowSubject`, `toolHeadline`; ordered subject lookup and simple header construction.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/components/ToolRowFormatTest.kt`: preferred-field and fallback tests; extend the existing coverage.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt`: `HeaderRow`, `ExpandedBody`; the collapsed header consumes the formatter while expanded input is independent.
- `docs/knowledge/features/tool-call-row.md`: use a JSON-shaped précis in regression coverage, following #1575's lesson.

## Design source

N/A. The tool row's existing layout, text only: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

## Change

Append `skill` after `description` in `TOOL_SUBJECT_FIELDS`. A Skill input containing `skill` and `args` then yields the skill name instead of its JSON précis, producing the simple header `Skill file-pyrycode-ticket`. Earlier fields retain their precedence, empty values retain the existing fallback, and expanded input remains unchanged. No new types, state, failure modes or consumer updates; no remote feature-branch overlaps in the two edited Kotlin files. Forecast: about 45 written lines including this plan and tests, within the XS scope and all sizing limits.

## Testing strategy

Add a failing `ToolRowFormatTest` case for Skill with a JSON-shaped précis, asserting both subject and simple headline. Extend existing precedence and empty-field coverage to include `skill`. Run the full formatter and existing `ToolCallRowTest` classes, lint, assembleDebug, formatting and forced spotlessCheck. Run the existing scripted `tool` scenario as coverage of the preserved Bash header. This corrects text selection in an existing flow; existing real-Claude tool-row coverage remains applicable, with no new operator action or scenario.
