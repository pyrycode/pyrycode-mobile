# SessionBoundaryDelimiter

Stateless composable (#135) that renders a single [`ThreadItem.SessionBoundary`](./conversation-repository.md) marker as a horizontal-rule delimiter inside the thread `LazyColumn`. **Since #1578, it draws only the rule / reason-label / rule row** — no explanation line, no memory-search copy, no Install button — for every `boundary.reason` and whatever the current session's memory-search report says. Of the three `boundary.reason` values (`Clear` / `WorkspaceChange` / `IdleEvict`), only `IdleEvict` gets its own label text — since #1498 the product has no workspaces, so `WorkspaceChange` renders the same "New session — <time>" copy as `Clear`. The boundary marker itself is produced by `ConversationRepository.observeMessages`; this component is the rendering half.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt`). Sibling of [`MessageBubble`](./message-bubble.md), [`ToolCallRow`](./tool-call-row.md), [`ConnectionBanner`](./connection-banner.md). Figma reference: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8)'s `Session reset` row (`119:3843`), a rule / label / rule arrangement, restyled here since #644. Frames `675:3682` and `675:5883` still draw an `Explanation` text node under the rule row and fade the rows above it; by decision on #1578 (Juhana, 2026-10-03; #1580 closed as not needed) the app keeps only the rule row and drops both — recorded as a "no separate frame" row in `app/src/androidTest/assets/design-1220/README.md`. The same file also hosts [`CompactionBoundaryDivider`](#compactionboundarydivider-874-1358) (#874, #1358), a finished-compaction row that reuses this component's rule/label/rule layout but is not a session boundary.

## Shape

```kotlin
@Composable
fun SessionBoundaryDelimiter(
    boundary: ThreadItem.SessionBoundary,
    modifier: Modifier = Modifier,
)
```

`boundary` is the only parameter. Stateless — no `remember`, `LaunchedEffect`, or coroutine scope, and (since #1578) no `LocalUriHandler` read either: with the explanation and Install gone, nothing in this composable has a side effect to wire. It resolves `TimeZone.currentSystemDefault()` and the default `Locale`, computes the reason label, and renders it through the shared `RuleLabelRow` worker (also used by `CompactionBoundaryDivider`), tagged `SESSION_BOUNDARY_TEST_TAG`.

## What it does

### Rule / label / rule only (#644; inset since #1512; explanation and Install dropped #1578)

The column (`Modifier.padding(start = MessageContentGutter + SessionBoundaryInset, end = MessageContentGutter + SessionBoundaryInset, bottom = MessageAreaRowSpacing)`, carrying `Modifier.testTag(SESSION_BOUNDARY_TEST_TAG)`) holds a single centred `Row(horizontalArrangement = Arrangement.spacedBy(RuleLabelSpacing = 12.dp), verticalAlignment = CenterVertically)`: a `weight(1f)` hairline rule, the reason label (`bodySmall`, `colorScheme.primary`, centred), and a second `weight(1f)` rule. Each rule is a 1dp `Box` painted with `colorScheme.inversePrimary` at 60% in the fixed dark palette (`LocalStaticDarkPalette`); other palettes use `outlineVariant` at the same alpha. This matches reset node `119:3843`. [The 412 × 892 code and boundary comparison](https://github.com/pyrycode/pyrycode-mobile/blob/44ac0889/app/src/androidTest/assets/thread-message-1207/code-boundary-side-by-side.png) uses the node inspected on 2026-09-29; Figma's last-modified date was unavailable.

The label is deliberately **unweighted**: `Row` measures a non-weighted child against the full available width before the weighted rules claim any, so a long label wraps (centred) and squeezes the rules toward zero instead of pushing anything past the viewport edge. Confirmed by the narrow preview. Since #1498 the longest label any reason actually produces is `"New session — <time>"`, so this degradation path has no live reason to exercise it, but it stays in place because nothing bounds a future reason's label length.

**Since #1578, nothing else renders here.** The "<agent> doesn't remember messages above this line." sentence, the memory-search copy, and the inline `Install` `TextButton` are gone for every reason and every `MemorySearchReport` state — they were never a Figma node (frames `675:3682` / `675:5883` draw an `Explanation` text node of their own, which the app does not implement; see [Rule / label / rule](#rule--label--rule-only-644-inset-since-1512-explanation-and-install-dropped-1578) above). The memory-plugin install offer survives in [channel overflow](thread-overflow-menu.md) and [Channel info](channel-info-sheet.md), both still gated on `shouldOfferMemoryInstall()`; the boundary itself no longer reads a `MemorySearchReport` at all. `SessionBoundaryDelimiterScreenTest` asserts the explanation text is absent for every reason and report combination, rather than covering report states that no longer change anything here.

**Every thread row draws at full opacity, since #1578.** The above-delimiter fade this section used to describe — [`ThreadScreen`](thread-screen.md) wrapping each `LazyColumn` row in `Box(Modifier.alpha(rowAlpha))` against the most-recent-`SessionBoundary` cutoff — is removed along with `ABOVE_DELIMITER_ALPHA` and the cutoff lookup; nothing in `ThreadScreen` dims rows above a boundary any more. `ThreadRowOpacityTest` pixel-checks that an older bubble and a newer one draw the same colour.

### Reason → label mapping

`internal fun boundaryLabel(boundary, timeZone, locale): String` returns one of:

| `boundary.reason`               | Label                                                    |
| ------------------------------- | -------------------------------------------------------- |
| `BoundaryReason.Clear`          | `"New session — $time"`                                  |
| `BoundaryReason.WorkspaceChange`| `"New session — $time"`                                  |
| `BoundaryReason.IdleEvict`      | `"Idle session ended — $time"`                           |

where `$time = formatShortTime(boundary.occurredAt, timeZone, locale)`. The em-dash is the literal U+2014 character, not `--`.

**The product has no workspaces any more (#1498).** `WorkspaceChange` renders the same copy as `Clear` and never reads `boundary.workspaceCwd`, so a null `workspaceCwd` on a `WorkspaceChange` boundary is no longer a fail-fast case — it renders "New session — <time>" like any other. `BoundaryReason`, the wire decoder and `ThreadItem.SessionBoundary.workspaceCwd` are unchanged, because the protocol still admits `workspace_change`; only this rendering arm stopped reading the field. The demo seed's "Pyrycode Mobile" channel now ends its history session with `BoundaryReason.Clear` and no seeded `workspaceCwd`, so none of the seed channels exercise `WorkspaceChange` any more — `FakeConversationRepositoryTest` pins the seeded set as `{Clear, IdleEvict}`.

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

Shared `internal const val`, used by [Channel info](channel-info-sheet.md) and [channel overflow](thread-overflow-menu.md) — the boundary itself stopped reading it in #1578. Their Install controls open the same destination through `UriHandler.openUri`. The URL remains the existing memory-plugin documentation destination.

## Spacing constants

Since #644, the outer gutter and the bottom inter-row spacing are no longer file-private — they are `MessageContentGutter` and `MessageAreaRowSpacing`, `internal` in [`MessageBubble.kt`](./message-bubble.md), shared so this component, the message bubbles, and [`UnrecognizedMessageRow`](./unrecognized-message-row.md) all sit on one rhythm. Four file-private `val`s remain, local to this file's own rule/label layout:

```kotlin
private val RuleLabelSpacing = 12.dp     // gap between each rule and the label
private val RuleThickness = 1.dp
private val SessionBoundaryInset = 20.dp // since #1512, session delimiter only
```

(`ExplanationTopSpacing` was removed with the explanation row in #1578.)

plus the file-private `RULE_ALPHA = 0.60f`. The rule source is `inversePrimary` in fixed dark and `outlineVariant` otherwise. No raw `.dp` literal appears inside the worker. The former delimiter padding is superseded by shared `MessageAreaRowSpacing` (16.dp) and `MessageContentGutter` (20.dp).

**Since #1512, the session delimiter's column pads by `MessageContentGutter + SessionBoundaryInset`, not the gutter alone.** The `675:3797` `Session boundary` frame adds its own 20 px padding inside the 20 px message gutter, so at 412 px width the rule row spans x 40–372 rather than x 20–392. `CompactionBoundaryDivider` pads `RuleLabelRow` directly with the gutter alone and is unaffected — it keeps the gutter-to-gutter width (x 20–392). The two composables share `RuleLabelRow` but no longer share identical width, so don't assume they still match pixel-for-pixel when touching either one's padding.

## Recomposition / stability

- `ThreadItem.SessionBoundary` is a `data class` with `kotlinx.datetime.Instant` + nullable `String` fields — all stable.
- No internal mutable state, no side-effecting handlers, no `LocalUriHandler` read (dropped #1578 along with the `Install` button it drove).

## Configuration

- **No new dependencies.** `kotlinx-datetime` was already on the classpath (its `.toJavaLocalTime()` interop extension is what reaches `java.time`); `java.time.format.DateTimeFormatter` / `FormatStyle` ship with the JDK on min SDK 33.
- **No new string resources.** Literals only — same posture as the rest of `ui/conversations/components/` today.
- **Theme tokens.** The rules use `inversePrimary` in fixed dark and `outlineVariant` otherwise, both at 60% alpha; the reason label uses `bodySmall` / `colorScheme.primary`.

## Preview

Three `@Preview` entries, all delegating to a shared private `SessionBoundaryDelimiterPreviewMatrix()` that stacks the three boundary fixtures (`Clear` / `WorkspaceChange` / `IdleEvict`) in a `Column(Arrangement.spacedBy(16.dp))` wrapped in `PyrycodeMobileTheme { Surface { ... } }`:

- `"SessionBoundaryDelimiter — Light"` — `widthDp = 412`, light theme.
- `"SessionBoundaryDelimiter — Dark"` — `widthDp = 412`, `uiMode = UI_MODE_NIGHT_YES`.
- `"SessionBoundaryDelimiter — Narrow"` — `widthDp = 320`, light theme. Exists specifically to verify the rule/label row degrades (wraps the label, squeezes the rules) rather than overflowing at narrow widths.

Fixtures use a fixed `Instant.parse("2026-05-17T14:32:00Z")` so previews are deterministic. The `WorkspaceChange` fixture still sets `workspaceCwd = "~/Workspace/Projects/KitchenClaw"` to match the field's shape on the wire, but since #1498 the label ignores it and renders the same "New session — <time>" text as the `Clear` fixture.

## Edge cases / limitations

- **Time format varies by device locale.** `FormatStyle.SHORT` is locale-aware; rely on `boundary.occurredAt` and the device's `TimeZone.currentSystemDefault()` / default `Locale`. Tests assert label prefix substrings (`"New session — "` etc.) rather than the exact rendered time, because the emulator and the dev's host JDK may format the same instant slightly differently. Since #644, [`MessageMetaRow`](./message-bubble.md#meta-row-and-copy-control-messagemetarowkt-since-644)'s `formatShortDateTime` reuses this same `formatShortTime` for its time half, and pins its own tests the same locale-robust way.
- **A long reason label wraps and squeezes the rules toward zero rather than overflowing (since #644).** `Row` measures the unweighted label against the full available width before the two `weight(1f)` rules claim any, so a long label degrades by shrinking the rules first. Since #1498 no reason actually produces a label long enough to wrap at ordinary widths, but the degradation path stays in place for whatever reason the wire adds next. Verified at the 320dp narrow preview.
- **No Install affordance on the boundary itself, since #1578.** The memory-plugin install offer lives only in the thread overflow menu and the channel info sheet now; neither still reads a report at the boundary row. A report with no providers is unknown unless aggregate availability explicitly says `Absent` — that logic is unchanged, just relocated to the two surfaces that still read it.
- **No animation.** The delimiter appears/disappears with the underlying list update; no `AnimatedVisibility`. Acceptable for Phase 0.
- **The layout-cost DoS that #644's security review named against `workspaceCwd` is moot since #1498.** `boundaryLabel` no longer interpolates `boundary.workspaceCwd` into the rendered text for any reason, so an attacker-controlled `workspace_change` cwd on the wire cannot inflate this label. `workspaceCwd` itself is still unbounded on the decode path, since the field remains part of the wire contract for whatever else might read it; a content-length bound at decode, as #644's review recommended, is still the right home if another render surface ever interpolates it.

## CompactionBoundaryDivider (#874, #1358)

A compaction, folded from two daemon frames rather than one. The `compacting` falling edge (split from \#654,
landed the way [`Banner` landed](banner-notice-row.md): one ticket for the type, both decode lanes,
and the row) now draws the divider itself, stamped with that edge's own `ts`, as soon as the compaction
ends — reading "Compaction failed" when the edge reported one, else the unreported "Conversation
compacted" below. A following `compaction_boundary` frame then replaces that divider **in place** (#1358):
same row position, but the boundary's `ts`, counts and trigger. A `compaction_boundary` with no edge
before it — the only shape mobile decoded before #1358 — still appends a divider on its own. Lives in this
same file, beside `SessionBoundaryDelimiterContent`, because it draws the same Figma `Session reset` rule
/ label / rule row and the decoders and the renderer are each other's only consumer.

```kotlin
@Composable
fun CompactionBoundaryDivider(item: ThreadItem.CompactionBoundary, modifier: Modifier = Modifier)
```

- **Shares the rule/label/rule layout via a private `RuleLabelRow(label, modifier)`**, extracted from
  the original `SessionBoundaryDelimiterContent`'s `Row` when this divider was added, and since #1578
  `SessionBoundaryDelimiter` itself calls the same `RuleLabelRow(label, modifier)` directly —
  `SessionBoundaryDelimiterContent` was removed rather than kept as a near-empty wrapper once the
  explanation it wrapped was gone. This composable draws **no explanation line and no `Install`
  affordance**, same as the session delimiter since #1578; a compaction was never a session reset, so
  nothing here ever read `LocalUriHandler` or offered a memory-plugin install.
- **Is not a session boundary.** It carries no `SESSION_BOUNDARY_TEST_TAG` and does not affect any
  other row's rendering. Every `LazyColumn` row, this one included, draws at full opacity since #1578.
- **`internal fun compactionBoundaryLabel(item: ThreadItem.CompactionBoundary): String`** — desktop's
  `compactionBoundaryTitle`, including its failed branch since #1358: `item.failed` short-circuits to
  `"Compaction failed"` with no counts and no "by you", regardless of whatever else the row carries.
  Otherwise `"Conversation compacted"`, then `", $pre → $post tokens"` **only** when both
  `item.preTokens` and `item.postTokens` are non-null, then `" by you"` **only** when `item.manual`. A
  missing, `null`, negative, or unsafe-large count claims no size — never `"→ 0"` — because
  [`ThreadItem.CompactionBoundary`](conversation-repository.md)'s counts are already narrowed to a
  validated `Long?` before this label ever sees them; an unrecognised or empty `trigger` never reaches
  here at all, since `manual` is a `Boolean` already reduced from the open wire string at decode. `failed`
  is itself reduced at decode, from `CompactingPayloadDto.failed()` (`compact_result == "failed"` or a
  non-empty `compact_error`) — neither claude-authored string reaches this label, the row, or the cache;
  only the boolean does.
- **`internal fun compactionTokenCount(value: Long): String`** — desktop's `tokenCount` for an
  already-validated non-negative value: the plain number below 1000, otherwise tenths of a thousand
  rounded half-up (`(value + 50) / 100`, integer arithmetic) with a trailing `.0` dropped and a `k` suffix
  — `24000` → `"24k"`, `1250` → `"1.3k"`. **Integer arithmetic is load-bearing, not a style choice**: a
  float formatter (`String.format`) would print `"1,3k"` on a German-locale phone, since the fraction
  format is entirely avoidable arithmetic rather than a locale-aware render.
- **Cached (#1353)** — kept by `cacheableThreadRows` and mapped by `FileConversationCache.toRecord` to
  `CachedCompaction(preTokens, postTokens, manual, occurredAt, failed)`, a `null` token count omitted on
  encode and read back as `null`; `failed` (#1358) is defaulted `false` on the cached record, so a thread
  document written before #1358 still decodes and every row it holds reads as not failed. See
  [Conversation cache § The contract](conversation-cache.md#the-contract). Before #1353 this row was
  excluded here and restored only by history replay joined to a live arrival of the same frame on the
  envelope's `ts` — see [Remote conversation repository § The
  compaction-boundary decode+fold seam](remote-conversation-repository-live-stream-and-modals.md#the-compaction-boundary-decodefold-seam-874)
  — which stopped running on a routine reopen once history started loading only on request, so a
  restored thread lost this row until it was cached instead.
- **Identity is `occurredAt`** (`ThreadItem.CompactionBoundary`'s field name for the protocol's `(type,
  ts)` join key), read by `ThreadRow.listKey()` as `"compaction:$occurredAt"` and by
  `HistoryPageReducer.holdsCompactionBoundary`, the one shared predicate every writer dedups on — the same
  three-reader shape [`Banner`](banner-notice-row.md#the-thread-row-type) documents, so a boundary
  received live and again in a history page never crashes the `LazyColumn` on a duplicate key. Since
  #1358 a divider's `ts` is not fixed for its whole life: a divider drawn from the `compacting` falling
  edge takes that edge's `ts` first, and if a `compaction_boundary` later replaces it **in place**, the
  row takes the boundary frame's `ts` instead — one shared fold (`HistoryPageReducer`'s
  `CompactionFold`/`withCompactingEdge`/`withCompactionBoundary`) runs on both the live lane
  (`ThreadProjection.applyCompacting`/`applyCompactionBoundary`) and the history reduction
  (`reduceHistoryPage`), so a divider born on one lane and replayed from a history page always resolves
  to the same `ts`, live and history never disagree on a key, and merging a page into a thread that
  already holds a divider adds no duplicate. When a history page races the live lane and already holds
  the filled-in row under the boundary's `ts`, the live fold removes the still-pending edge divider
  instead of writing a second row under that same key.

### Testing

- `CompactionBoundaryLabelTest` (JVM): sizes + manual, sizes only, manual only, neither, one count
  `null`; a failed row (by `compact_result` or by a markup-and-URL `compact_error`) always reads
  "Compaction failed" regardless of counts or `manual` (#1358); `compactionTokenCount` below 1000, exact
  thousands, half-up rounding, and a large value.
- `CompactionBoundaryDividerTest` (androidTest): the label renders; the row has no click action.
- `SessionBoundaryDelimiterTest` (androidTest, pre-existing): unchanged and still green — the proof the
  `RuleLabelRow` extraction preserved the session delimiter's own rendering.
- `ThreadProjectionTest` (#1358): the live lane's `withCompactingEdge`/`withCompactionBoundary` fold —
  a failed and an unreported falling edge each add one divider at the edge's `ts`; a following boundary
  replaces the unreported one in place at the boundary's `ts`; a boundary with no edge appends one; a
  second rising edge forgets the pending divider; a falling edge with no rising edge adds nothing; a
  boundary whose `ts` a merged history page already holds removes the pending divider instead of
  duplicating it.
- `HistoryPageReducerTest` (#1358): the same fold run over a stored page produces the same rows the live
  lane would, and merging that page into a thread that already holds them live adds none.
- `FileConversationCacheThreadTest` (#1358): a failed divider round-trips through the cache; a document
  with no `failed` key reads as not failed.

## Related

- Ticket notes: [`../codebase/135.md`](../codebase/135.md), [`../codebase/644.md`](../codebase/644.md).
  #874 (`CompactionBoundaryDivider`) and [#1112](https://github.com/pyrycode/pyrycode-mobile/issues/1112)
  (the `agent` param) postdate the frozen codebase archive (closed 2026-09-05); their specs are the only
  ticket-level record.
- Spec: `docs/specs/architecture/135-session-boundary-delimiter.md`, `docs/specs/architecture/644-message-bubbles-and-copy-actions.md`,
  `docs/specs/architecture/874-compaction-boundary-divider.md`,
  `docs/specs/architecture/1112-agent-name-reset-and-boundary.md`,
  `docs/specs/architecture/1358-failed-and-unreported-compaction-dividers.md`,
  `docs/specs/architecture/1512-session-delimiter-inset.md`
- Upstream:
  - [`#3`](../codebase/3.md) — `ThreadItem` / `SessionBoundary` / `BoundaryReason` definitions; the input contract this component consumes.
  - [`#9`](../codebase/9.md) — `buildThreadItems` projection that emits `SessionBoundary` markers between session-id deltas (the **Fake** producer, derived from full in-memory history).
  - [Session-transition fold](./session-transition-fold.md) ([`#336`](../codebase/336.md)) — the **real-backend** producer: folds the capability-gated v2 `session_transition` event into the live thread as a `SessionBoundary` (live transitions only). This component renders those rows unchanged.
  - [`#192`](../codebase/192.md) — authored `reason` + `workspaceCwd` on the marker; without this ticket the delimiter could not distinguish the three variants. Since [#1498](#reason--label-mapping) the seed channels exercise only `Clear` and `IdleEvict` — the product dropped workspace-change copy, so the demo seed no longer seeds a `WorkspaceChange` boundary.
- Component render test: [`#473`](../codebase/473.md) — the e2e-ladder Layer-1b test that drives the **real** [#336 fold](./session-transition-fold.md) through to render and asserts this delimiter draws exactly once, positioned between two cross-session messages (reason `clear`). The first test exercising this component on the real-backend producer rather than the Fake.
- Sibling component patterns: [`MarkdownText`](./markdown-text.md) (injectable-`UriHandler` worker pattern for testable URL side effects), [`ConnectionBanner`](./connection-banner.md) (stateless row primitive in the same package, file shape `constants → public @Composable → private content → preview matrix → preview wrappers`), [`MessageBubble`](./message-bubble.md) / [`ToolCallRow`](./tool-call-row.md) (spacing constants — shared/`internal` with `MessageBubble.kt` since #644, still file-private in `ToolCallRow.kt`).
- Downstream / follow-ups:
  - Wired into `ThreadScreen`'s `LazyColumn` (landed after this component originally shipped — see [Thread screen](thread-screen.md) and [#246](../codebase/246.md)).
  - Above-delimiter opacity treatment landed in [#136](../codebase/136.md) and was **removed in #1578**: `ThreadScreen`'s `LazyColumn` no longer wraps rows in `Modifier.alpha(rowAlpha)` against a most-recent-boundary cutoff, so every row (including older `SessionBoundaryDelimiter` instances) draws at full opacity.
  - Open: replace `MEMORY_PLUGIN_DOCS_URL` with the Phase 3+ deep link into the plugin install flow, at its two remaining call sites (channel overflow, Channel info). One-line constant swap.
