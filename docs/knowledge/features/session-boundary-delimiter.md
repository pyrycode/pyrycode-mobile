# SessionBoundaryDelimiter

Stateless composable (#135) that renders a single [`ThreadItem.SessionBoundary`](./conversation-repository.md) marker as a horizontal-rule delimiter inside the thread `LazyColumn`. It shows the reset reason (`Clear` / `WorkspaceChange` / `IdleEvict`) and time, always explains that the conversation's agent does not remember messages above the line, and offers the memory-plugin docs link only when the current session's report confirms absence. The boundary marker itself is produced by `ConversationRepository.observeMessages`; this component is the rendering half.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt`). Sibling of [`MessageBubble`](./message-bubble.md), [`ToolCallRow`](./tool-call-row.md), [`ConnectionBanner`](./connection-banner.md). Figma reference: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8)'s `Session reset` row (`119:3843`), a rule / label / rule arrangement, restyled here since #644. The explanation sentence and the `Install` button below it have no Figma node of their own and stay implemented per the textual spec — see [Rule / label / rule, explanation retained below (#644)](#rule--label--rule-explanation-retained-below-644). The same file also hosts [`CompactionBoundaryDivider`](#compactionboundarydivider-874) (#874), a finished-compaction row that reuses this component's rule/label/rule layout but is not a session boundary.

## Shape

```kotlin
@Composable
fun SessionBoundaryDelimiter(
    boundary: ThreadItem.SessionBoundary,
    modifier: Modifier = Modifier,
    agent: ConversationAgent = ConversationAgent.Claude,
    memorySearch: MemorySearchReport = MemorySearchReport.Unknown,
)
```

`boundary` is the only required parameter. Stateless — no `remember`, `LaunchedEffect`, or coroutine scope. The public composable resolves `TimeZone.currentSystemDefault()`, default `Locale`, and `LocalUriHandler.current`, then delegates to the file-internal `SessionBoundaryDelimiterContent` worker. `agent` defaults to `Claude`; `memorySearch` defaults to `Unknown`, so isolated callers cannot accidentally show Install. In production, [`ThreadScreen`](thread-screen.md) passes the current session's `state.runConfig.memorySearch`.

## What it does

### Rule / label / rule, explanation retained below (#644)

A `Column(fillMaxWidth().padding(start = MessageContentGutter, end = MessageContentGutter, bottom = MessageAreaRowSpacing))` — the same two [`MessageBubble.kt`](./message-bubble.md) constants a message bubble uses for its own gutter and inter-row rhythm, `internal` in that file since #644 specifically so this component (and [`UnrecognizedMessageRow`](./unrecognized-message-row.md)) can share them — containing, in order:

1. **A centred `Row(horizontalArrangement = Arrangement.spacedBy(RuleLabelSpacing = 12.dp), verticalAlignment = CenterVertically)`** of a `weight(1f)` hairline rule, the reason label (`bodySmall`, `colorScheme.primary`, centred), and a second `weight(1f)` rule. Each rule is a 1dp `Box` painted with `colorScheme.inversePrimary` at 60% in the fixed dark palette (`LocalStaticDarkPalette`); other palettes use `outlineVariant` at the same alpha. This matches reset node `119:3843` without changing label behavior. [The 412 × 892 code and boundary comparison](https://github.com/pyrycode/pyrycode-mobile/blob/44ac0889/app/src/androidTest/assets/thread-message-1207/code-boundary-side-by-side.png) uses the node inspected on 2026-09-29; Figma's last-modified date was unavailable.

   The label is deliberately **unweighted**: `Row` measures a non-weighted child against the full available width before the weighted rules claim any, so a long `Workspace changed to …` label wraps (centred) and squeezes the rules toward zero instead of pushing anything past the viewport edge. Confirmed by the narrow preview and by `SessionBoundaryDelimiterTest` staying green unchanged across the restyle — no `Modifier.weight(1f, fill = false)` fallback was needed.
2. **8.dp `Spacer(ExplanationTopSpacing)` + `FlowRow(horizontalArrangement = Arrangement.Center)`** — the always-present sentence `"${agentDisplayName(agent)} doesn't remember messages above this line."` uses `bodySmall` / `onSurfaceVariant` / centered. When `memorySearch.shouldOfferMemoryInstall()` is true, it is followed by `"Search stored knowledge with a memory plugin."` and an inline `Install` `TextButton` opening `MEMORY_PLUGIN_DOCS_URL`. The helper requires aggregate `Absent` with no installed provider. Installed, disabled, unavailable, unknown, and omitted reports show neither install copy nor button. `FlowRow` allows the optional text and button to wrap at narrow widths. Search retrieves stored knowledge; it does not save the conversation, preserve context, or make every earlier message available.

The Figma row has a generic “Session reset” label and draws neither explanation nor Install. The product keeps its reason/time label and agent-specific explanation for every reset; only a confirmed absent report adds search copy and link. The reason/time label is independent of memory status. `SessionBoundaryDelimiterScreenTest` covers the report states.

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

Shared `internal const val`, used by the boundary, [Channel info](channel-info-sheet.md) and [channel overflow](thread-overflow-menu.md). Their Install controls open the same destination through `UriHandler.openUri`. The URL remains the existing memory-plugin documentation destination.

## Spacing constants

Since #644, the outer gutter and the bottom inter-row spacing are no longer file-private — they are `MessageContentGutter` and `MessageAreaRowSpacing`, `internal` in [`MessageBubble.kt`](./message-bubble.md), shared so this component, the message bubbles, and [`UnrecognizedMessageRow`](./unrecognized-message-row.md) all sit on one rhythm. Three file-private `val`s remain, local to this file's own rule/label layout:

```kotlin
private val RuleLabelSpacing = 12.dp     // gap between each rule and the label
private val RuleThickness = 1.dp
private val ExplanationTopSpacing = 8.dp // was 4.dp pre-#644
```

plus the file-private `RULE_ALPHA = 0.60f`. The rule source is `inversePrimary` in fixed dark and `outlineVariant` otherwise. No raw `.dp` literal appears inside the worker. The former delimiter padding is superseded by shared `MessageAreaRowSpacing` (16.dp) and `MessageContentGutter` (20.dp).

## Recomposition / stability

- `ThreadItem.SessionBoundary` is a `data class` with `kotlinx.datetime.Instant` + nullable `String` fields — all stable.
- `UriHandler` is a Compose-platform interface; the public composable reads it from `LocalUriHandler.current` at composition time. No `remember` needed — the local is stable across recompositions.
- No internal mutable state, no side-effecting handlers beyond the synchronous `uriHandler.openUri` call inside `TextButton.onClick`.

## Configuration

- **No new dependencies.** `kotlinx-datetime` was already on the classpath (its `.toJavaLocalTime()` interop extension is what reaches `java.time`); `java.time.format.DateTimeFormatter` / `FormatStyle` ship with the JDK on min SDK 33.
- **No new string resources.** Literals only — same posture as the rest of `ui/conversations/components/` today, including `agentDisplayName`'s two-value mapping (#1112).
- **Theme tokens.** The rules use `inversePrimary` in fixed dark and `outlineVariant` otherwise, both at 60% alpha; the reason label uses `bodySmall` / `colorScheme.primary`. The explanation and optional search copy use `bodySmall` / `onSurfaceVariant`.

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
- **Install depends on the report, not the reset reason.** A report with no providers is still unknown unless aggregate availability explicitly says `Absent`; never turn omitted or malformed status into an installation prompt. The reason/time and context-reset explanation remain visible for every status.
- **No animation.** The delimiter appears/disappears with the underlying list update; no `AnimatedVisibility`. Acceptable for Phase 0.
- **No a11y review.** The `Install` button inherits `TextButton`'s default semantics (`role = Role.Button`); no `onClickLabel` is set on the `TextButton`. Same open thread as [`ConnectionBanner`](./connection-banner.md)'s retry affordance — tracked there.
- **Names the conversation's own agent since #1112.** [`ThreadScreen`](thread-screen.md) passes
  `state.agent` with the current memory report at the `SessionBoundary` row dispatch. The focused
  Codex test pins the agent name and, with an explicit absent report, the Install button.
- **Unbounded `workspaceCwd` can still drive a layout-cost DoS — pre-existing, not addressed by #644.** The label interpolates `boundary.workspaceCwd` with no length bound on the inbound path; it renders into an unweighted `Text` in a `Row` since the restyle, which wraps identically to the pre-#644 centred `Text`. #644's security review named this rather than fixing it: the right home is a content-length bound in the decode layer, which would cover every render surface (this label and [`MessageBubble`](./message-bubble.md)'s `Message.content`) rather than each component defending itself.

## CompactionBoundaryDivider (#874)

A finished compaction, folded from the daemon's `compaction_boundary` frame (split from #654, landed the
way [`Banner` landed](banner-notice-row.md): one ticket for the type, both decode lanes, and the row).
Lives in this same file, beside `SessionBoundaryDelimiterContent`, because it draws the same Figma
`Session reset` rule / label / rule row and the decoder and the renderer are each other's only consumer.

```kotlin
@Composable
fun CompactionBoundaryDivider(item: ThreadItem.CompactionBoundary, modifier: Modifier = Modifier)
```

- **Shares the rule/label/rule layout via a private `RuleLabelRow(label, modifier)`**, extracted from
  `SessionBoundaryDelimiterContent`'s own `Row` in the same change — `SessionBoundaryDelimiterContent` now
  calls `RuleLabelRow(label)` too, and its rendering is otherwise unchanged; `SessionBoundaryDelimiterTest`
  staying green unchanged is the proof the extraction didn't alter the session delimiter's shape. Unlike
  `SessionBoundaryDelimiter`, this composable draws **no explanation line and no `Install` affordance** —
  a compaction is not a session reset, so nothing here reads `LocalUriHandler` or offers a memory-plugin
  install.
- **Is not a session boundary.** It does not enter `ThreadScreen`'s `mostRecentSessionBoundaryIndex`
  cutoff and changes no above-delimiter de-emphasis; the row inherits the same `Modifier.alpha(rowAlpha)`
  wrapper as its neighbours purely because every `LazyColumn` item does.
- **`internal fun compactionBoundaryLabel(item: ThreadItem.CompactionBoundary): String`** — desktop's
  `compactionBoundaryTitle` without its failed branch (mobile decodes `compaction_boundary` only, never
  `compact_result`/`compact_error`): `"Conversation compacted"`, then `", $pre → $post tokens"` **only**
  when both `item.preTokens` and `item.postTokens` are non-null, then `" by you"` **only** when
  `item.manual`. A missing, `null`, negative, or unsafe-large count claims no size — never `"→ 0"` — because
  [`ThreadItem.CompactionBoundary`](conversation-repository.md)'s counts are already narrowed to a
  validated `Long?` before this label ever sees them; an unrecognised or empty `trigger` never reaches
  here at all, since `manual` is a `Boolean` already reduced from the open wire string at decode.
- **`internal fun compactionTokenCount(value: Long): String`** — desktop's `tokenCount` for an
  already-validated non-negative value: the plain number below 1000, otherwise tenths of a thousand
  rounded half-up (`(value + 50) / 100`, integer arithmetic) with a trailing `.0` dropped and a `k` suffix
  — `24000` → `"24k"`, `1250` → `"1.3k"`. **Integer arithmetic is load-bearing, not a style choice**: a
  float formatter (`String.format`) would print `"1,3k"` on a German-locale phone, since the fraction
  format is entirely avoidable arithmetic rather than a locale-aware render.
- **Cached (#1353)** — kept by `cacheableThreadRows` and mapped by `FileConversationCache.toRecord` to
  `CachedCompaction(preTokens, postTokens, manual, occurredAt)`, a `null` token count omitted on encode
  and read back as `null`; see [Conversation cache § The contract](conversation-cache.md#the-contract).
  Before #1353 this row was excluded here and restored only by history replay joined to a live arrival
  of the same frame on the envelope's `ts` — see [Remote conversation repository § The
  compaction-boundary decode+fold seam](remote-conversation-repository-live-stream-and-modals.md#the-compaction-boundary-decodefold-seam-874)
  — which stopped running on a routine reopen once history started loading only on request, so a
  restored thread lost this row until it was cached instead.
- **Identity is `occurredAt`** (`ThreadItem.CompactionBoundary`'s field name for the protocol's `(type,
  ts)` join key), read by `ThreadRow.listKey()` as `"compaction:$occurredAt"` and by
  `HistoryPageReducer.holdsCompactionBoundary` / `RemoteConversationRepository.appendCompactionBoundary`,
  the one shared predicate both writers dedup on — the same three-reader shape [`Banner`](banner-notice-row.md#the-thread-row-type)
  documents, so a boundary received live and again in a history page never crashes the `LazyColumn` on a
  duplicate key.

### Testing

- `CompactionBoundaryLabelTest` (JVM, new): sizes + manual, sizes only, manual only, neither, one count
  `null`; `compactionTokenCount` below 1000, exact thousands, half-up rounding, and a large value.
- `CompactionBoundaryDividerTest` (androidTest, new): the label renders; the row has no click action.
- `SessionBoundaryDelimiterTest` (androidTest, pre-existing): unchanged and still green — the proof the
  `RuleLabelRow` extraction preserved the session delimiter's own rendering.

## Related

- Ticket notes: [`../codebase/135.md`](../codebase/135.md), [`../codebase/644.md`](../codebase/644.md).
  #874 (`CompactionBoundaryDivider`) and [#1112](https://github.com/pyrycode/pyrycode-mobile/issues/1112)
  (the `agent` param) postdate the frozen codebase archive (closed 2026-09-05); their specs are the only
  ticket-level record.
- Spec: `docs/specs/architecture/135-session-boundary-delimiter.md`, `docs/specs/architecture/644-message-bubbles-and-copy-actions.md`,
  `docs/specs/architecture/874-compaction-boundary-divider.md`,
  `docs/specs/architecture/1112-agent-name-reset-and-boundary.md`
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
