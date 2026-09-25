# Banner notice row — `ThreadItem.Banner` / `BannerNoticeRow`

The thread's visible answer to text claude prints **about** the session rather than as part of an
answer — a hook's reason for blocking a prompt, a local command's output, a loop notification
([#873](../codebase/873.md), split from #654). Without this row a blocked prompt looked accepted and
then silently ignored: the daemon sent the `banner` frame, and mobile dropped it on both the live and
history lanes. Landed the way [`unrecognized_message` landed](unrecognized-message-row.md) (#608, #609):
one ticket for the type, the decode arms on both lanes, and the row composable, because the decoder and
the renderer are each other's only consumer.

Package: `de.pyryco.mobile.ui.conversations.components`
(`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `BannerNoticeRow.kt`.
Type + wire DTO: `data/repository/ConversationRepository.kt` (`ThreadItem.Banner`, `BannerLevel`),
`data/network/InteractivePayloads.kt` (`BannerPayloadDto`, `toRow`). Live decode:
`data/repository/RemoteConversationRepository.kt` (`decodeBanner`, `appendBanner`, `TYPE_BANNER`).
History decode: `data/repository/HistoryPageReducer.kt` (`withHistoryEntry`'s `TYPE_BANNER` arm,
`holdsBanner`). Sibling of [`UnrecognizedMessageRow`](unrecognized-message-row.md),
[`SessionBoundaryDelimiter`](session-boundary-delimiter.md).

Wire SSOT: pyrycode `docs/protocol-mobile.md` § `banner` (sibling checkout) + § *Joining a page to the
live stream* for the `(type, ts)` join key. Desktop sibling: pyrycode-desktop `ConversationScreen.tsx`'s
`bannerDisplayText` and the `banner` arm of the timeline render — this row's stripping set and its
attribution are taken from it, without desktop's composer-slot handling of `stops_turn`, which mobile has
no equivalent surface for. The attribution originally read a fixed `"Claude: "`; since
[#1113](https://github.com/pyrycode/pyrycode-mobile/issues/1113) it names the conversation's own agent
(§ Security below).

## The thread-row type

```kotlin
sealed interface ThreadItem {
    data class Banner(
        val level: BannerLevel,
        val text: String,
        val truncated: Boolean,
        val occurredAt: Instant,
    ) : ThreadItem
}

enum class BannerLevel { Warning, Notice }
```

- **`occurredAt` is the row's identity**, not a client-minted id — the envelope's (or stored entry's)
  `ts`, the protocol's `(type, ts)` join key with `type` implied by the variant. This is the opposite
  choice from `UnrecognizedMessage.id`: that frame carries no timestamp claude could stamp meaningfully,
  so #609 mints a client-owned monotonic counter; `banner` carries a daemon-minted `ts` on both lanes, so
  reusing it is what lets a banner received live and again in a history page join to one row. **Do not
  copy the `appendUnrecognizedMessage` no-dedup posture onto a new `ThreadItem` variant without checking
  whether the frame has a wire identity first** — a banner has one, `unrecognized_message` deliberately
  does not (its repeats are never coalesced because the firing frequency is itself the signal), and
  copying the no-dedup shape here would pass every single-lane test and then crash the `LazyColumn` on a
  duplicate key the first time a history page raced the live lane.
- **Invariant: `occurredAt` is unique among a thread's banners**, documented in KDoc and asserted in
  tests, not enforced at construction — the `SessionBoundary` posture. Both thread writers skip a banner
  the thread already holds: `RemoteConversationRepository.appendBanner` and `HistoryPageReducer`'s
  `TYPE_BANNER` arm both call the one `holdsBanner(row)` predicate, and `ThreadRow.listKey()` keys a
  banner on the same `occurredAt`, so the three readers can never disagree about identity. A hostile
  daemon repeating one `ts` for two different banners loses the second — the fail-safe direction, a
  missing notice rather than a crashed list — and the daemon's single emit path cannot actually produce
  that case.
- **`level` is closed client-side.** The wire's `level` is an open set — `warning` is observed, `info` /
  `notice` / `suggestion` are documented, and it may be empty — and `BannerPayloadDto.toRow` is a
  **total** map: `"warning"` becomes `Warning`, every other value (including empty, unknown, and one
  claude ships later) becomes `Notice`. The wire string itself never reaches the UI, so claude cannot
  inject an arbitrary label.
- **`text` is carried verbatim, unsanitized**, bounded daemon-side at 4 KiB and not cleaned before it
  crosses the wire. Stripping belongs to the render boundary (`bannerDisplayText`, below) — keeping the
  domain value verbatim means the `(type, ts)` join never depends on presentation.
- **`stops_turn` is decoded and then dropped.** `BannerPayloadDto` carries `stopsTurn` so its shape is
  checked (a missing or wrong-typed field still fails the decode), but `toRow` never copies it into
  `ThreadItem.Banner` — nothing on mobile has a turn-scoped surface for it to drive, and the frame is
  conversation-scoped with no `turn_id`. A `stops_turn: true` banner changes no turn state, emits no
  `LiveSessionEvent`, and moves no stall, API-retry, or compacting indicator; it is a report, not a
  control signal.
- **`BannerPayloadDto`'s five fields are all strict-required with no Kotlin default**, matching pyrycode
  `internal/protocol/interactive.go`'s `BannerPayload`, which declares no `omitempty` — every field always
  arrives present, so a missing or wrong-typed one is a decode failure rather than a silently-defaulted
  value.

## Live lane — `decodeBanner` / `appendBanner` (`TYPE_BANNER`)

Rides the single existing `pump.inbound` collector, the same shape as `unrecognized_message`'s arm: gated
on `CAPABILITY_INTERACTIVE in negotiatedCapabilities()`, decode-or-drop inside one `try`/`catch
(IllegalArgumentException)` that covers both the DTO decode and `Instant.parse(envelope.ts)`, routed by
the payload's `conversation_id`, folded with `threadByConversation.update { … }`. `appendBanner` differs
from `appendUnrecognizedMessage` in exactly the way the type-level note above requires: it end-appends
**unless `holdsBanner(row)`** already holds, and the dedup check runs inside the same atomic `update`
lambda as the write, so a concurrent merge cannot slip a duplicate in between the check and the append.
The arm makes exactly one write and logs nothing — `text` is claude-authored prose.

## History lane — `withHistoryEntry`'s `TYPE_BANNER` arm

Gated the same way as the live lane's structured types (`interactive` only). Decodes the entry's payload,
calls `toRow(occurredAt = entry.timestamp)` — the entry's own stored timestamp, not `Clock.System.now()`
— and skips the row via `holdsBanner` before appending. `alreadyHolds` (the predicate `withHistoryEntry`'s
merge runs against the thread the live lane already has) gained an `is ThreadItem.Banner ->
holdsBanner(row)` arm alongside `MessageItem`/`SessionBoundary`/`UnrecognizedMessage`. `holdsBanner` is
the one shared predicate — the history merge, the live lane's `appendBanner`, and (transitively, since
uniqueness is what the key relies on) `ThreadRow.listKey()` all read it.

A stale KDoc line on `withHistoryEntry` counted "the six structured types [interactive gates]"; this
ticket found it already inaccurate before the change and dropped the count entirely rather than bumping
it to seven — a specific number invites falling out of sync again the next time an arm is added.

## The row composable

```kotlin
@Composable
fun BannerNoticeRow(item: ThreadItem.Banner, agent: ConversationAgent, modifier: Modifier = Modifier)
```

Stateless, single `Row` inside the [`MessageContentGutter`](message-bubble.md), no bubble fill —
borrows the thread's `Session reset` body-small treatment, since Figma 16:8 has no dedicated notice
component. A `BannerLevel.Warning` row takes the theme's existing `colorScheme.warning` token for both a
leading 16dp `Icons.Outlined.WarningAmber` (content description `cd_thread_banner_warning`, "Warning from
%1$s") and the text; every other level uses `onSurfaceVariant` and draws no icon — the two read apart
without relying on colour alone. The `Text` is one `AnnotatedString` built from three parts, in order: the
client-owned `thread_banner_attribution` ("%1$s: ", the conversation's agent name from `agentName()`,
[#1113](https://github.com/pyrycode/pyrycode-mobile/issues/1113); "Claude: " for a Claude conversation) in
`FontWeight.Medium`, `bannerDisplayText(item.text)` in the row's normal weight, and — only when
`item.truncated` — the client-owned `thread_banner_truncated` (" (truncated)") in `FontStyle.Italic`. No
`SelectionContainer`, no markdown, no link detection, no click, and no second length cap beyond the
daemon's 4 KiB bound.

`bannerDisplayText(text: String): String` is the pure render-boundary stripping function, mirroring
desktop's set without its prefix/suffix concerns: OSC strings and DCS/SOS/PM/APC strings (terminated or
running to end-of-string when unterminated, so a truncated escape sequence cannot leak a tail), CSI
sequences, other two-byte ESC sequences, C0 controls except `\t`/`\n`/`\r`, and DEL/C1 controls. Covered
by `BannerDisplayTextTest` (JVM) across CSI (7-bit and 8-bit), OSC with both `BEL` and `ST` terminators,
an unterminated OSC, DCS/APC strings, other ESC sequences, C0/C1/DEL controls, a lone trailing `ESC`, and
that plain text plus tab/newline/carriage-return survive untouched.

### Security — why the attribution is its own span

`item.text` is claude-authored and unsanitized; the realistic abuse the protocol names is text at
`warning` impersonating daemon chrome. The attribution (`"<agent>: "`, e.g. "Claude: " or "Codex: ") is a
separate `SpanStyle` in a distinct weight, appended before `bannerDisplayText(item.text)` rather than
concatenated into one plain string — claude can type the literal characters `"Claude: "` into `text`, but
it cannot restyle a span, so the attribution cannot be forged from inside the banner's own text. **Keep it
a separate styled span in any future edit to this row.** Unicode bidi/format-character stripping
(U+202A–U+202E, U+2066–U+2069) is deliberately out of scope, matching desktop — such characters can only
reorder glyphs inside a row whose attribution span is client-owned, and there is no observed abuse to
justify the added complexity (see [Development verification](development-verification.md) on
evidence-based fix selection).

**The agent name stays client-owned ([#1113](https://github.com/pyrycode/pyrycode-mobile/issues/1113)).**
The name inside the attribution and the warning content description comes from `agentName(agent):
String` (`components/AgentName.kt`), an exhaustive `when` over the closed `ConversationAgent` enum
resolving one of two string resources (`agent_name_claude`, `agent_name_codex`) — never text the daemon
sent. `agent` is a **required** parameter on this row (no default), so a future call site cannot silently
credit Claude. `ConversationAgent` comes from `Conversation.agent` ([#1108](https://github.com/pyrycode/pyrycode-mobile/issues/1108))
via `ThreadUiState.agent`, set in `ThreadViewModel`'s existing conversations `combine` (`conv?.agent ?:
ConversationAgent.Claude`); `ThreadScreen` passes `state.agent` straight through. With a Claude
conversation every string renders byte-for-byte as before #1113. This is a **different idiom** from the
one `ApiRetryIndicator`/`CompactingIndicator`/`ThinkingIndicator` use for the same job — see [Turn-outcome
indicator § The agent name](turn-outcome-indicator.md#the-agent-name-1113) for both idioms and why the
divergence is a non-blocking NIT, not a defect. [`ModelRefusalRow`](model-refusal-row.md) reuses this same
`agentName()` / `thread_banner_attribution` pair for its own opened explanation.

## `ThreadRow` / `ThreadScreen` wiring

All four of the exhaustive `when`s over `ThreadItem` gained a `Banner` arm:

- **`ThreadRow.listKey()`** — `"banner:$occurredAt"`; unique because `holdsBanner` is.
- **`ThreadScreen`'s `LazyColumn` render** — `BannerNoticeRow(item = item, agent = state.agent)` (`agent`
  since #1113), inside the same `rowAlpha`-driven `Box` as its neighbours, so above-delimiter dimming
  applies with no new code. A banner row is **not** a session boundary for
  `mostRecentSessionBoundaryIndex` — unchanged.
- **`ThreadItem.timestamp()`** — `occurredAt`.
- **`RemoteConversationRepositoryTest.threadShape()`** — the test-fixture helper outside production code
  that also needs every `ThreadItem` arm to keep compiling, the same fourth site #608's Lessons learned
  named.

## Cache — never persisted

`cacheableThreadRows` ([Conversation cache](conversation-cache.md)) filters out `ThreadItem.Banner`
alongside `ThreadItem.UnrecognizedMessage` — claude-authored prose never reaches app-private disk.
`FileConversationCache.toRecord` throws `IllegalStateException("banner rows are never cached")` if it
ever receives one, the same defensive-throw shape as the unrecognized-row arm. `settledThreadRows` (the
narrower "may still draw, connection gone" filter) keeps banner rows, so a thread that has lost its
connection still shows them; only `cacheableThreadRows` (the "may reach disk" filter) drops them. History
replay is what restores a banner after a cold start or a fresh cache — not the cache.

## Testing

- `BannerDisplayTextTest` (JVM, new): the stripping-set coverage listed above.
- `RemoteConversationRepositoryTest`, `banner_*` block: text/level/truncated/`occurredAt` fold verbatim
  from a live envelope; `warning` → `Warning` and every other tested value (`info`, `notice`,
  `suggestion`, empty, an unrecognized string) → `Notice`; arrival-order interleaving with messages and
  strict `conversation_id` routing; a repeated `ts` folds once while a distinct `ts` folds twice; a
  `stops_turn: true` banner leaves an existing stall, `ApiRetryStatus`, compacting state, and
  `liveSessionEvents` untouched; a missing field, a wrong-typed field, or a malformed `ts` drops only that
  one frame while a later well-formed banner still folds; the gate closed or set to an unrelated
  capability folds nothing. `threadShape()` gained a `Banner` arm.
- `HistoryPageReducerTest`: a stored banner reduces to the same row stamped with the entry's own
  timestamp; a non-interactive reduction yields nothing; a malformed entry costs only that entry; a page
  whose banner the live thread already holds merges to one row; a repeated `ts` within one page yields
  one row.
- `FileConversationCacheThreadTest`: `cacheableThreadRows` drops banner rows.
- `BannerNoticeRowTest` (Compose instrumented, `app/src/androidTest/.../components/`): the attribution +
  sanitized text render as one string with escapes gone; a truncated row carries the mark and an
  untruncated one does not; a `Warning` row exposes `cd_thread_banner_warning` and a `Notice` row does
  not; the row has no click action even when its text contains something that looks like a link. Since
  #1113 every case passes `agent = ConversationAgent.Claude` and keeps asserting the literal "Claude: "
  copy — the "reads exactly as today" guard.
- **No rung-4 scripted twin.** The daemon has no scripted `banner` emitter, so this ticket added none;
  live verification against a real claude is [#679](https://github.com/pyrycode/pyrycode-mobile/issues/679),
  per the ticket.
- **#1113**: the new sharedTest `ThreadAgentAttributionTest` (Robolectric, through `ThreadScreen`) covers
  a Codex conversation's warning banner — "Codex: …" text, "Warning from Codex" content description — and
  a Claude conversation rendering unchanged, proving the `state.agent` wiring end to end.

## Related

- Ticket notes: [`../codebase/873.md`](../codebase/873.md). #1113 postdates the 2026-09-05 codebase-archive
  freeze and has no per-ticket note.
- Spec: [`docs/specs/architecture/873-banner-notice-row.md`](../../specs/architecture/873-banner-notice-row.md)
  (design + security review, verdict PASS) ·
  `docs/specs/architecture/1113-agent-name-in-thread-notices.md` (the `agent` param, § Security above).
- Wire SSOT: `pyrycode/docs/protocol-mobile.md` § `banner`, § *Joining a page to the live stream* (sibling
  checkout).
- Desktop sibling: pyrycode-desktop `ConversationScreen.tsx`'s `bannerDisplayText` and `banner` timeline
  arm.
- Structural precedent: [`UnrecognizedMessageRow`](unrecognized-message-row.md) — same three-site landing
  shape (type + live decode + history decode + row in one ticket), but the opposite dedup posture; read
  both before adding a fourth `ThreadItem` variant that carries a wire-minted identity.
- Consumers: [`Conversation repository`](conversation-repository.md) (`ThreadItem`, co-located types),
  [`Remote conversation repository`](remote-conversation-repository.md) (live decode), [`Thread
  screen`](thread-screen.md) (`LazyColumn` key, render arm, `timestamp()`), [`Conversation
  cache`](conversation-cache.md) (excluded from persistence).
