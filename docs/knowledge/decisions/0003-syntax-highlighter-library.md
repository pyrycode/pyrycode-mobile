# ADR 0003 — `dev.snipme:highlights` for code-block syntax highlighting

**Status:** Accepted (2026-05-16, with #130).

## Context

#130 needs syntax highlighting for fenced code blocks inside [`MarkdownText`](../features/markdown-text.md). The ticket body named two candidate libraries (`kotlin-multiplatform-codehighlights` and `Highlights`) — these resolve to the same artifact, `dev.snipme:highlights`. The acceptance criteria pin three contracts the choice must hold against:

- Token colours derive from `MaterialTheme.colorScheme` (`tertiary` as the primary accent) — **no hardcoded hex values** anywhere in the renderer.
- Languages covered include at least Kotlin, JSON, Bash, and Markdown.
- The wire-up stays within the project's "S size" budget — one library, one composable rewrite, ≤100 lines of production code.

The codebase's library-choice posture from [ADR 0002](./0002-markdown-renderer-library.md) applies: prefer KMP-portable, Compose-native libraries; pay the renderer ourselves rather than accept an `AndroidView` interop seam.

## Decision

Use **`dev.snipme:highlights` 1.1.0** as the tokeniser. The renderer (`buildHighlightedCode` in [`MarkdownText.kt`](../features/markdown-text.md)) **owns the colour mapping** — the library returns `CodeStructure` with `PhraseLocation(start, end)` lists per token category (keywords, annotations, strings, literals, comments, multiline comments); we walk each list and apply `SpanStyle(color = MaterialTheme.colorScheme.<slot>)` per span.

Library wire-up:

```toml
# gradle/libs.versions.toml
[versions]
snipmeHighlights = "1.1.0"

[libraries]
snipme-highlights = { group = "dev.snipme", name = "highlights", version.ref = "snipmeHighlights" }
```

```kotlin
// app/build.gradle.kts
implementation(libs.snipme.highlights)
```

No plugin entry, no KSP / kapt, no proguard rules. Same three-line wire-up shape as `org.jetbrains:markdown` ([ADR 0002](./0002-markdown-renderer-library.md)).

Token → M3 slot mapping:

| `CodeStructure` field | M3 slot | Span attributes |
|---|---|---|
| `keywords` | `colorScheme.tertiary` | `fontWeight = FontWeight.Medium` |
| `annotations` | `colorScheme.tertiary` | — |
| `strings` | `colorScheme.secondary` | — |
| `literals` | `colorScheme.primary` | — |
| `comments` / `multilineComments` | `colorScheme.onSurfaceVariant` | `fontStyle = FontStyle.Italic` |
| Punctuation / operators / default | inherited `LocalContentColor.current` | — |

Language-name → `SyntaxLanguage` mapping (case-insensitive, trimmed):

| Fence info string | Resolved language |
|---|---|
| `kotlin`, `kt`, `kts` | `SyntaxLanguage.KOTLIN` |
| `bash`, `sh`, `shell`, `zsh` | `SyntaxLanguage.SHELL` |
| `json` | `SyntaxLanguage.JAVASCRIPT` (no dedicated JSON lexer; subset match) |
| `markdown`, `md` | `null` — plain monospace fallback |
| anything else | `null` — plain monospace fallback |

## Rationale

- **Pure Kotlin Multiplatform, no `AndroidView` seam.** Same posture as [ADR 0002](./0002-markdown-renderer-library.md). No transitive AndroidX, no reflection. CMP walk-back ([`CLAUDE.md`](../../../CLAUDE.md)) stays cheap; the tokeniser runs on `commonMain` if the data layer ever leaves Android.
- **Structural output, not pre-coloured spans.** The library returns `PhraseLocation` ranges grouped by category, not an `AnnotatedString` with pre-applied colours. That's exactly the seam we need to make AC6 ("no hardcoded hex values") hold by construction: the renderer reads `MaterialTheme.colorScheme.tertiary` / `.secondary` / `.primary` / `.onSurfaceVariant` at composition time and writes them into the `AnnotatedString` builder. The library never sees a colour value.
- **Reflection-free, no proguard rules.** Wire-up is one `[versions]` line + one `[libraries]` line + one `implementation(...)` line — same shape as [ADR 0002](./0002-markdown-renderer-library.md). If the wire-up had required a Gradle plugin entry or KSP, that would have been a re-evaluation signal.
- **The two candidate names resolve to the same artifact.** The ticket body named "`kotlin-multiplatform-codehighlights`" and "`Highlights`"; both are aliases / old names for `dev.snipme:highlights`. Pinned to `1.1.0` (the current Maven Central stable on 2026-05-16). API surface used (`Highlights.Builder().code(...).language(...).build().getCodeStructure()`, the `SyntaxLanguage` enum, the `CodeStructure` field shape) has been stable across `0.x` and `1.x` releases.
- **Tertiary as the primary accent** matches the ticket body's "Syntax accent color: `Schemes/Tertiary`" pin. Keywords + types are the visually dominant token categories in most code, so binding both to `tertiary` makes the colour story two-stop (`tertiary` carries the syntax weight; `secondary` and `primary` accent strings and numbers). Comments use `onSurfaceVariant` + italic — the M3-conventional "auxiliary text" slot.

## Alternatives considered

- **`io.github.kbiakov:codeview-android` (Android-only highlighter view).** Rejected. JVM-only, ships its own `AndroidView`, undermines the CMP walk-back trigger in [`CLAUDE.md`](../../../CLAUDE.md). Same rejection rationale as `dev.jeziellago:compose-markdown` in [ADR 0002](./0002-markdown-renderer-library.md).
- **Hand-rolled tokeniser per language.** Rejected for the four-language minimum. Kotlin alone needs keyword set, string escapes, nested template expressions, character literals, comments, KDoc — comparable code size to using the library, without the multi-language coverage. Cost-benefit only flips if `dev.snipme:highlights` is later removed and we need to ship one language; not relevant today.
- **Markwon's `syntax-highlight` plugin.** Rejected — Markwon is the renderer we explicitly avoided in [ADR 0002](./0002-markdown-renderer-library.md). Bringing Markwon back through the highlighter dependency would re-introduce the `AndroidView` seam.
- **`io.noties.markwon.syntax`-style integration with an existing JS-bundled highlighter (e.g. Prism / highlight.js via WebView).** Rejected — WebView interop is an even heavier seam than `AndroidView`, the colour theme is fixed by the JS lib, and AC6 wouldn't hold.

## Consequences

- **Renderer-side colour mapping is ours to extend.** Adding new token categories (e.g. type names as a separate accent, regex literals) is a code change in `buildHighlightedCode` in [`MarkdownText.kt`](../features/markdown-text.md), not a library upgrade. If a future ticket wants per-language colour overrides (e.g. JSON keys in a distinct slot), the mapping is local — extend the `when` over a hypothetical category bucket, don't reach for a library plugin.
- **Language coverage is bounded by the library's `SyntaxLanguage` enum.** Adding TypeScript, Python, YAML, etc. is a one-line addition to `resolveSyntaxLanguage` in [`MarkdownText.kt`](../features/markdown-text.md) — but only if the library exposes the language. JSON and Markdown specifically have no dedicated lexer in `1.1.0`; JSON substitutes `JAVASCRIPT` (subset grammar), Markdown falls through to plain monospace. If Markdown highlighting becomes a real requirement, the cheapest path is a ~40-line hand-rolled tokeniser inside `CodeBlock` (ATX headings, emphasis pairs, fence delimiters) rather than swapping the library.
- **Library failures degrade to plain monospace, not a crash.** `tokeniseCode` wraps the library call in `runCatching { … }.getOrNull()`; on throw, the block falls through to plain monospace (no spans). Same posture as the link-no-op fallback in [ADR 0002](./0002-markdown-renderer-library.md). User-visible degradation is "monospace on `surfaceContainer`" — already the [#129](../codebase/129.md) baseline.
- **Span offsets are clamped before `addStyle`.** `AnnotatedString.Builder.addStyle` throws `IndexOutOfBoundsException` on out-of-range offsets, which would crash the assistant message if the library ever returns offsets exceeding the input length. The `applySpan` helper coerces ranges; the renderer can't crash on a library offset regression. Cost is two `coerceIn` calls per span.
- **Library version bumps are low-risk.** The API surface used is stable across `0.x` and `1.x` releases. The pin is `version.ref` (no SNAPSHOT, no version-range), so the build is deterministic against supply-chain version-substitution.
- **No streaming-specific handling.** [#132](https://github.com/pyrycode/pyrycode-mobile/issues/132) will pass partial code-block content through `CodeBlock` per token batch; the `remember(content, syntaxLanguage)` re-tokenises per batch (sub-millisecond on typical sizes). Incremental tokenisation is a #132-scope optimisation if a trace ever shows pressure — not pre-paid here.

## Related

- Feature doc: [`../features/markdown-text.md`](../features/markdown-text.md) (CodeBlock section)
- Ticket notes: [`../codebase/130.md`](../codebase/130.md)
- Spec: `docs/specs/architecture/130-code-block-rendering-syntax-highlighting.md`
- Pattern sibling: [ADR 0002 — markdown renderer library](./0002-markdown-renderer-library.md) (same "prefer KMP-portable, Compose-native libraries; pay the renderer ourselves" posture; same three-line wire-up shape)
- Foundational posture: [ADR 0001 — kotlinx-datetime for data layer](./0001-kotlinx-datetime-for-data-layer.md) (KMP-portable library preference)
