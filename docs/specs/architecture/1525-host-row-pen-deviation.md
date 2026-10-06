# #1525 — Host row edit pen as a deliberate deviation from Figma 15:8

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt`: `TreeHostRow` and its
  `TreeRowControl` pencil, unchanged.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRowsTest.kt`:
  `hostRow_editControl_isNamedForItsHostAndReportsOnlyItsOwnTap`, `hostRow_editControl_clampsAnOversizedIdIntoItsTestHandle`
  and `hostRow_longName_staysOnOneLineAndLeavesTheEditControlInsideTheRow`, unchanged.
- `docs/knowledge/features/channel-list-screen-tree-and-controls.md`, section "Host row edit control (#744)": read
  only; the documentation stage writes the deviation note.

## Design source

Figma `15:8` (https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8), reference only. It draws no pen on
host rows; the shipped row keeps its pen on purpose, so nothing visible changes and the visual check has no delta.

## Change

None in code. The ticket's decision (option 1, keep the pen) is that `TreeHostRow`'s pencil is the only Edit host
entry point for a non-owner host, since Settings opens the editor for the owner only through
`SettingsViewModel.openOwnerHostEditor`. No file under `app/src/main/` or `app/src/` changes; the record of the
deviation is a documentation-stage item.

## Testing strategy

The existing host-row edit-control tests in `ConversationTreeRowsTest` cover the acceptance criterion as they stand;
they are run unchanged and must pass.

## Documentation handoff

Pending for the documentation stage: in `docs/knowledge/features/channel-list-screen-tree-and-controls.md`, section
"Host row edit control (#744)", add a short paragraph stating that the pen is a deliberate deviation from Figma
`15:8` (which draws none on host rows), decided in #1525, because it is the only Edit host entry point for a
non-owner host; removing it needs another entry point with its own design first.

## Revisions

**2026-10-06:** Figma `15:8` now draws a persistent pencil on every host row too, matching the shipped row — the
deviation this ticket recorded is resolved, not just tolerated. `docs/knowledge/features/channel-list-screen-tree-and-controls.md`,
section "Host row edit control (#744)", is updated to drop the "deviation" framing; the pencil stays the only Edit
host entry point for a non-owner host, which is still true independent of the design delta. No code changes — the
row's pencil was already unconditional and non-hover-gated, which is what the frame now also shows.
