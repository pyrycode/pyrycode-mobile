# Thread composer footer — remembered effort recall

Split out of [Thread composer footer](thread-composer-footer.md) on 2026-09-24 to keep that document
under the 50000-byte size cap the docs guard enforces. This section moved here verbatim and kept its
heading, so its anchor is unchanged. Part of [Thread composer footer](thread-composer-footer.md); see
that document for the component's shape, sourcing and wiring.

## Remembered effort recall (#686)

The phone keeps **one remembered effort level app-wide**, across chats, channels and connected hosts, stored under `AppPreferences.rememberedEffort` (see [App preferences § Remembered effort key](app-preferences.md)) — not per-conversation, and unrelated to `defaultEffort`. It never appears on its own in the footer; it only ever reaches the screen by being sent through the normal effort write path and read back on the next settings reading, so `effortLabel` and `selectedEffort` above are unchanged by this feature.

`internal class EffortRecall` (`EffortRecall.kt`) is a `ThreadViewModel`-scoped collaborator, one per thread opening, constructed with the injected `RememberedEffortStore` (`RememberedEffortStore.None` on the demo path — inert by construction) and a `start: (sessionId, level) -> Job` that `ThreadViewModel.startEffortRecall` wires to `sendSessionSettings`, the same function `onEffortSelected` calls. `ThreadViewModel.state`'s combine calls `effortRecall.offer(runConfig, conv?.currentSessionId.orEmpty())` on every emission — the one place that already sees both the settings reading and the live session id, so no second `observeSessionSettings` subscription is opened.

**The decision, made lazily, at most one write per opening (#1310):**

- It waits while the store hasn't loaded, no reading has arrived yet, `!runConfig.settingsAvailable`, `!runConfig.menuAvailable`, or a model/effort write is already pending.
- It also waits while `runConfig.settingsHeld` (#1320) — a reading carried across a reconnect rather than answered by the live connection. In the disconnected gap there is no connection to write on, and at the new connection's head the reading may name a session the daemon replaced or an effort another client set meanwhile. The recall stays undecided until the new connection's own reply arrives, which decides it once, at that reply's own session id.
- It also waits while the reading is for a session the conversation has already replaced — mirroring `forLiveSession`'s own replaced-session rule: a non-empty live session id that differs from `runConfig.sessionId`, checked only when the session is writable.
- With nothing remembered, or once `runConfig.savedEffort` is non-empty (a saved choice another device made, logged `saved_choice`), the recall is terminal: `decided` is set and no later reading can revive it. Nothing remembered stays silent — no log, see below.
- `no_session` (`!runConfig.writable`) and `unpublished` (the remembered level isn't among `runConfig.effortChoices`' values — the selected row's levels, or, with no model override (`savedModel == ""`), the published `default` row's (#972, see [Thread composer footer § Sourcing](thread-composer-footer.md#sourcing))) are **waiting** conditions, not terminal skips (#1310): `decided` stays `false`, and a later reading that gains a live session, or a model change that makes the remembered level offered, is evaluated again on the next `offer`. Each waiting reading still logs its own `no_session`/`unpublished` line (see Logging, below); only a saved effort, a tap, or an eligible reading ends the recall.
- Once eligible, `decided` is set immediately before calling `start(sessionId, level)` — the recall write — through the identical path a tap uses, so a synchronous state emission or a failed/rejected write cannot start a second one: `pendingEffort` shows the level immediately, and the refreshed reading after the ack decides what the footer actually shows, exactly as [Thread composer footer § Applied effort](thread-composer-footer.md#applied-effort-889) already describes.

**Cancel.** `onEffortSelected` calls `effortRecall.cancel()` before its own guards, so a user tap made before the decision settles wins outright: the recall is marked decided with no write, and only the tap's write goes out (and only the tap's level is ever remembered from that opening).

**Remember only successes.** `ThreadViewModel.sendSessionSettings` calls `effortRecall.remember(effort)` — after the repository ack, before the `refreshSessionSettings` re-read — but only when the write carried a non-null `effort`. A model-only write, a permission write, and a failed or rejected write (the `catch` branches revert and signal exactly as before) never remember anything, whether the write was a tap or the recall itself. A rejected recall write is therefore never retried within the same opening: `decided` is already `true`, and only reopening the thread (a fresh `ThreadViewModel`, hence a fresh `EffortRecall`) tries again.

**Acknowledgement and persistence barriers in tests (#2075).** Optimistic radio
selection does not prove an acknowledged write. `awaitFooter` delegates to the
shared `awaitRunConfiguration` wait: section presence, selection, absence of that
section's applying header and its optional note must agree within one tagged
section snapshot. Another section's labels or ordinary sheet text cannot settle
the wait. Priming additionally checks the actual remembered-effort store through
`confirmed`, within the unchanged deadline. An independent `RunConfigOpen`
settings reply can clear `pendingEffort` while the acknowledged write is still
suspended in `EffortRecall.remember`; UI settlement alone cannot prove persistence.

`RunConfigurationWaitTest.optimisticEffortWaitsForAcknowledgementAndFreshReading`
drives the real ViewModel with a controlled repository: the wait stays blocked
before acknowledgement, then after persistence while the fresh reading is pending,
and completes when that reading settles. The opposite ordering is covered by
`matchingIndependentReadingCannotCompletePrimingBeforePersistence`: an independent
matching reply settles the UI while storage is held; priming completes only after
storage is released. The extracted old wait had **5 executed, 5 failed, 0 skipped**;
the reviewed section-only barrier failed the delayed-storage regression with
**1 executed, 1 failed, 0 skipped**. After the persistence repair, JVM and Android 13
wait-class reports each have **6 executed, 6 passed, 0 failed/errors/skipped**,
including both ordering tests. These use a fake repository and store, with no daemon
or Claude turns. [Retained reports and commands](../../../app/src/test/resources/e2e/2075/commands-and-results.txt)
record the controls. The [live restart proof](../../e2e-interactive-stream.md#remembered-effort-acknowledgement-barrier-2075)
is separate; neither controlled race diagnoses the archived #1869 failure.

**First message waits for the recall write.** `sendMessage` calls `effortRecall.awaitWrite()` inside its `launchGuardedRepoCall` block, before `repository.sendMessage`. `awaitWrite` joins the recall's `Job` if one was started and returns at once otherwise — it never waits on a reading or the model menu to arrive, and effort is never attached to `send_message` itself.

**Isolation.** Nothing beyond construction is needed: the repository is bound to the destination's own host, `observeSessionSettings(conversationId)` is scoped to this conversation, and the recall write addresses only the reading's own `sessionId`. Two `ThreadViewModel`s sharing one `RememberedEffortStore` each run their own independent `EffortRecall` and can only ever write to their own session.

**Merged menu and agent changes (#1118).** Both agents' rows are visible in Run
configuration, but recall still consumes `ThreadRunConfig.effortChoices` from the
conversation's own-agent selected/default metadata. Another agent's visible row
cannot make a remembered level eligible, including Codex with no saved model.
Opening a cross-agent confirmation cancels this opening's undecided recall, even
if the operator then dismisses it; a switch does not start another recall instance.
Confirmation forwards the current selected effort only if the target row publishes
it, and does not remember a new level through the switch operation.

Fresh successor settings determine target effort and permission options. A pending
or restored display label grants no settings authority. Held, old-session and
detached-connection readings cannot offer recall or enable writes after a switch;
a fresh correlated replacement reply can supply the session when the replacement
conversation summary is sessionless. Keep this admission upstream of recall and
of the outer thread's applied-effort/permission masking. See
[the model-menu agent filter](thread-screen-how-it-works-state.md#the-model-menu-agent-filter-1110)
for connection provenance and permanent display retirement. The app-wide store,
one decision per opening and success-only effort-write remembering remain unchanged.

**Logging.** Every decided outcome logs `event=effort_recall outcome=<code>` (`started`, `saved_choice`, `no_session`, `unpublished`, `cancelled_by_tap`, `read_failed`) by static code only — never the level or the session id. The one exception: deciding to send nothing because nothing is remembered logs nothing at all, and neither does a cancelling tap while nothing is remembered. `RelayLog` is enabled in debug unit tests and its default sink calls `android.util.Log`, which throws on a plain JVM outside Robolectric; a log call reachable from every thread opening — this decision runs inside the `state` combine — would otherwise crash the state flow of every pre-#686 `ThreadViewModel` test using the inert `RememberedEffortStore.None` default. `setRememberedEffort`'s own `event=remembered_effort_set outcome=success|io_failure` log (in `AppPreferences`) is unconditional and never level-bearing, so it needed no such carve-out.

No visual change: this rides the same pending/settled footer states [Thread composer footer § Applied effort](thread-composer-footer.md#applied-effort-889) already renders. The live recall and restart proof is [#545](https://github.com/pyrycode/pyrycode-mobile/issues/545)'s `interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel` — see [e2e coverage](../../e2e-interactive-stream.md).

## Related

Part of [Thread composer footer](thread-composer-footer.md); see that document for the component's shape, sourcing and wiring. See also [App preferences § Remembered effort key](app-preferences.md) for the `rememberedEffort` storage key and `EffortRecall`'s `RememberedEffortStore` adapter.
