# Thread composer footer — context usage segment

Split out of [Thread composer footer](thread-composer-footer.md) on 2026-09-25 to keep that document
under the 50000-byte size cap the docs guard enforces. The computed-source section retains its heading and anchor.

## Context usage segment (#946, computed since #1411)

`ThreadRunConfig.contextPercent: Int?` is an independent reading folded into `runConfigFlow` from
`repository.observeContextUsage(conversationId)` and eligible session settings. The same configuration
carries the held reading and selected used/maximum token pair for the Context sheet.
Since [#1411](https://github.com/pyrycode/pyrycode-mobile/issues/1411)
the shown percentage is **computed, not Claude's verbatim `percentage`**, reversing #946's original rule: the
top-level `contextPercent(usage, settings)` function (bottom of `ThreadViewModel.kt`) takes `usage.totalTokens`
/ `.maxTokens` while a reading exists — whatever it holds, even a zero `maxTokens`, which still yields `null`
rather than falling back — and otherwise `settings.usedTokens` / `.windowTokens`. It rounds half up to a whole
percent and clamps to 0–100, matching desktop's `contextTokenSource` + `contextUsagePercent`. `null` is the
unavailable state: neither source, or a window `<= 0` in the one source used. `runConfigFlow` threads
`SessionSettings` through its chained combines as a pair so this fallback is available without subscribing to
`sessionSettings` a second time (its `onEach` has side effects on pending state). See [Conversation repository
§ `observeContextUsage`](conversation-repository-shape.md#shape) for the reading's own contract, now preferred over
the settings pair rather than mutually exclusive with it.

**Session freshness, #1947.** Replacing a session clears the previous context reading and its pending requests
before reset completion can send the replacement request. Settings token counts are a fallback only when their
nonempty session identity matches the conversation's current session. Both the footer and Context window
remain unavailable while no eligible reading or matching settings exist. A valid context reply can render
before the replacement settings arrive.

Context supplied by admitted authoritative live-session state retains that source proof. Other readings after
a replacement need the current producer identity or a reply to this connection's post-replacement request. A
conflicting producer is rejected even when the request identity matches. Remembered readings measured before
the latest replacement remain rejected across reconnects. Request identities belong to their connection, so a
reused number cannot validate another connection's reply.

Legacy post-turn pushes have no producer identity. After a replacement-session turn starts, an unowned push
signals one correlated current-session request. The push itself never supplies the displayed value. Signals
coalesce while that request is pending. Its successful reply updates the reading. A matching terminal error or
rejected old remembered reply releases that request so a later turn can refresh again. Sending happens outside
the reading monitor. Other conversations keep their own readings.

Since [#2321](https://github.com/pyrycode/pyrycode-mobile/issues/2321), tapping the context circle before
Actions opens the standalone read-only Context sheet, even when its percentage is unavailable.
The client-owned accessible action label is “Open context”. Its physical target is at least 48 × 48 dp,
using leading blank space and the gap before Actions without overlapping that action.
The 15 × 15 dp circle is top-aligned inside a 15 × 16 dp slot, with a 2 dp stroke. The
`primaryContainer` track is drawn first and stays dark at every reading. The used arc starts at the top
(-90 degrees) and grows counterclockwise with a negative sweep proportional to the computed percentage.
Zero and unavailable readings draw no used arc; 100% draws a complete used ring. See
[footer geometry](thread-composer-footer.md#trailing-icons-stay-outside-the-weighted-text-region-1032-wrap-shape-1549)
for the fixed slot and reserved trailing controls.

Reserving the full touch height adds 20 dp to the measured composer above the visible icon band.
Automatic expansion of the old 28 dp box extended below the window and made bottom-edge taps miss.
Keep the ring aligned with the footer icons while reserving the target inside the window. This height
also affects the task pill, editor, empty-state center and newest-message/status gap. At fractional
density, subtract separately rounded pixel values for `ComposerTopGap` and
`ordinaryMessageRestAdjustment` from the measured composer height; rounding the combined dp
subtraction fails to match Compose's actual padding.

### Context sheet (#2321)

`ContextSummarySheet` uses [the shared dismiss modal](mobile-modal.md): Context title, close X,
separator, scrolling body and pinned Done action. Close, Done and Android Back dismiss it.
`ThreadViewModel` owns visibility in `ThreadUiState`; an open sheet updates with the current reading,
survives recollection of that ViewModel and obstructs thread read-sight qualification. A new destination
ViewModel starts closed.

| Reading | Body |
|---|---|
| Claude, no held reading | Only “A context reading arrives after the next turn.”, even if settings supply a footer percentage. |
| Claude, fresh reading | Model, “X of Y tokens”, computed footer percentage or “Not reported yet”, then Categories when there are rows or a positive dropped count. |
| Claude, remembered reading (`asOf` present) | Held scalar header only. Categories stay hidden even if the received reading contains them; missing stored detail is not a fresh empty breakdown. |
| Codex, eligible token source with a positive maximum | Totals and the same computed percentage as the footer. Model and categories are hidden. |
| Codex, no usable source | The pending line. |

Fresh category rows retain received order and show a name, token figure and 4 dp bar scaled to
`maxTokens`. A positive dropped count adds “+N more”, including when the list is empty; an empty list
with no positive dropped count hides Categories. Model and category names ellipsize to one line.
Token figures follow desktop's one-decimal thousands formatting (`12.4k`, `12k`, without `.0`).
`contextBarPercent` converts to Double before arithmetic, clamps to 0–100 and returns zero for a
nonpositive maximum. Counts never control allocations.

The [design inventory](../../../app/src/androidTest/assets/design-1220/README.md#context)
links the loaded, waiting and Codex frames from #2224. The 2026-10-19 decision allows Codex to open
this sheet with totals only. MCP-tool inventories (#2322), memory-file inventories (#2323) and allowance
(#2226) have their own scope; this summary has no placeholder rows for them.

### Decode boundary and coverage

The authoritative wire contract remains pyrycode's `docs/protocol-mobile.md`, section `context_usage`.
The shared decoder retains model, ordered categories and dropped count. Required summary fields
must be present with their correct primitive kinds; missing/null lists or fields and stringified token
counts reject the frame while preserving the held reading. Empty lists, an empty model and unknown
extra keys remain valid. Existing scalar validation, timestamp parsing and conversation routing apply.
Model/category text is bounded to 512 characters and rendered only as inert Text, never as logs,
exception output, paths, actions or identity keys. DTO/domain string representations redact it.
Defaulted domain fields preserve in-process scalar-only constructors; wire fixtures must still be complete.

`ThreadReadEvidence` uses the same decoder. When required wire fields change, complete intended-valid
fixtures imported by main merges too: otherwise a rejection test can pass at shape validation without
ever exercising freshness or ownership. `RemoteConversationRepositoryContextUsageTest` and
`ContextUsageProjectionTest` cover decoding/retention; `ThreadViewModelContextUsageTest` covers visibility,
source selection, updates and clearing. `ContextSummarySheetTest` exercises the actual footer opener,
all three dismissals, target edges and adjacent Actions routing, pending/loaded/updating/remembered/Codex
states, scrolling, ellipsis and clamped bars. Keep composer geometry checks alongside these pointer tests;
neither proves the other's contract.

Linux acceptance at `c7e0aa32779930dfcd832ee806fc86df2817df1f` on 2026-10-20 passed in the complete
dispatcher gates: JVM/shared **6,221 executed / 6,221 passed / 0 failed / 0 skipped**; device
**335 executed / 335 passed / 0 failed / 1 skipped**; scripted **22 executed / 22 passed / 0 failed /
0 skipped**. The fresh XML confirms all eight `ContextSummarySheetTest` and eleven
`ThreadViewModelContextUsageTest` methods passed, including the opener/dismissal, updating/clamping,
remembered and Codex scenarios above. `ThreadDesignCaptureTest#footerAt320By700` passed in the device
gate; `RenameDialogCaptureTest#renameAtFigmaViewport` was skipped. Raw reports were retained at
`/tmp/verifier-2324/current-green/` in `jvm-gtoqemwt/`, `ui-q_nhusa5/` and `scripted-all-9_0go5p3/`.
The [accepted verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/2324#issuecomment-6286505096)
records the counts and scope. No fresh Context-sheet raster comparison or real-Claude turn was run;
macOS was outside this ticket's scope. The historical scripted measurement failure remains a failed
capture with unproved cause; the later complete pass does not establish a causal repair.

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
must send the first ask. Since [#1761](https://github.com/pyrycode/pyrycode-mobile/issues/1761),
the existing settings-reread collector also asks once on each `reset_end` for this conversation: an active
reset becoming idle. Initial idle, reset-start phases, repeated idle and host turn endings add no context
ask; host turn endings still reread settings alone. This refreshes the reply-backed footer after reset
without another message. The current-session settings filter prevents the stale fallback described by the earlier implementation.
Reconnect and reset-ended asks log static reasons (`event=context_usage_ask reason=reconnect` or
`reason=reset_end`, never the id); logging the opening ask too broke screen tests that construct `ThreadViewModel` without
stubbing `RelayLog.sink`. A refusal (`conversation.not_found` or `context_usage.unavailable`) is an
`error` that retires only the matching context request. The held reading stays as it was and nothing surfaces.
A successful answer uses the existing context decoder, then passes the freshness checks described above. A
conversation still
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
reset proof added in #1761 and strengthened in #1947,
`InteractiveStreamE2ETest#interactiveTurn_newSession_rendersSessionBoundaryDelimiter`, observes invalidation
before the reset tap. Its passive witness binds the additional request to the same connection and replacement,
rejects old producers and old remembered values, and checks the exact admitted repository reading. The footer
and Context window must show that reading without another phone message. Equal rounded percentages cannot
establish freshness. Deterministic coverage also checks later legacy turns, unavailable reset replies,
current-session fallback and conversation isolation. The
older `interactiveTurn_pingPrompt_footerShowsContextUsage` has the same blind spot and predates this fix;
it is not itself proof that an ask was sent, only that a reading — pushed or asked for — is showing.

**Same percentage across surfaces.** The Context summary and
[`StatusSheet`](status-sheet-readings.md#contextwindowsection)'s Context-window section read the identical
`ThreadRunConfig.contextPercent` as the footer. All use the one
`contextPercent(usage, settings)` result computed in `runConfigFlow`, never
Claude's `percentage` directly. No rung-4 twin: whether the scripted `fakeclaude` path makes the daemon publish
`context_usage` is not established.

## Related

Part of [Thread composer footer](thread-composer-footer.md); see that document for the component's shape,
sourcing and wiring, and [§ Trailing icons stay outside the weighted text
region](thread-composer-footer.md#trailing-icons-stay-outside-the-weighted-text-region-1032) for the
[#1032](https://github.com/pyrycode/pyrycode-mobile/issues/1032) layout that now guarantees the paperclip
and the Status opener their width regardless of this segment.
