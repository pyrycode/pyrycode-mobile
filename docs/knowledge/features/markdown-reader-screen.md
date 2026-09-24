# Markdown reader screen

In-app reader for a markdown attachment, at the `markdown_reader/{serverId}/{conversationId}/{attachmentId}`
route (#1027). Tapping a ready file row whose name ends in `.md` or `.markdown` (any case) opens this screen
instead of handing the file to another app; every other attachment type still goes through
[`openAttachment`](message-bubble-attachment-slot.md#open-and-save-since-985). Figma: `Markdown Reader Screen`
(`553:2574`). Package: `de.pyryco.mobile.ui.conversations.thread`, new file `MarkdownReaderScreen.kt`.

## What it does

The reader draws the thread's own top bar — 24dp back arrow, the file name in `titleLarge` /
`onPrimaryContainer` on one ellipsised line, a 60%-alpha `outlineVariant` rule — with no overflow icon and no
title tap, since the Figma frame's overflow has no actions yet (left out per the ticket). Below it, the file
renders through [`MarkdownText`](markdown-text.md), the same renderer [assistant replies](message-bubble.md)
use, in a `weight(1f)` `verticalScroll` column under the fixed bar. The back arrow and system back both pop
the destination and return to the same thread. A file that cannot be read, or is not valid UTF-8, never opens
the reader at all — the operator stays on the thread with the existing `AttachmentNotice.OPEN_FAILED`
snackbar (see [Load and navigate from the thread](#load-and-navigate-from-the-thread) below).

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
`Loaded(document)` renders `MarkdownReaderScreen`; `Failed` — the file disappeared between the thread's read
and this one — calls `onBack()` once from a `LaunchedEffect(Unit)`, through `rememberUpdatedState(onBack)` so
a stale lambda from an earlier composition is never captured. This is how "never shows empty or garbled
content" holds even for the rare in-between-reads removal case; no test pins this path specifically (verifier
NIT on PR #1034 — a shared test with a failing fake repository would cover it cheaply). The document is held
in composition only, never `rememberSaveable`, so no file content ever enters the saved-state bundle.

`MarkdownReaderScreen(document, onBack, modifier)` is the stateless render: a `Surface` (not a bare `Column`
with a background — `Surface` is what makes `MaterialTheme.colorScheme.onSurface` the content colour
`MarkdownText`'s text draws in) holding `MarkdownReaderTopBar` then the scrolling body. `MarkdownReaderTopBar`
reuses `ThreadTopAppBar`'s bar-metric constants (`BarGlyphSize`, `BarTouchSize`, `BarTouchSlack`, `BarGutter`,
`BarTopGap`, `BarRuleGap`, `BarBottomGap`, `BAR_RULE_ALPHA`) — promoted from `private` to `internal` in
`ThreadTopAppBar.kt` by this ticket, visibility-only, so both bars share one set of numbers rather than a
second copy.

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

## Testing

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

## Related

- [MessageBubble — attachment slot § Open and save](message-bubble-attachment-slot.md#open-and-save-since-985) —
  the `Ready`-row tap this screen is one branch of; `AttachmentActions.kt`'s `rememberAttachmentActions` and
  `openAttachment`.
- [Attachment retrieval](attachment-retrieval.md) — `ConversationRepository.retrieveAttachment`, the
  host-keyed `AttachmentStore` this reader's two reads (thread, then reader) both resolve through, and why the
  retrieval bound and this reader's own 256 KiB bound are separate numbers for separate reasons.
- [Navigation § Host-qualified destinations](navigation.md#host-qualified-destinations) — the per-component
  `Uri.encode` discipline and the `HostDestination` guard this route follows.
- [Thread screen](thread-screen.md) — `ThreadNavigation`, the one-shot `navigationChannel` this ticket's
  `OpenMarkdown` case rides, and where `onOpenMarkdownAttachment` / `markdownOpenFailures` are wired into
  `ThreadScreen`.
- [MarkdownText](markdown-text.md) — the renderer this screen reuses unchanged; following links inside a
  rendered note is a later ticket.
- Ticket: `docs/specs/architecture/1027-markdown-reader.md` — design, the security review (the 256 KiB bound
  closes a composition-cost DoS the retrieval bound alone would not), and the implementation revisions (the
  `Surface`-over-`Column` choice, the `modifier` parameter, where `isMarkdownAttachmentName` ended up, and the
  tap-routing screen test).
