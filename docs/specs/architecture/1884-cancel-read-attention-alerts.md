# #1884 — Cancel attention alerts covered by confirmed daemon read facts

## Files read

- `app/src/main/java/de/pyryco/mobile/notifications/AttentionNotifier.kt`: `handle`, `post`, `digest` and `AlertLedger` establish dedupe before gates and one notification per host/conversation.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: `launchAttention`, `updateReadMarks`, `updateAttention` and `publish` already guard host and repository generations and retain offline read facts.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt`: the eager `AttentionNotifier` singleton supplies exact-host lookups and app-private, backup-excluded ledger storage.
- `app/src/main/java/de/pyryco/mobile/di/ConversationAttention.kt`: `HostAttentionState.readMarks` is independent of Running/WaitingForAnswer and never restored from cache.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationReadMarks.kt`: nullable unsigned durable identities distinguish unsupported/unknown facts from checkpoint zero.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `onInbound` and `observeHostReadMarks` record received durable identity and expose merged list/push/reply facts.
- `app/src/test/java/de/pyryco/mobile/notifications/AttentionNotifierTest.kt`: `withNotifier` and `posted` exercise actual Android notifications through Robolectric.
- `app/src/test/java/de/pyryco/mobile/di/HostConversationSourceAttentionTest.kt`: real repository/pump harness exercises list refresh, pushes, durable identity and repository replacement.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_attentionDot_followsARealTurn` already proves phone and peer read directions; preserve unchanged.
- `docs/knowledge/INDEX.md`, `docs/knowledge/features/push-messaging-service.md`, `docs/knowledge/features/dependency-injection-host-conversation-source.md`: retain ledger-before-gates, exact-host identity, and live-only read-fact support.
- Sibling `pyrycode/docs/protocol-mobile.md`: “Marking a conversation read” and “Security model” remain the wire/security authority; no protocol change.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Read the design context and screenshot on 2026-10-08. The channel list groups compact rows and status dots beneath host headings on a dark gradient, with body-small row labels, title-small headings, on-surface text and inverse-primary rules. This ticket follows the existing shared read state and only removes a posted Android notification; no layout, asset, token, notification copy or appearance changes.

## Context

#1883 supplies daemon-authoritative unread, but a posted notification currently survives a peer read. The cancellation contract is a present confirmed `readUpTo` covering a present known `latestEntryId`, including equality and zero. Idle, thread viewing and local persisted positions are not read proof.

One deliverable, three acceptance criteria, approximately 650 written lines including tests and this plan, three production files, no new exported type, one production constructor consumer update, and fewer than ten decision branches. This fits the nearest notification analogue #685. No in-flight branch overlaps the proposed production files. No decision record is needed.

## Design

Expose an internal host-first `StateFlow<Map<String, Map<String, ConversationReadMarks>>>` from `HostConversationSource`, projected from the existing guarded attention facts during `publish`. It follows the same host removal, repository reset and offline retention rules as the existing fold. Do not infer or persist additional read facts.

Pass this flow to `AttentionNotifier` from `AppModule`, using an inert default for existing test/demo consumers. Keep the coverage predicate and cancellation consumer inside `AttentionNotifier.kt`, since cancellation has one consumer. A new or changed covered checkpoint cancels `digest(serverId, conversationId)` with notification id zero, regardless of the permission/settings/mute/foreground gates. Initial state collection also cancels an existing notification without requiring a new alert. Repeated unchanged facts do not cancel a prompt posted subsequently; changes on another conversation/host do not recancel that prompt.

Completion alerts keep the existing ledger and gates, then check the current flow value immediately before posting. A covered completion is spent without posting. Prompt alerts retain all existing gates and can post even while the conversation is read. Cancellation never writes repository state, answers a prompt, or changes running/busy state. Missing either read fact leaves current behaviour intact. Future known latest identities exceeding the mark permit new unread completion notifications.

## State and concurrency model

The source remains app-owned with its existing monitor and per-host jobs; `dispose` clears the additional projection. The notifier owns two collectors in its existing supervisor scope, cancelled by `dispose`. One `Mutex` serializes Android cancellation and the final coverage-check/post step. Preference collection may suspend before that critical section; coverage is read afresh after it resumes, so cancellation during a suspended gate cannot be undone by a stale post. The cancellation collector reads the current flow value inside the mutex, rather than acting on an obsolete queued emission. Its prior facts track per-conversation changes only and are replaced by the current map.

## Error handling

Cancellation of absent/repeated tags is Android's harmless no-op. Keep the existing post-time SecurityException classification, permission/settings/mute/foreground outcomes and failed-ledger-write handling. Add content-free structured lifecycle logging for read cancellation and a static read suppression outcome; no host ids, conversation ids, turn ids, durable checkpoints, hashes, text or secrets are logged.

## Testing strategy

Write notifier tests first and observe red, then implement. Robolectric tests inspect posted Android notifications and cover post-before-read, read-before-post, initial snapshots across notifier restart, repeated/absent cancellation, host isolation, null/older-daemon facts, equality/zero/unsigned comparison, pending preference gates, retained ledger, subsequent unread completions and prompt delivery after reads and unrelated updates.

Source wiring tests exercise actual repository list/push frames feeding the notifier read projection, plus real notifications from source alerts, host isolation, live generation reset and cancellation independent of Running/WaitingForAnswer. Run `AttentionNotifierTest`, `HostConversationSourceAttentionTest`, `HostConversationSourceTest` and `ConversationAttentionTest`, lint, assemble, formatting and final full unit/shared suite plus `scripts/pre-verify.py --gradle`. No device-only test is added: cancellation is fully reachable in Robolectric without FCM/background wake.

Preserve the existing rung-3 shared-mark scenario unchanged. The dispatcher owns a fresh full live gate after verification and must report executed/failed/skipped counts and explicit passage of `InteractiveStreamE2ETest.interactiveTurn_attentionDot_followsARealTurn`. Deterministic notification proof is separate evidence, not a claim that live execution passed.

## Open Questions

None.

## Documentation handoff

Pending for documentation stage: update `docs/knowledge/features/push-messaging-service.md`, “Attention notifications”/gates, logging and testing sections with daemon read cancellation, host isolation, unchanged local fallback and retained ledger semantics. Record the distinction between deterministic notification proof and dispatcher-produced shared-mark live scenario evidence.

## Security review

**Verdict:** PASS

- [Trust boundaries] Cancellation accepts only repository-merged `ConversationReadMarks` under the source's host/repository generation guards. Both unsigned facts must be present; local position, Idle and cached list fields cannot assert confirmed coverage.
- [Tokens] No credentials are created or consumed. Read facts remain in memory; identity fields are equality keys and never logs or rendered text.
- [Files and storage] The existing bounded digest ledger remains in `noBackupFilesDir`, with temp-then-rename writes. Read suppression must still record its digest before all gates; do not erase the ledger on cancellation.
- [Android attack surface] No component, intent filter or remote action is added. Cancel only the exact length-prefixed host/conversation digest and id zero; never use `cancelAll`. Existing immutable pending intents and validated tap routing stay intact.
- [Cryptography] Reuse SHA-256 solely for notification identity and ledger digests. Noise, Keystore, pairing and key lifecycle stay untouched.
- [Network and I/O] Reuse decoded read facts and the existing inbound consumer. No new request, socket, retry loop or frame parser; transport bounds and authentication remain unchanged.
- [Errors and logs] Use static outcomes only; do not log notification text, ids, read checkpoints, decrypted data or credentials. Existing debug-only RelayLog controls remain.
- [Concurrency] SHOULD FIX: a read can land while `notificationsEnabled.first` is suspended. Read coverage again inside the same mutex as post/cancel, and prove this ordering with a controlled flow test. Compare changed checkpoints per host/conversation so an unrelated update cannot clear a later prompt.
- [Threat model] Authenticated hostile daemon facts can suppress that daemon's own alerts but cannot cancel another host's tag. Relay delays may delay cancellation; Noise prevents forged read facts. Rooted-device token theft and screenshot/accessibility leakage remain handled by the existing protocol/storage/UI boundaries; no new plaintext or UI surface is added here.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-08
