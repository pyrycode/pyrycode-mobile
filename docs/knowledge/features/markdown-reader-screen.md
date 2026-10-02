# Markdown reader screen

In-app reader for ready `.md`/`.markdown` attachments (#1027) and live workspace-note links
from assistant replies (#1050). Other attachments use
[`openAttachment`](message-bubble-attachment-slot.md#open-and-save-since-985).
The `markdown_reader/{serverId}/{conversationId}/{attachmentId}` route, copy/refresh
[menu](#copy-and-refresh-menu-since-1067), [open](#open-in-another-app-since-1068) and
[save](#save-to-device-since-1069) actions live in
`de.pyryco.mobile.ui.conversations.thread.MarkdownReaderScreen.kt`.

## What it does

The fixed top bar uses the Figma-matching `ic_thread_back` and `ic_thread_overflow` vectors in
48dp touch areas, a single-line ellipsised file name in `titleLarge` / `onPrimaryContainer`,
and a 60%-alpha inset rule. Its reader-only top gap puts the scrolling body at y=97dp in
the 412 × 892 dark reference; the title is not tappable.
The body uses [`MarkdownText`](markdown-text.md#public-surface) in a `weight(1f)` `verticalScroll` column.
Both attachment and linked-note readers select M3 `bodyLarge` (16sp/24sp), 12dp block gaps
and 6dp sibling-list-item gaps. Reader headings and prose use untrimmed line-height boxes; the
thread keeps its existing metrics. Unlabelled and indented code uses a plain, tappable,
horizontally scrolling panel; labelled fences keep syntax highlighting and visible per-block copy.
Reader quotes use regular `bodyLarge` text and a subdued 3dp rule. See
[MarkdownText block dispatch](markdown-text.md#block-dispatch). Reader menu copies and link routing
are unchanged.

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
`#0B0E11` (30% black over `#101418`), including the transparent header's background and blank space
below short notes or around scrolling content. The existing 1dp rule, inset 20dp, uses `inversePrimary`
(`#32628D`) at 60% alpha. Explicit static light and wallpaper light/dark variants in isolated tests retain
the `surface` canvas and an `outlineVariant` rule at 60%. This is the shared screen-local `ThreadColors` mapping from `PyrycodeMobileTheme`, following
the app's resolved mode even when it differs from the system; global Material roles stay unchanged.
See [thread canvas and header](thread-screen-how-it-works-overlays-and-app-bar.md#threadtopappbar--figma-168-chrome)
and the [palette plan](../../specs/architecture/1162-thread-reader-canvas.md).

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

A markdown-path link in an assistant reply — [`markdownLinkPath`](markdown-text.md#markdown-path-links-since-1050),
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

The top bar's overflow button (`Icons.Filled.MoreVert`, `cd_more_actions`, the same 48dp touch target and bar
metrics as `ThreadTopAppBar`'s own) opens a plain M3 `DropdownMenu` built the way `ThreadOverflowMenu` builds
the thread's menu — the operator decided (2026-09-24) it reuses that dropdown rather than a second style.
Figma: `Options overlay` (`533:1958`). Six items, each dismissing the menu before it acts: **Copy as
markdown**, **Copy as plain text**, **Copy as HTML**, **Refresh**, **Open in another app** (since #1068,
[below](#open-in-another-app-since-1068)), **Save to device** (since #1069,
[below](#save-to-device-since-1069)). The menu shows only once the reader has content — the bar draws
nothing until the first read finishes, and a failed first read never opens the reader at all (see [What it
does](#what-it-does)).

### Copy

All three copy formats act on the `document` currently on screen — after a Refresh, on the refreshed one — and
go out through `LocalClipboardManager.setClip(ClipEntry(clipData))`. None shows a snackbar of its own: every
supported device (min SDK 33) already confirms a copy at the platform level, the same precedent
[`MessageMetaRow`'s `CopyTextControl`](message-bubble.md#meta-row-and-copy-control-messagemetarowkt-since-644)
set. The clip label is the static string `markdown_reader_clip_label`, never the note's name.

- **Copy as markdown** — `ClipData.newPlainText(label, boundClipText(document.text))`: the raw file text,
  bounded.
- **Copy as plain text** — `ClipData.newPlainText(label, boundClipText(markdownPlainText(document.text)))`.
- **Copy as HTML** — one `ClipData.newHtmlText(label, plainTextFallback, html)`: a single clip item carrying
  both the generated HTML and the plain-text form as its fallback, each independently bounded.

`markdownPlainText` and `markdownHtml` are new pure functions in
`ui/conversations/components/MarkdownConversions.kt`, no Compose runtime, no Android types — parsed with the
same `MarkdownFlavour` (GFM) [`MarkdownText`](markdown-text.md) uses, so the copies match what the screen
actually renders rather than a second, potentially-drifting notion of "this note's markdown":

- **Plain text** walks the AST directly for block structure (headings — all six ATX levels, even though the
  renderer shows `####`+ as raw source, because the AC promises no heading markers at any level; lists, one
  line per item with no bullet/number/task box, nested lists indented two spaces per level; block quotes, no
  `>`; fenced/indented code kept verbatim via the renderer's own `fencedCodeText`/`indentedCodeText`; tables,
  header then body rows tab-separated, within the renderer's `MAX_TABLE_ROWS`/`MAX_TABLE_COLUMNS` bounds) but
  defers every inline span — paragraph text, heading content, table cells — to
  [`inlineText`](markdown-text.md#inline-dispatch), the renderer's own inline walk exposed for this purpose.
  That is what makes "the rendered text" true by construction: emphasis/code/strike delimiters and link targets
  drop exactly as the screen hides them. **Lesson from implementation:** a block quote's continuation `>` and
  the space after it sit inside the *paragraph* node, not the quote's own children — a plain-text extractor
  that walks only the paragraph keeps them, rendering a two-line quote as `words  and more` with a double
  space; `blockText`'s `PARAGRAPH` arm filters both out explicitly.
- **HTML** runs the same parse through `org.intellij.markdown`'s `HtmlGenerator` with the flavour's own
  provider map, then overrides the providers that would otherwise put note-authored text somewhere unsafe. This
  is the one new place note text leaves the app (another app renders the pasted HTML), so every override
  matters for the security boundary, not just fidelity:
  - **Raw HTML is text.** The HTML-block and inline-HTML-tag providers are replaced with one that
    HTML-escapes the node's source and writes it as a `<p>` (block) or inline text.
  - **One href allowlist for every link-producing node** — inline links, reference links, `<autolink>` and a
    GFM bare URL all resolve through [`isSafeLinkScheme`](markdown-text.md#link-safety--scheme-allowlist)
    (`http`/`https`/`mailto`); anything else writes the link's text alone, with no `href`. Titles are dropped.
    **Lesson:** `LinkGeneratingProvider.RenderInfo.destination` arrives already entity-decoded by the library,
    so escaping its `&` again turned `?b=1&c=2` into `&amp;amp;`; the href is escaped for `"`/`'`/`<`/`>` only,
    leaving `&` alone, and the scheme check runs on that decoded value so `java&#115;cript:` is judged as
    `javascript:`. The autolink provider reads *raw* source instead, so it escapes `&` as well as the other
    four characters.
  - **Images become alt text only** — no `<img>` ever reaches the clip, so a remote `src` can never fetch
    anything on the note author's behalf.
  - **The code-fence provider is always replaced**, not conditionally — `<pre><code>` with the code escaped, no
    `class` attribute — so a fence info string can never reach an attribute regardless of what the library
    itself would have escaped. A revision during implementation dropped the earlier "replace it only if the
    library doesn't already escape it" plan in favour of always replacing it, since that removes any dependence
    on the library's own behaviour.
  - No other attribute in the generated HTML carries note-authored text.
- **`internal fun boundClipText(text)`** — `text.take(MAX_CLIPBOARD_CHARS)`. **`internal fun
  boundClipHtml(html)`** — unchanged within the bound; otherwise cut after the last `>` inside it, so the cut
  never lands inside a tag or an entity. `MAX_CLIPBOARD_CHARS` moved from `private` in `MessageMetaRow.kt` to
  `internal` (visibility only) so both copy paths share the one number — a parcelled clip has a Binder ceiling
  near 1 MB, and a note at the reader's own `MAX_MARKDOWN_READER_BYTES` (256 KiB) must not crash a copy.

### Refresh

`@Composable fun RefreshableMarkdownReader(initial: MarkdownDocument, reread: suspend () -> MarkdownDocument?,
onBack, modifier)` owns the refresh and is the one real caller of `MarkdownReaderScreen`. `document` is
`remember(initial) { mutableStateOf(initial) }`, so it survives a Refresh but not a new attachment/link
identity. A tap holds a `Job` in `remember`; if it is still active, a second tap does nothing — the read
already in flight is the only one that can complete. A non-null result from `reread()` replaces `document` in
place, keeping the scroll position wherever `MarkdownText` happens to re-lay it out; `null` leaves `document`
untouched and shows `AttachmentNotice.OPEN_FAILED`'s "Couldn't open file" string in the reader's own
`SnackbarHostState` — the same string the thread's own failed-open snackbar uses, but scoped to the reader so
the operator doesn't have to leave it to see the failure. Leaving the reader cancels the scope and any read
still in flight.

**Lesson from the verifier's rework round:** the failure snackbar first ran *inside* the same `Job` as the
read, so `refreshJob?.isActive` stayed `true` for the ~4s the "Couldn't open file" snackbar was showing —
retrying, the natural next action after that notice, silently did nothing. The fix launches the snackbar in
its own `scope.launch { … }`, so the in-flight guard covers only the read; a retry while the notice is still
showing reads again. Pinned by `MarkdownReaderScreenTest.aRetry_whileTheFailureNoticeShows_readsAgain`.

### Open in another app (since #1068)

The menu's last item, `markdown_reader_open_in_app`, hands the `document` currently on screen — after a
Refresh, the refreshed one — to another app as `text/markdown`, through the system chooser, mirroring the
read-only single-URI grant [`openAttachment`](message-bubble-attachment-slot.md#open-and-save-since-985) gives
a stored attachment. A linked note (since #1050) has no file on disk at all, and a stored attachment's kept
file can be stale after a Refresh, so the text on screen is always written out fresh first rather than handing
on whatever file (if any) already exists.

- **`SharedNoteFile.kt`** (new, `ui/conversations/thread/`, pure JVM, no Compose or Android types):
  `sharedNoteDirectory(noBackupFilesDir)` is `<noBackupFilesDir>/attachments/shared-note` — a child of the same
  root [`AttachmentStore`](attachment-retrieval.md#host-store--datacacheattachmentstorekt) and the attachment
  `FileProvider` use, but named `shared-note`, which is not a 64-character hex digest. That is what keeps it
  safe from `AttachmentStore.removeHost`'s recursive per-host delete (`<root>/<sha256hex(serverId)>`), which
  can never collide with or reach a name outside that shape, and it never deletes anything but its own
  contents in return. `sharedNoteFileName(name)` takes the last component after the last `/` and `\` (a
  daemon-authored name — an attachment's `displayName` or a link's path text — can carry either separator),
  runs it through `attachmentDisplayName` (the same control/format-character strip and 255-UTF-8-byte bound
  attachment names already get, see [Attachment retrieval](attachment-retrieval.md)), and falls back to
  `note.md` for an empty, `.` or `..` result. `writeSharedNote(directory, name, text)` runs under one
  process-wide lock: creates the directory, deletes every entry already there (so at most one note's file
  exists at a time — an open never accumulates), resolves the name inside it, and refuses (`null`) unless the
  resolved file's canonical parent is still the directory's own canonical file — the same
  climb-back-out defence `writeSharedNote`'s own test (`aNameThatWouldClimbOut_staysInside`) pins, on top of
  `FileProvider`'s independent canonicalisation against `attachment_paths.xml`'s one root. Any exception is
  `null`, unread — an exception message can carry the path.
- **`openNoteInAnotherApp(context, document, chooserTitle, ioDispatcher)`** (`AttachmentActions.kt`): writes the
  note on `ioDispatcher` (`OPEN_FAILED` on `null`), resolves its content URI through the same
  `attachmentContentUri` an opened attachment uses (`OPEN_FAILED` on `null`), builds an `ACTION_VIEW` /
  `text/markdown` intent carrying exactly `FLAG_GRANT_READ_URI_PERMISSION` — never write, persistable or
  prefix — then checks `packageManager.queryIntentActivities` before starting anything: an empty result is
  `NO_APP`, because handing an unresolvable `ACTION_VIEW` to `Intent.createChooser` would show an empty chooser
  rather than throw. `startActivity(Intent.createChooser(view, chooserTitle))` follows; `createChooser` itself
  migrates only that read grant into the chooser's own `ClipData` for the picked target.
  `ActivityNotFoundException` is also `NO_APP`; any other exception is `OPEN_FAILED`; a started chooser is
  `null`. `AndroidManifest.xml` gained a `<queries>` element (`VIEW` + `content` scheme + `text/markdown` type)
  so the `queryIntentActivities` check sees installed viewers under Android 11+ package visibility — without
  it the query would under-report regardless of what is actually installed.
- **`MarkdownReaderScreen`** owns the action the same way it owns the copies: the tap captures the `document`
  it is currently drawing, launches in `rememberCoroutineScope()`, and a non-null notice shows in the reader's
  own `snackbarHostState` — `AttachmentNotice.NO_APP` ("No app can open this file") or the existing
  `OPEN_FAILED` ("Couldn't open file"), the same strings the thread's own attachment-open failures use.
  Notice strings are resolved with `stringResource` before the coroutine launches, not `Context.getString`
  inside it (lint `LocalContextGetResourceValueCall`).
- **Accepted stale-grant gap** (from the ticket's security review): the app the operator picked loses its read
  grant on the previous file the moment the *next* open deletes it, and it cannot resolve the new file unless
  the note's name is unchanged (a different URI). Recorded as accepted rather than fixed — one note stays on
  disk at a time is the design, and a grant to text the operator already handed the app for the same note is
  not a new exposure.

**Lesson from implementation:** `MarkdownReaderScreenTest` needed the same `FileProvider.sCache` reset
`AttachmentActionsTest` already used (see [Testing](#testing)) — without it, a shared-note file served in one
test could leave the provider's authority-to-root cache pointed at a data directory Robolectric had already
torn down for the next test in the same JVM, turning an expected `NO_APP` into `OPEN_FAILED`.

Both destinations wrap `RefreshableMarkdownReader` rather than `MarkdownReaderScreen` directly:
`MarkdownReaderDestination`'s `Loaded` arm passes `reread = { readMarkdownAttachment(repository,
conversationId, attachmentId) }` (see [The reader destination](#the-reader-destination));
`LinkedMarkdownReaderDestination` passes `reread = { reread(note.path) }`, where the outer `reread` parameter
is `MainActivity`'s `{ path -> readLinkedMarkdown(destinations.repository(target.serverId), target.conversationId,
path) }` (see [Linked note, live](#linked-note-live-since-1050)) — so a linked note's Refresh sends another
`read_workspace_file`, proven live by the extended `interactiveTurn_markdownLink_opensLiveNoteInReader`
scenario (see [Testing](#testing) and [Interactive stream e2e](../../e2e-interactive-stream.md)).

### Save to device (since #1069)

The menu's last item writes `document.text`, as it stood when the picker opened, into a document the operator
picks through the same system create-document picker the thread's own attachment save uses, then reports the
outcome with the existing `AttachmentNotice.SAVED` / `SAVE_FAILED` snackbar strings.

- **`saveNoteText(text, openOutput, discard)`** (`AttachmentActions.kt`): `null` `text` — the pending text was
  lost, see below — discards the document without ever calling `openOutput` and returns `false`; otherwise it
  runs `copyAttachment` with `text.toByteArray(Charsets.UTF_8).inputStream()` as the source, so a failed write
  discards the document exactly as an attachment save does. Blocking; the caller runs it on `Dispatchers.IO`.
  This is the one new pure function — everything else reuses `CreateAttachmentDocument` and `copyAttachment`
  unchanged.
- **`rememberNoteSaver(onNotice): (MarkdownDocument) -> Unit`** (`AttachmentActions.kt`) is the reader's save,
  bound to its composition: one `rememberLauncherForActivityResult(CreateAttachmentDocument())`, and a private
  `PendingNote` holder — a plain `var` in a `remember`, never `rememberSaveable` — that carries the note's text
  across the picker. The returned function stores `document.text` in the holder and launches
  `Request(suggestedName = sharedNoteFileName(document.name), mimeType = "text/markdown")`, reusing
  [`sharedNoteFileName`](#open-in-another-app-since-1068) for the same last-path-component-and-sanitise
  treatment Open in another app already gives the note's name. The result callback takes the holder's text and
  clears it immediately, before deciding anything else, so a later result can never see stale text from an
  earlier save (the same discipline `RefreshableMarkdownReader` uses for its own in-flight guard). A `null`
  destination (cancelled) writes nothing and notifies nothing. A picked destination writes via `saveNoteText` on
  `Dispatchers.IO` inside `rememberCoroutineScope()`, then notifies `SAVED` or `SAVE_FAILED`.
- **Text lost across process death.** The pending text lives only in the `remember`ed holder — never in saved
  state — because the ticket's technical note ruled out writing stale or wrong text after a restart. If the
  activity or process is recreated while the picker is open, `ActivityResultRegistry` still redelivers the
  picked URI to the re-registered launcher, but the holder is now empty: `saveNoteText(null, …)` deletes the
  created document and the reader shows `SAVE_FAILED` rather than writing anything else in its place.
- **`MarkdownReaderScreen`** wires `onSaveToDevice = { saveNote(document) }`, so like the copies and Open in
  another app it acts on the `document` currently drawn — the refreshed one after a Refresh — not on whatever
  was on screen when the menu opened.
- Logs (see [Logging](#logging) below) never carry the text, the name or the picked URI, matching every other
  action in this menu.

**Lesson from implementation (screen-test provider access).** `MarkdownReaderScreenTest` lives in
`app/src/sharedTest`, which compiles into both the JVM (Robolectric) and device test sets (see
[Development verification § Where a screen test goes](development-verification-gates.md#where-a-screen-test-goes)),
so it cannot call a Robolectric-only API such as `Robolectric.setupContentProvider` to stand in for the
document the picker returns — that would fail to compile for the device target. The test instead answers the
picker with a `file://` URI inside the app's cache directory: Robolectric 4.17's
`ContentResolver.openOutputStream(uri, "wt")` passes an unregistered URI straight to the real resolver, which
resolves a `file://` URI the same way a device does, so a missing parent directory makes the write fail exactly
as it would on a device. `DocumentsContract.deleteDocument` cannot reach a `file://` URI at all and throws,
which `saveNoteText`'s own `runCatching` swallows — so the discard call is proven only in the `saveNoteText`
unit tests (`AttachmentActionsTest`), and the failed-write screen test instead asserts the "Couldn't save file"
notice and that no file exists at the destination.

### Logging

`RelayLog.d` only, static fields, never the text, name, path or clip contents:
`event=markdown_reader_copy format=markdown|plain|html chars=<source length>`,
`event=markdown_reader_refresh outcome=loaded|failed`,
`event=markdown_reader_open_in_app outcome=opened|no_app|failed chars=<text length>` (since #1068), and (since
\#1069) `event=markdown_reader_save outcome=saved|failed|cancelled chars=<length, or -1 when lost>`.

## Testing

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
[MarkdownText § Markdown-path links](markdown-text.md#markdown-path-links-since-1050)); `ThreadViewModelMarkdownLinkTest`
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

\#1068's [open in another app](#open-in-another-app-since-1068) adds: `SharedNoteFileTest` (`app/src/test/…/thread/`,
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
cached authority roots the [lesson above](#open-in-another-app-since-1068) needed; the overflow's five items in
order (was four); choosing Open in another app with no registered viewer shows "No app can open this file"
while the rendered content stays displayed underneath. No rung-3 scenario — the hand-off ends in another app's
chooser, which the harness cannot drive, and the daemon is not involved; the read that produces the text is
already proven live by the scenarios above.

\#1069's [Save to device](#save-to-device-since-1069) adds: `AttachmentActionsTest` gained 3 pure-JVM tests for
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

- [MessageBubble — attachment slot § Open and save](message-bubble-attachment-slot.md#open-and-save-since-985) —
  the `Ready`-row tap this screen is one branch of; `AttachmentActions.kt`'s `rememberAttachmentActions` and
  `openAttachment`, the read-only single-URI-grant pattern [`openNoteInAnotherApp`](#open-in-another-app-since-1068)
  mirrors for a note instead of a kept attachment file.
- [Attachment retrieval](attachment-retrieval.md) — `ConversationRepository.retrieveAttachment`, the
  host-keyed `AttachmentStore` this reader's two reads (thread, then reader) both resolve through, and why the
  retrieval bound and this reader's own 256 KiB bound are separate numbers for separate reasons.
- [Navigation § Host-qualified destinations](navigation.md#host-qualified-destinations) — the per-component
  `Uri.encode` discipline and the `HostDestination` guard this route follows.
- [Thread screen](thread-screen.md) — `ThreadNavigation`, the one-shot `navigationChannel` this ticket's
  `OpenMarkdown` case rides, and where `onOpenMarkdownAttachment` / `markdownOpenFailures` are wired into
  `ThreadScreen`.
- [MarkdownText § Markdown-path links](markdown-text.md#markdown-path-links-since-1050) — `markdownLinkPath`,
  `routeMarkdownLink`, and the `onOpenMarkdownPath` opt-in this screen's linked-note path is reached through;
  following a link inside an *open* note (attachment or linked) is still a later ticket, unaffected by #1050.
- [MarkdownText § Inline dispatch](markdown-text.md#inline-dispatch) and
  [§ Link safety](markdown-text.md#link-safety--scheme-allowlist) — `inlineText`, `MarkdownFlavour` and
  `isSafeLinkScheme`, all `internal` since #1067 so the copy-and-refresh menu's conversions share the renderer's
  own parse and allowlist rather than a second copy.
- [Thread overflow menu](thread-overflow-menu.md) — `ThreadOverflowMenu`, the `DropdownMenu` +
  `DropdownMenuItem` + dismiss-then-act shape [the reader's own menu](#copy-and-refresh-menu-since-1067) copies,
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
  [open in another app](#open-in-another-app-since-1068), the security review (the accepted stale-grant
  gap, the `shared-note` directory's non-collision with `AttachmentStore`'s host directories), and the
  Revisions entry (the `FileProvider.sCache` reset `MarkdownReaderScreenTest` needed once `AttachmentActionsTest`
  ran first in the same JVM, and resolving notice strings with `stringResource` ahead of the coroutine).
- Ticket: `docs/specs/architecture/1069-markdown-reader-save-to-device.md` — design for
  [Save to device](#save-to-device-since-1069), the security review (no findings; the app never builds a path
  and gets no persistable or tree grant, and lost pending text after recreation is addressed by design), and the
  Revisions entry (the screen test's `file://`-URI provider workaround once `sharedTest` compilation into the
  device set ruled out a Robolectric-only fake content provider).
