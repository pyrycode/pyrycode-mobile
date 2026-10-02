# Thread composer footer — context usage segment

Split out of [Thread composer footer](thread-composer-footer.md) on 2026-09-25 to keep that document
under the 50000-byte size cap the docs guard enforces. This section moved here verbatim, except for the
`ContextSegment` layout paragraph corrected below, and kept its heading, so its anchor is unchanged.

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
after `/clear` the footer and Status sheet can briefly show a non-`n/a` percentage for a session that no
longer exists, where before #1411 they showed `n/a`. `ThreadRunConfig.forLiveSession` exists to hide exactly
this kind of stale-settings window for session-scoped facets (it already blanks `permissionMode`,
`appliedEffort` and `memorySearch`), but `contextPercent` was not added to it — desktop's own
`runConfigStore.clearSnapshot` only fires on a conversation switch, delete or archive, never on a transition,
so this matches desktop but diverges from mobile's own stricter rule. Unresolved as of #1411; a future ticket
should either null the settings fallback when `settings.sessionId` differs from the live `currentSessionId`,
or record a deliberate decision to accept the gap.

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

### Colour steps at 50 and 70 percent (#1412, after desktop #1062)

`contextUsageStep(percent: Int)` mirrors desktop's `contextUsageStep`: below 50 is `Normal`, 50 to 69 is
`Warning`, 70 and above is `High`. As on desktop, the thresholds and colours are the operator's ruling, not
a design state — Figma `110:3497` shows only the ordinary 42% reading. `ContextSegment` picks text,
description and colour in one `when` over the step: `Normal` and `Warning` both read `"Cxt: N%"` (`primary`
or `ColorScheme.warning`, from `ui/theme/WarningColors.kt`), and `High` reads `"Cxt high: N%"`
(`colorScheme.error`) with the content description "Context usage high, N%". The text style
(`bodySmall`) does not change at any step. The unavailable state (`"Cxt: n/a"`, `onSurfaceVariant`) is a
separate branch, untouched by the step.

**The high text no longer ellipsizes at 320dp and 1.5× — it wraps under Actions instead ([#1549](https://github.com/pyrycode/pyrycode-mobile/issues/1549)).**
Before #1549, `"Cxt high: N%"` was long enough that at a 320dp-wide footer with 1.5× font scale it
ellipsized, relying on the content description (`"Context usage high, 84%"`) to carry the full figure. The
design decision on [#1485](https://github.com/pyrycode/pyrycode-mobile/issues/1485) (2026-10-02) replaced
that trade-off: when Actions, the label and the trailing paperclip/tune icons do not fit one row at their
natural widths, `FooterTextRow` moves the label whole to its own line under Actions instead of shrinking or
ellipsizing it — see [Thread composer footer § Trailing icons stay outside the weighted text
region](thread-composer-footer.md#trailing-icons-stay-outside-the-weighted-text-region-1032) for the wrap
shape, the `FooterFirstRowBottom` alignment line and `FooterLineGap`. The label keeps `maxLines = 1` and
`TextOverflow.Ellipsis` for widths narrower than its own wrapped line can hold; #1549's acceptance scope is
150% text, where "Cxt high: 84%" fits in full, so this remains unmeasured rather than a known gap.
`ThreadComposerFooterTest` cannot catch wrapping either: it is a semantics-level test, and semantics always
hold the untruncated description regardless of what the rendered line shows. The layout claim lives in
`ThreadComposerFooterWidthTest` (a NATIVE-graphics test class): `compactWidth_highReadingFitsOnOneLine`
(320dp, default font) asserts the high text stays on one unellipsized line on the Actions row, and
`compactWidthAndEnlargedText_highReadingWrapsUnderActions` (320dp, 1.5× font, replacing the old
`…highReadingKeepsTheFigureInItsDescription`) asserts the label is not ellipsized, sits `FooterLineGap`
under Actions' visible text (measured above `contentBottomPadding`) and starts at Actions' left edge, with
the paperclip and tune bottoms still level with Actions. `pixel8_highReadingStaysOnActionsRow` (411dp, 1.0×)
covers the still-one-row shape at a realistic phone width. A reading at or above 70% also changed two older
fixed-percentage tests: the #1032 compact-width layout test `compactWidthAndEnlargedText_keepThreeActionsSeparate`
moved its reading from 84% to 37% because it is about the *ordinary* reading's layout, not the high one, and
`contextSegment_showsTheReportedPercentage_andTheSheetAgrees` now expects `"Cxt high: 84%"` since 84 is
itself a high reading. The real-Claude regexes in `InteractiveStreamE2ETest` (`CONTEXT_REPORTED` and its
users) accept `Cxt high:` too, so a live reading of 70 or more does not fail them; there is no new rung-3
scenario for the colour step or the wrap itself, since neither is a new operator flow and a live session
cannot be pushed to a specific percentage on demand.

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
shows `Cxt: n/a` only until the open thread's first ask or the next turn end resolves it, whichever comes
first — no longer only the latter. See [Relay repository coordinator § `HostReadings`](relay-repository-coordinator.md)
and [Remote conversation repository — live stream, modal seams and the replay cursor §
`context_usage`](remote-conversation-repository-live-stream-and-modals.md#context_usage--the-context-usage-reading-945)
for the wire contract.

**Live-test trap: the #1411 fallback can satisfy a check meant to prove the ask fired.** Since #1411 the
footer shows a percentage computed from `session_settings` alone whenever no `context_usage` reading
exists, so a live assertion that only waits for `Cxt: \d+%` in the footer proves nothing about whether
`request_context_usage` was ever sent — a fresh live session reads `0/200000` from the daemon's
`contextwindow.Read` and renders `Cxt: 0%`, matching that pattern with no reading at all. #1410's own live
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
