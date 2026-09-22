# MessageBubble

Stateless row primitive (#128) rendering a single `Message` in the conversation thread surface. Three visual variants dispatched off `Message.role`: a right-aligned bubble for `Role.User` (plain text) and a left-aligned bubble for `Role.Assistant` (markdown-rendered via [`MarkdownText`](./markdown-text.md) since #129 — CommonMark element set, boxed since #644), both through one shared `Message` component; and a tap-to-expand `surfaceContainerHigh` card for `Role.Tool`, routed via [`ToolCallRow`](./tool-call-row.md) since #131. Every bubble ends with a **meta row** — that message's own locale-formatted date/time plus a copy control (#644). Assistant content reveals progressively with a blinking caret when `Message.isStreaming = true` (#184). Eventual call site is the `LazyColumn(reverseLayout = true)` body of [`ThreadScreen`](./thread-screen.md).

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). Files: `MessageBubble.kt` (both role bubbles, the streaming pair) and `MessageMetaRow.kt` (the meta row + copy control, since #644 — `internal` rather than file-private so [#657](../codebase/657.md)'s per-code-block copy control can reuse it). Sibling of [`DiscussionPreviewRow`](./discussion-preview-row.md), [`ConversationRow`](./conversation-row.md), `ArchiveRow.kt`.

## What it does

Dispatches on `message.role` with a Kotlin `when`:

- **`Role.User`** → `UserMessageBubble(message, modifier)` — right-aligned, filled from the `primaryContainer` pair, plain unparsed `Text(message.content)`.
- **`Role.Assistant`** → `AssistantMessage(message, modifier)` — left-aligned, filled from the `secondaryContainer` pair, either the static [`MarkdownText`](./markdown-text.md) (finalized) or the private `StreamingAssistantBody` (while `message.isStreaming`).
- **`Role.Tool`** → `message.toolCall?.let { ToolCallRow(toolCall = it, modifier = modifier.padding(horizontal = MessageContentGutter)) }` — routes the unwrapped [`ToolCall`](./data-model.md) payload to [`ToolCallRow`](./tool-call-row.md) since #131. Since #644 this arm also applies the thread's shared content gutter to the modifier it passes down, so the tool card sits on the same inset as the two bubble roles without [`ToolCallRow.kt`](./tool-call-row.md) itself changing — that file is owned by [#658](../codebase/658.md), and this is a caller-side `Modifier.padding`, not an edit to it. The null-safe `?.let` still absorbs the data-class invariant (`toolCall` non-null iff `role == Role.Tool`) silently — a `Role.Tool` message with `toolCall = null` (a data-layer bug) renders nothing.

Both bubble roles route through one private `MessageContainer(message, alignment, bubbleColor, bubbleContentColor, body)` — the design's shared `Message` component (Figma `132:*`, inside `Message area` `533:1956`). It is the only place that knows the shape, the padding, the gutter/inset geometry and the meta row; the two role composables differ only in which alignment and which M3 container-pair they pass in. See [Shared `Message` container](#shared-message-container-since-644) below.

## Shape

```kotlin
@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
)
```

Single `Message` parameter (not pre-split `(text, isUser)`). `ThreadScreen`'s `LazyColumn` already keys each row on `"msg:${item.message.id}"`; #644 did not need to touch that keying, and the meta row's copy control reads `Message.content` directly rather than anything derived from the list key.

The composable is **pure rendering** — no `remember`, no `LaunchedEffect`, no coroutines, no state hoisting at the `MessageBubble` level. `Message` is a `data class` with all stable fields, so Compose's stability inference skips recompositions on identity-equal and `equals`-equal inputs without any `@Stable` / `@Immutable` annotation.

## How it works

### Role dispatch — exhaustive `when`, no default, no defensive throw

```kotlin
when (message.role) {
    Role.User -> UserMessageBubble(message, modifier)
    Role.Assistant -> AssistantMessage(message, modifier)
    Role.Tool ->
        message.toolCall?.let {
            ToolCallRow(toolCall = it, modifier = modifier.padding(horizontal = MessageContentGutter))
        }
}
```

Since #644 the assistant and user arms both take the full `Message` (not just `message.content`), because the shared `MessageContainer` reads `message.timestamp` for the meta row and `message.content` for both the body and the copy text.

The absence of a default `else ->` arm is deliberate: it keeps the Kotlin compiler enforcing exhaustiveness against the `Role` enum, so a future fourth value (e.g. `Role.System`) is flagged at this site as a hard compile-time decision rather than silently absorbed into a fallback. Project-wide pattern for `when (role)` / `when (kind)` dispatchers in `ui/conversations/components/`.

### Shared `Message` container (since #644)

```kotlin
@Composable
private fun MessageContainer(
    message: Message,
    alignment: Alignment.Horizontal,
    bubbleColor: Color,
    bubbleContentColor: Color,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    val isUserSide = alignment == Alignment.End
    Row(
        modifier = modifier.fillMaxWidth().padding(
            start = MessageContentGutter + if (isUserSide) MessageRoleInset else 0.dp,
            end = MessageContentGutter + if (isUserSide) 0.dp else MessageRoleInset,
            bottom = MessageAreaRowSpacing,
        ),
        horizontalArrangement = Arrangement.spacedBy(0.dp, alignment),
    ) {
        Surface(
            modifier = Modifier.testTag(MESSAGE_BUBBLE_TEST_TAG),
            shape = BubbleShape,
            color = bubbleColor,
            contentColor = bubbleContentColor,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = BubbleHorizontalPadding, vertical = BubbleVerticalPadding),
                verticalArrangement = Arrangement.spacedBy(BubbleContentSpacing),
                horizontalAlignment = Alignment.Start,
            ) {
                body()
                MessageMetaRow(timestamp = message.timestamp, copyText = message.content, modifier = Modifier.align(alignment))
            }
        }
    }
}
```

`UserMessageBubble` and `AssistantMessage` are now thin: each supplies `alignment` (`Alignment.End` / `Alignment.Start`), the M3 container-pair (see [Token mapping](#token-mapping-figma-roles-against-this-apps-two-schemes) below), and the `body` composable — plain `Text` for the user, the streaming/static markdown pair for the assistant. Neither role owns its own `Surface`, padding, or meta row any more; drift between the two is no longer possible because there is one function that lays both out.

The frame's 412dp reference width carries a 20dp gutter on each edge (`MessageContentGutter`), leaving a 372dp content area; the role container then insets its *opposite* edge by 100dp (`MessageRoleInset`), which caps a bubble at 272dp there. The inset is the mechanism and 272dp is its value at the reference width — there is no separate max-width constant to drift away from it. The gutter lives on the component (not on `ThreadScreen`'s `LazyColumn`, which applies none) because this ticket did not touch that screen.

The `Column`'s `horizontalAlignment = Alignment.Start` applies to **both** roles — the design puts `items-start` on the `Message` column even for the right-aligned user bubble (a short user body left-aligns inside its own bubble), while `justify-end` is on the *meta row* alone. That is why the meta row alone takes `Modifier.align(alignment)` rather than the whole column taking the role's alignment.

The meta row is handed `message.content` directly — never text read back out of `body` — so an assistant bubble's copy control copies the markdown *source*, not the parsed render.

### The #128 divergence closes here

`MessageBubble`'s assistant body was unboxed, flat text since #128; `message-bubble.md` used to record that as a deliberate, unresolved divergence from the Figma frame, with `needs-rework:po` as the escalation path for reconciling it. **#644 is that reconciliation, resolved in favour of the design.** Both roles now render through the same boxed `Message` component described above; there is no more flat-vs-boxed asymmetry between the two roles, and the escalation note is retired. The asymmetry that remains is only alignment and colour — which side of the lane, and which M3 container-pair.

### Token mapping — Figma roles against this app's two schemes

The supplied `16:8` adaptation is drawn against this app's **dark** palette, and its role names are only partly usable literally:

| Figma role | Design hex | Used here | Why |
|---|---|---|---|
| Assistant fill `Schemes/on-primary-fixed` | `#001D34` | `colorScheme.secondaryContainer` | **Divergence.** `Theme.kt`'s `darkColorScheme(...)` / `lightColorScheme(...)` never set the M3 *fixed* roles, so `colorScheme.onPrimaryFixed` resolves to the baseline-purple default, not anything in this palette. `secondaryContainer` is the canonical partner of the assistant body's own `onSecondaryContainer` and keeps the assistant bubble distinct from the user's primary-tinted one in both schemes. |
| Assistant body `Schemes/on-secondary-container` | `#D6E4F7` | `colorScheme.onSecondaryContainer` | As named. |
| User fill `Schemes/on-primary` | `#003355` | `colorScheme.primaryContainer` | **Divergence**, same reasoning. Also the fill the shipped user bubble already painted pre-#644, so [`QueuedBacklog`](queued-backlog-section.md)'s mirrored row stays in family for free. |
| User body `Schemes/on-primary-container` | `#CFE4FF` | `colorScheme.onPrimaryContainer` | As named. |
| Meta row text + copy glyph `Schemes/inverse-primary` | `#32628D` | `LocalContentColor.current.copy(alpha = META_CONTENT_ALPHA)` | **Divergence.** M3 has no de-emphasis role *inside* a filled container; `inverse-primary` is a light-scheme primary tone and only reads as de-emphasis against the dark reference frame. Taking the host bubble's own content colour at a fixed alpha de-emphasises correctly in both bubbles and both schemes. |

Every other `Schemes/*` hex in `16:8` matches a `*Dark` value in `Color.kt` exactly; only the *fixed* roles and the in-container de-emphasis role need this substitution. Same trade #643 made for the header rule (`Schemes/inverse-primary` @ 60% → `outlineVariant` at 0.60 alpha), reused verbatim for the [session boundary](session-boundary-delimiter.md)'s rules.

### Meta row and copy control (`MessageMetaRow.kt`, since #644)

Each bubble's last child is a `MessageMetaRow(timestamp, copyText, modifier)`: a `Row` of the formatted timestamp (`typography.bodySmall`) and a `CopyTextControl`, 8dp apart, aligned to the bubble's own side via the caller's `Modifier.align(alignment)`.

```kotlin
internal fun formatShortDateTime(instant: Instant, timeZone: TimeZone, locale: Locale): String
```
The design's date-then-time timestamp (sample `13.01.2026 - 13:55`). The date half is `DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)`; the time half is `SessionBoundaryDelimiter.kt`'s already-shipped `formatShortTime` (`internal`, same package, importable without moving it) — so neither half carries a hardcoded pattern. The ` - ` separator and the date-before-time order are the design's own and are fixed here rather than delegated to `ofLocalizedDateTime`, which would let a locale reorder them.

```kotlin
@Composable
internal fun CopyTextControl(text: String, contentDescription: String, modifier: Modifier = Modifier)
```
A `Box(Modifier.clickable(role = Role.Button) { ... }.padding(CopyTouchPadding))` around the 11×12 copy glyph (`R.drawable.ic_copy`, tinted from `LocalContentColor`), writing `AnnotatedString(text.take(MAX_CLIPBOARD_CHARS))` to `LocalClipboardManager.current` on tap — the `ChannelInfoSheet` footer's clipboard idiom. It reads **nothing** from the composition tree: the copied text is exactly the caller-supplied `text` argument, never something re-derived from `body`'s rendered output. `MAX_CLIPBOARD_CHARS = 100_000` — `Message.content` is daemon-authored and bounded nowhere on the inbound path, while `ClipData` crosses a Binder transaction with a ~1MB ceiling; an unbounded `setText` on a long assistant turn would throw `TransactionTooLargeException` on the user's own tap. The bound lives inside the control (not at call sites) so every future caller — [#657](../codebase/657.md)'s per-code-block copy included — inherits it without needing to know the text is untrusted.

**Touch target — recorded deviation.** The glyph draws at 11×12 (a 12dp tap target); `CopyTouchPadding = 6.dp` inside the clickable widens that to 24dp, growing the meta row from 16dp to 24dp. Still under Material's 48dp guidance — a full `IconButton` would inflate every bubble by ~32dp and visibly miss the frame — the deviation runs toward accessibility and is identical on both roles.

**Why wrap-content, not `fillMaxWidth()`.** The design's meta row is `w-full` inside a shrink-wrapping `Message` column. CSS resolves `w-full` against the *parent's resolved* width, so a short bubble's meta row stays narrow; Compose resolves `fillMaxWidth()` against the *incoming max constraint*, so a literal translation stretched every bubble to the full 272dp lane and destroyed the shrink-wrap — plausible-looking and would have passed a test that didn't check width. The meta row is wrap-content, and `Modifier.align(alignment)` inside the bubble's `Column` puts it on the right side.

`R.string.cd_thread_copy_message` ("Copy this message") is the control's accessible name, in the existing `cd_thread_*` family; the control carries `role = Role.Button`.

### Streaming variant — progressive reveal + blinking caret (since #184)

When `message.isStreaming = true`, the assistant arm routes to a private `StreamingAssistantBody(content, modifier)` instead of the static `MarkdownText(...)` call. The composable derives two pieces of state via `produceState`:

- `revealedLength: State<Int>` keyed on `content`. Producer: `while (value < content.length) { delay(STREAMING_REVEAL_STEP_MS); value = (value + STREAMING_REVEAL_STEP_CHARS).coerceAtMost(content.length) }`. Reveal rate is one character per `STREAMING_REVEAL_STEP_MS = 20L` tick → 50 chars/sec. The `key1 = content` causes the producer to restart from 0 if the content snapshot changes (Phase 4: token-by-token growth from the WS feed).
- `caretVisible: State<Boolean>` keyed on `Unit`. Producer: `while (true) { delay(STREAMING_CARET_BLINK_PERIOD_MS); value = !value }`. `STREAMING_CARET_BLINK_PERIOD_MS = 500L` → 1 Hz toggle / 0.5 Hz full blink cycle. Independent of the reveal — the caret keeps blinking after the prefix is fully revealed until `isStreaming` flips `false`.

Both producers cancel automatically when the composable leaves composition. No `LaunchedEffect`, no `DisposableEffect`, no `viewModelScope` involvement — carried over unchanged through #644's new container.

The two values feed a second private composable `StreamingAssistantBodyView(revealedText, caretVisible, modifier)` (pure rendering, no state) that computes `displayText = revealedText + (if (caretVisible) STREAMING_CARET_GLYPH else "")` and calls `MarkdownText(markdown = displayText, modifier = modifier)`.

**Caret as inline text, not a sibling composable.** `STREAMING_CARET_GLYPH = "▎"` (U+258E LEFT ONE QUARTER BLOCK). The caret is appended to the revealed prefix and flows through `MarkdownText` as ordinary text, inheriting the ambient content colour — since #644 that ambient is the enclosing `Surface(contentColor = …)`, not a `CompositionLocalProvider` the bubble sets up itself (see [Fill vs. hug](#fill-vs-hug-the-streaming-arm-keeps-fillmaxwidth-since-644) below).

**Zero animation cost when not streaming.** Historical messages take the unchanged static `MarkdownText(...)` path — no `produceState`, no coroutine, no extra recomposition.

**Reveal restart on `content` change; lifetime tied to `LazyColumn` item disposal.** Unchanged since #184 — see [Edge cases / limitations](#edge-cases--limitations).

### Fill vs. hug: the streaming arm keeps `fillMaxWidth()`, the finalized arm doesn't (since #644)

The finalized assistant body (`MarkdownText(markdown = message.content)`) no longer takes `Modifier.fillMaxWidth()`. Carrying that modifier over from the unboxed era was a rework-cycle bug: inside a shrink-wrapping `Surface`, `fillMaxWidth()` sets `minWidth = maxWidth`, so a bubble measured against the 272dp lane became a *fixed* width rather than the design's *maximum* — measured on device, a two-character assistant reply and a wrapping one both rendered at 271.24dp, against the frame's own short-instance example (`I533:1956;132:4539`) at 205dp. `CodeBlock` inside `MarkdownText` carries its own `fillMaxWidth()`, so a fenced code block still spans the bubble; only prose hugs.

**The streaming arm is the deliberate exception.** `caretVisible` toggles the rendered string by one glyph twice a second; a hugging streaming bubble would oscillate in width at 2Hz for the whole turn — worst on exactly the short replies the hug exists for. Filling holds the width steady while deltas land, and the bubble settles onto its content in one snap at `turn_end` instead of continuous jitter. The alternative (reserving the caret's width so the blink stops moving the edge) would rework the streaming render path `MarkdownText` drives and was out of scope for #644.

A regression guard pins this: `MessageBubbleTest.shortAssistantBody_hugsItsContent_whileALongOneStillGrowsToTheLane` mounts a short and a long finalized assistant message and reads both bubble widths off `MESSAGE_BUBBLE_TEST_TAG` — the `Surface`, not the body `Text`, since a `Text` hugs its own content whether or not its container does and is the one node that does **not** move when the hug regresses. The paired "long > short" assertion is what stops a blanket shrink from passing, and the hug assertion needs real margin: a lane-pinned bubble measures a fraction *under* the computed lane (271.24 vs 271.43dp) once padding rounds through px, so a bare `short < lane` check passed even in the broken state.

### Spacing / geometry constants

At the top of `MessageBubble.kt`, `internal` (not file-private) since #644 — [`QueuedBacklog`](queued-backlog-section.md) needs the same numbers and used to keep copies precisely because these were unreachable:

```kotlin
internal val MessageAreaRowSpacing = 16.dp     // `Message area` gap-[16px] — between rows
internal val BubbleShape = RoundedCornerShape(6.dp)  // `Message` rounded-[6px], both roles
internal val BubbleHorizontalPadding = 20.dp   // `Message` px-[20px]
internal val BubbleVerticalPadding = 16.dp     // `Message` py-[16px]
internal val BubbleContentSpacing = 12.dp      // `Message` gap-[12px] — body to meta row
internal val MessageContentGutter = 20.dp      // content-area inset each edge
internal val MessageRoleInset = 100.dp         // the role's *opposite* edge, caps a bubble at 272dp
internal const val MESSAGE_BUBBLE_TEST_TAG = "message-bubble"
```

**`UserBubbleShape` and `UserBubbleMaxWidth` are gone** — the pre-#644 asymmetric 20/20/6/20 "tail" corner radius and the 320dp cap are both superseded by the shared `BubbleShape` (uniform 6dp) and the `MessageRoleInset` mechanism above. There is no longer a separate max-width constant for either role: the inset *is* the mechanism, and 272dp is its value at the 412dp reference width.

The streaming constants (`STREAMING_CARET_GLYPH`, `STREAMING_REVEAL_*`, `STREAMING_CARET_BLINK_PERIOD_MS`) are unchanged and stay file-private — nothing outside this file needs them.

Naming note: the shared row-spacing constant is `MessageAreaRowSpacing`, not (as an earlier draft of this ticket's plan called it) `MessageRowVerticalSpacing` — [`ToolCallRow.kt`](./tool-call-row.md) already owns a file-private constant of that exact name, and promoting `MessageBubble.kt`'s to `internal` under the same identifier would have been a package-level conflicting declaration at `ToolCallRow`'s own use site. `ToolCallRow.kt` is owned by [#658](../codebase/658.md) and #644 left it untouched; searching the package for a name before promoting it to `internal` is the general lesson.

### Vertical rhythm and the content gutter — component-side, not the `LazyColumn` consumer's

Each role container's `Row` carries `Modifier.padding(bottom = MessageAreaRowSpacing)`, so the per-row vertical rhythm lives on the component, not on `ThreadScreen`'s eventual `LazyColumn` consumer — same posture as pre-#644. Since #644 the same `Row` also carries the 20dp `MessageContentGutter` on both horizontal edges, because `ThreadScreen`'s `LazyColumn` applies no horizontal padding of its own and this ticket did not touch that screen. [`SessionBoundaryDelimiter`](session-boundary-delimiter.md) picks up the same two constants so all three row kinds in the thread (assistant/user bubble, tool card, session boundary) share one rhythm and one gutter. The `Role.Tool` dispatch arm and [`UnrecognizedMessageRow`](./unrecognized-message-row.md) each apply `MessageContentGutter` at their own call site instead, since neither of those files is owned by this ticket.

### Ignored `Message` fields

- **`id`, `sessionId`** — passed through `Message` for `equals` / recomposition stability and downstream consumption (`ThreadScreen`'s `LazyColumn` keys items by `message.id`), but not visually surfaced here.

`timestamp` is **no longer ignored** — since #644 it renders in every bubble's meta row via `formatShortDateTime`. `isStreaming` is consumed by the assistant arm since #184 — see the streaming-variant section above.

## Configuration

- **Transitive dependencies:** the assistant variant routes through [`MarkdownText`](./markdown-text.md), wired against `org.jetbrains:markdown` (see [ADR 0002](../decisions/0002-markdown-renderer-library.md)). Since #644, `MessageMetaRow.kt` reads `LocalClipboardManager` / `AnnotatedString` (`androidx.compose.ui`) and `java.time.format.DateTimeFormatter` (already on the min-SDK-33 classpath, no desugaring needed — same posture as [`SessionBoundaryDelimiter`](session-boundary-delimiter.md)'s time formatter).
- **One string resource** (since #644): `cd_thread_copy_message` ("Copy this message"), the copy control's accessible name, in the `cd_thread_*` family. User bodies still render plainly and assistant bodies through `MarkdownText` — no role prefix, no fallback copy on the body text itself.
- **One drawable** (since #644): `res/drawable/ic_copy.xml` — single-path, 11×12 viewport, tinted at the call site from `LocalContentColor`, the same idiom `ic_open_in_new.xml` already uses.
- **No theme overrides.** Reads `colorScheme.primaryContainer` / `onPrimaryContainer` (user), `colorScheme.secondaryContainer` / `onSecondaryContainer` (assistant), `typography.bodyMedium` / `bodySmall` directly — see [Token mapping](#token-mapping-figma-roles-against-this-apps-two-schemes). The assistant body's colour now comes from `Surface(contentColor = …)` rather than a `CompositionLocalProvider` the bubble sets up itself; `Box`, `CompositionLocalProvider` and `LocalContentColor` are no longer referenced anywhere in `MessageBubble.kt` as a result — the meta row's de-emphasis and `MarkdownText`'s ambient both come from the one `Surface`.

## Previews

Four `@Preview`s in `MessageBubble.kt`, all `widthDp = 412` except the narrow one, plus one narrow preview added by #644.

**Pair one — sequence rendering.** `MessageBubbleLightPreview` / `MessageBubbleDarkPreview` render a shared `MessageBubblePreviewSequence()` of five messages (User → Assistant → User → Assistant → a short one-line Assistant) inside `Surface { Column { … } }` — no horizontal padding on the wrapping `Column` any more, since the component now owns the design's 20dp gutter itself and the preview viewport is the frame's real 412dp reference width. The trailing short message ("On it.") exists specifically so the hug is visible in the preview: every earlier message in the sequence wraps and reaches the 272dp lane maximum, which made the shrink-wrap invisible under review until a genuinely short reply was added.

**`MessageBubbleNarrowPreview`** (new, #644) — `widthDp = 320`, the peer of `SessionBoundaryDelimiterNarrowPreview`. At 320dp the 20dp gutters and the 100dp role inset leave a 180dp bubble, so the meta row's timestamp-plus-glyph becomes the widest thing in it and sets the bubble's floor — the width at which the design's generous insets bite hardest.

**Pair two — markdown rendering** (added in #129; extended in #184 and #644). `MessageBubbleMarkdownLightPreview` / `MessageBubbleMarkdownDarkPreview` render a half-revealed streaming snapshot followed by the completed markdown fixture, both now routed through the real `MessageContainer` (previously a bare `Box` with its own ambient) — so the preview shows the caret and the meta row exactly where the shipped bubble puts them, at a pinned `PreviewTimestamp = 2026-01-13T12:55:00Z` (the design's own sample moment).

No preview for the `Role.Tool` arm — preview coverage for the tool-call surface lives in [`ToolCallRow.kt`](./tool-call-row.md#previews).

## Testing

`app/src/androidTest/.../components/MessageBubbleTest.kt` (new, #644), the rung-2 component-render layer, with a file-local fake `ClipboardManager` provided through `LocalClipboardManager`:

- `bothRoles_renderBodyAndOwnMetaRow` — both roles render their body text and their own meta row.
- `roleAlignment_userSitsRightOfAssistant_andEachClearsTheOppositeInset` — reads both bodies' rects; the user body sits right of the assistant body and each clears the opposite root edge by at least `MessageRoleInset`.
- `shortAssistantBody_hugsItsContent_whileALongOneStillGrowsToTheLane` — the regression guard for [Fill vs. hug](#fill-vs-hug-the-streaming-arm-keeps-fillmaxwidth-since-644) above, added in the rework cycle.
- `copy_putsOnlyThatMessagesTextOnTheClipboard` / `copy_fromTheUserBubble_putsOnlyTheUserText_onTheClipboard` — tapping one bubble's copy control captures exactly that message's `content`, never the other's.
- `copyControl_carriesItsAccessibleNameAndButtonRole` — addressable by `cd_thread_copy_message`, `Role.Button`.
- `copy_onStreamingMessage_yieldsWhatHasArrived_andTheCaretStillRenders` — a streaming message's caret still renders inside the new container, and its copy control yields the full `content`, not the revealed prefix.

`app/src/test/.../components/MessageMetaRowFormatTest.kt` (new, #644) pins `formatShortDateTime` locale-robustly, the way [`SessionBoundaryDelimiter`](session-boundary-delimiter.md)'s tests already do for `formatShortTime`: composition and order (`joinsLocalizedShortDateAndShortTimeInThatOrder`), the design's separator (`joinsTheTwoHalvesWithTheDesignsSeparator`), locale- and zone-sensitivity computed through the same `DateTimeFormatter.ofLocalized*` API rather than a literal (`followsTheSuppliedLocaleRatherThanAFixedPattern`, `followsTheSuppliedTimeZone`), and that the result never equals the Figma sample literal under an unrelated locale (`neverEmitsTheFigmaSampleLiteralForAnUnrelatedLocale`).

Two pre-existing suites were re-run rather than relaxed across #644's restyle, because both read this surface closely: `ScriptedThreadRenderTest` (the streaming caret glyph through the real fold) and `ScriptedSessionBoundaryTest` (tight text-node rects through the unmerged tree, which a container wrapped around assistant text changes the ownership of — a `Surface` + `Column` adds no semantics node, so the leaf text nodes it addresses survive). Both stayed green unchanged.

## Edge cases / limitations

- **User variant is plain text; assistant variant renders markdown** (since #129, unchanged by #644). User messages render `Text(message.content)` with no parsing. Assistant messages render through [`MarkdownText`](./markdown-text.md): CommonMark element set, code-block styling from #130.
- **Streaming reveal is character-by-character at a fixed rate; no token-batch awareness.** Unchanged since #184 — see [`streaming-assistant-turns.md`](./streaming-assistant-turns.md) for the Phase 4 live-feed behaviour.
- **Streaming state is lost on `LazyColumn` item disposal.** Scrolling a streaming message off-screen disposes the item, cancels both `produceState` coroutines, and forgets `revealedLength`; scrolling back re-mounts and the reveal restarts from 0. Phase-0 acceptable.
- **Caret inside an open markdown construct falls back to plain text.** Unchanged since #184 — see [`MarkdownText`](./markdown-text.md).
- **`Role.Tool` routes to [`ToolCallRow`](./tool-call-row.md) since #131.** The null-safe `?.let` renders nothing if a `Role.Tool` message arrives with `toolCall = null`.
- **RTL.** `Arrangement.spacedBy(0.dp, alignment)` and `Modifier.fillMaxWidth()` respect `LayoutDirection` automatically — in RTL locales the user bubble pins to the left and the assistant bubble to the right. `BubbleShape`'s uniform 6dp corners mean there is no longer an asymmetric "tail" to worry about flipping (the pre-#644 shape's `bottomEnd = 6.dp` notch is gone).
- **The finalized assistant body hugs its content up to the 272dp lane maximum; the streaming body still fills it.** See [Fill vs. hug](#fill-vs-hug-the-streaming-arm-keeps-fillmaxwidth-since-644) above — deliberate, not an oversight, and the one place the two render paths' width behaviour diverges.
- **Clipboard write is bounded, not caught.** `CopyTextControl` truncates to `MAX_CLIPBOARD_CHARS = 100_000` rather than catching a `TransactionTooLargeException` after the fact — see [Meta row and copy control](#meta-row-and-copy-control-messagemetarowkt-since-644).
- **Copy control accessible name is a static string, not message text.** A screen reader announces "Copy this message," not the message content — the control's `contentDescription` never reads from `Message.content`.
- **Unbounded daemon-authored text can still drive a layout-cost DoS on this screen — pre-existing, not addressed by #644.** Neither `Message.content` nor (in the boundary label) `workspaceCwd` is bounded anywhere on the inbound path; both have rendered into unbounded-height `Text` since #128/#135. #644's security review flagged this as out of scope for this ticket (the fix belongs in `RemoteConversationRepository`'s fold or `MobileWireCodec`'s decode, where one bound would cover every render surface) and bounded only its own new sink, the clipboard.

## Related

- Ticket notes: [`../codebase/128.md`](../codebase/128.md), [`../codebase/129.md`](../codebase/129.md), [`../codebase/130.md`](../codebase/130.md), [`../codebase/131.md`](../codebase/131.md), [`../codebase/184.md`](../codebase/184.md), [`../codebase/644.md`](../codebase/644.md)
- Specs: `docs/specs/architecture/128-message-bubble-user-assistant-variants.md`, `docs/specs/architecture/129-markdown-rendering-assistant-messages.md`, `docs/specs/architecture/130-code-block-rendering-syntax-highlighting.md`, `docs/specs/architecture/131-tool-call-collapsed-expanded-component.md`, `docs/specs/architecture/184-streaming-token-reveal-blinking-caret.md`, `docs/specs/architecture/644-message-bubbles-and-copy-actions.md`
- Decisions: [ADR 0002 — markdown renderer library](../decisions/0002-markdown-renderer-library.md) (assistant-variant rendering pipeline), [ADR 0003 — syntax highlighter library](../decisions/0003-syntax-highlighter-library.md) (fenced-code styling)
- Upstream: [data model](./data-model.md) (`Message`, `Role`, `isStreaming`), [Thread screen](./thread-screen.md) (the `LazyColumn(reverseLayout = true)` host, `"msg:${item.message.id}"` keying)
- Component pipeline: [`MarkdownText`](./markdown-text.md) (consumed by the assistant variant since #129; the streaming caret in #184 and the meta row's ambient colour in #644 both ride through the same `Surface.contentColor`); [`ToolCallRow`](./tool-call-row.md) (consumed by the `Role.Tool` arm since #131, gutter applied at the dispatch site since #644); [`SessionBoundaryDelimiter`](./session-boundary-delimiter.md) (shares `MessageContentGutter` / `MessageAreaRowSpacing` and `formatShortTime` since #644); [`QueuedBacklog`](./queued-backlog-section.md) (consumes `BubbleShape` / `BubbleHorizontalPadding` / `BubbleVerticalPadding` / `MessageRoleInset` since #644 rather than copying them); [`UnrecognizedMessageRow`](./unrecognized-message-row.md) (applies `MessageContentGutter` at its own call site since #644)
- Sibling pattern references: [`DiscussionPreviewRow`](./discussion-preview-row.md) (closest stateless-row composable; mirrored preview-pairing shape), [`ConversationRow`](./conversation-row.md), `ArchiveRow.kt`
- Figma: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) — `Message area` (`533:1956`), the shared `Message` component in both containers (`114:3558` assistant / `114:3559` user), `Meta row` (`132:4446`). Both roles now match the frame; the pre-#644 flat-assistant divergence is closed.
- Downstream:
  - [#657](../codebase/657.md) — per-code-block copy control, reusing `CopyTextControl` from `MessageMetaRow.kt` rather than parsing rendered text back out of the UI.
  - [#658](../codebase/658.md) — the tool-row treatment; owns `ToolCallRow.kt` itself.
  - [#672](../codebase/672.md) — the attachment `Slot` inside `Message`.
  - [#681](../codebase/681.md) — the remaining markdown element set.
  - #185 — auto-scroll behaviour that keeps the thread anchored to the bottom as the streaming message grows; consumes the same `Message.isStreaming` contract, no change required inside `MessageBubble`.
