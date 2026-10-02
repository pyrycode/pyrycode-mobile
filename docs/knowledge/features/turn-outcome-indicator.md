# Turn-outcome indicator — `TurnOutcomeIndicator`

The status area's arm for a stopped turn, since
[#1357](https://github.com/pyrycode/pyrycode-mobile/issues/1357) narrowed to **recovery advice only**:
desktop's `ComposerErrorSlotControl` copy, read by desktop's `latestTurnEnd` rule. Before #1357 (originally
\#805, split from #653) this arm decoded `turn_end` into a "Turn interrupted" / "Turn failed" / "Turn stopped
early" label with the agent's own reported detail. [#1356](stopped-turn-row.md) gave the thread itself a
persistent `ThreadItem.StoppedTurn` row that already tells that story, so this arm repeating it was
redundant; #1357 copied desktop's posture, where this slot says only what to do, not what happened.

Package: `de.pyryco.mobile.ui.conversations.components`
(`app/src/main/java/de/pyryco/mobile/ui/conversations/components/TurnOutcomeIndicator.kt`).

## What it shows

Three notices, all client-owned copy — **no daemon text reaches this component at all**, a stronger
posture than #805's attribution scheme:

| Notice | Copy | Pill |
|---|---|---|
| `ContextTooLong` | "Context too long. Compact or reset the session." | plus a tappable "Compact" pill |
| `BillingError` | "<agent> reported a billing error. Check <agent> billing on this server." | — |
| `AuthenticationFailed` | "<agent> reported an authentication failure. Check <agent> sign-in on this server." | — |

`<agent>` is the conversation's agent, named via `agentName()` (#1113, see § The agent name below). Every
other stopped turn, and every cancelled turn, shows nothing. The resources are `thread_recovery_context`,
`thread_recovery_billing`, `thread_recovery_auth`, `thread_recovery_compact` — the six `thread_turn_outcome_*`
strings and `thread_turn_outcome_api_error` were removed with the #805 contract.

## Classification & sanitization

The classifier, `turnRecoveryNotice(event): TurnRecoveryNotice?`, mirrors desktop's `latestTurnEnd`:

```kotlin
enum class TurnRecoveryNotice { ContextTooLong, BillingError, AuthenticationFailed }

internal fun turnRecoveryNotice(event: LiveSessionEvent.TurnEnd): TurnRecoveryNotice?
```

`null` when `stopReason == "cancelled"`. Otherwise `null` unless the turn is marked stopped — `isError`, or
`outcome` non-empty and not `"success"` (the documented context-overflow shape is `outcome: "success"` +
`is_error: true`, still caught by `isError` alone). A stopped turn then resolves in priority order:
`terminalReason == "prompt_too_long"` → `ContextTooLong`, else `errorCategory == "billing_error"` →
`BillingError`, else `errorCategory == "authentication_failed"` → `AuthenticationFailed`, else `null` (a
stopped turn whose category is some other value, or none, shows nothing — e.g. `error_max_turns` or a
`refusal` `stopReason`, which raised the old `StoppedEarly` kind but maps to no recovery advice here).
`cancelled` is checked first and wins regardless of `isError`/`outcome`/category, same precedence as #805's
`Kind`.

**No sanitizer.** Every field this classifier reads (`terminalReason`, `errorCategory`) is only *compared*
against a closed set of known tokens, never rendered — the render trust boundary #805 needed
(`inertOutcomeToken`, the 40-character cut, the control/format-character scrub) no longer applies to this
component, because nothing agent-authored crosses into a `Text` argument here any more. See
[Live-session events § 1. DTOs](live-session-events.md#1-dtos--datanetworkinteractivepayloadskt-internal)
for the decode half these fields come from; [Stopped-turn row](stopped-turn-row.md) now holds the only
sanitizer in this area that still renders daemon text (`stoppedReportText`, which iterates code points and
has no truncation gap).

## `TurnOutcomeIndicator` (composable)

```kotlin
@Composable
fun TurnOutcomeIndicator(
    notice: TurnRecoveryNotice?,
    agent: ConversationAgent,
    onCompact: (() -> Unit)?,
    modifier: Modifier = Modifier,
)
```

Early-return on `null` — the sibling totality idiom shared with [`ApiRetryIndicator`](api-retry-indicator.md)
/ [`ResettingIndicator`](resetting-indicator.md) / [`CompactingIndicator`](compacting-indicator.md). Otherwise
a row holds the shared error [`NoticePill`](notice-pill.md) (`Icons.Outlined.ErrorOutline`, `bodySmall`,
`maxLines = 2`) with the notice's label. For `ContextTooLong` a second, Default-variant `NoticePill` reading
"Compact" follows it, tappable through its own `onClick = onCompact` — the same idiom as the top overlay's
Offline · Retry pill ([`627:4910`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-4910)). A
`null` `onCompact` — the command absent from the published Actions menu — leaves the pill visible with no
click action, rather than hiding it; the figma reference has no frame for that state, so this is a judgment
call recorded in the plan's Revisions. The pill takes `sizeIn(minHeight = 24.dp)`, matching the task-count
pill beside it, because a text-only pill measures 22dp and the band is 24dp.

**Every notice wraps to two lines even at 412dp width**, so the status band grows while one is showing — the
same behaviour the old two-line failure labels had. `ThreadStatusBandTest` holds only the status glyph's
leading edge for a notice reading, not its whole bounds, because the band's height is no longer fixed.

No spinner appears for a finished turn, and no icon distinguishes the three notices (#805 varied the icon
by `Kind`; #1357 dropped that since there is no longer an "interrupted" kind to tell apart from an error).

### The agent name (#1113)

**Unaffected by #1357's narrowing.** The billing and sign-in notices still name the conversation's
[`agent: ConversationAgent`](data-model.md) via the shared `agentName()` composable
(`components/AgentName.kt`), the same idiom #1113 gave this component, [`BannerNoticeRow`](banner-notice-row.md)
and [`ModelRefusalRow`](model-refusal-row.md) — one format string per label (`%1$s`) rather than a whole
sibling string per agent. `ContextTooLong`'s copy names no agent at all, since "Context too long" is a
client-side read of the daemon's own `terminal_reason`, not something claude said about itself.

## Placement in the thread

Joins [`ThreadScreen`](thread-screen.md)'s single mutually-exclusive status slot (`ThreadStatusArea`,
`ThreadScreen.kt`), directly below compaction — since [#1311](https://github.com/pyrycode/pyrycode-mobile/issues/1311)
with the stall arm between the two — and above the turn-level tail (an open tool, thinking, working, or the
local-send window): `connection → resetting → api-retry → compaction → stall → turn outcome →
thinking/working/running tool`. See [Thread screen § The arm
order](thread-screen-how-it-works-list-and-status-row.md#the-arm-order-1311) for the current `statusArm`
precedence table. Compaction still wins the slot over a turn outcome when both are somehow live (never
observed); `ScriptedTurnOutcomeTest.compaction_winsTheSlotOverTheOutcome` pins it. **A pending local send
also hides a turn outcome since #1311**, and since #1357 the send itself clears the notice outright (see §
Wiring below), so the two mechanisms now agree rather than one masking a value the other would otherwise
keep. See [API-retry indicator § Placement](api-retry-indicator.md#placement-in-the-thread) and [Compacting
indicator § Placement](compacting-indicator.md#placement-in-the-thread) for the rest of the ladder's history,
and
[Thread screen — overlays and app bar](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643)
for the `ThreadStatusArea` composable this arm was added to and the `onCompact` wiring through it.

## Wiring — `ThreadViewModel.turnOutcome`

```kotlin
private val _turnOutcome = MutableStateFlow<TurnRecoveryNotice?>(null)
val turnOutcome: StateFlow<TurnRecoveryNotice?> = _turnOutcome.asStateFlow()
```

Since #1357 this is a **held** value, not a `WhileSubscribed` fold over `liveSessionEvents` — the pre-#1357
cold fold lost a `turn_end` that arrived while nothing collected it. Every writer runs on `viewModelScope`
(`Dispatchers.Main.immediate`), so the flow needs no lock, and every collector below is an eager `init`
launch (like the #1311 local-send collector) so the notice survives while the screen is not collecting.

**Set.** A `turn_end` on the live-events collector runs `turnRecoveryNotice(event)` through
`nextTurnOutcome`, replacing the current value outright (a clean or cancelled end clears a stale notice).

**Cleared**, each on its own writer:

| Signal | Where |
|---|---|
| non-idle `turn_state`, an assistant delta, a tool use, a tool result | the same live-events `init` collector, via `nextTurnOutcome` |
| a new non-null `observeThinkingProgress` reading | a dedicated `init` collector, `filterNotNull()` |
| a new newest `SessionBoundary` in the drawn thread | `noteNewestBoundary`, an `onEach` on `threadItems` |
| the user's send — any message, or any Actions-menu command including Compact itself | `sendInLocalWindow` (text and attachment sends) and the bare-command branch of `onComposerCommand`, at hand-off, before the repository call |
| a reconnect (`repositoryAvailable` changing) | the existing `repositoryAvailable … drop(1)` collector, alongside `closeLocalSendWindow("reconnect")` |

An idle `turn_state` and a `ReplayGap` leave the notice as it is — the protocol says `turn_state: idle` may
arrive on either side of the `turn_end` it accompanies, so clearing on it could erase a just-shown notice
depending on frame order alone. Every writer logs once per change:
`event=turn_recovery_notice state=shown notice=<code>` / `state=cleared reason=<code>`.

**The boundary edge (`noteNewestBoundary`)** tracks the newest `ThreadItem.SessionBoundary` the `threadItems`
pipeline has drawn. A rejected design was a second `observeMessages` subscription dedicated to watching for
boundaries — subscribing sends a backfill request on the remote repository and drives cache writes on the
caching one, so the edge instead rides the existing pipeline. An empty list (the fold's seed, or a thread not
yet loaded) sets no baseline, so the first load is never read as a transition; comparing only the *newest*
boundary, rather than "any boundary appeared in the list", means an older history page prepending rows never
clears a live notice, since the newest boundary is unchanged by a page loading above it. The tracker lives on
the ViewModel (not `WhileSubscribed` state), so a restart compares against what was seen before rather than
re-baselining — but it runs only while `threadItems` is collected, so a session transition that lands while
the screen is unsubscribed is caught only on resubscription. **Known gap (verifier NIT on #1357,
non-blocking):** if a failed `turn_end` also lands in that same unsubscribed window, after the transition,
the late-seen boundary clears a notice that in wall-clock terms arrived after it — a transition followed by a
failed turn with nobody watching, and no send in between. Not fixed in #1357.

`isThinking` and `isBusy` are untouched by this ticket — they already turn off on any `turn_end` via
`TurnPhaseProjection.apply` (since #1313).

## Testing

- **JVM** `TurnRecoveryNoticeTest` (replaces `TurnOutcomeReportTest`) — a 21-case table over the rule: the
  three notices; `prompt_too_long` winning over a category; `isError` alone and a non-success `outcome`
  alone both marking a turn stopped; `cancelled` giving none regardless of its other fields; a clean turn
  with a stale category giving none; other stopped turns (`error_max_turns`, `refusal`, an unrecognised
  category) giving none.
- **JVM** `ThreadViewModelTurnRecoveryTest` (replaces the #805 `turnOutcome_*` group in `ThreadViewModelTest`,
  14 tests) — set by `turn_end`; one test per clear signal (non-idle `turn_state`, an assistant delta, a tool
  use, a tool result, a thinking reading, a new session boundary — and *not* on an older history page or a
  re-emission of the same boundary — a text send, an attachment send, a bare command, a reconnect); survives
  `Idle` and a `ReplayGap`; another conversation's events ignored.
- **sharedTest** `ThreadRecoveryNoticeTest` (4, Robolectric, through `ThreadScreen`) — the context notice plus
  Compact pill; tapping it calls `onComposerCommand(CompactSession)`; no click action when `CompactSession`
  is in `absentActions`; the billing and sign-in notices name a Codex agent, with no Compact pill beside them.
- **sharedTest (rung 2)** `ScriptedTurnOutcomeTest` (5, rewritten to the new contract on the real repository)
  — `prompt_too_long` shows the notice and Compact; `cancelled` shows nothing; the next turn clears it; a
  clean turn shows nothing; compaction still wins the slot.
- Mechanical fixture updates for the dropped `TurnOutcomeReport`/`thread_turn_outcome_*` contract:
  `ThreadAgentAttributionTest`, `ThreadTopOverlayTest`, `ThreadStatusBandTest`, `RunningToolIndicatorTest`,
  `ThreadActivityIndicatorVisualTest` (the Compact pill's 24dp height), and the device-only
  `ThreadActivityIndicatorCaptureTest` (real pixels, its device-only reason; capture `412x892-outcome.png`
  shows the error notice wrapping to two lines beside the Default-variant Compact pill).
- **Rung 3**, live-verified 2026-10-02 (50 executed, 50 passed): `InteractiveStreamE2ETest`'s
  `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain` (#965) dropped its Interrupted-label
  assertion — a cancelled turn shows nothing in this arm now — and keeps the method name and its assertion
  that the Stop control itself leaves once the `cancelled` `turn_end` arrives. See [End-to-end interactive
  stream](../../e2e-interactive-stream.md) for the scenario. The Compact pill has no rung-3 proof: real
  claude cannot reliably reach `prompt_too_long`. [#1473](https://github.com/pyrycode/pyrycode-mobile/issues/1473)
  (open) covers a deterministic rung-4 scenario.

## Security

Builder self-review and verifier review both **PASS** (full accounts in the
[#805](../../specs/architecture/805-turn-outcome-status-arm.md#security-review) and
[#1357](../../specs/architecture/1357-turn-recovery-notice.md) plans). Since #1357 no daemon-authored string
reaches a `Text` argument in this component at all: `terminalReason` and `errorCategory` are read only as
comparisons against a closed set of literal tokens, never interpolated into rendered copy, a stronger
posture than #805's attribute-and-sanitize scheme (whose `inertOutcomeToken` sanitizer gap is now moot for
this component — see [Stopped-turn row](stopped-turn-row.md) for the one place in this area that still
renders daemon text and its own, gap-free sanitizer). No token is a
branch input beyond the documented `"success"`/`"cancelled"` exclusions and the two named `error_category`
values. Nothing is persisted or logged beyond the static reason/notice codes; the notice lives in a VM
`StateFlow` and dies with it.

## How this differs from the stopped-turn row (#1356)

[`ThreadItem.StoppedTurn`](stopped-turn-row.md) is the thread's persistent "Stopped: …" row, built from the
same `turn_end` by desktop's `stoppedTurnText`/`stoppedReportText` rule — attributed reportage of what
happened, kept in the thread after the turn ends. This component is the status-area arm, a held value that
clears on the next sign of activity (see § Wiring); since #1357 it says only what to do next, not what
happened, so the two surfaces no longer describe the same thing in different words — they describe different
things. Before #1357 (#805 through #1356) both arms described the current turn's failure for as long as its
status stayed in the ladder, with different wording and different raise conditions (this arm's old
`turnOutcomeReport` raised on `stopReason` values that `stoppedTurn` did not); #1357 ended that overlap by
narrowing this arm to advice the row doesn't carry at all — the row never tells the user to tap Compact.

## Edge cases / limitations

- **The boundary edge can clear a notice out of wall-clock order** when a session transition and a failed
  turn both land while the thread is unsubscribed, with no send between them — see § Wiring above.
- **The context notice's Compact pill has no live proof** — real claude cannot reliably reach
  `prompt_too_long`; see § Testing and the open follow-up #1473.
- **`errorCategory` can be one turn stale** per the protocol; it is only read on a turn already marked
  stopped by `isError`/`outcome`, so a clean turn recovering from an API error never shows a notice from it.
- **Compaction always wins the slot when both are somehow live** (never observed) — see § Placement.
- **No animation** — the ladder's arms swap instantly on `when`-branch change, matching every sibling status
  affordance.
- **No `liveRegion`** on this or any sibling status affordance — a pre-existing, out-of-scope gap across the
  whole family (see [API-retry indicator § Edge cases](api-retry-indicator.md#edge-cases--limitations)).

## Related

- Upstream signal: [Live-session events](live-session-events.md) — the `turn_end` decode seam; § 1 DTOs
  documents the four lenient-defaulted fields this component classifies.
- Host: [Thread screen](thread-screen.md) / [Thread screen — overlays and app
  bar](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643)
  — threads `turnOutcome` and `onCompact` as flat sibling parameters and arbitrates the status slot across
  five affordances.
- Idioms mirrored: [Usage-limit indicator](usage-limit-indicator.md) (the icon-not-spinner precedent),
  [API-retry indicator](api-retry-indicator.md) and [Compacting indicator](compacting-indicator.md) (the row
  idiom, early-return totality, and the precedence-lives-in-the-screen posture all four arms share).
- Spec: `docs/specs/architecture/805-turn-outcome-status-arm.md`,
  `docs/specs/architecture/1113-agent-name-in-thread-notices.md` (§ The agent name, #1113),
  `docs/specs/architecture/1357-turn-recovery-notice.md`.
- Sibling surface: [Stopped-turn row](stopped-turn-row.md) — the thread's persistent "Stopped: …" row built
  from the same `turn_end`, by a different rule; see § How this differs above.
- Follow-up: [#1473](https://github.com/pyrycode/pyrycode-mobile/issues/1473) (open — a deterministic
  rung-4 scenario for the Compact pill, since real claude cannot reliably reach `prompt_too_long`).
- Server SSOT: `docs/protocol-mobile.md § turn_end` — cited, not restated.
