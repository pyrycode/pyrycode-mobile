# #1529: Capture the #1529 thread-state frames and close the thread audit's gaps

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/design/ThreadDesignCaptureTest.kt`: `install()` (the repository
  override), `openThread`, `stageAttachments`, `thinking`, `await`. The new method follows
  `threadStatusFramesAt412By892`.
- `app/src/androidTest/java/de/pyryco/mobile/design/DesignInputs.kt`: the shared flows; left unchanged unless an
  input every audit needs turns up (the ticket allows either).
- `app/src/androidTest/java/de/pyryco/mobile/design/DesignCapture.kt`: `capture`, `openKeyboard`, `openMenu`.
- `app/src/androidTest/assets/design-1220/thread/index.md`: the **Gaps** table and paragraph, and the per-frame
  section shape.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBar.kt`: the Send icon's comment that the
  component has no Stop variant (routed, not edited).
- `scripts/design-compare.py`: side-by-side and overlay output.

One overlap: #1519 edits other sections of the same index (`568:3139`, the Routed defects line). Edits here stay in
new sections, the Routed defects line's #1529 entry and the Gaps section.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=685-3991

Nine 412x892 frames in section `685:3991`: Turn outcome `685:3992`, Unrecognized message `685:4112`, Slash command
type-ahead `685:4232`, Failure notice `685:4337`, History tail Loading `689:4281`, Retry `689:4330`, Dead end
`689:4379`, Offline `689:4427`, Uploading attachments `689:4475`. Exported 2026-10-03 with `get_screenshot` as
`figma-<node>.png`.

## Change

Test-only. Three new `ThreadDesignCaptureTest` methods capture the nine frames, each waiting strictly for its
marker as the existing methods do:

- `rowAndNoticeFramesAt412By892`: two `ThreadItem.UnrecognizedMessage` rows through `extraItems` (the second
  expanded by a click, truncated); a `LiveSessionEvent.TurnEnd` with `terminalReason = "prompt_too_long"` plus the
  matching `ThreadItem.StoppedTurn` row (today's band outcome); a failing `archive` from the overflow menu (today's
  bottom snackbar); last, with the keyboard open, `/co` against a `SlashCommandMenu` set on the fake.
- `historyTailFramesAt412By892`: `requestHistory` held on a test-owned gate, so the open's newest-page ask shows
  Loading; failed retryably (Retry), retried with `onRetryOlderHistory` and failed permanently (Dead end); then the
  view model's `repositoryAvailable` set false (Offline). The list is scrolled to the tail's key.
- `uploadingFrameAt412By892`: the frames' four staged tiles, `sendMessage`, and an `uploadAttachment` that reports
  4 of 10 chunks and then suspends.

The override in `install()` gains `archive`, `requestHistory` and `uploadAttachment` hooks and passes a
`repositoryAvailable` flow; `requestHistory` and `repositoryAvailable` keep the fake's behaviour unless a test sets
them, and no existing method archives or uploads, so the existing captures are unchanged. `DesignInputs` is not
edited.

`index.md` gains one section per frame with the nine-aspect verdict table, a "No separate frame" section with one
line per decided state and its reference node, and the Gaps table loses every #1529 row; the paragraph no longer
says the Input area has no Stop variant. Mismatches are routed to new scoped tickets (none open covers them), named
in each section and in the Routed defects line.

## Testing strategy

Device-only (real `MainActivity`, real pixels, IME): run the new method on the full image,

```
./gradlew :app:pixel8Api35DebugAndroidTest --rerun \
  '-Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.design.ThreadDesignCaptureTest#gapFramesAt412By892' \
  -Pandroid.testInstrumentationRunnerArguments.requireRealSystemBars=true --console=plain
```

copy its results XML to `design-1220/thread/1529-results.xml`, then `scripts/design-compare.py` per frame.
`compileDebugAndroidTestKotlin`, `lint`, `assembleDebug` and `spotlessCheck` locally. No rung-3 scenario: an audit,
not an operator-facing flow.
