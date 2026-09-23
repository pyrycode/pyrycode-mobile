# Model refusal row — `ThreadItem.ModelRefusal` / `ModelRefusalRow`

The thread's explanation for a changed model or a missing answer: claude refused a turn on one model and
either retried it on another or did not ([#875](../codebase/875.md), split from #654). Before this ticket
mobile dropped both `model_refusal_fallback` and `model_refusal_no_fallback` on both lanes, so a refusal
went unexplained. Landed in the [`Banner`](banner-notice-row.md) / [`CompactionBoundary`](session-boundary-delimiter.md#compactionboundarydivider-874)
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
(reuses `bannerDisplayText`) and [`UnrecognizedMessageRow`](unrecognized-message-row.md) (reuses the
collapsed-pill shape).

Wire SSOT: pyrycode `docs/protocol-mobile.md` § `model_refusal_fallback`, § `model_refusal_no_fallback`,
§ *Joining a page to the live stream* for the `(type, ts)` join key (sibling checkout). Desktop sibling:
pyrycode-desktop `ConversationScreen.tsx`'s `ModelRefusalRow` — this row's two titles and "unknown model"
copy are taken from it, without desktop's live-only offer to switch to the fallback model, which is out of
scope here.

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
fun ModelRefusalRow(item: ThreadItem.ModelRefusal, modifier: Modifier = Modifier)
```

Stateful/stateless split matching [`UnrecognizedMessageRow`](unrecognized-message-row.md): the public
`ModelRefusalRow` owns a `rememberSaveable` `expanded: Boolean` (only the toggle is saved, scoped by the
row's `LazyColumn` key) and delegates to a private stateless `ModelRefusalRowContent`. Figma 16:8 has no
refusal component, so the row draws `UnrecognizedMessageRow`'s collapsed pill: a 12dp rounded `Surface` on
`colorScheme.surfaceContainer` with a 1dp `outlineVariant` border, a leading 18dp `Icons.Outlined.Info`
glyph (`contentDescription = null` — the adjacent title carries the meaning), and one `bodySmall` line in
`onSurfaceVariant`. The `Surface` is `clickable` only when the sanitized banner is non-blank; a row with no
explanation has no click action at all.

The title is one `AnnotatedString`: client-owned copy (`thread_refusal_refused_on` "Refused on ",
`thread_refusal_continued_on` ", continued on ", `thread_refusal_refused_by` "Refused by ") in
`onSurfaceVariant`, with each model identifier as its own `FontFamily.Monospace` + `onSurface` span so an
identifier crafted to read as client copy (e.g. `"a, continued on b"` sent as `original_model` in a
no-fallback frame) cannot pass itself off as the surrounding words. A blank identifier renders the
client-owned `thread_refusal_unknown_model` ("unknown model") in the client span instead. Expanding shows
one `Text` built the same way [`BannerNoticeRow`](banner-notice-row.md) builds its line: the reused
`thread_banner_attribution` ("Claude: ") span in `FontWeight.Medium`, then `bannerDisplayText(item.banner)`,
then — when `bannerTruncated` — the reused `thread_banner_truncated` in italic. No markdown, no
`SelectionContainer`, no link detection, no second length cap beyond the daemon's bound.

`refusalModelDisplay(model: String): String?` is the model-identifier render-boundary function:
`bannerDisplayText(model)` (the same CSI/OSC/C0/C1/DEL stripping `banner` uses) with tab, `\n` and `\r`
also removed — an identifier has no business spanning lines, and one that did could fake a second row — then
`null` when the result is blank, so the caller falls back to "unknown model". Covered by
`ModelRefusalDisplayTest` (JVM): a plain identifier survives; CSI/OSC/control escapes are stripped; tabs and
line breaks are stripped; empty, blank, and escape-only input all read as no model.

### Security — why each model is its own span

The realistic abuse the protocol names is a claude-authored identifier trying to read as more of the
client's sentence, or as a second attribution. Both are addressed the way `BannerNoticeRow` addresses banner
spoofing: every claude-authored value is its own styled `SpanStyle` between client-owned proportional spans,
never concatenated into one plain string, so claude can type the client's literal words into a field but
cannot restyle a span to make them look like part of the chrome. `"Claude: "` stays a separate medium-weight
span, the #873 rule. Unicode bidi/format-character stripping is out of scope here, matching `banner` and
desktop.

## `ThreadRow` / `ThreadScreen` wiring

All the exhaustive `when`s over `ThreadItem` gained a `ModelRefusal` arm:

- **`ThreadRow.listKey()`** — `"refusal:fallback:$occurredAt"` when `fallbackModel != null`, else
  `"refusal:no-fallback:$occurredAt"` — unique because `holdsModelRefusal` is.
- **`ThreadScreen`'s `LazyColumn` render** — `ModelRefusalRow(item = item)`. Not a session boundary for
  `mostRecentSessionBoundaryIndex` — unchanged.
- **`ThreadItem.timestamp()`** — `occurredAt`.
- **`HistoryPageReducer.alreadyHolds`** — `holdsModelRefusal(row)`.
- **`FileConversationCache.toRecord`** — throws `IllegalStateException("model refusal rows are never cached")`.
- **`RemoteConversationRepositoryTest.threadShape()`** — the test-fixture helper outside production code
  that also needs every `ThreadItem` arm to keep compiling.

## Cache — never persisted

`cacheableThreadRows` ([Conversation cache](conversation-cache.md)) filters out `ThreadItem.ModelRefusal`
alongside `Banner`, `CompactionBoundary` and `UnrecognizedMessage` — claude-authored model names and prose
never reach app-private disk. `settledThreadRows` (the narrower "may still draw, connection gone" filter)
keeps refusal rows, so a thread that has lost its connection still shows them; only `cacheableThreadRows`
(the "may reach disk" filter) drops them. History replay is what restores a refusal after a cold start or a
fresh cache — not the cache.

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
  when cut, and an empty banner leaves the row with no click action.
- **No rung-3 / rung-4 scenario.** The daemon has no scripted refusal emitter; live verification against a
  real claude is [#679](https://github.com/pyrycode/pyrycode-mobile/issues/679), per the ticket. #679's own
  body does not yet mention either frame — the only record of that handoff is on #875 and here.

## Related

- Ticket notes: [`../codebase/875.md`](../codebase/875.md)
- Spec: [`docs/specs/architecture/875-model-refusal-row.md`](../../specs/architecture/875-model-refusal-row.md)
  (design + security review, verdict PASS)
- Wire SSOT: `pyrycode/docs/protocol-mobile.md` § `model_refusal_fallback`, § `model_refusal_no_fallback`,
  § *Joining a page to the live stream* (sibling checkout).
- Desktop sibling: pyrycode-desktop `ConversationScreen.tsx`'s `ModelRefusalRow`.
- Structural precedent: [`Banner notice row`](banner-notice-row.md) — same wire-minted `(type, ts)` identity
  and dedup posture, read before adding a fifth `ThreadItem` variant of this kind.
- Consumers: [`Conversation repository`](conversation-repository.md) (`ThreadItem`, co-located types,
  `model_announced` as the model-state authority), [`Remote conversation repository`](remote-conversation-repository.md)
  (`onInbound` routing arm; the decode+fold itself lives in `ThreadProjection`), [`Thread
  screen`](thread-screen.md) (`LazyColumn` key, render arm, `timestamp()`), [`Conversation
  cache`](conversation-cache.md) (excluded from persistence).
