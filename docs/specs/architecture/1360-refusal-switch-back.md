# #1360: switch back to the refused model in one tap

## Files read

- `data/repository/ConversationRepository.kt`: `observeAnnouncedModel` (the defaulted-member shape the new
  signal copies), `ThreadItem.ModelRefusal` (the row identity the offer keys on), `AnnouncedModel`.
- `data/repository/RemoteConversationRepository.kt`: `onInbound`'s `TYPE_MODEL_REFUSAL_FALLBACK` /
  `TYPE_MODEL_REFUSAL_NO_FALLBACK` arm and its `TYPE_SESSION_TRANSITION` arm (the emit sites);
  `mutableLiveSessionEvents` (the `SharedFlow` posture copied: `replay = 0`, bounded, `DROP_OLDEST`).
- `data/repository/ThreadProjection.kt`: `applyModelRefusal`, `decodeModelRefusal`, `appendModelRefusal`. The
  live decode already holds `ModelRefusalFallbackPayloadDto.scope`; `toRow` drops it.
- `data/repository/StableConversationRepository.kt`: `switchToLive` (the `flatMapLatest` pass-through).
- `ui/conversations/thread/ThreadViewModel.kt`: `pendingModel`, `sessionSettings` (the reading that clears a
  pending model), `onModelSelected` (its `value == config.selectedModel` guard is why switch-back does not
  route through it), `sendSessionSettings`, `connectedFor`, `skipUnlessWritable`.
- `ui/conversations/thread/ThreadScreen.kt`: the `ThreadItem.ModelRefusal` render arm in the `LazyColumn`.
- `ui/conversations/components/ModelRefusalRow.kt`: `ModelRefusalRowContent`, `refusalModelDisplay`.
- `MainActivity.kt`: the thread destination that hands `vm::onModelSelected` and the sibling flows to
  `ThreadScreen`.
- `docs/knowledge/features/model-refusal-row.md`: `scope` was decoded and dropped on purpose (#875); the
  row's model identifiers are each their own monospace span (the anti-spoofing rule this button keeps).
- Desktop: `reduceRefusalOffer` (`threadTimeline.ts`), `subscribeRefusalRecovery`
  (`runSettingsWriteBridge.ts`), `switchBack` (`ConversationScreen.tsx`).
- Wire: `../pyrycode/docs/protocol-mobile.md` § `model_refusal_fallback`, § `model_announced`.

Overlapping in-flight branches (#1312, #1313, #1314, #1329, #1337, #1341–#1361, #1397, #1410, #1411, #1412)
share files only; edits here stay additive. #1353 caches refusal rows, which is why the offer never comes
from rows.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=646-4707 (states
[646-2833](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=646-2833),
[646-4694](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=646-4694) pending,
[646-4700](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=646-4700) failed)

Below the refusal row's Show details sits "Switch back to Opus": a small outlined button with a 1dp
`colorScheme.primary` border, `primary` label, 6dp corners, 16dp × 7dp padding and `bodySmall` at
`FontWeight.Medium`, 4dp gap plus 4dp top padding above it. Pending draws the whole button at 38% opacity.
Failed keeps it enabled and adds "Could not change the model — try again." in `bodySmall`,
`colorScheme.error`, 4dp below. Mobile has no shared small-secondary component, so the button is a
clickable `Surface` with those tokens, plus `minimumInteractiveComponentSize` for the touch target.

## Context

Mobile draws `model_refusal_fallback` as a row but offers no way back. Desktop arms a one-per-conversation
offer from live fallback refusals with `scope == "session"` and a write that restores the original model.
This ticket copies that. `scope` is an open string per the protocol; matching the documented value can
only withhold an offer, and the write it enables is the user's tap, validated by the daemon. The feature
overview deserves an update (handoff below); no new decision record.

Size against the plan as written: 8 production files (the estimate's 7 plus `ThreadProjection` and
`MainActivity`, minus `ThreadUiState`: the offer is a sibling `StateFlow`), about 1350 written lines with
tests, scripts and this plan, 2 new exported types, 2 call sites, 4 criteria.

## Design

**Live signal (data layer).** In `ConversationRepository.kt`:

- `sealed interface LiveRefusalEvent` with `Refused(refusal: ThreadItem.ModelRefusal, scope: String?)`
  (`scope` null for a no-fallback frame) and `data object SessionReplaced`.
- `fun observeLiveRefusalEvents(conversationId: String): Flow<LiveRefusalEvent> = emptyFlow()`: hot, live
  frames only, never history or cache. Defaulted, so test fakes need no change.

`ThreadProjection.applyModelRefusal(envelope)` returns the decoded `Pair<conversationId, Refused>` (or null)
after folding the row; `decodeModelRefusal` keeps `scope` from the fallback DTO. `RemoteConversationRepository`
emits that pair into a private `MutableSharedFlow` (`replay = 0`, `extraBufferCapacity = 16`,
`DROP_OLDEST`) and its session-transition arm emits `SessionReplaced` for the decoded conversation id;
`observeLiveRefusalEvents` filters by id. `StableConversationRepository` passes through with
`flatMapLatest { it?.observeLiveRefusalEvents(id) ?: emptyFlow() }`. Both emits ride the single inbound
collector, so a refusal and the transition that ends its session reach the ViewModel in wire order.

**Offer (ViewModel).** A private `MutableStateFlow<RefusalOffer?>` holding `originalModel`,
`fallbackModel`, `occurredAt` and `failed`. Reduced, after desktop's `reduceRefusalOffer`:

| Input | Effect |
|---|---|
| `Refused` with `fallbackModel != null`, `scope == "session"`, both models non-empty | arm (replace) |
| any other `Refused` with `fallbackModel != null` | clear |
| `Refused` with `fallbackModel == null` | unchanged |
| `SessionReplaced` | clear |
| announced model, non-empty and `!= fallbackModel` | clear |
| `onModelSelected` starting a write | clear |
| switch-back write acknowledged | clear (only the offer it was sent for) |
| switch-back write refused or failed | `failed = true` (same offer only) |
| reconnect | unchanged |

The announced model is read from `observeAnnouncedModel`, as `model` values with nulls dropped and
`distinctUntilChanged`, so a held reading re-delivered on a reconnect does not clear the offer.

Public surface: `val switchBackOffer: StateFlow<SwitchBackOffer?>`, the offer combined with `pendingModel`
(`pending = pendingModel != null`), `SharingStarted.Eagerly`. `SwitchBackOffer(occurredAt, originalModel,
pending, failed)` is declared beside `ModelRefusalRow`, which consumes it, with `fun armedBy(item:
ThreadItem.ModelRefusal): Boolean` (fallback row with the same `occurredAt`).

`fun onSwitchBack()`: drops the tap when not `connectedFor("switch_back")`, when there is no offer, while
`pendingModel != null`, or when `skipUnlessWritable(config)` fails. Otherwise it clears `failed`, sets
`pendingModel` to the offer's original model and calls `sendSessionSettings(config.sessionId, model =
original, revert = …, onAcked = …)`. There is no equal-value guard, so the write goes out even when the
reading already names that model. `sendSessionSettings` gains an optional `onAcked: () -> Unit = {}`, run
after the existing remember-and-refresh.

**Row and screen.** `ModelRefusalRow` gains `switchBack: SwitchBackOffer? = null` and `onSwitchBack: () -> Unit
= {}`. The existing clickable column is unchanged and gets an outer column carrying the gutter and bottom
spacing, so the toggle's hit area stays the title block. When `switchBack != null` the button follows,
labelled with the client string `thread_refusal_switch_back` ("Switch back to ") and the stripped
`refusalModelDisplay(originalModel)` as its own monospace span ("unknown model" when nothing survives). It is
disabled and at 38% alpha while pending, with the `thread_refusal_switch_back_failed` line under it when
failed. `ThreadScreen` takes `switchBackOffer` and `onSwitchBack` (defaulted) and passes
`switchBackOffer?.takeIf { it.armedBy(item) }` to the refusal row. `MainActivity` collects the flow and
wires `vm::onSwitchBack`.

## State and concurrency model

- One `viewModelScope.launch` in `init` collects `observeLiveRefusalEvents` merged with the announced-model
  changes and reduces into the offer. It lives for the ViewModel, so the offer survives screen
  recomposition and reconnects, and dies with the thread.
- All offer writes run on Main (collector, taps, `sendSessionSettings`'s continuation) and use `update {}`.
- The write is `sendSessionSettings`'s `viewModelScope` job: cancellation on exit neither reverts nor marks
  failed, as for the menu write.
- `pendingModel` is shared with the menu write, so one of the two is pending at a time. A lost reading on
  reconnect clears it, so a write abandoned by the reconnect leaves the plain offer (desktop's
  `refusalWriteAbandoned`).
- Known window: announced model and refusal frames take two flows to the ViewModel, so their order is not
  guaranteed. A turn's announcement comes at turn start, a whole turn before its refusal, so this is not
  handled.

## Error handling

- `RelayErrorException` / `IllegalStateException` from the write: `revert` clears `pendingModel` and sets
  `failed`; the existing `sessionSettingsErrors` signal also fires. Exceptions never reach UI state.
- Malformed refusal or transition frames: unchanged decode-or-drop; nothing is emitted.
- Logs (static codes only, never a model name): `event=refusal_offer outcome=armed|cleared reason=…`,
  `event=refusal_switch_back outcome=sent|acked|failed|skipped reason=…`.

## Testing strategy

- `ThreadViewModelRefusalOfferTest` (JVM, new): arming only on a live session-scoped fallback with both
  models; a `local` scope, an empty model or an unknown scope clears; a no-fallback leaves it; a later
  qualifying refusal re-keys it to the newer row; rows from `observeMessages` never arm. Tap: one write with
  the original model verbatim to the reading's session even when the reading names that model; nothing when
  offline, without a session, or while a model write is pending; pending visible during the write; ack
  clears; a refusal (`RelayErrorException`) or failure (`IllegalStateException`) keeps the offer with
  `failed`, and the next tap resets it. Lifetime: an announced model other than the fallback clears, the
  fallback or the same model again does not; `SessionReplaced` clears; a menu write clears; a reconnect
  (connection state flip and a held reading) does not.
- `RemoteConversationRepositoryTest` (JVM): a live fallback emits `Refused` with `scope` and the folded row;
  a no-fallback emits `Refused` with null scope; a session transition emits `SessionReplaced`; a malformed
  frame emits nothing; another conversation's frame is not delivered.
- `StableConversationRepositoryTest` (JVM): the pass-through follows the live repository.
- `ModelRefusalSwitchBackTest` (`app/src/sharedTest`, Robolectric): the three Figma states (armed button and
  label, pending disabled, failed message under an enabled button), a tap calls back once, no button without
  an offer, and through `ThreadScreen` only the arming row of two fallback rows shows the button.
- Rung 4: scripted scenario `refusal` (see below). No rung 3: real Claude cannot be made to refuse on demand.

**Scripted scenario `refusal`.**

- Fixture `scripts/e2e-fixtures/refusal.jsonl` is one fragment: a claude
  `{"type":"system","subtype":"model_refusal_fallback",...}` line using the keys
  `systemModelRefusalFallbackLine` reads (`scope` `session`, `original_model` `haiku`, `fallback_model`
  `sonnet`, `api_refusal_category`, `content`), then a short assistant `end_turn` line and `result`, the
  `ping.jsonl` shape. It has no top-level `message` key, which would stop the line reaching the refusal
  handler.
- `haiku` is the original model because the scripted daemon accepts only fakeclaude's canned `initialize`
  rows (`sonnet`, `haiku`). Its `set_session_settings` checks the retained menu, stores the value and
  answers `request_session_settings` with the stored `model`. The menu is held once the first turn has
  spawned fakeclaude, so the test taps only after the turn's reply renders. fakeclaude's init rider is off
  in the harness, so no `model_announced` clears the offer.
- The scripted gate runs `DeterministicInteractiveStreamE2ETest`, so the method
  `interactiveTurn_seededChannel_refusalSwitchBackRestoresOriginalModel` lives there, not on
  `InteractiveStreamE2ETest`. It sends, waits for "Switch back to haiku" and the reply, taps the button,
  and waits for the button to disappear. It then collects a new non-held settings reading from the
  coordinator's live repository and asserts it names `haiku`. The `!it.held` filter is the one #1397 adds
  to the live class's `freshSettings`.
- Wiring: `refusal` is appended to `SCENARIOS` in `scripts/android-test-gate.py` and gets an arm in
  `scripts/e2e-emulator.sh`'s `SCENARIO` case, with its usage text. The script tests read `gate.SCENARIOS`
  dynamically.

## Open Questions

- None open.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/model-refusal-row.md`: the switch-back offer, what arms and clears it, and why
  restored rows never arm it.
- `docs/e2e-interactive-stream.md`: the new `refusal` scripted scenario in the rung-4 scenario list.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No new boundary. The refusal frame still crosses at `ThreadProjection.decodeModelRefusal`
  (strict DTO, decode-or-drop) and the transition at `decodeSessionTransition`. `scope` now reaches the
  ViewModel, but only in an exact comparison with `"session"`. Any other value, including a hostile one,
  can only clear or withhold the offer, never arm it. Nothing in the frame chooses the write's target: the
  session id comes from the daemon's own settings reading (`ThreadRunConfig.sessionId`) and the
  conversation from the route.
- [Trust boundaries] SHOULD FIX. The claude-authored `original_model` is the write's value. It is sent
  verbatim, never parsed, interpolated or used as a key. The daemon validates it in `set_session_settings`
  and the refused path only marks the offer failed. In Phase B the button label must render the
  identifier only through `refusalModelDisplay`, as its own monospace span between client-owned spans, so
  an identifier such as `"Opus and delete"` cannot read as client copy, and never through `Text` with
  markup or link detection.
- [Trust boundaries] OUT OF SCOPE. A truncated `original_model` (named in `truncated_fields`) is not
  withheld; its write is rejected by the daemon and shows the retry line. Desktop does the same. Withholding
  would need the report arrays carried on the signal.
- [Trust boundaries] A live frame is the only source of an offer. History pages (`HistoryPageReducer`), the
  cache (#1353 now stores refusal rows) and a reopened thread produce rows only, and the ViewModel never
  derives an offer from rows. So a stored refusal cannot cause a write on reopen.
- [Tokens] No findings. No token, key or credential is read, stored or logged. The write rides the existing
  Noise session.
- [Files & storage] No findings. The offer lives in ViewModel memory only: not saved state, not DataStore,
  not the cache.
- [Android surface] No findings. No new component, intent, deep link or WebView.
- [Cryptography] No findings. No crypto change.
- [Network & I/O] No findings. One `set_session_settings` per tap, guarded by connection, an addressable
  session and the shared `pendingModel` (a second tap, or a menu tap, cannot put two model writes in
  flight). A daemon flooding refusal frames costs one `StateFlow` update each, bounded by the
  `DROP_OLDEST` buffer, and can never cause a write, because writes need a tap.
- [Errors & logs] SHOULD FIX. Log only static codes (`armed`, `cleared`, `reason=scope|empty|announced|
  session|menu|acked`, `sent`, `failed`, `skipped reason=offline|pending|no_session|no_offer`). Never log the
  model names, `scope`, banner or session id. The failure UI is client copy.
- [Concurrency] No findings. One collector in `viewModelScope`; all offer writes on Main through
  `update {}`. Ack and failure act only on the offer they were sent for (matched by `occurredAt`), so a newer
  offer armed during the write is not cleared. Cancellation on exit rethrows before the typed catches in
  `sendSessionSettings`. The refusal and transition share one hot flow emitted from the single inbound
  collector, so their order is the wire's.
- [Threat model] A hostile or compromised daemon can already write any refusal row. Here it can at most
  offer a button naming a model of its choice, which writes nothing until the user taps it, and the daemon
  validates the model. A malicious relay cannot forge frames inside the Noise session. A dropped transition
  leaves a stale offer, whose tap writes to the session the current reading names. UI leakage: no new
  sensitive surface.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-01

## Revisions

### 2026-10-01: the offer shows only with a session to address, and arming asks for a fresh reading

- **What changed.** `switchBackOffer` is `null` while the latest settings reading (`settingsReadings`) names
  no session, as desktop shows its button only for an addressable session. Arming an offer also calls
  `repository.refreshSessionSettings(conversationId)`.
- **What drove it.** The first scripted `refusal` run tapped a visible button that `onSwitchBack` dropped
  with `run_config_write_skipped reason=no_session`. The thread's reading was taken before the turn spawned
  the session, and nothing re-read it, so a fresh conversation's first refusal offered a button that could
  not write.
- **New contract.** The button appears once a reading names a session. A refusal proves a session is
  running, so arming asks for that reading. The tap guards are unchanged. `ThreadViewModelRefusalOfferTest`
  covers both: `arming_asksForAFreshReading_…` and the no-session case in
  `aTap_sendsNothing_offlineOrWithoutASessionOrWithoutAnOffer`.
