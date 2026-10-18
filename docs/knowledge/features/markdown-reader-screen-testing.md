# Markdown reader — testing

Reader behavior and geometry: [screen overview](markdown-reader-screen.md).

## Testing

The [heading-scale coverage and counted #2277 acceptance](markdown-text.md#testing) exercise
Reader rendering in light and dark themes through streaming and settlement, including
untrimmed line boxes. `MarkdownReaderDesignTest.referencePlacesBarRuleAndBodyAtFigmaCoordinates`
retains density-1 exact gap assertions with the shared heading scale, 14/20sp prose and 16dp ordinary
block spacing. Reference tops are heading 97dp, paragraph 141dp, subheading 217dp,
first item 259dp, code 347dp and quote 421dp (#2284 shared panel).
New Reader-screen integration checks remain assigned to #2276.

### Reader body typography coverage (#2283)

`MarkdownTypographyTest` uses native graphics to check both attachment and linked-note
readers in light and dark themes: Roboto 14/20, 0.25sp tracking, body/quote colours,
bold table headers, link colour/underline and untrimmed 20sp line boxes. Check measured
height as well as the declared style: default trimming can shorten a single-line box.
Tables need the reader body style explicitly; see [table styling](markdown-text-internals.md#tables).

`MarkdownReaderDesignTest` retains chrome, gutter, scroll, pointer and quote-bar checks.
At the reference viewport, code/quote tops are now 347/421dp after #2284
(previously 347/427dp after #2285; the #2283 typography-only positions were 331/407dp). The wrap fixture
is deliberately long enough to wrap at 14sp; a formerly wrapping 16sp fixture could
otherwise stop testing continuation at the gutter. Heading and code styles stay as documented.

The [accepted verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/2287#issuecomment-6252413269)
records Linux acceptance on `5e1c4079ac6a888578ac03d8f696d0ba6b829ce3` (2026-10-18):
JVM gate **5,987 executed/passed, 0 failed, 0 skipped**; UI gate **334 executed/passed,
0 failed, 1 skipped**; scripted gate **22 executed/passed, 0 failed, 0 skipped**.
The unrelated `renameAtFigmaViewport` was skipped, not passed.

Retained focused builder XML records **105 executed/passed, 0 failed, 0 skipped**,
including `MarkdownTypographyTest` **9/9** and `MarkdownReaderDesignTest` **16/16**.
The separate managed Android 13 capture run records **2 executed/passed, 0 failed,
0 skipped**: `MarkdownReaderCaptureTest.referenceAndMenuAt412By892` and
`compactLargeTextKeepsControlsAndBodyReachable`. Both also passed in the full UI gate.
Raw reports and captures were retained at
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2283/builder-20261018-5e1c4079a/`
(`jvm/`, `device/`, `captures/`); this is their location at review time, not permanent storage.
macOS is outside this Linux ticket's verification scope. No live-provider scenario changed.

### Ordinary block bounds coverage (#2285)

`MarkdownTypographyTest.assertReaderBlockBounds` checks attachment and linked-note readers
using native graphics: paragraph/paragraph, paragraph/list, list/code,
paragraph/code and code/paragraph gaps are 16dp for labelled and plain panels. Recursive
body checks cover lists and quotes; sibling items remain 6dp. Bounds also pin the measured
bar plus 28dp top inset, 20dp gutters and 16dp bottom padding at the scroll end, with unchanged
tolerances. Measure panel surfaces including padding; see
[block dispatch](markdown-text-internals.md#block-dispatch).

The [accepted verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/2289#issuecomment-6253642117)
records Linux acceptance on 2026-10-18 at `2c78d3960643ac75cfa820207c34df19b5c645fe`:
JVM **5,994 executed/passed, 0 failed, 0 skipped**, including `MarkdownTypographyTest`
**11/11** and `MarkdownReaderDesignTest` **16/16**, with
`referencePlacesBarRuleAndBodyAtFigmaCoordinates` passed. UI **334 executed/passed,
0 failed, 1 skipped**; scripted **22 executed/passed, 0 failed, 0 skipped**.
Both `MarkdownReaderCaptureTest.referenceAndMenuAt412By892` and
`compactLargeTextKeepsControlsAndBodyReachable` passed in the full UI gate;
`renameAtFigmaViewport` was skipped and is not counted as passed.

Builder XML at `ff9fa380ebcb4596781d77c9eb1c8430e3411748` confirms **27 JVM
executed/passed** (11 typography + 16 design) and **2 device executed/passed**,
with zero failures, errors or skips. Raw reports were at
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2285/builder-20261018-ff9fa380e/`
(`jvm/`, `ui-zaxf6au9/`) at review time. macOS is outside this ticket's
verification scope; no new live-provider scenario is required.

### Shared code-panel coverage (#2284)

All reader code forms use the shared panel; see [code blocks](markdown-text-internals.md#code-blocks).
`MarkdownTypographyTest` checks labelled/unlabelled fences and indented code through both
attachment and linked-note readers, including light/dark cases, at 12sp/20sp monospace.
Its bounds checks measure the panel ancestor of the code viewport, preserving the exact
16dp gaps instead of depending on the removed `reader-code-panel` tag. The reference's
two-line shared panel is naturally 58dp high because its text trims the outer line boxes;
the old reader-only panel forced a 64dp minimum. Consequently the following quote starts
at 421dp rather than 427dp. Keep prose/headings untrimmed and preserve existing spacing
assertions and fractional-density allowances; code-panel reuse does not redefine those roles.

`MarkdownTextTest.reader_code_forms_keep_only_the_labelled_header_and_visible_copy_controls`
distinguishes the visible control from the former whole-panel copy action. A semantics
copy action alone could pass with that old panel. The labelled, unlabelled and indented
scroll tests assert a stationary copy rail outside the horizontally scrolling viewport,
without widening the container. Exact-copy checks retain blank lines and code whitespace,
with the syntax indent removed from indented blocks. The clipboard-bound test first scrolls
the tall block's bottom control into view, then checks the original 100,000-character prefix;
a semantics click on an offscreen control is not operator interaction proof.

`MarkdownReaderDesignTest.headerBlocksUnderlyingCodePanelWithPositiveClearAreaControl`
physically taps the visible copy glyph as a positive control, then scrolls its centre beneath
the fixed header and taps that root coordinate. The sentinel clipboard must remain unchanged.
This proves header pointer interception; invoking the control's semantics action beneath
the overlay would bypass the behavior being tested.

The [accepted verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/2290#issuecomment-6256496269)
records Linux acceptance on 2026-10-18 at `aee419a9cd01ba5f74849a1e10be4a6ef59e1e3f`.
The current JVM gate XML contains **57 executed/passed, 0 failed/errors, 0 skipped** across
`MarkdownTextTest` (29), `MarkdownTypographyTest` (11) and `MarkdownReaderDesignTest` (17).
The named copy/header, exact-source, clipboard-bound, three scroll-form and reference-geometry
methods are present and passed. These are results within the full configured JVM gate,
which had **5,999 executed/passed, 0 failed, 0 skipped**; UI had **334 executed/passed,
0 failed, 1 skipped** and scripted **22 executed/passed, 0 failed, 0 skipped**.
`renameAtFigmaViewport` was skipped, not passed. Counted XML was retained in
`/work/Projects/pyrycode-mobile-agents/logs/verifier-gate_#2284_4.log`, with UI/scripted
reports in `_5.log` and `_6.log`, at documentation time.

Separately, [committed focused Linux XML](../../../app/src/test/resources/reader-code-panels-2284/)
at builder source `cf7a5ee2d1def7df63e5d33fa7f649f833ad962d` records **52 executed/passed,
0 failed/errors, 0 skipped**: MarkdownText 29, typography 7 and design 16, before later main
merges. No affected screenshot goldens were present. No fresh app screenshot or real-Claude
run was required or produced for this change. macOS is outside this Linux ticket's scope.

### Fractional-density coordinate coverage (#2292)

Adjacent block tops can accumulate rounding even when the following gap is exact.
At density 2.625, the reference paragraph has three 53px line boxes instead of
52.5px each: its 60dp height and 76dp paragraph-to-subheading interval drift by
1.5px. The list has three such items and two 16px sibling gaps instead of 15.75px,
so the 88dp first-item-to-code interval drifts by 2px. Both following 16dp block
gaps are exact. Preserve the nominal coordinates above and the shared helper's
one-pixel default; only those two intervals and the paragraph's total height use
`pixels = 2`. Check the three-line count, each line/item height, each sibling gap
and each following block gap separately with the default allowance. A wider
cumulative allowance alone could conceal a real spacing regression.

`adjacentBlockRoundingAllowanceKeepsItsPixelBoundary` uses the same private
allowance constant as the coordinate method: both signs of two pixels pass,
three pixels fail, and two pixels fail with the unchanged default. At density 1,
even subpixel drift fails with the explicit allowance. See the shared
[pixel comparison guidance](development-verification-gates.md#where-a-screen-test-goes).

The [accepted verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/2297#issuecomment-6255306502)
and retained XML record Linux x86_64 evidence on 2026-10-18:

- Managed Android 13 Pixel 2, density 2.625: unchanged coordinate method at
  `d069c285d` (test source `74e2340de`) **1 executed, 0 passed, 1 failed,
  0 skipped**, expected 76dp versus 76.57144dp. Diagnostic revision `5038a3488`
  also **1 executed, 0 passed, 1 failed, 0 skipped**, establishing the constituents above.
- Same managed image at `c75eab30b`: coordinate method and boundary control
  **2 executed/passed, 0 failed, 0 skipped**.
- Focused Robolectric `MarkdownReaderDesignTest` at `43711d802`:
  **17 executed/passed, 0 failed, 0 skipped**, including both named methods.
- Configured verifier gates at `f93ba5a55`: JVM **5,995 executed/passed,
  0 failed, 0 skipped**, including both named methods; UI **334 executed/passed,
  0 failed, 1 skipped**; scripted **22 executed/passed, 0 failed, 0 skipped**.
  The two shared methods did not run in those full device gates; their native
  proof is the focused run above. `renameAtFigmaViewport` was skipped, not passed.

The committed [evidence index](../../../app/src/androidTest/assets/reader-rounding-2292/evidence.txt)
keeps commands and revisions. Raw XML, phone diagnostics and source identities were at
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2292/builder-20261018/`
at review time (`red-d069c285d/`, `diagnostic-red-5038a3488/`,
`native-green-c75eab30b/`, `jvm-green-43711d802/`); raw captures are subject to retention.
macOS is outside this ticket's verification scope.

The [accepted #2298 confirmation](https://github.com/pyrycode/pyrycode-mobile/pull/2299#issuecomment-6255858638)
records fresh Linux evidence on 2026-10-18 at
`3d6999fe647f29f3b2ccd92b30456af6fb1b5919`, containing merged #2297 at
`e02920bf71c18c9ce3ece6f11b18ee57ffaf1cbd`. Production rendering, test sources,
nominal geometry, constituent checks and tolerances were unchanged:

- Focused managed Android 13 Pixel 2, density 2.625: the coordinate method and
  `adjacentBlockRoundingAllowanceKeepsItsPixelBoundary` both passed;
  **2 executed/passed, 0 failed, 0 errors, 0 skipped**.
- Focused Robolectric `MarkdownReaderDesignTest`: **17 executed/passed,
  0 failed, 0 errors, 0 skipped**, including both named methods.
- Configured dispatcher gates passed at `1876d9b6795e71d47ccca7b0985f78adfdaab805`:
  JVM **5,995 executed/passed, 0 failed, 0 skipped**; UI **334 executed/passed,
  0 failed, 1 skipped**; scripted **22 executed/passed, 0 failed, 0 skipped**.
  Both reader methods passed in the JVM gate; their native proof is the focused
  run above, since neither ran in the full device gates.
  `renameAtFigmaViewport` was skipped, not passed.

The [#2298 evidence index](../../../app/src/androidTest/assets/reader-sweep-2298/evidence.txt)
links the accepted #2292 repair and retains the focused commands and source revision.
Raw XML, phone logs, image identity and source records were at
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2298/builder-20261018/`
(`native-3d6999fe6/`, `jvm-3d6999fe6/`) at review time, subject to retention.
The original `f8a397b` in-depth sweep remains failed: **1,726 executed,
1,725 passed, 1 failed**. The next in-depth Linux main sweep remains
dispatcher-owned and pending; neither focused confirmation nor the configured
gates establish that sweep passed. macOS is outside this Linux ticket's scope.

### Native memory regression evidence (#1759)

The [accepted verifier record](https://github.com/pyrycode/pyrycode-mobile/pull/1825#issuecomment-6006058557)
on 2026-10-06 reviewed commit `1d9aff52949227e2559216e96a40218f6a32df82`
and the preserved native XML on `pixel2Api33Atd` (Android 13 Google ATD):

- Routine `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui`:
  **186 executed, 186 passed, 0 failed, 1 skipped** in
  `build/dispatcher-tests/ui-sygy1igf/dispatcher.xml`.
  `MarkdownReaderMemoryTest.noteAtSupportedBound_scrollsCopiesAndReturnsWithoutTermination`
  is present and passed unskipped. The unrelated skip is
  `RenameDialogCaptureTest.renameAtFigmaViewport`.
- Separate full native `MarkdownReaderScreenTest` class run with
  `./gradlew :app:pixel2Api33AtdDebugAndroidTest --rerun`
  and instrumentation arguments `class=de.pyryco.mobile.ui.conversations.thread.MarkdownReaderScreenTest`
  and `notPackage=de.pyryco.mobile.e2e`: **25 executed, 25 passed, 0 failed, 0 skipped**.
  `/tmp/builder-1759/rework-evidence/reader-screen-20261006.xml` explicitly contains
  the enabled, passing `copiesOfANoteAtTheReadersBound_areBounded` method. Routine UI
  selection excludes `app/src/sharedTest` classes, so its total cannot prove this method
  ran on the native engine; retain the separate whole-class evidence.
- Initial focused native `MarkdownReaderMemoryTest` run: **1 executed, 1 passed,
  0 failed, 0 skipped**. The builder's temporary pre-`ef587984` renderer negative
  control: **1 executed, 0 passed, 1 failed, 0 skipped**, exit 1 with instrumentation
  `Process crashed`. The focused and negative XML copies are named
  `builder-1759-focused-TEST-pixel2Api33Atd-_app-.xml` and
  `builder-1759-negative-TEST-pixel2Api33Atd-_app-.xml` under
  `/tmp/verifier-1825/review-1d9aff52/`. XML establishes termination; the temporary
  renderer selection and allocation diagnosis are builder-reported. The repaired
  renderer was restored before green runs, with no production reader diff.

### Reader presentation and earlier coverage

`MarkdownReaderScreenTest.leavingTheReader_cancelsItsActiveAndPendingErrorNotices`
must establish both an active notice and a queued second occurrence before leaving
the reader (#2042). File operations complete outside the Compose clock: observe both
static open-operation outcomes and hold notice time while the first occurrence remains
active. After changing visibility, deliver `Snapshot.sendApplyNotifications()` before
driving the clock until actual composition disposal; changing the visibility flag alone
does not prove disposal. Assert the pill is absent and notice state is null, then retain
the original 4,100 ms advance and assert the occurrence has not changed, proving the
queued notice never started. Restore the diagnostic logger and clock in `finally`.
This synchronizes the fixture's asynchronous preconditions; it does not establish a
production cause for the earlier unmatched full-suite failure or its teardown exception.

The current reader-menu captures are retained under
[`reader-actions-1667/`](../../../app/src/androidTest/assets/reader-actions-1667/):
`reference-412x892.png`, dark `menu-412x892.png`, `menu-light-412x892.png`,
`compact-large-text-320x700.png`, `compact-menu-320x700.png` and `compact-scrolled-320x700.png`.
[Device XML](../../../app/src/androidTest/assets/reader-actions-1667/device-results.xml)
records 3 executed, 0 failed/errors, 0 skipped on API 33/pixel2Api33Atd.
These root-View draws establish menu appearance and compact presentation, not hardware
backdrop/chrome fidelity; title-shadow artifacts also occur in the closed-menu reference.
The menu uses the shared Below Actions presentation described in [reader menu](markdown-reader-menu.md#presentation).
Placement and pointer tests prove live anchoring, dismissal without tap-through and compact last-row
activation; capture helpers await same-window column semantics rather than separate popup roots.

The [dispatcher’s fresh full live gate for #1667](https://github.com/pyrycode/pyrycode-mobile/issues/1667#issuecomment-5987861689)
on 2026-10-05 ran `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live`
against `fef53301e5` merged with main `90d395d1b1`, exit 0: **53 executed, 53 passed,
0 failed, 0 skipped**. The retained dispatcher XML report
`2026-10-05T03-45-19-511Z_real-claude-gate_#1667.log` explicitly contains the passing
`InteractiveStreamE2ETest.interactiveTurn_markdownLink_opensLiveNoteInReader` testcase,
with no failure/error/skip child. It reaches Refresh through the new menu. This is full-suite
evidence, not a separate focused run; no new live scenario was added.


The [reader chrome evidence](../../../app/src/androidTest/assets/reader-chrome-1647/README.txt)
retains fresh Figma exports for `553:2574` and `731:6010`, resting and scrolled-under-bar
hardware PNGs, [reader comparison](../../../app/src/androidTest/assets/reader-chrome-1647/reader-comparison.png),
[bar comparison](../../../app/src/androidTest/assets/reader-chrome-1647/top-bar-comparison.png),
overlay/difference and viewport/inset sidecars (2026-10-04). Full `pixel8Api35` with
`requireRealSystemBars=true` produced nonblank hardware-accelerated captures with
`syntheticBars=false`, density/font scale 1 and real 24px status/navigation bars:
the 412 × 892 framebuffer contains a 412 × 844 app area, whereas Figma's frame is a
412 × 892 screen area. Comparison crops remove the bars without stretching the images.
The existing date-wrap difference preserves three-line paragraph height and subsequent
block positions.

[Final hardware XML](../../../app/src/androidTest/assets/reader-chrome-1647/hardware-results.xml)
records 5 executed, 0 failed/errors, 0 skipped. `ThreadDesignCaptureTest.runConfigurationAndReaderAt412By892`
passed with exact 24/69/97dp coordinates, 28dp clearance and 20dp gutters.
The four passing `MarkdownReaderDesignTest` methods are
`scrollViewportStartsBehindTheFixedBar` (underlap and final 16dp padding),
`largeTextReservesMeasuredBarPlus28dp` (1.5× text and 48dp targets),
`headerBlocksUnderlyingLinkWithPositiveClearAreaControl` and
`headerBlocksUnderlyingCodePanelWithPositiveClearAreaControl` (physical pointer isolation
with positive taps outside chrome). Retained focused JVM reports record 35 executed,
0 failed/errors/skipped across reader design (10), reader screen (20) and palette (5),
plus a separate passing link-pointer rerun (1 executed, 0 failed/errors/skipped).
This local chrome adoption adds no real-Claude scenario.

Robolectric `@Config` does not set emulator density. An earlier whole-class hardware run
executed 11 tests, with 1 failure and 0 skipped: the existing absolute-coordinate list fixture
accumulated pixel rounding at native density 2.625 (266.29dp versus 265dp). Keep its strict
density-1 JVM assertions; exact hardware coordinates belong in the capture harness's
explicit density-1 viewport. Also, `performScrollTo` makes the final block visible without
traversing trailing content padding. A bottom-padding assertion must scroll to the actual
end before measuring, as `scrollViewportStartsBehindTheFixedBar` does.

`ThreadCanvasPaletteTest` uses native-graphics pixel checks for both screens' canvases and inset rules,
including short and scrolled reader content. It exercises all four theme mappings with system/app modes
opposed and switches themes after composition; its 320dp fixtures prove colour, not full 412dp layout parity.

- `MarkdownReaderLoadTest` (`app/src/test/…/thread/`, 8 tests): `isMarkdownAttachmentName` across case and
  near-miss names (`notes.txt`, `notes.md.txt`, bare `md`, `null`); `decodeUtf8Strictly` round-trips valid
  multi-byte text and refuses a lone continuation byte, a truncated sequence and an overlong encoding;
  `readMarkdownAttachment` against a fake repository and a temp file — success, `NotFound`/`Unavailable`,
  invalid UTF-8, a file over the bound, a file exactly at the bound, a throwing repository, a missing file;
  `MarkdownDocument.toString()` prints no name or text.
- `ThreadViewModelAttachmentRetrievalTest` gained 4 tests for `onOpenMarkdownAttachment`: success emits
  `ThreadNavigation.OpenMarkdown(id)` and no failure signal; bad UTF-8 and a retrieval failure each emit one
  `markdownOpenFailures` and no navigation; a second tap while the first read is in flight is ignored; the log
  line carries the id and a static outcome only.
- `MarkdownReaderScreenTest` (`app/src/sharedTest/…/thread/`, Robolectric, 4 tests): the file name is in the
  top bar and rendered content (a heading, a list item) is below it; the back arrow calls `onBack`; long
  content scrolls to its last paragraph while the name and back arrow stay displayed; the tap-routing case
  added during implementation — a markdown name from both `Kept` and `Original` sources opens the reader, a
  non-markdown name still goes through `openAttachment`, and a non-`Ready` row does nothing. This last case
  covers `rememberAttachmentActions`'s branch directly rather than relying on `isMarkdownAttachmentName`
  alone, since the plan's original split put it there.
- No rung-3 real-Claude scenario. The tap uses the same `retrieveAttachment` path the live
  `interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart` scenario (#1016) already proves; what this
  ticket adds — strict decoding, the byte bound, the reader UI — happens after retrieval and is entirely
  local, covered by the Robolectric tests above. The existing live fixtures all use `.txt` names, so the new
  markdown branch does not reroute them.

\#1050's linked-note path adds its own tests, on the same shapes: `MarkdownReaderLoadTest` gained
`readLinkedMarkdown` cases (name from the last path component, text unchanged, every failure kind collapsing
to `null`, exactly-at-bound succeeding); `MarkdownLinkRoutingTest` (`app/src/test/…/components/`) covers
`markdownLinkPath` and `routeMarkdownLink` classification (see
[MarkdownText § Markdown-path links](markdown-text-internals.md#markdown-path-links-since-1050)); `ThreadViewModelMarkdownLinkTest`
covers one read per open, the shared in-flight guard, a reopen after release re-fetching and showing new
content, one failure signal with nothing held, and that logs carry neither path nor text;
`MarkdownLinkTapTest` (`app/src/sharedTest/…/components/`, Robolectric) covers a tap in both a finished and a
streaming assistant `MessageBubble`, an `https` link still reaching the platform handler, a `MarkdownText`
with no callback leaving a markdown-path tap inert, and `LinkedMarkdownReaderDestination` with a document and
with `null`. Rung 3: `InteractiveStreamE2ETest#interactiveTurn_markdownLink_opensLiveNoteInReader`, extended by
\#1067 below — see [Interactive stream e2e](../../e2e-interactive-stream.md).

\#1067's [copy and refresh menu](markdown-reader-screen.md#copy-and-refresh-menu-since-1067) adds: `MarkdownConversionsTest`
(`app/src/test/…/components/`, pure JVM, 11 tests) — `markdownPlainText` drops every syntax kind the AC lists
and keeps code and table content verbatim; `markdownHtml` escapes a raw `<script>` block and an inline
`onerror` attribute as text, keeps `href` for `http`/`https`/`mailto` inline and reference links, writes text
only for `javascript:`, an entity-encoded `javascript:`, `file:`, `data:`, `intent:` and a relative path, and
neither a quote inside an allowed href nor a hostile fence info string can open a tag; images come out as alt
text with no `<img`; bounds hold at `MAX_MARKDOWN_READER_BYTES`, with `boundClipHtml` always ending on `>`
inside the limit. `MarkdownReaderScreenTest` grew from 4 to 15 tests: the overflow button opens the four items
in order; each copy puts the expected clip on the system `ClipboardManager` (the HTML clip carries both
`htmlText` and its `text` fallback, MIME `text/html`) with no snackbar shown; a copy at
`MAX_MARKDOWN_READER_BYTES` does not throw; `RefreshableMarkdownReader` with a controllable `reread` covers a
successful refresh replacing the content, a second refresh while the first is still suspended calling `reread`
once, a failed refresh keeping the old content and showing "Couldn't open file", and the retry-while-the-notice-
shows regression test above. `ThreadViewModelMarkdownLinkTest` gained the assertion that the held note carries
the path it was read from, alongside the existing name/text checks.
`InteractiveStreamE2ETest#interactiveTurn_markdownLink_opensLiveNoteInReader` grew a third step: with the reader
still open after the first tap, the peer (not the phone) has claude rewrite the note, then the test chooses
Refresh from the reader's own overflow and asserts the new heading is shown with the old one and "Couldn't open
file" both absent, before backing out and re-opening through the link as #1050 already did. The scenario still
spends two real-claude turns — the rewrite prompt moved from the phone's composer to the peer's — so it adds no
turn to the suite's running total; see [Interactive stream e2e § Follow-ups to
ticket](../../e2e-interactive-stream.md#follow-ups-to-ticket) for the live-run evidence.

\#1068's [open in another app](markdown-reader-menu.md#open-in-another-app-since-1068) adds: `SharedNoteFileTest` (`app/src/test/…/thread/`,
pure JVM, `TemporaryFolder`, 8 tests) — `sharedNoteFileName` takes the last path component for both `/` and
`\`, strips control characters, fits the 255-UTF-8-byte limit, and falls back to `note.md` for an empty, `.`,
`..` or trailing-separator name; `sharedNoteDirectory`'s name is never a 64-hex-character host directory;
`writeSharedNote` keeps the exact UTF-8 bytes under the resolved name, a second write leaves only the second
note's file, a name that would climb out of the directory still resolves inside it, and a directory that cannot be
created (a regular file already sitting at that path) writes nothing. `AttachmentActionsTest` gained 3 Robolectric tests:
the shared-note file is served by the attachment provider with the note's name as its last path segment; with
a markdown viewer registered, `openNoteInAnotherApp` starts an `ACTION_CHOOSER` whose `EXTRA_INTENT` is
`ACTION_VIEW` + `text/markdown` + a `content` URI of the attachments authority with flags exactly the read
grant, and whose own chooser flags carry none of the write/persistable/prefix bits; with no viewer registered,
`NO_APP` and nothing started; with the shared-note directory blocked by a file in its place, `OPEN_FAILED` and
nothing started. `MarkdownReaderScreenTest` grew by 2 tests and one `@Before`: the reset of `FileProvider`'s
cached authority roots the [lesson above](markdown-reader-menu.md#open-in-another-app-since-1068) needed; the overflow's five items in
order (was four); choosing Open in another app with no registered viewer shows "No app can open this file"
while the rendered content stays displayed underneath. No rung-3 scenario — the hand-off ends in another app's
chooser, which the harness cannot drive, and the daemon is not involved; the read that produces the text is
already proven live by the scenarios above.

\#1069's [Save to device](markdown-reader-menu.md#save-to-device-since-1069) adds: `AttachmentActionsTest` gained 3 pure-JVM tests for
`saveNoteText` — the exact UTF-8 bytes of a string with multi-byte characters, with nothing discarded; a failing
output stream returns `false` and discards; `null` text returns `false`, discards and never opens the output.
`MarkdownReaderScreenTest` grew by 4 tests: the overflow's six items in order (was five); choosing Save to
device launches `ACTION_CREATE_DOCUMENT` of type `text/markdown` with `EXTRA_TITLE` set to the note's last path
component; a picked `file://` destination receives exactly the text on screen and the reader shows "File saved";
a destination whose parent directory does not exist shows "Couldn't save file" and leaves no file behind; a
cancelled picker shows neither notice. The lost-pending-text-after-recreation path is proven only by the
`saveNoteText(null, …)` unit test — recreating the process mid-picker is not driven in either test tier. No
rung-3 scenario: the save ends in the system's document picker, which the harness cannot drive, and the daemon
is not involved, the same call #1068 made.
