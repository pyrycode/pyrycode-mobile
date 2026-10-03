# #1619: Capture the six #1539 reachable-state frames and record their verdicts

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/design/ThreadDesignCaptureTest.kt`: `install()` (the repository
  override, which gains three test-owned hooks), `openThread`, `await`, `message`, `refusalStateFramesAt412By892`
  (the #1540 analogue) and `runConfigurationAndReaderAt412By892` (reaches the reader through `onOpenMarkdownLink`).
- `app/src/androidTest/java/de/pyryco/mobile/design/DesignInputs.kt`: `hostModal`, the flow a resolved prompt is
  set on.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadRow.kt`: `foldQueuedRows` (a backlog item
  with no `messageId` is appended as an unmatched `ThreadRow.Queued`) and `toolNestingDepths` (depth from
  `parentToolUseId`). Read only.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/QueuedMessageRow.kt`: the `Row` of glyph, bubble
  and drop `IconButton`, end padding `MessageContentGutter`. Read only.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt` and `ToolRowFormat.kt`:
  `toolHeadline` (a `Bash` description takes the described header with its chevron; other tools take the first
  of `TOOL_SUBJECT_FIELDS`, paths cut to four segments by `shortenToolPath`), `TrailingStatus`. Read only.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `onAttachmentShown` (images load
  on sight), `onAttachmentRequested` (other files load on request), `retrieved` (`NotFound` → not found,
  `Unavailable` → failed). Read only.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: the `EmptyThreadState` branch (no
  items, no queue), and the `ModalUiState.Dismissed` branch, which shows `dismissReasonText` in the snackbar host.
  Read only.
- `app/src/main/java/de/pyryco/mobile/data/model/ModalUiState.kt`: `HostModalState.resolved`. Read only.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt`:
  `RefreshableMarkdownReader` shows "Couldn't open file" in its snackbar host when the refresh's reread fails;
  `rememberNoteSaver` reports Saved and Save failed only after the system's create-document picker returns.
  Read only.
- `app/src/androidTest/assets/design-1220/thread/index.md`: the per-item format of "Thread states (#1529)" and
  **Routed defects**; `design-1220/README.md`: the six inventory rows.
- `scripts/design-compare.py`: full-frame side-by-side and overlay.

No in-flight feature branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=696-4676

Six 412x892 dark frames in section Reachable states · #1539: queued rows (`696:4677`; a clock glyph, a dimmed user
bubble and an X, the first row wrapping to three lines), sub-agent tool rows (`696:4795`; Agent running, Grep done
with "12 files", Read failed, Agent running, and a described "Run the unit tests" row at 14s, indented 16 px per
level), message attachments (`696:4913`; an image bubble with a spinner, then Loading…, Couldn't load file with
Retry, and File not found file tiles), the empty thread (`696:4989`; "Send a message to get started" in
body-small on-surface-variant), the prompt resolved elsewhere (`696:5065`; a "Resolved on another device" Default
pill under the header at the right) and the reader's save-failed notice (`696:5101`; an Error pill "Couldn't save
file" under the header at the right, no X).

## Change

`ThreadDesignCaptureTest` gains three methods, each opening the thread fixture with context usage set:

- `queuedAndToolRowFramesAt412By892`: the band thinking, an override `observeQueue` set to the frame's two
  backlog items (no `messageId`, so they fold as unmatched queued rows), wait for both texts, capture
  `queued-messages` (`696:4677`). Then a third item with a long single-paragraph text, captured as
  `queued-long` (evidence only, compared against the same frame's row geometry). The queue is cleared, and five
  tool messages with `parentToolUseId` chains (depths 0, 1, 1, 1, 2) and the frame's statuses, subjects, result
  count and elapsed reading are appended; capture `tool-rows-nested` (`696:4795`).
- `attachmentAndEmptyFramesAt412By892`: a user message with one image and an assistant message with three files,
  whose retrievals the override answers from a test-owned map of gates (the image and the log never complete, the
  PDF answers `Unavailable`, the YAML `NotFound`); the three files are requested through `onAttachmentRequested`.
  Wait for the three state lines and capture `attachment-states` (`696:4913`). Then the override's seed flag hides
  the fake's messages and the extras are cleared; wait for the empty text and capture `empty-thread`
  (`696:4989`).
- `dismissalAndReaderNoticeFramesAt412By892`: `hostModal` gets one `Dismissed` with source `remote` for the
  conversation; wait for "Resolved on another device" and capture `prompt-resolved-elsewhere` (`696:5065`). Wait
  out the snackbar, open the linked note, set the override's note read to fail, tap Refresh in the reader's
  overflow menu, wait for "Couldn't open file" and capture `reader-notice` (`696:5101`).

The override's new hooks (`queue`, `attachmentGates`, `seedShown`, `noteReadFails`) default to the fake's own
behaviour or the existing answers, so no existing method changes. Nothing under `app/src/main/` changes.

Save failed and Saved are reported only after the system's create-document picker returns, which the harness
cannot drive without a new test dependency. They share the reader's snackbar host and style with the open-failed
notice, so `reader-notice` judges the host's placement for all three; the verdict says so.

Each capture is compared at full frame with `scripts/design-compare.py <capture> figma-<node>.png <name>`. The six
exports are committed as `figma-<node>.png`. Each state gets an entry in a new "Reachable states (#1539)" section
of `design-1220/thread/index.md` in the #1529 per-item format, and the six README rows move to their verdicts and
point at it. The dismissal and reader notices are expected mismatches (Juhana's 2026-10-02 change to a top-overlay
pill); with the thread's confirmations they route to #1604 as a comment extending its scope, since #1604 is open.
Any other mismatch goes to a new scoped defect, joined to **Routed defects**.

## Testing strategy

Device-only: the captures need real pixels on the `pixel8Api35` image with real system bars, as every
`ThreadDesignCaptureTest` method. Focused run:

```bash
./gradlew :app:pixel8Api35DebugAndroidTest --rerun \
  '-Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.design.ThreadDesignCaptureTest#queuedAndToolRowFramesAt412By892,de.pyryco.mobile.design.ThreadDesignCaptureTest#attachmentAndEmptyFramesAt412By892,de.pyryco.mobile.design.ThreadDesignCaptureTest#dismissalAndReaderNoticeFramesAt412By892' \
  -Pandroid.testInstrumentationRunnerArguments.requireRealSystemBars=true --console=plain
```

Its results XML is committed as `design-1220/thread/1619-results.xml`. Every state waits strictly for its marker,
so a state that never renders fails the run. `compileDebugAndroidTestKotlin`, `lint`, `assembleDebug` and
`spotlessCheck` locally. No rung-3 scenario: an audit, not an operator-facing flow.
