# #1356 — Keep a stopped turn's reason in the thread

## Files read

- `data/repository/HistoryPageReducer.kt` — `withFinalizedTurn` (the one place both lanes settle a turn), `withHistoryEntry`'s `TYPE_TURN_END` arm, `alreadyHolds`, `joinIdentity`, the `holds*` predicates and `mergeHistoryRows` / `mergeCachedRows`.
- `data/repository/ThreadProjection.kt` — `finalizeAssistantTurn`, the live caller of `withFinalizedTurn`.
- `data/repository/ConversationRepository.kt` — the `ThreadItem` sealed interface; `Banner` and `ModelRefusal` KDoc for the identity-invariant shape.
- `data/model/LiveSessionEvent.kt` — `TurnEnd` (absent wire fields arrive as `""` / `false`).
- `data/cache/FileConversationCache.kt` — `CachedThreadRow`, `toRecord`, `toDomain`, `decodeThread` (#1353's per-kind arms).
- `data/cache/ConversationCache.kt` — `cacheableThreadRows` keeps every kind but `UnrecognizedMessage`, so the new row is cached with no change there.
- `ui/conversations/thread/ThreadRow.kt` — `ThreadItem.listKey`.
- `ui/conversations/thread/ThreadScreen.kt` — the delivered-row `when` and `ThreadItem.timestamp`.
- `ui/conversations/components/BannerNoticeRow.kt` — the bare muted row this ticket mirrors; `AgentName.kt` — `agentName`.
- `ui/conversations/components/TurnOutcomeIndicator.kt` — `inertOutcomeToken`, which this ticket deliberately does **not** reuse (the ticket names desktop's sanitizer instead). The status-area arm stays as it is; #1357 changes it.
- Desktop `src/renderer/src/screens/conversation/ConversationScreen.tsx` — `stoppedReportText`, `stoppedTurnText`; `stoppedTurn.test.tsx` — the case table copied into the tests here.
- Lesson from `caching-conversation-repository.md` (#1353): `joinIdentity` and `alreadyHolds` encode the same identity twice; both must gain the new kind together, and `HistoryPageReducerTest.mergeCached_eachKindJoinsItsLiveTwinOnItsKeyAlone` pins them equal.

Overlapping in-flight branches: #1340, #1346, #1352 (`ThreadScreen.kt`, `strings.xml`) and #1355 (`ThreadProjection.kt` KDoc). None restructures the blocks touched here; edits stay additive.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=620-1576 (Thread notification, state "No details", node 620:1574), as used in node 16-8.

One line of bare text in the message stream: `MaterialTheme.typography.bodyMedium`, `colorScheme.onSurfaceVariant`, no bubble, border or icon, the `MessageContentGutter` side gutters and `MessageAreaRowSpacing` below — the treatment `BannerNoticeRow` already draws. The "Stopped" lead is client-owned copy, so the meaning does not depend on colour.

## Context

A turn's ending is shown only as `ThreadViewModel.turnOutcome`, a status-area value cleared when the next turn starts, so the reason is lost once the user sends again. Desktop keeps a `turnBoundary` row per `turn_end`, live and from history, and draws "Stopped: …" when `stoppedTurnText` returns text. This ticket gives mobile the same persistent row. No decision record needed: it follows #873/#1353's row-kind pattern.

## Design

**`ThreadItem.StoppedTurn(turnId: String, reason: String, category: String, occurredAt: Instant)`** in `ConversationRepository.kt`. Holds only values that already crossed `stoppedReportText`: `reason` is the derived reason token (`""` means "error"), `category` the sanitized `error_category` (`""` means none). Identity: `turnId`; unique among a thread's stopped rows, a producer obligation (`holdsStoppedTurn`) like the sibling kinds.

**New `data/repository/StoppedTurn.kt`** (pure, no Android imports):

- `stoppedReportText(value: String): String` — desktop's `stoppedReportText`: `""` when the value's UTF-8 length exceeds 256 bytes (a lone surrogate counts 3, as `TextEncoder` encodes U+FFFD), else the value with every code point of type `CONTROL`, `FORMAT`, `LINE_SEPARATOR` or `PARAGRAPH_SEPARATOR` removed. Iterates code points, so astral format characters go too.
- `LiveSessionEvent.TurnEnd.stoppedTurn(occurredAt: Instant): ThreadItem.StoppedTurn?` — desktop's rule: null for `stopReason == "cancelled"`, and null when `!isError` and the sanitized outcome is `""` or `success`. Reason: sanitized `terminal_reason` when non-empty and not `completed`; else `error_max_turns` → `max_turns`, `error_max_budget_usd` → `budget_exhausted`; else `api_error` when the sanitized category is non-empty; else `""` for `success`, else the outcome.

**`withFinalizedTurn(event, occurredAt: Instant)`** (`HistoryPageReducer.kt`) settles the turn as today, then end-appends `event.stoppedTurn(occurredAt)` unless the thread already holds a stopped row for that turn. Callers: `ThreadProjection.finalizeAssistantTurn` passes `Clock.System.now()` (as `applyAssistantDelta` stamps rows), `withHistoryEntry`'s turn-end arm passes `entry.timestamp`. `withSettledTurns` is unchanged, so #1419's late settling adds no row.

**Merge.** `alreadyHolds` → `holdsStoppedTurn(row.turnId)`; `joinIdentity` → `listOf("stopped", turnId)`. A history page's row and the live row of one turn join once; `mergeCachedRows` places a cache-only one beside its neighbours as for every kind.

**Cache.** `CachedThreadRow.stopped: CachedStoppedTurn? = null` with `(turnId, reason, category, occurredAt)`; `toRecord` / `toDomain` arms; `decodeThread` rejects a repeated `turnId` among stopped rows. Defaulted to null, so a document written before this change still loads.

**UI.** `ThreadItem.listKey` → `"stopped:$turnId"` (a distinct namespace literal). `ThreadItem.timestamp` → `occurredAt`. New `components/StoppedTurnRow.kt`: `StoppedTurnRow(item, agent, modifier)` draws one `Text` of `stoppedTurnLabel(item, agent)`, a `@Composable` that re-applies `stoppedReportText` to both fields (so a row restored from disk is held to the same rule), maps the reason to copy and appends the category clause. Copy as string resources:

| reason | text |
|---|---|
| `max_turns` | Stopped: turn limit reached |
| `budget_exhausted` | Stopped: budget exhausted |
| `prompt_too_long` | Stopped: context too long, compact or reset |
| `api_error` | Stopped: API error |
| `hook_stopped`, `stop_hook_prevented` | Stopped by a hook |
| `model_error` | Stopped: model error |
| other non-empty | Stopped: <reason> |
| `""` | Stopped: error |

A non-empty category gives `"<text> (<agentName> reported: <category>)"`. `ThreadScreen` renders the row with `state.agent`. Light and dark previews.

## State and concurrency model

No new state or jobs. The row is written inside the existing atomic `threadByConversation.update` in `finalizeAssistantTurn` and inside the pure history fold; `FileConversationCache` writes it through the existing mutex and atomic move.

## Error handling

A malformed `turn_end` already costs only its envelope or entry (the decode `try` in both lanes). `stoppedTurn` is total. A cache document with a repeated stopped `turnId` or a row of no or several kinds is rejected as `invalid_data`, as today. No log is added: the row's strings are agent-authored and never logged; the existing turn-end handling stays the lifecycle trace.

## Testing strategy

- `StoppedTurnTest` (unit): the rule table from desktop's `stoppedTurn.test.tsx` — terminal-reason preference (with `outcome = error_max_turns`), outcome fallbacks (`terminal_reason = completed`), suppression of cancelled / `isError = false` + success / legacy empty, category → `api_error`; `stoppedReportText` removes line breaks, NUL, bidi overrides and isolates, U+2028/2029 and an astral tag character, and treats 257 bytes (including a multi-byte case at the edge) as absent.
- `HistoryPageReducerTest`: a page with an erroring `turn_end` yields the row after the turn's last row; a cancelled / successful one yields none; the live `withFinalizedTurn` row and the page's row merge once by `turnId`; `mergeCached_eachKindJoinsItsLiveTwinOnItsKeyAlone` gains the kind.
- `ThreadProjection`/repository live path: a live erroring `turn_end` adds one row; a duplicate adds none; the row stays after the next turn's delta.
- `FileConversationCacheTest`: round-trip of the row and a process restart (new cache instance); a pre-change document still loads; a repeated `turnId` is rejected.
- `StoppedTurnRowTest` (sharedTest, Robolectric): the copy table rendered, both agent names, and hostile reason/category text (line break, bidi override, over 256 bytes) cannot change the fixed text.

Not operator-facing as a new flow (a row drawn from an existing frame, no action), so no rung-3 scenario.

## Open Questions

- Does any existing test assert a whole thread after an erroring `turn_end`? Resolved during implementation by running the affected suites; expectations change only where a row is now correct.
  - Resolution (2026-10-02): none did. The only existing test change is `RemoteConversationRepositoryTest`'s exhaustive `threadShape` `when`, which gains the new kind. The design is unchanged.

## Documentation handoff

Pending for the documentation stage: update the thread row topics (`docs/knowledge/features/thread-screen*.md`, `banner-notice-row.md`'s sibling-row notes) and `docs/knowledge/features/turn-outcome-indicator.md` with the stopped-turn row and its rule.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] The `turn_end` fields are agent-authored text crossing into Compose. One boundary: `stoppedReportText` in `StoppedTurn.kt`, applied when the row is built and again in `stoppedTurnLabel` at render. Downstream holds only sanitized tokens. SHOULD FIX (implementation): the render path must be a plain `Text` — no markdown, no link detection, no `SelectionContainer`, no use as a key other than the namespaced `turnId` list key — and the category is attributed through the client-owned agent name, not the token.
- [Trust boundaries] `turnId` is daemon-supplied and becomes a `LazyColumn` key. Uniqueness is enforced by `holdsStoppedTurn` in both writers and by `decodeThread` on restore; the `stopped:` prefix keeps it out of other namespaces. A hostile duplicate loses the second row (fail-safe), never crashes the list.
- [Tokens] No findings: no secret touches this path.
- [Files and storage] The row joins the existing app-private, atomically written thread cache (`FileConversationCache.writeAtomically`); no path derives from row data (file names are SHA-256 of ids). A tampered cache cannot bypass the inert rule because render re-sanitizes.
- [Android surface] No findings: no component, intent or WebView is added.
- [Cryptography] No findings: no crypto involved.
- [Network & I/O] No findings: no new frame or send; the existing frame-size cap bounds the input, and the 256-byte rule bounds what renders.
- [Errors and logs] The row's strings must never be logged; the plan adds no log line, and `CachedStoppedTurn` stays `private` to its file like the sibling records.
- [Concurrency] No findings: writes ride the existing atomic `update` and the cache mutex.
- [Threat model] Hostile daemon frame: handled — bidi or line-break tokens cannot reorder or break the client-owned "Stopped" lead, oversized ones fall back to "Stopped: error". Malicious relay: cannot alter in-session frames; a replayed `turn_end` dedups by `turnId`. Disk theft and UI leakage: unchanged from existing message content in the same cache.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-02
