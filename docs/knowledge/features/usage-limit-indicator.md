# Usage-limit indicator — `UsageLimitIndicator`

The **UI half of the usage-limit reading** ([#804](https://github.com/pyrycode/pyrycode-mobile/issues/804),
split from #653, consumes [#802](usage-limit-state.md)): a stateless composable that fills the thread's
status slot with claude's own `rate_limited` report, so a turn that stalls on a usage limit says why
instead of leaving the user watching a spinner.

The signal it renders is the **data half** —
[`ThreadViewModel.usageLimit`](usage-limit-state.md) — a sibling `StateFlow<UsageLimitReading?>` beside
`apiRetry` / `isCompacting`. This component adds no data access: it receives the already-decoded
`UsageLimitReading?` as a hoisted value.

Package: `de.pyryco.mobile.ui.conversations.components`
(`app/src/main/java/de/pyryco/mobile/ui/conversations/components/UsageLimitIndicator.kt`).

## Wording is the whole risk here

A `rate_limited` frame is **not proof that a turn was blocked** — the one measured non-benign status
(`allowed_warning`) rode an account whose turns all ran normally. Every string this component emits is
therefore **attributed reportage**, never a verdict: the lead reads "Claude reports usage-limit status:
…", and nothing anywhere in the copy says "limited", "blocked", "reached" or "lifted". `status` renders as
an opaque label credited to claude; no branch anywhere on the render path compares it against a value.
`limitType` is not rendered at all — the acceptance criteria do not require it, and leaving it out keeps
the untrusted surface to one string.

## Shape

```kotlin
@Composable
fun UsageLimitIndicator(reading: UsageLimitReading?, modifier: Modifier = Modifier)
```

Early-return on `null` — the sibling totality idiom shared with
[`ApiRetryIndicator`](api-retry-indicator.md) / [`CompactingIndicator`](compacting-indicator.md). Otherwise
one `Row` (16dp horizontal / 8dp vertical padding, 8dp gap, `bodySmall` / `onSurfaceVariant`, merged
`semantics`) with a 16dp `Icons.Outlined.Info` glyph in place of the siblings' spinner — a usage-limit
report is not progress, and a spinner would imply a wait the frame does not prove. The row's merged
`contentDescription` is the visible label itself, so the wording has one source. `now` is captured with
`remember(reading) { Clock.System.now() }`; time zone and locale come from the system defaults at
composition.

## Three render-or-decline helpers, not a mapper

Three `internal` pure functions in `UsageLimitIndicator.kt`, each declining to `null` rather than
throwing, clamping or defaulting:

| Helper | Contract |
|---|---|
| `usageLimitStatusLabel(status, truncatedFields)` | Strips ISO-control and Unicode format characters (`Cf`, including bidi overrides that could visually reorder the client-owned clauses that follow) to spaces, trims, cuts to `MAX_STATUS_CHARS = 40`. An ellipsis marks a cut made here **or** one the daemon reported in `truncatedFields` — claude's cut text is never presented as complete. `null` when nothing printable is left. |
| `usageLimitSpentPercent(utilization)` | `null` when absent, non-finite, or outside `0.0..1.0`; otherwise `(utilization * 100).roundToInt()`. **An absent value is never rendered as `0`** — that would show a fresh window as an exhausted one — and an out-of-range value is declined, never clamped into range. |
| `formatUsageLimitReset(resetsAt, now, timeZone, locale)` | `null` when `resetsAt <= 0` (0 = claude reported no reset, not the epoch — tested first), when it is already past, or when it lies more than `MAX_RESET_HORIZON_SECONDS` (31 days) ahead — the range check runs **before** any date is constructed, so a year-40000 value never reaches a date constructor. Otherwise a localized SHORT time when the reset falls on today's local date, else a localized SHORT date + time. |

The label is assembled from three client-owned string resources (`thread_usage_limit_label` /
`_label_no_status`, `_spent`, `_resets`), appending the optional `· N% spent` / `· resets …` clauses only
when the matching helper is non-`null`.

## Placement in the thread

Joins [`ThreadScreen`](thread-screen.md)'s single mutually-exclusive status slot
(`ThreadStatusArea`, `ThreadScreen.kt`) as a fourth arm, ranked **between** api-retry and resetting
(originally compaction; [#872](https://github.com/pyrycode/pyrycode-mobile/issues/872) inserted
[`ResettingIndicator`](resetting-indicator.md) below this arm without moving it):

```kotlin
when {
    apiRetry != ApiRetryStatus.NotRetrying -> ApiRetryIndicator(status = apiRetry, modifier = slot)
    usageLimit != null                     -> UsageLimitIndicator(reading = usageLimit, modifier = slot)
    resetting != null                      -> ResettingIndicator(status = resetting, modifier = slot)
    isCompacting                           -> CompactingIndicator(isCompacting = true, modifier = slot)
    turnOutcome != null                    -> TurnOutcomeIndicator(report = turnOutcome, modifier = slot)
    else                                   -> ThinkingIndicator(isThinking = isThinking, modifier = slot, progress = thinkingProgress)
}
```

Usage limit sits above resetting and compaction for the same reason api-retry already sits above it: both
are benign-or-routine (a report, and the daemon's own scheduled reset) and must never mask a signal that
something may be going wrong. It sits below api-retry because a live retry is the stronger "something is
wrong" signal when both are somehow true (never observed in practice). The arm is raised by a non-`null`
reading alone — the benign clear and the read-time expiry are already applied upstream in
[`UsageLimitProjection`](usage-limit-state.md#the-clear-is-session-scoped--the-expiry-is-the-readings-second-way-down),
so nothing in this arm or the screen re-reads `resetsAt`/`status`. [#805](https://github.com/pyrycode/pyrycode-mobile/issues/805)
added a fifth arm (now sixth), [`TurnOutcomeIndicator`](turn-outcome-indicator.md), directly below
compaction and above thinking — a failed or interrupted turn's outcome is post-turn, so it has never been
observed overlapping this reading. See
[API-retry indicator § Placement](api-retry-indicator.md#placement-in-the-thread),
[Resetting indicator § Placement](resetting-indicator.md#placement-in-the-thread) and
[Compacting indicator § Placement](compacting-indicator.md#placement-in-the-thread) for the rest of the
ladder's rationale, and [Thread screen — overlays and app bar](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643)
for the `ThreadStatusArea` composable this arm was added to.

## Wiring — a 30 s re-read ticker, not a timer from `resets_at`

`ThreadViewModel.usageLimit` is a sibling `StateFlow` beside `apiRetry` / `isCompacting`, but unlike them
it does not simply `.stateIn()` the repository flow:

```kotlin
val usageLimit: StateFlow<UsageLimitReading?> =
    usageLimitRereads()
        .flatMapLatest { repository.observeUsageLimit(conversationId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)
```

**Why a ticker is load-bearing here, unlike every sibling arm.**
[`UsageLimitProjection.isReadable`](usage-limit-state.md#the-clear-is-session-scoped--the-expiry-is-the-readings-second-way-down)
applies the expiry rule **only when read** — an already-subscribed collector gets no emission at the
`resets_at` deadline itself, because nothing pushes a frame at that moment. A ViewModel that only
`.stateIn()`'d the repository flow, the way `apiRetry`/`isCompacting` do, would keep an expired reading on
screen indefinitely, until some unrelated frame happened to re-subscribe it. `usageLimitRereads()` is a
private cold ticker (`internal const val USAGE_LIMIT_REREAD_MS = 30_000L`) emitting `Unit` immediately and
then every 30 s; each tick re-subscribes via `flatMapLatest`, which re-evaluates `isReadable` against the
current clock. A displayed reading therefore leaves within ≤30 s of its `resets_at` **without this layer
re-deriving the expiry rule**, and **with no delay ever computed from `resets_at`** — claude's unvalidated
number never becomes a scheduling input. `StateFlow` equality suppresses identical re-reads, so the ticks
do not recompose the screen while nothing has changed. The ticker runs only while the screen is subscribed
(`WhileSubscribed(5_000)`) and is cancelled with its collector.

`MainActivity` collects it via `vm.usageLimit.collectAsStateWithLifecycle()` beside `apiRetry` /
`isCompacting`, and `ThreadScreen` threads it as a defaulted `usageLimit: UsageLimitReading? = null`
parameter — every pre-#804 call site and preview keeps compiling unchanged.

## Testing

- **JVM unit** `UsageLimitIndicatorFormatTest.kt` pins the three render-or-decline helpers directly
  (status pass-through, control/bidi stripping, the 40-char cut, the daemon's own `truncatedFields`
  ellipsis; percent absent/in-range/out-of-range/non-finite; reset zero/negative/past/absurd-future/
  same-day/other-day).
- **JVM unit** `ThreadViewModelTest`'s `usageLimit_*` group: initial `null` with a plain fake; a reading
  then its clear; scoping to the VM's own conversation id; and the case the ticker exists for —
  `usageLimit_expiredReading_leavesOnTheNextReRead`, which flips a controllable fake's `expired` flag
  **without emitting**, asserts the value is unchanged immediately (no upstream push at the deadline), then
  `advanceTimeBy(USAGE_LIMIT_REREAD_MS)` and asserts it clears. **Uses `runCurrent`/`advanceTimeBy`, never
  `advanceUntilIdle`** — the ticker's `delay` is perpetual while the flow is collected, so
  `advanceUntilIdle` would chase it forever and the test would hang.
- **Rung 2 scripted** `ScriptedUsageLimitTest` on `ScriptedThreadHarness`'s new
  `pushRateLimited(status, limitType, resetsAt, utilization, truncatedFields)`, in the shape of
  [`ScriptedApiRetryTest`](api-retry-indicator.md): status shown verbatim and replaces thinking; shows
  while idle; a benign `allowed` frame clears it; a past `resets_at` never shows; `0` and a year-40000
  value render no reset clause; absent and out-of-range `utilization` render no percent; api-retry wins the
  slot over it and it wins over compaction.
- No rung-3/4 scenario: live behaviour is [#679](https://github.com/pyrycode/pyrycode-mobile/issues/679)
  per the ticket, same posture as the sibling arms recorded in `docs/e2e-interactive-stream.md`.

## Lessons learned

- **A JVM unit test formatting a localized `SHORT` time with `Locale.US` can fail on newer JDKs** — the
  platform inserts a narrow no-break space before AM/PM that a naive string-equality assertion does not
  expect. `UsageLimitIndicatorFormatTest` formats against `Locale.GERMANY` instead (24-hour, no AM/PM
  marker), the same fix [`SessionBoundaryDelimiterTest`](session-boundary-delimiter.md) already carries —
  pick a locale without an AM/PM marker for this class of test rather than special-casing the space
  character.
- **`strings.xml` values that must keep a leading space need an explicit quoted string** —
  `thread_usage_limit_spent` / `_resets` are declared as `" · %1$d%% spent"` / `" · resets %1$s"` (quotes
  included in the XML) so Android's resource parser does not collapse the leading space, which would glue
  the optional clause onto the preceding one with no separator.

## Security

Builder self-review **PASS** (full pass recorded in the
[architecture plan](../../specs/architecture/804-usage-limit-status-arm.md#security-review)). The only
daemon-authored text reaching Compose is `status`, and it crosses exactly one render boundary
(`usageLimitStatusLabel`) before landing as a `%1$s` argument of a `Text` — never markup, a URL, an
attribute, a filename, a cache key or a log. `resets_at` and `utilization` are range-checked before any
date or figure is built from them, never clamped. Nothing branches on `status` beyond the helpers'
`null`/non-`null` result, and nothing is logged — the account's quota posture must not be.

## Related

- Upstream signal: [Usage-limit state](usage-limit-state.md) — `ThreadViewModel.usageLimit` /
  `observeUsageLimit`, the `rate_limited` decode this component renders, and the read-time expiry rule
  the 30 s ticker exists to re-apply.
- Host: [Thread screen](thread-screen.md) — threads `usageLimit` as another flat sibling parameter and
  arbitrates the status slot (`ThreadStatusArea`) across four affordances.
- Idioms mirrored: [API-retry indicator](api-retry-indicator.md) and
  [Compacting indicator](compacting-indicator.md) (the row idiom, the early-return totality, the
  precedence-lives-in-the-screen posture) — this arm is the first in the family to render an icon rather
  than a spinner, since a report is not progress. [Thinking-progress state](thinking-progress-state.md)
  and [Thinking indicator](thinking-indicator.md) (the closest sibling in carrying daemon-originated
  numbers, though that arm carries no daemon text and needs no display cap). [Turn-outcome
  indicator](turn-outcome-indicator.md) ([#805](https://github.com/pyrycode/pyrycode-mobile/issues/805),
  immediately below compaction in the ladder) — the next arm to render an icon rather than a spinner, and
  the first to carry three independent daemon-authored strings through one sanitizer at once.
- Spec: `docs/specs/architecture/804-usage-limit-status-arm.md`.
- Server SSOT: `internal/protocol/interactive.go` (`RateLimitedPayload`), `docs/protocol-mobile.md §
  rate_limited` — cited, not restated.
