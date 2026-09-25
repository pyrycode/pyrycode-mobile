# #1050 — Open a markdown link in the assistant's reply in the in-app reader, fetched live

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt` → `MarkdownText`, `appendInline` (the `INLINE_LINK` arm's `LinkInteractionListener`), `isSafeLinkScheme` — where a link tap is decided today; every private block function takes a `UriHandler`, so routing through a wrapping handler touches none of their signatures.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt` → `MessageBubble`, `AssistantMessage`, `StreamingAssistantBody`, `StreamingAssistantBodyView` — the two assistant render paths (finished and streaming) that opt in; `UserMessageBubble` renders plain `Text` and has no links.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt` → `MAX_MARKDOWN_READER_BYTES`, `decodeUtf8Strictly`, `MarkdownDocument`, `readMarkdownAttachment`, `MarkdownReaderDestination`, `MarkdownReaderScreen` — #1027's loading pieces and screen, reused as is.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `onOpenMarkdownAttachment`, `markdownOpenJob`, `markdownOpenFailures`, `navigationChannel` — the single-in-flight guard and failure signal this ticket shares.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadNavigation` — gains the linked-note variant.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen` parameters, the `MessageBubble` call in the delivered-row arm, the `markdownOpenFailures` snackbar.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `Routes` (`MARKDOWN_READER`, `markdownReader`, `hostArguments`, `target`), the thread destination's `navigationEvents` collector, the `MARKDOWN_READER` destination.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `readWorkspaceFile` (#1049) — one live read per call, verified bytes in memory, never throws except on cancellation.
- `app/src/main/java/de/pyryco/mobile/data/repository/AttachmentRetrieval.kt` → `AttachmentFetchResult.Fetched`, `AttachmentContent` (`size`, `writeTo`) — the in-memory result.
- `app/src/main/java/de/pyryco/mobile/data/network/AttachmentPayloads.kt` → `attachmentDisplayName` — sanitiser for the assistant-authored top-bar name.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelAttachmentRetrievalTest.kt` → `RetrievingRepository`, the `openMarkdown_*` tests — the fixture shape the new VM tests follow.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownTextTest.kt`, `.../thread/MarkdownReaderScreenTest.kt` — screen-test idioms for the renderer and the reader.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart`, `answerChat`, `openChatRow`, `sendFromPhone`, `allowPromptsUntil`, `leaveThread` — the rung-3 shape the new scenario copies; `scripts/e2e-emulator.sh` LIVE list and `scripts/android-test-gate.py` `LIVE_MINIMUM`.
- `docs/specs/architecture/1027-markdown-reader.md`, `docs/specs/architecture/1049-read-workspace-file.md` — the two predecessors.

In-flight overlaps (additive, building through): `feature/1043` edits `ThreadScreen.kt` (different block), `feature/1021` edits one line of `MainActivity.kt`, and `feature/1017`, `1020`, `1021`, `1036` append scenarios to `InteractiveStreamE2ETest.kt`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=553-2574

Unchanged from #1027: a full-height `surface` column with the thread's top bar (back arrow, file name in `titleLarge` / `onPrimaryContainer`, the 60% rule), then the `MarkdownText` body in a 20dp gutter. The frame's three-dot menu belongs to #1031 and stays out. This ticket draws no new UI; it reuses `MarkdownReaderScreen`.

## Context

Assistant replies link workspace notes by path (`[Plan](notes/Plan.md)`), and today the tap does nothing because `isSafeLinkScheme` sees no allowed scheme. The operator decided (2026-09-24) that the reader always shows the file as it is on the host now, so every open is a fresh `read_workspace_file` (#1049) and nothing is kept between opens. No ADR warranted.

## Design

### Link classification — `MarkdownText.kt`

- `internal fun markdownLinkPath(target: String): String?` — the path to send, or `null` when [target] is not a markdown path. Removes a trailing `#fragment` (from the last `#`), then a trailing `:line` or `:line:column` (digits only), then refuses anything that still starts with a URL scheme (`^[A-Za-z][A-Za-z0-9+.-]*:`), then requires a `.md` / `.markdown` suffix, any case. The stripped path is returned exactly as written: no decoding, resolving or trimming beyond the link destination's existing `<…>` / whitespace handling. Stripping `:12` before the scheme check is what keeps `Plan.md:12` from reading as scheme `plan.md`.
- `internal fun routeMarkdownLink(target: String, onOpenMarkdownPath: ((String) -> Unit)?, openUri: (String) -> Unit)` — with a callback and a markdown path, the callback gets the path and nothing opens externally; otherwise today's rule: `openUri` only for `http`, `https`, `mailto`.
- `MarkdownText(markdown, modifier, onOpenMarkdownPath: ((String) -> Unit)? = null)` — the default keeps today's behaviour exactly, so the reader's own `MarkdownText` and every other caller are unchanged. The composable wraps `LocalUriHandler.current` in a private `UriHandler` whose `openUri` calls `routeMarkdownLink` (callback held through `rememberUpdatedState`); the `INLINE_LINK` listener calls that handler for every link target instead of checking `isSafeLinkScheme` itself. No private block function changes signature.

### Assistant opt-in — `MessageBubble.kt`

- `MessageBubble(…, onOpenMarkdownLink: ((String) -> Unit)? = null)` → `AssistantMessage` → both `MarkdownText` (finished) and `StreamingAssistantBody` / `StreamingAssistantBodyView` (streaming). User and tool rows and attachment rows do not receive it.

### Thread side — `ThreadViewModel`, `ThreadNavigation`, `ThreadScreen`

- `ThreadNavigation.OpenLinkedMarkdown` (a `data object`): the note to read is the one the ViewModel holds.
- `ThreadViewModel`:
  - `fun onOpenMarkdownLink(path: String)` — returns at once while `markdownOpenJob` is active (shared with attachment opens, so any second tap sends nothing). Otherwise clears the held document and launches: `readLinkedMarkdown(repository, conversationId, path)`; a document is stored as the held one and `OpenLinkedMarkdown` is sent; `null` sends `markdownOpenFailures`. Logs `event=thread_markdown_link_open outcome=reader|failed` only: no path, no name.
  - `fun linkedMarkdown(): MarkdownDocument?` — the held document, read once by the reader destination.
  - `fun releaseLinkedMarkdown()` — drops it; called when the thread destination is composed again (the operator is back on the thread), so the document lives only while its reader is open.
- `ThreadScreen(…, onOpenMarkdownLink: (String) -> Unit = {})` → the delivered-row `MessageBubble` call. The existing `markdownOpenFailures` snackbar ("Couldn't open file") covers link failures too.

### Loading — `MarkdownReaderScreen.kt`

- `internal suspend fun readLinkedMarkdown(repository: ConversationRepository, conversationId: String, path: String): MarkdownDocument?` — one `readWorkspaceFile(conversationId, path)`. `Fetched` whose `content.size` is at most `MAX_MARKDOWN_READER_BYTES` is copied into memory, strictly decoded with `decodeUtf8Strictly`, and named `linkedMarkdownName(path)`. Any `Failed`, over-bound size, bad UTF-8 or non-cancellation exception → `null`. Nothing touches disk.
- `internal fun linkedMarkdownName(path: String): String` — the text after the last `/`, through `attachmentDisplayName`.
- `@Composable fun LinkedMarkdownReaderDestination(document: MarkdownDocument?, onBack, modifier)` — a document draws `MarkdownReaderScreen`; `null` (process restored with the reader on top, or a destination reached without an open) goes `onBack` once and draws nothing but the surface, as #1027's failed re-read does.

### Route and destination — `MainActivity.kt`

- `Routes.MARKDOWN_LINK = "markdown_link/{serverId}/{conversationId}"` and `Routes.markdownLink(target)` (per-component `Uri.encode`, as `thread`). Arguments are `hostArguments()`.
- Why not the ticket's suggested widened route: the path never needs to travel. The document is already in the thread's ViewModel, and a path in the route would put assistant-authored text into the saved back stack. The route keeps ids only, so `Routes.markdownReader`'s "Ids only" KDoc stays true and `MARKDOWN_READER` is untouched.
- Thread destination: `OpenLinkedMarkdown` → `navigate(Routes.markdownLink(target))`; wire `onOpenMarkdownLink = vm::onOpenMarkdownLink`; `LaunchedEffect(vm) { vm.releaseLinkedMarkdown() }`.
- Link-reader destination inside `HostDestination`: find the thread entry beneath with `navController.getBackStackEntry(Routes.CONVERSATION_THREAD)` (none → `null`), get its `ThreadViewModel` with `koinViewModel(viewModelStoreOwner = entry)`, and `remember(backStackEntry)` the value of `linkedMarkdown()` once, then draw `LinkedMarkdownReaderDestination`. Remembering once means the release that runs when the thread recomposes during the pop transition cannot turn the reader's document to `null` and trigger a second pop.

## State + concurrency model

- One `viewModelScope` job per open, shared guard `markdownOpenJob`, cancelled with the ViewModel. The fetch suspends in the repository (its own mutex, stall timeout and cancellation).
- The held document is a plain `private var` on the ViewModel, set on the main thread before the navigation event is sent and read by the reader destination during composition, so it is set before it is read. Released when the thread destination recomposes.
- The reader holds the document in composition only (`remember`, never `rememberSaveable`). A configuration change re-reads it from the ViewModel. Process death loses it, and the reader goes back.

## Error handling

`readLinkedMarkdown` collapses every failure (`NotFound`, `Unavailable` covering a refusal, aborted or stalled stream and a dropped connection, `Invalid`, `TooLarge`, over 256 KiB, bad UTF-8, exceptions) to `null`: no navigation, one `markdownOpenFailures` emission, and the fixed "Couldn't open file" snackbar. Exception messages are never read. An empty but valid file is a real note and opens empty; that is not a failure.

## Testing strategy

- Unit, `app/src/test/.../components/MarkdownLinkRoutingTest.kt`:
  - `markdownLinkPath`: `notes/Plan.md`, `Plan.md:12`, `Plan.md:12:5`, `docs/A.MARKDOWN#usage`, `My%20Plan.md` and `../x.md` returned verbatim; `null` for `https://h/a.md`, `file:a.md`, `mailto:a.md`, `notes/a.txt`, `a.md.txt`, `Plan.md:x`, `#a.md`.
  - `routeMarkdownLink`: a markdown path with a callback calls it with the stripped path and never `openUri`; the same path without a callback does nothing; `https` with a callback opens externally; `javascript:` does nothing.
- Unit, append to `MarkdownReaderLoadTest`: `readLinkedMarkdown` returns name (last component, sanitised) and text, and passes conversation id and path unchanged; `NotFound`, `Unavailable`, `Invalid`, `TooLarge`, one byte over the bound, bad UTF-8 and a throwing repository → `null`; exactly at the bound → document; `MarkdownDocument.toString` unchanged.
- Unit, new `ThreadViewModelMarkdownLinkTest`: success makes one read and emits `OpenLinkedMarkdown` with `linkedMarkdown()` holding the note; a second tap while the read is suspended sends nothing; a reopen after release makes a second read and holds the new text; a failure emits one `markdownOpenFailures`, no navigation, nothing held; `releaseLinkedMarkdown` clears; logs carry neither the path nor the text.
- Shared screen test, `app/src/sharedTest/.../components/MarkdownLinkTapTest.kt` (Robolectric): tapping a markdown link in a finished and in a streaming assistant `MessageBubble` calls `onOpenMarkdownLink` with the stripped path and never the `UriHandler`; an `https` link there still reaches the handler; a `MarkdownText` with no callback leaves a markdown-path tap inert. `LinkedMarkdownReaderDestination` with a document shows its name and content; with `null` it calls `onBack` exactly once.
- Rung 3, `InteractiveStreamE2ETest#interactiveTurn_markdownLink_opensLiveNoteInReader` (two claude turns): claude writes `e2e1050-<stamp>.md` with one `printf` and replies with a link to it; tapping the link shows the note's marker text and file name in the reader and hides the composer; back returns to the thread; claude rewrites the note; tapping again shows the new marker and not the old one. Added to the `LIVE` list in `scripts/e2e-emulator.sh`, with `LIVE_MINIMUM += 1` in `scripts/android-test-gate.py`. No rung-4 twin: the scripted `fakeclaude` has no workspace-file tool path to hold, and the phone side is covered by the unit and screen tests.

## Open questions

- Does Compose's `performFirstLinkClick` exist at this BOM (`2026.02.01`)? If not, tests invoke the `LinkAnnotation`'s listener from the annotated text.

## Documentation handoff

Pending for the documentation stage: the ticket names no documentation section. Suggest `docs/knowledge/features/markdown-text.md` (markdown-path links and the opt-in callback), `docs/knowledge/features/markdown-reader-screen.md` (the live linked-note entry and its `markdown_link/{serverId}/{conversationId}` route), `docs/knowledge/features/navigation.md` (the route), and `docs/e2e-interactive-stream.md` (the new LIVE scenario).

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The link target is assistant-authored, and a prompt-injected claude controls it. It crosses into the app at `markdownLinkPath` and is used only as an outbound `read_workspace_file` path. The phone never resolves it, never uses it as a local path and never puts it in a URI or intent. Confinement and the markdown-only rule belong to the daemon (#1049, `protocol-mobile.md` § `read_workspace_file`). The fetched bytes cross back at `readLinkedMarkdown`: size bound first, then strict UTF-8, and they reach only `MarkdownText`, which renders text and not a WebView.
- [Trust boundaries / spoofing] No findings. Link text can name one file while the target names another (`[Plan](secrets.md)`). The reader's top bar shows the last component of the path actually read, through `attachmentDisplayName`, and never the link text, so the operator sees what opened.
- [Trust boundaries / chaining] No findings. Links inside an open note use `MarkdownText`'s default: a markdown path does nothing, and other links keep the existing `http`/`https`/`mailto` allowlist. A note cannot chain further opens. A link opens only on a tap through `LinkInteractionListener`.
- [Tokens] No findings. No secrets touched.
- [File / storage] No findings. Nothing is written. `AttachmentContent` is copied into memory only when within `MAX_MARKDOWN_READER_BYTES`. The route `markdown_link/{serverId}/{conversationId}` carries ids only, so no path or content enters the saved back stack. The document lives in the ViewModel field and the reader's `remember`, never `rememberSaveable`, and it is released when the operator returns to the thread.
- [Android surface] No findings. There is no new exported component, intent filter or deep link. The route is reachable only by in-app navigation. A markdown-path tap now stays in-app, and no path reaches `UriHandler` or another app.
- [Crypto] Not applicable. The read rides the existing Noise session and #1049's SHA-256 integrity check.
- [Network & I/O] No findings. One request per open, and the shared `markdownOpenJob` guard blocks a second request while one is in flight. The daemon may stream up to #1049's 23 MB retrieval bound before the 256 KiB check runs. That is the retrieval's existing heap bound, and bytes over 256 KiB are dropped without being copied or decoded.
- [Logs] No findings. The code logs only `event=thread_markdown_link_open outcome=reader|failed`, with no path, name, text or conversation id. Exceptions are caught without reading their messages. `MarkdownDocument.toString` prints lengths only. Verified by test.
- [Concurrency] No findings. The job runs in `viewModelScope`. The held document is written on the main thread before the navigation event is sent. The reader remembers it once, so the release that runs during the pop transition cannot trigger a second pop. A read that finishes while the operator is on another screen buffers its navigation until the thread returns. #1027's attachment open does the same, and it is not a leak.
- [Threat model] OUT OF SCOPE, as in #1049. The accepted residual is that a hostile or injected reply can make the operator's tap read any markdown file inside that conversation's workspace. `FLAG_SECURE` for rendered content stays out of scope, as in #1027.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-25

## Revisions

- 2026-09-25 (implementation): Open question resolved: `performFirstLinkClick` exists at BOM `2026.02.01`, so the screen tests and the rung-3 scenario tap the rendered link itself. A streaming reply reveals its source a character at a time, so the tap is retried until the link is complete. Both kinds of test wait for the link before they tap. No contract changed.
