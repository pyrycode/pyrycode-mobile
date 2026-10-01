# #1346 — Channel info: Session section (Claude version, reported permission mode, session cost)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ChannelInfoSheet.kt` → `ChannelInfoUiModel`, `ChannelInfoSheetContent`, `SectionHeader`, `AboutRow` — the section/row shapes the Session rows reuse.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `toChannelInfoUiModel`, the `ChannelInfoSheet` call — the mapper extended here.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `runningModel` (reads `observeSessionFacts` for the build only and deliberately leaves `permissionMode` unread), `isThinking` / `turnOutcome` (the `liveSessionEvents` seam and its per-conversation routing), `state` (the chained `combine` the new readings join).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadUiState`, `ThreadRunConfig` — the new fields sit on `ThreadUiState`, **not** on `ThreadRunConfig`, so nothing that reads the run configuration (Status sheet permission control, composer footer) can see the claimed permission mode.
- `app/src/main/java/de/pyryco/mobile/data/model/LiveSessionEvent.kt` → `LiveSessionEvent.TurnEnd`.
- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` → `TurnEndPayloadDto`, `TurnEndPayloadDto.toEvent`; `MobileJson` (`ignoreUnknownKeys`, not lenient).
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `SessionFacts`, `observeSessionFacts` (cold projection read, no request side effect — a second subscription is free).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/UsageLimitIndicator.kt` → `usageLimitStatusLabel` — the inert pattern (ISO-control and Unicode format characters become spaces, trim, cut, daemon flag OR own cut marks the value).
- `app/src/test/java/de/pyryco/mobile/data/network/TurnEndPayloadsTest.kt`, `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelRunningModelTest.kt`, `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenMapperTest.kt` — test shapes mirrored.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_pingPrompt_statusSheetShowsRunningModel`, and the overflow → "Channel info" steps of the delete scenario.
- Desktop reference: `pyrycode-desktop` `ConversationScreen.tsx` (`ChannelInfoSheetView`'s Session block) and `sessionCost.ts` (`latestSessionCostUsd`, `formatSessionCost`).
- Wire SSOT: pyrycode `docs/protocol-mobile.md` § `turn_end` (`cost_usd_total`: optional float, Claude's running session total, unverified, nothing clamped; a negative arrives as sent).

In-flight overlaps (build through, additive edits only): #1342 (System prompt section in the same sheet, between Memory and Actions — this ticket inserts between About and Memory), #1311, #1329, #1337, #1341, #1359, #1399 touch `ThreadViewModel.kt` / `ThreadScreen.kt` / `InteractivePayloads.kt` / the e2e class in unrelated blocks.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-48

No frame draws the Session rows (the ticket says so; the Figma MCP was also unauthenticated in this run). The section reuses the sheet's existing `SectionHeader` (labelLarge, onSurfaceVariant, 24dp start inset) and the `AboutRow` layout (bodyLarge onSurface label left, bodyMedium onSurfaceVariant value right-aligned, 16dp/8dp padding), as desktop drew them with its About rows. The "Truncated" tag is a labelSmall onSurfaceVariant line under the value.

## Context

Desktop's Channel info shows a Session section after About. Mobile already decodes `session_facts` (#890) but only surfaces the build in the Status sheet (#891), and does not decode `turn_end.cost_usd_total` at all. This ticket brings the Session section to parity. No ADR needed.

## Design

**Wire.** `TurnEndPayloadDto` gains `@SerialName("cost_usd_total") val costUsdTotal: JsonElement? = null`. A `JsonElement` rather than `Double?` because `MobileJson` is strict: a string or boolean in a `Double?` slot throws and drops the whole `turn_end`, and the AC requires the frame to decode as before. `toEvent()` maps it with a private helper: a non-string `JsonPrimitive` whose content parses as a `Double` → that value; anything else (string, bool, `null`, object, array) → `null`. Decode is verbatim: zero, negative and overflowed-to-infinity values pass through; the consumer filters.

**Model.** `LiveSessionEvent.TurnEnd` gains `val costUsdTotal: Double? = null` (Claude's estimate of the session total; `null` = not reported or not a number).

**ViewModel.**
- `private val sessionCostUsd: StateFlow<Double?>` — `liveSessionEvents` routed by `conversationId`, `TurnEnd` only, `costUsdTotal` kept when finite and `> 0`, `stateIn(viewModelScope, SharingStarted.Eagerly, null)`. Each qualifying value **replaces** the previous (latest, never a sum, never a max); a non-qualifying `turn_end` emits nothing, so the earlier value stands. `Eagerly` gives the ticket's lifetime — as long as the view model lives — where `WhileSubscribed(5_000)` would forget it after the screen is backgrounded for five seconds.
- The `state` chain gains one more `.combine(...)` over `combine(repository.observeSessionFacts(conversationId), sessionCostUsd, ::Pair)`, copying into two new `ThreadUiState` fields. A second `observeSessionFacts` subscription is a projection read with no wire request. `runningModel` is untouched and still does not read `permissionMode`.

**State.** `ThreadUiState` gains `reportedSessionFacts: SessionFacts? = null` (Claude's claim, Channel info only — documented as never driving a control) and `sessionCostUsd: Double? = null`.

**Mapper.** `ChannelInfoUiModel` gains `agent: ConversationAgent = Claude`, `sessionFacts: SessionFacts? = null`, `sessionCostUsd: Double? = null` (defaults keep previews and the device capture test unchanged). `toChannelInfoUiModel` passes `agent`, `reportedSessionFacts`, `sessionCostUsd` through.

**Sheet.** `ChannelInfoSheetContent` renders, after the About rows and before Memory: `SectionHeader("Session")`, a version row labelled "Claude version" / "Codex version" by `agent`, a "Reported permission mode" row, and — only when `sessionCostUsd != null` — a "Cost (Claude's estimate)" row. Pure helpers in `ChannelInfoSheet.kt`:
- `internal fun reportedSessionValue(raw: String?, flaggedTruncated: Boolean): ReportedSessionValue` — code points that are ISO control or Unicode format become spaces, the result is trimmed, then cut at 256 code points via `offsetByCodePoints` (a surrogate pair is never split). `text = null` when nothing printable is left ("Not reported"); `truncated = cut || flaggedTruncated`. The flag comes from `truncatedFields` containing `claude_code_version` / `permission_mode` respectively.
- `internal fun formatSessionCost(usd: Double): String` → `"$%.2f est."` with `Locale.ROOT` (desktop's `toFixed(2)`).
- A private `SessionRow(label, value, truncated, valueTag)` composable: the `AboutRow` layout, value `maxLines = 3` with ellipsis, plus a "Truncated" labelSmall line when `truncated`. Value text tags `CHANNEL_INFO_AGENT_VERSION_TAG` and `CHANNEL_INFO_SESSION_COST_TAG` exist for the e2e scenario.

## State + concurrency model

One new `viewModelScope` collector (`sessionCostUsd`, Eagerly, cancelled in `onCleared`). `liveSessionEvents` is the coordinator's hot `replay = 0` flow, so a reopened thread starts empty and shows the cost row again after its next turn — matching the ticket. No dispatcher switching; everything is Main.

## Error handling

A malformed `cost_usd_total` is absent, never an error; the frame still decodes. No new user-facing errors. **No new log lines**: the cost and both facts are Claude's claims and are never logged (no reject branch exists to classify — an unusable cost is simply absent, per the protocol's "absent decodes to 0").

## Testing strategy

- `TurnEndPayloadsTest` (unit): absent → `null` and the rest of the frame unchanged; a number → carried; a string, `true`, `null`, object → `null` and the frame still decodes; `0`, negative and `1e999` (→ infinity) carried verbatim.
- New `ThreadViewModelSessionReadingsTest` (unit, `runTest`, `MutableSharedFlow` live events): latest positive finite replaces an earlier one; later absent / `0` / negative / infinite / NaN do not replace it; another conversation's `turn_end` is ignored; none → `null`; facts reach `reportedSessionFacts` while `runConfig.permissionMode` stays the settings reading; the cost survives the state subscription being dropped for longer than five seconds.
- `ThreadScreenMapperTest`: `toChannelInfoUiModel` passes agent, facts and cost through.
- New `app/src/sharedTest/.../components/ChannelInfoSessionSectionTest` (Robolectric): "Claude version" vs "Codex version" label; "Not reported" for absent facts and for an all-control value; a 300-code-point value cut to 256 with a surrogate pair at the boundary intact plus "Truncated"; `truncatedFields` tags each row independently; cost row "$0.42 est." present, and absent with `null`; control characters rendered inert. Pure-helper assertions for `reportedSessionValue` / `formatSessionCost` live in the same class.
- Rung 3: extend `interactiveTurn_pingPrompt_statusSheetShowsRunningModel` — after the real turn, open overflow → "Channel info", wait for the version tag to show something other than "Not reported" and for the cost tag to exist, close the sheet with its "Close" button, then run the existing Status sheet assertions. The live run is the dispatcher's post-verifier gate (`needs-real-claude`); I only compile it. No rung-4 twin: the scripted `fakeclaude` fixtures do not carry `cost_usd_total`, and fixture changes live in the daemon repo.

## Open questions

- Does the e2e's Channel info sheet need `performScrollTo` for the cost node? Semantics of a `verticalScroll` `Column` exist off-screen, so `fetchSemanticsNodes` sees them; no scroll planned.

## Documentation handoff

Pending for the documentation stage: fold the Session section (version/permission/cost rows, the `Eagerly` lifetime choice, the `JsonElement` lenient-number decode) into the Channel info and `turn_end` feature overviews. The ticket names no specific reference doc.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — two boundaries, both explicit. `cost_usd_total` crosses at `TurnEndPayloadDto.toEvent` and leaves as `Double?`; a number carries no markup or control characters, and the only render is `formatSessionCost`'s fixed `%.2f` template. Overflow (`1e999`) is filtered as non-finite in the ViewModel; a huge finite value renders a long digit string, bounded visually by the row's `maxLines` ellipsis. Version and permission mode cross at `reportedSessionValue`: control and format code points (bidi overrides included, supplementary ones too since it walks code points) become spaces, the length is bounded at 256 code points, and the output reaches `Text` only — never `MarkdownText`, a URL, a `testTag`, a key or a log.
- [Trust boundaries] No findings — the claimed permission mode lives only on `ThreadUiState.reportedSessionFacts`, outside `ThreadRunConfig`; the Status sheet permission control and composer footer read `runConfig.permissionMode` (the settings reading), and `theClaimedPermissionPosture_neverReachesThePermissionReading` stays green. A new VM test asserts the same with the new field populated.
- [Trust boundaries] SHOULD FIX — the cost is Claude's unverified estimate; label it as such ("Cost (Claude's estimate)", "est.") so it is never read as the app's accounting (protocol § `turn_end` misattribution note). Implemented as planned.
- [Tokens / secrets] No findings — no secret is created, stored or logged.
- [File / storage] No findings — nothing persisted; the cost lives in memory for the view model's life.
- [Android attack surface] No findings — no intents, deep links, pending intents, providers or WebViews.
- [Crypto] No findings — no primitive touched.
- [Network & I/O] No findings — no new frame type; a field on an existing, already size-capped frame. A non-number does not drop the frame, so a hostile value cannot suppress `turn_end` handling (spinner, outcome).
- [Logs] No findings — no new log lines; neither the cost nor the facts are logged.
- [Concurrency] No findings — one `viewModelScope` collector, cancelled with the scope; `stateIn` holds the single writer; no check-then-act.
- [Threat model] Hostile daemon frame: covered by the decode and render rules above. A flood of `turn_end` frames changes one `StateFlow` value per frame — no growth.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-01
