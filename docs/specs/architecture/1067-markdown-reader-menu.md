# #1067 — A menu in the markdown reader to copy the note and refresh it

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt` → `MarkdownReaderScreen`, `MarkdownReaderTopBar`, `MarkdownReaderDestination`, `LinkedMarkdownReaderDestination`, `readMarkdownAttachment`, `readLinkedMarkdown`, `MarkdownDocument` — the screen this ticket extends and the two reads Refresh repeats.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt` → `MarkdownFlavour`, `appendInlineChildren`, `trimmedContent`, `fencedCodeText`, `indentedCodeText`, `isSafeLinkScheme` — the one parse and GFM flavour the conversions must share, the inline walk whose text the plain-text copy must equal, and the `http`/`https`/`mailto` allowlist.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt` → `ThreadOverflowMenu` — the `DropdownMenu` + `DropdownMenuItem` + dismiss-then-act shape the reader's menu copies.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopAppBar.kt` → `ThreadTopAppBar` — the `Box { IconButton(MoreVert, cd_more_actions); menu }` anchoring and the bar metrics (`BarGutter`, `BarTouchSlack`, …).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageMetaRow.kt` → `MAX_CLIPBOARD_CHARS`, `CopyTextControl` — the Binder-ceiling bound and the "no confirmation of our own" copy precedent.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `onOpenMarkdownLink`, `linkedMarkdown`, `releaseLinkedMarkdown`, `linkedMarkdownDocument` — where the linked note's path is known and dropped today.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `Routes.MARKDOWN_READER` and `Routes.MARKDOWN_LINK` destinations — where each reader gets its repository and document.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `repository(serverId)` — a destination's `CachingConversationRepository` delegates `readWorkspaceFile` to the host's live repository, so the link destination can re-read through it as the attachment destination already does.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AttachmentActions.kt` → `AttachmentNotice.OPEN_FAILED` — the notice a failed refresh shows.
- `docs/knowledge/features/markdown-reader-screen.md` § "Linked note, live" — the reader must `remember` the thread's hand-off **once**, because the thread releases its copy while the pop transition is still drawing the reader. The path has to be captured in that same single read.
- `docs/knowledge/features/markdown-text.md` — the renderer shows reference links, autolinks, bare URLs, setext headings and `####`+ headings as their source characters; only inline links are links.
- Test shapes: `MarkdownReaderScreenTest`, `MarkdownLinkTapTest` (sharedTest), `MarkdownReaderLoadTest`, `ThreadViewModelMarkdownLinkTest` (test).

No in-flight feature branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=553-2585 (menu: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1958)

`Markdown Reader Screen` (553:2574) puts a `Menu button` — the vertical-ellipsis glyph, 24dp — at the right end of the reader's top bar row, on the same line as the back arrow and the file name, above the rule. It is the thread bar's overflow glyph (`Icons.Filled.MoreVert`, `cd_more_actions`) in the same 48dp touch target. The menu is the thread's `Options overlay` (533:1958), which the operator decided on 2026-09-24 is built exactly as `ThreadOverflowMenu` builds it: an M3 `DropdownMenu` of plain-text `DropdownMenuItem`s anchored under the glyph.

## Context

The reader (#1027, #1050) shows a note but offers no way to take it elsewhere or see a newer version. This ticket adds the overflow button and four of the six Figma items; Open in another app and Save to device are separate tickets that append after Refresh.

No ADR warranted.

## Design

### Conversions — new file `ui/conversations/components/MarkdownConversions.kt`

Pure functions, no Compose runtime, no Android types:

- `internal fun markdownPlainText(markdown: String): String` — the text the reader shows, without syntax. Parses once with the shared `MarkdownFlavour` and walks the blocks:
  - ATX headings (all six levels) → their `ATX_CONTENT`, edge-trimmed. (`####`–`######` are stripped too even though the renderer shows them raw: the AC asks for no heading markers.)
  - Paragraphs, heading content, table cells → **the renderer's own inline walk**: a new `internal fun inlineText(nodes: List<ASTNode>, source: String): String` in `MarkdownText.kt` runs `appendInlineChildren` into a throwaway `AnnotatedString` with an inert `UriHandler` and unspecified colours and returns `.text`. That makes "the rendered text" true by construction: emphasis, code and strike delimiters, paired single tildes and link targets drop exactly as they do on screen; a link contributes its text.
  - Lists → one line per item, no bullet, number or task box; nested blocks of an item indented two spaces per level.
  - Block quotes → their child blocks, no `>`.
  - Fenced / indented code → `fencedCodeText` / `indentedCodeText`, the code as authored, no fences.
  - Tables → header then body rows (the delimiter row skipped), cells joined by a tab, capped at the renderer's `MAX_TABLE_ROWS` / `MAX_TABLE_COLUMNS`.
  - Anything else → its source text trimmed, as the renderer's `else` arm shows it.
  - Blocks joined by a blank line.
- `internal fun markdownHtml(markdown: String): String` — the same parse through `org.intellij.markdown`'s `HtmlGenerator`, with the flavour's own provider map (`MarkdownFlavour.createHtmlGeneratingProviders(LinkMap.buildLinkMap(root, text), null)`) and these overrides:
  - **Raw HTML is text.** The providers for HTML blocks and inline HTML tags are replaced by one that writes the node's source HTML-escaped (`&`, `<`, `>`, `"`, `'`).
  - **One href policy for every link-producing node** (inline, full / short reference, `<autolink>`, GFM bare URL): the destination becomes `href="…"` (attribute-escaped) only when `isSafeLinkScheme` passes — `http`, `https`, `mailto`, the renderer's allowlist, made `internal`; otherwise the link's text alone is written. Link titles are dropped.
  - **Images** write their alt text only; no `<img>` ever reaches the clip (a remote `src` would be a fetch the paste target makes on the note author's behalf).
  - **No note-authored text reaches an attribute unescaped.** The href is the only attribute this code writes from the note. The library's code-fence provider writes the info string into `class="language-…"`; if a hostile info string (`` ```"><script> ``) is not escaped by the library, the fence provider is replaced by one that writes `<pre><code>` with the escaped code and no class. A test pins it either way.
  - The scheme check runs on the destination **after** the library's normalisation (entity decoding), so `java&#115;cript:` is judged as `javascript:`.
- `internal fun boundClipText(text: String): String` — `take(MAX_CLIPBOARD_CHARS)`.
- `internal fun boundClipHtml(html: String): String` — unchanged when within `MAX_CLIPBOARD_CHARS`; otherwise cut after the last `>` inside the bound, so the cut never lands inside a tag or an entity.

`MAX_CLIPBOARD_CHARS` in `MessageMetaRow.kt` goes from `private` to `internal` so both copy paths share the one number (visibility-only). `MarkdownFlavour`, `isSafeLinkScheme` and `trimmedContent` likewise become `internal`.

### The reader — `MarkdownReaderScreen.kt`

- `MarkdownReaderScreen(document, onBack, modifier, onRefresh: () -> Unit = {}, snackbarHostState: SnackbarHostState = remember { SnackbarHostState() })` stays stateless about content: the bar gains the overflow `Box { IconButton(MoreVert); MarkdownReaderMenu(...) }`, the body is unchanged, and a `SnackbarHost` sits at the bottom of the surface. The menu's `expanded` flag is UI-local `remember` state.
- `private fun MarkdownReaderMenu(expanded, onDismiss, onCopy: (MarkdownCopyFormat) -> Unit, onRefresh)` — `DropdownMenu` with, in order, Copy as markdown, Copy as plain text, Copy as HTML, Refresh; each item dismisses, then acts (the `ThreadOverflowMenu` shape).
- Copy runs in the screen against the `document` it is drawing, through `LocalClipboardManager.setClip(ClipEntry(clipData))`:
  - markdown → `ClipData.newPlainText(label, boundClipText(text))`
  - plain → `ClipData.newPlainText(label, boundClipText(markdownPlainText(text)))`
  - HTML → one `ClipData.newHtmlText(label, boundClipText(markdownPlainText(text)), boundClipHtml(markdownHtml(text)))`
  - The clip label is a static string resource, never the note's name. No snackbar: Android 13+ confirms a copy itself.
- `@Composable fun RefreshableMarkdownReader(initial: MarkdownDocument, reread: suspend () -> MarkdownDocument?, onBack, modifier)` owns the refresh: `document` in a `remember(initial) { mutableStateOf(initial) }`, a `SnackbarHostState`, a `rememberCoroutineScope()` and the in-flight `Job`. Refresh while the job is active does nothing. A non-null result replaces `document`; `null` keeps the old one and shows `AttachmentNotice.OPEN_FAILED`'s string in the reader's snackbar.
- `MarkdownReaderDestination`'s `Loaded` arm draws `RefreshableMarkdownReader(initial = document, reread = { readMarkdownAttachment(repository, conversationId, attachmentId) })`.
- `class LinkedMarkdown(val path: String, val document: MarkdownDocument)` — the thread's hand-off, `toString()` lengths only.
- `LinkedMarkdownReaderDestination(note: LinkedMarkdown?, repository: ConversationRepository, conversationId: String, onBack, modifier)` draws `RefreshableMarkdownReader(note.document, reread = { readLinkedMarkdown(repository, conversationId, note.path) })`; `null` goes back as today.

### Thread side

- `ThreadViewModel`: `linkedMarkdownDocument: MarkdownDocument?` becomes `linkedMarkdownNote: LinkedMarkdown?`, set to `LinkedMarkdown(path, document)` on a successful open; `linkedMarkdown(): LinkedMarkdown?`. Still a plain in-memory `var`, still released by `releaseLinkedMarkdown()`; the path never enters the route, the event or saved state.
- `MainActivity`'s `MARKDOWN_LINK` destination remembers `threadVm?.linkedMarkdown()` once, as now, resolves `destinations.repository(target.serverId)` as the `MARKDOWN_READER` destination does, and passes both plus `target.conversationId`.

### Logging

`RelayLog.d` only, static fields: `event=markdown_reader_copy format=markdown|plain|html truncated=true|false` and `event=markdown_reader_refresh outcome=loaded|failed`. Never the text, the name, the path or clip contents.

## State + concurrency model

- Refresh runs in the reader's `rememberCoroutineScope()` on Main; the reads already move file I/O to `Dispatchers.IO` (`readMarkdownAttachment`) or suspend on the relay (`readLinkedMarkdown`). Leaving the reader cancels the scope and the read; cancellation is rethrown by both reads.
- The in-flight guard is the job itself (`job?.isActive == true` → return), checked and set on Main, so two taps cannot both launch.
- The document is composition state only — never `rememberSaveable`, as #1027 requires.
- Copy conversions run synchronously on the tap. The input is bounded by `MAX_MARKDOWN_READER_BYTES` (256 KiB) and is parsed again anyway every time `MarkdownText` recomposes on a new document.

## Error handling

- Refresh failure → `null` from either read (they swallow every non-cancellation exception) → old content stays, snackbar "Couldn't open file".
- Copy cannot fail on content: both conversions are total over any string (the renderer's walk is total by design; the HTML generator's fallback provider handles unknown nodes). Oversize output is bounded, never thrown.

## Testing strategy

- `MarkdownConversionsTest` (`app/src/test/…/components/`, pure JVM):
  - plain text drops heading and quote markers, emphasis/strong/strike/single-tilde delimiters, code-span backticks and fences, list bullets / numbers / task boxes, link targets; keeps code-block content verbatim and table cells;
  - HTML escapes a raw `<script>` block and inline `<img onerror>` as text; keeps `href` for `http`, `https`, `mailto` inline and reference links; writes only the text for `javascript:`, an entity-encoded `javascript:`, `file:`, `data:`, `intent:` and a relative path; a `"` in an allowed href cannot close the attribute; a hostile fence info string cannot open a tag; images come out as alt text with no `<img`;
  - bounds: a 256 KiB note of `<` characters converts, `boundClipHtml` ends on `>` and within the bound, `boundClipText` caps at `MAX_CLIPBOARD_CHARS`.
- `MarkdownReaderScreenTest` (sharedTest, Robolectric) grows:
  - the overflow button opens the four items in order;
  - each copy puts the expected clip on the system `ClipboardManager` (HTML clip: `htmlText` + `text`, MIME `text/html`), with no snackbar;
  - a copy of a note at `MAX_MARKDOWN_READER_BYTES` does not throw and lands bounded;
  - Refresh through `RefreshableMarkdownReader` with a controllable `reread`: new content replaces old; a second Refresh while the first is suspended calls `reread` once; a `null` result keeps the old content and shows "Couldn't open file".
  - The two existing linked-destination tests move to the new signature.
- `ThreadViewModelMarkdownLinkTest`: the held note carries the path it read, alongside the existing name/text assertions.
- No rung-3 scenario of its own: Refresh on a linked note sends the same `read_workspace_file` the #1050 scenario `interactiveTurn_markdownLink_opensLiveNoteInReader` already proves live, and the copies are local. Named in the PR.

## Open questions

- The exact provider keys the flavour registers for autolinks and inline HTML in `markdown` 0.7.3 — resolved in Phase B by the escaping and scheme tests going red first.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/markdown-reader-screen.md` — the reader no longer "has no overflow icon"; add the menu, the copy formats and bounds, Refresh and its failure notice, and `LinkedMarkdown`. `docs/knowledge/features/markdown-text.md` — `inlineText` and the now-`internal` helpers.

## Security review

**Verdict:** PASS (second pass; the first pass found the fence-attribute gap, now in the Design)

**Findings:**

- [Trust boundaries] The note's text is workspace- or daemon-authored and untrusted. This ticket adds one new outbound path for it: the HTML clip, which another app renders. The boundary is the single function `markdownHtml`: raw HTML is escaped as text, every link-producing node passes one allowlist (`isSafeLinkScheme`, judged after entity decoding), images never become `<img>`, link titles are dropped, and the only attribute written from note text (href) is attribute-escaped. First-pass MUST FIX, resolved in the Design: the library's code-fence provider writes the info string into a `class` attribute; the plan now requires escaping it, or replacing the provider, plus a test with a quote-bearing info string. The plain-text clip is text by construction.
- [Trust boundaries] Refresh re-enters the existing reads unchanged: `readLinkedMarkdown` (byte bound, strict UTF-8, name through `attachmentDisplayName`) with the same path the operator's first open used, which the daemon confines; `readMarkdownAttachment` with the same id. No new decoding or naming path.
- [Tokens] No findings: no token, key or credential is read, stored or copied.
- [File / storage] No findings: no new file path is built; the attachment re-read goes through the retrieval store's kept file by id, as #1027 does. The linked path stays in memory (`LinkedMarkdown` in `ThreadViewModel`), out of the route, the navigation event and saved state; the document stays out of `rememberSaveable`.
- [Inter-process] The clipboard is the new IPC surface. Every clip is bounded (`MAX_CLIPBOARD_CHARS` per field, HTML cut on a `>` boundary), so a note at `MAX_MARKDOWN_READER_BYTES` cannot raise `TransactionTooLargeException`. The clip label is a static string, not the note's name. No intents, deep links or pending intents.
- [Crypto] No findings: no randomness, keys or comparisons.
- [Network & I/O] One `read_workspace_file` per Refresh tap; the in-flight job guard stops a second request while one reads, and the response is bounded by the existing reader bound before decoding.
- [Logs] SHOULD FIX (verifier checks): the two new log lines carry static fields only (`format`, `truncated`, `outcome`) — never the text, name, path or clip. No exception message is logged; both reads already drop them unread.
- [Concurrency] The refresh job lives in the reader's `rememberCoroutineScope`, cancelled when the reader leaves composition; guard and state writes happen on Main only. The thread's `releaseLinkedMarkdown` cannot empty an open reader, because the destination still captures the hand-off once.
- [Threat model] Hostile daemon: a note built to inject markup into whatever app receives the paste is neutralised by `markdownHtml`'s escaping and allowlist. OUT OF SCOPE: marking the clip sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`) to hide the system clipboard preview. The operator explicitly copies a note they are reading, and the existing message copy does not mark its clip either. File a ticket if the operator wants it.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-25

## Revisions

**2026-09-25, during implementation.**

- `LinkedMarkdownReaderDestination(note, reread: suspend (path: String) -> MarkdownDocument?, onBack, modifier)` takes a read function instead of `repository` + `conversationId`. `MainActivity` passes `readLinkedMarkdown(destinations.repository(serverId), conversationId, path)`, so the request is unchanged. This keeps the repository out of the composable and makes the destination testable without a fake repository.
- The code-fence provider is **always** replaced (`<pre><code>` with escaped code, no `class`), rather than only if the library turned out not to escape the info string. This removes any dependence on how the library handles it; the hostile-info-string test pins the result.
- A link destination arrives from the library already entity-encoded, so the href is escaped for `"`, `'`, `<` and `>` but its `&` is left as written. Escaping it again turned `&amp;` into `&amp;amp;`. A `&` cannot end the attribute. The autolink provider reads raw source, so it escapes `&` as well.
- The plain-text copy also drops a quote's continuation `>` and the space after it, which sit inside the paragraph. Without that, a two-line quote copies as `words  and more` with a double space.
- The copy log line carries `chars=<source length>` in place of `truncated`: a length is content-free and needs no second conversion pass to compute.
- The bar's end padding is now `BarGutter - BarTouchSlack`, as in `ThreadTopAppBar`, because the row now ends in a 48dp touch target instead of text.

**2026-09-25, rework after verifier review.**

- MUST FIX (no live proof of Refresh): `InteractiveStreamE2ETest#interactiveTurn_markdownLink_opensLiveNoteInReader` now keeps the reader open while the peer has claude rewrite the note, then chooses Refresh from the reader's overflow and waits for the new heading, with the old one and "Couldn't open file" absent. The turn count stays at two: the rewrite moves from the phone's composer to the peer. The re-open through the link that #1050 asserts still follows. This replaces the Testing strategy's "no rung-3 scenario of its own".
- SHOULD FIX (retry blocked by the notice): in `RefreshableMarkdownReader` the failure snackbar is shown in its own `scope.launch`, so the in-flight guard covers the read only. A retry while "Couldn't open file" still shows reads again; `MarkdownReaderScreenTest.aRetry_whileTheFailureNoticeShows_readsAgain` pins it.
