# #1434 — App-wide Figma parity verdict

## Files read

- `app/src/androidTest/assets/design-1220/README.md` — the harness, folder layout and per-item index format from #1430; the file this ticket extends.
- `design-1220/onboarding/index.md` (#1430), `list/index.md` (#1431), `thread/index.md` (#1432), `prompts/index.md` (#1433) — every audited item, its node id, owning ticket, routed issues, and each audit's Gaps table.
- `MainActivity.kt`, its navigation graph's `composable` routes and `Routes` — the reachable route set. `Routes.DISCUSSION_LIST` and `Routes.ABOUT` have destinations but no `navigate` caller.
- `ThreadScreen.kt` — the overlays drawn over the thread: `StatusSheet` (Run configuration), `ChannelInfoSheet`, `BackgroundTaskPanel`, `RenameDialog`, `SaveAsChannelDialog`, `DeleteConfirmationDialog`, `WorkspacePicker`, `OptionsOverlay`, `SlashCommandTypeAhead`, the snackbar host, and the rows of the message list (`MessageBubble`, `SessionBoundaryDelimiter`, `UnrecognizedMessageRow`, `BannerNoticeRow`, `CompactionBoundaryDivider`, `ModelRefusalRow`, `StoppedTurnRow`, `QueuedMessageRow`, the history tail rows).
- `MarkdownReaderScreen.kt` — the reader's overflow `DropdownMenu` and snackbars.
- Figma `g2HIq2UyPhslEoHRokQmHG`, Mobile page `0:1` and Components page `347:5692`, read with `get_metadata` on 2026-10-02.
- Open issues #1488, #1502, #1504 and #1529 — each carries a design decision that added Mobile frames after its audit, and owns their capture.

## Context

#1430 to #1433 each audited a surface group and routed its own mismatches. None of them declares app-wide parity. This ticket joins their indexes into one inventory in `design-1220/README.md`, checks it against the reachable routes and the current Mobile page, and states the verdict by a mechanical rule: parity only when no linked issue is open.

No production code and no test code change. No decision record is needed: the verdict rule is the ticket's own.

## Design

`design-1220/README.md` gains three sections after the existing harness and commands text, which stays as it is:

1. **App-wide inventory.** One table per area named in the acceptance criteria (onboarding, channel list and sidebar, thread and composer, messages and tools, attachments, Markdown reader, Archive, Channel Info, Settings, forms, Run configuration, background tasks, questions and permissions). Each row is one screen, modal, sheet, menu or material state with: Figma node, status (`audited`, `gap`, `retired`, `frame only`), the audit row it links (`<folder>/index.md`, section heading) or the reason it has none, the owning ticket and the linked issues.
2. **Coverage check.** Current Mobile frames read on 2026-10-02 that no audit compared, each tied to the open ticket that owns its capture; reachable states with no reference anywhere; routes and dialogs that exist in source but are unreachable (with the call-chain evidence); the retired workspace surfaces, pointing at #1431's reachability table.
3. **Linked issues and verdict.** Every linked issue with its state at the recorded `main` commit, then the verdict.

### Frames added after the audits

The Mobile page now holds four sections the audits did not compare: Channel Info `668:5355` and `668:5460` (#1488), List states `670:5299` (#1504), Prompt edge states `668:3051` (#1502) and Thread states · #1529 `685:3991` (#1529). Each was drawn for an open ticket whose acceptance criteria already require a `ListDesignCaptureTest`, `PromptsDesignCaptureTest` or `ThreadDesignCaptureTest` capture compared with `design-compare.py`. The inventory lists them as `frame only` and links that ticket. Capturing them here would duplicate those tickets' work and their index edits, and the 24 frames are far beyond this ticket's "small capture addition".

### States with no reference

Reachable states found in source with no Mobile frame, no Components-page component and no open ticket are routed to one new design ticket, in the #1529 shape: frames or an out-of-reference decision for each. Three Components-page states that no audit captured (Thread notification `Expanded`, `Switch back pending`, `Switch back failed`) are routed to one new capture ticket.

### Verdict

Many linked issues are open on 2026-10-02, so the README says "parity not reached" and links one new re-verification ticket, blocked by every open linked issue.

## Testing strategy

No code changes, so no unit or screen test. Checks: `spotlessCheck` (formats Markdown), `scripts/docs-guard.sh`, and every linked issue's state re-read with `gh issue view` immediately before the README commit.

## Open Questions

- Should the post-audit frames be captured here? Resolved above: no, their owning tickets already require the capture.
