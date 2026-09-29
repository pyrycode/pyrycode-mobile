# Channel Info visual alignment (#1266)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ChannelInfoSheet.kt` → `ChannelInfoSheet`, `ChannelInfoSheetContent`, `AboutRow`, `MemoryRow`, `ActionsGrid`: current layout, data and callback contract.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenChannelInfoTest.kt` → `ThreadScreenChannelInfoTest`: existing state and action coverage; extend with visual-content and compact text checks.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` → `PyrycodeMobileTheme`: fixed dark M3 roles supplied by #1225 and #1226.
- `docs/knowledge/features/channel-info-sheet.md` → `ChannelInfoSheet`: prior layout and memory-state decisions; current ticket supersedes the workspace label and full-width Rename layout.
- `docs/knowledge/features/development-verification.md` → Compose evidence: shared Robolectric placement and device capture expectations.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-48 — inspected 2026-09-29.

The 412-wide dark sheet uses a `surfaceContainerLow` rounded top, centered drag handle, title-large name and close icon, label-large section captions, compact information rows, an inline memory-install button, filled tonal pill actions and a quiet monospace ID footer. It has no visible row dividers. The design's Workspace row and Change workspace button conflict with the 2026-09-28 product decision: present the existing path as read-only **Folder**, and omit that button. Settings storage sections are absent.

## Context

The existing sheet is functional but predates the current visual reference. This slice aligns the layout without changing the `ChannelInfoUiModel` contract or the host's callback and capability gates. The model's `workspacePath` field supplies the conversation folder path; renaming the field would create unrelated caller churn.

## Design

- Keep `ChannelInfoSheet` as the `ModalBottomSheet` shell and `ChannelInfoSheetContent` as the stateless body. Set the shell surface role explicitly, preserving the current drag handle and dismiss behavior.
- Change the first About label to **Folder**. Preserve start ellipsis for the read-only path, with the leaf visible.
- Use two equal-width filled tonal cells for Rename and Archive, then a full-width Delete cell. This preserves the Figma's row rhythm without inventing a fourth action. Retain the `mutationsSupported` gate.
- Bound title, non-path About values, memory status and provider text within weighted slots; allow wrapping where a single line would hide critical state. Keep the whole body vertically scrollable so enlarged text never strands footer or actions.
- Keep token-based colors and typography. Preserve the existing memory availability mapping, absent-only Install, and long-press ID copy.
- Add a dark 412dp preview and a device-only capture fixture for a 412 × 892 logical viewport. Place the Figma render, emulator capture and labelled comparison artifact under `app/src/androidTest/assets/channel-info-1266/` for review, if device capture can run in this environment.

## State and concurrency

No new state, flow or coroutine. The sheet remains driven by its host; scrolling is local Compose state and ends with composition.

## Error handling

No new I/O. Existing unknown, absent, unavailable and provider memory states retain their current labels and action gating. Long untrusted provider names stay inert and length-bounded.

## Testing strategy

- Extend `ThreadScreenChannelInfoTest` for Folder, absence of Workspace/Change workspace, memory states and usable action callbacks.
- Add focused compact-width and enlarged-text Compose assertions for readable/reachable controls and no horizontal overlap.
- Run the touched shared test class, `lint`, `assembleDebug`, `compileDebugAndroidTestKotlin`, and Spotless. Run a focused device capture test, inspect its executed XML, then compare the emulator image with the Figma render at the same logical viewport. Check menu and sheet controls for clipping.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/channel-info-sheet.md` sections **What it does**, **Row contracts**, **Color & typography mapping**, **Preview**, and **Tests** to reflect Folder, the three-action grid and visual evidence. The builder does not edit that shared overview.

## Open questions

- Resolve during capture whether M3's default drag handle and top inset already match the Figma geometry; adjust only on observed mismatch.
