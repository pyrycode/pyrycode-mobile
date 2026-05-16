# Spec — code block rendering with syntax highlighting (#130)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt:46-51` — file-private spacing constants set by #129 (`CodeBlockCornerRadius`, `CodeBlockPadding`, `ParagraphSpacing`). The new code-block composable lives in the same file and **reuses these constants** rather than introducing parallel ones.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt:99-112` — the two call sites that currently invoke `CodeBlock(code)`: `CODE_FENCE` (fenced ``` blocks) and `CODE_BLOCK` (indented 4-space blocks). The fenced branch already collects `CODE_FENCE_CONTENT` lines; this ticket extends it to also extract the fence info string (the `kotlin` after the opening ` ``` `) from `MarkdownTokenTypes.FENCE_LANG`. The indented branch keeps passing `language = null`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt:216-232` — the existing private `CodeBlock` composable. The body changes; the function name and visibility stay private. Its signature gains a `language: String?` parameter.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt:174-193` — the `MARKDOWN_PREVIEW_FIXTURE` constant currently exercises one Kotlin fence. AC7 wants Kotlin, JSON, Bash, and Markdown fences exercised in light + dark; the cleanest path is to **extend this single fixture** to include all four fences. The existing `MessageBubbleMarkdownLightPreview` / `MessageBubbleMarkdownDarkPreview` then automatically cover AC7 — no new `@Preview` functions needed.
- `gradle/libs.versions.toml` — one `[versions]` line + one `[libraries]` line for the new highlighter dependency. Mirror the existing alphabetised ordering inside each section (see how `jetbrainsMarkdown` / `jetbrains-markdown` were added by #129 for the shape).
- `app/build.gradle.kts:78-106` — `dependencies { }` block. Add one `implementation(libs.snipme.highlights)` line near the other text/markdown deps.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt:272-303` — `PyrycodeMobileTheme` supplies the M3 colour scheme. AC6 binds the token colours to slots on `MaterialTheme.colorScheme` (`tertiary`, `secondary`, `primary`, `onSurfaceVariant`, `surfaceContainer`); no edit needed.
- `docs/knowledge/decisions/0002-markdown-renderer-library.md` — same posture applies here ("prefer KMP-portable, Compose-native libraries; pay the renderer ourselves rather than accept an AndroidView interop seam"). The choice of `dev.snipme:highlights` over a Markwon-style highlighter wrapper is a direct consequence; see § Design / Library choice.
- `docs/knowledge/features/markdown-text.md` — feature doc. The downstream note at the bottom ("`#130` — full code-block styling…") is realised here; the file will be updated by the documentation phase, not by this ticket.
- `docs/specs/architecture/129-markdown-rendering-assistant-messages.md:31` — load-bearing scope statement from #129: "Fenced code blocks render flat… **No language label, no outline border, no syntax highlighting** — all three land in #130." This ticket exhausts that promise. No outline border is added; the ticket body explicitly pins `surfaceContainer` as the fill and does not call for a border.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The Conversation Thread frame `16:8`. The fenced code block in the design (`16:45` – `16:50`) is a rounded rectangle with a header strip containing the language label (`typescript`, top-left, `labelSmall` / `onSurfaceVariant`), a separator line, and a monospace code body below.

**Two deliberate deviations from the Figma to follow the ticket body, which is authoritative:**

1. **Background slot.** Figma uses `surface` + `outline-variant` border. Ticket AC2 + ticket-body "Background" both pin **`surfaceContainer`** with **no border**. The flat `surfaceContainer` fill matches the inline-code `CODE_SPAN` background introduced by #129, so the inline-code idiom and the fenced-block idiom share a tonal slot — visually consistent inside the assistant bubble.
2. **Language label position.** Figma places the label top-left inside a separate header strip with a bottom-of-strip separator. Ticket AC5 says **top-right** of the block, no separator strip. We implement an overlaid label aligned to `Alignment.TopEnd` inside the same surface — no header row, no separator.

The monospace code body itself is preserved from Figma: monospace `FontFamily`, M3 `bodyMedium` size class, `onSurface` text colour from the ambient `LocalContentColor`. The added value of this ticket over #129's flat `CodeBlock` is the token colouring (per § Design / Token → colour mapping) and the horizontal scroll behaviour for long lines.

## Context

`#129` shipped `MarkdownText` with a flat private `CodeBlock(content: String)` composable: `Surface(surfaceContainer)` rounded rectangle, monospace `bodyMedium` text, no language label, no token colours, no scroll. `#129`'s spec explicitly defers all three to this ticket. This ticket replaces the body of that same private `CodeBlock` with a syntax-highlighted, optionally-language-labelled, horizontally-scrolling variant, and threads the fence info string from the AST walk into the new `language` parameter.

Scope contract:

- **Private composable inside `MarkdownText.kt`.** The function name (`CodeBlock`) and visibility (`private`) are unchanged. Only its signature, body, and call sites in the same file change. **No new file is created.** No new public API.
- **`MessageBubble.kt` previews are the AC7 acceptance signal** — extending the existing `MARKDOWN_PREVIEW_FIXTURE` with the four fence languages. No new `@Preview` functions, no new files for previews.
- **Languages.** Kotlin, JSON, Bash, and Markdown per AC3. Other languages emitted by Claude fall through to a no-highlighting plain-monospace render (still on `surfaceContainer`, still with the language label) — see § Error handling.
- **No outline border, no header separator strip.** Ticket body pins flat-tile aesthetics.
- **No selection / copy.** Same posture as #129 — out of scope; a future ticket can add `SelectionContainer` at the screen level.
- **No copy-to-clipboard affordance.** Not in the ticket body or AC.

## Design

### Library choice

Use **`dev.snipme:highlights`** (KMP-native, pure Kotlin syntax tokeniser, ~Compose-friendly).

**Rationale (same posture as [ADR 0002](../../knowledge/decisions/0002-markdown-renderer-library.md)):**

- Pure Kotlin Multiplatform. No `AndroidView` seam, no transitive AndroidX, no reflection. Mirrors the `jetbrains-markdown` choice.
- Tokenises a code string and returns **phrase locations** (start, end offsets) per token category — we own the colour mapping. That lets AC6 ("Syntax accent colors derive from `MaterialTheme.colorScheme`; no hardcoded hex values") hold by construction: the renderer reads `MaterialTheme.colorScheme.tertiary` / `.secondary` / `.primary` / `.onSurfaceVariant` at composition time and writes them into the `AnnotatedString` builder, never asking the library for a colour.
- Reflection-free, no proguard rules, no KSP, no kapt.

The ticket's two candidate names (`kotlin-multiplatform-codehighlights` and `Highlights`) resolve to the same library — `dev.snipme:highlights`. Pick one, pin once.

**Why not an Android-only highlighter (e.g. `io.github.kbiakov:codeview-android`):** AndroidView interop seam, JVM-only, undermines the KMP walk-back trigger in [`CLAUDE.md`](../../../CLAUDE.md). Rejected by the same rationale as `compose-markdown` in [ADR 0002](../../knowledge/decisions/0002-markdown-renderer-library.md).

**Library version.** Pin to the current Maven Central stable at wire-up time. As of 2026-05-16 the latest is `0.9.3` (`dev.snipme:highlights:0.9.3`) — the developer should `gh search` Maven Central / the library's GitHub Releases for any newer stable and pin to that. The API surface used here (`Highlights.Builder().code(...).language(SyntaxLanguage.X).build()`, `getCodeStructure()` or `getHighlights()`, the `SyntaxLanguage` enum) has been stable across `0.x` releases.

### Library wire-up

`gradle/libs.versions.toml`:

```toml
[versions]
+ snipmeHighlights = "0.9.3"   # confirm latest stable on Maven Central at wire-up

[libraries]
+ snipme-highlights = { group = "dev.snipme", name = "highlights", version.ref = "snipmeHighlights" }
```

`app/build.gradle.kts`, in the `dependencies { }` block:

```kotlin
implementation(libs.snipme.highlights)
```

No plugin entry, no KSP / kapt, no proguard rules. Order alphabetically with the existing `implementation(libs.jetbrains.markdown)` line for a small house-style cue; the codebase isn't strict here.

### Modified composable: `CodeBlock`

Location: still inside `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`. Visibility: `private`. Signature changes:

```kotlin
@Composable
private fun CodeBlock(
    content: String,
    language: String?,
)
```

- `content` is the joined source text (existing behaviour from #129's `CODE_FENCE_CONTENT` / `CODE_LINE` collection).
- `language` is the fence info string (`"kotlin"`, `"json"`, `"bash"`, `"md"`, …) or `null` for indented-code blocks and bare fences with no info string.

#### Visual structure

A single `Surface` (rounded rectangle, `surfaceContainer` fill) containing a `Box` whose children are:

1. **Code body** — a `Row` (or `Box`) inside `Modifier.horizontalScroll(rememberScrollState())`, wrapping a single `Text` with `softWrap = false`. Padding from the file-private `CodeBlockPadding` constant (already exists). The `Text` receives the tokenised `AnnotatedString` (see § Tokenisation).
2. **Language label** (conditional — render only when `language != null && language.isNotBlank()`) — a `Text(language, style = labelSmall, color = onSurfaceVariant)` aligned to `Alignment.TopEnd` inside the `Box` with a small `padding(horizontal = CodeBlockPadding.calculateRightPadding(LayoutDirection.Ltr), vertical = CodeBlockLabelVerticalPadding)`.

The label sits **on top of** the scrollable code (a `Box` with `align(TopEnd)`), so its position is fixed regardless of horizontal scroll position. Visually it appears as a small label tag in the corner.

The container shape is `RoundedCornerShape(CodeBlockCornerRadius)` — existing file-private constant from #129.

#### File-private constants

Existing (reuse, do not redeclare):

- `CodeBlockCornerRadius = 8.dp`
- `CodeBlockPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)`
- `ParagraphSpacing = 8.dp` (inter-block; not consumed inside `CodeBlock`)

New (one addition):

- `private val CodeBlockLabelVerticalPadding = 4.dp` — vertical inset of the language label from the surface's top edge. Horizontal inset reuses `CodeBlockPadding`'s horizontal slot, so the label aligns visually with the right edge of the code body.

No `.sp` literal anywhere. Every text size is reached through `MaterialTheme.typography.<slot>`.

#### Horizontal scroll

`Modifier.horizontalScroll(rememberScrollState())` on the code container; `Text(..., softWrap = false)`. Each `CodeBlock` invocation gets its own scroll state (via `rememberScrollState()` inside the composable), so independent fenced blocks scroll independently. The scrollbar is not custom-rendered — Compose's default fling/edge behaviour is sufficient for the AC ("Long lines scroll horizontally inside the block — no wrapping").

The language label overlays the scroll viewport, **not** the scroll content — it stays anchored to the top-right corner of the surface regardless of scroll position. This is achieved by placing the `Text(language, …)` outside the `horizontalScroll` modifier chain, inside the same parent `Box` via `align(Alignment.TopEnd)`.

### Tokenisation

`Highlights` exposes the tokenised structure via `Highlights.Builder().code(content).language(<SyntaxLanguage>).build().getCodeStructure()` (or equivalent — the developer verifies the precise API at wire-up time). The returned structure groups `PhraseLocation(start, end)` entries by token category (keywords, types, strings, numbers, comments, annotations, marks).

**Memoise the tokenisation:** `remember(content, language) { tokenise(content, language) }` — re-tokenise only when the source text or language changes. Cheap, but defensive against re-tokenising every recomposition of the parent thread.

**Build the `AnnotatedString`:**

1. Start from the raw `content` string.
2. For each token category, walk its `PhraseLocation` list and `addStyle(SpanStyle(color = <M3 slot>, fontStyle = …?), start, end)` to apply the colour over that span.
3. Default text (anything outside all category spans) inherits `LocalContentColor.current` — no explicit colour applied.

#### Token → colour mapping

| Library category (approximate) | M3 slot | Span attributes |
|---|---|---|
| Keywords (control flow, declarations) | `colorScheme.tertiary` | `fontWeight = FontWeight.Medium` |
| Types / classes / annotations | `colorScheme.tertiary` | — |
| Strings / character literals | `colorScheme.secondary` | — |
| Numbers / literals | `colorScheme.primary` | — |
| Comments | `colorScheme.onSurfaceVariant` | `fontStyle = FontStyle.Italic` |
| Punctuation / operators / default | inherited (`LocalContentColor.current`) | — |

The "primary accent" called out by ticket AC6 is `tertiary` — applied to keywords + types, the two visually-dominant categories in most code. `secondary` / `primary` for strings / numbers keep the colour story two-stop and themable, never hardcoded. Comments use `onSurfaceVariant` (the M3 "auxiliary text" slot) + italic, which is also the M3-conventional treatment.

The exact category names exposed by the library may not line up one-to-one with this table — that's expected. The mapping table above describes **intent**: keywords/types ⇒ tertiary, strings ⇒ secondary, numbers ⇒ primary, comments ⇒ onSurfaceVariant italic, rest ⇒ inherited. The developer reconciles library categories to these intent buckets at wire-up time. If the library lumps "annotation" into "keyword", that's fine — both map to `tertiary`. If it splits "type" out separately, both spans get `tertiary`. The contract is the bucket → slot mapping, not the library's specific enum names.

### Language detection

The fence info string from the AST (e.g. `kotlin`, `json`, `bash`, `markdown`, `md`, `sh`) maps to `SyntaxLanguage` via a small file-private function:

```kotlin
private fun resolveSyntaxLanguage(fenceLang: String?): SyntaxLanguage?
```

Mapping table (case-insensitive, trim whitespace before matching):

| Fence info string | Resolved language |
|---|---|
| `kotlin`, `kt`, `kts` | `SyntaxLanguage.KOTLIN` |
| `json` | `SyntaxLanguage.JSON` if exposed by the library; otherwise `null` (fall through to no-highlighting) |
| `bash`, `sh`, `shell`, `zsh` | `SyntaxLanguage.SHELL` |
| `markdown`, `md` | `null` (the library does not currently expose a Markdown lexer; render as plain monospace — see § Error handling) |
| anything else | `null` (plain monospace fallback) |
| `null` input (no info string) | `null` |

When `resolveSyntaxLanguage` returns `null`, the `CodeBlock` skips the `Highlights` call entirely and renders `content` as a plain `AnnotatedString` (no spans), so default `LocalContentColor` paints the full text. The block still shows the language label (if one was given) — only the token colouring is suppressed.

### Modified call sites: `MarkdownText.kt` block dispatch

Both call sites pass through to the new `CodeBlock` signature; only the fenced branch extracts the info string.

`CODE_FENCE` branch (existing lines 99–105):

- Existing collection of `CODE_FENCE_CONTENT` lines into `code` is unchanged.
- Add: extract the first `MarkdownTokenTypes.FENCE_LANG` child's text into `language: String?` (trim; `null` if absent or blank).
- Call `CodeBlock(code, language)`.

`CODE_BLOCK` branch (existing lines 106–112):

- Existing collection of `CODE_LINE` lines into `code` is unchanged.
- Call `CodeBlock(code, language = null)`.

No other branches change. The block-dispatch `when` arms for headings, paragraphs, lists, blockquotes, inline-link / inline-code, and the `else` fallback are untouched.

### Updated preview fixture: `MessageBubble.MARKDOWN_PREVIEW_FIXTURE`

The existing constant (lines 174–193 of `MessageBubble.kt`) currently includes one Kotlin fence. Extend it so the same single fixture exercises all four AC3 languages, **without adding new `@Preview` functions**. The existing `MessageBubbleMarkdownLightPreview` / `MessageBubbleMarkdownDarkPreview` then satisfy AC7 by re-rendering this fixture in light and dark themes.

Shape the fixture so each fence is short (3–5 lines) and prefaced by a one-liner paragraph that names the language being demonstrated. The four fences:

1. **Kotlin** — exercise keywords (`fun`, `val`), a type, a string literal, a number literal, a comment.
2. **JSON** — exercise strings, numbers, structural punctuation.
3. **Bash** — exercise a comment (`#`), a command, a quoted string.
4. **Markdown** — fenced with `markdown` (or `md`); this exercises the **plain-monospace fallback** path through `CodeBlock` (no library highlighting). The visual contrast in the preview between the three highlighted fences and the one plain Markdown fence is itself the AC3 acceptance signal: "library handles Kotlin / JSON / Bash; Markdown still renders, just unhighlighted."

The fixture stays inside `MessageBubble.kt`'s file-private constants block; the previews stay where they are. No new preview functions or new files.

### Module wiring

None beyond the library wire-up above. Koin DI is unchanged — `CodeBlock` is a pure leaf composable with no injected dependency.

## State + concurrency model

`CodeBlock` is a stateless composable except for:

- `rememberScrollState()` per invocation — local UI state for the horizontal scroll viewport. Recomposing with the same `content` keeps the scroll position.
- `remember(content, language) { tokenise(content, language) }` — memoised tokeniser output, re-run only on input change.

No `LaunchedEffect`, no coroutines, no flows. Tokenisation is synchronous and CPU-only. Typical assistant code blocks are well under a thousand characters; `Highlights` is O(n) in code length and finishes in sub-millisecond on these inputs.

Recomposition: a `String` content + nullable-`String` language is structurally stable in Compose's inference, so `CodeBlock` skips recomposition when the same `(content, language)` is passed by the parent.

Streaming (#132) is out of scope here. When streaming arrives, the streaming path will call `CodeBlock(partialContent, language)` per token batch; the `remember` re-tokenises per batch. That's fine for typical batch granularity — same posture as the `MarkdownText.remember(markdown)` from #129.

## Error handling

`Highlights` does not throw on unrecognised languages — but to be safe, the language resolver short-circuits to `null` for anything outside its mapping table, and `CodeBlock` checks `language != null` before invoking the library. The library is never called with an unsupported `SyntaxLanguage`.

Failure modes and their treatment:

| Failure mode | Treatment |
|---|---|
| Unknown fence language (e.g. `lua`, `rust`) | `resolveSyntaxLanguage` → `null`; render plain monospace, keep the label. |
| Empty / blank fence info string | `language = null`; render plain monospace, no label. |
| Empty `content` | Render an empty `CodeBlock` surface — the visual is a small rounded rectangle. Acceptable; not a degenerate case worth special-casing. |
| Library throws (defensive) | Catch and render plain monospace fallback — see § Defensive try/catch. |

### Defensive try/catch around tokenisation

`Highlights` is reflection-free and total on valid `SyntaxLanguage` input, but a future library version could regress. Wrap the tokenise call in a single `try/catch (Throwable)` and, on catch, return a no-spans `AnnotatedString.Builder().append(content).toAnnotatedString()`. The user-visible result is plain monospace — the block still renders. **No logging, no toast, no error UI** — same posture as the link-no-op fallback in #129.

The justification for the defensive catch (rare but warranted): the tokeniser walks attacker/model-controlled text, and a pathological input (very long unterminated string, malformed escape) is exactly the kind of input that triggers third-party library bugs. The catch keeps the message readable even when the highlighter regresses. Visual fidelity degrades to "monospace on `surfaceContainer`" — already the baseline from #129.

### No defensive throws

The composable never throws. Unknown languages, empty inputs, and library failures all fall through to plain monospace. The renderer is total.

## Security

The ticket carries the `security-sensitive` label. The architect-side pass is included inline below (the label gate is honoured: review, verdict, then commit).

**Trust boundaries:**

- **Input — `content: String`** flows from the markdown AST → `CODE_FENCE` / `CODE_BLOCK` joined token text → `CodeBlock`. The string is **model-output**, not user input today (Phase 0 uses `FakeConversationRepository`), but in Phase 4 will be Claude API response text. Treat as untrusted.
- **Input — `language: String?`** flows from the fence info string token (`MarkdownTokenTypes.FENCE_LANG`). Also model-controlled. Treat as untrusted.
- **Output:** rendered Compose `Text` nodes inside a `Surface`. No code execution, no shell-out, no file/network IO, no clipboard write, no intent dispatch.

**Findings walk:**

1. **Code-block content is rendered as plain `Text`, not interpreted.** Same posture as #129 (`Security` section, "Code-block content"). The tokeniser walks the bytes to identify keyword / string / number / comment categories, but never `eval`s, never spawns processes, never deserialises. The categorisation only affects what `SpanStyle` is applied; the bytes themselves are still rendered as glyphs by Compose. **No execution path.** PASS.
2. **Language label is rendered as plain `Text`.** A malicious fence info string like `<script>alert(1)</script>` or `../../etc/passwd` is drawn as literal characters in `labelSmall` / `onSurfaceVariant`. No HTML parse, no path resolution. **No execution / no path traversal.** PASS.
3. **Library transitive dependencies.** `dev.snipme:highlights` is a pure Kotlin tokeniser, no AndroidX, no networking, no reflection-based class loading. The dependency surface added is bounded to the highlighter's Kotlin stdlib + KMP runtime — already on the classpath. Verify at wire-up time via `./gradlew :app:dependencies | grep snipme` that no surprising transitive deps appear. **Low net surface increase.** PASS.
4. **Library version pin.** Pinned by `version.ref = "snipmeHighlights"` in the catalog → deterministic build. No SNAPSHOT, no version-range. Resilient against supply-chain version-substitution attacks at build time. PASS.
5. **DoS via pathological code content.** A very long unterminated string, deep nesting, or megabytes of code could in theory exercise quadratic tokeniser paths. Phase 0 messages are bounded by the fake repo; Phase 4 inherits Claude API response-size limits. Out of scope for #130. The defensive `try/catch (Throwable)` (§ Error handling) is the local mitigation if a regression slips through. PASS.
6. **No URL handling added.** This ticket does not touch the `INLINE_LINK` path in `MarkdownText.kt`. The link-scheme allowlist (`isSafeLinkScheme`) from #129 stays unmodified. **No new URL surface.** PASS.
7. **No image / asset fetching.** Code blocks do not embed images. PASS.
8. **Horizontal scroll is inert.** `Modifier.horizontalScroll(rememberScrollState())` is pure layout — no input parsing. PASS.

**Verdict: PASS.** Every category is either inert by construction (plain `Text` rendering, no execution path) or has a deterministic mitigation in the spec (defensive try/catch, scheme allowlist inherited from #129, pinned library version).

**Out of scope (acceptable for Phase 0):**

- Long-content DoS bounds — inherited from #129's stance.
- Library supply-chain audit beyond Maven Central pin + transitive-deps spot-check at wire-up.
- Sanitising the language label against ZWJ / RTL-override Unicode tricks. Phase 0 acceptable.

## Testing strategy

### Unit tests — none

The renderer is pure-rendering Compose code; no logic to assert without a Compose host. Same precedent as `#128` and `#129`: no Robolectric, no Compose JVM-host on `testImplementation`. Building that infrastructure is over-scope.

### Compose UI tests — none

Same precedent as `#128` / `#129`: no `ComposeTestRule` setup in this codebase. Visual verification is by `@Preview` rendering in Android Studio:

1. **`MessageBubbleMarkdownLightPreview`** and **`MessageBubbleMarkdownDarkPreview`** (extended fixture) render Kotlin / JSON / Bash / Markdown fences in light and dark — AC7.
2. The Kotlin block in the fixture exercises keywords-as-tertiary, strings-as-secondary, numbers-as-primary, comments-as-onSurfaceVariant-italic — AC6.
3. The JSON / Bash blocks confirm the language-detection paths reach the library — AC3.
4. The Markdown block confirms the plain-monospace fallback path renders without crash — also AC3 ("works for at least … Markdown" — the spec interprets this as "renders correctly", not "applies token colours" since the library has no Markdown lexer; see § Open questions).
5. Long lines inside the Kotlin block (≥ 80 cols) demonstrate horizontal scroll — AC4.
6. A fence with a language and a fence without (the indented `CODE_BLOCK` path) demonstrate the conditional language label — AC5.

7. **`./gradlew assembleDebug`** must pass.
8. **`./gradlew lint`** must pass with no new warnings.

Code-review treats successful preview rendering + lint pass as the AC acceptance signal.

### `./gradlew test`

No new unit tests added. Existing test suite stays green.

## Open questions

- **Markdown highlighting.** `dev.snipme:highlights` does not currently ship a Markdown lexer. AC3 says "works for at least Kotlin, JSON, Bash, and Markdown"; the spec interprets this as "renders correctly with the block styling (background, monospace, label, scroll) but without token colours" for the Markdown case. The fallback path (§ Error handling) makes this graceful. If a designer or operator pushes back, the follow-up is either (a) a tiny hand-rolled Markdown tokeniser inside `CodeBlock` (~40 lines: spot ATX headings, bold/italic emphasis, fence delimiters), or (b) switching highlighter library. **Not pre-paid here.**
- **JSON support.** Verify at wire-up time that `SyntaxLanguage.JSON` (or the closest equivalent) is exposed by the library version pinned. If JSON is absent, fall through to plain monospace for `json` fences as well — same path as Markdown. Update the resolver mapping in one line.
- **Bold spans on keywords.** The mapping table proposes `fontWeight = FontWeight.Medium` on keywords. If `Medium` looks heavy at `bodyMedium` monospace, drop it — the colour distinction alone is sufficient. Visually previewable.
- **Scrollbar visibility.** Compose's `horizontalScroll` does not render a visible scrollbar by default. Long lines scroll on touch, but there's no visual indicator of overflow. Acceptable for Phase 0; if users miss the affordance, a follow-up adds a thin track via a custom `Modifier` or a fading edge. Not pre-built.
- **Language label collision with long first-line code.** The label overlays the top-right corner of the code body. If the first line is very long and the label is wide, the label can sit on top of code glyphs. Mitigations a future ticket might pick from: (a) add right-padding to the first code line equal to label width, (b) tint the label background, (c) move the label to a top strip after all (closer to Figma). For #130 the simple overlay is shipped and the visual is preview-verifiable.
- **Library version drift.** The pin (`0.9.3` as of 2026-05-16) may not match Maven Central's latest at the moment the developer wires this up. Developer pins to current latest stable; the API surface used here is stable across `0.x` per the upstream README's compatibility note.

## Related

- Spec: [`docs/specs/architecture/129-markdown-rendering-assistant-messages.md`](./129-markdown-rendering-assistant-messages.md) — the predecessor, defines the flat `CodeBlock` this ticket replaces, the inline-code idiom this ticket visually pairs with, and the `MARKDOWN_PREVIEW_FIXTURE` this ticket extends.
- Knowledge: [`docs/knowledge/decisions/0002-markdown-renderer-library.md`](../../knowledge/decisions/0002-markdown-renderer-library.md) — same library-choice posture applies here.
- Knowledge: [`docs/knowledge/features/markdown-text.md`](../../knowledge/features/markdown-text.md) — feature doc. Its downstream note about #130 ("full code-block styling — language label, outline border, syntax highlighting") is realised here, minus the outline border (ticket body pins flat `surfaceContainer`).
- Knowledge: [`docs/knowledge/features/message-bubble.md`](../../knowledge/features/message-bubble.md) — preview-pair shape mirrored by the extended `MARKDOWN_PREVIEW_FIXTURE`.
- Downstream: #132 — streaming caret + animation. Operates at the `MessageBubble` layer; partial markdown including partial code fences flows through this `CodeBlock` unchanged.
