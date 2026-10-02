# #1488 Channel Info matches the full-height frames 668:5355 and 668:5460

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ChannelInfoSheet.kt` — `ChannelInfoSheetContent` (section order), `AboutRow`, `ActionsGrid`/`ActionCell`, `Footer` (the `Modifier.alpha(0.5f)` on the Channel ID text), `SAMPLE_MODEL`, `SAMPLE_PROMPT`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/McpServersSection.kt` — `McpServersSection` hides built-in servers by default, so `github` and `postgres` show with Show built-in off.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/ChannelInfoCaptureTest.kt` — `showSheet` and `capture`, the #1266 device capture this ticket extends.
- `app/src/androidTest/java/de/pyryco/mobile/design/ListDesignCaptureTest.kt` — the `channel-info` capture that still names `20:48`.
- `scripts/design-compare.py` — side-by-side and overlay builder.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=668-5355 (Top) and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=668-5460 (Scrolled to Actions)

A full-height 412x892 `ModalBottomSheet` on `Schemes/surface-container-low`: drag handle, title row, then About, Session, Memory, System prompt, MCP servers and Actions headers in `M3/label/large` on `Schemes/on-surface-variant`, 40 px About rows, Rename/Archive/Delete as `Schemes/secondary-container` pills (100 px radius, `M3/label/large` on `Schemes/on-secondary-container`), and a Roboto Mono 11/16 Channel ID footer at opacity 0.55. The Scrolled frame is the content scrolled to its end (content 1320 px, viewport 868 px), so the Actions grid and the footer show above a 24 px bottom gap.

## Change

Read on 2026-10-03, the frames' metadata matches `ChannelInfoSheetContent`: section order and headings, About rows on a 40 px pitch with the About header 36 px tall (label 12 px from its top, so 32 px from label top to the first row), Actions grid with 4 px top, 8 px gaps and 40 px pill buttons, and the footer's 24 px top and side padding. The one known difference is the footer opacity; `Footer` changes from `alpha(0.5f)` to `alpha(0.55f)`. Any further mismatch the device comparison shows in the criteria's listed elements is fixed in `ChannelInfoSheet.kt` and recorded under Revisions. `ListDesignCaptureTest`'s channel-info capture names `668:5355` instead of `20:48`. Nothing else moves: the sheet already opens full height (`skipPartiallyExpanded = true`), the first About row already reads "Folder" and there is no Change workspace action.

## Testing strategy

Device-only, because the evidence is real pixels from the emulator. `ChannelInfoCaptureTest` gains two methods that show the sheet in the frames' sample state — session facts `2.1.143`/`acceptEdits` with $0.42, memory `Absent`, a loaded prompt "Answer in short paragraphs." whose applied status matches (the frames show no "differs" line), and MCP servers `github` (connected) and `postgres` (failed, "MCP error -32000: Connection closed") — at 412x892, density 1.0, dark:

- `channelInfoTopMatches668_5355` asserts Folder, None and Install display, and that "Change workspace" does not exist, then captures the top.
- `channelInfoScrolledMatches668_5460` scrolls the sheet content to its end, asserts Rename, Archive, Delete and the Channel ID footer display, then captures.

The captures land in `app/src/androidTest/assets/channel-info-1488/` with the Figma exports, `design-compare.py` side-by-side and overlay images, a `capture-context.txt` and the device result XML (executed count, no failures or skips). The existing three methods keep writing under `channel-info-1266/`.

## Documentation handoff

Pending for the documentation stage: in `docs/knowledge/features/channel-info-sheet.md`, replace the `20:48` reference (the opening paragraph and the Session section note) with `668:5355` and `668:5460`; record the 2026-10-02 decision that the sheet keeps Session, System prompt and MCP servers and opens full height; link the evidence under `app/src/androidTest/assets/channel-info-1488/`.
