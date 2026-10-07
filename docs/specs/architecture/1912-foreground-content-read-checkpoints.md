# #1912 — Foreground content read checkpoints

## Files read

- `data/repository/ConversationCommands.kt`: `markConversationRead` and `routeReadMarkReply` confirm the daemon's stored unsigned mark, never the requested value.
- `data/repository/ConversationListProjection.kt`: the live monotonic read-fact ledger also needs received durable latest ids.
- `data/repository/ThreadProjection.kt`: row folds, `mergeHistoryPage` and `observeSnapshot` own received row versions.
- `data/repository/HistoryPageReducer.kt`: `reduceOrderedHistoryPage` already records producing-entry claims, including individual assistant deltas.
- `data/repository/HistoryCoverage.kt`: unsigned spans prove receipt and retain known holes, independently of visibility.
- `data/repository/ThreadSnapshotSource.kt`: the immutable snapshot carries row/version evidence to its consumer.
- `data/repository/CachingConversationRepository.kt`: restored rows must never acquire live sight evidence merely by merging.
- `data/repository/StableConversationRepository.kt`: reads switch on reconnect; pending writes need process ownership outside a destination.
- `di/AppModule.kt`: `ThreadDestinationFactory` captures host ownership; `ConversationViewing` retains the older-daemon local fallback.
- `ui/conversations/thread/ThreadViewModel.kt`: thread content combines received rows, coverage and local live folds.
- `ui/conversations/thread/ThreadScreen.kt`: `newestRenderedRow`, measured chrome and reverse-list layout qualify sight.
- `ui/conversations/components/MessageBubble.kt`: `StreamingAssistantBody` reveals content after composition, so layout alone cannot prove presentation.
- `InteractiveStreamE2ETest` and `SecondClientPeer`: the existing attention scenario preserves permission checks and can observe daemon read facts from a second connection.
- Merged foundations #1881, #1909, #1910 and #1911: strict unsigned identity, live facts, durable cached coverage and unsigned gap targeting remain authoritative.
- `docs/knowledge/features/thread-screen.md`, `remote-conversation-repository.md` and `remote-conversation-repository-reads-and-thread-store-history-paging.md`: cached receipt is not sight; newest history loading already has its own ordinary readiness path.
- Protocol source: `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`, “Application envelope”, “A history entry”, “Joining a page to the live stream” and “Security model”.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Inspected design context and screenshot. Opposing dark message bubbles use Material 3 body typography and scheme colours, with 20dp gutters, rule/label/rule session delimiters and translucent fixed header/composer overlays. Preserve all existing appearance; measured composer height and the IME-constrained message viewport bound qualification.

## Context

Only the newest received content actually presented in an open foreground thread qualifies a durable daemon mark. ViewModel lifetime, coverage, list latest ids and live connection/replay counters cannot establish sight. No decision record or wire change is needed.

One deliverable: a qualified read checkpoint from receipt through confirmed/retried write. Forecast approximately 1500 written lines including plan and probes, at most four new exported declarations, six simultaneous production consumer updates, five acceptance criteria and eight classified reject conditions. The single-consumer evidence/checkpoint machinery remains in this slice under the floor rule. Overlap: #1766 touches `MessageBubble`; its streaming renderer change is compatible with a local presentation callback.

## Design

Add immutable thread read evidence beside `ThreadSnapshot`. History reduction distinguishes successfully understood deliberately nonvisual entries from malformed/unsupported entries. Producing claims attach to exact immutable row versions, including all streaming deltas and tool changes represented by that version. Receipt of a positive durable id independently raises the conversation's known latest id. Direct-live and replay use only `history_entry_id`; absent identities create an advancement barrier that only an ordinary received matching history entry can resolve. No reconciliation ask is added.

Carry snapshot evidence through cache and ViewModel without granting it to restored/offscreen rows. A screen event carries the presented newest row version. Qualification requires the destination lifecycle to be resumed, the newest rendered content's trailing edge inside the message viewport above the measured composer, and streaming reveal to have caught up. Info banners and valid transient-state entries may extend a qualified mark only through continuously received positions. Known holes, unidentified content and malformed/unsupported entries stop advancement. Recomposition of an unchanged checkpoint is inert. Older daemons keep `ReadPosition` behaviour and receive no command.

An application-owned host-bound retry holder retains the maximum qualified checkpoint per conversation. It snapshots the exact host connection source rather than the selected host. Writes serialize per conversation; a confirmed daemon fact at or beyond pending clears it. Failed, malformed or clamped-below replies retain it for the next connection. Closing the screen does not erase qualified pending work; removing/replacing a pairing cannot route it to another host identity.

## State and concurrency model

Projection evidence is immutable and published with snapshot generations. A content update cannot inherit an earlier version's sight. Existing atomic projection writers and unsigned coverage remain in place. Screen lifecycle/layout/reveal effects are composition-owned and cancel on exit. Retry jobs belong to an injected application scope with explicit disposal; per-host connection observation cancels in-flight work on replacement while retaining pending maxima. The lifecycle driver continues closing sockets on background. A retry is previously qualified work and may complete after the destination closes.

## Error handling

Unsupported/malformed entries and missing identities are conservative barriers, not silent permission to skip. Missing support, offscreen/obscured content, background destinations and incomplete reveal create no write. Repository Result failures retain pending state; cancellation propagates. Existing command validation remains the confirmation boundary. Structured debug events contain static outcomes only, without text, ids, payloads, keys or credentials.

## Testing strategy

Write failing deterministic tests before production. Probe exact durable ids distinct from connection/replay ids, row-version streaming/tool updates, duplicate/history overlap permutations, valid nonvisual tails, malformed/unknown and missing-id barriers, known gaps, empty/one-row input and fetched offscreen rows. Retry tests cover offline qualification, mid-write disconnect, malformed/clamped replies, confirmation pushes, screen closure and two isolated hosts.

Add shared Compose viewport/lifecycle tests, including named managed-device methods for foreground qualification and scrolled-away/unseen updates. Run those methods on Android 13 and report fresh XML executed/failed/skipped counts. Keep ordinary layout tests under sharedTest; real IME coverage uses existing device methods. Extend `InteractiveStreamE2ETest.interactiveTurn_attentionDot_followsARealTurn` with second-peer read-mark observation and retain its permission/attention checks. Dispatcher owns fresh full live execution and documentation of its counts/evidence.

Run affected repository, history, cache, ViewModel, newest-row and viewport tests, lint, assemble, Android-test compilation and forced Spotless. Merge main and push before the final whole unit/shared suite, assemble and `scripts/pre-verify.py --gradle`.

## Open Questions

None. Any necessary refinement of row-version binding during implementation is recorded under Revisions.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/thread-screen.md`, `docs/knowledge/features/remote-conversation-repository.md` under “Daemon conversation read marks”, and `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md` with viewport qualification, nonvisual versus unsupported/malformed entries, missing-id behaviour and app-process reconnect retry. Record the named live scenario's dispatcher-produced evidence in `docs/e2e-interactive-stream.md`.

## Security review

**Verdict:** PASS

- [Trust boundaries] MUST FIX addressed in design: receipt alone cannot grant sight. Exact row-version claims, strict unsigned durable identity and understood-entry classification exclude envelope/replay counters and malformed content.
- [Tokens] No new credentials or secret processing. Existing authenticated command transport is reused.
- [Files and storage] Evidence and pending retries stay in process memory; cached rows grant no sight. No new files, paths, persistence or backup surfaces.
- [Android attack surface] No exported component, intent, provider, PendingIntent or WebView changes. Lifecycle is read from the destination, not from caller-supplied wire data.
- [Cryptography] Existing Noise IK, Keystore and TLS remain unchanged.
- [Network and I/O] Existing frame bounds, request timeouts and capped reconnect backoff remain authoritative. No missing-id reconciliation traffic. Unsupported replies cannot confirm work.
- [Errors and telemetry] SHOULD FIX: emit static lifecycle/classified outcomes only; never log marks, row content, payloads, tokens, keys or parser messages.
- [Concurrency] MUST FIX addressed in design: keep retry ownership beyond ViewModel disposal and bind it to the original host connection source; serialize monotonic writes and clear only confirmed maxima.
- [Threat model] Relay delay/drop/reordering cannot manufacture visibility or confirmation; duplicate delivery is inert. Hostile daemon content fails conservatively. Rooted-device credential theft and keyboard/screenshot leakage introduce no new surface in this slice and remain covered by existing storage/UI policy.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-07

## Revisions

2026-10-07: The wire envelope currently discards the named daemon foundations' optional `history_entry_id`. Retain it with the existing strict unsigned serializer, independently of the signed connection and replay fields. The two initial regressions executed and failed (zero skipped), covering discarded upper-range identity and a finalized row missing its `turn_end` claim; red XML is retained in `/tmp/builder-1912/red.xml`.

2026-10-07: Keep `markConversationRead` as the confirmed Result-returning command. The separate `acknowledgeReadCheckpoint` presentation sink queues previously qualified work in the host-bound process holder, avoiding a false confirmation or a fabricated command failure while a write is pending. Missing identities reconcile only against received entries matching type, timestamp and payload.

2026-10-07: A lazy item's bounds include trailing spacing, and chrome initially measures zero. Qualification now requires measured nonzero chrome and the actual bubble surface's un-clipped trailing edge in window coordinates, within the IME-constrained list viewport. This preserves resting geometry without treating padded/obscured layout bounds as content. The event captures its immutable qualified checkpoint before dispatch, so a concurrent unseen row update cannot increase it or erase already-qualified work. Superseded version bindings are dropped from the current snapshot; old snapshots retain their own immutable proof without retaining every streaming text version for the process lifetime.

2026-10-07: Row-version claims are separate from `ReducedHistoryPage.unsignedClaims`, whose per-delta sets remain unchanged for coverage/gap fragmentation. `readClaims` includes row-changing finalization/tool updates without assigning their ids to an unrelated first delta. Bubble qualification measures the inner visible content column, excluding decorative surface padding; existing resting surfaces extend into the composer's translucent top padding while their readable content stays clear.

2026-10-07: Observe lifecycle through `Lifecycle.currentStateFlow`; the deterministic STARTED-to-RESUMED probe exposed stale callback qualification. Queue the captured event with an undispatched launch so destination disposal cannot cancel the handoff before process ownership begins. Extend the existing scripted ping scenario with confirmed daemon read facts as the deterministic twin; fresh full live proof remains dispatcher-owned.

2026-10-07: Keep viewport measurement/reveal bindings bounded to the current immutable rows as well. Superseded streaming text versions are removed after composition so a long foreground turn does not retain every previous string in UI-local maps.

2026-10-07: A new repository probe failed because the existing read projection settles a streaming message when an info banner follows it. Bind the snapshot's presentation-only settled copy to the same producing claims only when every other message field is identical. This preserves nonvisual-tail advancement without granting a later text/tool update an older version's claims.

2026-10-07: The whole-suite agent-label probes exposed a composition loop in raw-item membership cleanup: projected agent labels are synthetic rows. Replace prior measurement/reveal versions by their rendered merge identity inside presentation callbacks, preserving unchanged bindings and synthetic rows. Keep only lazy wrapper membership cleanup against the actual rendered wrappers.

2026-10-07: The deterministic twin is the curated `ping` scenario, not `stream` (which selects the multi-delta method). Its assertion waits for the exact rendered assistant reply's durable checkpoint before checking the confirmed daemon fact, so a previously read user message cannot satisfy it during publication of later live facts.

2026-10-08: The scripted ping read proof timed out, and a repository regression failed on valid nonhistorical slash-command metadata. The read exclusion used `slash_commands` instead of the protocol's `slash_command_list`. Use the actual metadata envelope name so its absent history id does not become an unidentified-content barrier.

2026-10-08: The isolated daemon's received history also contains durable model/slash-command menus, MCP reports and context usage. A new reducer regression failed because these supported state entries were classified as unknown barriers. Reuse their existing DTO decoders and domain validators, including announced model and session facts from real-Claude initialization, to classify successfully understood state entries without a thread row as nonvisual. Malformed payloads remain barriers. This changes no history fetch or wire contract.
