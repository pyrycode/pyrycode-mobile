# #842 — Re-pair a host with a rejected pairing from its tree row

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeViewModel.kt` → `PairCodeViewModel`, `PairCodeState`, `PairCodeEvent`, `persist` — the code-pair state machine that gains a target mode.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeScreen.kt` → `PairCodeScreen`, `PairCodeField` — renders the target host; today it keys the field error on the literal "Invalid pairing code".
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairingConfirmation.kt` → `confirmPairingAndConnect` — save → connect ordering, unchanged and reused.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `PyryNavHost` (the `Routes.PAIR_CODE` destination, the channel list's event `when`), `Routes` (`SETTINGS`, `settings`, `settingsArguments`) — the optional-query-argument pattern the pair-code route copies.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ChannelListEvent.TreeHostReconnectTapped`, the tree's `TreeHostRow` call — where the plug control's event is built with the host's `connectionStatus` in hand.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `TreeHostRow`, `RelayLinkStatus.isDisconnected` — `PairingRejected` already draws the plug; no change here.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `PairCodeViewModel` binding, `ThreadDestinationFactory.settings` (reads `serverId` off `SavedStateHandle`), `hostLabel` (display-name-or-serverId fallback), the `ObservablePairedServerStore` binding comment (same-id save keeps name and drafts).
- `app/src/test/java/de/pyryco/mobile/ui/onboarding/PairCodeViewModelTest.kt` → `Fixture`, `Store` — fake store whose `save` keeps the existing display name, like the real one.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/PairCodeScreenTest.kt` → `cancelToolbarAndAndroidBackReturnToCallerWithoutSaving` navigates to `Routes.PAIR_CODE` and must keep working unchanged.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` → `hostRowReconnectControl_targetsItsOwnHost_andLeavesEveryHostsRowsDrawn` — the sibling the new rejected-state test sits beside.
- `docs/knowledge/features/paste-code-dialog.md`, `pairing-confirm-gate.md`, `paired-server-store.md`, `relay-link-status.md` — pairing flow and `PairingRejected` background.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=133-259

The channel-list tree; the lower Channels section's "Pyrybox" host row is the disconnected treatment — name and glyph in the error tint with a plug icon button before the two connection dots. This ticket changes only what that plug does for a rejected pairing, not how it looks. The code-pair screen keeps its shipped layout; in target mode the "Host name" field shows the target host's name, disabled (not editable, since the stored name is kept), which is the only visual addition.

## Context

A host whose saved pairing was rejected (`RelayLinkStatus.PairingRejected`) shows the plug control, which today retries — a retry cannot succeed. The plug should instead open the code-pair flow scoped to that host, so only that host's credential is replaced. No ADR needed.

## Design

### Tree row → event

- New `ChannelListEvent.TreeHostRePairTapped(serverId: String)` next to `TreeHostReconnectTapped`, carrying the `serverId` only.
- In `ChannelListScreen`'s `TreeHostRow` call, `onReconnectTapped` dispatches `TreeHostRePairTapped` when `host.connectionStatus.relay == RelayLinkStatus.PairingRejected`, else `TreeHostReconnectTapped` as today.
- `PyryNavHost` routes `TreeHostRePairTapped` to `navController.navigate(Routes.pairCode(event.serverId))`.

### Route

- `Routes.PAIR_CODE = "pair_code"` stays as the navigable plain route (scanner's paste entry and the device test use it unchanged).
- New `Routes.PAIR_CODE_ROUTE = "pair_code?serverId={serverId}"` is the destination pattern; `Routes.pairCodeArguments()` declares `serverId` as an optional string defaulting to `""`, as `settingsArguments` does; `Routes.pairCode(serverId: String)` builds `pair_code?serverId=<Uri.encode(serverId)>`. Navigating to plain `pair_code` matches the pattern with the empty default, the same way `settings` does.
- Complete and Cancelled handling in the destination are unchanged (Complete pops the graph onto the channel list; Cancelled pops back to the list).

### ViewModel

- Constructor gains `private val target: String? = null` (last parameter, default null, so existing construction is untouched).
- `PairCodeState` gains `targetName: String? = null` — the label for the named host. `toString` stays redacted.
- `init`: when `target != null`, set `targetName = target` immediately, then `viewModelScope.launch` a `store.loadById(target)` and `update` the state to the non-blank display name if one exists (the `hostLabel` fallback rule). A store failure keeps the `serverId` label.
- `PairCodeEvent.Name` is ignored in target mode.
- `PairCodeEvent.Pair`: after a successful parse, when `target != null && parsed.server.serverId != target` (exact, case-sensitive `==`), `fail(WRONG_HOST_ERROR, "target_mismatch")` — stays in Editing, no confirmation, nothing saved.
- `persist`: in target mode skip `store.setDisplayName` entirely, so the stored name is kept (the store keeps it across a same-id save). Everything else — `confirmPairingAndConnect`, the 30 s connection wait that already ends on `PairingRejected`, failure copy — is unchanged.
- Error strings become `internal const val`s in the VM file: `INVALID_CODE_ERROR` ("Invalid pairing code", unchanged) and `WRONG_HOST_ERROR` ("This code is for a different host"). Both are code-field errors.

### Screen

- `PairCodeField`'s `error: Boolean` becomes `error: String?` (the supporting text). The screen computes `codeError = state.error?.takeIf { it == INVALID_CODE_ERROR || it == WRONG_HOST_ERROR }`, shows it under the code field, and shows any other error in the existing bottom slot; the "Retry" label keys on a non-field error as before.
- Target mode: the Host name field shows `state.targetName`, disabled. Rendered as a single-line `TextField` value only.

### DI

- The `PairCodeViewModel` binding reads `get<SavedStateHandle>().get<String>("serverId")`, maps blank to null, and passes it as `target`.

## State + concurrency model

Unchanged except the one `init` load, launched in `viewModelScope` and cancelled with the VM; it uses `MutableStateFlow.update` so it cannot clobber a concurrent event. The existing `operation` job and its cancellation on Back are unchanged.

## Error handling

- Wrong host → field error before confirmation, nothing saved (`target_mismatch` log code, content-free).
- Save failure, rejected or unavailable host, deadline → existing `fail` paths; phase returns to Editing, never Complete. Other hosts' records are never touched: the only write in target mode is `store.save` of a record whose `serverId` equals the target.
- Target not in the store (unpaired meanwhile) → the screen names the `serverId`; confirming a matching code saves it as a new record, same as the unrouted flow would. Acceptable.

## Testing strategy

Unit (`PairCodeViewModelTest`, fixture gains an optional target):
- Mismatch: target `A` (and case variant `b` vs code `B`) refuses the code with `WRONG_HOST_ERROR`, no confirmation, zero saves and connects.
- Replace-only-this-host: store holds target `B` (old record, name "Pyrybox") and peer `C`; `targetName` resolves to "Pyrybox"; a `Name` event is ignored; confirm replaces `B`'s record, keeps "Pyrybox", leaves `C` equal, never calls `setDisplayName`, connects once, completes on Connected.
- Cancel and failed save in target mode leave the store unchanged and never reach Complete.
- Rejected while connecting in target mode fails immediately, well before the 30 s deadline.

Device (compiled and run focused on the managed device):
- `ChannelListScreenTest`: a `PairingRejected` host's plug emits `TreeHostRePairTapped(serverId)`; the existing Offline/DaemonAbsent test keeps asserting `TreeHostReconnectTapped`.
- `PairCodeScreenTest`: target state renders the host name in a disabled Host name field, and the wrong-host error renders under the code field.

No rung-3 scenario: the flow needs a daemon that rejects a pairing and then a fresh code for the same server id, which the real-claude harness cannot produce; unit tests prove every AC (as the refiner concluded, no `needs-real-claude`).

## Documentation handoff

None named by the ticket. Pending for the documentation stage: the pair-code overview (`docs/knowledge/features/paste-code-dialog.md`) and navigation overview (`docs/knowledge/features/navigation.md`) may want the target mode and the `pair_code?serverId=` route.

## Open questions

- Overlap: `feature/804` edits `MainActivity.kt` (thread destination) and `feature/861` edits `AppModule.kt` (`ThreadDestinationFactory.thread`). Neither touches the regions this plan edits; resolve by a `git merge-tree` trial merge of this branch against both before opening the PR.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the paste-code payload still crosses into trusted state only through `parsePairingPayload` plus the fingerprint confirmation; the new check compares the parsed `serverId` to the route's target with exact `==` before `AwaitingConfirm` is ever built, so a code for another host cannot reach `store.save`. The route argument itself is only compared and used as a store key lookup (`loadById`), never saved.
- [Trust boundaries] No findings — the host display name (daemon-authored) reaches Compose only as a single-line `TextField` value in `PairCodeScreen`; it is never a route argument (navigation carries `serverId` only), a key, or a log field.
- [Tokens] No findings — credential replacement goes through the existing Keystore-backed `PairedServerCollectionStore.save` via `confirmPairingAndConnect`; no new storage. The token is never logged; new log lines carry static codes only (`target_mismatch`). `PairCodeState.toString` stays redacted.
- [Tokens / revocation] No findings — replacement is per-host: the only write is `save` of a record whose `serverId` equals the target, which `RelayConnectionRegistry.reconcile` diffs per host, so other hosts' credentials and connections are untouched.
- [File / storage] No findings — no new files or paths.
- [Android surface] No findings — the new route argument is internal navigation only; no new intent filter or deep link. A crafted `serverId` argument can only scope the flow to a host the user then must supply a matching, fingerprint-confirmed code for.
- [Crypto] No findings — no primitives touched; the `serverId` comparison is an identifier match, not a secret compare, so constant time is not required.
- [Network & I/O] No findings — the connection path, relay URL validation and backoff are the existing ones.
- [Errors/logs] No findings — user-facing copy is generic ("This code is for a different host") and never interpolates the parsed or target id.
- [Concurrency] No findings — the name load uses `update {}` in `viewModelScope`; the save job keeps the existing Back cancellation and the Saving-phase lock. `RelayConnectionRegistry.pairingStatus` matches on the exact saved record, so the old rejected connection's status cannot be read as the new credential's success.
- [Threat model] OUT OF SCOPE — a code identical to the rejected one does not rebuild the connection and surfaces as a failure (never success), accepted by the ticket.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

## Revisions

- 2026-09-23 — Open question resolved: `git merge-tree` of this branch against `origin/feature/804` and `origin/feature/861` both merge cleanly. The design is unchanged. One device test was added beyond the plan, `PairCodeScreenTest.targetedRouteNamesItsHostAndBackReturnsWithoutSaving`, to prove the `SavedStateHandle` → `target` wiring through `PyryNavHost`.
