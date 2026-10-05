# Turn-outcome indicator — `TurnOutcomeIndicator`

Stopped-turn recovery advice in the thread's [top overlay](thread-top-overlay.md), moved
out of the status band by #1603 to match Figma `685:3992`. The persistent
[stopped-turn row](stopped-turn-row.md) reports what happened; this pill offers recovery.
Classification and the held clearing lifecycle introduced by #1357 remain unchanged.

Package: `de.pyryco.mobile.ui.conversations.components`
(`app/src/main/java/de/pyryco/mobile/ui/conversations/components/TurnOutcomeIndicator.kt`).

## What it shows

Three notices, all client-owned copy — **no daemon text reaches this component at all**, a stronger
posture than #805's attribution scheme:

| Notice | Copy | Pill |
|---|---|---|
| `ContextTooLong` | "Context too long - Compact" | whole-pill Compact action when published |
| `BillingError` | "<agent> reported a billing error. Check <agent> billing on this server." | — |
| `AuthenticationFailed` | "<agent> reported an authentication failure. Check <agent> sign-in on this server." | — |

`<agent>` is the conversation's agent, named via `agentName()` (#1113, see § The agent name below). Every
other stopped turn, and every cancelled turn, shows nothing. The resources are `thread_recovery_context`,
`thread_recovery_billing`, `thread_recovery_auth` — the six `thread_turn_outcome_*`
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

`TurnOutcomeIndicator(notice, agent, onCompact, modifier, followingNotice)` renders one
Error [NoticePill](notice-pill.md): error-container background, error body-small text,
8dp horizontal/4dp vertical padding, 6dp corners, no icon or X. Long copy wraps within
the message-area width. A null notice emits only the optional following notice.

Only context recovery with a non-null `onCompact` owns a click action. The whole pill
and its downward-extending, at-least-48dp target invoke Compact once; its label and action
share one merged accessibility node. Absent published Compact, context remains visible
and inert, as do billing and sign-in. There is no separate Compact pill.

Measure the visible surface independently of its expanded action target. The
`followingNotice` slot places the transient error 12dp below that surface, rather than
36dp below a 24dp pill in a 48dp box. Its inert surface consumes pointer taps where it
overlaps the target so an attachment refusal cannot accidentally run Compact. Test
physical taps on both preceding and following neighbors, not just the visible label.

The fresh API 35 capture is 176×24px at x=216/y=121 in a 412×892 framebuffer;
subtracting the real 24px status bar gives the design's top 97px and right gutter 20px.
Android's shared Roboto metrics make the text-hug width 4px wider than Figma's 172px.
Retain shared typography and full copy rather than force a width that wraps the label.
Use native Robolectric graphics for text geometry: legacy graphics falsely wrapped it
into a 43dp-high pill.

### The agent name (#1113)

**Unaffected by #1357's narrowing.** The billing and sign-in notices still name the conversation's
[`agent: ConversationAgent`](data-model.md) via the shared `agentName()` composable
(`components/AgentName.kt`), the same idiom #1113 gave this component, [`BannerNoticeRow`](banner-notice-row.md)
and [`ModelRefusalRow`](model-refusal-row.md) — one format string per label (`%1$s`) rather than a whole
sibling string per agent. `ContextTooLong`'s copy names no agent at all, since "Context too long" is a
client-side read of the daemon's own `terminal_reason`, not something claude said about itself.

## Placement in the thread

`ThreadScreen` passes `turnOutcome`, `state.agent` and the published-menu-gated Compact
callback to `ThreadTopOverlay`. Recovery follows attention, usage, MCP, pairing/Offline
and session errors; transient errors remain last and expire independently. Visible
notices keep a 12dp gap. The overlay reserves no message-list space.

Recovery no longer participates in `statusArm`. Connected idle keeps the snowflake alone;
connection and active-turn readings retain their existing precedence. Reset session
remains in Actions, and the stopped-turn row survives when the recovery pill clears.
See [the arm order](thread-screen-how-it-works-list-and-status-row.md#the-arm-order-1311).

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
- **sharedTest** `ThreadRecoveryNoticeTest`, `ThreadTopOverlayTest` and
  `RecoveryTransientOverlayTest` cover combined copy, inert unavailable Compact and
  agent-specific billing/sign-in, visible geometry, 48dp action bounds and physical
  routing after Re-pair/Offline and before a transient error. Native graphics separate
  visible-pill bounds from merged action bounds. `ThreadStatusBandTest`,
  `RunningToolIndicatorTest` and `ScriptedTurnOutcomeTest` cover independent status and
  unchanged recovery clearing.
- **Rung 4** `DeterministicInteractiveStreamE2ETest#interactiveTurn_seededChannel_contextOverflowCompactReachesDaemon`
  taps the combined pill, requires the daemon child's `/compact` echo and verifies the
  notice clears. Dispatcher `scripted-all` on 2026-10-05: 15 executed/passed, 0 failed,
  0 skipped, including this method. Retained focused XML also records 1 executed/passed,
  0 failed/skipped in `design-1220/thread/turn-outcome-1603-scripted-results.xml`.
- **Capture** `ThreadDesignCaptureTest#rowAndNoticeFramesAt412By892`: retained fresh API 35
  XML records 1 executed/passed, 0 failed/skipped at `2026-10-05T17:22:34` with real
  system bars and hardware pixels. See the [design verdict](../../../app/src/androidTest/assets/design-1220/thread/index.md#turn-outcome--6853992).
- **Rung 3 shared Compact path**: dispatcher full live suite on branch `5eeeeb8254`,
  2026-10-05, run `2026-10-05T18-11-46-185Z`: 57 executed/passed, 0 failed, 0 skipped.
  `InteractiveStreamE2ETest#interactiveTurn_reconnect_slashCommandsAndCompactStillWork`
  executed and passed in that suite, confirmed by the dispatcher gate report and issue
  evidence. This was a full-suite result, not a separate focused live run. Real Claude
  cannot reliably induce context overflow; the deterministic twin proves this pill's
  tap path. See [the ladder](../../e2e-interactive-stream.md).

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
happened, kept in the thread after the turn ends. This component is an overlay pill, a held value that
clears on the next sign of activity (see § Wiring); since #1357 it says only what to do next, not what
happened, so the two surfaces no longer describe the same thing in different words — they describe different
things. Before #1357 (#805 through #1356) both arms described the current turn's failure for as long as its
status stayed in the ladder, with different wording and different raise conditions (this arm's old
`turnOutcomeReport` raised on `stopReason` values that `stoppedTurn` did not); #1357 ended that overlap by
narrowing this arm to advice the row doesn't carry at all — the row never tells the user to tap Compact.

## Edge cases / limitations

- **The boundary edge can clear a notice out of wall-clock order** when a session transition and a failed
  turn both land while the thread is unsubscribed, with no send between them — see § Wiring above.
- **Context overflow cannot be reliably induced with real Claude.** Rung 4 proves this
  pill's tap path; rung 3 proves the shared Compact command path — see § Testing.
- **`errorCategory` can be one turn stale** per the protocol; it is only read on a turn already marked
  stopped by `isError`/`outcome`, so a clean turn recovering from an API error never shows a notice from it.
- **No animation** — notice changes render immediately.
- **No `liveRegion`** on this or any sibling status affordance — a pre-existing, out-of-scope gap across the
  whole family (see [API-retry indicator § Edge cases](api-retry-indicator.md#edge-cases--limitations)).

## Related

- Upstream signal: [Live-session events](live-session-events.md) — the `turn_end` decode seam; § 1 DTOs
  documents the four lenient-defaulted fields this component classifies.
- Host: [Thread screen](thread-screen.md) / [Thread screen — overlays and app
  bar](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643)
  — threads `turnOutcome` and `onCompact` into the overlay independently of status selection.
- Shared surface: [NoticePill](notice-pill.md); sibling overlay reports include
  [Usage-limit indicator](usage-limit-indicator.md).
- Spec: `docs/specs/architecture/805-turn-outcome-status-arm.md`,
  `docs/specs/architecture/1113-agent-name-in-thread-notices.md` (§ The agent name, #1113),
  `docs/specs/architecture/1357-turn-recovery-notice.md`.
- Sibling surface: [Stopped-turn row](stopped-turn-row.md) — the thread's persistent "Stopped: …" row built
  from the same `turn_end`, by a different rule; see § How this differs above.
- Follow-up: [#1473](https://github.com/pyrycode/pyrycode-mobile/issues/1473) (the shipped deterministic
  rung-4 context-overflow scenario).
- Server SSOT: `docs/protocol-mobile.md § turn_end` — cited, not restated.
