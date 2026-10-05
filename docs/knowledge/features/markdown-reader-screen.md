# Markdown reader screen

In-app reader for ready `.md`/`.markdown` attachments (#1027) and live workspace-note links
from assistant replies (#1050). Other attachments use
[`openAttachment`](message-bubble-attachment-slot.md#open-and-save-since-985).
The `markdown_reader/{serverId}/{conversationId}/{attachmentId}` route, copy/refresh
[menu](markdown-reader-menu.md) actions live in
`de.pyryco.mobile.ui.conversations.thread.MarkdownReaderScreen.kt`.

## What it does

The fixed top bar uses the Figma-matching `ic_thread_back` and `ic_thread_overflow` vectors in
48dp touch areas, a single-line ellipsised file name in `titleLarge` / `onPrimaryContainer`,
and a 60%-alpha inset rule. The title is inert and overflow keeps its own menu anchor.
The body and fixed bar are sibling layers in a `Box`: a full-screen-area `verticalScroll`
column supplies the Haze source, and the full-width bar samples it through shared
`chromeBackdrop` gradient/progressive blur. The title/glyph row uses `defaultChromeShadow`
once, keeping foreground shapes sharp while text scrolls behind them. The bar owns pointer
hits across its controls, title and blank background, preventing links or code panels beneath
it from activating.

The screen area begins below the system status bar. At rest, the reference title starts
24dp from that area's top, the bar through its rule ends at 69dp, and the first heading starts
at 97dp. [`MarkdownText`](markdown-text.md#public-surface) reserves the bar's actual measured
height plus 28dp as scrollable top padding, so enlarged text retains the rule-to-heading gap.
The reader removes `BarBottomGap`; counting it again would enlarge that gap. Body gutters
remain 20dp and bottom padding 16dp, with the final block reachable at the scroll end.
Both attachment and linked-note readers select M3 `bodyLarge` (16sp/24sp), 12dp block gaps
and 6dp sibling-list-item gaps. Reader headings and prose use untrimmed line-height boxes; the
thread keeps its existing metrics. Unlabelled and indented code uses a plain, tappable,
horizontally scrolling panel; labelled fences keep syntax highlighting and visible per-block copy.
Reader quotes use regular `bodyLarge` text and a subdued 3dp rule. See
[MarkdownText block dispatch](markdown-text-internals.md#block-dispatch). Reader menu copies and link routing
are unchanged.

**List items follow `553:2574`'s in-paragraph marker (#1533).** A non-task reader item whose first
block is a paragraph draws its marker and text as one `Text`, so a wrapped continuation line returns
to the list's own left edge rather than hanging under the item text — the frame writes each item as
`•  text` in one paragraph, not a marker beside a text column. Pinned by
`MarkdownReaderDesignTest.wrappedListItemContinuesAtTheGutter`. A nested block under an item still
indents at a fixed offset since the frame has no nested case to measure; see
[MarkdownText § Block dispatch](markdown-text-internals.md#block-dispatch) for the full rule, including why
ordered items and task items differ. Because the marker is now part of the item's text node,
`MarkdownTypographyTest`'s reader list assertions match item text with `substring = true` and count
`•  ` / `N.  `-prefixed nodes rather than standalone marker nodes; the thread cases still assert
standalone markers.

The [side-by-side](../../../app/src/androidTest/assets/markdown-reader-1291/side-by-side.png) and
[labelled overlay/difference](../../../app/src/androidTest/assets/markdown-reader-1291/overlay-difference.png)
compare matching Markdown with Figma node `553:2574`, inspected 2026-09-30, at 412 × 892.
Shared Roboto metrics wrap one date differently but keep the same three-line paragraph height and
following block positions. Figma has no menu, compact or enlarged-text state;
[comparison notes](../../../app/src/androidTest/assets/markdown-reader-1291/comparison-notes.txt)
record Pixel 8 checks at 320 × 700 with 1.5× text, including menu and scroll reachability.

The back arrow and system back return to the same thread. An unreadable or invalid UTF-8 file leaves
the operator on the thread with `AttachmentNotice.OPEN_FAILED` (see [Load and navigate from the
thread](#load-and-navigate-from-the-thread)). A failed Refresh retains the open document and shows the
same notice inside the reader; see [Copy and refresh menu](#copy-and-refresh-menu-since-1067).

Under the app root's static dark palette, the full-size reader `Surface` uses
`#0B0E11` (30% black over `#101418`) for blank space below short notes or around scrolling
content. The header overlays the canvas/content with the resolved `ThreadColors.headerBackdrop`
gradient and progressive blur rather than a flat canvas fill. The existing 1dp rule, inset 20dp, uses `inversePrimary`
(`#32628D`) at 60% alpha. Explicit static light and wallpaper light/dark variants in isolated tests retain
the `surface` canvas and an `outlineVariant` rule at 60%. This is the shared screen-local `ThreadColors` mapping from `PyrycodeMobileTheme`, following
the app's resolved mode even when it differs from the system; global Material roles stay unchanged.
See [thread canvas and header](thread-screen-how-it-works-overlays-and-app-bar.md#threadtopappbar--figma-168-chrome)
and the [palette plan](../../specs/architecture/1162-thread-reader-canvas.md).

Reader notices currently remain bottom snackbars. When #1604 introduces the top-overlay
Error pill from frame `696:5101`, its placement must be rechecked at 28dp below the actual
measured bar-through-rule height. That notice migration and capture are not proven by the
chrome evidence here.

## Routing a tap to the reader

`isMarkdownAttachmentName(name: String?)` (in this file, not `AttachmentActions.kt` — it lives beside the
other reader helpers and `AttachmentActions.kt` calls it) is true when the name ends in `.md` or `.markdown`,
ignoring case, and false for `null`. `rememberAttachmentActions` (`AttachmentActions.kt`) gained a required
`onOpenMarkdown: (attachmentId: String) -> Unit` parameter: in `open`, a `Ready` target whose
`displayName` passes the check calls `onOpenMarkdown(id)` and nothing else, for **both**
`AttachmentSource.Kept` and `AttachmentSource.Original` — the reader never reads the tapped row's source, only
its id, so an operator's own attached markdown file (shown from its original picked URI) and one the assistant
sent are opened identically. Every other ready target keeps the existing `openAttachment` → view-intent path.
See [MessageBubble — attachment slot § Open and save](message-bubble-attachment-slot.md#open-and-save-since-985)
for the click wiring this plugs into.

## Load and navigate from the thread

The ticket's "stay on the thread on failure" criterion is met by reading and decoding the file **before**
navigating, on the thread side:

- `ThreadViewModel.onOpenMarkdownAttachment(attachmentId)` is a no-op while a previous open is still in
  flight (`markdownOpenJob?.isActive`) — without the guard, a double tap would buffer a second navigation
  that fires when the operator returns from the first. Otherwise it launches in `viewModelScope`:
  `readMarkdownAttachment(repository, conversationId, attachmentId, ioDispatcher)` (`ioDispatcher` is a
  trailing constructor parameter defaulting to `Dispatchers.IO`, so no call site needed to change). A
  document sends `ThreadNavigation.OpenMarkdown(attachmentId)` on the existing `navigationChannel`; `null`
  sends on `markdownOpenFailures: Flow<Unit>` (a buffered `Channel`, the same one-shot idiom as
  `archiveErrors`). Logs `event=thread_attachment_open id=<id> outcome=reader|failed` only — never a name,
  path or content.
- `MainActivity`'s thread destination routes `ThreadNavigation.OpenMarkdown` to
  `navController.navigate(Routes.markdownReader(target, event.attachmentId))`, beside the existing
  `PopBack → popBackStack()` arm.
- `ThreadScreen` gained `onOpenMarkdownAttachment: (String) -> Unit = {}` (passed into
  `rememberAttachmentActions`) and `markdownOpenFailures: Flow<Unit> = emptyFlow()` (shown as
  `AttachmentNotice.OPEN_FAILED`'s string via `snackbarHostState`, the same `LaunchedEffect(flow,
  snackbarHostState)` shape `archiveErrors` uses).

`readMarkdownAttachment(repository, conversationId, attachmentId, ioDispatcher)` is the one loader both the
thread and the reader call:

1. `repository.retrieveAttachment(conversationId, attachmentId)` — anything but
   `AttachmentRetrievalResult.Retrieved` (see [Attachment retrieval](attachment-retrieval.md)) is `null`.
2. On `ioDispatcher`, `readBoundedFile` reads at most `MAX_MARKDOWN_READER_BYTES + 1` bytes
   (`input.readNBytes(bound + 1)`) and returns `null` once the count exceeds the bound — the bound is
   enforced by how much is actually read, never by trusting the file's on-disk length.
3. `decodeUtf8Strictly` decodes with `CodingErrorAction.REPORT` on both malformed and unmappable input;
   any decoding error is `null`. It never substitutes U+FFFD for a bad byte, matching the acceptance
   criterion.
4. A `CancellationException` is rethrown; every other exception (including a repository that keeps no
   files) is swallowed to `null` — exception messages are never read, since they can carry a local path.
5. Success returns `MarkdownDocument(name = result.displayName, text)`, named by the retrieval's own
   sanitised `displayName`, never by the route or a file name derived from it.

**`MAX_MARKDOWN_READER_BYTES = 262_144` (256 KiB) is the reader's own bound, separate from retrieval's 23 MB
one.** `AttachmentRetrievalLimit.MAX_BYTES` (see [Attachment retrieval § The
bound](attachment-retrieval.md#the-bound--attachmentretrievallimit-512-chunks-23040000-bytes)) is a heap
budget for the fetch; `MarkdownText` parses the text during composition and lays every block out in one
non-lazy column, so a large `.md` well within the retrieval bound could still freeze the UI. 256 KiB is far
above any realistic workspace note.

**A run with no whitespace gets a line break every `MAX_UNBROKEN_RUN` (1024) characters in the reader's
drawn text (2026-10-05).** Android's text engine shapes a space-separated word at a time, and a word wider
than the line costs native memory far faster than linearly: on the test emulator a 64 KB word took about
220 MB and a 256 KB one got the process killed by the low-memory killer. A zero-width space or a slash does
not end the engine's word; a line break does. `withBreaksInLongRuns` applies it to the reader's paragraphs,
headings, list items, quotes and fallback blocks, keeping styles and links. Chat bubbles are untouched,
since their text is selectable and a copied selection would carry the breaks. Copies read the note, not
the drawn text. Code blocks do not wrap and are not changed.

`MarkdownDocument(name, text)` overrides `toString()` to print lengths only
(`MarkdownDocument(name=7, text=482)`), the same redaction discipline `AttachmentSource` and `Ready` use in
[MessageBubble — attachment slot](message-bubble-attachment-slot.md#view-state-keyed-by-attachment-id).

## The reader destination

`MarkdownReaderDestination(repository, conversationId, attachmentId, onBack, modifier)` re-reads the same
call — a `produceState<ReaderLoad>` keyed on all three identity params. Because it is the same kept file the
thread just decoded, this second read is local, not a fetch. Three states: `Loading` draws a bare `Surface`;
`Loaded(document)` renders [`RefreshableMarkdownReader`](#copy-and-refresh-menu-since-1067) with
`reread = { readMarkdownAttachment(repository, conversationId, attachmentId) }`; `Failed` — the file disappeared
between the thread's read and this one — calls `onBack()` once from a `LaunchedEffect(Unit)`, through
`rememberUpdatedState(onBack)` so a stale lambda from an earlier composition is never captured. This is how
"never shows empty or garbled content" holds even for the rare in-between-reads removal case; no test pins this
path specifically (verifier NIT on PR #1034 — a shared test with a failing fake repository would cover it
cheaply). The document is held in composition only, never `rememberSaveable`, so no file content ever enters
the saved-state bundle.

`MarkdownReaderScreen(document, onBack, modifier, onRefresh, snackbarHostState)` is the stateless render: a
`Surface` with explicit `contentColor = MaterialTheme.colorScheme.onSurface`, holding `MarkdownReaderTopBar`
then the scrolling body. The custom canvas is not a global Material role, so automatic content-colour lookup
cannot select its foreground; retaining `onSurface` explicitly keeps `MarkdownText`'s text colour intact. It has
a `SnackbarHost` docked to the bottom for a failed refresh. `onRefresh` and `snackbarHostState` both default to
inert values, so every existing caller and preview still compiles; `RefreshableMarkdownReader` is the one real
caller. `MarkdownReaderTopBar` reuses `ThreadTopAppBar`'s bar-metric constants (`BarGlyphSize`, `BarTouchSize`,
`BarTouchSlack`, `BarGutter`, `BarTopGap`, `BarRuleGap`, `BarBottomGap`, `BAR_RULE_ALPHA`) — promoted from
`private` to `internal` in `ThreadTopAppBar.kt` by #1027, visibility-only, so both bars share one set of numbers
rather than a second copy. Since #1067 the row's end padding is `BarGutter - BarTouchSlack`, matching
`ThreadTopAppBar`, because the row now ends in a 48dp touch target (the overflow button) instead of plain text.
[#1562](https://github.com/pyrycode/pyrycode-mobile/issues/1562) dropped `bottom = BarBottomGap` from the
thread bar's own rule, so this reader's bar is now `BarBottomGap`'s only user — its rule keeps the 16dp gap
underneath it unchanged.

## Route

`Routes.MARKDOWN_READER = "markdown_reader/{serverId}/{conversationId}/{attachmentId}"`. `Routes.markdownReader(target, attachmentId)`
encodes each of the three components independently with `Uri.encode`, the same per-component discipline
[`Routes.thread`](navigation.md#host-qualified-destinations) uses — never a whole encoded path, URI or file
name. `Routes.markdownReaderArguments() = hostArguments() + navArgument("attachmentId") { type =
NavType.StringType }`; `Routes.attachmentId(arguments)` reads it back. The destination is wrapped in
`HostDestination(target.serverId, …)`, the same guard `conversation_thread` uses: an unknown or removed host
returns to the channel list rather than resolving another host's repository. Inside the guard it resolves
`repository = destinations.repository(target.serverId)` — a fresh `CachingConversationRepository` wrapper,
exactly like [`ThreadDestinationFactory.repository`](attachment-retrieval.md#lessons-learned); per-host state
(the single-flight bookkeeping and the kept file itself) lives one level up in the app-singleton
`AttachmentStore`, so this second wrapper reaches the same kept file the thread already read. The
`navigate(...)` call carries no `launchSingleTop`.

## Linked note, live (since #1050)

A markdown-path link in an assistant reply — [`markdownLinkPath`](markdown-text-internals.md#markdown-path-links-since-1050),
e.g. `[Plan](notes/Plan.md)` — opens this same reader, but the note is **never a stored attachment**: it is
read live from the conversation's workspace on every tap, and nothing is kept between opens. The operator
decided (2026-09-24) that the reader always shows the file as it is on the host right now, so unlike the
attachment path above there is no local second read to reuse — the thread's one read *is* what the reader
shows.

- `internal suspend fun readLinkedMarkdown(repository, conversationId, path): MarkdownDocument?` — one
  `repository.readWorkspaceFile(conversationId, path)` (#1049). A `Fetched` result whose `content.size` is at
  most `MAX_MARKDOWN_READER_BYTES` is copied to a `ByteArray` and decoded with the same `decodeUtf8Strictly`
  the attachment path uses; anything else (`NotFound`, `Unavailable` — covering a refusal, an aborted or
  stalled stream, a dropped connection — `Invalid`, over the bound, bad UTF-8, a non-cancellation exception)
  is `null`. `path` reaches the repository exactly as the link wrote it — the phone never decodes, resolves or
  confines it; the daemon does.
- `internal fun linkedMarkdownName(path: String): String` — the top-bar name, since the assistant authored the
  link: the text after the last `/`, through `attachmentDisplayName`, the same sanitiser a retrieved
  attachment's name goes through. The bar shows the path actually read, never the link's own display text, so
  `[Plan](secrets.md)` cannot make the operator think a different file opened.
- `ThreadViewModel.onOpenMarkdownLink(path)` shares the attachment path's `markdownOpenJob` guard — one open
  in flight blocks a second tap, whether it is another link or the attachment flow, and either one clears
  before a new job starts. Success stores `path` and the document together as `linkedMarkdownNote:
  LinkedMarkdown?` (a plain `var`, not a `StateFlow`; **since #1067** — before that it held only the
  `MarkdownDocument`) and sends `ThreadNavigation.OpenLinkedMarkdown` (a `data object`; the note itself never
  travels in the event); failure sends on the shared `markdownOpenFailures`, so a failed link tap shows the
  same "Couldn't open file" snackbar the attachment path uses. `linkedMarkdown(): LinkedMarkdown?` reads the
  held note once; `releaseLinkedMarkdown()` drops it. Logs `event=thread_markdown_link_open outcome=reader|failed`
  only — no path, name or text, ever.
- `class LinkedMarkdown(val path: String, val document: MarkdownDocument)` (in `MarkdownReaderScreen.kt`, #1067)
  is the thread's hand-off: the [reader's Refresh](#copy-and-refresh-menu-since-1067) needs the path the
  document was read from, and the path lived only in the open call's stack frame before this. `toString()`
  prints lengths only, the same discipline `MarkdownDocument` uses.
- `@Composable fun LinkedMarkdownReaderDestination(note: LinkedMarkdown?, reread: suspend (path: String) ->
  MarkdownDocument?, onBack, modifier)` draws [`RefreshableMarkdownReader`](#copy-and-refresh-menu-since-1067)
  with `initial = note.document` and `reread = { reread(note.path) }` for a non-null `note`; `null` — the
  process was restored with this destination on top, or it was reached with nothing held — draws a bare
  `Surface` and calls `onBack()` once, the same shape `MarkdownReaderDestination`'s `Failed` case uses. Before
  #1067 this composable took a bare `document: MarkdownDocument?` and drew `MarkdownReaderScreen` directly, with
  no way to refresh.
- Route: `Routes.MARKDOWN_LINK = "markdown_link/{serverId}/{conversationId}"`, ids only — no path, no
  attachment id. The path never needs to travel: the document and the path it came from already live in the
  thread's `ThreadViewModel`. `MainActivity`'s `HostDestination` block for this route calls
  `navController.getBackStackEntry(Routes.CONVERSATION_THREAD)` to find the thread beneath (`null` if it is
  gone), resolves that same `ThreadViewModel` with `koinViewModel(viewModelStoreOwner = threadEntry)`, and
  `remember(backStackEntry) { threadVm?.linkedMarkdown() }`s the note **once** rather than collecting it live.
  Since #1067 it also `remember(target.serverId) { destinations.repository(target.serverId) }`s the same host
  repository the attachment route resolves, and passes
  `reread = { path -> readLinkedMarkdown(repository, target.conversationId, path) }` — so Refresh on a linked
  note reaches the daemon through this host, not through the thread's own `ThreadViewModel`.

  **Why `remember` once, not a live read.** The thread destination clears its copy
  (`vm.releaseLinkedMarkdown()`) from a `LaunchedEffect(vm)` that fires whenever it recomposes — including
  during the pop transition back from this reader, while the reader is still on screen. A live read of
  `linkedMarkdown()` at that moment would see `null` and pop a second time, which would close the thread
  underneath it too. Reading it once when the destination first composes avoids that; it also means Refresh's
  `reread` closure captures `note.path` directly rather than calling back into the ViewModel, so a later
  `releaseLinkedMarkdown()` cannot affect a reader already open.
- Root cause of the SHOULD FIX the #1050 verifier left open (PR #1062, non-blocking): the release runs on
  thread *re-entry*, not on the reader's own exit. If a link's read finishes while the operator is on a screen
  pushed above the thread (Settings, say) and the operator returns before opening the reader, the effect can
  clear `linkedMarkdownNote` before the buffered `OpenLinkedMarkdown` navigation is acted on, and the reader
  mounts with `null` — a blank-surface flash and an immediate pop, with no "Couldn't open file" notice.
  Narrow (needs a completed background read plus a return to the thread before the navigation fires) and not
  fixed as of #1067; a `DisposableEffect` releasing on the *reader's* exit, or folding the document into the
  `OpenLinkedMarkdown` event itself, would close it.

## Copy and refresh menu (since #1067)

The top bar's overflow menu — copy as markdown/plain text/HTML, Refresh, Open in another app (since #1068) and Save to device (since #1069), plus their logging — moved to [Markdown reader menu](markdown-reader-menu.md) under the docs guard's size cap (#1533).

## Testing

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

\#1067's [copy and refresh menu](#copy-and-refresh-menu-since-1067) adds: `MarkdownConversionsTest`
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

## Related

- [Markdown reader menu](markdown-reader-menu.md) — copy as markdown/plain text/HTML, Refresh, Open in another app
  and Save to device, and their logging; split out under the docs guard's size cap (#1533).
- [MessageBubble — attachment slot § Open and save](message-bubble-attachment-slot.md#open-and-save-since-985) —
  the `Ready`-row tap this screen is one branch of; `AttachmentActions.kt`'s `rememberAttachmentActions` and
  `openAttachment`, the read-only single-URI-grant pattern [`openNoteInAnotherApp`](markdown-reader-menu.md#open-in-another-app-since-1068)
  mirrors for a note instead of a kept attachment file.
- [Attachment retrieval](attachment-retrieval.md) — `ConversationRepository.retrieveAttachment`, the
  host-keyed `AttachmentStore` this reader's two reads (thread, then reader) both resolve through, and why the
  retrieval bound and this reader's own 256 KiB bound are separate numbers for separate reasons.
- [Navigation § Host-qualified destinations](navigation.md#host-qualified-destinations) — the per-component
  `Uri.encode` discipline and the `HostDestination` guard this route follows.
- [Thread screen](thread-screen.md) — `ThreadNavigation`, the one-shot `navigationChannel` this ticket's
  `OpenMarkdown` case rides, and where `onOpenMarkdownAttachment` / `markdownOpenFailures` are wired into
  `ThreadScreen`.
- [MarkdownText § Markdown-path links](markdown-text-internals.md#markdown-path-links-since-1050) — `markdownLinkPath`,
  `routeMarkdownLink`, and the `onOpenMarkdownPath` opt-in this screen's linked-note path is reached through;
  following a link inside an *open* note (attachment or linked) is still a later ticket, unaffected by #1050.
- [MarkdownText § Inline dispatch](markdown-text-internals.md#inline-dispatch) and
  [§ Link safety](markdown-text-internals.md#link-safety--scheme-allowlist) — `inlineText`, `MarkdownFlavour` and
  `isSafeLinkScheme`, all `internal` since #1067 so the copy-and-refresh menu's conversions share the renderer's
  own parse and allowlist rather than a second copy.
- [Thread overflow menu](thread-overflow-menu.md) — `ThreadOverflowMenu`, the shared Below Actions overlay and
  dismiss-then-act shape also used by [the reader's menu](#copy-and-refresh-menu-since-1067),
  per the operator's 2026-09-24 decision against a second dropdown style.
- [MessageMetaRow § Meta row and copy control](message-bubble.md#meta-row-and-copy-control-messagemetarowkt-since-644) —
  `CopyTextControl` and `MAX_CLIPBOARD_CHARS` (now `internal`, shared with this reader's copies), and the
  "system confirms the copy, we don't" precedent this menu's copy items follow.
- Ticket: `docs/specs/architecture/1027-markdown-reader.md` — design, the security review (the 256 KiB bound
  closes a composition-cost DoS the retrieval bound alone would not), and the implementation revisions (the
  `Surface`-over-`Column` choice, the `modifier` parameter, where `isMarkdownAttachmentName` ended up, and the
  tap-routing screen test).
- Ticket: `docs/specs/architecture/1050-markdown-link-live-reader.md` — design for the linked-note path (why
  the route carries ids only rather than the path), the security review, and the Revisions entry resolving
  `performFirstLinkClick`'s availability at BOM `2026.02.01`.
- Ticket: `docs/specs/architecture/1049-read-workspace-file.md` — `ConversationRepository.readWorkspaceFile`,
  the live read `readLinkedMarkdown` calls.
- Ticket: `docs/specs/architecture/1067-markdown-reader-menu.md` — design for the copy-and-refresh menu, the
  security review of the HTML-escaping/allowlist boundary, and the Revisions entries (the `reread`-parameter
  change, the fence provider always replaced, `&` left encoded in a link href, the quote-continuation fix, the
  `chars=` log field, the bar padding, and the two rework-round fixes: the live Refresh proof and the
  retry-while-the-notice-shows fix).
- Ticket: `docs/specs/architecture/1068-markdown-reader-open-in-app.md` — design for
  [open in another app](markdown-reader-menu.md#open-in-another-app-since-1068), the security review (the accepted stale-grant
  gap, the `shared-note` directory's non-collision with `AttachmentStore`'s host directories), and the
  Revisions entry (the `FileProvider.sCache` reset `MarkdownReaderScreenTest` needed once `AttachmentActionsTest`
  ran first in the same JVM, and resolving notice strings with `stringResource` ahead of the coroutine).
- Ticket: `docs/specs/architecture/1533-reader-list-wrap-to-gutter.md` — design for the reader's
  in-paragraph list marker fixing a wrapped continuation line's left edge to match Figma `553:2574`.
- Ticket: `docs/specs/architecture/1069-markdown-reader-save-to-device.md` — design for
  [Save to device](markdown-reader-menu.md#save-to-device-since-1069), the security review (no findings; the app never builds a path
  and gets no persistable or tree grant, and lost pending text after recreation is addressed by design), and the
  Revisions entry (the screen test's `file://`-URI provider workaround once `sharedTest` compilation into the
  device set ruled out a Robolectric-only fake content provider).
