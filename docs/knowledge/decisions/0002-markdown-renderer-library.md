# ADR 0002 — JetBrains markdown parser + hand-rolled Compose renderer

**Status:** Accepted (2026-05-16, with #129).

## Context

#129's brief named `dev.jeziellago:compose-markdown` as the "preferred" library for rendering assistant-message markdown, with JetBrains' `org.jetbrains:markdown` parser paired with a custom Compose renderer as the alternative. The acceptance criteria pin three contracts that the choice must hold against:

- Every text style resolves from `MaterialTheme.typography` — no hardcoded `TextStyle` or `.sp` literals in the renderer.
- Inline code uses M3 `surfaceContainer` background with a monospace `FontFamily`.
- Link taps route through `androidx.compose.ui.platform.LocalUriHandler`.

## Decision

Use **JetBrains' `org.jetbrains:markdown`** as the CommonMark parser and **own the rendering side** as a small set of `@Composable` AST-walk functions in [`MarkdownText.kt`](../features/markdown-text.md). Reject `compose-markdown`.

Library wire-up:

```toml
# gradle/libs.versions.toml
[versions]
jetbrainsMarkdown = "0.7.3"

[libraries]
jetbrains-markdown = { group = "org.jetbrains", name = "markdown", version.ref = "jetbrainsMarkdown" }
```

```kotlin
// app/build.gradle.kts
implementation(libs.jetbrains.markdown)
```

No plugin entry, no KSP / kapt, no proguard rules — the library is pure Kotlin and reflection-free.

## Rationale

- **`compose-markdown` is Markwon-on-AndroidView under the hood.** It renders into a `TextView` via `AndroidView`, not native Compose. That introduces an Android-View interop seam (text selection, accessibility, theming, focus, RTL) that no other surface in the codebase has today. The codebase is otherwise Compose-pure; once accepted the seam is permanent.
- **Markwon's heading sizes are relative multipliers on a single base style** (`fontSize * 1.5 / 1.3 / 1.1` for h1/h2/h3). The AC requires each heading level to map to a **distinct M3 typography slot** (`headlineSmall` / `titleLarge` / `titleMedium`). Achieving that on Markwon means writing a custom plugin that reads `MaterialTheme.typography.*.fontSize.toPx()` and injects values back into Markwon's theme — roughly the same line count as a CommonMark→Compose visitor walk, while still leaving the `AndroidView` seam in place.
- **The JetBrains parser is a pure Kotlin Multiplatform AST builder** (~50 KB jar, no Android deps, no transitive AndroidX). Owning the renderer means "every text style resolves from `MaterialTheme.typography`; no `.sp` literals" holds by construction, not by post-hoc theme injection.
- **The AC element set is small and bounded** (h1–h3, bold, italic, ordered/unordered lists, inline code, blockquote, links, fenced code blocks). A hand-rolled renderer over that surface is ~330 lines including imports and previews — within the ticket's S size estimate.
- **Compose Multiplatform walk-back stays cheap.** The data layer's `kotlinx.datetime` choice ([ADR 0001](./0001-kotlinx-datetime-for-data-layer.md)) follows the same posture: prefer libraries that work on `commonMain`. The JetBrains parser is KMP-native; Markwon would not move to CMP.

## Alternatives considered

- **`dev.jeziellago:compose-markdown` (Markwon-on-AndroidView wrapper).** Rejected — see Rationale. Saves nothing on AC compliance, costs the permanent View interop seam.
- **Markwon directly without the Compose wrapper.** Same seam, fewer features, more wiring. Rejected.
- **CommonMark Java + custom renderer.** Comparable parser quality but JVM-only; loses the KMP option without any feature gain. Rejected.
- **Roll our own parser.** Out of scope — CommonMark edge cases (nested emphasis, link-reference ambiguity, list-tightness rules) eat any line-count savings within a day.

## Consequences

- The renderer surface (`MarkdownText`) is **ours to extend**. Adding tables, strikethrough, task lists, or any other element is a code change in [`MarkdownText.kt`](../features/markdown-text.md), not a library upgrade. The `else ->` fallback in `MarkdownBlock` / `appendInline` keeps unsupported nodes readable (rendered as their raw source text) so the visual never breaks on input.
- **CommonMark flavour by default.** `CommonMarkFlavourDescriptor` covers every AC element. If Claude API responses lean on GFM (tables, strikethrough, task lists), swap to `GFMFlavourDescriptor()` and extend the renderer's `when`-arms. No API change at the call site.
- **Heading slot mapping is opinionated and small.** `h1 → headlineSmall`, `h2 → titleLarge`, `h3 → titleMedium` prioritises in-chat readability over "headings should be page-title sized" — assistant messages are body-flow inside a bubble-less paragraph stream and `displayLarge` would look comically large at chat scale. If a designer pushes back, the change is one line per slot.
- **Library version bumps are low-risk.** The API surface used (`MarkdownParser`, `CommonMarkFlavourDescriptor`, `MarkdownElementTypes`, `MarkdownTokenTypes`, `ASTNode.children`, `getTextInNode`) has been stable across `0.x` releases.
- **Streaming will share the same renderer.** When #132 lands the streaming caret, partial-token `markdown` strings flow through `MarkdownText` unchanged — the `remember(markdown)` re-parses per token batch, which is sub-millisecond for typical message sizes. Incremental-parse is an optimisation for #132 if a trace ever shows pressure; not pre-paid here.

## Related

- Feature doc: [`../features/markdown-text.md`](../features/markdown-text.md)
- Ticket notes: [`../codebase/129.md`](../codebase/129.md)
- Spec: `docs/specs/architecture/129-markdown-rendering-assistant-messages.md`
- Consumer: [`MessageBubble`](../features/message-bubble.md) (assistant variant)
- Pattern sibling: [ADR 0001 — kotlinx-datetime for data layer](./0001-kotlinx-datetime-for-data-layer.md) (same "prefer KMP-portable libraries; pay the renderer ourselves" posture)
