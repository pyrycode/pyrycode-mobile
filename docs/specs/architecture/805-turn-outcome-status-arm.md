# #805 — Show a failed or interrupted turn's real outcome in the thread status area

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` → `TurnEndPayloadDto`, `TurnEndPayloadDto.toEvent`, `ToolUsePayloadDto` — the DTO to widen; `ToolUsePayloadDto.parentToolUseId` is the lenient-default precedent for an optional wire field.
- `app/src/main/java/de/pyryco/mobile/data/model/LiveSessionEvent.kt` → `LiveSessionEvent.TurnEnd` — the event to widen; `ToolUse`'s defaulted trailing fields are the precedent that keeps positional `TurnEnd(c, t, stopReason)` constructions compiling.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `isThinking`, `isBusy`, `thinkingTransition`, `busyTransition`, `usageLimit` — the live-event reductions this slice pins and the sibling `StateFlow` shape the new `turnOutcome` joins.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`, `ThreadStatusArea` — the single status slot and its precedence `when`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/UsageLimitIndicator.kt` → `UsageLimitIndicator`, `usageLimitStatusLabel` — row idiom (16/8 dp padding, 8 dp gap, 16 dp glyph, `bodySmall` / `onSurfaceVariant`, merged content description = visible label) and the control/`Cf` stripping idiom for claude-authored tokens.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `PyryNavHost`, the `CONVERSATION_THREAD` destination — collects each VM flow and passes it to `ThreadScreen`.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `ThreadViewModel` factory — `liveSessionEvents` is the selected host bundle's coordinator stream, so host scoping is structural.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt` → `pushTurnEnd`, `turnEndEnvelope`, `start` — the scripting seam to widen and the `ThreadScreen` call the new flow is wired into.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedUsageLimitTest.kt` — shape of the scripted regression.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt` → `vmWithLiveEvents`, `isBusy_turnEnd_resetsToFalse` — the live-event test idiom.
- `app/src/test/java/de/pyryco/mobile/data/network/ToolPayloadsTest.kt` — wire-JSON-through-`MobileJson` decode test idiom.
- `docs/specs/architecture/804-usage-limit-status-arm.md` — the nearest analogue (status arm, attributed copy, security review).
- `../pyrycode/docs/protocol-mobile.md` § `turn_end`, § `turn_state` — wire SSOT, cited not restated. Load-bearing readings: the four fields are optional/open-set; `is_error` is never inferred from `outcome`; `stop_reason`/`outcome` disagree by design; `error_category` is claude's report and independent of the stop (a clean turn may carry one); the three strings are bounded (over-bound → `""`) but unsanitized; `turn_state idle` may accompany a clean `turn_end`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8 (status area sub-node `111:3525`)

The status area is one row at the top of the composer: a small leading glyph, 8 dp gap, one `bodySmall` label, and a trailing contextual-action slot that stays empty until #675. The new arm reuses the shipped sibling row exactly (`UsageLimitIndicator` / `ApiRetryIndicator`: 16×8 dp padding, 16 dp glyph, `bodySmall`, `onSurfaceVariant`) with `Icons.Outlined.ErrorOutline` for a failed or early-stopped turn and `Icons.Outlined.StopCircle` for an interrupted one — no spinner, since a finished turn is not progress.

## Context

`turn_end` already carries claude's stop shape, but mobile decodes only `stop_reason`, so a refused, capped or API-failed turn ends looking exactly like a clean answer. This slice decodes the four string/bool fields and renders a non-clean stop as a new status arm. The eight numeric fields are out of scope. No ADR needed.

Ladder after this slice, top wins: api-retry → usage limit → compaction → **turn outcome** → thinking.

## Design

### Decode — `TurnEndPayloadDto` / `LiveSessionEvent.TurnEnd`

Both gain four trailing, defaulted fields; `toEvent()` copies them verbatim:

```kotlin
// DTO: @SerialName("outcome") val outcome: String = "", @SerialName("is_error") val isError: Boolean = false,
//      @SerialName("terminal_reason") val terminalReason: String = "", @SerialName("error_category") val errorCategory: String = ""
data class TurnEnd(conversationId, turnId, stopReason,
    val outcome: String = "", val isError: Boolean = false,
    val terminalReason: String = "", val errorCategory: String = "")
```

Verbatim and unsanitized at decode (the family's existing posture); an unrecognised token is just a string. A wire `null` still fails the one envelope's decode, as `parentToolUseId`'s does — the contract never sends one. The event KDoc names the three new strings as claude-authored inert data.

### Classification + sanitization — `turnOutcomeReport` (new, in `components/TurnOutcomeIndicator.kt`)

```kotlin
data class TurnOutcomeReport(val kind: Kind, val claudeReports: List<String>, val apiErrorCategory: String?) {
    enum class Kind { Interrupted, Failed, StoppedEarly }
}
internal fun turnOutcomeReport(event: LiveSessionEvent.TurnEnd): TurnOutcomeReport?
```

This is the single render trust boundary: the report holds only already-sanitized strings, so nothing downstream sees a raw daemon string.

`null` (a clean stop, no arm) unless one of these holds — each read independently, never one inferred from another:

| Raised by | Why |
|---|---|
| `stopReason == "cancelled"` | the daemon's own interrupt classification |
| `isError` | claude's flag, read directly — covers `outcome: "success"` + `is_error: true` |
| `outcome` non-empty and `!= "success"` | claude's own non-success subtype, e.g. `error_max_turns` under `stop_reason: "end_turn"` |
| `stopReason` in `max_tokens` / `max_turn_requests` / `refusal` | the documented non-clean daemon values |

`errorCategory` and `terminalReason` never raise the arm on their own (a clean turn may carry a category from a retried API error; the protocol says a category can be one turn stale), but are shown as details once it is raised.

Kind, first match: `cancelled` → `Interrupted`; `isError` → `Failed`; otherwise `StoppedEarly`.

`claudeReports` = the sanitized, de-duplicated, non-empty values of `outcome` (unless `"success"`) and `terminalReason` (unless `"completed"`), plus `stopReason` when it is one of the three raising daemon values. `apiErrorCategory` = sanitized `errorCategory`, or `null`.

Sanitizer `inertOutcomeToken(raw): String?` (private): ISO-control and Unicode format (`Cf`, incl. bidi overrides) characters → space, trim, cut to `MAX_TOKEN_CHARS = 40` with `…` appended when cut, `null` when nothing printable remains.

### `TurnOutcomeIndicator` (new composable)

```kotlin
@Composable fun TurnOutcomeIndicator(report: TurnOutcomeReport?, modifier: Modifier = Modifier)
```

Early-return on `null`. Otherwise the sibling row: glyph (`StopCircle` for `Interrupted`, else `ErrorOutline`; `contentDescription = null`, tinted `onSurfaceVariant`), `Text` in `bodySmall` / `onSurfaceVariant`, `maxLines = 2`, ellipsis; the row's merged `contentDescription` is the label. Label (all copy client-owned, `strings.xml`):

- lead: `Turn interrupted` / `Turn failed` / `Turn stopped early`
- then, when any detail exists, ` · Claude reports %1$s`, where the argument is `claudeReports` plus `API error %1$s` for the category, joined with `, `.
- `Failed` with no detail at all (`is_error` alone) uses ` · Claude reports an error`, so the verdict is still attributed.

Every claude-authored token appears only after "Claude reports" — never as the app's own finding about the account. Tokens only ever land as a `%1$s` argument to a `Text`. Light and dark `@Preview`s.

### `ThreadViewModel.turnOutcome`

```kotlin
val turnOutcome: StateFlow<TurnOutcomeReport?>
```

`liveSessionEvents.runningFold(null) { current, event -> nextTurnOutcome(current, event) }.stateIn(viewModelScope, WhileSubscribed(5_000), null)`, with a private `nextTurnOutcome`:

- event for another conversation → `current`
- `TurnEnd` → `turnOutcomeReport(event)` (a clean end clears a stale report)
- `TurnState` `Thinking` / `Responding` → `null` (the next turn has started)
- `TurnState` `Idle`, deltas, tool events, replay gap → `current` (idle may accompany a `turn_end` in either order, so it must not clear)

`thinkingTransition` / `busyTransition` are unchanged; tests pin that a failed/cancelled `turn_end` still turns both off.

### `ThreadScreen` / `ThreadStatusArea` / `MainActivity`

`ThreadScreen` gains `turnOutcome: TurnOutcomeReport? = null`, passed to `ThreadStatusArea`, whose `when` gains `turnOutcome != null -> TurnOutcomeIndicator(...)` after compaction and before thinking; the KDoc ladder is updated. `MainActivity` collects `vm.turnOutcome` and passes it. The scripted harness's `ThreadScreen` call gets the same wiring.

## State + concurrency model

One new cold chain on `viewModelScope` over the existing hot `liveSessionEvents` (replay 0), started/stopped by `WhileSubscribed(5_000)` — the same lifetime as `isThinking`/`isBusy`. Main dispatcher, no suspension of its own. Conversation scoping is the `conversationId` guard in `nextTurnOutcome`; host scoping is structural (the VM's `liveSessionEvents` is its host bundle's coordinator stream, and the VM is per conversation inside `HostDestination`).

## Error handling

No failure path of its own. Decode failures stay the existing one-envelope drop. `turnOutcomeReport` is total; a hostile token costs at most one row of stripped, 40-char-per-token inert text.

## Testing strategy

- **JVM** `app/src/test/.../data/network/TurnEndPayloadsTest.kt` (new): absent fields decode to `""`/`false`; present fields verbatim; unrecognised tokens survive; control characters survive decode verbatim (sanitization is the render boundary's); the existing three fields are unchanged.
- **JVM** `app/src/test/.../components/TurnOutcomeReportTest.kt` (new): clean `end_turn` and `outcome: success` → `null`; `success` + `is_error` → `Failed` with `prompt_too_long`; `cancelled` → `Interrupted`; `end_turn` + `error_max_turns` → shown, `stop_reason` not preferred; `refusal` → `StoppedEarly`; category alone → `null`, category with a raise → `apiErrorCategory`; ESC / bidi-override / newline → spaces; all-control token dropped; 40-char cut adds ellipsis; duplicate tokens collapse.
- **JVM** `ThreadViewModelTest`: `turnOutcome` initial `null`; failed `turn_end` sets it; clean `turn_end` leaves/clears to `null`; `turn_state thinking` clears it, `idle` does not; another conversation's `turn_end` is ignored; **pin**: after `thinking` then a failed and a cancelled `turn_end`, `isThinking` and `isBusy` are `false`.
- **Rung 2 scripted** `ScriptedTurnOutcomeTest` (new) on `ScriptedThreadHarness` (`pushTurnEnd` gains optional `outcome`/`isError`/`terminalReason`/`errorCategory`, omitted from the JSON when null so existing callers send the unchanged frame; payload built with `buildJsonObject` so control characters are escaped): `success` + `is_error` replaces thinking and the Stop affordance with the failed label; `cancelled` shows the interrupted label; a later `turn_state thinking` clears it; a clean `turn_end` shows nothing; control characters render as spaces in the exact label; compaction wins the slot over it.
- Focused device run of `ScriptedTurnOutcomeTest` on the managed API 33 device. No rung-3/4 scenario here: live behaviour is #679 per the ticket.

## Documentation handoff

Pending for the documentation stage: fold the turn-outcome arm into the status-area ladder in the thread-screen / thread-status-row overviews, and the four decoded `turn_end` fields into `docs/knowledge/features/live-session-events.md`. No shared doc is edited here.

## Open questions

- In-flight overlap: `origin/feature/842` (In Documentation) touches `MainActivity.kt`, in the `PAIR_CODE` route and `Routes` hunks, far from the thread destination's flow collection this plan edits. Proceeding rather than blocking; before opening the PR, `git merge-tree` against that branch must show no conflict.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] Addressed in-plan — `outcome`, `terminal_reason` and `error_category` are claude-authored, bounded but unsanitized. They cross into rendering at exactly one function, `turnOutcomeReport`, which returns a `TurnOutcomeReport` holding only sanitized tokens: ISO-control characters (ESC, CR/LF — so no terminal escape or line injection) and `Cf` characters (bidi overrides that could visually reorder the client-owned lead into a spoof) become spaces, each token is cut to 40 characters. Tokens land only as a `%1$s` argument of a `Text` — never markup, a URL, an attribute, a filename, a cache key or a log.
- [Trust boundaries] Addressed in-plan — misattribution: every claude-authored token is rendered after "Claude reports", and `error_category` additionally as "API error …", so an account-state value (`billing_error`, `authentication_failed`) never reads as the app's own finding. No behaviour branches on any token's value other than the documented `"success"` / `"completed"` exclusions and the daemon's own closed `stop_reason` set; no retry, navigation or action is keyed on them.
- [Trust boundaries] Addressed in-plan — `is_error` is read, never inferred: a hostile or buggy daemon claiming `outcome: "success"` with `is_error: true` shows a failure (fail-visible); the inverse can only hide a label, which is the pre-slice state.
- [Tokens] No findings — no token, key or credential is read, stored or displayed.
- [File / storage] No findings — nothing is persisted; the report lives in a VM `StateFlow` and dies with it.
- [Inter-process] No findings — no intents, deep links, pending intents, providers or WebViews.
- [Crypto] No findings — no primitives touched.
- [Network & I/O] No findings — no new frame or send; four fields decoded on an existing frame whose size the transport already caps.
- [Logs] No findings — nothing new is logged; the account-state `error_category` must not be logged and the plan adds no log call.
- [Concurrency] Addressed in-plan — one `runningFold` chain on `viewModelScope` under `WhileSubscribed`, no suspension, cancelled with the VM; the per-conversation guard keeps another conversation's turn out, and host scoping is the per-host coordinator stream.
- [Threat model] Hostile daemon: worst case is one row of stripped, length-bounded inert text below api-retry/usage-limit/compaction, cleared by the next turn's `thinking`/`responding`. A daemon that withholds a `turn_end` leaves the pre-slice behaviour. UI-side leakage (screenshots of an account-state category) — OUT OF SCOPE, same exposure as the thread content itself.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
