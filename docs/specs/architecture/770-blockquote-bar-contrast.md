# 770 — Blockquote bar contrast against the message bubble

One-binding colour fix in `MarkdownText.kt`: `BlockQuoteBlock`'s `barColor` moves from
`outlineVariant` to `onSurfaceVariant`, the token `TableBlock`'s `borderColor` already carries
since #681 for the same defect class one construct later in the same file.

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt` →
  `BlockQuoteBlock` — the binding that changes; `TableBlock`'s `borderColor` — the comment shape
  and the measured token this fix adopts; the file's `MAX_TABLE_*` / spacing constants and
  `MarkdownBlock`'s `BLOCK_QUOTE` arm — confirming nothing else references the bar.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt` →
  `MARKDOWN_PREVIEW_FIXTURE` — carries a `> Blockquote — single line of quoted text.` line, so the
  existing light/dark preview pair renders the before/after with no new preview code; and
  `MessageContainer`, which grounds every `MarkdownText` call on `secondaryContainer`.
- `docs/knowledge/features/markdown-text.md` § "Tables" and § "Edge cases / limitations" — the
  measured contrast figures, the "ported token, wrong ground" lesson #681 recorded, and the five
  places that currently describe this defect as open (see Documentation handoff).
- `docs/specs/architecture/681-gfm-tables-task-lists-strikethrough.md` — the nearest analogue's
  spec; its table-border revision is the precedent this change follows.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The `Message area` frame #681 and #768 anchored to, unchanged here: a dark thread of message
bubbles in a single muted container tone over a near-black ground, bubble content rendered as plain
body text with a timestamp row. The frame carries **no blockquote counterpart** — no quoted-line
treatment, no vertical rule, no indent construct appears anywhere in it (whole-file component
search, 2026-09-22; the ticket records the same result, as #681 found for `Table`). The bar's
treatment therefore comes from desktop plus this file's own measured M3 token, not from the design
file, and there is no visual target for a fidelity diff beyond "the bar is visible on the bubble".

## Change

`BlockQuoteBlock`'s `barColor` binds `MaterialTheme.colorScheme.outlineVariant` (as shipped since
#129) and moves to `MaterialTheme.colorScheme.onSurfaceVariant`. Measured against the
`secondaryContainer` bubble that `MessageContainer` always grounds this renderer on —
`MessageBubble` is `MarkdownText`'s only caller — `outlineVariant` is **1.00:1 dark / 1.32:1
light**, so the bar is invisible in dark and near-invisible in light; `onSurfaceVariant` is
**7.27:1 light / 5.51:1 dark**, the only measured candidate clearing WCAG 1.4.11's 3:1 floor in
both themes, and already this file's token for the table grid, the task-mark border and struck
text. The bar is a graphical object conveying structure because `BlockQuoteBlock` adds no other
treatment beyond the paragraph italic, which is what puts it under 1.4.11 rather than under the
text-contrast rule.

The binding gains a comment recording the ground it is measured against and the bar any
replacement must clear, in the shape `TableBlock`'s `borderColor` has carried since #681 — the bar
has no assertion behind it and a pixel test would cost more than it proves, so the comment is the
durable record.

Nothing else moves. `BlockquoteBarWidth`, `BlockquoteContentIndent`, the `IntrinsicSize.Min` row,
the paragraph italic and the `MarkdownBlock` recursion for non-paragraph children are untouched;
no other binding in the file changes; no signature, no constant and no dispatch arm changes. After
this edit `colorScheme.outlineVariant` is no longer read anywhere in `MarkdownText.kt` — a
documentation consequence, not a code one (see Documentation handoff).

## Testing strategy

No new test. The change swaps one `MaterialTheme.colorScheme` slot for another in a `Modifier
.background(...)` on a text-free `Box`: there is no logic, no branch and no observable behaviour a
unit test or a Compose UI test can assert without a pixel comparison, which the ticket's AC2
explicitly rejects as costing more than it proves. The existing coverage stands as the regression
net for everything the change must *not* disturb — `MarkdownTextTest` and
`MarkdownTextParsingTest` cover blockquote content rendering and the surrounding dispatch, and
`MessageBubbleMarkdownLightPreview` / `MessageBubbleMarkdownDarkPreview` already render
`MARKDOWN_PREVIEW_FIXTURE`'s blockquote line inside the real bubble, so the before/after is
preview-verifiable with no new preview code. Verification is the scoped unit run for the touched
class plus `lint` and `assembleDebug`.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings. The one untrusted-data boundary in this file is the
  daemon-authored markdown string entering `MarkdownParser` in `MarkdownText`, and this change does
  not touch it: `barColor` is a `MaterialTheme.colorScheme` lookup, structurally unable to take a
  value from the source string. The bar `Box` renders no node content — it has a width, a height
  and a background and holds no children — so no daemon-authored text reaches it. `BlockQuoteBlock`'s
  child walk (`buildInline` for paragraphs, `MarkdownBlock` otherwise) is unchanged, which keeps the
  quoted content on the same scheme-gated inline path as every other construct.
- **[Tokens, secrets, credentials]** Not applicable by construction, not by assertion: this
  composable reaches no store, no `SharedPreferences`, no Keystore and no `data/crypto/` type. The
  file has no credential-holding symbol for the change to expose.
- **[File / storage operations]** Not applicable — no filesystem, cache or `DataStore` path exists
  in `MarkdownText.kt`, and the change adds none.
- **[Inter-process / Android attack surface]** No findings. No intent, deep link, `PendingIntent`,
  content provider or WebView is involved. Worth stating explicitly because the ticket's label turns
  on daemon-authored text: blockquote content continues to render through Compose `Text`, never a
  WebView, and the change does not add a rendering path of any kind.
- **[Cryptographic primitives]** Not applicable — no primitive is reachable from this composable.
- **[Network & I/O]** Not applicable — no socket, no frame, no I/O. The bar cannot trigger a fetch;
  a colour slot has no remote resolution path.
- **[Error messages, logs, telemetry]** No findings, and the omission is deliberate: the change adds
  no log call. A log line on the blockquote path would be a log line about daemon-authored text,
  which is exactly what this repo's logging rule forbids, so the fix stays content-free by adding no
  logging at all.
- **[Concurrency]** Not applicable — no coroutine, no scope, no mutable state. The colour is read
  during composition, the same way the adjacent `TableBlock` binding reads its own.
- **[Threat model alignment]** One scenario considered and dismissed: a hostile daemon reply full of
  `>` markers now draws a *visible* bar where it previously drew an invisible one, so the change
  marginally increases what a hostile reply can render. That is the intended accessibility fix, and
  the bar carries no text, no link, no action and no semantics; it is 4dp wide, bounded by the
  content column's intrinsic height inside the bubble, and drawn in a token already used for the
  table grid and task mark, so it reads as in-bubble structure rather than as system chrome. Fan-out
  is unchanged — no new recursion, no new node kind dispatched. **Out of scope:** the general
  "daemon markdown can imitate app UI" question, which belongs to the renderer's total-fallback
  design rather than to this binding, and the live desktop/mobile comparison of rendered replies,
  owned by #680.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22

## Documentation handoff

Pending for the documentation stage — `docs/knowledge/features/markdown-text.md`, five places the
ticket names, all of which currently describe this defect as open:

- `### Block dispatch` — the `BLOCK_QUOTE` row names `outlineVariant` in its rendering summary.
- `## Edge cases / limitations` — the "Blockquote paragraphs render italic" bullet ends by stating
  the bar is `outlineVariant`.
- `## Edge cases / limitations` — the "One pre-existing rendering defect…" bullet is entirely about
  #770; with #768 already fixed, this bullet has nothing left to carry and goes.
- `## Related` — the "Filed, not fixed here" line names #770.
- `### Tables` — its closing aside says `BlockQuoteBlock`'s bar "has the identical defect … filed as
  #770 rather than fixed here"; rewrite it to record that both now share the one contrast-checked
  token.

One addition found while reading the file, not in the ticket's list:

- `## Configuration` — the "No theme overrides" sentence lists `colorScheme.outlineVariant` among
  the slots this file reads. After this change nothing in `MarkdownText.kt` reads that slot, so the
  name comes out of that list.
