# MessageBubble

Stateless row primitive (#128) rendering a single `Message` in the conversation thread surface. Three visual variants dispatched off `Message.role`: a right-aligned bubble for `Role.User` (plain text) and a left-aligned bubble for `Role.Assistant` (markdown-rendered via [`MarkdownText`](./markdown-text.md) since #129 — CommonMark element set, boxed since #644), both through one shared `Message` component; and a tap-to-expand `surfaceContainerHigh` card for `Role.Tool`, routed via [`ToolCallRow`](./tool-call-row.md) since #131. User and assistant bubbles can end with a **meta row** — that message's own locale-formatted date/time (#644). In the thread only that timestamp stays hidden until tapped, with at most one visible and none on a streaming reply (#1621, revised by #1817). Copy and reply (#1818) are always beside the bubble, left of user messages and right of assistant messages, including streaming replies. Assistant content reveals progressively with a blinking caret when `Message.isStreaming = true` (#184), through stabilized, formatted markdown since #1766. Eventual call site is the `LazyColumn(reverseLayout = true)` body of [`ThreadScreen`](./thread-screen.md).

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). Files: `MessageBubble.kt` (both role bubbles, the streaming pair) and `MessageMetaRow.kt` (the meta row + copy control, since #644 — `internal` rather than file-private so [#657](../codebase/657.md)'s per-code-block copy control can reuse it). Sibling of [`DiscussionPreviewRow`](./discussion-preview-row.md), [`ConversationRow`](./conversation-row.md), `ArchiveRow.kt`.

## What it does

Dispatches on `message.role` with a Kotlin `when`:

- **`Role.User`** → `UserMessageBubble(message, modifier)` — right-aligned, filled from `colorScheme.userBubbleContainer` with `onPrimaryContainer` content, plain unparsed text split at blank-line paragraph breaks.
- **`Role.Assistant`** → `AssistantMessage(message, modifier)` — left-aligned, filled from `colorScheme.assistantBubbleContainer` with `onSecondaryContainer` content, either the static [`MarkdownText`](./markdown-text.md) (finalized) or the private `StreamingAssistantBody` (while `message.isStreaming`).
- **`Role.Tool`** → `message.toolCall?.let { ToolCallRow(toolCall = it, modifier = modifier.padding(start = MessageContentGutter + ToolNestingIndent * toolNestingDepth, end = MessageContentGutter), subagentDepth = toolNestingDepth) }` — routes the unwrapped [`ToolCall`](./data-model.md) payload to [`ToolCallRow`](./tool-call-row.md) since #131. Since #644 this arm also applies the thread's shared content gutter to the modifier it passes down, so the tool card sits on the same inset as the two bubble roles without [`ToolCallRow.kt`](./tool-call-row.md) itself changing — that file is owned by [#658](../codebase/658.md), and this is a caller-side `Modifier.padding`, not an edit to it. **Since #896** the arm also reads `toolNestingDepth` (see [Subagent nesting indent](#subagent-nesting-indent-since-896) below) and steps the start padding in by one `ToolNestingIndent` per level, on top of the gutter; `end` stays a plain `MessageContentGutter`. The null-safe `?.let` still absorbs the data-class invariant (`toolCall` non-null iff `role == Role.Tool`) silently — a `Role.Tool` message with `toolCall = null` (a data-layer bug) renders nothing.

Both bubble roles route through one private `MessageContainer(message, alignment, bubbleColor, bubbleContentColor, body)` — the design's shared `Message` component (Figma `132:*`, inside `Message area` `533:1956`). It is the only place that knows the shape, the padding, the gutter/inset geometry and the meta row; the two role composables differ only in which alignment and which bubble fill and content colour they pass in. See [Shared `Message` container](#shared-message-container-since-644) below.

## Shape

```kotlin
@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    toolNestingDepth: Int = 0,
    attachmentStates: Map<String, AttachmentViewState> = emptyMap(),
    onAttachmentShown: (String) -> Unit = {},
    onRetryAttachment: (String) -> Unit = {},
    onOpenAttachment: (AttachmentTarget) -> Unit = {},
    onSaveAttachment: (AttachmentTarget) -> Unit = {},
    onRequestAttachment: (MessageAttachment, AttachmentAction) -> Unit = { _, _ -> },
    onOpenMarkdownLink: ((String) -> Unit)? = null,
    metaRowVisible: Boolean = true,
    onToggleMetaRow: (() -> Unit)? = null,
)
```

Single `Message` parameter (not pre-split `(text, isUser)`). `ThreadScreen`'s `LazyColumn` already keys each row on `"msg:${item.message.id}"`; #644 did not need to touch that keying, and the side copy control reads `Message.content` directly rather than anything derived from the list key.

**`toolNestingDepth` (since #896)** is read only by the `Role.Tool` arm — see [Subagent nesting indent](#subagent-nesting-indent-since-896) below. The `Role.User` and `Role.Assistant` arms ignore it; the parameter defaults to `0` so every pre-#896 call site (previews, other tests) is unaffected.

**`attachmentStates`, `onAttachmentShown`, `onRetryAttachment` (since #984), `onOpenAttachment`, `onSaveAttachment` (since #985)** are read only by the two bubble roles, never by `Role.Tool` — see [MessageBubble — attachment slot](message-bubble-attachment-slot.md) below. `onOpenAttachment`/`onSaveAttachment` fire only for a `Ready` attachment, carrying an `AttachmentTarget(attachmentId, displayName, mimeType)`; every one of the five defaults (`emptyMap()`, no-op lambdas), so a text-only call site draws every attachment as loading, starts nothing, and offers neither action; `ThreadScreen` is the only caller that supplies real ones, from `ThreadViewModel.attachmentStates` / `onAttachmentShown` / `onRetryAttachment` and its own `rememberAttachmentActions(attachmentStates) { ... }`.

The composable owns no selection state. The host supplies `metaRowVisible` and `onToggleMetaRow`; their defaults keep standalone components and previews showing the row with no bubble tap. Timestamp formatting and the latest gesture callback are remembered inside the container, but which message is selected belongs to `ThreadScreen`. `Message` is a `data class` with all stable fields, so Compose's stability inference skips recompositions on identity-equal and `equals`-equal inputs without any `@Stable` / `@Immutable` annotation.

## How it works

### Role dispatch — exhaustive `when`, no default, no defensive throw

```kotlin
when (message.role) {
    Role.User -> UserMessageBubble(message, modifier)
    Role.Assistant -> AssistantMessage(message, modifier)
    Role.Tool ->
        message.toolCall?.let {
            ToolCallRow(
                toolCall = it,
                modifier = modifier.padding(
                    start = MessageContentGutter + ToolNestingIndent * toolNestingDepth,
                    end = MessageContentGutter,
                ),
                subagentDepth = toolNestingDepth,
            )
        }
}
```

Since #644 the assistant and user arms both take the full `Message` (not just `message.content`), because the shared `MessageContainer` reads `message.timestamp` for the meta row and `message.content` for both the body and the copy text.

The absence of a default `else ->` arm is deliberate: it keeps the Kotlin compiler enforcing exhaustiveness against the `Role` enum, so a future fourth value (e.g. `Role.System`) is flagged at this site as a hard compile-time decision rather than silently absorbed into a fallback. Project-wide pattern for `when (role)` / `when (kind)` dispatchers in `ui/conversations/components/`.

### Subagent nesting indent (since #896)

`ThreadScreen` derives `toolNestingDepths: Map<String, Int>` once per `state.items` change (see [Thread screen § Subagent tool-row nesting](./thread-screen-subagent-tool-rows.md#subagent-tool-row-nesting-896)) and passes each tool row's own depth in as `toolNestingDepth`. The `Role.Tool` arm is the only reader: it adds `ToolNestingIndent * toolNestingDepth` to the row's **start** padding, on top of the existing `MessageContentGutter`; `end` stays a plain gutter, so nesting only steps the row's leading edge, never its trailing one. `ToolNestingIndent` is declared next to `MessageBubble`'s other spacing constants as `private val ToolNestingIndent = MessageAreaRowSpacing` — the Figma frame (`16:8`) has no subagent grouping of its own, so #896 reused the `Message area`'s existing 16dp inter-row gap as the per-level step rather than inventing a new token. The same `toolNestingDepth` value is forwarded to [`ToolCallRow`](./tool-call-row.md) as `subagentDepth`, which is where the row states its own nesting to a screen reader — see [`ToolCallRow` § Subagent step description](./tool-call-row.md#subagent-step-description-since-896).

At `toolNestingDepth = 0` (every non-tool row, and a top-level or unmatched-parent tool row) this arm renders byte-for-byte what it rendered before #896 — the parameter is additive, not a behaviour change to the two bubble roles or to a depth-0 tool row.

### Shared `Message` container (since #644)

`MessageContainer` measures the bubble first with a custom `Layout`, then measures
its action column at the bubble's height. It places the bubble at the role's edge
and the actions beside it: left of user messages, right of assistant messages
(#1817, [Figma 620:1577 / 808:12242](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=620-1577)).
The column is 13dp wide with a 12dp bubble-to-column gap. Copy sits above reply
(#1818), glyph centres 25dp apart, and the pair is centred vertically beside the
bubble. Tool and queued rows have no actions.

The 20dp gutters leave 372dp at the 412dp reference width. Delivered bubbles reserve
a private 40dp far-side inset plus the 12dp gap and 13dp column, giving a **307dp**
bubble maximum (215dp at 320dp). There is no extra phone-width cap. Short finished
bodies hug content; streaming bodies retain their fill behavior. The shared
`MessageRoleInset = 100.dp` still serves queued rows and must not be changed to
adjust delivered bubbles. Tool rows keep their existing geometry.

The bubble's column aligns content to Start for both roles, with the timestamp
alone aligned to the role's side. It retains 20dp horizontal and 16dp vertical
padding, 12dp child spacing, 6dp corners and a 4dp shadow. Attachments precede the
body; a finished non-empty body owns its selection container. The optional
`MessageMetaRow(timestamp, modifier)` is timestamp text only, with no copy-width
reservation. Keep it wrap-content: `fillMaxWidth()` would resolve against incoming
constraints and pin even short bubbles to the full lane.

### Body selection (since #1638)

Long-pressing a finished user or assistant body starts system text selection,
including fenced code. Each bubble owns its selection region; it cannot extend
into another bubble. Streaming bodies stay unselectable so arriving text cannot
invalidate selection offsets. Attachments and the meta row are outside the region:
attachment long press still saves, and the side button copies the whole source.
Links and code-block copy buttons keep their existing actions. The five inert
rows (`UnrecognizedMessageRow`, `ThreadPermissionModal`, `BannerNoticeRow`,
`StoppedTurnRow`, `ModelRefusalRow`) remain without selection containers.

`SelectionContainer` stacks direct children, so the body needs an inner `Column`
with `BubbleContentSpacing` to keep user paragraphs 12dp apart. Neither wrapper
has a width modifier, preserving [the finished bubble's hug](#fill-vs-hug-the-streaming-arm-keeps-fillmaxwidth-since-644).
Keep the wrapper behind `hasNoBody()` as well as the streaming guard: an
attachment-only body's lambda emits nothing, but wrapping it produces a zero-height
child that still receives spacing on both sides. This doubles the attachments-to-meta
gap from 12dp to 24dp. Counting empty text nodes cannot detect that regression;
measure the gap. `hasNoBody()` means attachments exist and content is blank;
a text-only message retains its body even when empty.

The selection handles and Copy toolbar are system UI; the bubble has no new
at-rest appearance. The [design inventory](../../../app/src/androidTest/assets/design-1220/README.md#thread-composer-and-footer)
records the no-separate-frame decision. When combining selection with a surface
tap that toggles metadata (#1621), check a pointer tap inside the selection area
still toggles the row.

`MessageBubbleSelectionTest` uses a recording context-menu provider: it checks
selection and clipboard behavior inside the component, but does not exercise
Android's actual selection handles and Copy menu in `ThreadScreen`. That device
path remains unverified. [#1674](https://github.com/pyrycode/pyrycode-mobile/issues/1674)
owns a rung-3 `InteractiveStreamE2ETest` scenario selecting a word from a finished
real-Claude reply through the actual Android Copy menu and reading the platform
clipboard, plus a rung-4 `DeterministicInteractiveStreamE2ETest` twin. Scenario
implementation, [ladder coverage documentation](../../e2e-interactive-stream.md)
and dispatcher-owned live evidence remain pending in that follow-up.

### Attachment slot (since #984)

Split into [MessageBubble — attachment slot](message-bubble-attachment-slot.md) on 2026-09-24 to keep this document under the 50000-byte cap the docs guard enforces. The slot's view states, name/MIME resolution, image and file row rendering, the retrieval load lifecycle, and the ready-only tap-to-open/long-press-to-save actions (#985, `onOpenAttachment`/`onSaveAttachment`) moved there verbatim, keeping their heading and anchors; the one link this section carried out, to [Fill vs. hug](#fill-vs-hug-the-streaming-arm-keeps-fillmaxwidth-since-644), now points back here from the child document instead of "above".

### The #128 divergence closes here

`MessageBubble`'s assistant body was unboxed, flat text since #128; `message-bubble.md` used to record that as a deliberate, unresolved divergence from the Figma frame, with `needs-rework:po` as the escalation path for reconciling it. **#644 is that reconciliation, resolved in favour of the design.** Both roles now render through the same boxed `Message` component described above; there is no more flat-vs-boxed asymmetry between the two roles, and the escalation note is retired. The asymmetry that remains is only alignment and colour — which side of the lane, and which bubble fill and content colour.

### Token mapping — Figma roles against this app's two schemes

The supplied `16:8` adaptation is drawn against this app's **dark** palette.
Since [#1161](../../specs/architecture/1161-dark-message-bubble-fills.md), the bubble
fills match it in static dark mode (`darkTheme && !dynamicColor`). Static light
and wallpaper-derived light/dark palettes retain their selected scheme's
`primaryContainer` (user) and `secondaryContainer` (assistant). This supersedes
only #644's static-dark fill divergence; the current message presentation uses:

| Figma role | Design hex | Used here | Why |
|---|---|---|---|
| Assistant fill `Schemes/on-primary-fixed` | `#001D34` | `colorScheme.assistantBubbleContainer` | Static dark uses `assistantBubbleContainerDark` from `BubbleColors.kt`, for both streaming and finalized replies. Static light and wallpaper modes use `secondaryContainer`. |
| Assistant body `Schemes/on-secondary-container` | `#D6E4F7` | `colorScheme.onSecondaryContainer` | As named. |
| User fill `Schemes/on-primary` | `#003355` | `colorScheme.userBubbleContainer` | Static dark uses `onPrimaryDark` from `Color.kt`; static light and wallpaper modes use `primaryContainer`. [`QueuedMessageRow`](queued-backlog-section.md) shares this base fill beneath its existing 0.6 row opacity. |
| User body `Schemes/on-primary-container` | `#CFE4FF` | `colorScheme.onPrimaryContainer` | As named. |
| Timestamp text `Schemes/inverse-primary` | `#32628D` | `LocalContentColor.current.copy(alpha = META_CONTENT_ALPHA)` (0.8) | **Accessibility deviation:** Figma's tone measures about 2–3:1 on these fills. The enclosing surface provides `onPrimaryContainer` for user and `onSecondaryContainer` for assistant; 80% of that role clears 4.5:1 on both. |

Side copy (#1817) and reply (#1818) draw their Figma glyphs alone, no backing, tinted
`colorScheme.primary`; timestamp contrast treatment above remains inside the
bubble. See [the action contrast resolution](#meta-row-and-copy-control-messagemetarowkt-since-644).

The bubble-specific roles leave global Material containers unchanged (static dark:
`primaryContainer = #134A74`, `secondaryContainer = #3A4857`). Static dark now maps
`colorScheme.onPrimaryFixed` to the same `#001D34`, but the assistant bubble keeps
its scoped fill: light and wallpaper-colour bubbles still use `secondaryContainer`.
The design was inspected on 2026-09-29; its last-modified date was unavailable.
[The 412 × 892 comparison](https://github.com/pyrycode/pyrycode-mobile/blob/44ac0889/app/src/androidTest/assets/thread-message-1207/long-text-side-by-side.png)
and [labelled overlay](https://github.com/pyrycode/pyrycode-mobile/blob/44ac0889/app/src/androidTest/assets/thread-message-1207/long-text-overlay-difference.png)
show the geometry and the deliberate metadata contrast difference.

### Meta row and copy control (`MessageMetaRow.kt`, since #644)

`ThreadScreen` owns one saveable timestamp selection (#1621). A finished bubble tap
selects it, a second tap clears it, and a tap on another transfers visibility.
Selection survives rotation and the back stack. Only the timestamp hides by
default (#1817); copy does not change selection. Streaming hides the timestamp
and disables toggling in both the container and screen, while copy stays available.
The first tap after completion can reveal time. Omitting the timestamp also removes
its column gap and height. See the [design inventory](../../../app/src/androidTest/assets/design-1220/README.md#messages-and-tools).

The surface uses `pointerInput { detectTapGestures }` with `rememberUpdatedState`
and non-merging semantics. A parent `clickable` would merge descendant text,
changing TalkBack stops and paragraph-bound assertions. The bubble exposes
“Show time” / “Hide time” and describes the hidden timestamp as “Sent …”. It has
no custom copy action. Finished and streaming messages each expose the side copy
as its own “Copy this message” Button. Manual TalkBack traversal remains unchecked;
semantics tests establish the nodes and labels, not spoken order.

Links, code-block copy and attachments consume their own taps. Loading, NotFound
and Failed attachments use a no-op detector without click semantics so their taps
do not fall through into timestamp selection; Retry retains its action. See
[attachment open/save](message-bubble-attachment-slot.md#open-and-save-since-985).

`MessageMetaRow` contains only `bodySmall` date/time text. `formatShortDateTime`
uses localized SHORT date and time in the device's zone and locale, joined by
` - ` in date-before-time order. Its remembered formatter keys include instant,
zone and locale. The locale-aware short date can use a two-digit year even where
the Figma sample shows four; do not replace it with a literal pattern.

Side copy writes current `Message.content`, including markdown source and arrived
streaming text ahead of progressive display, through `setBoundedText`. The shared
100,000-character bound protects the Binder clipboard transaction. Fenced-code
`CopyTextControl` remains compact with its existing ambient tint and padding, and
uses the same safeguard; it copies the block's source rather than the message.

Each side action starts from a 48×48dp target centred on its glyph (11×12dp
`ic_copy`, 13×12dp `ic_reply`), without expanding the drawn 13dp column or
bubble. The two targets meet at the midpoint between glyph centres; every outer
edge sits 24dp from its centre, so the pair's touch layout is 48×73dp. A tap just
above the midpoint copies and just below it replies. `MessageActions` provides a
zero `minimumTouchTargetSize` so neither target's platform expansion reaches into
the other. The overflow is not clipped and is placed after the bubble so taps in
the overlap act rather than toggle time. On a short row the pair overflows the
row itself, so the copy glyph sits 12.5dp above the row's centre.

Reply (#1818) hands the immutable `Message` at tap time to `onReply`; the default
is inert, so previews and standalone mounts need no fixture. `ThreadScreen` turns
it into a staged composer quote, see
[composer draft ownership](thread-screen-composer-drafts-and-attachments.md#composer-draft-ownership).
TalkBack exposes it as its own “Reply to this message” Button. Neither action
toggles the timestamp.

The glyph uses `colorScheme.primary`. Figma names `Schemes/Inverse Primary` for
this icon, but that role is paired with `inverseSurface` — the opposite theme's
surface, not this screen's own background — so it measured 1.61:1 in static
light. A centered 13×14dp `inverseSurface` backing briefly patched that to 3:1,
but in dark theme `inverseSurface` is near-white, so the patch showed as a stray
light chip behind the glyph; that backing is removed. `primary` is the same
accent hue read through the matching theme instead of the inverted one: static
light pairs `#32628D` on the `#F8F9FF` thread background (6.1:1); static dark
pairs `#9DCBFC` on the static-dark thread canvas `#0B0E11` (11.4:1). Check
glyph-on-thread-background contrast directly, using the actual thread
background, across static and wallpaper light/dark. See
[palette and geometry coverage](message-bubble-testing.md#testing). #1818's
ticket asked for reply in the old `inversePrimary` tint over an `inverseSurface`
backing; reply follows `primary` without backing instead, matching copy.

### Streaming variant — progressive reveal + blinking caret (since #184)

When `message.isStreaming = true`, the assistant arm routes to the private
`StreamingAssistantBody` with content, initial reveal length and the markdown-link
callback instead of the static `MarkdownText(...)` call.

`MessageBubble.threadOpenedAt` defaults to `null`, keeping standalone bubbles'
zero-start reveal. The thread supplies its remembered opening time: when `message.timestamp`
precedes it, the body seeds `revealedLength` with the current `content.length`.
Otherwise it starts at zero. Pre-open streaming text is therefore immediate on
opening/reopening, while post-open appends reveal progressively from the retained
prefix. The seed only initializes a fresh body; changing content or replacing a
synthetic row with a repository row under the same lazy key does not reseed it.
See [first-arrival timestamps](streaming-assistant-turns.md#the-fold).

The composable derives two pieces of state via `produceState`:

- `revealedLength: State<Int>` keyed on `Unit` since [#1754](../../specs/architecture/1754-word-reveal-catch-up.md). The composition-lifetime producer reads the latest `content` through `rememberUpdatedState` every `STREAMING_REVEAL_STEP_MS = 33L` tick. Small backlogs reveal one whitespace-delimited word per tick (about 30 words/sec); `nextStreamingRevealLength` includes adjacent whitespace and the last arrived word even without trailing whitespace. A 15-tick catch-up budget divides the remaining words across the remaining ticks, rounding up, so larger backlogs reveal several words per step. While behind, the countdown decreases on each tick; it resets only once caught up. Arrivals preserve the visible prefix, delay and outstanding countdown, and the deadline tick reveals all currently arrived text: each snapshot catches up within 495 ms of reveal-clock time, with presentation adding a frame.
- `caretVisible: State<Boolean>` keyed on `Unit`. Producer: `while (true) { delay(STREAMING_CARET_BLINK_PERIOD_MS); value = !value }`. `STREAMING_CARET_BLINK_PERIOD_MS = 500L` → 1 Hz toggle / 0.5 Hz full blink cycle. Independent of the reveal — the caret keeps blinking after the prefix is fully revealed until `isStreaming` flips `false`.

Both producers cancel automatically when the composable leaves composition. No `LaunchedEffect`, no `DisposableEffect`, no `viewModelScope` involvement — carried over unchanged through #644's new container.

The two values feed a second private composable `StreamingAssistantBodyView(revealedText, caretVisible, onOpenMarkdownLink, modifier)` (pure rendering, no state). Since #1766 it calls `StreamingMarkdownText`, which re-parses only the growing tail, reuses completed blocks and shows unfinished constructs without their punctuation; see [MarkdownText, streaming](markdown-text.md#streaming-since-1766).

**The caret is drawn beside the parsed text, never inside it (since #1766).** `STREAMING_CARET_GLYPH = "▎"` (U+258E LEFT ONE QUARTER BLOCK). Until #1766 it was appended to the revealed source, so it could become code content or part of a link target. It is now appended to the built text of the trailing paragraph, heading or table cell, and drawn only while the blink is on, as before. When the reply ends in something else, such as an open fence, it takes its own line. It inherits the ambient content colour of the enclosing `Surface(contentColor = …)`.

**Zero animation cost when not streaming.** Historical messages take the unchanged static `MarkdownText(...)` path — no `produceState`, no coroutine, no extra recomposition.

**Prefix retention alone does not preserve the clock.** A content-keyed `produceState` retains its state value when the key changes; the historical claim that every delta reset the prefix to zero was incorrect. It does restart the producer, cancelling the pending delay and resetting local countdowns. Arrivals faster than 33 ms can therefore starve a content-keyed reveal indefinitely. Keep the stable producer and latest-content state together, and exercise repeated arrivals as well as fixed backlogs — see [Testing](message-bubble-testing.md#testing). Disposal still ends both producers; see [Edge cases / limitations](#edge-cases--limitations).

### Fill vs. hug: the streaming arm keeps `fillMaxWidth()`, the finalized arm doesn't (since #644)

The finalized assistant body (`MarkdownText(markdown = message.content)`) no longer takes `Modifier.fillMaxWidth()`. Carrying that modifier over from the unboxed era was a rework-cycle bug: inside a shrink-wrapping `Surface`, `fillMaxWidth()` sets `minWidth = maxWidth`, so a bubble measured against the then-272dp lane became a *fixed* width rather than the design's *maximum* — measured on device, a two-character assistant reply and a wrapping one both rendered at 271.24dp, against the frame's own short-instance example (`I533:1956;132:4539`) at 205dp. `CodeBlock` inside `MarkdownText` carries its own `fillMaxWidth()`, so a fenced code block still spans the bubble; only prose hugs.

**The streaming arm is the deliberate exception.** Before #1766, `caretVisible` toggled the rendered string by one glyph twice a second, and since then the reveal still widens the text every tick; a hugging streaming bubble would oscillate in width for the whole turn — worst on exactly the short replies the hug exists for. Filling holds the width steady while deltas land, and the bubble settles onto its content in one snap at `turn_end` instead of continuous jitter. The alternative (reserving the caret's width so the blink stops moving the edge) would rework the streaming render path `MarkdownText` drives and was out of scope for #644.

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
internal val MessageRoleInset = 100.dp         // queued rows retain their original opposite inset
internal const val MESSAGE_BUBBLE_TEST_TAG = "message-bubble"
```

`ToolNestingIndent` (since #896, `private val ToolNestingIndent = MessageAreaRowSpacing`) is declared just above this block rather than inside it — it aliases the existing row-spacing constant rather than being a new dp literal, so the two can't drift out of sync.

**`UserBubbleShape` and `UserBubbleMaxWidth` are gone** — the pre-#644 asymmetric 20/20/6/20 "tail" corner radius and the 320dp cap are both superseded by the shared `BubbleShape` (uniform 6dp) and the delivered inset plus action-column reservation above. There is no longer a separate max-width constant for either role: the inset *is* the mechanism, and 307dp is the delivered bubble maximum at the 412dp reference width.

The blink period stays file-private. The caret glyph is `internal` in `MarkdownText.kt` since #1766, shared by the streaming renderer and its tests. The word rate and catch-up budget are also private; `STREAMING_REVEAL_STEP_MS` and the pure `nextStreamingRevealLength` helper are internal so the step tests can pin cadence and word boundaries.

Naming note: the shared row-spacing constant is `MessageAreaRowSpacing`, not (as an earlier draft of this ticket's plan called it) `MessageRowVerticalSpacing` — [`ToolCallRow.kt`](./tool-call-row.md) already owns a file-private constant of that exact name, and promoting `MessageBubble.kt`'s to `internal` under the same identifier would have been a package-level conflicting declaration at `ToolCallRow`'s own use site. `ToolCallRow.kt` is owned by [#658](../codebase/658.md) and #644 left it untouched; searching the package for a name before promoting it to `internal` is the general lesson.

### Vertical rhythm and the content gutter — component-side, not the `LazyColumn` consumer's

Each role container's `Layout` carries `Modifier.padding(bottom = MessageAreaRowSpacing)`, so the per-row vertical rhythm lives on the component, not on `ThreadScreen`'s eventual `LazyColumn` consumer — same posture as pre-#644. Since #644 the same container also carries the 20dp `MessageContentGutter` on both horizontal edges, because `ThreadScreen`'s `LazyColumn` applies no horizontal padding of its own and this ticket did not touch that screen. [`SessionBoundaryDelimiter`](session-boundary-delimiter.md) picks up the same two constants so all three row kinds in the thread (assistant/user bubble, tool card, session boundary) share one rhythm and one gutter. The `Role.Tool` dispatch arm and [`UnrecognizedMessageRow`](./unrecognized-message-row.md) each apply `MessageContentGutter` at their own call site instead, since neither of those files is owned by this ticket.

### Ignored `Message` fields

- **`id`, `sessionId`** — passed through `Message` for `equals` / recomposition stability and downstream consumption (`ThreadScreen`'s `LazyColumn` keys items by `message.id`), but not visually surfaced here.

`timestamp` is **no longer ignored** — since #644 it renders in the visible meta row via `formatShortDateTime`, and since #1621 also in the hidden-row accessibility description. `isStreaming` is consumed by the assistant arm since #184 — see the streaming-variant section above. `attachments` is **no longer ignored** either — since #984 a non-empty list renders through the [Attachment slot](#attachment-slot-since-984) above and also decides `hasNoBody()`.

## Configuration

- **Transitive dependencies:** the assistant variant routes through [`MarkdownText`](./markdown-text.md), wired against `org.jetbrains:markdown` (see [ADR 0002](../decisions/0002-markdown-renderer-library.md)). Since #644, `MessageMetaRow.kt` reads `LocalClipboardManager` / `AnnotatedString` (`androidx.compose.ui`) and `java.time.format.DateTimeFormatter` (already on the min-SDK-33 classpath, no desugaring needed — same posture as [`SessionBoundaryDelimiter`](session-boundary-delimiter.md)'s time formatter).
- **Accessibility strings:** `cd_thread_copy_message` ("Copy this message") and `cd_thread_reply_message` ("Reply to this message", #1818), the side actions' accessible names, in the `cd_thread_*` family. User bodies still render plainly and assistant bodies through `MarkdownText` with no role prefix. Since #1621, `cd_thread_message_sent` describes the hidden timestamp, and `thread_message_show_details` / `thread_message_hide_details` label the bubble action; the labels now say “Show time” / “Hide time” (#1817), and copy is an independent side Button without a bubble custom action.
- **Drawables:** `res/drawable/ic_copy.xml` (since #644) — single-path, 11×12 viewport, tinted at the call site from `primary` for side copy and `LocalContentColor` for code copy, the same idiom `ic_open_in_new.xml` already uses. `res/drawable/ic_reply.xml` (#1818) is Figma's 13×12 `reply-solid-full`, tinted `primary`.
- **Bubble theme roles:** wrap consumers in `PyrycodeMobileTheme`, which provides
  `LocalUserBubbleContainer` / `LocalAssistantBubbleContainer` through the
  `ColorScheme.userBubbleContainer` / `assistantBubbleContainer` extensions in
  `ui/theme/BubbleColors.kt`. The locals require this provider. The theme resolves
  the exception from its effective `darkTheme` and `dynamicColor` arguments;
  bubble consumers do not read system dark mode. The app root supplies the static
  dark fills even on a light system. Explicit light and wallpaper themes in
  isolated tests and previews still use their selected scheme's containers — see [Token mapping](#token-mapping-figma-roles-against-this-apps-two-schemes).
- **Content styling:** `onPrimaryContainer` (user), `onSecondaryContainer`
  (assistant), and `typography.bodyMedium` / `bodySmall` remain unchanged. The
  shared `Surface(contentColor = …)` supplies the assistant markdown ambient and
  the meta row's content colour; metadata applies its existing 0.8 opacity.
- **Attachment rendering** (since #984, `MessageAttachments.kt` beside this file): `android.graphics.ImageDecoder` for off-main-thread thumbnail decode at a capped target size — no third-party image library. Two new string families in the `thread_attachment_*` group (`unnamed`, `loading`, `not_found`, `failed`, `retry`); no new drawable, since the file row reuses `ic_attachment_file` from the composer strip (#933).

## Previews

Four `@Preview`s in `MessageBubble.kt`, all `widthDp = 412` except the narrow one, plus one narrow preview added by #644.

**Pair one — sequence rendering.** `MessageBubbleLightPreview` / `MessageBubbleDarkPreview` render a shared `MessageBubblePreviewSequence()` of five messages (User → Assistant → User → Assistant → a short one-line Assistant) inside `Surface { Column { … } }` — no horizontal padding on the wrapping `Column` any more, since the component now owns the design's 20dp gutter itself and the preview viewport is the frame's real 412dp reference width. The trailing short message ("On it.") exists specifically so the hug is visible in the preview: every earlier message in the sequence wraps and reaches the 307dp lane maximum, which made the shrink-wrap invisible under review until a genuinely short reply was added.

**`MessageBubbleNarrowPreview`** (new, #644) — `widthDp = 320`, the peer of `SessionBoundaryDelimiterNarrowPreview`. At 320dp the 20dp gutters, 40dp delivered inset and 25dp action reservation leave a 215dp bubble maximum. The timestamp wraps independently without a copy-width reservation.

**Pair two — markdown rendering** (added in #129; extended in #184 and #644). `MessageBubbleMarkdownLightPreview` / `MessageBubbleMarkdownDarkPreview` render a half-revealed streaming snapshot followed by the completed markdown fixture, both now routed through the real `MessageContainer` (previously a bare `Box` with its own ambient) — so the preview shows the caret and the meta row exactly where the shipped bubble puts them, at a pinned `PreviewTimestamp = 2026-01-13T12:55:00Z` (the design's own sample moment).

No preview for the `Role.Tool` arm at depth 0 — preview coverage for the tool-call surface itself lives in [`ToolCallRow.kt`](./tool-call-row.md#previews). **Since #896**, `MessageBubblePreviewSequence()` appends three `Role.Tool` messages at `toolNestingDepth = 0, 1, 2` (an `Agent` call, a `Task` call one level in, and a `Grep` call two levels in — `PreviewToolNesting`), so the pair-one previews also show the indent step at each level, light and dark.

## Testing

See [MessageBubble — testing](message-bubble-testing.md#testing) for selection-menu
fixtures, metadata gestures, palette and geometry guards, and attachment coverage.

## Edge cases / limitations

- **User variant is plain text; assistant variant renders markdown** (since #129, unchanged by #644). User blank-line paragraphs have a 12dp gap while single line breaks remain literal. Assistant messages render through [`MarkdownText`](./markdown-text.md).
- **Word boundaries follow arrived whitespace, not token boundaries.** A final arrived word is revealed even without trailing whitespace, so later deltas may extend that word. A 2000-character string with no whitespace reveals in one step. Large backlogs accelerate beyond the nominal one-word cadence to meet the catch-up deadline — see [Streaming assistant turns](streaming-assistant-turns.md#lifecycle-errors-edge-cases).
- **Streaming state is lost on `LazyColumn` item disposal.** Disposing a streaming item cancels both `produceState` coroutines and forgets `revealedLength`; re-mounting uses a new catch-up budget and the timestamp-based seed: full current text for a row predating the thread opening, zero for a post-open row or a standalone bubble without `threadOpenedAt`. Reopening the thread captures a fresh opening time and shows all arrived text immediately. Appended content during the same composition preserves the prefix and both clocks. Finalization removes the streaming body and renders the full static markdown immediately.
- **Unfinished constructs show their text without punctuation (since #1766).** An open `**bold`, code span, link target or strikethrough in the trailing text renders plain and untappable, and a pipe header waiting for its delimiter row shows its cells separated by spaces. A construct never closed renders literally once the reply is final. See [`MarkdownText` streaming](./markdown-text.md#streaming-since-1766).
- **`Role.Tool` routes to [`ToolCallRow`](./tool-call-row.md) since #131.** The null-safe `?.let` renders nothing if a `Role.Tool` message arrives with `toolCall = null`.
- **RTL.** The custom layout uses `placeRelative` with role alignment — in RTL locales the user bubble pins to the left and the assistant bubble to the right. `BubbleShape`'s uniform 6dp corners mean there is no longer an asymmetric "tail" to worry about flipping (the pre-#644 shape's `bottomEnd = 6.dp` notch is gone).
- **The finalized assistant body hugs its content up to the 307dp lane maximum; the streaming body still fills it.** See [Fill vs. hug](#fill-vs-hug-the-streaming-arm-keeps-fillmaxwidth-since-644) above — deliberate, not an oversight, and the one place the two render paths' width behaviour diverges.
- **Explicit copy controls are bounded; system selection Copy is not.** `CopyTextControl` truncates to `MAX_CLIPBOARD_CHARS = 100_000` rather than catching a `TransactionTooLargeException` after the fact. System selection Copy bypasses this cap; selecting all of a very long assembled reply can overflow the Binder clipboard transaction. This is accepted for user-chosen selections (#1638) — see [Meta row and copy control](#meta-row-and-copy-control-messagemetarowkt-since-644).
- **Copy control accessible name is a static string, not message text.** A screen reader announces "Copy this message," not the message content — the control's `contentDescription` never reads from `Message.content`.
- **Unbounded daemon-authored text can still drive a layout-cost DoS on this screen — pre-existing, not addressed by #644.** Neither `Message.content` nor (in the boundary label) `workspaceCwd` is bounded anywhere on the inbound path; both have rendered into unbounded-height `Text` since #128/#135. #644's security review flagged this as out of scope for this ticket (the fix belongs in `RemoteConversationRepository`'s fold or `MobileWireCodec`'s decode, where one bound would cover every render surface) and bounded only its own new sink, the clipboard.
- **Code-block language labels are selectable.** Select all can include the language label with the code. The block's own copy button still copies only its code source. Excluding the label would require `DisableSelection` in `MarkdownText`.

## Related

- [Selectable message bubble spec](../../specs/architecture/1638-selectable-message-bubble-text.md): selection scope, security review and test setup revisions.
- Ticket notes: [`../codebase/128.md`](../codebase/128.md), [`../codebase/129.md`](../codebase/129.md), [`../codebase/130.md`](../codebase/130.md), [`../codebase/131.md`](../codebase/131.md), [`../codebase/184.md`](../codebase/184.md), [`../codebase/644.md`](../codebase/644.md)
- Specs: `docs/specs/architecture/128-message-bubble-user-assistant-variants.md`, `docs/specs/architecture/129-markdown-rendering-assistant-messages.md`, `docs/specs/architecture/130-code-block-rendering-syntax-highlighting.md`, `docs/specs/architecture/131-tool-call-collapsed-expanded-component.md`, `docs/specs/architecture/184-streaming-token-reveal-blinking-caret.md`, `docs/specs/architecture/644-message-bubbles-and-copy-actions.md`, `docs/specs/architecture/896-nest-subagent-tool-rows.md`, `docs/specs/architecture/984-message-attachments-in-bubbles.md`, `docs/specs/architecture/985-open-and-save-message-attachment.md`
- Decisions: [ADR 0002 — markdown renderer library](../decisions/0002-markdown-renderer-library.md) (assistant-variant rendering pipeline), [ADR 0003 — syntax highlighter library](../decisions/0003-syntax-highlighter-library.md) (fenced-code styling)
- Upstream: [data model](./data-model.md) (`Message`, `Role`, `isStreaming`, `attachments`), [Thread screen](./thread-screen.md) (the `LazyColumn(reverseLayout = true)` host, `"msg:${item.message.id}"` keying), [Attachment retrieval](./attachment-retrieval.md) (`retrieveAttachment`, `AttachmentRetrievalResult`, this ticket's `Ready`/`NotFound`/`Failed` mapping), [Thread screen — composer drafts and attachments](./thread-screen-composer-drafts-and-attachments.md) (`ComposerDraftStore.recordSentOriginals` / `sentOriginal`, `AttachmentReader.canRead` — the two calls [Load lifecycle](#load-lifecycle-since-984) resolves between)
- Component pipeline: [`MarkdownText`](./markdown-text.md) (consumed by the assistant variant since #129; the streaming caret in #184 and the meta row's ambient colour in #644 both ride through the same `Surface.contentColor`); [`ToolCallRow`](./tool-call-row.md) (consumed by the `Role.Tool` arm since #131, gutter applied at the dispatch site since #644); [`SessionBoundaryDelimiter`](./session-boundary-delimiter.md) (shares `MessageContentGutter` / `MessageAreaRowSpacing` and `formatShortTime` since #644); [`QueuedBacklog`](./queued-backlog-section.md) (consumes `BubbleShape` / `BubbleHorizontalPadding` / `BubbleVerticalPadding` / `MessageRoleInset` since #644 rather than copying them); [`UnrecognizedMessageRow`](./unrecognized-message-row.md) (applies `MessageContentGutter` at its own call site since #644)
- Sibling pattern references: [`DiscussionPreviewRow`](./discussion-preview-row.md) (closest stateless-row composable; mirrored preview-pairing shape), [`ConversationRow`](./conversation-row.md), `ArchiveRow.kt`
- Figma: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) — `Message area` (`533:1956`), the shared `Message` component in both containers (`114:3558` assistant / `114:3559` user), `Meta row` (`132:4446`). Both roles now match the frame; the pre-#644 flat-assistant divergence is closed.
- Downstream:
  - [#657](../codebase/657.md) — per-code-block copy control, reusing `CopyTextControl` from `MessageMetaRow.kt` rather than parsing rendered text back out of the UI.
  - [#658](../codebase/658.md) — the tool-row treatment; owns `ToolCallRow.kt` itself.
  - [#672](../codebase/672.md) — the attachment `Slot` inside `Message`, split into **#984** (rendering and the load lifecycle) and **#985** (opening and saving a ready attachment) — both now in [MessageBubble — attachment slot](message-bubble-attachment-slot.md).
  - [#681](../codebase/681.md) — the remaining markdown element set.
  - #185 — auto-scroll behaviour that keeps the thread anchored to the bottom as the streaming message grows; consumes the same `Message.isStreaming` contract, no change required inside `MessageBubble`.
  - **#896** — subagent tool-row nesting, split from #658: added `toolNestingDepth` to the `Role.Tool` arm only (see [Subagent nesting indent](#subagent-nesting-indent-since-896) above); the `Role.User` / `Role.Assistant` arms and every other behaviour in this file are unchanged.
