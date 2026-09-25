# Turn-outcome indicator — `TurnOutcomeIndicator`

How a failed or interrupted turn's real outcome reaches the thread status area
([#805](https://github.com/pyrycode/pyrycode-mobile/issues/805), split from #653). Before this ticket,
mobile decoded only `turn_end`'s `stop_reason`; a refused, budget-exhausted or API-failed turn ended
looking exactly like a clean answer. This is a **decode-and-render** slice deliberately kept together —
the widening is four optional fields on one existing DTO/event with a single consumer, so splitting decode
from render would produce a slice that changes nothing observable on its own.

Package: `de.pyryco.mobile.ui.conversations.components`
(`app/src/main/java/de/pyryco/mobile/ui/conversations/components/TurnOutcomeIndicator.kt`).

## Wording is the whole risk here

Every string this component renders is **the agent's own account of the stop, attributed as such**, never
the app's own finding: the lead reads "Turn interrupted" / "Turn failed" / "Turn stopped early", and
everything the agent said follows "<agent> reports" — the conversation's own agent
([`ConversationAgent`](data-model.md), named via `agentName()`, [#1113](https://github.com/pyrycode/pyrycode-mobile/issues/1113)),
never a fixed "Claude". This matters because `error_category`'s value set names
account states — `account_on_hold`, `billing_error`, `authentication_failed` — that the daemon never
verifies; rendering one without attribution would read as the app's own diagnosis of the user's account.
See [Live-session events § 1. DTOs](live-session-events.md#1-dtos--datanetworkinteractivepayloadskt-internal)
for the decode half these four fields come from.

## Classification & sanitization

The classifier, `turnOutcomeReport(event): TurnOutcomeReport?`:

```kotlin
data class TurnOutcomeReport(val kind: Kind, val claudeReports: List<String>, val apiErrorCategory: String?) {
    enum class Kind { Interrupted, Failed, StoppedEarly }
}

internal fun turnOutcomeReport(event: LiveSessionEvent.TurnEnd): TurnOutcomeReport?
```

`null` on a clean stop. Otherwise raised by one of four independently-read signals — **none is inferred
from another**, per the protocol's own warning that they can disagree by design:

| Raised by | Why | Kind |
|---|---|---|
| `stopReason == "cancelled"` | the daemon's own interrupt classification | `Interrupted` |
| `isError` | claude's flag, read directly — covers `outcome: "success"` + `is_error: true` (the documented context-overflow case) | `Failed` |
| `outcome` non-empty and `!= "success"` | claude's own non-success subtype, e.g. `error_max_turns` under `stop_reason: "end_turn"` | `StoppedEarly` |
| `stopReason` in `max_tokens` / `max_turn_requests` / `refusal` | the documented non-clean daemon values | `StoppedEarly` |

`cancelled` wins the `Kind` even when `isError` is also set (`cancelled_winsOverIsError_andKeepsClaudesDetail`
in `TurnOutcomeReportTest`) — an interruption is what happened, regardless of what claude's own flag says
about it — but `outcome`/`terminalReason` detail text from all raised signals is still collected. `errorCategory`
and `terminalReason` never raise the arm **on their own**: a clean turn may carry a category from an API
error it recovered from, and the protocol says the value can be one turn stale. Both are shown only as
detail once another signal has raised it.

`claudeReports` is the sanitized, de-duplicated, non-empty union of `outcome` (unless `"success"`),
`terminalReason` (unless `"completed"`), and `stopReason` (only when it is one of the three
early-stop values) — so a turn capped at `end_turn` + `error_max_turns` shows `error_max_turns`, never
`end_turn` re-surfacing as if it were the story. `apiErrorCategory` is the sanitized `errorCategory`, or
`null`.

### The sanitizer — `inertOutcomeToken`

`inertOutcomeToken(raw): String?` is the render trust boundary — every claude-authored string in a
`TurnOutcomeReport` has already crossed it, so nothing downstream holds raw daemon text. ISO-control
characters (terminal escapes, line breaks) and Unicode format characters (`Cf`, including the bidi
overrides that could visually reorder the client-owned lead) become spaces; the result is trimmed and cut
to `MAX_TOKEN_CHARS = 40` with a trailing `…`; `null` when nothing printable survives.

**Known gap (verifier NIT on #805, non-blocking):** the sanitizer checks one UTF-16 `Char` at a time, so
three cases pass through unstripped — a `Cf` character outside the BMP (e.g. the invisible tag range
U+E0000–E007F) is a surrogate pair that `Character.getType(Char)` reports as `SURROGATE`, not `FORMAT`;
`take(MAX_TOKEN_CHARS)` can split a surrogate pair; and U+2028/U+2029 (`Zl`/`Zp`) are neither ISO-control
nor `Cf`. The risk is low — every bidi override actually in use is in the BMP and is stripped, each token
is capped at 40 characters, and the row is `maxLines = 2`, so a stray separator can do nothing ordinary
wrapping doesn't — but the plan's security review's "no line injection" claim is not quite true for
U+2028. Iterating code points and adding `Zl`/`Zp` to the check would close the gap; not done in #805.

## `TurnOutcomeIndicator` (composable)

```kotlin
@Composable
fun TurnOutcomeIndicator(report: TurnOutcomeReport?, agent: ConversationAgent, modifier: Modifier = Modifier)
```

Early-return on `null` — the sibling totality idiom shared with [`ApiRetryIndicator`](api-retry-indicator.md)
/ [`ResettingIndicator`](resetting-indicator.md) / [`CompactingIndicator`](compacting-indicator.md).
Otherwise the sibling row (16dp horizontal / 8dp vertical padding, 8dp gap, `bodySmall` /
`onSurfaceVariant`, merged `semantics`), with `Icons.Outlined.StopCircle` for `Interrupted` and
`Icons.Outlined.ErrorOutline` for `Failed`/`StoppedEarly` — no spinner, since a finished turn is not
progress (the same reasoning the pre-#1002 `UsageLimitIndicator` applied to its own `Icons.Outlined.Info`,
and [`NoticePill`](notice-pill.md) now applies to the usage-limit reading's own icon-free pill). The row's
merged `contentDescription` is the visible label.

Label assembly (`strings.xml`, all copy client-owned):

- Lead: `thread_turn_outcome_interrupted` / `_failed` / `_stopped`.
- Then, when `claudeReports` or `apiErrorCategory` is non-empty, `thread_turn_outcome_agent_reports`
  (` · %1$s reports %2$s`, `%1$s` the agent's name from `agentName()`) with `%2$s` being `claudeReports`
  plus `thread_turn_outcome_api_error` ("API error %1$s") for the category, joined with `, `.
- `Failed` with no detail at all (`is_error` alone, nothing else raised) falls back to
  `thread_turn_outcome_agent_reports_error` (` · %1$s reports an error`) — the verdict stays
  attributed to the agent even with nothing else to show.

Every agent-authored token lands only after "<agent> reports" and only as a `%2$s` `Text` argument —
never markup, a URL, an attribute, a filename, a cache key or a log. The two resources were renamed from
`thread_turn_outcome_claude_reports(_error)` in [#1113](https://github.com/pyrycode/pyrycode-mobile/issues/1113)
(see § The agent name below); with a Claude conversation both render byte-for-byte as the pre-#1113 copy.

### The agent name (#1113)

**Closes the status-ladder's agent-naming rollout.** [#1114](https://github.com/pyrycode/pyrycode-mobile/issues/1114)
named the agent in `ApiRetryIndicator`/`CompactingIndicator`/`ThinkingIndicator` and #1112 closed
`ResettingIndicator`'s `WrappingUp` reading (see [Thinking indicator § The agent
name](thinking-indicator.md#the-agent-name-1114)); this component was the one arm #1114's verifier flagged
as still saying "Claude" unconditionally. #1113 threads `agent: ConversationAgent` in the same way as its
three siblings and `BannerNoticeRow`/`ModelRefusalRow` (below) — `ThreadScreen` reads `state.agent` and
passes it straight through `ThreadStatusArea` → `StatusReading` into this component, no new
`MainActivity`/`ThreadViewModel` flow.

**#1113 uses a different naming idiom from #1114's.** `ApiRetryIndicator`/`CompactingIndicator`/`ThinkingIndicator`
each carry a whole sibling string per label (`cd_thread_api_retry_codex`, etc.) chosen by
`when (agent)`, so the Claude string stays byte-identical and a translator can inflect each sentence
independently. This component instead takes a new `@Composable fun agentName(agent): String`
(`components/AgentName.kt`, an exhaustive `when` over `ConversationAgent` resolving `agent_name_claude`
/ `agent_name_codex`) and fills it into one shared format string per label (`%1$s`/`%2$s` above), reused
by [`BannerNoticeRow`](banner-notice-row.md) and [`ModelRefusalRow`](model-refusal-row.md). Both idioms are
correct and client-owned — the name is always a string resource picked from the closed enum, never daemon
text — but the thread package now has two ways to name an agent. Verifier NIT on #1113 (non-blocking):
a later change could consolidate on one of them; not done here.

## Placement in the thread

Joins [`ThreadScreen`](thread-screen.md)'s single mutually-exclusive status slot (`ThreadStatusArea`,
`ThreadScreen.kt`), directly **below compaction and above thinking** — the current ladder, turn status
only: `api-retry → resetting → compaction → turn outcome → thinking/running tool`. Compaction is mid-turn
progress and a turn outcome is necessarily post-turn, so the two co-occurring has not been observed; no AC
is spent on the combination, and `ScriptedTurnOutcomeTest.compaction_winsTheSlotOverTheOutcome` pins that
compaction still wins the slot when both are somehow live. Claude's usage-limit report shared this ladder
between #804 and #1002; it now draws as a pill in [Thread top overlay](thread-top-overlay.md) instead,
pinned over the message area rather than a `ThreadStatusArea` arm, because a live reading was masking
every arm below it including this one. See [API-retry indicator §
Placement](api-retry-indicator.md#placement-in-the-thread) and [Compacting indicator §
Placement](compacting-indicator.md#placement-in-the-thread) for the rest of the ladder's rationale, and
[Thread screen — overlays and app bar](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643)
for the `ThreadStatusArea` composable this arm was added to.

## Wiring — `ThreadViewModel.turnOutcome`

```kotlin
val turnOutcome: StateFlow<TurnOutcomeReport?> =
    liveSessionEvents
        .runningFold(null as TurnOutcomeReport?) { current, event -> nextTurnOutcome(current, event) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)
```

A sibling `StateFlow` beside `isThinking` / `isBusy`, over the same `liveSessionEvents` seam and with the
same `WhileSubscribed(5_000)` lifetime — a cold fold, no suspension of its own, cancelled with the VM.
The private `nextTurnOutcome(current, event)` reducer:

- an event for another `conversationId` → `current` unchanged (per-conversation scoping)
- `TurnEnd` → `turnOutcomeReport(event)` — a clean end **replaces** a stale report with `null`
- `TurnState` `Thinking`/`Responding` → `null` — the next turn has started
- `TurnState` `Idle`, `AssistantDelta`, `ToolUse`, `ToolResult`, `ReplayGap` → `current` unchanged

**`Idle` deliberately does not clear it.** The protocol states `turn_state: idle` may arrive on either
side of the `turn_end` it accompanies; clearing on `Idle` could erase a just-shown failure depending on
frame order alone. Only `Thinking`/`Responding` mean a new turn has actually started —
`turnOutcome_clearsWhenTheNextTurnStarts_butNotOnIdle` in `ThreadViewModelTest` pins this against `Idle`,
an `AssistantDelta`, and a `ReplayGap` in sequence.

**`isThinking` and `isBusy` are pinned, not changed.** They already turn off on any `turn_end` via their
existing `thinkingTransition`/`busyTransition` reducers; #805 adds
`failedAndCancelledTurnEnds_clearIsThinkingAndIsBusy` to guard that a failed or cancelled `turn_end` can
never leave the spinner or the Stop affordance on, now that a `turn_end` can carry a non-clean shape.

`MainActivity` collects `vm.turnOutcome.collectAsStateWithLifecycle()` beside `apiRetry`/`usageLimit`/
`isCompacting` and forwards it; `ThreadScreen` threads it as a defaulted `turnOutcome: TurnOutcomeReport? =
null` parameter — every pre-#805 call site and preview keeps compiling unchanged. The scripted harness's
`ThreadScreen` call and `ScriptedThreadHarness.pushTurnEnd` gained the same wiring (see § Testing).

## Testing

- **JVM** `TurnEndPayloadsTest.kt` (new) — the decode half: absent fields decode to `""`/`false`, present
  fields carry verbatim, unrecognised tokens survive, control characters survive decode verbatim
  (sanitization is the render boundary's job, not decode's), and the existing three `turn_end` fields are
  unchanged.
- **JVM** `TurnOutcomeReportTest.kt` (new, 17 tests) — pins the classification table above: a clean
  `end_turn` and a `success` outcome are `null`; `error_category` alone is `null`; `cancelled` wins `Kind`
  over a simultaneous `is_error`/`outcome`, keeping claude's detail; `end_turn` + `error_max_turns` shows
  without `stop_reason` overriding `outcome`; the inert-token sanitizer (control/format → space, 40-char
  cut with ellipsis, all-control token dropped, duplicate tokens collapse).
- **JVM** `ThreadViewModelTest`'s `turnOutcome_*` group plus
  `failedAndCancelledTurnEnds_clearIsThinkingAndIsBusy` — initial `null`; a failed `turn_end` sets it; a
  clean `turn_end` clears a stale one; `Idle` does not clear it but `Thinking`/`Responding` do; another
  conversation's `turn_end` is ignored; the `isThinking`/`isBusy` regression pin.
- **Rung 2 scripted** `ScriptedTurnOutcomeTest.kt` (new, 6 tests) on `ScriptedThreadHarness`, whose
  `pushTurnEnd` gained optional `outcome`/`isError`/`terminalReason`/`errorCategory` (omitted from the
  JSON when `null`, so every pre-#805 caller still sends the unchanged frame) and whose `turnEndEnvelope`
  moved to `buildJsonObject` so a claude-authored control character is escaped into valid JSON rather than
  breaking an interpolated string literal: `success` + `is_error` replaces thinking and the Stop
  affordance with the failed label; `cancelled` shows the interrupted label; a later `turn_state thinking`
  clears it; a clean `turn_end` (with or without the stop-shape fields) shows nothing; control characters
  render as spaces in the exact composed label; compaction wins the slot over it.
- No rung-3/4 scenario — live behaviour is
  [#679](https://github.com/pyrycode/pyrycode-mobile/issues/679) per the ticket, the same posture recorded
  in `docs/e2e-interactive-stream.md` for the sibling ladder arms.
- **#1113**: `ScriptedTurnOutcomeTest` and `ThreadViewModelTest`'s existing cases pass `agent =
  ConversationAgent.Claude` and keep asserting today's literal copy — the "reads exactly as today" guard.
  The new sharedTest `ThreadAgentAttributionTest` (Robolectric, through `ThreadScreen`) covers a Codex
  conversation's failed outcome (`"Turn failed · Codex reports prompt_too_long"`) and a `Failed` outcome
  with no details (`"· Codex reports an error"`).

## Security

Builder self-review **PASS** (full pass recorded in the
[architecture plan](../../specs/architecture/805-turn-outcome-status-arm.md#security-review)); verifier
review **PASS** with one non-blocking NIT (the sanitizer gap recorded in § Classification &
sanitization above). `outcome`, `terminal_reason` and `error_category` are claude-authored, bounded by
the daemon at 256 bytes but not sanitized on the wire; they cross into rendering at exactly one function,
`turnOutcomeReport`, whose return value holds only already-sanitized strings. `is_error` is read directly,
never inferred from `outcome` — a hostile or buggy daemon claiming `outcome: "success"` with
`is_error: true` still renders as a failure (fail-visible); the inverse can only hide a label, which is
the pre-#805 state. No token is ever a branch input beyond the documented `"success"`/`"completed"`
exclusions and the daemon's own closed `stop_reason` set — no retry, navigation or action is keyed on any
of them. Nothing is persisted or logged; the report lives in a VM `StateFlow` and dies with it.

## Edge cases / limitations

- **The sanitizer misses supplementary-plane `Cf`, surrogate splitting, and `Zl`/`Zp`** — see §
  Classification & sanitization. Low risk given the 40-char cap and `maxLines = 2`; recorded as a known
  gap, not fixed in #805.
- **`errorCategory` can be one turn stale** per the protocol, and never raises the arm alone — a clean
  turn recovering from an API error can still carry a category. Only shown once another signal raises
  the arm.
- **Compaction always wins the slot when both are somehow live** (never observed) — see § Placement.
- **No animation** — the ladder's arms swap instantly on `when`-branch change, matching every sibling
  status affordance.
- **No `liveRegion`** on this or any sibling status affordance — a pre-existing, out-of-scope gap across
  the whole family (see [API-retry indicator § Edge cases](api-retry-indicator.md#edge-cases--limitations)).

## Related

- Upstream signal: [Live-session events](live-session-events.md) — the `turn_end` decode seam; § 1 DTOs
  documents the four lenient-defaulted fields this component classifies.
- Host: [Thread screen](thread-screen.md) / [Thread screen — overlays and app
  bar](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643)
  — threads `turnOutcome` as another flat sibling parameter and arbitrates the status slot across five
  affordances.
- Idioms mirrored: [Usage-limit indicator](usage-limit-indicator.md) (attributed-reportage copy, the
  icon-not-spinner precedent, the render-or-decline sanitizer idiom), [API-retry indicator](api-retry-indicator.md)
  and [Compacting indicator](compacting-indicator.md) (the row idiom, early-return totality, and the
  precedence-lives-in-the-screen posture all four arms share).
- Spec: `docs/specs/architecture/805-turn-outcome-status-arm.md`,
  `docs/specs/architecture/1113-agent-name-in-thread-notices.md` (§ The agent name, #1113).
- Follow-up: [#679](https://github.com/pyrycode/pyrycode-mobile/issues/679) (live real-claude
  verification of this arm).
- Server SSOT: `docs/protocol-mobile.md § turn_end` — cited, not restated.
