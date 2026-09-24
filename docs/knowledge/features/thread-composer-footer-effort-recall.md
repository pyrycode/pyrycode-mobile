# Thread composer footer — remembered effort recall

Split out of [Thread composer footer](thread-composer-footer.md) on 2026-09-24 to keep that document
under the 50000-byte size cap the docs guard enforces. This section moved here verbatim and kept its
heading, so its anchor is unchanged. Part of [Thread composer footer](thread-composer-footer.md); see
that document for the component's shape, sourcing and wiring.

## Remembered effort recall (#686)

The phone keeps **one remembered effort level app-wide**, across chats, channels and connected hosts, stored under `AppPreferences.rememberedEffort` (see [App preferences § Remembered effort key](app-preferences.md)) — not per-conversation, and unrelated to `defaultEffort`. It never appears on its own in the footer; it only ever reaches the screen by being sent through the normal effort write path and read back on the next settings reading, so `effortLabel` and `selectedEffort` above are unchanged by this feature.

`internal class EffortRecall` (`ThreadViewModel.kt`, same file) is a `ThreadViewModel`-scoped collaborator, one per thread opening, constructed with the injected `RememberedEffortStore` (`RememberedEffortStore.None` on the demo path — inert by construction) and a `start: (sessionId, level) -> Job` that `ThreadViewModel.startEffortRecall` wires to `sendSessionSettings`, the same function `onEffortSelected` calls. `ThreadViewModel.state`'s combine calls `effortRecall.offer(runConfig, conv?.currentSessionId.orEmpty())` on every emission — the one place that already sees both the settings reading and the live session id, so no second `observeSessionSettings` subscription is opened.

**The decision, made at most once per opening:**

- It waits while the store hasn't loaded, no reading has arrived yet, `!runConfig.settingsAvailable`, `!runConfig.menuAvailable`, or a model/effort write is already pending.
- It also waits while the reading is for a session the conversation has already replaced — mirroring `forLiveSession`'s own replaced-session rule: a non-empty live session id that differs from `runConfig.sessionId`, checked only when the session is writable.
- Once decided, it sends nothing (and stays silent — no log, see below) when nothing is remembered; sends nothing (logged `saved_choice`) when `runConfig.savedEffort` is non-empty, which covers a saved choice another device made; sends nothing (`no_session`) when `!runConfig.writable`; sends nothing (`unpublished`) when the remembered level isn't among the selected row's `effortChoices` values.
- Otherwise it calls `start(sessionId, level)` — the recall write — through the identical path a tap uses: `pendingEffort` shows the level immediately, and the refreshed reading after the ack decides what the footer actually shows, exactly as [Thread composer footer § Applied effort](thread-composer-footer.md#applied-effort-889) already describes.

**Cancel.** `onEffortSelected` calls `effortRecall.cancel()` before its own guards, so a user tap made before the decision settles wins outright: the recall is marked decided with no write, and only the tap's write goes out (and only the tap's level is ever remembered from that opening).

**Remember only successes.** `ThreadViewModel.sendSessionSettings` calls `effortRecall.remember(effort)` — after the repository ack, before the `refreshSessionSettings` re-read — but only when the write carried a non-null `effort`. A model-only write, a permission write, and a failed or rejected write (the `catch` branches revert and signal exactly as before) never remember anything, whether the write was a tap or the recall itself. A rejected recall write is therefore never retried within the same opening: `decided` is already `true`, and only reopening the thread (a fresh `ThreadViewModel`, hence a fresh `EffortRecall`) tries again.

**First message waits for the recall write.** `sendMessage` calls `effortRecall.awaitWrite()` inside its `launchGuardedRepoCall` block, before `repository.sendMessage`. `awaitWrite` joins the recall's `Job` if one was started and returns at once otherwise — it never waits on a reading or the model menu to arrive, and effort is never attached to `send_message` itself.

**Isolation.** Nothing beyond construction is needed: the repository is bound to the destination's own host, `observeSessionSettings(conversationId)` is scoped to this conversation, and the recall write addresses only the reading's own `sessionId`. Two `ThreadViewModel`s sharing one `RememberedEffortStore` each run their own independent `EffortRecall` and can only ever write to their own session.

**Logging.** Every decided outcome logs `event=effort_recall outcome=<code>` (`started`, `saved_choice`, `no_session`, `unpublished`, `cancelled_by_tap`, `read_failed`) by static code only — never the level or the session id. The one exception: deciding to send nothing because nothing is remembered logs nothing at all, and neither does a cancelling tap while nothing is remembered. `RelayLog` is enabled in debug unit tests and its default sink calls `android.util.Log`, which throws on a plain JVM outside Robolectric; a log call reachable from every thread opening — this decision runs inside the `state` combine — would otherwise crash the state flow of every pre-#686 `ThreadViewModel` test using the inert `RememberedEffortStore.None` default. `setRememberedEffort`'s own `event=remembered_effort_set outcome=success|io_failure` log (in `AppPreferences`) is unconditional and never level-bearing, so it needed no such carve-out.

No visual change: this rides the same pending/settled footer states [Thread composer footer § Applied effort](thread-composer-footer.md#applied-effort-889) already renders. The live recall and restart proof is [#545](https://github.com/pyrycode/pyrycode-mobile/issues/545)'s `interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel` — see [e2e coverage](../../e2e-interactive-stream.md).

## Related

Part of [Thread composer footer](thread-composer-footer.md); see that document for the component's shape, sourcing and wiring. See also [App preferences § Remembered effort key](app-preferences.md) for the `rememberedEffort` storage key and `EffortRecall`'s `RememberedEffortStore` adapter.
