# Thread composer footer

Always-visible row at the bottom of [`ThreadScreen`](thread-screen.md), stacked below the [`ThreadInputBar`](thread-input-bar.md) inside the same `Scaffold.bottomBar` slot. **[#808](../codebase/808.md) replaced [the retired `ThreadStatusRow`](thread-screen-how-it-works-list-and-status-row.md#status-row-wiring-post-145)** — one monospace `model · effort` string with a single expand affordance — with independent buttons, each opening the design's [Options overlay](options-overlay.md) directly above it. **[#650](https://github.com/pyrycode/pyrycode-mobile/issues/650)** added a third button, Permission, ordered first per Figma `110:3494` (`Actions · Auto · Opus · Max · Cxt`), and retired the [`StatusSheet`](status-sheet.md)'s YOLO switch — the sheet no longer hosts a permission control of any kind. A trailing icon still keeps the sheet one tap away, now for Model, Effort and the Context-window section only. **[#884](https://github.com/pyrycode/pyrycode-mobile/issues/884)** added a fourth button, Actions, leading the row per that same Figma order — a client-owned menu of Reset session, Compact session and Knowledge capture; see [§ Actions menu](#actions-menu-884).

Package: `de.pyryco.mobile.ui.conversations.thread` (`app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt`). Figma reference: the `Input footer` [`110:3494`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=110-3494) inside `Input area` [`533:1957`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1957), parent [thread screen node](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8).

## What it does

Renders one small button per control — `Actions` (#884), `Permission` (#650), `Model` and `Effort`, in that order — each showing that control's current value with a trailing up-chevron, followed by a `Tune` icon that opens the Status sheet. Tapping a button opens an [`OptionsOverlay`](options-overlay.md) listing only that control's own choices; the chevron disappears from a button that has nothing to offer, since there is no menu to promise. Choosing a Model, Effort or Permission row forwards the value to the thread's existing `onModelSelected` / `onEffortSelected` / `onPermissionModeSelected` handlers; the Actions menu is not a choice of value, so it dispatches differently — see [§ Actions menu](#actions-menu-884). Either way the overlay closes on selection; tapping outside the overlay dismisses it without selecting anything.

## Sourcing

The footer's values come from `ThreadUiState.runConfig: ThreadRunConfig` (`ThreadUiState.kt`) — the same daemon-sourced state the [`StatusSheet`](status-sheet.md) reads (for Model and Effort; see below for Permission), so the surfaces agree by construction. `ThreadRunConfig` folds `ConversationRepository.observeSessionSettings(conversationId)` (the saved `model` / `effort` / `permissionMode` plus the `sessionId` a write must address) and `observeModelMenu(conversationId)` (the models this conversation's daemon published, each with its own `effortLevels` and, since #650, `supportsAutoMode`) together with independent pending-write flags — `pendingModel: String?`, `pendingEffort: String?`, and `pendingPermission: String?` — each `null` when no write is outstanding for that control.

Two computed properties resolve what each button shows:

- **`modelLabel`** — `"unknown"` when no settings reading is available yet; `"default"` when the reading is available but the saved value is `""` (the daemon's own "no override, inherited default"); otherwise the matching published row's `displayName`, made inert, or — when the menu names no matching row — the saved value itself, made inert.
- **`effortLabel`** — `"unknown"` when no settings reading is available yet; `EFFORT_PLACEHOLDER_LABEL` ("Effort") when nothing is selected; otherwise the selected value made inert, with no menu lookup: an effort level is its own label. Unlike the model label, an empty selection never reads as `"default"` — see [§ Applied effort](#applied-effort-889) for what "selected" means since #889.

`modelLabel` reads `pendingModel` first and the saved reading second (`selectedModel = pendingModel ?: savedModel`) — a tap that has been sent but not yet confirmed by a fresh settings reading renders immediately, and the acknowledgement of the write does **not** by itself clear the pending flag; only an arriving `observeSessionSettings` emission does. A refused write needs no footer-side handling: the ViewModel clears the relevant `pending*` flow, and the label falls back to the saved reading on the next recomposition. `effortLabel` follows the same pending-renders-immediately / refusal-reverts shape, but its non-pending ranking is wider than a single saved reading — see below.

Every daemon-authored string reaching `ThreadRunConfig` (`displayName`, the saved-value fallback) is passed through an internal `String.inert()` in `ThreadViewModel.kt` before it lands — dropping `Char.isISOControl()` characters and bounding length — because `ModelMenuRow` / `SessionSettings` text crosses the subprocess trust boundary unsanitized. The write argument (`ThreadModelChoice.value` / `ThreadEffortChoice.value`) skips that treatment and stays byte-identical, since it is sent back, never rendered. See [Options overlay § Trust boundary](options-overlay.md#trust-boundary) for the overlay's own floor on the same data. `inert()` was made `internal` (from file-private) by #650 so `permissionModeLabel` in this file can reuse it for an unrecognised `permissionMode` value.

### Permission mode (#650)

The permission button reads `ThreadRunConfig.permissionMode: String` — the **latest confirmed reading's** `SessionSettings.permissionMode`, verbatim, `""` meaning no current-child confirmation (no reading yet, a dormant session, or a live child that has not confirmed). There is no fallback to stored settings, `yolo`, or `session_facts`: a non-empty `session_id` with `yolo=false` still hides the button when `permissionMode` is `""`. Unlike model/effort there is **no optimistic value** — `pendingPermission: String?` only marks a write's request-or-settle as outstanding (drives the pending dim + `stateDescription`, and blocks a second write); the label itself never reads it.

`internal enum class PermissionModeOption(val wire: String, val label: String)` (`ThreadComposerFooter.kt`) is the closed, client-owned vocabulary in menu order — `default` "Manual approval", `acceptEdits` "Auto-approve edits", `auto` "Auto approval", `plan` "Plan", `dontAsk` "Approved actions only", `bypassPermissions` "Bypass approvals" (desktop #1546's labels) — with `fromWire(String): PermissionModeOption?`. `permissionModeLabel(runConfig): String?` is `null` when `permissionMode` is `""`; the matching option's `label` for a known value; otherwise `permissionMode.inert()` — an unrecognised non-empty value renders as inert text, is never offered as a menu choice, and never becomes a write. A label describes a posture and grants nothing on its own: Manual approval still applies whatever allow rules are already in place, so a daemon that lies about its own mode (e.g. reports `default` while actually bypassing) cannot use the label to grant itself anything beyond what it could already do.

**Write path:** `ThreadViewModel.onPermissionModeSelected(value: String)` re-validates `value` against `PermissionModeOption.fromWire` — no daemon- or screen-supplied string outside that table can become a write argument. It sends nothing for: an unknown value, the already-confirmed mode, `permissionMode == ""` (button would be hidden), an outstanding permission write, `auto` when `runConfig.selectedChoice?.supportsAutoMode != true`, or no session to address (`skipUnlessWritable`). `Bypass` sends `repository.setSessionSettings(sessionId, yolo = true)`; every other mode sends `permissionMode = mode.wire`; the two are never combined — `SetSessionSettingsPayloadDto`'s `init` guard (`require(yolo == null || permissionMode == null)`) makes a both-fields frame unconstructible, so the client-side one-field rule has a deterministic backstop beneath it.

**Settle rule (desktop #1544, adopted verbatim):** `session_settings_updated` acknowledges the *request*, not claude's mode — at the current daemon, picking a mode equal to the stored setting can be acked without changing the child. After an ack, `ThreadViewModel.settlePermission` re-reads at once via `refreshSessionSettings`, then every `PERMISSION_SETTLE_INTERVAL_MS` (500 ms) inside `withTimeoutOrNull(PERMISSION_SETTLE_WINDOW_MS)` (15 s), and stops as soon as a reading reports the requested mode. Reads are serialized — the loop waits on a private `settingsReadings: MutableStateFlow<SettingsReading>` that the existing `sessionSettings.onEach` publishes (a `(seq, reading)` pair, numbered on every delivery including same-value re-reads) rather than opening a second `observeSessionSettings` collector, which on `RemoteConversationRepository`'s cold per-collector read would double the `request_session_settings` traffic. Settle expiry is silent: the label shows whatever the last reading said, since the daemon may legitimately ack without changing the child — not hidden, not "fixed" client-side.

**Failure:** a `RelayErrorException` (e.g. `session.not_found` on a dormant session) or `IllegalStateException` clears `pendingPermission`, sends the existing `sessionSettingsErrorChannel` signal (the shared failure snackbar), and triggers one `refreshSessionSettings` — no settle loop runs. `CancellationException` is rethrown before either typed catch, matching `sendSessionSettings`.

**Context cancellation:** the same `sessionSettings.onEach` that publishes the reading tick also cancels an outstanding permission write — before publishing that tick — when the delivered reading is `null` (subscription head on a host switch or an owning-host reconnect, or a failed read) or its `sessionId` differs from the write's target session. Cancelling mid-send abandons the reply waiter, so a late ack from the old context reaches nothing and cannot start a settle; the canceller clears `pendingPermission` itself. Running the cancel check before the tick publish matters: otherwise the settle loop could observe a first reading from the new context before its own job was torn down.

**Session-reset staleness:** on a `session_transition`, `RemoteConversationRepository` folds the new id into `currentSessionId` synchronously before bumping the settings-read revision, so a reading for the *old* session can still be the most recent one on hand for a beat. `ThreadRunConfig.forLiveSession(liveSessionId)` (private, `ThreadViewModel.kt`, applied in the `state` combine using `conv?.currentSessionId`) blanks `permissionMode` whenever `liveSessionId` is non-empty and differs from `runConfig.sessionId` — an equality check, not an arrival-order race, so it doesn't matter whether the transition or the settings re-read lands first. Model and effort labels are untouched by this rule.

### Applied effort (#889)

`ThreadRunConfig.appliedEffort: EffectiveEffort` carries the daemon's `session_settings.effective_effort` reading verbatim — `Applied(value)`, `NotReported` (an explicit `null`), or `Unavailable` (the key omitted; #590's three-state decode, see [Conversation repository](conversation-repository.md)). It rides the same `runConfig` fold as everything else here; `runConfig()` replaces it wholesale on every reading, so a later reading that omits the key drops a previous applied value rather than sticking.

`selectedEffort` ranks: `pendingEffort` (a tap not yet settled) → a non-empty `Applied` value → `savedEffort`, but the saved choice is used **only** when the reading is `Unavailable` or `Applied("")`. `NotReported` selects nothing (`""`), never the saved value — an explicit `null` means Claude is reporting it runs no effort parameter, and substituting the saved choice there would misstate what is actually running. This adopts desktop #1549's `selectDisplayedEffort` (PR #1554) verbatim. [#686](https://github.com/pyrycode/pyrycode-mobile/issues/686) added the phone's own app-wide (not per-conversation) recall of a remembered effort level, which rides this same write path — see [Thread composer footer — remembered effort recall](thread-composer-footer-effort-recall.md#remembered-effort-recall-686).

`effortNote: EffortNote?` explains a selection that is not Claude's own non-empty applied value: `null` when there is no settings reading at all, while a tap is pending (the existing "Applying" `stateDescription` already covers that case), or when the applied value is non-empty; `NotReported` for an explicit `null`; `SelectedRunningUnavailable` / `DefaultRunningUnavailable` for `Unavailable` / `Applied("")`, chosen by whether `savedEffort` is non-empty. `EffortNote.textRes()` (`ThreadComposerFooter.kt`) maps each case to a client-owned string resource (`thread_effort_note_selected_unavailable`, `thread_effort_note_default_unavailable`, `thread_effort_note_not_reported`) — never daemon text, since the note is the client's own explanation, not a reading. The effort `FooterButton` carries the resolved string as `stateDescription` whenever no write is pending; the [Status sheet](status-sheet.md) renders the same resolved string as one `Caption` line below the effort chips.

An applied value outside the selected model's published `effortChoices` still becomes the label (made inert) — `footerMenu`'s `selectedValue` then matches no option, so the overlay simply marks nothing selected; no extra code enforces this, it falls out of the existing equality comparison. The write path is unchanged: `onEffortSelected` only ever sends a tapped published level, and `appliedEffort` has no path into `setSessionSettings`. `selectedEffort` is also the value `onEffortSelected`'s same-value guard compares against, so tapping the level Claude already applies now sends nothing — but tapping the *saved* level while Claude runs a different one still re-sends it (harmless).

**Session-reset staleness:** `ThreadRunConfig.forLiveSession(liveSessionId)` — the same private helper that blanks a stale `permissionMode` (#650) — also blanks `appliedEffort` to `Unavailable` when `liveSessionId` is non-empty and differs from `runConfig.sessionId`, so a late reply for a replaced session never shows the old session's applied effort; the display falls back to the saved choice with the "running effort unavailable" note. The saved model and effort are choices, not readings of the running child, so `forLiveSession` leaves them alone.

**A turn ending does not by itself refresh the footer.** `observeSessionSettings` re-reads on subscription, on a `session_transition`, and after a settled write (`refreshSessionSettings`) — nothing re-reads it when a turn's `turn_end` arrives. So an effort Claude only started applying during the turn just finished (inherited or freshly chosen) reaches this footer on the *next* reading, not the one already on screen; an open thread that stays open through a turn can keep showing the saved fallback and its note after Claude has in fact applied a value. [#545](https://github.com/pyrycode/pyrycode-mobile/issues/545)'s live scenarios read the "settled" footer by leaving and reopening the thread, which subscribes again — see [e2e coverage](../../e2e-interactive-stream.md).

### Running model (#891)

`ThreadRunConfig.running: ThreadRunningModel` is a second, independent reading, not part of the five-arm
`runConfig` `combine` above (already at Kotlin's typed ceiling): a private `runningModel: Flow<ThreadRunningModel>`
combines [#890](https://github.com/pyrycode/pyrycode-mobile/issues/890)'s `observeAnnouncedModel` and
`observeSessionFacts`, and `runConfigFlow` folds it in with one more
`.combine(runningModel) { config, running -> config.copy(running = running) }` call. It is never derived
from `savedModel` / `selectedModel` and nothing falls back to it — the
[Status sheet](status-sheet-readings.md#runningmodelsection) is its only consumer; the footer's own model button and
layout are unchanged.

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
staleness handling of its own — `null` in is `null` (unavailable) out.

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

`enum class ComposerAction(val value: String, val label: String, val command: String?)` (`ThreadComposerFooter.kt`) is the closed, client-owned table behind the Actions menu, after desktop's `ComposerActionsMenu` / `composerActionAvailability.ts`, in menu order: `ResetSession` ("Reset session", no command — it dispatches the overflow menu's existing `ThreadEvent.NewSession` path, not a message), `CompactSession` ("Compact session", `/compact`), `KnowledgeCapture` ("Knowledge capture", `/knowledge-capture`), `BackgroundTasks` ("Background tasks", no command — #678). `BackgroundTasks` neither sends a message nor dispatches a `ThreadEvent`: `ThreadScreen`'s own `onSelect` flips a screen-local `backgroundTasksOpen` flag instead, so `onComposerCommand` never sees it — see [§ Dispatch and send](#actions-menu-884) below and [Shared mobile modal § Callers](mobile-modal.md#callers) for the panel it opens. Every field is a Kotlin constant; nothing the workspace publishes is ever shown, handed back or sent through this menu — see [Options overlay § Trust boundary](options-overlay.md#trust-boundary) for how that floor is enforced on the overlay side.

**Live count (#678).** `footerMenu`'s Actions branch takes a fifth parameter, `backgroundTaskCount: Int = 0` — the open conversation's `RelayRepositoryCoordinator.observeLiveBackgroundTaskCount` reading, folded into `ThreadUiState.backgroundTaskCount` the same way `absentActions` is folded in, one more `.combine` after the five-arm `runConfig` combine. Only the `BackgroundTasks` row's label reads it, appended as `"${it.label} ($backgroundTaskCount)"` — a number, never a task string. `liveCount` excludes a finished task (#677), so a task followed by its own completed update reads back to 0. The panel's own "Finished" label is not equally durable: claude can send an empty `background_task_roster` unprompted after a finish, and the roster's wholesale-replace fold then reads the task as gone rather than finished — #967's live run observed this win the race, so a test reading the panel after a finish must accept either "Finished" or "No background tasks".

`footerMenu(FooterControl.Actions, runConfig, mutationsSupported, absentActions)` is never `null`: its options are `ComposerAction.entries`, `ResetSession` dropped unless `mutationsSupported` (the overflow menu's own Reset session item stays, under the same gate), each row `enabled = it !in absentActions`, and `FooterMenu.actions = true` so [`OptionsOverlay`](options-overlay.md) draws button-mode rows instead of a radio group. `footerControlEnabled(Actions, …)` skips the `writable && !outstanding` rule every other control follows and returns `true` unconditionally — a command send addresses no session and writes no setting, so there is nothing to disable.

**Absence proof.** `internal fun absentComposerActions(menu: SlashCommandMenu?): Set<ComposerAction>` (`ThreadViewModel.kt`) returns the command-bearing actions the conversation's published slash-command menu ([#882](https://github.com/pyrycode/pyrycode-mobile/issues/882)) proves absent, fail-open by construction: it needs a non-null `menu`, `droppedCommands == 0` (a negative count proves nothing), no row with `"name"` or `"aliases"` in its `truncatedFields` (a truncated `description` or `argument_hint` still proves), and no row whose `name` or any alias exactly equals the command with its leading slash stripped — no case fold, no trim, no slash on either side. Anything short of that leaves every command available. `ResetSession` is never in the returned set, since the published menu never governs it. `ThreadViewModel.absentActions: Flow<Set<ComposerAction>>` is `repository.observeSlashCommandMenu(conversationId).map(::absentComposerActions).onStart { emit(emptySet()) }.distinctUntilChanged()`, joined into `state` with one more `.combine(absentActions) { uiState, absent -> uiState.copy(absentActions = absent) }` step after the five-arm `runConfig` combine — the same reason [§ Running model](#running-model-891) rides its own separate combine. `ThreadUiState.absentActions` carries only the verdict, never a published string.

**Dispatch and send.** `ThreadScreen`'s overlay `onSelect` resolves the tapped `value` through `ComposerAction.fromValue`: an unknown value dispatches nothing, `ResetSession` becomes `onOverflowEvent(ThreadEvent.NewSession)` — the existing #540/#625 reset path, gated by `mutationsSupported` exactly as the overflow menu's own item — `BackgroundTasks` (#678) sets `backgroundTasksOpen = true` and opens [`BackgroundTaskPanel`](mobile-modal.md#the-read-only-panel-mobilereadonlymodal) in the shared read-only shell, and every other action becomes `onComposerCommand(action)`. `ThreadViewModel.onComposerCommand(action)` returns without sending when `action.command == null` (Reset never reaches this function) or when `action in state.value.absentActions` — a deterministic backstop behind the greyed-out row, in case a stale overlay composition still delivers a tap. Otherwise it runs `launchGuardedRepoCall { effortRecall.awaitWrite(); repository.sendMessage(conversationId, command) }` — the same guarded body `sendMessage` runs (see [Thread composer footer — remembered effort recall](thread-composer-footer-effort-recall.md#remembered-effort-recall-686)), minus the draft clear and attachment pick-up, so the typed draft (even one that equals the command text) and pending attachments are left untouched. A failed send is swallowed exactly as a composer send's is: no confirmed echo is inserted, so the command never appears as sent in the thread. Both outcomes log a static `event=composer_action action=<value> outcome=sent|absent` line — never a conversation id, a command's effect, or menu content.

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
    backgroundTaskCount: Int = 0,
): FooterMenu?

internal fun footerControlEnabled(control: FooterControl, runConfig: ThreadRunConfig): Boolean

@Composable
fun ThreadComposerFooter(
    runConfig: ThreadRunConfig,
    onOpen: (FooterControl) -> Unit,
    onStatusClick: () -> Unit,
    onAnchorChanged: (FooterControl, Rect) -> Unit,
    modifier: Modifier = Modifier,
)
```

`FooterControl` is the extension point for further controls, as Permission (#650) and Actions (#884) both show: each adds an entry here, a `footerMenu` branch, a `footerControlEnabled` case where its rule differs from the default, and a button in `ThreadComposerFooter` — nothing else about the shape needs to change.

`footerMenu(control, runConfig, mutationsSupported, absentActions)` is a pure function returning `null` when the control has nothing to offer. `mutationsSupported` and `absentActions` are defaulted so every pre-#884 call site still compiles; only the Actions branch reads them.

- **Model** — `null` unless `runConfig.menuAvailable && runConfig.choices.isNotEmpty()`. Options are `choices` mapped to `(value, label)` in daemon order; `selectedValue = runConfig.selectedModel`; `notListed = runConfig.droppedModels + runConfig.hiddenChoices` — the producer's own cut (`droppedModels`) plus this client's render cap (`hiddenChoices`, from `MAX_RENDERED_MODEL_CHOICES = 32`), summed for display exactly as the [`StatusSheet`](status-sheet.md) sums them. Neither figure is ever recomputed from `choices.size`.
- **Effort** — `null` when `runConfig.effortChoices` is empty: the row backing the saved model — or, with no model override (`savedModel == ""`), the published `default` row (#972, mirroring desktop's `effortRowFor`) — publishes no levels, or no such row exists. `selectedValue = runConfig.selectedEffort`; `notListed = 0` always, since effort levels have no producer-side cut or client-side cap.
- **Permission** (#650) — `null` when `runConfig.permissionMode` is `""`. Options are every `PermissionModeOption`, `Auto` filtered out unless `runConfig.selectedChoice?.supportsAutoMode == true` (the same field the [Status sheet](status-sheet.md)'s Model section reads for its own rows); `selectedValue = runConfig.permissionMode` — an unrecognised value therefore selects nothing in the overlay, since it matches no `PermissionModeOption.wire`; `notListed = 0` always, since the vocabulary is closed and client-owned. See [§ Sourcing — Permission mode](#permission-mode-650) for the label and write rules.
- **Actions** (#884) — never `null`. See [§ Actions menu](#actions-menu-884) for its options, the `mutationsSupported` gate on Reset session, and the `absentActions` enable rule; since #678 it also reads `backgroundTaskCount`, folded only into the background-tasks row's own label.

`footerControlEnabled(control, runConfig)` is `runConfig.writable && !outstanding && footerMenu(control, runConfig) != null` for Model, Effort and Permission — the [`StatusSheet`](status-sheet.md)'s own `enabled && !pending` rule, plus the one rule specific to this surface: a control with nothing to offer does not open an empty overlay. `outstanding` is `runConfig.pending` (`pendingModel != null || pendingEffort != null`) for Model and Effort — a write in flight on either one disables both buttons, matching the sheet's own single `pending` gate — but `runConfig.pendingPermission != null` for Permission, its own gate (#650): a model or effort tap does not block the permission button, and a permission write does not block the other two. Actions (#884) skips this rule entirely and is always `true` — see [§ Actions menu](#actions-menu-884). `footerMenu` and `footerControlEnabled` are the one place these rules are written; `ThreadScreen`'s stale-overlay close (below) reads the same functions so the two surfaces cannot disagree.

## How it works

### `FooterButton` — one per control, plus a trailing icon for the sheet

Each button is a `Row` at least 32dp tall (Figma's own buttons are 16dp; this grows the touch target for a thumb) showing the resolved label (`bodySmall`, one line, ellipsized, width-capped at 140dp) followed by a 14dp up-chevron — the chevron is omitted when the button is neither enabled nor pending, since a disabled button with no pending write has nothing to promise. The button reports its own window bounds via `Modifier.onGloballyPositioned { onBounds(it.boundsInWindow()) }`; `ThreadScreen` uses this to place the overlay (below). A pending button (`pendingModel != null` for the model button, `pendingEffort != null` for the effort button, `pendingPermission != null` for the permission button — each read independently, not through `runConfig.pending`) renders at `0.55f` alpha and carries a `stateDescription` of `thread_footer_pending` ("Applying"), so a screen reader distinguishes "applying" from a plain disabled control. The trailing `Tune` icon is a 32dp tap target around a 16dp glyph with the existing `cd_thread_status_expand` content description — the design has no footer affordance for the Status sheet, which since #650 hosts Model, Effort and the Context-window section only (the permission control moved here and the YOLO switch was retired outright).

The Actions button (#884) is drawn first, always enabled, with `label` and `clickLabel` `R.string.thread_footer_actions` / `R.string.thread_footer_open_actions` ("Actions" / "Open actions"). The permission button is drawn next, before Model and Effort (Figma `110:3494`'s `Actions · Auto · Opus · Max · Cxt` order), and only when `permissionModeLabel(runConfig)` is non-null — unlike Actions, Model and Effort, which always render (Model/Effort possibly disabled) once the footer itself is shown. The permission button's `clickLabel` is `R.string.thread_footer_change_permission` ("Change permission mode").

### Trailing icons stay outside the weighted text region (#1032)

The outer `Row` holds three top-level children: `FooterTextRow` (`Modifier.weight(1f)`), the paperclip `Box`, then the Status opener `Box`. A `Row` measures its non-weighted children first, in source order, before handing the rest to a weighted one — a fixed-size child placed *after* a weighted sibling in source order is not protected by that sibling's weight; it is measured exactly like every other non-weighted child, against whatever width is left once the row's earlier children have taken theirs. Before #1032 the two icon boxes sat after a weighted `ContextSegment` inside one flat `Row` with the buttons, so a full set of long labels (seen on a 1080px phone showing "Actions, Manual approval, default, medium") could still starve the icons to zero width — see [Thread composer footer — context usage segment](thread-composer-footer-context-usage.md) for that segment's corrected description. Moving the icon boxes to be siblings of the weighted region, not members of it, is what guarantees them their 32dp regardless of button count or label length.

`FooterTextRow`, a private `Layout`, holds the buttons and, as its last child, `ContextSegment`. Each button is measured at `min(maxIntrinsicWidth, cap)`, where `internal fun footerShrinkCap(widths: List<Int>, available: Int): Int` (pure) is `Int.MAX_VALUE` when the buttons already fit, else the largest per-button cap that keeps every capped button's width sum within `available` — the widest labels shrink first, and no button is squeezed to nothing while another keeps its full label. `ContextSegment` gets whatever width the buttons leave, possibly none, same as before #1032. `FooterButton`'s label `Text` carries `Modifier.weight(1f, fill = false)` so a capped button's chevron is measured before the label and survives the ellipsis rather than being pushed off with it. A plain nested `Row` (buttons measured in source order with no shared cap) was rejected: at the full-footer width it would starve whichever button came last in the text region rather than sharing the shrink across the widest labels.

### Wiring in `ThreadScreen`

```kotlin
var openControl by remember(state.conversationId) { mutableStateOf<FooterControl?>(null) }
val footerAnchors = remember { mutableStateMapOf<FooterControl, Rect>() }
var layerOrigin by remember { mutableStateOf(Offset.Zero) }
val openMenu =
    openControl
        ?.takeIf { footerControlEnabled(it, state.runConfig) }
        ?.let { control ->
            footerMenu(control, state.runConfig, state.mutationsSupported, state.absentActions, state.backgroundTaskCount)
                ?.let { control to it }
        }
LaunchedEffect(openControl, openMenu == null) {
    if (openMenu == null) openControl = null
}
Box(modifier = modifier.onGloballyPositioned { layerOrigin = it.positionInWindow() }) {
    Scaffold(modifier = Modifier.fillMaxSize()) { /* … topBar, bottomBar, body … */ }
    openMenu?.let { (control, menu) ->
        footerAnchors[control]?.let { anchor ->
            OptionsOverlay(
                options = menu.options,
                selectedValue = menu.selectedValue,
                notListed = menu.notListed,
                anchor = anchor.translate(-layerOrigin),
                onSelect = { value ->
                    when (control) {
                        FooterControl.Model -> onModelSelected(value)
                        FooterControl.Effort -> onEffortSelected(value)
                        FooterControl.Permission -> onPermissionModeSelected(value)
                        FooterControl.Actions ->
                            when (val action = ComposerAction.fromValue(value)) {
                                null -> Unit
                                ComposerAction.ResetSession -> onOverflowEvent(ThreadEvent.NewSession)
                                ComposerAction.BackgroundTasks -> backgroundTasksOpen = true
                                else -> onComposerCommand(action)
                            }
                    }
                    openControl = null
                },
                onDismiss = { openControl = null },
                actions = menu.actions,
            )
        }
    }
}
```

`Scaffold` moved inside a `Box` that records its own window origin (`onGloballyPositioned { layerOrigin = it.positionInWindow() }`), because [`OptionsOverlay`](options-overlay.md) draws in the screen's own window rather than a separate `Popup` window and needs its anchor in the `Box`'s own coordinates, not the device window's. `footerAnchors` is fed by the footer's `onAnchorChanged` callback and holds each control's **live window bounds**; the overlay call translates a control's window-space `Rect` into the `Box`'s layer-space `Rect` by subtracting `layerOrigin`. Because both `footerAnchors[control]` and `layerOrigin` are read every frame from live layout callbacks, the overlay follows the composer as it lifts with `imePadding()` — there is no separate "is the keyboard open" branch.

`openControl` is a **plain `remember`, deliberately not `rememberSaveable`**, keyed on `state.conversationId`. Two things follow from that: switching conversations within one composition drops any open overlay (the `remember` key changes), and a process death / config change never restores an overlay a fresh screen instance never opened. `openMenu` re-derives `footerMenu(...)` on every composition from the **current** `state.runConfig` (plus, since #884, `state.mutationsSupported` and `state.absentActions`), gated by `footerControlEnabled`, rather than caching the menu captured at open time — so if a write goes pending, a fresh reading drops the selected model's effort levels, the session's `sessionId` empties out, or the published slash-command menu changes what it proves absent while the overlay is showing, `openMenu` is recomputed (or, for Model/Effort/Permission, becomes `null` and the `LaunchedEffect(openControl, openMenu == null)` clears `openControl` on the next frame) without an explicit "is my menu still valid" check anywhere else.

`onSelect` dispatches straight through the existing `onModelSelected: (String) -> Unit` / `onEffortSelected: (String) -> Unit` parameters — unchanged since [#807](../codebase/807.md) — plus, since [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650), `onPermissionModeSelected: (String) -> Unit`, and, since [#884](https://github.com/pyrycode/pyrycode-mobile/issues/884), `onComposerCommand: (ComposerAction) -> Unit` for a command row and the existing `onOverflowEvent(ThreadEvent.NewSession)` for Reset session — then closes the overlay; `onDismiss` only closes it. Model, Effort and Permission still round-trip through `ConversationRepository.setSessionSettings`, addressed to `SessionSettings.sessionId`, exactly as the sheet's own rows do; the permission handler additionally re-validates the value and picks the `yolo` vs. `permission_mode` field — see [§ Sourcing — Permission mode](#permission-mode-650). Actions sends a message or dispatches the reset event instead — see [§ Actions menu](#actions-menu-884).

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

Two `@Preview`s in `ThreadComposerFooter.kt` — `ThreadComposerFooterDarkPreview` (all three buttons resolved, dark theme) and `ThreadComposerFooterLightPendingPreview` (the effort button pending and, since #650, the permission button pending too, light theme) — both `widthDp = 372`. `previewRunConfig` carries `permissionMode = "plan"` so the permission button renders in both previews.

## Edge cases / limitations

- **The design has no Status-sheet affordance in the footer.** A trailing `Tune` icon carries the existing `cd_thread_status_expand` description regardless. Since #650 the sheet hosts Model, Effort and the Context-window section only — the permission control lives exclusively here now, and the retired YOLO switch has no replacement in the sheet. `Actions` (split from #655) landed as [#884, § Actions menu](#actions-menu-884), and `Cxt:` (split from #591) landed as [#946, § Context usage segment](#context-usage-segment-946).
- **The paperclip ([#933](https://github.com/pyrycode/pyrycode-mobile/issues/933), Figma `115:3654`).** A plain `Box` (32dp touch target like the Status opener, 11×12dp glyph, `R.drawable.ic_attach_file` tinted `primary`) sits right before the Status opener, at the row's trailing end — not a `FooterControl`, since it opens no `OptionsOverlay`: its `onAttach: () -> Unit` parameter is bound in `ThreadScreen` to `rememberAttachmentPicker`'s launch action. See [Thread screen § Composer pending attachments](thread-screen-composer-drafts-and-attachments.md#composer-pending-attachments) for the picker and the strip it fills, and [Thread input bar § Shape](thread-input-bar.md#shape) for how a pending attachment changes Send/Stop.
- **Adding a footer button before Model shifts where every later overlay opens.** [#884](https://github.com/pyrycode/pyrycode-mobile/issues/884) put Actions ahead of Permission/Model/Effort, so the Model overlay now opens far enough right, at narrow widths, to reach past the composer's horizontal centre — a test (or any other geometry assumption) that treats "near the composer's centre" as "clearly outside every overlay" needs re-checking whenever a button is added or reordered ahead of it. See [Thread composer footer — testing](thread-composer-footer-testing.md#testing) for the regression this caused and its fix.
- **`effortLevels` has no count cap**, unlike the model menu's `MAX_RENDERED_MODEL_CHOICES = 32` / `hiddenChoices`. [Options overlay](options-overlay.md) scrolls, so an unbounded effort menu degrades to a tall scrolling list rather than a layout break, but it composes every row. Flagged for triage on the #808 PR as an out-of-scope hostile-daemon-frame finding; a cap would belong beside `MAX_RENDERED_MODEL_CHOICES` in `ThreadViewModel.kt`.
- **No device test exercises the software keyboard actually showing.** The overlay's placement depends on the anchoring button's live bounds inside the `imePadding()` column reflecting the IME lift; this is exercised only through the anchor-tracking mechanism's own correctness, not an emulator run with the keyboard open. Per AC#5, #679 covers live behaviour.

## Related

- [Options overlay](options-overlay.md) — the popup this footer opens; owns placement, the scrim, and the row rendering, for all four controls including Permission (#650) and Actions (#884).
- [Status sheet](status-sheet.md) — the retained expanded surface, reachable from the trailing icon, hosting Model, Effort and the Context-window section. Reads the same `ThreadRunConfig`. Its YOLO toggle was retired outright by [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650), not relocated.
- [Thread screen — the list, chip, empty state and status row](thread-screen-how-it-works-list-and-status-row.md#status-row-wiring-post-145) — the historical `bottomBar` wiring narrative through [#145](../codebase/145.md)–[#807](../codebase/807.md), before this ticket's replacement.
- [Thread input bar](thread-input-bar.md) — the composer this footer stacks below, inside the same `bottomBar` column.
- [Thread overflow menu](thread-overflow-menu.md) — owns the Reset session item the Actions menu's own Reset row dispatches through, and the `mutationsSupported` gate both share.
- [Shared mobile modal § Callers](mobile-modal.md#callers) — `BackgroundTaskPanel` (#678), the read-only panel the Actions menu's background-tasks row opens.
- [Thread composer footer — testing](thread-composer-footer-testing.md) — the full test-case list for every control, split out to keep this document under the size cap.
- [Thread composer footer — remembered effort recall](thread-composer-footer-effort-recall.md) — `EffortRecall`'s once-per-opening decision, cancel, remember-only-successes and isolation rules, split out to keep this document under the size cap.
- [Thread composer footer — context usage segment](thread-composer-footer-context-usage.md) — the `Cxt:` reading, its no-ask rule, and (since #1032) the corrected note on why the segment's own weight never protected the trailing icons; split out to keep this document under the size cap.
- Tickets: [#808](../codebase/808.md) (Model + Effort buttons, this component's shape), [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) (Permission button, the settle rule, session-reset staleness, YOLO retirement), [#889](https://github.com/pyrycode/pyrycode-mobile/issues/889) (applied effort display, § Applied effort above), [#686](https://github.com/pyrycode/pyrycode-mobile/issues/686) (remembered-effort recall, [split doc](thread-composer-footer-effort-recall.md) above; mobile port of desktop [#1549](https://github.com/pyrycode/pyrycode-desktop/issues/1549) / PR #1554), [#884](https://github.com/pyrycode/pyrycode-mobile/issues/884) (Actions menu, § Actions menu above; split from #655; ports desktop's `ComposerActionsMenu` / `composerActionAvailability.ts`, gated on the [#882](https://github.com/pyrycode/pyrycode-mobile/issues/882) published slash-command menu), [#678](https://github.com/pyrycode/pyrycode-mobile/issues/678) (the `BackgroundTasks` row, § Actions menu above — live count and the panel it opens; the roster store itself is [#677](https://github.com/pyrycode/pyrycode-mobile/issues/677)), [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946) (`Cxt:` segment, [split doc](thread-composer-footer-context-usage.md) above; split from #591, sourced off [#945](https://github.com/pyrycode/pyrycode-mobile/issues/945)'s `observeContextUsage`), [#1032](https://github.com/pyrycode/pyrycode-mobile/issues/1032) (the paperclip and Status opener kept visible on a full footer; § Trailing icons stay outside the weighted text region above). Specs: `docs/specs/architecture/808-composer-footer-model-effort-buttons.md`, `docs/specs/architecture/650-composer-permission-mode.md`, `docs/specs/architecture/889-applied-effort-footer.md`, `docs/specs/architecture/686-remembered-effort-recall.md`, `docs/specs/architecture/884-composer-actions-control.md`, `docs/specs/architecture/678-background-task-list.md`, `docs/specs/architecture/946-context-usage-footer.md`, `docs/specs/architecture/1032-footer-trailing-icons-always-visible.md`.
- Live coverage: [#679](https://github.com/pyrycode/pyrycode-mobile/issues/679) (Model/Effort, and, per the #884 AC, the Actions menu's command rows), [#687](https://github.com/pyrycode/pyrycode-mobile/issues/687)'s `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild` (the Plan → Bypass approvals → Manual approval permission transition and its tool approval, against a dedicated operator-bypass daemon); applied effort's own live proof is [#545](https://github.com/pyrycode/pyrycode-mobile/issues/545)'s `interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn` and `interactiveTurn_chosenEffort_appliesFromTheFirstTurn`, and the saved-model round trip is the same ticket's `interactiveTurn_modelChange_roundTripsAndStaysPerConversation` — see [e2e coverage](../../e2e-interactive-stream.md). The context usage segment's own live proof is [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946)'s own `interactiveTurn_pingPrompt_footerShowsContextUsage` — see [e2e coverage](../../e2e-interactive-stream.md).
- [App preferences § Remembered effort key](app-preferences.md) — the `rememberedEffort` storage key and `EffortRecall`'s `RememberedEffortStore` adapter.
- Figma: `Input footer` [`110:3494`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=110-3494), overlay [`533:1958`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1958).
