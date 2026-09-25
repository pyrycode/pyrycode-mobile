# #1021 — Mute notifications checkbox in Edit channel

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/components/EditChannelModal.kt` → `EditChannelModal`, `ArchiveChannelAction` — the modal the row goes into; its buffers are keyed on `conversationId`.
- `app/src/main/java/de/pyryco/mobile/ui/components/ChannelFormFields.kt` → `ChannelFormFields` — the form above the new row; unchanged.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModalShell` — the content column is `spacedBy(12.dp)`, which gives the frame's 12dp gaps for free.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadPermissionModal.kt` → `AlwaysAllowOffer` — the app's existing whole-row checkbox (`toggleable` with `Role.Checkbox`, `Checkbox(onCheckedChange = null)`, 48dp floor), mirrored here.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `ChannelEditorState`, `openChannelEditor`, `submitChannelEdit`, `dismissChannelEditor` — the open/submit/retry chain the mute write joins.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ChannelListEvent.ChannelEditSubmitted` (its redacting `toString`), `ChannelEditorModal` binding.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `ChannelListEvent` dispatch `when`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `setMuted` (#1000) — throws on refusal; the confirmed value comes back through `observeConversations`.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` → `Conversation.muted` — the host's stored flag, `false` from an older daemon.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` → `Repo`, `seedCollidingChannelEditors`, `channelSubmitFailuresStayOpenAndARetryNeverRepeatsAConfirmedRename` — the fake and the retry test the new tests sit beside.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` → `openChannel`, the three `editChannelModal_*` tests.

Overlap: `origin/feature/878` also edits `ChannelListScreen.kt`, `strings.xml` and `ChannelListScreenTest.kt` (tree attention dot) — different blocks; edits here stay additive.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=500-2120 (desktop Edit channel, the checkbox's position); mobile shell https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369.

A "Checkbox with label" row in the modal's content column, 12dp under the system prompt well and 12dp above the Archive channel action (which keeps its own 8dp top padding, as the frame draws it): a 20dp box stroked and filled in `Schemes/tertiary`, then 12dp gap, then "Mute notifications" in label-medium emphasized (SemiBold) `on-background`. On the phone it is M3 `Checkbox` with `colorScheme.tertiary` for checked and unchecked, `typography.labelMedium` + `FontWeight.SemiBold`, content colour from the shell; the whole row is the toggle target at the 48dp touch floor.

## Context

The host stores a per-conversation mute flag (#999 carries it in `Conversation.muted`, #1000 added `ConversationRepository.setMuted`). This ticket lets the operator read and flip it from Edit channel. The phone's own alert suppression is a separate ticket (`AttentionNotifier` muted gate). No ADR warranted.

## Design

**`EditChannelModal`** gains `initialMuted: Boolean`, and `onSubmit` becomes `(name: String, systemPrompt: String?, muted: Boolean) -> Unit`. A `muted` buffer is `remember(conversationId) { mutableStateOf(initialMuted) }` — same keying as the name, so a failure or reconnect leaves it where the operator put it. A private `MuteNotificationsRow(checked, onCheckedChange)` is drawn between `ChannelFormFields` and `ArchiveChannelAction`. Its enabled state follows nothing: like the text fields, it stays editable; OK is what gates the write. Previews pass `initialMuted = true` for the main preview.

**`ChannelListEvent.ChannelEditSubmitted`** gains `val muted: Boolean` (required). `toString` keeps the prompt redaction and appends `muted=$muted`.

**`ChannelEditorModal`** binding passes `initialMuted = editor.savedMuted` and forwards the third value into the event. **`MainActivity`** forwards `event.muted`.

**`ChannelEditorState`** gains `val savedMuted: Boolean = false`, appended last (positional test constructors keep compiling). It is the channel's `muted` as its own host's snapshot held it at open, then the value the daemon confirmed — the `savedName` pattern.

**`openChannelEditor`** sets `savedMuted = channel.muted` from the same host-snapshot lookup that yields the name.

**`submitChannelEdit(name, systemPrompt, muted: Boolean? = null)`**: `muteTo = muted?.takeIf { it != state.savedMuted }`. `null` writes no mute (a caller that reported no value never changes the flag); the default avoids a 16-call-site test cascade and is the safe direction. The chain order becomes **rename → mute → prompt**: each write before the last records its confirmation in the editor state (`savedName`, now `savedMuted`), and the prompt write — the only one with no recorded confirmation — stays last, so a retry after any failure sends only the writes the host has not confirmed. A mute failure: `compareAndSet(current, current.copy(saving = false, failed = true))`, log `event=channel_mute_write_failed`, stop. On success `current = current.copy(savedMuted = muteTo)` via `compareAndSet`. The closing log gains `muted=<bool: a mute write was sent>`.

**`dismissChannelEditor`** is unchanged: Cancel, Close and Back already clear the editor without a write.

Nothing is patched locally: the daemon's `conversation_updated` echo updates the row.

## State + concurrency model

Unchanged shape: the single `viewModelScope.launch` chain in `submitChannelEdit`, every terminal transition a `compareAndSet` against the state that chain published, so a dismissal or a newer editor is never overwritten. The mute buffer is UI-local `remember` (not saveable — consistent with the other buffers; a Boolean carries no secret but keeping one policy is simpler).

## Error handling

`setMuted` throws (`IllegalArgumentException`, `RelayErrorException`, or anything else): caught as `Exception`, `CancellationException` rethrown, the modal stays open with the existing static `edit_channel_save_failed` via `failed = true`. No daemon message reaches the UI or the log.

## Testing strategy

Unit (`HostChannelListViewModelTest`), fake `Repo` gains `setMuted` recording `mutes`, applying the flag to its own rows, failing `muteFailures` times, and appending `"mute"` to `channelCalls`:
- Opening reads each host's own flag (A's `same` muted, B's not) into `savedMuted`; OK at the opening value sends no `setMuted`; a flipped value sends exactly one `setMuted(same, new)` to the editor's own host and closes; dismiss sends nothing.
- Rename + mute + prompt, mute fails → open, `failed`, `savedName` confirmed, no prompt write; retry, prompt fails → `savedMuted` confirmed; retry → only the prompt write goes out. Totals: one rename, one mute, one prompt. Log carries `channel_mute_write_failed` and no names, prompt text or ids.

Compose screen test (`ChannelListScreenTest`, Robolectric):
- The row opens checked for `savedMuted = true` (unchecked otherwise), has the checkbox role, toggles on tap, and OK reports the toggled value in `ChannelEditSubmitted`; a `failed` state change keeps the toggled value; the event's `toString` still redacts the prompt.
- Existing `ChannelEditSubmitted(...)` expectations gain `muted = false`.

No rung-3 scenario: this is a settings write whose effect (a `conversation_updated` echo) is already covered at the data layer by #1000; the operator-facing notification behaviour lands with the `AttentionNotifier` gate ticket. Flagged for the verifier's call.

## Open questions

- None blocking. If the verifier wants a real-claude scenario for the round trip, it is a follow-up in the #481 shape.

## Documentation handoff

Pending for the documentation stage: fold the mute checkbox and the rename → mute → prompt write order into `docs/knowledge/features/channel-list-viewmodel.md` (Edit channel section) and `docs/knowledge/features/channel-list-screen.md` (Edit channel modal).

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the only inbound value is `Conversation.muted`, a `Boolean` already decoded by `MobileWireCodec` (#999); it reaches Compose only as a checkbox state, never as text. The target is resolved from the editor's own `serverId` + `conversationId` via the host snapshot in `openChannelEditor`, never from row text, so a write cannot land on another host's same-id conversation (tested with the colliding-id fixture).
- [Tokens] No findings — no credentials touched.
- [File / storage] No findings — no storage; the buffer is in-memory `remember`.
- [Inter-process] No findings — no intents, deep links, push or providers touched.
- [Crypto] No findings — the write rides the existing Noise session through `setMuted`.
- [Network & I/O] No findings — one `set_conversation_muted` per changed OK; an unchanged value sends nothing, so repeated OK presses cannot spam the host; `saving` blocks re-entry while a chain is in flight.
- [Logs] SHOULD FIX (addressed in design) — the new failure log and the widened closing log carry only static event names and booleans; `ChannelEditSubmitted.toString` keeps the prompt redacted. The verifier checks the log-content test.
- [Concurrency] No findings — the mute step follows the existing `compareAndSet` discipline; a dismissal mid-chain does not reopen the modal, and the write still completes (operator pressed OK), matching rename.
- [Threat model] OUT OF SCOPE — the phone honouring the flag for its own alerts is the `AttentionNotifier` muted-gate ticket split from #1001.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24

## Revisions

### 2026-09-24 — rung-3 scenario (verifier finding on PR #1024)

The Testing strategy left the real-claude scenario to the verifier, who ruled it required: the reopened modal's checked state depends on the daemon storing the flag and echoing it in `conversation_updated`, which only a real daemon proves. Added `InteractiveStreamE2ETest.interactiveTurn_muteChannel_roundTripsThroughTheHost`: a channel set up on the host (discussion promoted in place), Edit channel opened from the row's pen, Mute checked and saved, the host's own row read as muted, the reopened modal shown checked, then unchecked and saved with the clear read back the same way; the channel is deleted in `finally`. Zero real-claude turns. It joins the LIVE list in `scripts/e2e-emulator.sh`, and `LIVE_MINIMUM` in `scripts/android-test-gate.py` rises by one. The ticket gains `needs-real-claude`. No rung-4 twin: there is no turn for a scripted claude to hold. Production code unchanged.
