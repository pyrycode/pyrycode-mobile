# Thread composer footer

Always-visible row at the bottom of [`ThreadScreen`](thread-screen.md), stacked below the [`ThreadInputBar`](thread-input-bar.md) inside the same `Scaffold.bottomBar` slot. **[#808](../codebase/808.md) replaced [the retired `ThreadStatusRow`](thread-screen-how-it-works-list-and-status-row.md#status-row-wiring-post-145)** — one monospace `model · effort` string with a single expand affordance — with independent buttons, each opening the design's [Options overlay](options-overlay.md) directly above it. **[#650](https://github.com/pyrycode/pyrycode-mobile/issues/650)** added a third button, Permission, ordered first per Figma `110:3494` (`Actions · Auto · Opus · Max · Cxt`), and retired the [`StatusSheet`](status-sheet.md)'s YOLO switch — the control later moved back into Run configuration in #1196. The trailing icon is the current entry to Run configuration, which includes Model, Effort and Permission after #1196. **[#884](https://github.com/pyrycode/pyrycode-mobile/issues/884)** added a fourth button, Actions, leading the row per that same Figma order — a client-owned menu of Reset session, Compact session and Knowledge capture; see [Actions menu](thread-composer-footer-actions-menu.md#actions-menu-884).

Package: `de.pyryco.mobile.ui.conversations.thread` (`app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt`). Current dark Figma reference (inspected 2026-10-04): [`Input area` 533:1957](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1957), [`footer button` 115:3677](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=115-3677), and [thread frame 16:8](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8). The older `Input footer` 110:3494 shows retired selectors and is historical.

## What it does

The current footer shows Actions and context usage, then attachment and Run configuration icons. The separate Model, Effort and Permission buttons were removed by #1196; their choices live in the Run configuration sheet opened by the `Tune` icon. `footerMenu` still projects model options for tests and legacy callers, using the same `selectedChoice` as the sheet. The Actions button opens an [`OptionsOverlay`](options-overlay.md); see [Actions menu](thread-composer-footer-actions-menu.md#actions-menu-884).

## Sourcing

Run configuration and the footer projections come from `ThreadUiState.runConfig: ThreadRunConfig` (`ThreadUiState.kt`). The sheet reads this state for Model, Effort and Permission, so its sections agree by construction. `ThreadRunConfig` folds `ConversationRepository.observeSessionSettings(conversationId)` (the saved `model` / `effort` / `permissionMode` plus the `sessionId` a write must address) and `observeModelMenu(conversationId)` (the models this conversation's daemon published, each with its own `effortLevels` and, since #650, `supportsAutoMode`) together with independent pending-write flags — `pendingModel: String?`, `pendingEffort: String?`, and `pendingPermission: String?` — each `null` when no write is outstanding for that control. `settingsHeld: Boolean` ([#1320](https://github.com/pyrycode/pyrycode-mobile/issues/1320), folded from `SessionSettings.held`) marks a reading that was carried across a reconnect rather than answered by the live connection — it fills the model/effort labels and keeps `writable` true, but is display-only: see [§ Permission mode — Context cancellation](#permission-mode-650) for how an outstanding write treats it, and [remembered effort recall](thread-composer-footer-effort-recall.md#remembered-effort-recall-686) for why the recall waits on it too.

Two computed properties resolve the model and effort labels used by run configuration and its menu projection:

- **`modelLabel`** — `"unknown"` before this conversation's settings reply; otherwise the selected ordinary row's label. Explicit saved or pending values match only an exact raw `value`. A confirmed Claude inherited `""` or `"default"` marks the row claude's own announcement maps to once one arrives — see [Status sheet § the inherited Claude mark](status-sheet.md#shape) for the value/`resolvedModel`/family tiers ([#1308](https://github.com/pyrycode/pyrycode-mobile/issues/1308)) — and before any announcement falls back to the older rule: a visible row only when the hidden default row has a usable concrete `resolvedModel` matching exactly one ordinary row in the full published list. With nothing marked the label names the announced family, then the default resolution's family, then "Model unavailable"; it never reads "Default". An unmatched explicit identifier reads as inert text with no selected row. Codex has no inherited resolution and ignores the announcement. Neither a previous conversation's settings nor the independent `ThreadRunConfig.running` display reading supplies a fallback — only the raw `announcedModel` key does, and only for the inherited Claude case.
- **`effortLabel`** — `"unknown"` when no settings reading is available yet; `EFFORT_PLACEHOLDER_LABEL` ("Effort") when nothing is selected; otherwise the selected value made inert, with no menu lookup: an effort level is its own label. Unlike the model label, an empty selection never reads as `"default"` — see [§ Applied effort](#applied-effort-889) for what "selected" means since #889.

`modelLabel` and `footerMenu(Model).selectedValue` both use `selectedChoice`, so the sheet and menu mark the same row. A tap sends that row's raw `value` and shows it pending. An acknowledgement alone does not confirm it: an arriving `observeSessionSettings` reading settles the choice, and reopening the conversation obtains a fresh reading. A rejected write clears pending and restores the last confirmed selection. `effortLabel` follows the same pending-renders-immediately / refusal-reverts shape, but its non-pending ranking is wider than a single saved reading — see below.

Published choices preserve agent order, omitting the `default` row only from visible options; its metadata still supplies inherited effort levels and Auto approval support. Claude labels use the bounded inert ASCII family derived from raw `value` (strip one `claude-`, take leading ASCII letters, capitalize the first), with inert `displayName` as fallback. Codex uses inert published `displayName` verbatim. The raw `value` stays byte-identical for exact matching and writes. Unmatched saved identifiers also pass through `String.inert()` before display. See [Options overlay § Trust boundary](options-overlay.md#trust-boundary).

### Remembered model choice (#1222)

`ThreadViewModel.onModelSelected` forwards the tapped published value to the current session. In `sendSessionSettings`, a successful `setSessionSettings` response triggers the app-wide [raw remembered-model write](app-preferences.md) before the passive refresh. Real-host threads receive that writer from `AppModule`; the demo path leaves it inert. A same-value tap, read-only thread, passive settings reading or effort-only write does not update the remembered model. Rejection, connection failure or cancellation before acknowledgement also leaves the previous value intact. A local DataStore IO failure is logged by the preference setter; it does not turn an acknowledged session change into a daemon error.

This preference is for a future chat's initial model choice. Opening an existing conversation still uses that conversation's daemon session settings; it never recalls or applies the remembered model. Keep this distinct from [remembered effort recall](thread-composer-footer-effort-recall.md), which can issue a write on thread opening.

### Permission mode (#650)

The permission button reads `ThreadRunConfig.permissionMode: String` — the **latest confirmed reading's** `SessionSettings.permissionMode`, verbatim, `""` meaning no current-child confirmation (no reading yet, a dormant session, or a live child that has not confirmed). There is no fallback to stored settings, `yolo`, or `session_facts`: a non-empty `session_id` with `yolo=false` still hides the button when `permissionMode` is `""`. Unlike model/effort there is **no optimistic value** — `pendingPermission: String?` only marks a write's request-or-settle as outstanding (drives the pending dim + `stateDescription`, and blocks a second write); the label itself never reads it.

`internal enum class PermissionModeOption(val wire: String, val label: String)` (`ThreadComposerFooter.kt`) is the closed, client-owned vocabulary in menu order — `default` "Manual approval", `acceptEdits` "Auto-approve edits", `auto` "Auto approval", `plan` "Plan", `dontAsk` "Approved actions only", `bypassPermissions` "Bypass approvals" (desktop #1546's labels) — with `fromWire(String): PermissionModeOption?`. `permissionModeLabel(runConfig): String?` is `null` when `permissionMode` is `""`; the matching option's `label` for a known value; otherwise `permissionMode.inert()` — an unrecognised non-empty value renders as inert text, is never offered as a menu choice, and never becomes a write. A label describes a posture and grants nothing on its own: Manual approval still applies whatever allow rules are already in place, so a daemon that lies about its own mode (e.g. reports `default` while actually bypassing) cannot use the label to grant itself anything beyond what it could already do.

**Session capability list (#1111).** `internal fun ThreadRunConfig.offersPermission(mode: PermissionModeOption): Boolean` is the one rule both the menu and the write guard ask, so they cannot disagree: `Auto` still needs `selectedChoice?.supportsAutoMode == true`; with a `SessionSettings.capabilities` list present (a `multi_agent` daemon's `session_settings` reply, pyrycode#2646), every mode other than `Bypass` must have its `wire` in `capabilities.permissionModes` — `Bypass` is exempt, since it is never listed and is sent as `yolo`, not `permission_mode`. No list (`capabilities == null`, today's only case until #1119) offers all six modes as before. The daemon-published strings are compared against `PermissionModeOption.wire` only, never rendered or logged. `ThreadRunConfig.effortChoices` (see [§ Shape](#shape) below) applies the same list to the effort menus.

**Write path:** `ThreadViewModel.onPermissionModeSelected(value: String)` re-validates `value` against `PermissionModeOption.fromWire` — no daemon- or screen-supplied string outside that table can become a write argument. It sends nothing for: an unknown value, the already-confirmed mode, `permissionMode == ""` (button would be hidden), an outstanding permission write, a mode `offersPermission` refuses (Auto without row support, or, with a list present, a mode the list omits), or no session to address (`skipUnlessWritable`). `Bypass` sends `repository.setSessionSettings(sessionId, yolo = true)`; every other mode sends `permissionMode = mode.wire`; the two are never combined — `SetSessionSettingsPayloadDto`'s `init` guard (`require(yolo == null || permissionMode == null)`) makes a both-fields frame unconstructible, so the client-side one-field rule has a deterministic backstop beneath it.

**Settle rule (desktop #1544, adopted verbatim):** `session_settings_updated` acknowledges the *request*, not claude's mode — at the current daemon, picking a mode equal to the stored setting can be acked without changing the child. After an ack, `ThreadViewModel.settlePermission` re-reads at once via `refreshSessionSettings`, then every `PERMISSION_SETTLE_INTERVAL_MS` (500 ms) inside `withTimeoutOrNull(PERMISSION_SETTLE_WINDOW_MS)` (15 s), and stops as soon as a reading reports the requested mode. Reads are serialized — the loop waits on a private `settingsReadings: MutableStateFlow<SettingsReading>` that the existing `sessionSettings.onEach` publishes (a `(seq, reading)` pair, numbered on every delivery including same-value re-reads) rather than opening a second `observeSessionSettings` collector, which on `RemoteConversationRepository`'s cold per-collector read would double the `request_session_settings` traffic. Settle expiry is silent: the label shows whatever the last reading said, since the daemon may legitimately ack without changing the child — not hidden, not "fixed" client-side.

**Failure:** a `RelayErrorException` (e.g. `session.not_found` on a dormant session) or `IllegalStateException` clears `pendingPermission`, sends the existing `sessionSettingsErrorChannel` signal (the shared failure snackbar), and triggers one `refreshSessionSettings` — no settle loop runs. `CancellationException` is rethrown before either typed catch, matching `sendSessionSettings`.

**Context cancellation:** the same `sessionSettings.onEach` that publishes the reading tick also cancels an outstanding permission write — before publishing that tick — when the delivered reading is `null` or `held` (subscription head on a host switch or a same-host reconnect, a failed read, or — since [#1320](https://github.com/pyrycode/pyrycode-mobile/issues/1320) — a reading carried across a reconnect rather than answered on the live connection) or its `sessionId` differs from the write's target session. Cancelling mid-send abandons the reply waiter, so a late ack from the old context reaches nothing and cannot start a settle; the canceller clears `pendingPermission` itself. Running the cancel check before the tick publish matters: otherwise the settle loop could observe a first reading from the new context before its own job was torn down. The same `lost` test (`reading == null || reading.held`) also clears `pendingModel`/`pendingEffort` when it matches the currently-pending raw value — a held reading is exactly as much a lost context as the pre-#1320 `null` head was, so a reconnect does not strand a tap as pending against a reading that will never confirm it.

**Session-reset staleness:** on a `session_transition`, `RemoteConversationRepository` folds the new id into `currentSessionId` synchronously before bumping the settings-read revision, so a reading for the *old* session can still be the most recent one on hand for a beat. `ThreadRunConfig.forLiveSession(liveSessionId)` (private, `ThreadViewModel.kt`, applied in the `state` combine using `conv?.currentSessionId`) blanks `permissionMode` whenever `liveSessionId` is non-empty and differs from `runConfig.sessionId` — an equality check, not an arrival-order race, so it doesn't matter whether the transition or the settings re-read lands first. Model and effort labels are untouched by this rule.

### Applied effort (#889)

`ThreadRunConfig.appliedEffort: EffectiveEffort` carries the daemon's `session_settings.effective_effort` reading verbatim — `Applied(value)`, `NotReported` (an explicit `null`), or `Unavailable` (the key omitted; #590's three-state decode, see [Conversation repository](conversation-repository.md)). It rides the same `runConfig` fold as everything else here; `runConfig()` replaces it wholesale on every reading, so a later reading that omits the key drops a previous applied value rather than sticking.

`selectedEffort` ranks: `pendingEffort` (a tap not yet settled) → a non-empty `Applied` value → `savedEffort`, but the saved choice is used **only** when the reading is `Unavailable` or `Applied("")`. `NotReported` selects nothing (`""`), never the saved value — an explicit `null` means Claude is reporting it runs no effort parameter, and substituting the saved choice there would misstate what is actually running. This adopts desktop #1549's `selectDisplayedEffort` (PR #1554) verbatim. [#686](https://github.com/pyrycode/pyrycode-mobile/issues/686) added the phone's own app-wide (not per-conversation) recall of a remembered effort level, which rides this same write path — see [Thread composer footer — remembered effort recall](thread-composer-footer-effort-recall.md#remembered-effort-recall-686).

`effortNote: EffortNote?` explains a selection that is not Claude's own non-empty applied value: `null` when there is no settings reading at all, while a tap is pending (the existing "Applying" `stateDescription` already covers that case), or when the applied value is non-empty; `NotReported` for an explicit `null`; `SelectedRunningUnavailable` / `DefaultRunningUnavailable` for `Unavailable` / `Applied("")`, chosen by whether `savedEffort` is non-empty. `EffortNote.textRes()` (`ThreadComposerFooter.kt`) maps each case to a client-owned string resource (`thread_effort_note_selected_unavailable`, `thread_effort_note_default_unavailable`, `thread_effort_note_not_reported`) — never daemon text, since the note is the client's own explanation, not a reading. [#1115](https://github.com/pyrycode/pyrycode-mobile/issues/1115) made the two agent-naming notes (`_default_unavailable`, `_not_reported`) take the conversation's agent name as a `%1$s` argument; `@Composable EffortNote.text(agent: ConversationAgent): String = stringResource(textRes(), agentName(agent))`, next to `textRes()`, resolves it. `thread_effort_note_selected_unavailable` names no agent and ignores the extra argument — Android's formatter ignores an unused positional arg, so this is silent, not a crash. The effort `FooterButton` carries `effortNote?.text(agent)` as `stateDescription` whenever no write is pending; the [Status sheet](status-sheet.md) renders `state.runConfig.effortNote?.text(state.agent)` as one `Caption` line below the effort chips — both resolve through the same function, so the footer and the sheet cannot disagree on the name.

An applied value outside the selected model's published `effortChoices` still becomes the label (made inert) — `footerMenu`'s `selectedValue` then matches no option, so the overlay simply marks nothing selected; no extra code enforces this, it falls out of the existing equality comparison. The write path is unchanged: `onEffortSelected` only ever sends a tapped published level, and `appliedEffort` has no path into `setSessionSettings`. `selectedEffort` is also the value `onEffortSelected`'s same-value guard compares against, so tapping the level Claude already applies now sends nothing — but tapping the *saved* level while Claude runs a different one still re-sends it (harmless).

**Session-reset staleness:** `ThreadRunConfig.forLiveSession(liveSessionId)` — the same private helper that blanks a stale `permissionMode` (#650) — also blanks `appliedEffort` to `Unavailable` when `liveSessionId` is non-empty and differs from `runConfig.sessionId`, so a late reply for a replaced session never shows the old session's applied effort; the display falls back to the saved choice with the "running effort unavailable" note. The saved model and effort are choices, not readings of the running child, so `forLiveSession` leaves them alone.

**A turn ending now refreshes the footer ([#1309](https://github.com/pyrycode/pyrycode-mobile/issues/1309)).** Besides `observeSessionSettings`'s own re-reads on subscription, a `session_transition`, and after a settled write (`refreshSessionSettings`), `ThreadViewModel` asks again on four more edges, copied from desktop's `createRunConfigRefreshTrigger` / `RunConfigData` / `openChannelInfo`: any conversation on the thread's host going from `thinking`/`responding` to `idle` (`turnEndEdges`, `RunSettingsRereads.kt` — a per-conversation running set, fresh per connection, ignoring a repeated `idle` and `turn_end` itself), a reset ending (`resetEndEdges`, non-null → null on `observeResetting`), and opening either sheet (`ThreadEvent.RunConfigOpen` from `ThreadScreen`'s `onStatusClick`, and `ThreadEvent.ChannelInfo` guarded by `compareAndSet(false, true)` so a repeated open event cannot double it) — closing either sheet sends nothing. Every edge calls the same `repository.refreshSessionSettings(conversationId)`, so the existing `flatMapLatest` still cancels an in-flight read and a reply still replaces the whole reading; the pending-write confirmation code (settle rule, failure handling, session-reset staleness) is unchanged. So an effort or permission mode Claude only started applying during the turn just finished (inherited or freshly chosen) now reaches this footer without leaving and reopening the thread. [#545](https://github.com/pyrycode/pyrycode-mobile/issues/545)'s `interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn` asserts this directly — see [e2e coverage](../../e2e-interactive-stream.md).

One known interaction: every reading still clears `pendingEffort` (unchanged), so a re-read can now land and settle an effort tap before its own write round-trips — e.g. Run configuration opens and the user taps an effort radio within one round trip, or another conversation on the host ends a turn while an effort write is outstanding. The radio briefly shows the old level and a second write becomes possible while the first is still outstanding. The design keeps this on purpose (brief, and avoiding it would mean threading a write-time sequence number through the effort path the way `settlePermission` already does for permission).

### Running model (#891)

`ThreadRunConfig.running: ThreadRunningModel` is a second, independent reading, not part of the five-arm
`runConfig` `combine` above (already at Kotlin's typed ceiling): a private
`runningModel: Flow<Pair<ThreadRunningModel, String>>` combines
[#890](https://github.com/pyrycode/pyrycode-mobile/issues/890)'s `observeAnnouncedModel` and
`observeSessionFacts`, and `runConfigFlow` folds it in with one more
`.combine(runningModel) { config, (running, announced) -> config.copy(running = running, announcedModel = announced) }`
call. `running` itself is never derived from `savedModel` / `selectedModel` and nothing falls back to it — the
[Status sheet](status-sheet-readings.md#runningmodelsection) is its only consumer for display; the model
selection and layout it drives are unchanged. The pair's second value is the raw announced model
([#1308](https://github.com/pyrycode/pyrycode-mobile/issues/1308)), dropped (left `""`) when truncated the
same way `toChoice` drops a truncated `resolvedModel`; it flows only into `ThreadRunConfig.announcedModel`,
used solely as the inherited Claude mark's comparison key — see [Status sheet § the inherited Claude
mark](status-sheet.md#shape). It is never written, logged or shown raw, matching `AnnouncedModel`'s own KDoc
warning against keying behaviour on the value — here it is used only as an equality key for which radio is
marked, not as a value that drives a different code path.

`internal fun reportedText(raw: String, truncated: Boolean): ThreadReportedText?` is the value-level
counterpart of [`inert()`](#sourcing) above: filters `isISOControl()` first and returns `null` when nothing
printable survives (an all-control-character value is unavailable, not a blank row), otherwise
`ThreadReportedText(raw.inert(), truncated || printable.length > MAX_RUN_CONFIG_LABEL_CHARS)` — the
daemon's own `truncated` flag widened to also catch a cut the 128-character inert bound made, so a value is
never shown as whole when either side cut it. `ThreadRunningModel.model` is
`announced?.let { reportedText(it.model, it.truncated) }`; `.build` is
`facts?.let { reportedText(it.claudeCodeVersion, "claude_code_version" in it.truncatedFields.orEmpty()) }`
— `SessionFacts.permissionMode` is read nowhere in this flow, pinned by a
`ThreadViewModelRunningModelTest` case. Both #890 readings are cleared by the repository on the
conversation's own `session_transition` and start `null` before any announcement, so the combine needs no
staleness handling of its own — `null` in is `null` (unavailable) out. Since
[#1317](https://github.com/pyrycode/pyrycode-mobile/issues/1317) both readings are held for the life of the
host's pairing rather than one connection, so a background/foreground reconnect no longer blanks an
already-announced model or build mid-thread; `session_transition` is still the only thing that clears them
short of the pairing ending. See [Relay repository coordinator §
`HostReadings`](relay-repository-coordinator.md).

### Context usage segment (#946)

Split into [Thread composer footer — context usage
segment](thread-composer-footer-context-usage.md) on 2026-09-25 to keep this document under the
50000-byte cap the docs guard enforces. The `contextPercent` reading, the `ContextSegment` composable
(including the corrected note on how [#1032](https://github.com/pyrycode/pyrycode-mobile/issues/1032)
changed its layout), the no-ask rule and the shared-value-two-surfaces note moved there verbatim.

### Remembered effort recall (#686)

Split into [Thread composer footer — remembered effort recall](thread-composer-footer-effort-recall.md)
on 2026-09-24 to keep this document under the 50000-byte cap the docs guard enforces. The one remembered
app-wide effort level, `EffortRecall`, its decision rules, cancel/remember/isolation behavior and its
logging moved there verbatim.

### Actions menu (#884)

Split into [Thread composer footer — Actions menu](thread-composer-footer-actions-menu.md) on
2026-09-25 to keep this document under the 50000-byte cap the docs guard enforces. `ComposerAction`,
the live background-task count, the absence proof — including how (#1111) a `SessionSettings.capabilities`
list's `slashCommands` flag marks a command absent regardless of the published menu — and the dispatch
and send path moved there verbatim.

## Shape

```kotlin
enum class FooterControl { Model, Effort, Permission, Actions }

data class FooterMenu(
    val options: List<OptionsOverlayOption>,
    val selectedValue: String,
    val notListed: Int,
    val actions: Boolean = false,
)

internal fun footerMenu(
    control: FooterControl,
    runConfig: ThreadRunConfig,
    mutationsSupported: Boolean = true,
    absentActions: Set<ComposerAction> = emptySet(),
): FooterMenu?

internal fun footerControlEnabled(control: FooterControl, runConfig: ThreadRunConfig, connected: Boolean): Boolean

@Composable
fun ThreadComposerFooter(
    runConfig: ThreadRunConfig,
    onOpen: (FooterControl) -> Unit,
    onStatusClick: () -> Unit,
    onAnchorChanged: (FooterControl, Rect) -> Unit,
    modifier: Modifier = Modifier,
    onAttach: () -> Unit = {},
    agent: ConversationAgent = ConversationAgent.Claude,
    connected: Boolean = true,
)
```

`agent` ([#1115](https://github.com/pyrycode/pyrycode-mobile/issues/1115)) is `ThreadScreen`'s own `state.agent`, read only by the effort button's `effortNote?.text(agent)` — see [§ Applied effort](#applied-effort-889). Defaulting to `Claude` keeps every prior call site and preview compiling unchanged.

`FooterControl` still names the older Model, Effort and Permission projections and the visible Actions control. Only Actions has a footer button after #1196; Model, Effort and Permission are edited in Run configuration.

`footerMenu(control, runConfig, mutationsSupported, absentActions)` is a pure option projection; only its Actions control has a visible footer button now. It returns `null` when a choice control has nothing to offer. `mutationsSupported` and `absentActions` are defaulted so every pre-#884 call site still compiles; only the Actions branch reads them.

- **Model** — `null` unless `runConfig.menuAvailable && runConfig.choices.isNotEmpty()`. Options are ordinary `choices` mapped to `(value, label)` in published order, with no `default` option; `selectedValue = runConfig.selectedChoice?.value.orEmpty()`, leaving none marked for unknown or unrepresented settings. The render cap of 32 applies after hiding `default`, but inherited uniqueness is checked against the full list. `notListed = runConfig.droppedModels + runConfig.hiddenChoices`; neither figure is inferred from the rendered row count. The Run configuration sheet reads `runConfig.choices` directly; this older footer projection remains testable.
- **Effort** — `null` when the explicit row or inherited `default` metadata publishes no levels, or a present `capabilities` list filters them all out. Inherited metadata still applies when no ordinary row represents the resolved default model. `selectedValue = runConfig.selectedEffort`; `notListed = 0`. The Run configuration sheet reads `runConfig.effortChoices` directly; this older footer projection remains testable.
- **Permission** (#650) — `null` when `runConfig.permissionMode` is `""`. Options are every `PermissionModeOption` `runConfig.offersPermission` allows (#1111) — `Auto` filtered out unless `runConfig.selectedMetadata?.supportsAutoMode == true` (the same field the [Status sheet](status-sheet.md)'s Model section reads for its own rows), and, with a `capabilities` list present, every mode but `Bypass` must be in `capabilities.permissionModes`; `selectedValue = runConfig.permissionMode` — an unrecognised value therefore selects nothing in the overlay, since it matches no `PermissionModeOption.wire`; `notListed = 0` always, since the vocabulary is closed and client-owned. See [§ Sourcing — Permission mode](#permission-mode-650) for the label and write rules.
- **Actions** (#884) — never `null`. See [Actions menu](thread-composer-footer-actions-menu.md#actions-menu-884) for its options, the `mutationsSupported` gate on Reset session, and the `absentActions` enable rule; labels are fixed client-owned strings after #1668.

`footerControlEnabled` takes a required `connected` flag (#1319, no default — every caller must state it) and returns `false` for every control, Actions included, while the host is not connected, before any of the per-control rules below run. When connected, it still gates the retained Model, Effort and Permission menu projections on a writable session, no relevant pending write and an available menu; Actions needs none of that — a command send addresses no session and writes no setting. The current sheet gates its own Model/Effort and Permission rows directly; the footer opens only Actions.

## How it works

### `FooterButton` and Run configuration access

The current footer renders an Actions button disabled only while the host is
not connected (#1319), the `Cxt:` reading, the attachment button and the
trailing `Tune` icon. The icon opens the
[Run configuration sheet](status-sheet.md), where Model, Effort and Permission
choices are rendered. The sheet receives `runConfig.selectedChoice?.value` for
its model radio selection, `runConfig.modelSelectionNote` for an unrepresented
confirmed choice, and each visible row's raw `value` for writes. It closes after
a choice; a fresh conversation-scoped settings reading confirms the pending
value. The old `FooterControl.Model`, `.Effort` and `.Permission` projections
remain in `footerMenu`, but no separate footer button opens them after #1196.

The current dark Input area uses 12 dp left, 16 dp right, 4 dp top and zero visible bottom footer padding inside the unchanged 20 dp composer gutter (#1659). Actions and context retain their 16 dp gap, body-small text and primary colour; Actions' chevron is 8 × 4 dp with a 4 dp label gap. The trailing group is 60 × 16 dp: two 24 × 16 dp visual boxes with a 12 dp gap, centring the 11 × 12 dp paperclip and 16 × 16 dp tune icon. At the 412 dp viewport it spans x=316–376, ending 16 dp inside the footer's right edge. The new context circle is a separate change.

### Trailing icons stay outside the weighted text region (#1032, wrap shape #1549)

The outer `Row` measures the paperclip and Run configuration opener as fixed
24dp-wide controls beside the weighted `FooterTextRow`, preserving both icons
when the Actions label or context reading grows. `FooterTextRow` gives the
`ContextSegment` whatever width the Actions button leaves. See
[context usage](thread-composer-footer-context-usage.md) for the original
width failure and its measurement rule.

Since [#1549](https://github.com/pyrycode/pyrycode-mobile/issues/1549) (design decision on
[#1485](https://github.com/pyrycode/pyrycode-mobile/issues/1485), 2026-10-02), `FooterTextRow` first checks
whether Actions, the gap and the label fit one row at their natural widths. If they do, layout is unchanged
from #1032: one row, bottom-aligned. If not, the label moves whole to its own line under the button row,
`FooterLineGap` (4dp, Figma `679:4116`) below the buttons' visible text — measured above
`contentBottomPadding`, the 12dp of invisible touch overflow the thread passes in, not above the buttons'
full touch box, since a 4dp gap from the touch box rendered as roughly 16dp on device. The layout reports a
`FooterFirstRowBottom` `HorizontalAlignmentLine` at the button row's bottom; the outer `Row` aligns
`FooterTextRow` by that line and the trailing group by its touch-box bottom (`Modifier.alignBy`), so both centred visual boxes stay aligned with the first visible Actions row whether or not the label has wrapped. `alignBy` places the aligned group at
the *top* of a row taller than its content, unlike the old `Alignment.Bottom`, so the outer `Row` also takes
`wrapContentHeight(Alignment.Bottom)` to keep the footer's bottom placement in a slot taller than its
content (a forced test size, or any fixed-height parent). `ContextSegment` still has `maxLines = 1` and
`TextOverflow.Ellipsis`: the wrapped line is only `FooterTextRow`'s own width, so a wide enough font scale
could still ellipsize it, but #1549's 150%-text acceptance scope fits in full. See [context
usage](thread-composer-footer-context-usage.md) for the test coverage and the accepted ellipsis boundary.

### Wiring in `ThreadScreen`

`openControl` is keyed by `state.conversationId`, so switching conversations
closes an Actions overlay. The overlay re-derives `footerMenu` from current
state rather than retaining an option captured when it opened. Its anchor
comes from the button's live window bounds translated into the screen's
`Box` coordinates; the menu follows the composer as the keyboard lifts it.
The trailing `Tune` icon opens `StatusSheet`, whose Model selection uses the
same `ThreadRunConfig` projection as `footerMenu(Model)`. Running-model text
is a separate reading and never selects a model row.

`ThreadScreen` supplies 28 dp-high touch boxes, including 12 dp of invisible bottom overflow beyond the 16 dp visual band. `frameHeightWithTouchOverflow` keeps that overflow outside the visible frame; Compose expands the hit areas to 48 dp without changing visual widths or gaps. Preserve the [expanded-touch input-clearance checks](thread-composer-footer-testing.md#testing) when changing wrappers: visual bounds alone cannot establish that the input remains reachable.

## State + concurrency model

At the composable layer: no new coroutines, flows or state. `openControl`, `footerAnchors` and `layerOrigin` are all screen-local Compose `remember` state, written from layout callbacks (`onGloballyPositioned`) and read only by the currently-open overlay. Selection goes through the existing handlers; the ViewModel owns pending, confirmation and revert exactly as it did for the retired row and the sheet.

At the ViewModel layer, Permission (#650) is not read-only like Model/Effort's pending-then-confirm shape: it owns one `Job` at a time (`ThreadViewModel.permissionWrite`, wrapping the send-then-settle coroutine and its target session id), started lazily so the field is set before the body runs, and torn down either by its own completion or by the context-cancellation rule in [§ Sourcing — Permission mode](#permission-mode-650). All of it runs on `viewModelScope` (Main); the only state carried across a suspension point is the job's own `permissionWrite` / `pendingPermission`.

## Error handling

Model and Effort: no new failure modes. A refused write surfaces as it always has: the ViewModel clears the relevant `pending*` flow and `sessionSettingsErrors` drives the existing snackbar; the footer re-renders the saved label with no footer-side branch for the failure. A control with nothing to offer is disabled and does not open an empty overlay — see `footerControlEnabled` above.

Permission (#650): a refusal or send failure clears `pendingPermission`, drives the same `sessionSettingsErrors` snackbar, and issues one re-read — see [§ Sourcing — Permission mode](#permission-mode-650). A settle that never confirms within 15 s expires silently; nothing is shown beyond the label reverting to whatever the last real reading said, since the daemon may legitimately ack a write without the mode actually changing.

**Known interaction, not blocking (verifier NIT on #650):** `sessionSettings.onEach` clears `pendingModel` / `pendingEffort` on **every** delivered reading, a rule that predates #650. A permission settle can produce up to ~31 extra readings in 15 s, so a model or effort tap that lands mid-settle can have its optimistic pending label cleared by one of those settle-driven readings before its own ack-triggered re-read arrives — a one-round-trip flicker back to the previous value. Not observed in practice and not fixed as of #650; flagged here because it is the one way a permission-mode write can touch Model/Effort's own rendering.

## Testing

Split into [Thread composer footer — testing](thread-composer-footer-testing.md) on 2026-09-24 to keep this document under the 50000-byte cap the docs guard enforces. Every case, including the Actions menu's ([#884](https://github.com/pyrycode/pyrycode-mobile/issues/884)) `FooterMenuTest` / `ComposerActionAvailabilityTest` / `ThreadViewModelComposerActionsTest` / `ThreadComposerFooterTest` coverage and its two testing lessons (the outside-tap overlay-position regression and the `MutableStateFlow`-seed `advanceUntilIdle()` requirement), moved there verbatim.

## Previews

Two `@Preview`s in `ThreadComposerFooter.kt` — `ThreadComposerFooterDarkPreview` and `ThreadComposerFooterLightPendingPreview` — both render the current Actions/context/icons footer at `widthDp = 372`. The preview config retains model and permission values for the shared state, though their separate footer buttons no longer render.

## Edge cases / limitations

- **The paperclip ([#933](https://github.com/pyrycode/pyrycode-mobile/issues/933), revised spacing #1659).** Its 24 × 16 dp visual box precedes the Tune opener and retains `cd_attach_files`; Tune retains `cd_thread_status_expand`. Attach is not a `FooterControl`, since it opens no `OptionsOverlay`: `onAttach` is bound in `ThreadScreen` to `rememberAttachmentPicker`'s launch action. See [composer pending attachments](thread-screen-composer-drafts-and-attachments.md#composer-pending-attachments) for the picker and strip.
- **Adding a footer button before Model shifts where every later overlay opens.** [#884](https://github.com/pyrycode/pyrycode-mobile/issues/884) put Actions ahead of Permission/Model/Effort, so the Model overlay now opens far enough right, at narrow widths, to reach past the composer's horizontal centre — a test (or any other geometry assumption) that treats "near the composer's centre" as "clearly outside every overlay" needs re-checking whenever a button is added or reordered ahead of it. See [Thread composer footer — testing](thread-composer-footer-testing.md#testing) for the regression this caused and its fix.
- **`effortLevels` has no count cap**, unlike the model menu's `MAX_RENDERED_MODEL_CHOICES = 32` / `hiddenChoices`. [Options overlay](options-overlay.md) scrolls, so an unbounded effort menu degrades to a tall scrolling list rather than a layout break, but it composes every row. Flagged for triage on the #808 PR as an out-of-scope hostile-daemon-frame finding; a cap would belong beside `MAX_RENDERED_MODEL_CHOICES` in `ThreadViewModel.kt`.
- **Keyboard evidence is limited to footer layout.** Emulator captures show the footer above the software keyboard at 412 × 892 and 360 × 640; they do not prove overlay placement after an Actions tap with the keyboard open. The overlay still depends on the anchoring button's live bounds inside the `imePadding()` column.

## Related

- [Options overlay](options-overlay.md) — the popup this footer opens; owns placement, the scrim, and the row rendering, for all four controls including Permission (#650) and Actions (#884).
- [Status sheet](status-sheet.md) — the retained expanded surface, reachable from the trailing icon, hosting Model, Effort and the Context-window section. Reads the same `ThreadRunConfig`. Its YOLO toggle was retired outright by [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650), not relocated.
- [Thread screen — the list, chip, empty state and status row](thread-screen-how-it-works-list-and-status-row.md#status-row-wiring-post-145) — the historical `bottomBar` wiring narrative through [#145](../codebase/145.md)–[#807](../codebase/807.md), before this ticket's replacement.
- [Thread input bar](thread-input-bar.md) — the composer this footer stacks below, inside the same `bottomBar` column.
- [Thread overflow menu](thread-overflow-menu.md) — owns the Reset session item the Actions menu's own Reset row dispatches through, and the `mutationsSupported` gate both share.
- [Shared mobile modal § Callers](mobile-modal-callers.md#callers) — `BackgroundTaskPanel` (#678), the read-only panel opened from the top menu or running-task pill.
- [Thread composer footer — testing](thread-composer-footer-testing.md) — the full test-case list for every control, split out to keep this document under the size cap.
- [Thread composer footer — Actions menu](thread-composer-footer-actions-menu.md) — `ComposerAction`, the absence proof (including #1111's `slashCommands` capability rule) and the dispatch/send path, split out to keep this document under the size cap.
- [Thread composer footer — remembered effort recall](thread-composer-footer-effort-recall.md) — `EffortRecall`'s once-per-opening decision, cancel, remember-only-successes and isolation rules, split out to keep this document under the size cap.
- [Thread composer footer — context usage segment](thread-composer-footer-context-usage.md) — the `Cxt:` reading, its no-ask rule, and (since #1032) the corrected note on why the segment's own weight never protected the trailing icons; split out to keep this document under the size cap.
- Tickets: [#808](../codebase/808.md) (Model + Effort buttons, this component's shape), [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) (Permission button, the settle rule, session-reset staleness, YOLO retirement), [#889](https://github.com/pyrycode/pyrycode-mobile/issues/889) (applied effort display, § Applied effort above), [#686](https://github.com/pyrycode/pyrycode-mobile/issues/686) (remembered-effort recall, [split doc](thread-composer-footer-effort-recall.md) above; mobile port of desktop [#1549](https://github.com/pyrycode/pyrycode-desktop/issues/1549) / PR #1554), [#884](https://github.com/pyrycode/pyrycode-mobile/issues/884) (Actions menu, § Actions menu above; split from #655; ports desktop's `ComposerActionsMenu` / `composerActionAvailability.ts`, gated on the [#882](https://github.com/pyrycode/pyrycode-mobile/issues/882) published slash-command menu), [#678](https://github.com/pyrycode/pyrycode-mobile/issues/678) (the original background-task entry, removed from Actions by #1668; the panel now opens from the top menu or pill; the roster store itself is [#677](https://github.com/pyrycode/pyrycode-mobile/issues/677)), [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946) (`Cxt:` segment, [split doc](thread-composer-footer-context-usage.md) above; split from #591, sourced off [#945](https://github.com/pyrycode/pyrycode-mobile/issues/945)'s `observeContextUsage`), [#1411](https://github.com/pyrycode/pyrycode-mobile/issues/1411) (reverses #946: the shown percentage is computed from token totals, falling back to session settings, rather than Claude's verbatim figure — same split doc), [#1032](https://github.com/pyrycode/pyrycode-mobile/issues/1032) (the paperclip and Status opener kept visible on a full footer; § Trailing icons stay outside the weighted text region above), [#1111](https://github.com/pyrycode/pyrycode-mobile/issues/1111) (`SessionSettings.capabilities` narrows the effort and permission menus and the Actions slash commands; `offersPermission` and the `absentComposerActions` `slashCommands` parameter above). Specs: `docs/specs/architecture/808-composer-footer-model-effort-buttons.md`, `docs/specs/architecture/650-composer-permission-mode.md`, `docs/specs/architecture/889-applied-effort-footer.md`, `docs/specs/architecture/686-remembered-effort-recall.md`, `docs/specs/architecture/884-composer-actions-control.md`, `docs/specs/architecture/678-background-task-list.md`, `docs/specs/architecture/946-context-usage-footer.md`, `docs/specs/architecture/1032-footer-trailing-icons-always-visible.md`, `docs/specs/architecture/1111-session-capabilities.md`.
- Live coverage: [#679](https://github.com/pyrycode/pyrycode-mobile/issues/679) (Model/Effort, and, per the #884 AC, the Actions menu's command rows), [#687](https://github.com/pyrycode/pyrycode-mobile/issues/687)'s `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild` (the Plan → Bypass approvals → Manual approval permission transition and its tool approval, against a dedicated operator-bypass daemon); applied effort's own live proof is [#545](https://github.com/pyrycode/pyrycode-mobile/issues/545)'s `interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn` and `interactiveTurn_chosenEffort_appliesFromTheFirstTurn`, and the saved-model round trip is the same ticket's `interactiveTurn_modelChange_roundTripsAndStaysPerConversation` — [#1308](https://github.com/pyrycode/pyrycode-mobile/issues/1308) turned that same method into a one-real-turn proof of the inherited announced-model mark — see [e2e coverage](../../e2e-interactive-stream.md). The context usage segment's own live proof is [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946)'s own `interactiveTurn_pingPrompt_footerShowsContextUsage` — see [e2e coverage](../../e2e-interactive-stream.md).
- [#1308](https://github.com/pyrycode/pyrycode-mobile/issues/1308) (inherited Claude conversations mark the model claude announces running, never "Default"; spec `docs/specs/architecture/1308-inherited-model-announced-mark.md`) — see [Status sheet § the inherited Claude mark](status-sheet.md#shape) for the full rule.
- [App preferences § Remembered effort key](app-preferences.md) — the `rememberedEffort` storage key and `EffortRecall`'s `RememberedEffortStore` adapter.
- Figma: `Input footer` [`110:3494`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=110-3494), overlay [`533:1958`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1958).
