# #1319 — Disable sending, actions and settings while the host is not connected

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `connectionState`, `sendMessage`, `sendWithAttachments`, `onInterrupt`, `onComposerCommand`, `onModelSelected`, `onEffortSelected`, `onPermissionModeSelected`, `skipUnlessWritable` — the six tap handlers that need the tap-time re-check, and the `WhileSubscribed` flow whose `.value` is stale without a collector.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConnectionStateSource.kt`, `FakeConnectionStateSource` — the cold `observe()` contract; the fake is a `MutableStateFlow` seeded `Connected`, which every existing VM test uses.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → `observe` — production source, a `flatMapLatest` over the selected supervisor; #1318 made its `Connected` mean "handshake answered".
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBar.kt` → `ThreadInputBar`, `buttonEnabled` — Send/Stop share one `IconButton`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt` → `footerControlEnabled`, `ThreadComposerFooter` (Actions `FooterButton` hard-wired `enabled = true`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen` (`connectionState` param, `openMenu`'s `footerControlEnabled` filter, the `StatusSheet` call's `enabled`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StatusSheet.kt` → `StatusSheet` — already greys every setting control on `enabled = false`; no change needed there.
- `app/src/test/.../thread/FooterMenuTest.kt`, `ThreadViewModelTest.kt` (`makeVm`, `connectionState_*`), `ThreadViewModelPermissionTest.kt` — callers of `footerControlEnabled` and the VM test fixtures.
- `app/src/sharedTest/.../thread/ThreadScreenRePairTest.kt` — how a `ThreadScreen` screen test is set up under Robolectric.

In-flight overlap: none of the four production files is touched by another `origin/feature/*` branch.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-4910 (Offline thread) and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1957 (input area)

No new visuals: the Send/Stop button, the footer's Actions button and the Status sheet's rows use the disabled appearance they already have (`primary` at 0.38 alpha for the composer button, `onSurfaceVariant` for a disabled footer label, the sheet's existing disabled radios). The Connecting / Reconnecting / Offline notices from #1283 are unchanged. (The Figma MCP server was not authorised in this run; the ticket states no new visuals, so the fidelity check reduces to "existing disabled states only".)

## Context

Today a tap while the host is down reaches the repository, throws "not connected", and `launchGuardedRepoCall` swallows it: nothing is reported. Desktop gates the composer, the Actions menu and run settings on the owning host being `connected`, and re-checks at the moment of the action. This copies that rule.

## Design

**Live connection state in the VM.** Change `ThreadViewModel.connectionState`'s `started` from `SharingStarted.WhileSubscribed(5_000)` to `SharingStarted.Eagerly` (initial value stays `Connected`). The source is a cheap `StateFlow` projection; collecting it for the VM's lifetime makes `.value` current with no UI collector, so the screen and the tap-time check read one flow. Under `Dispatchers.Main.immediate` (and the tests' `UnconfinedTestDispatcher`) the eager collection subscribes during construction, so the source's current value lands before any handler can run.

**Tap-time re-check.** One private helper, `connectedFor(action: String): Boolean` — returns `connectionState.value == ConnectionState.Connected`; otherwise logs `event=thread_action_skipped action=<static code> reason=not_connected` and returns false. It is the first statement in:

- `sendMessage` (before the attachments-sending guard and `sendWithAttachments`, so `_attachmentsSending` never flips)
- `onInterrupt`
- `onComposerCommand`
- `onModelSelected`, `onEffortSelected` (before `effortRecall.cancel()`, so an offline tap leaves the recall alone), `onPermissionModeSelected`

The draft and pending attachments are untouched by a skipped send.

**UI gating.**

- `ThreadInputBar` gains `enabled: Boolean = true`; `buttonEnabled = enabled && (stopping || (!sending && text.isNotBlank()))`. The field stays editable.
- `footerControlEnabled(control, runConfig, connected: Boolean)` returns false for every control, Actions included, when `!connected`. The parameter is required (no default) so every caller states it.
- `ThreadComposerFooter` gains `connected: Boolean = true` and passes `footerControlEnabled(FooterControl.Actions, runConfig, connected)` as the Actions button's `enabled`.
- `ThreadScreen` derives `val connected = connectionState == ConnectionState.Connected` and passes it to the input bar, the footer, the `openMenu` filter (an open Actions overlay closes on disconnect through the existing `LaunchedEffect`), and the Status sheet as `enabled = state.runConfig.writable && connected`.

## State + concurrency model

No new jobs. The `connectionState` `stateIn` collection now lives for `viewModelScope`'s lifetime and is cancelled in `onCleared`. All handlers run on Main, the same thread the eager collector writes `.value` from.

## Error handling

A skipped tap is silent by design: the control is already greyed and the #1283 notice explains why. Only a content-free debug log line is emitted.

## Testing strategy

- `FooterMenuTest`: `footerControlEnabled` is false for Model, Effort, Permission and Actions when `connected = false` with an otherwise writable config, true for Actions when connected. Existing calls gain `connected = true`.
- `ThreadViewModelTest` (new block, no collector on `connectionState`, `FakeConnectionStateSource` flipped to `Offline` just before the call): `sendMessage` records no repository send and the draft is unchanged; `sendMessage` with pending attachments uploads nothing and keeps them; `onInterrupt` invokes no interrupt lambda; `onComposerCommand` sends nothing; `onModelSelected` / `onEffortSelected` / `onPermissionModeSelected` make no `setSessionSettings` call and the run configuration is unchanged (no pending). These fail on main because `.value` stays at the `Connected` initial value without a collector. One positive control: after flipping back to `Connected`, `sendMessage` sends.
- `ThreadScreenConnectionGateTest` (new, `app/src/sharedTest/`, Robolectric): `ThreadScreen` with a `mutableStateOf` connection state and a typed draft; Send is disabled Offline and Connecting, enabled Connected, without re-setting content; the field still accepts text and the draft value survives; Stop (busy, empty draft) disabled Offline; the footer's Actions disabled Offline; opening the Status sheet Offline shows a model row not enabled, and enabled once Connected.
- No rung-3 scenario: this ticket removes a dead tap, it ships no new operator happy-path flow.

## Open questions

- Is the eager `stateIn` subscribed before a test's first call under every test dispatcher in use? Resolve by running the existing VM suites touched and the new tests.

## Documentation handoff

Pending for the documentation stage:
- `docs/knowledge/features/thread-composer-footer.md` — `footerControlEnabled` now takes `connected`; Actions is no longer always enabled.
- `docs/knowledge/features/thread-input-bar.md` — the new `enabled` input gating Send and Stop.
- `docs/knowledge/features/connection-state.md` (or the thread-screen overview) — `ThreadViewModel.connectionState` is now `Eagerly` started and is the tap-time authority.
