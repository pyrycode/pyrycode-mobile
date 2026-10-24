# Banner notice row

## Supplied content route

The existing banner presentation is fed by daemon-built thread items. Canonical
identity, revision, epoch and order remain supplied. Raw frame decode/fold and
`HistoryPageReducer`/`ThreadProjection` reconstruction were removed in #2367.
The component still renders through its existing `ThreadItem` presentation adapter;
literal component fixtures do not recreate a production content route. See
[store ownership](remote-conversation-repository-reads-and-thread-store.md) and
[authoritative rendering](thread-screen-how-it-works-list-and-status-row.md#supplied-authoritative-rendering-1985).

## The row composable

```kotlin
@Composable
fun BannerNoticeRow(item: ThreadItem.Banner, agent: ConversationAgent, modifier: Modifier = Modifier)
```

Stateless bare `Text` in the message stream, with no bubble, border or icon. The [thread notification component](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=620-1576) and [session warning thread](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-5466) use muted `bodyMedium` text within the message gutter. `BannerLevel.Warning` starts with a separate client-owned, medium-weight `Warning · ` span; both levels then show the conversation agent's client-owned attribution as its own medium-weight span, followed by `bannerDisplayText(item.text)` and the italic truncation mark when needed. One full-width `Text` handles wrapping within the gutter. It is inert: no markdown, links, selection container or click action. The daemon's 4 KiB bound remains the only length cap.

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
The name inside the attribution comes from `agentName(agent):
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

## Presentation and persistence

The supplied renderer selects this component using canonical item identity/revision.
It does not join live/history rows by `(type, ts)` or reconstruct content from raw
frames. Authoritative cache persistence retains supplied facts and certification;
dormant serialized row support remains for #2364. Component styling, text spans and
gestures remain unchanged.

## Testing

The dedicated raw DTO/reducer/live-history tests listed in the original coverage
below were retired in #2367. These references document historical coverage; current
supported fixtures supply authoritative facts or literal rows and retain presentation,
privacy, viewport and control assertions.


- `BannerDisplayTextTest` (JVM, new): the stripping-set coverage listed above.
- `RemoteConversationRepositoryTest`, `banner_*` block: text/level/truncated/`occurredAt` fold verbatim
  from a live envelope; `warning` → `Warning`, `info` → `Info` (#1359), and every other tested value
  (`notice`, `suggestion`, empty, an unrecognized string) → `Notice`; arrival-order interleaving with messages and
  strict `conversation_id` routing; a repeated `ts` folds once while a distinct `ts` folds twice; a
  `stops_turn: true` banner leaves an existing stall, `ApiRetryStatus`, compacting state, and
  `liveSessionEvents` untouched; a missing field, a wrong-typed field, or a malformed `ts` drops only that
  one frame while a later well-formed banner still folds; the gate closed or set to an unrelated
  capability folds nothing. `threadShape()` gained a `Banner` arm.
- `HistoryPageReducerTest`: a stored banner reduces to the same row stamped with the entry's own
  timestamp; a non-interactive reduction yields nothing; a malformed entry costs only that entry; a page
  whose banner the live thread already holds merges to one row; a repeated `ts` within one page yields
  one row; every level but `warning` reduces to `Notice` except `info`, which reduces to `Info` (#1359) —
  `reduceHistoryPage` returns a page newest-first and the reducer reverses it, so in a per-level fixture
  the first (newest) entry ends up last in the asserted order.
- `FileConversationCacheThreadTest`: `cacheableThreadRows` drops banner rows.
- `BannerNoticeRowTest` (Compose shared screen test, `app/src/sharedTest/.../components/`): the attribution +
  sanitized text render as one string with escapes gone; a truncated row carries the mark and an
  untruncated one does not; a `Warning` row has the client-owned `Warning · ` prefix while a `Notice` row does
  not; the row has no click action even when its text contains something that looks like a link. Since
  #1113 every case passes `agent = ConversationAgent.Claude` and keeps asserting the literal "Claude: "
  copy — the "reads exactly as today" guard.
- **No dedicated banner-rendering rung-4 twin.** The original banner ticket added none;
  live verification against a real claude is [#679](https://github.com/pyrycode/pyrycode-mobile/issues/679),
  per the ticket.
- **#1113**: the new sharedTest `ThreadAgentAttributionTest` (Robolectric, through `ThreadScreen`) covers
  a Codex conversation's warning banner — "Warning · Codex: …" text — and
  a Claude conversation rendering unchanged, proving the `state.agent` wiring end to end.
- **#1359**: `ThreadBannerLevelTest` (Robolectric shared screen test, through `ThreadScreen`) holds an
  `Info`, a `Notice` and a `Warning` banner in one thread — the info text does not exist, the notice and
  warning texts are displayed — proving the skip is in the render arm, not the mapping.

`infoBanners_preserveLiveHistoryAndReconnectSegments` covers live extension, page reduction,
both history/cache merge directions and repeated invisible evidence. The read-mark control
`nonvisualBannerTailRetainsPresentedStreamingContentClaims` keeps the reply streaming through
Info metadata, retains checkpoint `72uL`, then settles on turn end. The enabled
[queued-echo reproduction](../../e2e-interactive-stream.md#legacy-queued-follow-up-continuity-2078)
provides transport-level continuity evidence without claiming visible banner acceptance.

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
- Sibling row: [`StoppedTurnRow`](stopped-turn-row.md) (#1356) — same bare muted-text treatment, but keyed
  on `turnId` rather than a wire-minted `(type, ts)`, since a turn ends once.
- Consumers: [`Conversation repository`](conversation-repository.md) (`ThreadItem`, co-located types),
  [`Remote conversation repository`](remote-conversation-repository.md) (live decode), [`Thread
  screen`](thread-screen.md) (`LazyColumn` key, render arm, `timestamp()`), [`Conversation
  cache`](conversation-cache.md) (excluded from persistence).
