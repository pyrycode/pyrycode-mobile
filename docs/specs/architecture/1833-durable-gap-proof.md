# Durable gap regression proof (#1833)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect`, settled-cache wait and offline reopen assertions.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: scripted scenario selection and real relay-backed thread.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/UnrecognizedRowSentinel.kt`: `TappingConversationRepository` decorates the app's subscription without originating backfill.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DaemonFaultControl.kt`: bounded owned-daemon transitions and unchanged Retry timing contract.
- `scripts/e2e-daemon-fault.py`: controller owns the child and retains its command/home on restart.
- `scripts/e2e-emulator.sh`: isolated identities, daemon command, peer pairing and scenario selectors.
- `scripts/android-test-gate.py`: device custody, counted XML and owned emulator boot/cleanup.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: newest ask, `onDemandHistoryGap` and one request slot.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryRows.kt`: marker tags and gesture-only demand.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md`: coverage records received durable ids; restart clears replay but retains history.
- `docs/knowledge/features/conversation-cache.md` and `caching-conversation-repository.md`: settled rows and rows-before-position persistence.
- `docs/knowledge/features/thread-screen-oldest-end-history-demand.md`: page/marker visibility is inert; pulls target the crossed gap.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: isolated device custody and nonzero counted evidence.
- `docs/e2e-interactive-stream.md`: rung 3/4 vocabulary and curated live selection.
- Sibling `pyrycode/docs/protocol-mobile.md`, Conversation history (v2) and Security model: wire SSOT, restart empties ring, opaque backwards cursors.
- Sibling `internal/control/client.go`: `ChannelPost` confirms durable recording through the owned Unix socket.

## Context

#1832 is merged. Existing offline readability proof can pass through ring replay without exercising a multi-page durable gap. Strengthen that proof and add a zero-Claude twin plus a separate external process-death execution. No production behaviour or protocol changes. No decision record needed.

Remote overlaps #1682, #1689, #1690, #1691, #1693, #1695, #1729, #1762, #1766 and #1827 add other scenarios/selectors; keep additions local. None is a dependency. Forecast: approximately 900 written lines, at most three new test types, no production callers and five criteria. Verified parent chain is #1833 → #1787 → #1681.

## Design

Extend the existing test repository tap with an opt-in thread probe. It records the selected conversation's requests, completion and rendered repository rows, retaining opaque cursors only in memory. No extra subscription or request. A shared device helper drives a bounded batch of synthetic host posts (>2 default pages), with a completed reply inside the missing interval. Restart the harness-owned daemon after the missed sequence, preserving its command, home and pairing, then reconnect the phone. Assert one newest ask without a gesture, a remaining coverage gap, and one older request per physical pull. Programmatic scrolling to a marker and page arrival must originate no ask. Assert exact synthetic post order, uniqueness, completed reply and cached older rows through the real merge.

Retain the live method name, both real turns, phone settled-cache wait, offline list/reopen/readability assertions, and full-suite inclusion. Promote the scenario conversation so host posts address that channel. Add the deterministic twin to the existing `ping` selection alongside its original method and assertions; reuse its single terminal fixture for the completed reply. Pair an independent peer on that path without changing other scenarios' releases.

External proof support launches only an emulator it owns under the existing device hold. Run a cache-preparation instrumentation method against an isolated real daemon, then let instrumentation finish. The host driver launches the actual app, records its PID, force-stops it without clearing data, confirms no PID remains, posts a run-unique synthetic message through the owned controller, relaunches and opens the channel via UI Automator. Check that the post appears exactly once without scrolling. Record only revision/version, conversation id, synthetic identifier/text, PID transitions and result. Cleanup stays with the harness/device owner.

## State and concurrency model

Probe state is synchronized and opt-in, reset in each scenario's `finally`. Requests complete through the delegate with cancellation unchanged. Device helpers use bounded existing coroutine/Compose waits. Host controller serializes transitions/posts and forwards only to its fixed owned socket; restart retains durable files. External proof holds the gate device lock and stops its emulator in `finally`; it cannot target a caller-supplied device.

## Error handling

A refused synthetic post, unsuccessful daemon restart, missing page/gap, automatic catch-up, duplicate/reordered row or missing app PID transition fails with a static category. Do not weaken existing readiness or completion waits. Execution failures are unverified, never skipped/passed evidence.

## Testing strategy

Test-first probe tests drive the production decorator with a fake delegate: opt-in request capture, exception propagation and no extra observations. Python controller/proof tests check owned-socket routing, input bounds and sanitized evidence. Run the existing scripted `ping` gate and require both original and new methods in fresh counted XML. Device-only reason: real relay/Noise, daemon durable disk, phone Keystore/cache, physical gestures and external process death cannot be established in Robolectric.

Invariant probes check cached older rows; chronological once-only posts/reply; inert marker/page arrival; exactly one request per pull; preserved cache and durable home across daemon restart; actual stop/relaunch and once-only newest post after process death. The dispatcher supplies fresh full-suite live counts and explicit named-method PASS after verification. Execute the separate zero-Claude force-stop proof against the real isolated daemon before documentation. Final checks include unit suite, lint, assemble, Android-test compilation, forced Spotless and pre-verify.

## Open Questions

Resolved: #1842 supplies the derived-availability drain handoff and is merged into this branch. The regression remains active; no application fix belongs to this test-only ticket.

Setup resolved: Two batches of 120 posts exceed the thread’s 200-entry request page; assertions require the reply outside the newest page and at least two older pulls. Each post has multiple durable envelopes. No cursor encoding assumptions. Synthetic content remains fixture-only.

## Documentation handoff

Pending for documentation stage:
- `docs/e2e-interactive-stream.md`: stronger rung-3 scenario, deterministic twin, external force-stop procedure and fresh counted live/force-stop evidence.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md`, Resuming from the saved position: supplied proof evidence beside #1832.
- `docs/knowledge/features/thread-screen-oldest-end-history-demand.md`: lazy gap demand evidence.
- `docs/knowledge/features/conversation-cache.md` and `caching-conversation-repository.md`: process-death evidence beside coverage/legacy/persistence explanation.

## Security review

**Verdict:** PASS

- [Trust boundaries] The controller accepts bounded synthetic fixture names/text and a fixed batch count only, forwarding `channel.post` to its startup-owned Unix socket. No caller-supplied path, command or device.
- [Tokens] Existing pairing and Keystore storage remain; probe/evidence never retain credentials or opaque cursors on disk.
- [Files] Restart retains the owned durable home and app-private cache. Evidence contains only synthetic values and fixed diagnostic fields; no raw UI dump, entry payload or daemon log is published.
- [Android surface] No exported component or production intent changes. External control targets the emulator booted by its own gate custody.
- [Cryptography] Existing Noise IK and Keystore paths remain unchanged; no new crypto.
- [Network/I/O] Loopback-only control with bounded bodies and socket deadlines; restart waits for the existing relay registration fence. No arbitrary subprocess arguments from inbound control.
- [Errors/logs] Static failure categories only. Synthetic identifiers/text are explicitly the only content allowed in proof evidence; credentials, pairing, cursors and raw history payloads are excluded.
- [Concurrency] Probe captures only the selected app subscription; controller serializes owned-child lifecycle, cleanup in `finally`. Process death is external after instrumentation finishes, preserving pairing/cache.
- [Threat model] Malicious relay and hostile frame handling continue through production Noise/decoders. Rooted-device token theft and UI-side accessibility/screenshot leakage retain existing mitigations; this harness adds no production capability or content export.

**Reviewer:** builder self-review per `builder/security-review.md`
**Date:** 2026-10-06

## Revisions

2026-10-06: The test application’s existing repository decorator is the request-count seam. Capture newest/older classification and completed pages without retaining cursors. Use two 60-post batches with the completed reply between them, exceeding the daemon’s default 50-entry page. The live baseline refreshes once after its settled ping so coverage certifies durable baseline entries before the tested disconnect. The deterministic twin uses its own promoted discussion and the existing ping fixture, avoiding cross-method reply collisions. The external force-stop proof runs a dedicated preparation method on an emulator booted under the existing device hold; the host driver then kills the actual app and restarts the daemon after the synthetic post to exclude replay there too. Original stop/start readiness coverage remains effective.

2026-10-06: Two isolated scripted `ping` runs exposed a production reconnect failure before the gap could be filled: the newest availability event was logged, but the repository tap recorded zero asks/completions and retained only the baseline. File and link #1842; preserve the failing regression without issuing a test-side history request or ignoring its assertion. The exact stranded guard needs diagnosis in #1842. The actual external force-stop/relaunch proof passed against the isolated real daemon: one preparation test passed, one synthetic post displayed once with no scrolling, app PID absent after stop and replaced after launch. Sanitized counted evidence is retained in `scripts/e2e-fixtures/1833-force-stop-evidence.json`; reconnect failure counts are retained beside it. Full live execution and documentation remain later-stage work after the blocker and proof pass.

2026-10-07: Merged #1842 retains the deterministic proof and its strengthened setup: 120 posts per batch, first-batch delivery fenced by the peer’s final synthetic delta, and completed replies distinguished from `producer: channel_post` completions. Apply the same fences to the live proof, waiting for its second non-post completion (the first is the cached ping) before starting the newer batch. The generic second `turn_end` could otherwise return a post completion and restart the daemon before Claude settled. This reuses the failing-order evidence and repaired deterministic wait from #1842; the wire SSOT’s `assistant_delta` and `turn_end` sections define provenance. Preserve the live method name, both turns and settled-cache/offline assertions. Always clear its opt-in probe in cleanup. The merge’s `elif SCENARIO=ping` preserves both ordinary ping methods; only the external proof selects its dedicated preparation method. No production or protocol changes.

2026-10-07: Strengthen `cacheBaseline` to wait for received durable claims for every settled cached message identity, alongside nonempty spans. The opening page can contain durable state frames before the baseline ping exists; nonempty spans alone could satisfy the later reconnect wait with stale coverage. Keep the original phone-settled cache wait and certify its rows before the tested disconnect. The helper reads the existing cache only, with the same deadline and no test-originated history request.
