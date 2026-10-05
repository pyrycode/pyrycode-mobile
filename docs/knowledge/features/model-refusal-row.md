# Model refusal row — `ThreadItem.ModelRefusal` / `ModelRefusalRow`

The thread's explanation for a changed model or a missing answer: claude refused a turn on one model and
either retried it on another or did not ([#875](../codebase/875.md), split from #654). Before this ticket
mobile dropped both `model_refusal_fallback` and `model_refusal_no_fallback` on both lanes, so a refusal
went unexplained. Landed in the [`Banner`](banner-notice-row.md) / [`CompactionBoundary`](session-boundary-delimiter.md#compactionboundarydivider-874-1358)
shape — the fourth `ThreadItem` variant carrying a wire-minted `(type, ts)` identity: one ticket for the
type, both decode arms, and the row, because the decoder and the renderer are each other's only consumer.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`).
File: `ModelRefusalRow.kt`. Type + wire DTOs: `data/repository/ConversationRepository.kt`
(`ThreadItem.ModelRefusal`), `data/network/InteractivePayloads.kt` (`ModelRefusalFallbackPayloadDto`,
`ModelRefusalNoFallbackPayloadDto`, their `toRow`). Live decode+fold: `data/repository/ThreadProjection.kt`
(`applyModelRefusal`, `decodeModelRefusal`, `appendModelRefusal`) — since the #912–#916 repository split,
thread writes live here, not in `RemoteConversationRepository`, which only holds the `onInbound` routing
arm and the two `TYPE_MODEL_REFUSAL_*` constants. History decode: `data/repository/HistoryPageReducer.kt`
(`withHistoryEntry`'s two arms, `holdsModelRefusal`). Sibling of [`BannerNoticeRow`](banner-notice-row.md)
(reuses `bannerDisplayText`).

Wire SSOT: pyrycode `docs/protocol-mobile.md` § `model_refusal_fallback`, § `model_refusal_no_fallback`,
§ *Joining a page to the live stream* for the `(type, ts)` join key (sibling checkout). Desktop sibling:
pyrycode-desktop `ConversationScreen.tsx`'s `ModelRefusalRow` — this row's two titles and "unknown model"
copy are taken from it. Desktop's live-only offer to switch back to the *original* model, once out of
scope here, is now mobile's own ([#1360](https://github.com/pyrycode/pyrycode-mobile/issues/1360), §
Switch back below).

## The thread-row type

```kotlin
sealed interface ThreadItem {
    data class ModelRefusal(
        val originalModel: String,
        val fallbackModel: String?,   // non-null iff the frame was model_refusal_fallback
        val banner: String,
        val bannerTruncated: Boolean,
        val occurredAt: Instant,
    ) : ThreadItem
}
```

- **One variant for both frames.** `fallbackModel != null` *is* the envelope type — the domain carries it
  without a second flag, since the two frames render as one row kind with one of two titles.
- **Identity: the frame type — `fallbackModel != null` — plus `occurredAt`**, the envelope's (or stored
  entry's) `ts`. This is the same choice `banner` made and for the same reason: the daemon stamps one `ts`
  per refusal and hands it to both lanes, so reusing it is what lets a refusal received live and again in a
  history page join to one row. The type must stay part of the identity — using `ts` alone would collapse a
  fallback and a no-fallback that happen to share one instant into one row, which
  `modelRefusal_repeatOfOneTypeAndTimestamp_foldsOnce` and
  `merge_theSiblingRefusalTypeAtOneTimestamp_isAdmitted` (below) both cover.
- **Invariant: unique among a thread's refusal rows**, documented in KDoc and asserted in tests, not
  enforced at construction (the `SessionBoundary` posture). `ThreadProjection.appendModelRefusal` and
  `HistoryPageReducer`'s two arms both skip a refusal the thread already holds via the one
  `holdsModelRefusal` predicate, and `ThreadRow.listKey()` keys a refusal on the same `(type, ts)`, so the
  three readers can never disagree. A hostile daemon repeating one `(type, ts)` for two different refusals
  loses the second — the fail-safe direction, a missing row rather than a crashed list.
- **Every string is claude-authored, unsanitized, bounded daemon-side.** Held verbatim; stripping belongs
  to the render boundary (`refusalModelDisplay`, `bannerDisplayText`), the same split `banner` makes.
- **`scope` and `refusal_category` are decoded (shape-checked) and dropped by `toRow`.** They are claude's
  open assertions and drive nothing. A field the daemon named in `dropped_fields` simply arrives empty; an
  empty `originalModel` or `fallbackModel` reads "unknown model", an empty `banner` makes the row
  non-expandable.
- **`bannerTruncated` = `"banner" in truncated_fields`.** Truncation of a model identifier is not marked —
  the acceptance criteria name only the banner.
- **The two DTOs' fields are all strict-required with no Kotlin default** except the two report arrays
  (`truncated_fields`, `dropped_fields`, nullable because `null` is their normal wire value), matching
  pyrycode `internal/protocol/interactive.go`'s `ModelRefusalFallbackPayload` / `ModelRefusalNoFallbackPayload`,
  which set no `omitempty` on the required strings.
- **The frame is conversation-scoped with no `turn_id`, and neither opens nor closes a turn.** The row
  drives no turn state, no status-area indicator, and no model state — `model_announced` (see
  [Conversation repository § `model_announced` / `session_facts`](conversation-repository.md)) stays the
  only authority for which model is running. The wire cannot identify the refused partial reply, so no
  other row is retracted or edited.

## Live lane — `ThreadProjection.applyModelRefusal`

`RemoteConversationRepository.onInbound` gives both `TYPE_MODEL_REFUSAL_FALLBACK` (`"model_refusal_fallback"`)
and `TYPE_MODEL_REFUSAL_NO_FALLBACK` (`"model_refusal_no_fallback"`) one shared arm, gated on
`CAPABILITY_INTERACTIVE in negotiatedCapabilities()`, that does nothing but call
`threadProjection.applyModelRefusal(envelope)` — no `liveSessionEvents` emission, no turn/stall/status/model
write. `ThreadProjection.decodeModelRefusal(envelope)` selects the DTO by `envelope.type` (the only thing
that tells the two frames apart), stamps `Instant.parse(envelope.ts)`, and drops the one envelope inside a
single `try`/`catch (IllegalArgumentException)` covering both the decode and the timestamp parse — the
`decodeBanner` idiom. `appendModelRefusal` end-appends the mapped row inside one atomic
`threadByConversation.update` unless `holdsModelRefusal(row)` already holds; the dedup check and the write
share the lambda, so a concurrent merge cannot slip a duplicate in between them. Nothing on this arm logs
any field but the routing `conversation_id` — every other field is claude's.

## History lane — `withHistoryEntry`'s two arms

Gated the same way as the live lane (`interactive` only). Each arm decodes the entry's stored type, calls
`toRow(occurredAt = entry.timestamp)` — the entry's own stamped timestamp, not `Clock.System.now()` — and
skips the row via `holdsModelRefusal` before appending, inside the existing single `try` that costs only
that entry on a malformed one. `alreadyHolds` (the predicate the history merge runs against the thread the
live lane already has) gained an `is ThreadItem.ModelRefusal -> holdsModelRefusal(row)` arm.
`holdsModelRefusal` is the one shared predicate: the history merge, the live lane's `appendModelRefusal`,
and (transitively) `ThreadRow.listKey()` all agree with it.

## The row composable

```kotlin
@Composable
fun ModelRefusalRow(
    item: ThreadItem.ModelRefusal,
    agent: ConversationAgent,
    modifier: Modifier = Modifier,
    switchBack: SwitchBackOffer? = null,
    onSwitchBack: () -> Unit = {},
    knownModelLabel: (String) -> String? = { null },
)
```

**`knownModelLabel` (#1494).** `ThreadScreen` passes `state.runConfig::knownModelLabel`
(`ThreadRunConfig.knownModelLabel(identifier: String): String?`, next to `selectedChoice` in
`ThreadUiState.kt`): the label shared by every row in `choices + overflowChoices` (rendered and overflow menu
rows alike, never the hidden `inheritedChoice` default) whose `value` or `resolvedModel` equals `identifier`
exactly, or `null` when no row matches, matching rows disagree on the label, or `identifier` is empty (so a
cut `resolvedModel` of `""` can never match a blank identifier). No family or prefix guessing. With no menu
— including a row restored from the thread cache before a menu arrives — `choices` and `overflowChoices` are
both empty, so every identifier is unknown. The default parameter (`{ null }`) keeps every other caller
(previews, the no-menu shared tests) on the pre-#1494 monospace-only rendering.

The public row keeps only its `rememberSaveable` expansion Boolean under the list row key. The [refusal stream reference](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=620-1577) uses bare text in the message gutter: a collapsed title and `Show details` label, then an attributed explanation and `Hide details` when expanded. There is no pill, icon, border or bubble. When the sanitized explanation is non-blank, the entire gutter-width column is clickable, including its far edge, and carries the agent-named expand/collapse accessibility label. Without an explanation the title has no details action. Title and explanation wrap within the gutter.

The title is one `AnnotatedString`: client-owned copy (`thread_refusal_refused_on` "Refused on ",
`thread_refusal_continued_on` ", continued on ", `thread_refusal_refused_by` "Refused by ") in
`onSurfaceVariant`, with each model identifier its own span. **Since #1494**, a model the thread's menu
knows reads as the menu's label (`ThreadRunConfig.knownModelLabel`, see below) in that same `onSurfaceVariant`
body style — still its own `withStyle` span, never concatenated into the surrounding copy, even though the
style now matches it — while anything else keeps the pre-#1494 `FontFamily.Monospace` + `onSurface` span so
an identifier crafted to read as client copy (e.g. `"a, continued on b"` sent as `original_model` in a
no-fallback frame) cannot pass itself off as the surrounding words. A blank identifier renders the
client-owned `thread_refusal_unknown_model` ("unknown model") in the client span instead. Expanding shows
one `Text` built the same way [`BannerNoticeRow`](banner-notice-row.md) builds its line: the reused
`thread_banner_attribution` ("%1$s: ", the conversation's agent name from `agentName()`,
[#1113](https://github.com/pyrycode/pyrycode-mobile/issues/1113); "Claude: " for a Claude conversation)
span in `FontWeight.Medium`, then `bannerDisplayText(item.banner)`, then — when `bannerTruncated` — the
reused `thread_banner_truncated` in italic. No markdown, no `SelectionContainer`, no link detection, no
second length cap beyond the daemon's bound.

The expand/collapse click label (`cd_thread_refusal_expand`/`_collapse`, "Show/Hide %1$s's explanation")
takes the same agent name, since #1113 — before, both read "Show/Hide Claude's explanation"
unconditionally.

`refusalModelDisplay(model: String): String?` is the model-identifier render-boundary function:
`bannerDisplayText(model)` (the same CSI/OSC/C0/C1/DEL stripping `banner` uses) with tab, `\n` and `\r`
also removed — an identifier has no business spanning lines, and one that did could fake a second row — then
`null` when the result is blank, so the caller falls back to "unknown model". Covered by
`ModelRefusalDisplayTest` (JVM): a plain identifier survives; CSI/OSC/control escapes are stripped; tabs and
line breaks are stripped; empty, blank, and escape-only input all read as no model.

The private `appendModel` helper (#1494) is the one rule the title and the Switch back button (§ below) share
for one identifier: `refusalModelDisplay(model) == null` reads "unknown model"; otherwise
`knownModelLabel(model)` put through `refusalModelDisplay` again (so a label can never carry a line break
either, even though `label` is already `inert()`-stripped at the menu boundary) reads as that stripped label
in the client span when non-null; otherwise the stripped identifier reads in the monospace span as before.
The lookup is always against the raw `model`; stripping applies only to what is drawn.

### Security — why each model is its own span

The realistic abuse the protocol names is a claude-authored identifier trying to read as more of the
client's sentence, or as a second attribution. Both are addressed the way `BannerNoticeRow` addresses banner
spoofing: every claude-authored value is its own styled `SpanStyle` between client-owned proportional spans,
never concatenated into one plain string, so claude can type the client's literal words into a field but
cannot restyle a span to make them look like part of the chrome. The `"<agent>: "` attribution stays a
separate medium-weight span, the #873 rule. Unicode bidi/format-character stripping is out of scope here,
matching `banner` and desktop.

**The 2026-10-02 design decision relaxed the "always monospace" half of this rule, not the "always its own
span" half (#1494).** A model the thread's menu knows now draws in the title's own body style instead of
monospace, because that is what the Status sheet already shows as the model's name. It still goes through a
separate `withStyle` call — `appendModel`'s `label != null` branch — so the span boundary survives a style
that happens to match the surrounding copy; nothing is ever concatenated into the plain client string. The
match is exact (`ThreadRunConfig.knownModelLabel`: the identifier equals a published row's `value` or
`resolvedModel`, no family or prefix guess) and must be unanimous across every matching row, so a second
published row with a different label can never be shadowed by the first, and a hostile daemon cannot widen
the known set by publishing a near-miss. **Accepted residual:** when a Claude row's `value` has no family,
`ThreadViewModel`'s `dropdownLabel` falls back to the row's own claude-authored `displayName`, so a daemon
that controls the menu could in principle publish `value = "x"`, `displayName = "Opus, continued on Sonnet"`
and then refuse naming `"x"` — the title would draw that label in the client's own style. This is accepted
because the daemon that publishes the menu is the one that could already lie in it (the Status sheet shows
the same label today), and the label still passes through `refusalModelDisplay`, is capped at 128 characters
by `inert()` at the menu boundary, and stays a separate span. A future change to this row must keep those
three properties, not just the exact-match rule, to keep the residual this narrow.

**The agent name stays client-owned ([#1113](https://github.com/pyrycode/pyrycode-mobile/issues/1113)),
same as `BannerNoticeRow`.** The attribution and both click labels take their name from `agentName(agent)`
(`components/AgentName.kt`), an exhaustive `when` over the closed `ConversationAgent` enum — never text
the daemon sent. `agent` is a **required** parameter on this row (no default). See [Banner notice row §
Security](banner-notice-row.md#security--why-the-attribution-is-its-own-span) for the shared idiom, why it
diverges from #1114's twin-string approach on the other status indicators, and where `agent` comes from
(`ThreadUiState.agent` ← `Conversation.agent`, #1108). With a Claude conversation every string renders
byte-for-byte as before #1113.

## Switch back (#1360)

A live `model_refusal_fallback` with `scope` exactly `"session"` and both models non-empty offers "Switch
back to \<original model\>" below Show details on the row it armed — mobile's analogue of desktop's
`reduceRefusalOffer` / `switchBack` (`threadTimeline.ts`, `ConversationScreen.tsx`). One tap writes the
offer's original model, verbatim, to the session the settings reading names. `scope` is still an open,
claude-authored string (§ above); matching it here can only withhold the offer, never arm a write a user
did not request, and the daemon validates the model in `set_session_settings`.

**Why the offer cannot come from a row.** A `ThreadItem.ModelRefusal` looks the same whether it just arrived
live or was restored from a history page, the cache (§ below) or a reopened thread — the type carries no
"this is live" bit. So `ThreadViewModel` never derives the offer from
`ThreadUiState.items`. It is armed from a sibling signal, `ConversationRepository.observeLiveRefusalEvents
(conversationId): Flow<LiveRefusalEvent>` (defaulted to `emptyFlow()`, so the many repository test fakes
need no change, the same posture as `observeAnnouncedModel`), emitted only from the live decode path:
`ThreadProjection.applyModelRefusal` returns the decoded `(conversationId, LiveRefusalEvent.Refused)` pair
alongside folding the row, and `RemoteConversationRepository` pushes it into a private hot
`MutableSharedFlow` (`replay = 0`, `DROP_OLDEST`) that `observeLiveRefusalEvents` filters by id;
`StableConversationRepository` passes it through with `flatMapLatest`. A `TYPE_SESSION_TRANSITION` frame
emits `LiveRefusalEvent.SessionReplaced` on the same flow. History and cache code paths never touch this
flow, so a restored or cached refusal row can be on screen with no offer under it — that is the point, not
a bug to chase.

**The reducer** (`ThreadViewModel.onLiveRefusalEvent`, after desktop's table):

| Event | Effect |
|---|---|
| `Refused`, `fallbackModel` set, `scope == "session"`, both models non-empty | arm (replaces any existing offer) |
| `Refused`, `fallbackModel` set, anything else | clear (logged `reason=unqualified`) |
| `Refused`, `fallbackModel` null (a no-fallback refusal) | unchanged |
| `SessionReplaced` | clear (`reason=session`) |
| an announced model (`observeAnnouncedModel`, non-empty, `distinctUntilChanged`) other than the armed fallback | clear (`reason=announced`) |
| `onModelSelected` starting a menu write | clear (`reason=menu`) |
| the switch-back write acknowledged | clear, but only the offer it was sent for (matched by `occurredAt`) |
| the switch-back write refused or failed | `failed = true` on that same offer; the next tap resets it |
| a reconnect | unchanged — `distinctUntilChanged` on the announced-model reading means a held reading handed over again on reconnect does not read as a fresh announcement |

**Visible only with a session to address.** The public `switchBackOffer: StateFlow<SwitchBackOffer?>`
combines the armed offer with the latest settings reading and reads `null` whenever that reading names no
session — otherwise a visible button's tap would be silently dropped by `skipUnlessWritable`. Arming also
calls `repository.refreshSessionSettings(conversationId)`, because a refusal proves a session is running
but the thread's settings reading can predate that session's spawn and still name none. **This was found
only by the scripted scenario**, not by the JVM `ThreadViewModel` tests, because every JVM fake starts with
a session already present; a fresh conversation's first-turn refusal is the case that needed the extra
refresh. Any future control gated on `ThreadRunConfig.writable`/a session id should assume the opening
reading can be stale in exactly this way, not just absent.

**The write.** `onSwitchBack` reuses `sendSessionSettings` and the `pendingModel` mark shared with the menu
write (`onModelSelected`), so the two can never have two model writes in flight at once. It does **not**
route through `onModelSelected`: that function's `value == config.selectedModel` guard exists to swallow a
redundant radio tap, but after a session-scoped fallback the reading can still name the *original* model
while claude is actually running the fallback — the guard would silently drop the one write switch-back
exists to send. `onSwitchBack` is dropped while disconnected, without an offer, while any model write
(either control's) is pending, or without a session to address; while pending the button is disabled and
drawn at 38% opacity (Figma 646-4694); a refused or failed write re-enables it with the retry line "Could
not change the model — try again." (Figma 646-4700) until the next tap. Only switch-back passes
`reportFailure = false` to the shared write helper: both
`RelayErrorException` and `IllegalStateException` still run the offer's identity-checked revert, but emit
no `sessionSettingsErrors` signal. The inline retry line appears immediately without a duplicate
run-configuration snackbar covering it. Retry clears `failed` before entering pending. Ordinary model
and effort edits keep the default shared feedback. `CancellationException` is rethrown before either
failure catch, so teardown neither marks the offer failed nor signals an error (#1615).

**The remembered-model side effect.** `sendSessionSettings`'s ack path is shared by every caller and always
calls `rememberModel(model)` (wired to `AppPreferences.setRememberedModel`), regardless of which control
sent the write. So an acknowledged switch-back also sets the *original* model as the phone's remembered
model for new chats, exactly as an equivalent menu pick would. This was not called out in #1360's plan — its
security review said the offer lives "in ViewModel memory only," which is true of the offer itself but not
of this side effect of its write — and verification let it stand as a non-blocking SHOULD FIX rather than a
plan contradiction, since the daemon had already validated the value and `ChannelListViewModel
.applyRememberedModel` applies only a value that matches a menu row. Any new caller of `sendSessionSettings`
inherits this DataStore write and should say so in its own plan rather than assume the function is side-effect-free beyond the thread.

**Rendering.** `ModelRefusalRow` takes `switchBack: SwitchBackOffer? = null` and `onSwitchBack: () -> Unit
= {}`; `ThreadScreen` passes `switchBackOffer?.takeIf { it.armedBy(item) }` so only the fallback row that
armed the current offer draws the button — a second fallback row, qualifying or not, never shows one.
`SwitchBackOffer.armedBy` matches the row by `occurredAt`, the frame's own identity (§ above), not by model
value, so two refusals naming the same models at different instants cannot cross-arm each other's row. The
button label shares the title's `appendModel` rule (#1494, § The row composable above): the claude-authored
original model reads as the thread menu's label for it, in the button's own text style, when
`ThreadRunConfig.knownModelLabel` knows it, otherwise it is put through `refusalModelDisplay` as its own
monospace span, the same anti-spoofing split the row's title and banner use (§ Security above) — a tap target
is a second place an identifier could otherwise masquerade as client copy, and the label case keeps that
split by giving the label its own `withStyle` call even though the style matches the button's copy.

The normal collapsed offer's outline starts at row-relative y=56 and is 32 dp tall, with 12 clear
pixels below visible Show details at density 1/default font scale (#1615). Foundation `clickable`
expands input to 48 dp without reserving Material touch-target layout space. The details block's bottom
padding stays outside its click boundary so taps in either button extension route to switch-back;
pending disables both extensions. Offered title/details and the failed line use centred, untrimmed
line boxes: default first/last-line trimming shortened the title/details from 20/16 dp to 18/14 dp and
moved the outline to y=52. Offer-free rows keep their existing text layout.

Treat 32 dp as the outline's **minimum**, with 8 dp vertical label padding. A fixed height matched the
short Figma label while clipping a valid 128-character unknown destination. Wrapped identifiers and
larger fonts must grow the outline and put the retry line below every label line; keep `appendModel`'s
inert separate spans. Visible bounds, expanded input bounds and text containment need separate
assertions: semantics can expose the whole label even when only its first lines paint. Pin wrapping
fixtures to 412 dp inside their density provider and use native-font `TextLayoutResult` overflow and
last-line/outline containment alongside physical pointer taps. See the
[armed/pending/failed capture verdicts](../../../app/src/androidTest/assets/design-1220/thread/index.md#refusal-switch-back--6464707)
for real-bar pixels and the immediate inline-only failure capture.

**No rung-3 scenario; the rung-4 scripted `refusal` scenario is the end-to-end proof** — real claude cannot
be made to refuse on demand. See [the scripted-scenario entry](../../e2e-interactive-stream.md#scenarios-454)
in the e2e doc for the fixture and the daemon round trip it proves (a fresh settings reading naming the
original model, not just the button disappearing).

## `ThreadRow` / `ThreadScreen` wiring

All the exhaustive `when`s over `ThreadItem` gained a `ModelRefusal` arm:

- **`ThreadRow.listKey()`** — `"refusal:fallback:$occurredAt"` when `fallbackModel != null`, else
  `"refusal:no-fallback:$occurredAt"` — unique because `holdsModelRefusal` is.
- **`ThreadScreen`'s `LazyColumn` render** — `ModelRefusalRow(item = item, agent = state.agent)` (`agent`
  since #1113). Not a session boundary for `mostRecentSessionBoundaryIndex` — unchanged.
- **`ThreadItem.timestamp()`** — `occurredAt`.
- **`HistoryPageReducer.alreadyHolds`** — `holdsModelRefusal(row)`.
- **`FileConversationCache.toRecord`** — maps to `CachedRefusal(originalModel, fallbackModel, banner,
  bannerTruncated, occurredAt)` (#1353).
- **`RemoteConversationRepositoryTest.threadShape()`** — the test-fixture helper outside production code
  that also needs every `ThreadItem` arm to keep compiling.

## Cache — persisted (#1353)

`cacheableThreadRows` ([Conversation cache](conversation-cache.md)) keeps `ThreadItem.ModelRefusal`
alongside `Banner` and `CompactionBoundary`; only `ThreadItem.UnrecognizedMessage` still never reaches
app-private disk — its KDoc forbids persisting raw model-adjacent JSON. The model names and the banner
text are claude-authored and stored verbatim, the same posture message content already has; the render
path owns stripping either way. Before #1353 this row was dropped here and restored only by history
replay after a cold start or a fresh cache (#875); once history loads only on the user's request, that
replay stopped running on a routine reopen, so the row had to be cached instead. `occurredAt` plus
`fallbackModel != null` is the row's dedupe key both ways — `ThreadRow.listKey()` for the `LazyColumn`,
`HistoryPageReducer.holdsModelRefusal` for the merge, and `decodeThread` for rejecting a document that
repeats it for one frame type.

## Testing

- `ModelRefusalDisplayTest` (JVM, new): the stripping-set coverage listed above.
- `RemoteConversationRepositoryTest`, `modelRefusal_*` block: `modelRefusal_bothFramesFoldOneRowEachCarryingValuesVerbatim`
  (fallback and no-fallback each fold one row with every field verbatim and the envelope `ts`),
  `modelRefusal_bannerNamedInTruncatedFields_isMarkedCut` (only `"banner"` in `truncated_fields` sets
  `bannerTruncated`), `modelRefusal_interleavesInArrivalOrderAndNeverCrossRoutes`,
  `modelRefusal_repeatOfOneTypeAndTimestamp_foldsOnce` (the same `(type, ts)` folds once; a distinct `ts` on
  the sibling type folds a second row), `modelRefusal_changesNoTurnStatusModelOrExistingRow` (inert:
  `liveSessionEvents` silent, existing stall/model state untouched), `modelRefusal_malformedDropped_collectorSurvives`
  (a missing field, a wrong-typed field, or a malformed `ts` drops only that frame),
  `modelRefusal_capabilityGateClosedOrUnrelated_foldsNothing`. `threadShape()` gained the arm.
- `HistoryPageReducerTest`: `reduce_storedRefusalsOfBothTypes_becomeRowsStampedWithTheEntryTimestamp`,
  `reduce_storedRefusalWithoutInteractive_yieldsNothing`, `reduce_malformedRefusal_costsOnlyThatEntry`,
  `merge_aPageWhoseRefusalIsAlreadyLive_addsNoSecondRow` (a page racing the live lane merges to one row),
  `merge_theSiblingRefusalTypeAtOneTimestamp_isAdmitted` (proves the type half of the identity: a
  no-fallback refusal at the same `ts` as a live fallback refusal is a **different** row, not a duplicate).
- `FileConversationCacheThreadTest`: `` `model refusal rows are never stored` `` — `cacheableThreadRows`
  drops the row.
- `ModelRefusalRowTest` (Compose instrumented, `app/src/androidTest/.../components/`): a fallback reads
  "Refused on X, continued on Y", a no-fallback reads "Refused by X", an empty model reads "unknown model",
  the banner stays hidden until tapped then shows "Claude: " + stripped text, the truncated mark shows only
  when cut, and an empty banner leaves the row with no click action. The test is
  `androidTest`-only. The full managed-device suite on #1283 executed all nine methods with zero failures
  or skips, including long-explanation gutter wrapping and a pointer tap at the far edge of the row.
- **#875/#1113: no rung-3 / rung-4 scenario for the row itself.** The daemon has no scripted refusal
  emitter for the no-fallback frame; live verification against a real claude is
  [#679](https://github.com/pyrycode/pyrycode-mobile/issues/679), per the ticket. #679's own body does not
  yet mention either frame — the only record of that handoff is on #875 and here. #1360 (below) does add a
  scripted emitter, but only for the fallback frame's switch-back path.
- **#1113**: the new sharedTest `ThreadAgentAttributionTest` (Robolectric, through `ThreadScreen`) covers a
  Codex conversation's refusal — "Codex: …" text once expanded, click label "Show Codex's explanation" then
  "Hide Codex's explanation" after the toggle — and a Claude conversation rendering unchanged.
- **#1360 (switch back)**: `ThreadViewModelRefusalOfferTest` (JVM, new, 16 methods) — arming only on a live
  session-scoped fallback with both models non-empty; a `local` scope, an empty model or an unqualified
  scope clears; a no-fallback leaves the offer unchanged; a newer qualifying refusal re-keys it to the newer
  row; rows from `observeMessages`/history never arm. The tap: one write with the original model verbatim
  even when the reading already names it; nothing while offline, without a session, or while a model write
  is pending; pending visible during the write; an ack clears; a refusal (`RelayErrorException`) or failure
  (`IllegalStateException`) keeps the offer with `failed = true`, and the next tap resets it. Lifetime: an
  announced model other than the fallback clears, the fallback or a repeat of the same model does not;
  `SessionReplaced` clears; a menu write clears; a reconnect does not. Also covers `arming_asksForAFreshReading_…`
  and the no-session case for the Revisions-era addition (§ Switch back above).
  `RemoteConversationRepositoryRefusalEventsTest` (JVM, new, 5 methods): a live fallback emits `Refused`
  with `scope` and the folded row; a no-fallback emits `Refused` with a null scope; a session transition
  emits `SessionReplaced`; a malformed frame emits nothing; another conversation's frame is not delivered.
  One new `StableConversationRepositoryTest` method proves the pass-through. `ModelRefusalSwitchBackTest`
  (`app/src/sharedTest`, Robolectric, new, 5 methods): the three Figma states (armed button and label,
  pending disabled, failed message under an enabled button), a tap calling back once, no button without an
  offer, and — through `ThreadScreen` — only the arming row of two fallback rows showing the button.
  Device-only `ModelRefusalRowTest` was re-run after the row's layout restructuring (9 methods, 0 failures).
  Rung 4: the scripted `refusal` scenario (see
  [e2e-interactive-stream.md](../../e2e-interactive-stream.md#scenarios-454)). No rung 3: real claude cannot
  be made to refuse on demand.

- **#1494 (known model labels)**: `ThreadRunConfigKnownModelLabelTest` (JVM, new, 8 methods) — match by
  `value`, by `resolvedModel`, and on an overflow-only row; two agreeing rows (including one of them in
  overflow) match, two disagreeing rows don't; a prefix, family-only, case-different or trailing-newline
  lookup misses; no menu, an empty identifier against a cut (`""`) `resolvedModel`, and `inheritedChoice`
  alone are all unknown. `ModelRefusalModelLabelTest` (sharedTest, Robolectric at `w412dp-h892dp`, through
  `ThreadScreen`, new, 5 methods) — known (title one line, "Opus" and "Sonnet" each a span of their own with
  no monospace family and the client colour, button "Switch back to Opus" with "Opus" its own span), unknown,
  ambiguous (two menu rows disagreeing on the label), no menu, and a blank identifier reading "unknown
  model"; each case checks that no span straddles a model name, not just the rendered text. `ModelRefusalRowTest`,
  `ThreadAgentAttributionTest` and `ModelRefusalSwitchBackTest` keep their no-menu fixtures and so keep
  asserting the pre-#1494 monospace form unchanged. Device capture: `ThreadDesignCaptureTest
  .threadNoticeFramesAt412By892` now seeds the menu before taking `notification-text.png` and
  `refusal-switch-back.png`; the `16:8` status capture (`threadStatusFramesAt412By892`) still seeds none, so
  its refusal row stays monospace by design, not a defect — see the design index entries below. The scripted
  `refusal` scenario's Switch back button text follows whatever label fakeclaude's canned menu gives the
  model it names (`DeterministicInteractiveStreamE2ETest`, [e2e doc](../../e2e-interactive-stream.md#scenarios-454)).

- **#1615 (geometry, overflow and inline-only failure):** retained final rework XML records 50 JVM,
  20 device and 1 deterministic refusal executions, all passed with 0 failures/errors/skips.
  `ThreadViewModelRefusalOfferTest.aRefusedOrFailedWrite_keepsTheOfferMarkedFailed_untilTheNextTap`
  observes both typed failure classes and retry clearing;
  `ordinaryModelAndEffortFailures_stillEmitSharedFeedback` and
  `cancelledSwitchBack_doesNotRevertOrReportFailure` preserve the other write-path contracts.
  `ModelRefusalSwitchBackTest` independently checks 32 dp visible geometry, 48 dp touch bounds, upper/lower
  extension routing and disabled pending input. Its `longUnknownModel_paintsEveryLineInsideTheOutline`
  and `enlargedModelText_paintsEveryLineInsideTheOutline` executed on both native-font JVM and real device.
  `ThreadDesignCaptureTest.refusalStateFramesAt412By892` and `.threadNoticeFramesAt412By892` both passed
  in the full API 35 focused selection with `requireRealSystemBars=true`; the failure assertion happens
  before timer advancement or snackbar dismissal. See the linked capture index for XML and provenance.

## Related

- Ticket notes: [`../codebase/875.md`](../codebase/875.md). #1113 postdates the 2026-09-05 codebase-archive
  freeze and has no per-ticket note.
- Spec: [`docs/specs/architecture/875-model-refusal-row.md`](../../specs/architecture/875-model-refusal-row.md)
  (design + security review, verdict PASS) ·
  `docs/specs/architecture/1113-agent-name-in-thread-notices.md` (the `agent` param, § Security above) ·
  [`docs/specs/architecture/1360-refusal-switch-back.md`](../../specs/architecture/1360-refusal-switch-back.md)
  (the switch-back offer, § Switch back above; its Revisions section records the no-session fix) ·
  [`docs/specs/architecture/1494-refusal-row-model-labels.md`](../../specs/architecture/1494-refusal-row-model-labels.md)
  (`ThreadRunConfig.knownModelLabel`, the known/unknown render rule, § Security's accepted residual above).
- Wire SSOT: `pyrycode/docs/protocol-mobile.md` § `model_refusal_fallback`, § `model_refusal_no_fallback`,
  § *Joining a page to the live stream* (sibling checkout).
- Desktop sibling: pyrycode-desktop `ConversationScreen.tsx`'s `ModelRefusalRow` (`reduceRefusalOffer` in
  `store/threadTimeline.ts`, `subscribeRefusalRecovery` in `store/runSettingsWriteBridge.ts`, `switchBack`
  in `screens/conversation/ConversationScreen.tsx` — the source the switch-back offer copies).
- Rung-4 scripted scenario: [`docs/e2e-interactive-stream.md`](../../e2e-interactive-stream.md#scenarios-454)
  § Scenarios, the `refusal` row, and its Follow-ups-to-ticket entry for #1360.
- Structural precedent: [`Banner notice row`](banner-notice-row.md) — same wire-minted `(type, ts)` identity
  and dedup posture, read before adding a fifth `ThreadItem` variant of this kind.
- Sibling row: [`StoppedTurnRow`](stopped-turn-row.md) (#1356) — same bare-text landing shape, but keyed on
  `turnId` rather than a wire-minted `(type, ts)`.
- Consumers: [`Conversation repository`](conversation-repository.md) (`ThreadItem`, co-located types,
  `model_announced` as the model-state authority, and — since #1360 — `LiveRefusalEvent` /
  `observeLiveRefusalEvents`), [`Remote conversation repository`](remote-conversation-repository.md)
  (`onInbound` routing arm; the decode+fold itself lives in `ThreadProjection`, which also emits the live
  refusal event), [`Thread screen`](thread-screen.md) (`LazyColumn` key, render arm, `timestamp()`, and the
  `switchBackOffer`/`onSwitchBack` wiring), [`Conversation cache`](conversation-cache.md) (excluded from
  persistence).
