# Thread composer footer — context usage segment

Split out of [Thread composer footer](thread-composer-footer.md) on 2026-09-25 to keep that document
under the 50000-byte size cap the docs guard enforces. This section moved here verbatim, except for the
`ContextSegment` layout paragraph corrected below, and kept its heading, so its anchor is unchanged.

## Context usage segment (#946)

`ThreadRunConfig.contextPercent: Int?` is a third independent reading, joined the same way [§ Running
model](thread-composer-footer.md#running-model-891) is: `runConfigFlow` folds it in with one more
`.combine(repository.observeContextUsage(conversationId)) { config, usage -> config.copy(contextPercent =
usage?.percentage) }` after the `runningModel` combine — a third link, not a sixth arm of the five-arm
`combine` (already at Kotlin's typed ceiling). It carries Claude's reported `percentage` verbatim; `null`
is the unavailable state, never `0%`, and it is never derived from `SessionSettings.usedTokens` /
`.windowTokens`. See [Conversation repository § `observeContextUsage`](conversation-repository.md#shape)
for the reading's own contract.

A private `ContextSegment(percent: Int?)` renders Figma's `Cxt: 84%` text node (`110:3497`) — it is
**plain text, not a `FooterControl`**: it opens no overlay and carries no click action. It is the last
child inside `FooterTextRow` (see [Thread composer footer § Trailing icons stay outside the weighted text
region](thread-composer-footer.md#trailing-icons-stay-outside-the-weighted-text-region-1032)), which
gives it whatever width the buttons ahead of it leave, possibly none — it still ellipsizes (`maxLines =
1`, `TextOverflow.Ellipsis`) rather than push anything else off the row. Before
[#1032](https://github.com/pyrycode/pyrycode-mobile/issues/1032) it carried its own `Modifier.weight(1f)`
in the (then flat) outer `Row`, in place of the `Spacer` that used to fill it, with the paperclip and the
Status opener placed after it in source order. That placement did not actually protect the two icons: a
`Row` measures its non-weighted children in source order regardless of what a sibling's weight claims, so
a wide enough set of button labels could still starve them to nothing — [#1032](https://github.com/pyrycode/pyrycode-mobile/issues/1032) fixed this by moving the weight one level up, onto
`FooterTextRow` itself, so the two trailing icons are always measured before any text control.

**No ask, since Rework 1.** Subscribing to this reading sends nothing to the daemon. #945 originally had
`ContextUsageProjection` ask (`request_context_usage`) on the 0→1 subscriber edge, but this ticket's own
PR #970 was the reading's first production subscriber, and the daemon's `handleRequestContextUsage`
blocks the connection's serial frame worker until the open turn ends — a mid-turn ask deadlocked the
scripted `reconnect` scenario. The ask was removed outright; the reading now arrives only from the
daemon's post-turn `context_usage` push. A conversation shows `Cxt: n/a` until its next turn ends on the
current connection, including an idle conversation opened for the first time. See [Remote conversation
repository — live stream, modal seams and the replay cursor §
`context_usage`](remote-conversation-repository-live-stream-and-modals.md#context_usage--the-context-usage-reading-945)
for the wire contract and pyrycode/pyrycode#2563, the daemon fix that would let a future ticket restore
the ask.

**Same value, two surfaces.** [`StatusSheet`](status-sheet-readings.md#contextwindowsection)'s Context-window
section reads the identical `ThreadRunConfig.contextPercent`, so the footer and the sheet cannot
disagree. No rung-4 twin: whether the scripted `fakeclaude` path makes the daemon publish `context_usage`
is not established.

## Related

Part of [Thread composer footer](thread-composer-footer.md); see that document for the component's shape,
sourcing and wiring, and [§ Trailing icons stay outside the weighted text
region](thread-composer-footer.md#trailing-icons-stay-outside-the-weighted-text-region-1032) for the
[#1032](https://github.com/pyrycode/pyrycode-mobile/issues/1032) layout that now guarantees the paperclip
and the Status opener their width regardless of this segment.
