# SessionBoundaryDelimiter

Stateless composable (#135) that renders a single [`ThreadItem.SessionBoundary`](./conversation-repository.md) marker as a horizontal-rule delimiter inside the thread `LazyColumn`. Surfaces the **reason** the session reset (`Clear` / `WorkspaceChange` / `IdleEvict`, defined in #3 and authored on the marker since #192) and an inline `Install` affordance pointing at the memory-plugin docs URL via `LocalUriHandler`. The boundary marker itself is produced by `ConversationRepository.observeMessages` whenever Claude's context resets mid-thread; this component is the rendering half.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt`). Sibling of [`MessageBubble`](./message-bubble.md), [`ToolCallRow`](./tool-call-row.md), [`ConnectionBanner`](./connection-banner.md). Figma reference: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8)'s `Session reset` row (`119:3843`), a rule / label / rule arrangement, restyled here since #644. The explanation sentence and the `Install` button below it have no Figma node of their own and stay implemented per the textual spec — see [Rule / label / rule, explanation retained below (#644)](#rule--label--rule-explanation-retained-below-644).

## Shape

```kotlin
@Composable
fun SessionBoundaryDelimiter(
    boundary: ThreadItem.SessionBoundary,
    modifier: Modifier = Modifier,
)
```

Two params, no defaults on the load-bearing one. Stateless — no `remember`, no `LaunchedEffect`, no coroutine scope. The public composable resolves `TimeZone.currentSystemDefault()`, default `Locale`, and `LocalUriHandler.current`, then delegates to the file-internal `SessionBoundaryDelimiterContent(boundary, uriHandler, modifier)` worker. The injectable-`UriHandler` split mirrors [`MarkdownText`](./markdown-text.md)'s pattern and lets the androidTest exercise the click → URL side effect without `LocalUriHandler` mocking gymnastics.

## What it does

### Rule / label / rule, explanation retained below (#644)

A `Column(fillMaxWidth().padding(start = MessageContentGutter, end = MessageContentGutter, bottom = MessageAreaRowSpacing))` — the same two [`MessageBubble.kt`](./message-bubble.md) constants a message bubble uses for its own gutter and inter-row rhythm, `internal` in that file since #644 specifically so this component (and [`UnrecognizedMessageRow`](./unrecognized-message-row.md)) can share them — containing, in order:

1. **A centred `Row(horizontalArrangement = Arrangement.spacedBy(RuleLabelSpacing = 12.dp), verticalAlignment = CenterVertically)`** of a `weight(1f)` hairline rule, the reason label (`bodySmall`, `colorScheme.primary`, centred), and a second `weight(1f)` rule. Each rule is a `Box(Modifier.height(1.dp).background(outlineVariant.copy(alpha = 0.60f)))`. The design names `Schemes/inverse-primary` at 60% for the rules — #643 mapped that same token at that same alpha onto `outlineVariant` for the header rule, and this restyle reuses the mapping verbatim (M3's divider role, contrast-correct in both schemes, rather than only against the dark reference frame).

   The label is deliberately **unweighted**: `Row` measures a non-weighted child against the full available width before the weighted rules claim any, so a long `Workspace changed to …` label wraps (centred) and squeezes the rules toward zero instead of pushing anything past the viewport edge. Confirmed by the narrow preview and by `SessionBoundaryDelimiterTest` staying green unchanged across the restyle — no `Modifier.weight(1f, fill = false)` fallback was needed.
2. **8.dp `Spacer(ExplanationTopSpacing)`, unchanged in kind from before #644** (previously 4.dp — widened alongside the rest of the restyle) **+ `FlowRow(horizontalArrangement = Arrangement.Center)`** — explanatory sentence and the `Install` `TextButton` on the same flow line so the button reads as inline within the sentence; `FlowRow` (not `Row`) is the load-bearing choice so the sentence wraps cleanly at narrow widths and the button reflows with the trailing text. The text is `"Claude doesn't remember messages above this line. Install a memory plugin to preserve context. "` (`bodySmall` / `onSurfaceVariant` / centered), trailed by `TextButton(onClick = { uriHandler.openUri(MEMORY_PLUGIN_DOCS_URL) }, contentPadding = PaddingValues(0.dp)) { Text("Install") }`.

**The design draws neither the explanation nor the `Install` affordance, and both stay.** `CLAUDE.md` requires the explanatory line and the memory-plugin install affordance under every delimiter variant, this document's own record said so before #644, and `ScriptedSessionBoundaryTest` asserts the explanation string. #644 restyled only the rule-and-label arrangement above them; the explanation `FlowRow` and its `Install` button are otherwise untouched, and all five of `SessionBoundaryDelimiterTest`'s pre-#644 assertions (explanation, the three label prefixes, the Install tap) pass unchanged as the proof.

The above-delimiter opacity treatment (de-emphasizing messages above the line) is **not** in this component — it's the surface responsibility of [#136](../codebase/136.md). [`ThreadScreen`](thread-screen.md) wraps each `LazyColumn` row in `Box(Modifier.alpha(rowAlpha))` against the most-recent-`SessionBoundary` cutoff, so when this delimiter happens to be an older boundary (not the most recent) it inherits the wrapper's `0.55f` alpha end-to-end — both rules, the label `Text`, explanatory `Text`, and the `Install` `TextButton`'s ripple all dim uniformly. This component holds no opacity state of its own; the `Modifier.alpha(...)` is render-only and passes through every child.

### Reason → label mapping

`internal fun boundaryLabel(boundary, timeZone, locale): String` returns one of:

| `boundary.reason`               | Label                                                    |
| ------------------------------- | -------------------------------------------------------- |
| `BoundaryReason.Clear`          | `"New session — $time"`                                  |
| `BoundaryReason.WorkspaceChange`| `"Workspace changed to ${boundary.workspaceCwd!!} — $time"` |
| `BoundaryReason.IdleEvict`      | `"Idle session ended — $time"`                           |

where `$time = formatShortTime(boundary.occurredAt, timeZone, locale)`. The em-dash is the literal U+2014 character, not `--`.

**`workspaceCwd!!` is intentional, not a bug.** The repository invariant (`ConversationRepository.kt:135-142`, asserted in `FakeConversationRepositoryTest` since #192) guarantees `workspaceCwd != null` iff `reason == WorkspaceChange`. The not-null assertion fail-fasts if that invariant is ever violated — a silent empty-string fallback would mask repository regressions. JVM unit test `boundaryLabel fails fast when WorkspaceChange has null cwd` pins the contract (`@Test(expected = NullPointerException::class)`).

### Time formatting

`internal fun formatShortTime(instant, timeZone, locale): String` uses `kotlinx-datetime` for the timezone conversion and `java.time` for the locale-aware formatter:

```kotlin
val localTime = instant.toLocalDateTime(timeZone).time.toJavaLocalTime()
return DateTimeFormatter
    .ofLocalizedTime(FormatStyle.SHORT)
    .withLocale(locale)
    .format(localTime)
```

`FormatStyle.SHORT` renders `"16:32"` on 24-hour locales (`de_DE`, `en_GB`) and `"4:32 PM"` on 12-hour locales (`en_US`). The AC's `"14:32"` example is illustrative — either form is correct per the device locale. JDK builds differ on the AM/PM separator (regular space vs. narrow no-break space U+202F), so the JVM unit test asserts `result.contains("4:32") && result.contains("PM")` separately rather than chasing a literal glyph. **Don't import `formatRelativeTime` from `RelativeTime.kt`** — the Figma sample shows relative time (`— 2 hours ago`) but the AC is canonical and calls for locale short time.

`Min SDK 33` (per `CLAUDE.md`) means `java.time` is on the platform classpath without desugaring; no `coreLibraryDesugaring` dependency needed.

## Memory-plugin docs URL

```kotlin
internal const val MEMORY_PLUGIN_DOCS_URL: String = "https://pyryco.de/docs/memory-plugins"
```

File-internal `const val`, visible to tests so the androidTest can assert the exact URL is forwarded to `UriHandler.openUri`. **Placeholder for Phase 1–2** — `pyryco.de` is the host already used in `MARKDOWN_PREVIEW_FIXTURE` (`MessageBubble.kt`), so the choice is consistent with existing fixtures without committing to a real published page. Phase 3+ will replace this with a deep link into the plugin install flow; that's a one-line constant swap and does **not** change this component's shape or tests (other than the literal in the URL-forwarding assertion).

## Spacing constants

Since #644, the outer gutter and the bottom inter-row spacing are no longer file-private — they are `MessageContentGutter` and `MessageAreaRowSpacing`, `internal` in [`MessageBubble.kt`](./message-bubble.md), shared so this component, the message bubbles, and [`UnrecognizedMessageRow`](./unrecognized-message-row.md) all sit on one rhythm. Three file-private `val`s remain, local to this file's own rule/label layout:

```kotlin
private val RuleLabelSpacing = 12.dp     // gap between each rule and the label
private val RuleThickness = 1.dp
private val ExplanationTopSpacing = 8.dp // was 4.dp pre-#644
```

plus the file-private `RULE_ALPHA = 0.60f` the two rules paint `outlineVariant` at. No raw `.dp` literal at any call site inside the worker. Same named-constants posture as [`MessageBubble`](./message-bubble.md) / [`ToolCallRow`](./tool-call-row.md) / [`ConnectionBanner`](./connection-banner.md). The pre-#644 `DelimiterVerticalPadding` (12.dp) and `DelimiterHorizontalPadding` (16.dp) are gone — superseded by the shared `MessageAreaRowSpacing` (16.dp) and `MessageContentGutter` (20.dp) respectively.

## Recomposition / stability

- `ThreadItem.SessionBoundary` is a `data class` with `kotlinx.datetime.Instant` + nullable `String` fields — all stable.
- `UriHandler` is a Compose-platform interface; the public composable reads it from `LocalUriHandler.current` at composition time. No `remember` needed — the local is stable across recompositions.
- No internal mutable state, no side-effecting handlers beyond the synchronous `uriHandler.openUri` call inside `TextButton.onClick`.

## Configuration

- **No new dependencies.** `kotlinx-datetime` was already on the classpath (its `.toJavaLocalTime()` interop extension is what reaches `java.time`); `java.time.format.DateTimeFormatter` / `FormatStyle` ship with the JDK on min SDK 33.
- **No new string resources.** Literals only — same posture as the rest of `ui/conversations/components/` today.
- **Theme tokens, updated by #644's restyle.** The two rules paint `MaterialTheme.colorScheme.outlineVariant` at 60% alpha (previously the single `HorizontalDivider` used it at full opacity); the reason label moved from `labelSmall` / `onSurfaceVariant` to `bodySmall` / `colorScheme.primary`, matching the design's named `Schemes/primary` role, which reads correctly in both schemes as-is. The explanatory sentence and `Install` button are untouched: `bodySmall` / `onSurfaceVariant`.

## Preview

Three `@Preview` entries, all delegating to a shared private `SessionBoundaryDelimiterPreviewMatrix()` that stacks the three boundary fixtures (`Clear` / `WorkspaceChange` / `IdleEvict`) in a `Column(Arrangement.spacedBy(16.dp))` wrapped in `PyrycodeMobileTheme { Surface { ... } }`:

- `"SessionBoundaryDelimiter — Light"` — `widthDp = 412`, light theme.
- `"SessionBoundaryDelimiter — Dark"` — `widthDp = 412`, `uiMode = UI_MODE_NIGHT_YES`.
- `"SessionBoundaryDelimiter — Narrow"` — `widthDp = 320`, light theme. Exists specifically to verify `FlowRow` wraps cleanly at narrow widths without overflow.

Fixtures use a fixed `Instant.parse("2026-05-17T14:32:00Z")` so previews are deterministic. The `WorkspaceChange` fixture uses `workspaceCwd = "~/Workspace/Projects/KitchenClaw"` to match the Figma example for visual parity.

## Edge cases / limitations

- **`WorkspaceChange` with `null` `workspaceCwd` throws `NullPointerException`.** Intentional fail-fast on a repository invariant violation; see [Reason → label mapping](#reason--label-mapping). Pinned by JVM unit test.
- **Time format varies by device locale.** `FormatStyle.SHORT` is locale-aware; rely on `boundary.occurredAt` and the device's `TimeZone.currentSystemDefault()` / default `Locale`. Tests assert label prefix substrings (`"New session — "` etc.) rather than the exact rendered time, because the emulator and the dev's host JDK may format the same instant slightly differently. Since #644, [`MessageMetaRow`](./message-bubble.md#meta-row-and-copy-control-messagemetarowkt-since-644)'s `formatShortDateTime` reuses this same `formatShortTime` for its time half, and pins its own tests the same locale-robust way.
- **A long reason label wraps and squeezes the rules toward zero rather than overflowing (since #644).** `Row` measures the unweighted label against the full available width before the two `weight(1f)` rules claim any, so a long `Workspace changed to …` label degrades by shrinking the rules first. Verified at the 320dp narrow preview and unchanged by `SessionBoundaryDelimiterTest`.
- **`Install` button is always present, on every variant.** Even when the explanatory copy could feel redundant (e.g. user just did `/clear` deliberately), the AC requires the line under every variant. Showing/hiding by reason was considered and explicitly out of scope. #644's restyle reconfirmed this — the design itself draws neither the explanation nor the button, and both were kept anyway per `CLAUDE.md`.
- **No animation.** The delimiter appears/disappears with the underlying list update; no `AnimatedVisibility`. Acceptable for Phase 0.
- **No a11y review.** The `Install` button inherits `TextButton`'s default semantics (`role = Role.Button`); no `onClickLabel` is set on the `TextButton`. Same open thread as [`ConnectionBanner`](./connection-banner.md)'s retry affordance — tracked there.
- **Unbounded `workspaceCwd` can still drive a layout-cost DoS — pre-existing, not addressed by #644.** The label interpolates `boundary.workspaceCwd` with no length bound on the inbound path; it renders into an unweighted `Text` in a `Row` since the restyle, which wraps identically to the pre-#644 centred `Text`. #644's security review named this rather than fixing it: the right home is a content-length bound in the decode layer, which would cover every render surface (this label and [`MessageBubble`](./message-bubble.md)'s `Message.content`) rather than each component defending itself.

## Related

- Ticket notes: [`../codebase/135.md`](../codebase/135.md), [`../codebase/644.md`](../codebase/644.md)
- Spec: `docs/specs/architecture/135-session-boundary-delimiter.md`, `docs/specs/architecture/644-message-bubbles-and-copy-actions.md`
- Upstream:
  - [`#3`](../codebase/3.md) — `ThreadItem` / `SessionBoundary` / `BoundaryReason` definitions; the input contract this component consumes.
  - [`#9`](../codebase/9.md) — `buildThreadItems` projection that emits `SessionBoundary` markers between session-id deltas (the **Fake** producer, derived from full in-memory history).
  - [Session-transition fold](./session-transition-fold.md) ([`#336`](../codebase/336.md)) — the **real-backend** producer: folds the capability-gated v2 `session_transition` event into the live thread as a `SessionBoundary` (live transitions only). This component renders those rows unchanged.
  - [`#192`](../codebase/192.md) — authored `reason` + `workspaceCwd` on the marker; without this ticket the delimiter could not distinguish the three variants. The seed channels now collectively exercise all three reasons so previews and integration tests have realistic data to render against.
- Component render test: [`#473`](../codebase/473.md) — the e2e-ladder Layer-1b test that drives the **real** [#336 fold](./session-transition-fold.md) through to render and asserts this delimiter draws exactly once, positioned between two cross-session messages (reason `clear`). The first test exercising this component on the real-backend producer rather than the Fake.
- Sibling component patterns: [`MarkdownText`](./markdown-text.md) (injectable-`UriHandler` worker pattern for testable URL side effects), [`ConnectionBanner`](./connection-banner.md) (stateless row primitive in the same package, file shape `constants → public @Composable → private content → preview matrix → preview wrappers`), [`MessageBubble`](./message-bubble.md) / [`ToolCallRow`](./tool-call-row.md) (spacing constants — shared/`internal` with `MessageBubble.kt` since #644, still file-private in `ToolCallRow.kt`).
- Downstream / follow-ups:
  - Wired into `ThreadScreen`'s `LazyColumn` (landed after this component originally shipped — see [Thread screen](thread-screen.md) and [#246](../codebase/246.md)).
  - Above-delimiter opacity treatment landed in [#136](../codebase/136.md): [`ThreadScreen`](thread-screen.md)'s `LazyColumn` wraps each row in `Box(Modifier.alpha(rowAlpha))` against the most-recent-`SessionBoundary` cutoff, dimming rows above (including older `SessionBoundaryDelimiter` instances) to `0.55f`. The component contributed nothing — render-only `Modifier.alpha(...)` on the parent flows through every child without internal opacity state.
  - Open: replace `MEMORY_PLUGIN_DOCS_URL` with the Phase 3+ deep link into the plugin install flow. One-line constant swap.
  - Open: a11y review on the `Install` affordance (`role = Role.Button` is default; `onClickLabel = "Open memory plugin docs"` is the obvious hook).
