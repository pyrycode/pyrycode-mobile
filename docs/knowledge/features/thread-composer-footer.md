# Thread composer footer

Always-visible row at the bottom of [`ThreadScreen`](thread-screen.md), stacked below the [`ThreadInputBar`](thread-input-bar.md) inside the same `Scaffold.bottomBar` slot. **[#808](../codebase/808.md) replaced [the retired `ThreadStatusRow`](thread-screen-how-it-works-list-and-status-row.md#status-row-wiring-post-145)** — one monospace `model · effort` string with a single expand affordance — with independent buttons, each opening the design's [Options overlay](options-overlay.md) directly above it. **[#650](https://github.com/pyrycode/pyrycode-mobile/issues/650)** added a third button, Permission, ordered first per Figma `110:3494` (`Actions · Auto · Opus · Max · Cxt`), and retired the [`StatusSheet`](status-sheet.md)'s YOLO switch — the sheet no longer hosts a permission control of any kind. A trailing icon still keeps the sheet one tap away, now for Model, Effort and the Context-window section only.

Package: `de.pyryco.mobile.ui.conversations.thread` (`app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt`). Figma reference: the `Input footer` [`110:3494`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=110-3494) inside `Input area` [`533:1957`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1957), parent [thread screen node](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8).

## What it does

Renders one small button per selectable control — `Permission` (#650), `Model` and `Effort`, in that order — each showing that control's current value with a trailing up-chevron, followed by a `Tune` icon that opens the Status sheet. Tapping a button opens an [`OptionsOverlay`](options-overlay.md) listing only that control's own published choices; the chevron disappears from a button that has nothing to offer, since there is no menu to promise. Choosing a row forwards the value to the thread's existing `onModelSelected` / `onEffortSelected` / `onPermissionModeSelected` handlers and closes the overlay; tapping outside the overlay dismisses it without selecting anything.

## Sourcing

The footer's values come from `ThreadUiState.runConfig: ThreadRunConfig` (`ThreadUiState.kt`) — the same daemon-sourced state the [`StatusSheet`](status-sheet.md) reads (for Model and Effort; see below for Permission), so the surfaces agree by construction. `ThreadRunConfig` folds `ConversationRepository.observeSessionSettings(conversationId)` (the saved `model` / `effort` / `permissionMode` plus the `sessionId` a write must address) and `observeModelMenu(conversationId)` (the models this conversation's daemon published, each with its own `effortLevels` and, since #650, `supportsAutoMode`) together with independent pending-write flags — `pendingModel: String?`, `pendingEffort: String?`, and `pendingPermission: String?` — each `null` when no write is outstanding for that control.

Two computed properties resolve what each button shows:

- **`modelLabel`** — `"unknown"` when no settings reading is available yet; `"default"` when the reading is available but the saved value is `""` (the daemon's own "no override, inherited default"); otherwise the matching published row's `displayName`, made inert, or — when the menu names no matching row — the saved value itself, made inert.
- **`effortLabel`** — the same three-state rule for `savedEffort` / `pendingEffort`, with no menu lookup: an effort level is its own label.

Both read `pendingModel` / `pendingEffort` first and the saved reading second (`selectedModel = pendingModel ?: savedModel`, and the equivalent for effort) — a tap that has been sent but not yet confirmed by a fresh settings reading renders immediately, and the acknowledgement of the write does **not** by itself clear the pending flag; only an arriving `observeSessionSettings` emission does. A refused write needs no footer-side handling: the ViewModel clears the relevant `pending*` flow, and the label falls back to the saved reading on the next recomposition.

Every daemon-authored string reaching `ThreadRunConfig` (`displayName`, the saved-value fallback) is passed through an internal `String.inert()` in `ThreadViewModel.kt` before it lands — dropping `Char.isISOControl()` characters and bounding length — because `ModelMenuRow` / `SessionSettings` text crosses the subprocess trust boundary unsanitized. The write argument (`ThreadModelChoice.value` / `ThreadEffortChoice.value`) skips that treatment and stays byte-identical, since it is sent back, never rendered. See [Options overlay § Trust boundary](options-overlay.md#trust-boundary) for the overlay's own floor on the same data. `inert()` was made `internal` (from file-private) by #650 so `permissionModeLabel` in this file can reuse it for an unrecognised `permissionMode` value.

### Permission mode (#650)

The permission button reads `ThreadRunConfig.permissionMode: String` — the **latest confirmed reading's** `SessionSettings.permissionMode`, verbatim, `""` meaning no current-child confirmation (no reading yet, a dormant session, or a live child that has not confirmed). There is no fallback to stored settings, `yolo`, or `session_facts`: a non-empty `session_id` with `yolo=false` still hides the button when `permissionMode` is `""`. Unlike model/effort there is **no optimistic value** — `pendingPermission: String?` only marks a write's request-or-settle as outstanding (drives the pending dim + `stateDescription`, and blocks a second write); the label itself never reads it.

`internal enum class PermissionModeOption(val wire: String, val label: String)` (`ThreadComposerFooter.kt`) is the closed, client-owned vocabulary in menu order — `default` "Manual approval", `acceptEdits` "Auto-approve edits", `auto` "Auto approval", `plan` "Plan", `dontAsk` "Approved actions only", `bypassPermissions` "Bypass approvals" (desktop #1546's labels) — with `fromWire(String): PermissionModeOption?`. `permissionModeLabel(runConfig): String?` is `null` when `permissionMode` is `""`; the matching option's `label` for a known value; otherwise `permissionMode.inert()` — an unrecognised non-empty value renders as inert text, is never offered as a menu choice, and never becomes a write. A label describes a posture and grants nothing on its own: Manual approval still applies whatever allow rules are already in place, so a daemon that lies about its own mode (e.g. reports `default` while actually bypassing) cannot use the label to grant itself anything beyond what it could already do.

**Write path:** `ThreadViewModel.onPermissionModeSelected(value: String)` re-validates `value` against `PermissionModeOption.fromWire` — no daemon- or screen-supplied string outside that table can become a write argument. It sends nothing for: an unknown value, the already-confirmed mode, `permissionMode == ""` (button would be hidden), an outstanding permission write, `auto` when `runConfig.selectedChoice?.supportsAutoMode != true`, or no session to address (`skipUnlessWritable`). `Bypass` sends `repository.setSessionSettings(sessionId, yolo = true)`; every other mode sends `permissionMode = mode.wire`; the two are never combined — `SetSessionSettingsPayloadDto`'s `init` guard (`require(yolo == null || permissionMode == null)`) makes a both-fields frame unconstructible, so the client-side one-field rule has a deterministic backstop beneath it.

**Settle rule (desktop #1544, adopted verbatim):** `session_settings_updated` acknowledges the *request*, not claude's mode — at the current daemon, picking a mode equal to the stored setting can be acked without changing the child. After an ack, `ThreadViewModel.settlePermission` re-reads at once via `refreshSessionSettings`, then every `PERMISSION_SETTLE_INTERVAL_MS` (500 ms) inside `withTimeoutOrNull(PERMISSION_SETTLE_WINDOW_MS)` (15 s), and stops as soon as a reading reports the requested mode. Reads are serialized — the loop waits on a private `settingsReadings: MutableStateFlow<SettingsReading>` that the existing `sessionSettings.onEach` publishes (a `(seq, reading)` pair, numbered on every delivery including same-value re-reads) rather than opening a second `observeSessionSettings` collector, which on `RemoteConversationRepository`'s cold per-collector read would double the `request_session_settings` traffic. Settle expiry is silent: the label shows whatever the last reading said, since the daemon may legitimately ack without changing the child — not hidden, not "fixed" client-side.

**Failure:** a `RelayErrorException` (e.g. `session.not_found` on a dormant session) or `IllegalStateException` clears `pendingPermission`, sends the existing `sessionSettingsErrorChannel` signal (the shared failure snackbar), and triggers one `refreshSessionSettings` — no settle loop runs. `CancellationException` is rethrown before either typed catch, matching `sendSessionSettings`.

**Context cancellation:** the same `sessionSettings.onEach` that publishes the reading tick also cancels an outstanding permission write — before publishing that tick — when the delivered reading is `null` (subscription head on a host switch or an owning-host reconnect, or a failed read) or its `sessionId` differs from the write's target session. Cancelling mid-send abandons the reply waiter, so a late ack from the old context reaches nothing and cannot start a settle; the canceller clears `pendingPermission` itself. Running the cancel check before the tick publish matters: otherwise the settle loop could observe a first reading from the new context before its own job was torn down.

**Session-reset staleness:** on a `session_transition`, `RemoteConversationRepository` folds the new id into `currentSessionId` synchronously before bumping the settings-read revision, so a reading for the *old* session can still be the most recent one on hand for a beat. `ThreadRunConfig.forLiveSession(liveSessionId)` (private, `ThreadViewModel.kt`, applied in the `state` combine using `conv?.currentSessionId`) blanks `permissionMode` whenever `liveSessionId` is non-empty and differs from `runConfig.sessionId` — an equality check, not an arrival-order race, so it doesn't matter whether the transition or the settings re-read lands first. Model and effort labels are untouched by this rule.

## Shape

```kotlin
enum class FooterControl { Model, Effort, Permission }

data class FooterMenu(
    val options: List<OptionsOverlayOption>,
    val selectedValue: String,
    val notListed: Int,
)

internal fun footerMenu(control: FooterControl, runConfig: ThreadRunConfig): FooterMenu?

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

`FooterControl` is the extension point for further controls: the Actions control (#655) adds an entry here and a branch in `footerMenu`; nothing else about the shape needs to change for it. Permission (#650) already shows the pattern — its own `footerMenu` branch, `footerControlEnabled` case and button in `ThreadComposerFooter`, with no change anywhere else.

`footerMenu(control, runConfig)` is a pure function returning `null` when the control has nothing to offer:

- **Model** — `null` unless `runConfig.menuAvailable && runConfig.choices.isNotEmpty()`. Options are `choices` mapped to `(value, label)` in daemon order; `selectedValue = runConfig.selectedModel`; `notListed = runConfig.droppedModels + runConfig.hiddenChoices` — the producer's own cut (`droppedModels`) plus this client's render cap (`hiddenChoices`, from `MAX_RENDERED_MODEL_CHOICES = 32`), summed for display exactly as the [`StatusSheet`](status-sheet.md) sums them. Neither figure is ever recomputed from `choices.size`.
- **Effort** — `null` when `runConfig.effortChoices` is empty (no row is selected, or the selected row publishes no levels). `selectedValue = runConfig.selectedEffort`; `notListed = 0` always, since effort levels have no producer-side cut or client-side cap.
- **Permission** (#650) — `null` when `runConfig.permissionMode` is `""`. Options are every `PermissionModeOption`, `Auto` filtered out unless `runConfig.selectedChoice?.supportsAutoMode == true` (the same field the [Status sheet](status-sheet.md)'s Model section reads for its own rows); `selectedValue = runConfig.permissionMode` — an unrecognised value therefore selects nothing in the overlay, since it matches no `PermissionModeOption.wire`; `notListed = 0` always, since the vocabulary is closed and client-owned. See [§ Sourcing — Permission mode](#permission-mode-650) for the label and write rules.

`footerControlEnabled(control, runConfig)` is `runConfig.writable && !outstanding && footerMenu(control, runConfig) != null` — the [`StatusSheet`](status-sheet.md)'s own `enabled && !pending` rule, plus the one rule specific to this surface: a control with nothing to offer does not open an empty overlay. `outstanding` is `runConfig.pending` (`pendingModel != null || pendingEffort != null`) for Model and Effort — a write in flight on either one disables both buttons, matching the sheet's own single `pending` gate — but `runConfig.pendingPermission != null` for Permission, its own gate (#650): a model or effort tap does not block the permission button, and a permission write does not block the other two. `footerMenu` and `footerControlEnabled` are the one place this rule is written; `ThreadScreen`'s stale-overlay close (below) reads the same function so the two surfaces cannot disagree.

## How it works

### `FooterButton` — one per control, plus a trailing icon for the sheet

Each button is a `Row` at least 32dp tall (Figma's own buttons are 16dp; this grows the touch target for a thumb) showing the resolved label (`bodySmall`, one line, ellipsized, width-capped at 140dp) followed by a 14dp up-chevron — the chevron is omitted when the button is neither enabled nor pending, since a disabled button with no pending write has nothing to promise. The button reports its own window bounds via `Modifier.onGloballyPositioned { onBounds(it.boundsInWindow()) }`; `ThreadScreen` uses this to place the overlay (below). A pending button (`pendingModel != null` for the model button, `pendingEffort != null` for the effort button, `pendingPermission != null` for the permission button — each read independently, not through `runConfig.pending`) renders at `0.55f` alpha and carries a `stateDescription` of `thread_footer_pending` ("Applying"), so a screen reader distinguishes "applying" from a plain disabled control. The trailing `Tune` icon is a 32dp tap target around a 16dp glyph with the existing `cd_thread_status_expand` content description — the design has no footer affordance for the Status sheet, which since #650 hosts Model, Effort and the Context-window section only (the permission control moved here and the YOLO switch was retired outright).

The permission button is drawn first, before Model and Effort (Figma `110:3494`'s `Actions · Auto · Opus · Max · Cxt` order), and only when `permissionModeLabel(runConfig)` is non-null — unlike Model and Effort, which always render (possibly disabled) once the footer itself is shown. Its `clickLabel` is `R.string.thread_footer_change_permission` ("Change permission mode").

### Wiring in `ThreadScreen`

```kotlin
var openControl by remember(state.conversationId) { mutableStateOf<FooterControl?>(null) }
val footerAnchors = remember { mutableStateMapOf<FooterControl, Rect>() }
var layerOrigin by remember { mutableStateOf(Offset.Zero) }
val openMenu =
    openControl
        ?.takeIf { footerControlEnabled(it, state.runConfig) }
        ?.let { control -> footerMenu(control, state.runConfig)?.let { control to it } }
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
                    }
                    openControl = null
                },
                onDismiss = { openControl = null },
            )
        }
    }
}
```

`Scaffold` moved inside a `Box` that records its own window origin (`onGloballyPositioned { layerOrigin = it.positionInWindow() }`), because [`OptionsOverlay`](options-overlay.md) draws in the screen's own window rather than a separate `Popup` window and needs its anchor in the `Box`'s own coordinates, not the device window's. `footerAnchors` is fed by the footer's `onAnchorChanged` callback and holds each control's **live window bounds**; the overlay call translates a control's window-space `Rect` into the `Box`'s layer-space `Rect` by subtracting `layerOrigin`. Because both `footerAnchors[control]` and `layerOrigin` are read every frame from live layout callbacks, the overlay follows the composer as it lifts with `imePadding()` — there is no separate "is the keyboard open" branch.

`openControl` is a **plain `remember`, deliberately not `rememberSaveable`**, keyed on `state.conversationId`. Two things follow from that: switching conversations within one composition drops any open overlay (the `remember` key changes), and a process death / config change never restores an overlay a fresh screen instance never opened. `openMenu` re-derives `footerMenu(...)` on every composition from the **current** `state.runConfig`, gated by `footerControlEnabled`, rather than caching the menu captured at open time — so if a write goes pending, a fresh reading drops the selected model's effort levels, or the session's `sessionId` empties out while the overlay is showing, `openMenu` becomes `null` and the `LaunchedEffect(openControl, openMenu == null)` clears `openControl` on the next frame, closing the overlay without an explicit "is my menu still valid" check anywhere else.

`onSelect` dispatches straight through the existing `onModelSelected: (String) -> Unit` / `onEffortSelected: (String) -> Unit` parameters — unchanged since [#807](../codebase/807.md) — plus, since [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650), `onPermissionModeSelected: (String) -> Unit` — then closes the overlay; `onDismiss` only closes it. All three still round-trip through `ConversationRepository.setSessionSettings`, addressed to `SessionSettings.sessionId`, exactly as the sheet's own rows do; the permission handler additionally re-validates the value and picks the `yolo` vs. `permission_mode` field — see [§ Sourcing — Permission mode](#permission-mode-650).

## State + concurrency model

At the composable layer: no new coroutines, flows or state. `openControl`, `footerAnchors` and `layerOrigin` are all screen-local Compose `remember` state, written from layout callbacks (`onGloballyPositioned`) and read only by the currently-open overlay. Selection goes through the existing handlers; the ViewModel owns pending, confirmation and revert exactly as it did for the retired row and the sheet.

At the ViewModel layer, Permission (#650) is not read-only like Model/Effort's pending-then-confirm shape: it owns one `Job` at a time (`ThreadViewModel.permissionWrite`, wrapping the send-then-settle coroutine and its target session id), started lazily so the field is set before the body runs, and torn down either by its own completion or by the context-cancellation rule in [§ Sourcing — Permission mode](#permission-mode-650). All of it runs on `viewModelScope` (Main); the only state carried across a suspension point is the job's own `permissionWrite` / `pendingPermission`.

## Error handling

Model and Effort: no new failure modes. A refused write surfaces as it always has: the ViewModel clears the relevant `pending*` flow and `sessionSettingsErrors` drives the existing snackbar; the footer re-renders the saved label with no footer-side branch for the failure. A control with nothing to offer is disabled and does not open an empty overlay — see `footerControlEnabled` above.

Permission (#650): a refusal or send failure clears `pendingPermission`, drives the same `sessionSettingsErrors` snackbar, and issues one re-read — see [§ Sourcing — Permission mode](#permission-mode-650). A settle that never confirms within 15 s expires silently; nothing is shown beyond the label reverting to whatever the last real reading said, since the daemon may legitimately ack a write without the mode actually changing.

**Known interaction, not blocking (verifier NIT on #650):** `sessionSettings.onEach` clears `pendingModel` / `pendingEffort` on **every** delivered reading, a rule that predates #650. A permission settle can produce up to ~31 extra readings in 15 s, so a model or effort tap that lands mid-settle can have its optimistic pending label cleared by one of those settle-driven readings before its own ack-triggered re-read arrives — a one-round-trip flicker back to the previous value. Not observed in practice and not fixed as of #650; flagged here because it is the one way a permission-mode write can touch Model/Effort's own rendering.

## Testing

- **Unit** (`app/src/test/.../thread/FooterMenuTest.kt`) exercises `footerMenu` against constructed `ThreadRunConfig`s: no menu / an empty published menu / a selected row with no effort levels / no matching row all resolve to `null`; a real menu resolves to options in daemon order with verbatim `value`s; `notListed` is asserted to be `droppedModels + hiddenChoices` even in cases where `choices.size` would suggest a different number, guarding the "never recomputed from the row count" rule. Since #650, it also covers Permission: hidden (`null` menu/label) on `permissionMode == ""`; all six `PermissionModeOption` labels; `Auto` offered only when the selected row's `supportsAutoMode` is true; an unrecognised value produces an inert label with no option selected and nothing listed; `footerControlEnabled` gated by `pendingPermission` independently of `pendingModel` / `pendingEffort`.
- **Unit** (`app/src/test/.../thread/ThreadViewModelPermissionTest.kt`, new in #650): the label tracks only the confirmed reading and is hidden on `""` even with a non-empty session id; Bypass approvals sends `yolo = true` with no `permissionMode`, every other choice sends `permissionMode` with no `yolo`; nothing is sent for the confirmed mode, an empty session id, an unrecognised value or unsupported `auto`; the label is unchanged while pending and after an ack until a reading reports the new mode; the settle loop re-reads immediately then every 500 ms, stops on the requested mode, survives an early old-mode reading, expires at 15 s, and never opens a second `observeSessionSettings` subscription (a held read blocks the next refresh); a refusal and an `IllegalStateException` each clear the pending mark, send one error signal and one re-read; a `null` reading and a reading for another session both cancel the outstanding write and hide the label; a late ack from a cancelled write starts no settle; and `currentSessionId` differing from the reading's session id hides the permission label while `ThreadRunConfig.modelLabel` / `effortLabel` stay put.
- **Compose** (`app/src/androidTest/.../thread/ThreadComposerFooterTest.kt`, replacing the deleted `ThreadStatusRowTest`), hosted on `ThreadScreen` with mutable state: buttons show the current labels and the trailing icon opens the Status sheet in one tap; tapping the model button lists only the published model labels and choosing one calls `onModelSelected` exactly once and closes the overlay; the effort overlay lists only the selected row's levels, and a row with no levels leaves the effort button disabled with nothing opening on tap; tapping the input field while an overlay is open dismisses it, selects nothing, and leaves the field unfocused; the pending state renders its `stateDescription` and clears back to the saved label once a confirming reading lands; every option row is asserted `isDisplayed()` (pinning the `IntrinsicSize.Max` width fix — see [Options overlay](options-overlay.md#the-column-intrinsicsizemax-before-verticalscroll)); a non-zero `droppedModels` renders the not-listed caption; and switching `conversationId` while an overlay is open closes it. Since #650: the permission button opens the six-mode menu (`Auto approval` hidden without `supportsAutoMode`), and is absent from the row entirely when `permissionMode` is `""`.
- **Compose** (`app/src/androidTest/.../components/StatusSheetTest.kt`): `has_no_yolo_switch` asserts the sheet renders no YOLO row or switch at all, now that the permission control lives only in this footer — see [Status sheet](status-sheet.md).
- No rung-3 real-daemon scenario landed in #650; the live Plan → Bypass approvals → Manual approval transition (and its tool approval) is [#687](https://github.com/pyrycode/pyrycode-mobile/issues/687)'s, not this file's. The Model/Effort live scenario is covered by #679.

## Previews

Two `@Preview`s in `ThreadComposerFooter.kt` — `ThreadComposerFooterDarkPreview` (all three buttons resolved, dark theme) and `ThreadComposerFooterLightPendingPreview` (the effort button pending and, since #650, the permission button pending too, light theme) — both `widthDp = 372`. `previewRunConfig` carries `permissionMode = "plan"` so the permission button renders in both previews.

## Edge cases / limitations

- **The design has no Status-sheet affordance in the footer.** A trailing `Tune` icon carries the existing `cd_thread_status_expand` description regardless. Since #650 the sheet hosts Model, Effort and the Context-window section only — the permission control lives exclusively here now, and the retired YOLO switch has no replacement in the sheet. The design's `Actions` (#655), `Cxt:` (#591) and attachment segments remain out of scope for this file.
- **`effortLevels` has no count cap**, unlike the model menu's `MAX_RENDERED_MODEL_CHOICES = 32` / `hiddenChoices`. [Options overlay](options-overlay.md) scrolls, so an unbounded effort menu degrades to a tall scrolling list rather than a layout break, but it composes every row. Flagged for triage on the #808 PR as an out-of-scope hostile-daemon-frame finding; a cap would belong beside `MAX_RENDERED_MODEL_CHOICES` in `ThreadViewModel.kt`.
- **No device test exercises the software keyboard actually showing.** The overlay's placement depends on the anchoring button's live bounds inside the `imePadding()` column reflecting the IME lift; this is exercised only through the anchor-tracking mechanism's own correctness, not an emulator run with the keyboard open. Per AC#5, #679 covers live behaviour.

## Related

- [Options overlay](options-overlay.md) — the popup this footer opens; owns placement, the scrim, and the row rendering, for all three controls including Permission (#650).
- [Status sheet](status-sheet.md) — the retained expanded surface, reachable from the trailing icon, hosting Model, Effort and the Context-window section. Reads the same `ThreadRunConfig`. Its YOLO toggle was retired outright by [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650), not relocated.
- [Thread screen — the list, chip, empty state and status row](thread-screen-how-it-works-list-and-status-row.md#status-row-wiring-post-145) — the historical `bottomBar` wiring narrative through [#145](../codebase/145.md)–[#807](../codebase/807.md), before this ticket's replacement.
- [Thread input bar](thread-input-bar.md) — the composer this footer stacks below, inside the same `bottomBar` column.
- Tickets: [#808](../codebase/808.md) (Model + Effort buttons, this component's shape), [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) (Permission button, the settle rule, session-reset staleness, YOLO retirement). Specs: `docs/specs/architecture/808-composer-footer-model-effort-buttons.md`, `docs/specs/architecture/650-composer-permission-mode.md`.
- Live coverage: [#679](https://github.com/pyrycode/pyrycode-mobile/issues/679) (Model/Effort), [#687](https://github.com/pyrycode/pyrycode-mobile/issues/687) (the Plan → Bypass approvals → Manual approval permission transition and its tool approval).
- Figma: `Input footer` [`110:3494`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=110-3494), overlay [`533:1958`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1958).
