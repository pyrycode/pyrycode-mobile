# #1027 — Open a markdown file attachment in an in-app reader

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AttachmentActions.kt` → `rememberAttachmentActions`, `AttachmentActions`, `openAttachment`, `AttachmentNotice` — the tap's current route to Android's view intent; the markdown branch goes in `open`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageAttachments.kt` → `AttachmentTarget`, `AttachmentViewState.Ready`, `AttachmentSource` — the tap carries the id plus the sanitised display name the row shows; the name decides markdown.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `loadAttachment`, `retrieved`, `navigationChannel` / `navigationEvents` — how the thread already calls `retrieveAttachment` and routes navigation.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadNavigation` — gains the reader variant.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen` parameters, the payload-free `Flow<Unit>` → snackbar idiom (`archiveErrors`), `rememberAttachmentActions` call site.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopAppBar.kt` → `ThreadTopAppBar` and its bar metrics — the reader's bar is this bar without the overflow (Figma).
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `Routes` (`thread`, `hostArguments`, `target`), `PyryNavHost` thread destination, `HostDestination` — route shape and host guard.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `ThreadDestinationFactory.repository` — the host-bound `CachingConversationRepository` the reader resolves the file through.
- `app/src/main/java/de/pyryco/mobile/data/repository/AttachmentRetrieval.kt` → `AttachmentRetrievalResult` — `Retrieved(file, displayName, mimeType)`; every failure is `Failed`.
- `app/src/main/java/de/pyryco/mobile/data/cache/AttachmentStore.kt` → `AttachmentStore.retrieve` — refuses ids failing `isAttachmentIdShape`; kept files are named by id.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt` → `MarkdownText` — the renderer, unchanged.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelAttachmentRetrievalTest.kt` → `RetrievingRepository` — the fake the new VM tests reuse.
- `docs/knowledge/features/navigation.md` § "Host-qualified destinations" — per-component `Uri.encode`; do not use `launchSingleTop` on a parameterised route (it can keep the previous entry's state across arguments).
- `docs/knowledge/features/attachment-retrieval.md` § "Lessons learned" — `ThreadDestinationFactory.repository` builds a new wrapper each call; per-host state lives in `AttachmentStore`, so a second wrapper for the reader reaches the same kept file.

In-flight overlap: `feature/1021` edits `MainActivity.kt` (channel-list event wiring). Different block; building through, edits here are additive.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=553-2574

A full-height `surface` column: the thread's own top bar (24dp back arrow, the file name in `titleLarge` / `onPrimaryContainer`, single line, then the 60% rule 16dp below) followed 12dp lower by the rendered markdown body in a 20dp gutter — headings, body text, bullets, a `surfaceContainer` code block and a quote bar, all as `MarkdownText` already draws them. The frame's overflow glyph is left out per the ticket (it has no actions).

## Context

Tapping a ready file row today always hands the file to another app via `openAttachment`. A markdown note should read in-app with the same renderer as assistant replies. No ADR warranted.

## Design

### Markdown detection — `AttachmentActions.kt`

- `internal fun isMarkdownAttachmentName(name: String?): Boolean` — true when the name ends in `.md` or `.markdown`, ignoring case; `null` is false.
- `rememberAttachmentActions(states, onOpenMarkdown: (attachmentId: String) -> Unit, onNotice)` — in `open`, a `Ready` target whose `target.displayName` passes `isMarkdownAttachmentName` calls `onOpenMarkdown(id)` and nothing else; every other ready target keeps the `openAttachment` path. Works for both `Kept` and `Original` sources: the reader never uses the source.

### Loading — new `ui/conversations/thread/MarkdownReaderScreen.kt`

- `class MarkdownDocument(val name: String, val text: String)` — `toString` prints lengths only.
- `const val MAX_MARKDOWN_READER_BYTES = 262_144` (256 KiB) — the reader's own bound (see Security review).
- `internal fun decodeUtf8Strictly(bytes: ByteArray): String?` — a `CharsetDecoder` with `CodingErrorAction.REPORT` for both malformed and unmappable input; `null` on any error. Never substitutes U+FFFD.
- `internal suspend fun readMarkdownAttachment(repository, conversationId, attachmentId, ioDispatcher = Dispatchers.IO): MarkdownDocument?` — `retrieveAttachment`; on `Retrieved`, reads at most `MAX_MARKDOWN_READER_BYTES + 1` bytes of the kept file on `ioDispatcher` (over the bound → `null`), decodes strictly, and returns the document named by the result's sanitised `displayName`. Any `Failed`, any exception other than cancellation (including a repository that keeps no files), an over-bound file or bad UTF-8 → `null`.

### Thread side — `ThreadViewModel` + `ThreadNavigation`

- `ThreadNavigation.OpenMarkdown(val attachmentId: String)`.
- `ThreadViewModel` gains a trailing constructor parameter `ioDispatcher: CoroutineDispatcher = Dispatchers.IO` (default: no call site changes), and `val markdownOpenFailures: Flow<Unit>` (a buffered `Channel`, same idiom as `archiveErrors`).
- `fun onOpenMarkdownAttachment(attachmentId: String)` — ignored while a previous open is in flight (a double tap would otherwise buffer a second navigation that fires when the operator returns). Otherwise launches in `viewModelScope`: `readMarkdownAttachment(...)`; a document → `navigationChannel.send(OpenMarkdown(id))`; `null` → `markdownOpenFailures` emits. Logs `event=thread_attachment_open id=<id> outcome=reader|failed` only.

### Thread screen — `ThreadScreen.kt`

- New defaulted parameters `onOpenMarkdownAttachment: (String) -> Unit = {}` and `markdownOpenFailures: Flow<Unit> = emptyFlow()`; the first is passed into `rememberAttachmentActions`, the second shows `AttachmentNotice.OPEN_FAILED`'s string as a snackbar.

### Route and destination — `MainActivity.kt`

- `Routes.MARKDOWN_READER = "markdown_reader/{serverId}/{conversationId}/{attachmentId}"`, `Routes.markdownReader(target, attachmentId)` encoding each component with `Uri.encode`, `Routes.markdownReaderArguments()` (the host arguments plus `attachmentId`), `Routes.attachmentId(arguments)`.
- Thread destination: `ThreadNavigation.OpenMarkdown` → `navController.navigate(Routes.markdownReader(target, event.attachmentId))`; wire `onOpenMarkdownAttachment = vm::onOpenMarkdownAttachment`, `markdownOpenFailures = vm.markdownOpenFailures`.
- Reader destination inside `HostDestination(target.serverId, …)`: `MarkdownReaderDestination(repository = remember { destinations.repository(serverId) }, conversationId, attachmentId, onBack = { navController.popBackStack() })`.

### Reader UI — `MarkdownReaderScreen.kt`

- `MarkdownReaderDestination(repository, conversationId, attachmentId, onBack)` — `produceState<MarkdownDocument?>` running `readMarkdownAttachment`; the read is the same kept file the thread just decoded, so it is local. While `null` it draws nothing but the surface; a failed re-read (file removed in between) calls `onBack` once, so garbled or empty content is never shown. The document is held in composition only — never `rememberSaveable`, so no content enters the saved-state bundle.
- `MarkdownReaderScreen(document: MarkdownDocument, onBack: () -> Unit, modifier)` — stateless: `Scaffold`-free `Column` on `surface`: `MarkdownReaderTopBar(name, onBack)` fixed, then a `weight(1f)` `verticalScroll` column with `MarkdownText(document.text)` in the 20dp gutter. System back is the NavHost's own pop.
- `MarkdownReaderTopBar` — the thread bar's row and rule with no overflow and no title click. `ThreadTopAppBar.kt`'s bar metrics become `internal` so both bars share one set of numbers (visibility only).
- `@Preview` light and dark.

## State + concurrency model

- Thread: one `viewModelScope` job per open (guarded single in-flight), reading on `ioDispatcher`, cancelled with the ViewModel. Navigation and failure are one-shot channel events.
- Reader: `produceState`'s coroutine, bound to the destination's composition; cancelled when the entry leaves. Re-runs on configuration change (a local read).

## Error handling

Every failure (retrieval `Failed`, exception, over-bound, malformed UTF-8) collapses to `null` in `readMarkdownAttachment`. On the thread that is the existing open-failed snackbar and no navigation; in the reader it is a silent pop back. Exceptions are never read (their messages can carry paths).

## Testing strategy

- Unit, `app/src/test/.../thread/MarkdownReaderLoadTest.kt`:
  - `isMarkdownAttachmentName`: `.md`, `.MD`, `.Markdown`, `.markdown`; false for `notes.txt`, `notes.md.txt`, `md`, `null`.
  - `decodeUtf8Strictly`: valid multi-byte text round-trips; a lone continuation byte, a truncated sequence and an overlong encoding return `null`.
  - `readMarkdownAttachment` with a fake repository and a temp file: success returns name + text; `NotFound`/`Unavailable` → `null`; invalid UTF-8 → `null`; file over the bound → `null`; file exactly at the bound → document; a throwing repository → `null`; a missing file → `null`.
- Unit, append to `ThreadViewModelAttachmentRetrievalTest`: success emits `ThreadNavigation.OpenMarkdown(id)` and no failure; bad UTF-8 and a retrieval failure emit one `markdownOpenFailures` and no navigation; logs carry id + static outcome and never the name.
- Unit, `AttachmentActions` routing: covered by `isMarkdownAttachmentName`; the composable's branch is a single `if`.
- Shared screen test, `app/src/sharedTest/.../thread/MarkdownReaderScreenTest.kt`: the file name is in the top bar and rendered content below (a heading's text, a list item); the back arrow calls `onBack`; with long content, scrolling to the last paragraph leaves the name and back arrow displayed.
- No rung-3 scenario: the flow opens a file already on the phone and sends nothing to the daemon; the retrieval path it relies on is #984's. Noted as a follow-up only if the verifier asks.

## Open questions

- Is 256 KiB the right bound? It is a composition-cost bound (the renderer builds a non-lazy column), far above any workspace note. Resolve in implementation only if a test shows it trips a realistic file.

## Documentation handoff

Pending for the documentation stage: the ticket names no documentation section. Suggest `docs/knowledge/features/navigation.md` (new `markdown_reader/{serverId}/{conversationId}/{attachmentId}` route) and `docs/knowledge/features/attachment-retrieval.md` or the thread attachments topic (markdown taps open the in-app reader).

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] The file's bytes and name are daemon-/assistant-authored. The boundary is `readMarkdownAttachment`: strict UTF-8 decode and a byte bound (`MAX_MARKDOWN_READER_BYTES`) before any text reaches Compose; the text reaches only `MarkdownText`, which renders as Compose text with its existing link-scheme allowlist and table bounds, never a WebView. The name is the retrieval's sanitised `displayName`, drawn single-line and ellipsised.
- [Trust boundaries / DoS] SHOULD FIX (addressed in the design) — retrieval allows 23 MB, and `MarkdownText` parses in composition and lays out a non-lazy column; a hostile large `.md` would freeze the UI. The 256 KiB bound, enforced by a bounded read (not a length check followed by an unbounded read), closes it.
- [Tokens] No findings — no secrets touched.
- [File / storage] No findings — the route carries ids only (no path, URI or name); the file is resolved by `AttachmentStore.retrieve`, which refuses ids failing `isAttachmentIdShape` and names files by id under its own root. Reads are app-private; nothing is written. The document is never placed in `rememberSaveable`, so no content enters the saved-state bundle.
- [Android attack surface] No findings — no new exported component, intent filter or deep link; the route is reachable only through in-app navigation. The markdown branch removes an outgoing view intent rather than adding one.
- [Crypto] Not applicable — no primitives involved.
- [Network & I/O] No findings — the reader reuses the kept file; any fetch is #899's bounded retrieval, unchanged.
- [Logs] No findings — `event=thread_attachment_open id=<id> outcome=reader|failed` only; `MarkdownDocument.toString` prints lengths; exception messages are never read.
- [Concurrency] No findings — the thread job lives in `viewModelScope` with a single-in-flight guard so a double tap cannot buffer a navigation that fires later; the reader's `produceState` is composition-bound.
- [Threat model] OUT OF SCOPE — screenshot protection (`FLAG_SECURE`) for rendered content is not applied to the thread either; unchanged here.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24
