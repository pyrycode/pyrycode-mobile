# Spec — token-reveal animation + blinking caret on streaming assistant messages (#184)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt:38-49` — `MessageBubble` dispatch over `Role`. The `Role.Assistant` arm currently passes `message.content` to `AssistantMessage`. This ticket changes the dispatch to pass the full `Message` so the streaming branch can read `isStreaming` and `content` together.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt:82-102` — current `AssistantMessage(content, modifier)`. The body is a `Box` containing a `CompositionLocalProvider(LocalContentColor provides onSurface) { MarkdownText(...) }`. The streaming variant is added inside this same private composable — keep the outer `Box` + `CompositionLocalProvider` wrapper so the surrounding spacing and color contract is unchanged.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt:104-258` — existing preview helpers (`previewMessage`, `MessageBubblePreviewSequence`, `MARKDOWN_PREVIEW_FIXTURE`, `MessageBubbleMarkdownPreviewBody`, the four `@Preview` composables). AC5 extends this preview surface with a streaming-vs-completed pair in both themes; mirror the existing Light/Dark pattern.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt:69-87` — the `MarkdownText(markdown, modifier)` entry point. **No change to this file in #184.** The streaming wrapper calls `MarkdownText(content.take(revealedLength) + caretIfBlinkingOn)` and lets the existing renderer do everything it already does (memoised parse via `remember(markdown)`, paragraph dispatch, code-fence highlighting from #130). The caret glyph rides through as ordinary text in whatever block the revealed prefix ends in.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt:1-14` — `Message.isStreaming: Boolean` already exists (added before #128). This ticket consumes it for the first time; the data class is unchanged.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:362-372` — `SeedMessage` data class + `seedMsg(role, content, timestamp)` helper. Currently every seeded `Message` is constructed with `isStreaming = false` (lines 420, 431, 488). To satisfy AC4 the seed pipeline learns to carry an `isStreaming` flag through to the produced `Message`.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:265-294` — the `seed-channel-pyrycode-mobile` block. The "current session" message list ends at `2026-05-10T09:00:00Z` with a completed assistant reply; the streaming seed slots in here as the most recent assistant message in that channel (most-recently-used Pyrycode Mobile channel, hits the thread that demos this ticket).
- `docs/specs/architecture/128-message-bubble-user-assistant-variants.md:39` — load-bearing scope statement: "**No streaming caret.** `Message.isStreaming` is ignored. #132 adds the caret + animation." #184 inherits that scope (the original streaming ticket #132 was re-numbered / split; this spec realises that promise).
- `docs/specs/architecture/129-markdown-rendering-assistant-messages.md:188-196` — load-bearing state-and-concurrency stance: `MarkdownText` parses synchronously inside `remember(markdown)`, sub-millisecond on typical messages, and re-parses per token batch under streaming are explicitly accepted as "fine for typical Claude token-batch granularity." #184 leans on exactly this: the reveal and caret blink both drive the same input string and re-trigger the existing memoised parse.
- `docs/specs/architecture/130-code-block-syntax-highlighting.md:218-219` — `CodeBlock`'s streaming posture: "Streaming (#132) is out of scope here. When streaming arrives, the streaming path will call `CodeBlock(partialContent, language)` per token batch; the `remember` re-tokenises per batch. That's fine for typical batch granularity." #184 confirms that posture: streaming a partial code fence flows through `CodeBlock` unchanged.
- `gradle/libs.versions.toml` — confirm no new dependency is needed. The reveal uses `produceState` + `kotlinx-coroutines-core` (already on the classpath via `androidx.compose`); the blink uses the same coroutine primitives.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The Conversation Thread frame `16:8`. The last assistant message in the frame (`16:54` → `16:56`) is rendered **in the streaming state**: the body ends mid-sentence with `…2) Reassign to a system "deleted user" entry, or 3)▎` — the `▎` (U+258E LEFT ONE QUARTER BLOCK) glyph at the tail is the streaming caret. The text behind the caret is `onSurface` body-medium, identical styling to the rest of the assistant text; the caret glyph inherits the same color (`onSurface`) because it is rendered as ordinary text content. This ticket reproduces that exact visual: a single `▎` glyph appended to the revealed prefix, blinking on/off at ~2 Hz, removed entirely when `isStreaming` flips to `false`.

The Figma's surrounding assistant tile shape (background, border radius) does **not** apply — #128's scope contract pinned the assistant variant as flat, no bubble, transparent background, left-aligned. That decision stands for streaming too: the reveal animates the text in-place inside the same flat container the completed assistant message uses.

## Context

`#128` shipped the flat assistant message; `#129` swapped its inner `Text` for `MarkdownText`; `#130` added syntax-highlighted fenced code blocks under the same `MarkdownText` entry point. All three explicitly deferred the streaming visual to a follow-up — `#128` lines 39, `#129` lines 192–196, `#130` lines 218–219 all name the same future ticket. This is that ticket: when `Message.isStreaming = true`, the assistant content reveals progressively with a blinking caret at the reveal frontier; when it flips `false`, the caret disappears and the final text remains.

Phase 0 has no real streaming source yet — `FakeConversationRepository` is static. The repo therefore also gains a single seeded assistant `Message(isStreaming = true)` with substantive markdown content so the animation is visible in the running app and in `@Preview`.

Scope contract:

- **Only the streaming visual.** Auto-scroll behaviour that keeps the thread anchored as the message grows is sibling ticket #185 — explicitly out of scope here (ticket body, Context paragraph). No `LazyListState.scrollToItem` calls inside this composable.
- **Token-reveal is character-by-character at a fixed rate.** The "tokens" in "token-reveal" are characters, not parser tokens — Phase 0 has no token-batch source to follow. AC1 names ~50 chars/sec and explicitly says the exact rate may be tuned during implementation.
- **Markdown formatting applies to the revealed prefix.** The reveal substring goes through `MarkdownText` unchanged. AC2 explicitly forbids a flash of unformatted text.
- **No-op cost when `isStreaming = false`.** Historical messages (the typical case, ~all of them after Phase 0 demos) never enter the streaming branch — same composable path as #129 / #130.
- **Caret colour = `MaterialTheme.colorScheme.onSurface`.** The caret glyph is appended to the revealed source as ordinary text and inherits `LocalContentColor.current`, which `AssistantMessage`'s `CompositionLocalProvider` already sets to `onSurface`. AC1 holds by construction.
- **No isStreaming-driven content change.** Phase 0 seeds a static `isStreaming = true` message — the `content` doesn't grow over time at the repo layer. The reveal animation reveals a fixed-length `content` field. Phase 4 will feed growing `content` through the same composable; the existing `produceState(key1 = content)` already restarts the reveal when `content` changes (see § State + concurrency model). Nothing to do for that now.
- **Tool messages, user messages.** Untouched. The streaming logic lives strictly inside the `Role.Assistant` arm.

## Design

### Caret glyph

Use **`▎` (U+258E LEFT ONE QUARTER BLOCK)** as the caret glyph. This is the glyph the Figma frame `16:56` uses; matching the Figma keeps visual-fidelity drift at zero.

Declare as a file-private constant near the existing spacing tokens at the top of `MessageBubble.kt`:

```kotlin
private const val STREAMING_CARET_GLYPH = "▎"
```

Why a glyph in the source text, not a separate Compose element: the AC requires the caret to render `immediately after the currently-revealed text`. With `MarkdownText` flowing content into a `Column` of mixed block elements (paragraphs, headings, lists, code fences), Compose has no straightforward way to position a side-rendered caret pixel-adjacent to the last glyph of the last revealed character. Appending the caret as a Unicode block character to the revealed source means it rides through the same parser/renderer the rest of the text uses — it lands inline at the last position regardless of whether the prefix ends inside a paragraph, a heading, a list item, a blockquote, or a code fence. The Figma frame already designs the streaming end-state with this exact glyph in body-text flow.

### Modified composable: `AssistantMessage`

Location unchanged: still in `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt`. Visibility unchanged: `private`. Signature changes to take the full `Message` (was: `content: String`):

```kotlin
@Composable
private fun AssistantMessage(
    message: Message,
    modifier: Modifier = Modifier,
)
```

The outer container is unchanged: a `Box(modifier.fillMaxWidth().padding(bottom = MessageRowVerticalSpacing))` wrapping a `CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { … }`. The inside branches on `message.isStreaming`:

- `false` → `MarkdownText(markdown = message.content, modifier = Modifier.fillMaxWidth())`. Identical to today; the no-op-cost-when-not-streaming AC holds by construction (no `produceState`, no coroutine, no extra recomposition).
- `true` → call a new private `StreamingAssistantBody(content = message.content, modifier = Modifier.fillMaxWidth())` (described next).

The `MessageBubble` top-level dispatch (lines 38–49) updates:

```kotlin
Role.Assistant -> AssistantMessage(message, modifier)
```

(passing the whole `Message` rather than `message.content`). `UserMessageBubble` and the `Role.Tool -> Unit` arm are unchanged.

### New composable: `StreamingAssistantBody` (private)

In the same file, just below `AssistantMessage`. Signature:

```kotlin
@Composable
private fun StreamingAssistantBody(
    content: String,
    modifier: Modifier = Modifier,
)
```

Behaviour (signature + 1-line summary form; **do not pre-write the body**):

1. Derive `revealedLength: Int` via `produceState<Int>(initialValue = 0, key1 = content)` running a coroutine that increments the value over time at `STREAMING_REVEAL_CHARS_PER_SECOND`, capped at `content.length`. Implementation idiom: `while (value < content.length) { delay(STREAMING_REVEAL_STEP_MS); value = (value + STREAMING_REVEAL_STEP_CHARS).coerceAtMost(content.length) }`. The constants below resolve to ~50 chars/sec.
2. Derive `caretVisible: Boolean` via `produceState<Boolean>(initialValue = true, key1 = Unit)` running `while (true) { delay(STREAMING_CARET_BLINK_PERIOD_MS); value = !value }`. Independent of the reveal; the caret keeps blinking while content streams and after it finishes revealing (until `isStreaming` flips `false` at the `AssistantMessage` layer, which un-mounts this composable).
3. Pass `delegateToBody(revealedLength, caretVisible, content)` into a private `StreamingAssistantBodyView(revealedText, caretVisible, modifier)` (next section). The split exists so the preview can call the view with a fixed (revealedLength, caretVisible) snapshot — see § Previews.

`StreamingAssistantBody` does not itself render anything; it is a state-derivation shell that hands a snapshot to `StreamingAssistantBodyView`.

### New composable: `StreamingAssistantBodyView` (private)

Pure rendering, no state. Signature:

```kotlin
@Composable
private fun StreamingAssistantBodyView(
    revealedText: String,
    caretVisible: Boolean,
    modifier: Modifier = Modifier,
)
```

Behaviour: compute `displayText = revealedText + (if (caretVisible) STREAMING_CARET_GLYPH else "")` and render `MarkdownText(markdown = displayText, modifier = modifier)`.

The view is intentionally trivial — its only job is to be invocable with arbitrary `(revealedText, caretVisible)` so previews can show a mid-stream snapshot without a coroutine-driven `produceState`.

### File-private constants

Add near the existing constants at the top of `MessageBubble.kt`:

```kotlin
private const val STREAMING_CARET_GLYPH = "▎"
private const val STREAMING_REVEAL_CHARS_PER_SECOND = 50
private const val STREAMING_REVEAL_STEP_CHARS = 1
private val STREAMING_REVEAL_STEP_MS: Long = (1000L / STREAMING_REVEAL_CHARS_PER_SECOND)  // 20ms at 50 cps
private val STREAMING_CARET_BLINK_PERIOD_MS: Long = 500L
```

`STREAMING_REVEAL_STEP_CHARS = 1` keeps the implementation simple — increment by one character per tick. Recomposition cost: each character increment triggers a re-parse of the revealed prefix via `MarkdownText.remember(markdown)`. Per #129's spec, that parse is sub-millisecond for typical messages; 50 parses/sec is well inside frame budget. If a profiler ever flags it, the optimisation is `STREAMING_REVEAL_STEP_CHARS = 4` and `STREAMING_REVEAL_STEP_MS = 80L` — same effective rate, 4× fewer recompositions. Don't pre-build that now.

`STREAMING_CARET_BLINK_PERIOD_MS = 500L` → 1 Hz toggle → 0.5 Hz blink rate (one full on/off cycle per second). Matches conventional terminal caret cadence. Tune to taste during implementation; the AC says "blinking" without prescribing a rate.

### Reveal-loop edge cases

- **Empty `content`.** `produceState` starts at 0, the `while (value < content.length)` exits immediately, caret continues to blink alone. Visual is just the blinking caret on an empty line. Acceptable; matches "thinking…" UX. Phase 0 seed is non-empty so this isn't a frequent state.
- **Content mid-stream is shorter than `revealedLength`.** Not possible with the static seed. If Phase 4 ever shortens content, the `key1 = content` parameter to `produceState` re-runs the producer (Compose semantics: changing a key cancels the running producer and starts a new one) — the reveal restarts from 0. That's the right behaviour (replay the new content from scratch); pre-paying for "smarter" continuation is out of scope here.
- **Reveal completes while `isStreaming` is still true.** The reveal loop exits at `value == content.length`; `produceState` keeps the value pinned. The caret keeps blinking after the full text is revealed — visual signal that the message is still considered "live" even though no new tokens are arriving. When `isStreaming` flips `false`, `AssistantMessage` switches branches and the caret disappears.

### Modified `MessageBubble` dispatch

One-line change at line 45:

```kotlin
Role.Assistant -> AssistantMessage(message, modifier)
```

(was `AssistantMessage(message.content, modifier)`).

### `FakeConversationRepository` seed

Three small edits in `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt`:

1. **`SeedMessage` gains an `isStreaming` field with a default**:

   ```kotlin
   private data class SeedMessage(
       val role: Role,
       val content: String,
       val timestamp: Instant,
       val isStreaming: Boolean = false,
   )
   ```

2. **`seedMsg` accepts the flag, defaulted to `false`** so the dozens of existing call sites are unchanged:

   ```kotlin
   private fun seedMsg(
       role: Role,
       content: String,
       timestamp: String,
       isStreaming: Boolean = false,
   ): SeedMessage = SeedMessage(role, content, Instant.parse(timestamp), isStreaming)
   ```

3. **Both `Message` construction paths** (the `historicalMessages` and `currentMessageEntries` mappers inside `seedChannel`, lines 411–434, and the `messageEntries` mapper inside `seedDiscussion`, lines 481–491) read `msg.isStreaming` instead of the hard-coded `false`. Three identical edits — `isStreaming = false` becomes `isStreaming = msg.isStreaming`.

4. **One streaming seed inserted** as the last message of the `seed-channel-pyrycode-mobile` `currentMessages` block (after line 293's `seedMsg(Role.Assistant, "Drafted as #TBD with the architect notes.", "2026-05-10T09:00:00Z")`). Replace that line's content with:

   ```kotlin
   seedMsg(
       Role.Assistant,
       """
       Drafted as #TBD with the architect notes. Three things I'd add for the dialog ticket:

       1. **Validation** — name must be non-empty after trim; reject if a channel with the same name already exists in this conversation list.
       2. **Workspace picker** — default to the current cwd, allow override via the existing `WorkspacePicker` composable.
       3. **Cancel affordance** — Esc / back-press dismisses without promoting.

       Want me to sketch the `UiState` shape before we open the issue?
       """.trimIndent(),
       "2026-05-10T09:00:00Z",
       isStreaming = true,
   ),
   ```

   This satisfies AC4: ≥ 3 sentences (5+ sentences across the intro + the three bullets + the closing question), at least one markdown element (bold via `**…**`, an ordered list `1.` / `2.` / `3.`, inline code `` `WorkspacePicker` ``, `Esc` / `back-press`). It also keeps the existing demo-content flavour (Pyrycode Mobile channel, architect-notes voice).

   Word/character count check: ~480 characters → at 50 cps that's a ~10 s reveal. Long enough to observe the animation visibly in the running app.

The `historicalMessages` flag passthrough is required even though no historical seed sets `isStreaming = true` today — it keeps the data path uniform, and a future ticket that adds a historical streaming snapshot (e.g. for an archived recovery scenario) doesn't have to re-touch the seed wiring.

### Previews

Replace the existing `MessageBubbleMarkdownPreviewBody` with a richer body that shows the AC5 side-by-side comparison: a streaming message above (mid-reveal snapshot, caret on) and a completed message below (same content, no caret), inside the same Column with `verticalArrangement = Arrangement.spacedBy(MessageRowVerticalSpacing)`.

The streaming snapshot is rendered via a direct call to `StreamingAssistantBodyView(revealedText = MARKDOWN_PREVIEW_FIXTURE.take(MARKDOWN_PREVIEW_FIXTURE.length / 2), caretVisible = true)` so the preview shows the mid-state without spinning up a coroutine (Android Studio's `@Preview` runtime does run `LaunchedEffect` / `produceState`, but the visible snapshot at the moment of capture is non-deterministic for fast animations — pre-pinning the snapshot gives stable previews).

The completed message is `MessageBubble(previewMessage(Role.Assistant, MARKDOWN_PREVIEW_FIXTURE))` — exercises the `isStreaming = false` path of `AssistantMessage`.

Shape (signatures + structure; **do not pre-write bodies**):

- Rename or extend `MessageBubbleMarkdownPreviewBody` to render both, top-to-bottom:
  - A "Streaming · half-revealed" caption (small `labelSmall`/`onSurfaceVariant` Text — optional, kept only if it helps the reviewer parse the preview), followed by the streaming snapshot wrapped in the same surrounding `CompositionLocalProvider(LocalContentColor provides onSurface)` so the caret colour shows correctly in isolation.
  - A "Completed" caption (optional), followed by `MessageBubble(previewMessage(Role.Assistant, MARKDOWN_PREVIEW_FIXTURE))`.
- The existing `MessageBubbleMarkdownLightPreview` / `MessageBubbleMarkdownDarkPreview` continue to invoke this body — no rename, no new `@Preview` function. The pre-existing `MessageBubbleLightPreview` / `MessageBubbleDarkPreview` sequence previews are untouched.

The captions are an optional aid; if the reviewer pushes back as preview clutter, drop them and let the visual difference (one has a `▎` glyph at the half-content mark, the other doesn't) carry the meaning.

A `previewStreamingMessage` helper is **not** added — the snapshot uses `StreamingAssistantBodyView` directly with a fixed substring + `caretVisible = true`, sidestepping the need to mint a `Message(isStreaming = true)` only for the preview.

### Module wiring

None. Koin DI is unchanged. No new public API. No new dependency. The new composables are file-private inside `MessageBubble.kt`.

## State + concurrency model

`StreamingAssistantBody` holds two pieces of derived state, both via `produceState`:

- `revealedLength: State<Int>` — keyed on `content`. The producer is a `while (value < content.length) { delay(STREAMING_REVEAL_STEP_MS); value = (value + STREAMING_REVEAL_STEP_CHARS).coerceAtMost(content.length) }`. When `content` changes (Phase 4: streaming arrival of new tokens grows the string), the key flip cancels the running producer and starts a new one — the reveal restarts. Phase 0: content is static; the producer runs once until completion, then `produceState` retains the value `content.length`.
- `caretVisible: State<Boolean>` — keyed on `Unit` (so the blink runs for the lifetime of the composable). Producer alternates the value every `STREAMING_CARET_BLINK_PERIOD_MS`.

Both producers cancel automatically when the composable leaves composition (`produceState`'s built-in cleanup tied to the composition's coroutine scope). No `LaunchedEffect`, no `DisposableEffect`, no `viewModelScope` involvement. The composable is leaf-level UI state.

`AssistantMessage` itself is stateless — it branches on `message.isStreaming` and routes to either the static `MarkdownText` path or the `StreamingAssistantBody` path. Switching between the two branches is a Compose subtree swap; the state held by the streaming path is released when the branch flips back to static.

**Recomposition stability.** `Message` is a Kotlin `data class` with all stable fields (`String`, `Role` enum, `Instant`, `Boolean`); Compose's stability inference treats it as stable, so `AssistantMessage` skips recomposition when the same `Message` instance flows in. Inside the streaming branch, `revealedLength` and `caretVisible` are `State<…>` instances Compose tracks at fine granularity — only the inner `Text` (inside `MarkdownText`) recomposes when `revealedText + caretGlyph` changes, not the outer `Box` / `CompositionLocalProvider`.

**Parse cost.** Each `revealedLength` change re-keys the `remember(markdown)` inside `MarkdownText`, triggering a fresh parse of the revealed prefix. At 50 cps, that's 50 parses/sec; each is sub-millisecond per #129's measurements. Same posture as #129's open-question paragraph on streaming. The blink toggle also changes the input string (caret on/off) — adds 2 parses/sec on top of the reveal cadence, well inside frame budget.

**Why two separate `produceState` blocks instead of one combined.** Keying separately is what makes the reveal restart when `content` changes without restarting the blink. The blink is decoupled from the reveal; combining them would tangle restart semantics.

## Error handling

N/A. No I/O, no parsing failure path inside the streaming wrapper — `MarkdownText`'s own parser is total (per #129's stance), the highlighter has a defensive try/catch (per #130's stance), and `produceState`'s coroutine cancellation is handled by Compose. The composable never throws.

**Defensive `content.take(revealedLength)` clamp.** `String.take(n)` accepts `n > length` (returns the whole string), so the boundary value `revealedLength == content.length` is safe. The producer's `coerceAtMost(content.length)` is the same guarantee in producer-side form — double-belt is fine, both are zero-cost.

**No telemetry, no logs.** Streaming animation is a visual; no observability hook is added.

## Testing strategy

### Unit tests — none

The streaming wrapper is pure-rendering Compose code driven by coroutine timing. No logic to assert without a Compose host. Same precedent as `#128` / `#129` / `#130` — no Robolectric, no Compose JVM-host on `testImplementation`. Building that infrastructure is over-scope for a ~90-LOC ticket.

### Compose UI tests — none

Same precedent: no `ComposeTestRule` setup in the codebase. Visual verification is by `@Preview` rendering in Android Studio:

1. **`MessageBubbleMarkdownLightPreview`** and **`MessageBubbleMarkdownDarkPreview`** (extended) render the streaming snapshot (half-revealed, caret glyph visible at the cut point) and the completed snapshot in both themes. AC5 satisfied.
2. **Running the app** on a device/emulator with the new streaming seed in `FakeConversationRepository` shows the animation: the last assistant message in the Pyrycode Mobile channel reveals progressively at ~50 cps with the blinking caret. AC1 + AC2 + AC3 visually verifiable end-to-end. AC4 verifiable by inspecting that the message renders with markdown formatting (bold, ordered list, inline code).
3. **`./gradlew assembleDebug`** must pass.
4. **`./gradlew lint`** must pass with no new warnings. `produceState` and `delay` are stable Compose / coroutine APIs; no version concerns.

Code-review treats successful preview rendering + a screen-record (or live demo) of the streaming animation as the AC acceptance signal.

### `./gradlew test`

The repo's existing tests under `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt` (if present) exercise seeded conversations. Adding the streaming seed to the Pyrycode Mobile channel **may** affect a test that asserts the last-message content (`observeLastMessage` precedent from #161/#162). If a test breaks, the fix is updating the expected last-message content to the new long streaming message — not removing the seed. Verify locally with `./gradlew test`; if no test references that specific channel's last message, no test update is needed.

No new unit tests added for the streaming animation itself.

## Open questions

- **Caret placement when the revealed prefix ends inside an open markdown construct.** If the cut lands inside an open `**`, an open backtick, an open code fence with no closing ` ``` `, or an open `[link text`, the parser falls through to its `else` raw-text fallback (#129's stance). The caret glyph still renders, just inside the unstyled fallback text. Acceptable per the AC ("Markdown formatting and code-block styling continue to apply to the revealed prefix" — the formatting that *can* apply does; partial constructs are handled by the existing fallback). If this looks visually rough in practice, follow-ups include either (a) parsing a "stable" prefix that ends at the last closed construct, or (b) clamping the cut to a token boundary. Not pre-built.
- **Reveal rate tuning.** AC1 says "approximately 50 chars/sec … exact rate may be adjusted during implementation." 50 cps is a defensible default — fast enough to feel snappy, slow enough to read along. Tune by changing `STREAMING_REVEAL_CHARS_PER_SECOND` to taste; if a designer wants a different cadence, change one constant.
- **Blink rate tuning.** 500 ms toggle (1 s full cycle) is the conventional terminal caret rate. Some designs prefer faster (250 ms) or slower (750 ms). Tune `STREAMING_CARET_BLINK_PERIOD_MS` to taste.
- **Caret glyph choice.** `▎` U+258E matches the Figma. Adjacent options if it reads too thin or too wide: `▏` U+258F (thinner) or `▌` U+258C (wider, more terminal-like). Single-constant change.
- **Preview animation visibility.** Android Studio's `@Preview` rendering does run `LaunchedEffect` / `produceState`, but the captured snapshot for fast animations is non-deterministic. The spec splits `StreamingAssistantBody` (with state) from `StreamingAssistantBodyView` (pure, takes the snapshot) so the preview pins a deterministic mid-state. If a reviewer wants the preview to live-animate in Studio's interactive mode, that already works — the `produceState`-driven composable can be exercised via `MessageBubble(previewMessage(Role.Assistant, MARKDOWN_PREVIEW_FIXTURE).copy(isStreaming = true))` in an additional preview. Not added pre-emptively.
- **`historicalMessages` streaming passthrough.** The seed pipeline now carries `isStreaming` through both `historicalMessages` and `currentMessageEntries` in `seedChannel`, and through `messageEntries` in `seedDiscussion`. No existing seed sets the flag `true` outside the one added here. This is a uniformity fix, not a feature — keeps a future "archived streaming snapshot" ticket from re-touching the wiring.
- **`StreamingAssistantBody` lifetime when scrolled off-screen.** A `LazyColumn` item that scrolls out of the viewport is disposed; the composable's `produceState` coroutines cancel. When the user scrolls back, the streaming composable re-enters composition, `revealedLength` restarts from 0, and the message animates again from the top. Phase 0 acceptable — the streaming seed lives in the most-recently-used channel, and the typical demo path keeps it on screen. Phase 4 may want to hoist `revealedLength` into the `ViewModel` so it persists across scroll-induced disposals; that's a sibling-of-#185 concern, not this ticket's.

## Related

- Spec: [`docs/specs/architecture/128-message-bubble-user-assistant-variants.md`](./128-message-bubble-user-assistant-variants.md) — defines the assistant variant's flat-text shape and the file-private constants this ticket extends.
- Spec: [`docs/specs/architecture/129-markdown-rendering-assistant-messages.md`](./129-markdown-rendering-assistant-messages.md) — defines `MarkdownText`; this ticket re-renders its input on every reveal step.
- Spec: [`docs/specs/architecture/130-code-block-syntax-highlighting.md`](./130-code-block-syntax-highlighting.md) — defines the streamed `CodeBlock(partialContent, language)` pass-through; partial code fences inside a streaming message ride this code path unchanged.
- Sibling: #185 — auto-scroll on streaming growth, explicitly out of scope here.
- Downstream (Phase 4): real backend streaming via Ktor + WS will feed growing `Message.content` through the same composable; the `produceState(key1 = content)` restart semantics handle the rolling-update case.
