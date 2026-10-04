# Thread composer footer — context usage segment

Split out of [Thread composer footer](thread-composer-footer.md) on 2026-09-25 to keep that document
under the 50000-byte size cap the docs guard enforces. The computed-source section retains its heading and anchor; rendering now follows the #1660 circle.

## Context usage segment (#946, computed since #1411)

`ThreadRunConfig.contextPercent: Int?` is a third independent reading, joined the same way [§ Running
model](thread-composer-footer.md#running-model-891) is: `runConfigFlow` folds it in with one more
`.combine(repository.observeContextUsage(conversationId)) { config, usage -> config.copy(contextPercent =
contextPercent(usage, settings)) }` after the `runningModel` combine — a third link, not a sixth arm of the
five-arm `combine` (already at Kotlin's typed ceiling). Since [#1411](https://github.com/pyrycode/pyrycode-mobile/issues/1411)
the shown percentage is **computed, not Claude's verbatim `percentage`**, reversing #946's original rule: the
top-level `contextPercent(usage, settings)` function (bottom of `ThreadViewModel.kt`) takes `usage.totalTokens`
/ `.maxTokens` while a reading exists — whatever it holds, even a zero `maxTokens`, which still yields `null`
rather than falling back — and otherwise `settings.usedTokens` / `.windowTokens`. It rounds half up to a whole
percent and clamps to 0–100, matching desktop's `contextTokenSource` + `contextUsagePercent`. `null` is the
unavailable state: neither source, or a window `<= 0` in the one source used. `runConfigFlow` threads
`SessionSettings` through its chained combines as a pair so this fallback is available without subscribing to
`sessionSettings` a second time (its `onEach` has side effects on pending state). See [Conversation repository
§ `observeContextUsage`](conversation-repository.md#shape) for the reading's own contract, now preferred over
the settings pair rather than mutually exclusive with it.

**Known gap: a stale figure survives a session transition.** The repository clears the `context_usage`
reading on a `session_transition`, but the replaced session's `SessionSettings` stays current until the
re-requested reply lands, so `contextPercent` falls back to the *old* session's token pair for that gap —
after `/clear` the footer and Status sheet can briefly show a available percentage for a session that no
longer exists, where before #1411 they showed unavailable. `ThreadRunConfig.forLiveSession` exists to hide exactly
this kind of stale-settings window for session-scoped facets (it already blanks `permissionMode`,
`appliedEffort` and `memorySearch`), but `contextPercent` was not added to it — desktop's own
`runConfigStore.clearSnapshot` only fires on a conversation switch, delete or archive, never on a transition,
so this matches desktop but diverges from mobile's own stricter rule. Unresolved as of #1411; a future ticket
should either null the settings fallback when `settings.sessionId` differs from the live `currentSessionId`,
or record a deliberate decision to accept the gap.

A private `ContextSegment(percent: Int?)` renders an inert context circle before Actions
since [#1660](https://github.com/pyrycode/pyrycode-mobile/issues/1660). It has no click action or overlay.
The 15 × 15 dp circle is top-aligned inside a 15 × 16 dp slot, with a 2 dp stroke. The
`primaryContainer` track is drawn first and stays dark at every reading. The used arc starts at the top
(-90 degrees) and grows counterclockwise with a negative sweep proportional to the computed percentage.
Zero and unavailable readings draw no used arc; 100% draws a complete used ring. See
[footer geometry](thread-composer-footer.md#trailing-icons-stay-outside-the-weighted-text-region-1032-wrap-shape-1549)
for the fixed slot and reserved trailing controls.

### Colour steps at 70 and 85 percent (#1660)

`contextUsageStep` now classifies below 70 as `Normal`, 70–84 as `Warning`, and 85–100 as `High`,
replacing #1412's 50/70 thresholds. The used arc is `primary`, `ColorScheme.warning` (yellow), or
`colorScheme.error` (red), respectively. There is no visible percentage text. The
`CONTEXT_USAGE_TEST_TAG` node's content description is “Context usage N%” for normal,
“Context usage warning, N%” for warning, and “Context usage high, N%” for high. A missing reading is
“Context usage unavailable”, never 0%; both arc and description derive from the same reading.
Run configuration retains its numeric detail.

`ContextUsageCircleTest` uses NATIVE graphics to sample null, 0, 69, 70, 84, 85 and 100, including
updates, top origin, counterclockwise fill, constant track and stroke geometry. Semantics alone cannot
prove rendering: an available description can precede the hardware frame. Full-image footer captures
wait for the 84% warning description **and yellow pixels inside the circle**. ATD geometry remains
geometry evidence when its framebuffer is blank. Compact-width tests replace the retired percentage
wrapping checks; see [footer testing](thread-composer-footer-testing.md#testing).

**The open thread asks again, since [#1410](https://github.com/pyrycode/pyrycode-mobile/issues/1410).**
\#945 originally had `ContextUsageProjection` ask (`request_context_usage`) on the 0→1 subscriber edge, but
\#946's own PR #970 was the reading's first production subscriber, and the daemon's
`handleRequestContextUsage` blocked the connection's serial frame worker until the open turn ended — a
mid-turn ask deadlocked the scripted `reconnect` scenario, so #946 removed the ask outright and the
reading arrived only from the daemon's post-turn `context_usage` push. [pyrycode/pyrycode#2563](https://github.com/pyrycode/pyrycode/issues/2563)
(closed 2026-09-24) fixed the daemon side — a mid-turn ask now defers its answer to turn end instead of
holding up later frames — and [#1317](https://github.com/pyrycode/pyrycode-mobile/issues/1317) made a
pushed reading survive a reconnect, so #1410 restores the ask on top of both. It does **not** live back
in `ContextUsageProjection`: that class is pairing-scoped, held in `HostReadings` across a reconnect, so
the connection-scoped `send` could not move there without threading a live socket through a reading that
outlives it. The ask is `ConversationRepository.requestContextUsage(conversationId)` instead, fire-and-forget
in the shape of `refreshSessionSettings` — a no-op default, a no-op in `StableConversationRepository` with
no live connection, one guarded send in `RemoteConversationRepository` behind the same empty-id and
`interactive`-capability guards `askForModelMenu` uses, never retried and never logging the conversation
id. `ThreadViewModel` triggers it off `repositoryAvailable`: once when the thread opens on a live host,
and once more on each later `false → true` edge (a reconnect or the host coming back), with no
`drop(1)` — unlike the #778 history-walk restart that shares the same signal, the *opening* value here
must send the first ask. Only the reconnect ask logs (`event=context_usage_ask reason=reconnect`, never
the id); logging the opening ask too broke screen tests that construct `ThreadViewModel` without
stubbing `RelayLog.sink`. A refusal (`conversation.not_found` or `context_usage.unavailable`) is an
`error` whose `in_reply_to` matches no waiter, so it is a no-op in the existing arm — the reading stays as
it was and nothing surfaces. The successful answer is just another `context_usage` frame, routed and
applied the same way the post-turn push always was; asking adds no new decode path. A conversation still
announces unavailable only until the open thread's first ask or the next turn end resolves it, whichever comes
first — no longer only the latter. See [Relay repository coordinator § `HostReadings`](relay-repository-coordinator.md)
and [Remote conversation repository — live stream, modal seams and the replay cursor §
`context_usage`](remote-conversation-repository-live-stream-and-modals.md#context_usage--the-context-usage-reading-945)
for the wire contract.

**Live-test trap: the #1411 fallback can satisfy a check meant to prove the ask fired.** Since #1411 the
footer shows a percentage computed from `session_settings` alone whenever no `context_usage` reading
exists, so a live assertion that only waits for an accessible percentage in the footer proves nothing about whether
`request_context_usage` was ever sent — a fresh live session reads `0/200000` from the daemon's
`contextwindow.Read` and announces “Context usage 0%”, matching that pattern with no reading at all. #1410's own live
proof,
`InteractiveStreamE2ETest#interactiveTurn_reopenAfterReconnect_footerShowsContextUsageBeforeAnyTurn`
(see [e2e coverage](../../e2e-interactive-stream.md)), asserts the host's held reading is `null` before
the open and then waits on `observeContextUsage(...).filterNotNull()` itself, not only the footer. The
older `interactiveTurn_pingPrompt_footerShowsContextUsage` has the same blind spot and predates this fix;
it is not itself proof that an ask was sent, only that a reading — pushed or asked for — is showing.

**Same value, two surfaces.** [`StatusSheet`](status-sheet-readings.md#contextwindowsection)'s Context-window
section reads the identical `ThreadRunConfig.contextPercent`, so the footer and the sheet cannot
disagree — both read the one `contextPercent(usage, settings)` result computed in `runConfigFlow`, never
Claude's `percentage` directly. No rung-4 twin: whether the scripted `fakeclaude` path makes the daemon publish
`context_usage` is not established.

## Related

Part of [Thread composer footer](thread-composer-footer.md); see that document for the component's shape,
sourcing and wiring, and [§ Trailing icons stay outside the weighted text
region](thread-composer-footer.md#trailing-icons-stay-outside-the-weighted-text-region-1032) for the
[#1032](https://github.com/pyrycode/pyrycode-mobile/issues/1032) layout that now guarantees the paperclip
and the Status opener their width regardless of this segment.
