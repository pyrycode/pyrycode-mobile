# #804 — Show claude's usage-limit report in the thread status area

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/UsageLimitProjection.kt` → `UsageLimitProjection.observe`, `isReadable` — the expiry rule lives here and is applied **on read only**; an already-subscribed collector gets no emission at the deadline, so the render side must re-read on its own cadence without re-deriving the rule.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `observeUsageLimit`, `UsageLimitReading` — the defaulted `flowOf(null)` read and the five-field value this slice renders.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `observeUsageLimit`, `switchToLive` — the facade the ViewModel holds; `currentRepository` is a `StateFlow`, so a re-subscription emits the live value first (no `null` flicker).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `apiRetry`, `isCompacting`, `thinkingProgress` — the sibling `StateFlow` shape the new `usageLimit` joins.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadStatusArea`, `ThreadScreen` — the single mutually-exclusive status slot and its precedence `when`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ApiRetryIndicator.kt` → `ApiRetryIndicator`, `isRenderableCounter` — row idiom (16/8 dp padding, 8 dp gap, `bodySmall` / `onSurfaceVariant`, merged semantics) and the render-or-decline gate posture.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/CompactingIndicator.kt` → `CompactingIndicator` — same idiom; early-return totality.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt` → `formatShortTime` — the app's localized SHORT time idiom over kotlinx-datetime.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `CONVERSATION_THREAD` destination — collects each VM flow and passes it to `ThreadScreen`, inside `HostDestination` (host scoping).
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt` → `pushApiRetry`, `apiRetryEnvelope`, `start` — the scripting seam the new `pushRateLimited` joins.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedApiRetryTest.kt` — the shape of the scripted regression.
- `docs/knowledge/features/usage-limit-state.md` — render-boundary obligations inherited from #802: inert text, defensive date formatting, copy that claims neither blocking nor lifting; the `StateFlow` conflation test idiom.
- `../pyrycode/docs/protocol-mobile.md` § `rate_limited` — wire SSOT (cited, not restated).
- pyrycode-desktop `src/renderer/src/screens/conversation/usageLimitNotice.ts` — wording reference only (not layout); its range-guarded reset formatter and "same local day → time only" rule are carried over.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8 (status area sub-node `111:3525`)

The status area is a single row at the top of the composer: a small leading glyph, 8 dp gap, then one `bodySmall` label (the trailing contextual-action slot stays empty until #675). The shipped arms render that row as `ApiRetryIndicator` / `CompactingIndicator` (16×8 dp padding, `bodySmall`, `onSurfaceVariant`); the new arm reuses exactly that row with a 16 dp `Icons.Outlined.Info` glyph in place of the spinner — a usage-limit report is not progress, and a spinner would imply a wait the frame does not prove.

## Context

#802 decoded `rate_limited` into `observeUsageLimit`. This slice renders it as a new arm of `ThreadStatusArea`. Ladder after this slice, top wins: api-retry → **usage limit** → compaction → thinking. No ADR needed.

The protocol's wording hazards drive the whole design: a frame is not proof the turn was blocked; `status` is claude's open string (opaque label, attributed to claude, no branching); `resets_at` is unvalidated in both directions; `utilization` is usually absent and not a bounded fraction.

## Design

### `UsageLimitIndicator` (new, `ui/conversations/components/UsageLimitIndicator.kt`)

```kotlin
@Composable fun UsageLimitIndicator(reading: UsageLimitReading?, modifier: Modifier = Modifier)
```

Early-return on `null` (sibling totality idiom). Otherwise one `Row` (sibling padding/gap), `Icon(Icons.Outlined.Info, contentDescription = null, 16.dp, tint = onSurfaceVariant)` and a `Text` in `bodySmall` / `onSurfaceVariant`, `maxLines = 2`, `TextOverflow.Ellipsis`. The row's merged `contentDescription` is the visible label itself (one source of truth for the wording; the label is already a full sentence). `now` is captured with `remember(reading) { Clock.System.now() }`, and the time zone / locale come from the system defaults at composition.

The label is composed from client-owned string resources plus three render-or-decline helpers, all `internal` pure functions in the same file so JVM unit tests pin them:

| Helper | Contract |
|---|---|
| `usageLimitStatusLabel(status: String, truncatedFields: List<String>?): String?` | claude's status as an inert display string: ISO-control and Unicode format (`Cf`, incl. bidi overrides) characters replaced by a space and trimmed, cut to `MAX_STATUS_CHARS = 40`; an ellipsis is appended when this cut happened **or** the daemon listed `"status"` in `truncatedFields`. Returns `null` when nothing printable is left. No comparison against any value. |
| `usageLimitSpentPercent(utilization: Double?): Int?` | `null` when absent, non-finite, or outside `0.0..1.0`; otherwise `roundToInt(utilization * 100)`. Never `0` for an absent reading. |
| `formatUsageLimitReset(resetsAt: Long, now: Instant, timeZone: TimeZone, locale: Locale): String?` | `null` when `resetsAt <= 0` (0 = not reported, tested first), when `resetsAt <= now` (past; the projection already hides these, defence in depth), or when `resetsAt - now > MAX_RESET_HORIZON_SECONDS` (31 days — the observed windows are 5 h and 7 d; year-40000 values never reach a date constructor). Otherwise a localized SHORT time when the reset falls on today's local date, else a localized SHORT date + time. |

Label assembly (strings.xml, all client-owned):

- lead: `thread_usage_limit_label` = `Claude reports usage-limit status: %1$s` — or `thread_usage_limit_label_no_status` = `Claude reported a usage-limit update` when the status helper returns `null`.
- optional ` · %1$d%% spent` (`thread_usage_limit_spent`) when the percent helper is non-null.
- optional ` · resets %1$s` (`thread_usage_limit_resets`) when the reset helper is non-null.

Nothing in the copy says "limited", "blocked", "reached" or "lifted": the lead is attributed reportage, which is honest for `allowed_warning` and for any unmeasured status alike. `limitType` is not rendered (not required by the AC; keeps the untrusted surface to one string).

### `ThreadViewModel.usageLimit` (sibling `StateFlow`)

```kotlin
val usageLimit: StateFlow<UsageLimitReading?>
```

`usageLimitRereads().flatMapLatest { repository.observeUsageLimit(conversationId) }.stateIn(viewModelScope, WhileSubscribed(5_000), null)`, where `usageLimitRereads()` is a private cold ticker emitting `Unit` immediately and then every `USAGE_LIMIT_REREAD_MS = 30_000`. Each tick re-subscribes, which re-evaluates the projection's `isReadable` against the current clock — so a displayed reading leaves within ≤ 30 s of its `resets_at` **without** the VM re-deriving the expiry rule and **without** any delay computed from `resetsAt` (a fixed cadence only). `StateFlow` equality suppresses identical re-reads, so the screen does not recompose on ticks. Opt-in `@OptIn(ExperimentalCoroutinesApi::class)` for `flatMapLatest` on this property.

### `ThreadScreen` / `ThreadStatusArea`

`ThreadScreen` gains `usageLimit: UsageLimitReading? = null` (default keeps every existing caller and preview compiling), passed to `ThreadStatusArea`, whose `when` gains one arm after api-retry and before compaction:

```
apiRetry != NotRetrying -> ApiRetryIndicator
usageLimit != null      -> UsageLimitIndicator
isCompacting            -> CompactingIndicator
else                    -> ThinkingIndicator
```

The KDoc on `ThreadStatusArea` is updated to name the new ladder.

### `MainActivity`

Collect `vm.usageLimit` with `collectAsStateWithLifecycle()` and pass it to `ThreadScreen`. Host/conversation scoping is structural: the VM is per conversation inside `HostDestination`, the facade is per host, the projection per connection.

## State + concurrency model

One new cold chain on `viewModelScope`, started/stopped by `WhileSubscribed(5_000)`; the ticker's `delay` is its only suspension and is cancelled with the collector. Main dispatcher only (no IO). No state is held outside the `StateFlow`.

## Error handling

No failure path of its own: the helpers are total and decline to `null` rather than throw. A hostile or absurd reading costs at most one row of inert text with no date and no percent.

## Testing strategy

- **JVM unit** `app/src/test/.../components/UsageLimitIndicatorFormatTest.kt`: status helper (verbatim pass-through, control/bidi chars stripped, 40-char cut adds ellipsis, daemon `truncatedFields` adds ellipsis, blank → `null`); percent helper (`null` → `null`, `0.94` → 94, `0.0` → 0, `-0.1` / `1.5` / `NaN` / `Infinity` → `null`); reset helper (`0` → `null`, negative → `null`, past → `null`, year-40000 → `null`, same-day → time only, next-day → date + time, both against fixed `now`/UTC/`Locale.US`).
- **JVM unit** `ThreadViewModelTest`: `usageLimit` initial `null` with a plain fake; reflects a reading then its clear; observes only its own conversation id; **re-reads on the fixed cadence** — a controllable double whose `observeUsageLimit` returns the reading or `null` by a switch flipped mid-test, asserting the value drops after `advanceTimeBy(USAGE_LIMIT_REREAD_MS)` with no upstream emission (the expiry-while-displayed case). Uses `runCurrent`/`advanceTimeBy`, never `advanceUntilIdle` (the ticker is perpetual while subscribed).
- **Rung 2 scripted** `ScriptedUsageLimitTest` on `ScriptedThreadHarness` (new `pushRateLimited(status, limitType, resetsAt, utilization, truncatedFields)`), in the `ScriptedApiRetryTest` shape: label shows status verbatim and replaces thinking; shows while idle; benign `allowed` frame clears it; a reading with a past `resets_at` never shows; `resets_at = 0` and year-40000 render no reset clause; absent `utilization` renders no `%`; out-of-range renders no `%`; api-retry wins the slot over it and it wins over compaction.
- No rung-3/4 scenario here: live behaviour is #679 per the ticket.

## Documentation handoff

Pending for the documentation stage: fold this render arm into `docs/knowledge/features/usage-limit-state.md` (its "Rendering … is a sibling slice … not yet filed" line) and the status-area ladder in the thread-screen / thread-status overviews. No shared doc is edited here.

## Open questions

- In-flight overlap: `origin/feature/859` touches `ThreadViewModel.kt`, but only a five-line KDoc hunk inside the drop function, far from the sibling `StateFlow` block this plan adds. Proceeding rather than blocking; before opening the PR, `git merge-tree` against that branch must show no conflict, recorded in the PR.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] Addressed in-plan — the only daemon-authored text reaching Compose is `status`, and it crosses exactly one render boundary, `usageLimitStatusLabel`: control and `Cf` characters (bidi overrides that could visually reorder the client-owned reset/percent clauses into a spoof) are removed, it is cut to 40 characters, and it lands only as a `%1$s` argument of a `Text` — never markup, URL, attribute, filename, cache key or log. `limitType` is not rendered. `conversation_id` never reaches the value (it is the projection's map key).
- [Trust boundaries] Addressed in-plan — `resets_at` and `utilization` are declined, never clamped: `formatUsageLimitReset` range-checks before constructing any date (the protocol's named bug), and `usageLimitSpentPercent` declines anything outside `0.0..1.0` or non-finite, so no gauge or figure is driven by an out-of-range number. No behaviour branches on `status` or `utilization`; the only branch is on the helpers' `null`/non-`null` result.
- [Trust boundaries] Copy overclaim — the lead is attributed reportage ("Claude reports…"), never "you are rate limited" / "limit reached" / "limit lifted"; the scripted test asserts the verbatim status appears inside the attributed lead.
- [Tokens] No findings — no token, key or credential is read, stored or displayed.
- [File / storage] No findings — nothing is persisted; the reading stays in the connection-scoped projection and a VM `StateFlow`.
- [Inter-process] No findings — no intents, deep links, pending intents, providers or WebViews.
- [Crypto] No findings — no primitives touched.
- [Network & I/O] No findings — no new frame, request or connection; the fixed 30 s re-read re-subscribes to an in-memory `StateFlow` and sends nothing on the wire.
- [Logs] No findings — nothing new is logged; the account's quota posture (the status/utilization pair) must not be logged, and the plan adds no log call.
- [Concurrency] Addressed in-plan — the ticker runs only while the screen subscribes (`WhileSubscribed`), is cancelled with `viewModelScope`, and schedules from a fixed constant, never from `resetsAt` (a negative or clamp-overflowing delay is structurally impossible).
- [Threat model] Hostile daemon: worst case is one row of stripped, 40-char inert text with no date and no percent, displaced by api-retry and expiring on its own `resets_at`; a `resets_at = 0` reading persists until a benign frame or reconnect, which is the documented #802 posture. UI-side leakage (screenshots of the quota posture) — OUT OF SCOPE, same exposure as the thread content itself.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
