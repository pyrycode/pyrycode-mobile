# #1330 — Title an alert with its conversation's name

## Files read

- `app/src/main/java/de/pyryco/mobile/notifications/AttentionNotifier.kt` → `AttentionNotifier.post` (sets the title to `app_name` today), `agentOf` (the per-host lookup the new `nameOf` mirrors).
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `AttentionNotifier` single, where `agentOf` is wired over `HostConversationSource.snapshots`.
- `app/src/test/java/de/pyryco/mobile/notifications/AttentionNotifierTest.kt` → `withNotifier` harness and `theAgentLookupReadsOnlyTheAlertsOwnHostAndIsNullWhenMissing`, the shape the new tests follow.
- `../pyrycode-desktop/src/main/fireNotification.ts` → `notificationTitle` (#1593), the contract copied here.

## Design source

N/A — notifications have no Figma frame (ticket says so).

## Change

`AttentionNotifier` gains a constructor lambda `nameOf: (serverId, conversationId) -> String?` beside `agentOf`. A new top-level `internal fun List<HostConversationSnapshot>.nameOf(serverId, conversationId): String?` returns `Conversation.name` of the matching row in that host's `channels + chats`, null when the host or row is missing — same host-first keying as `agentOf`, so the same id on another host never names it. A new `internal fun notificationTitle(name: String?): String?` cleans the name like desktop's `notificationTitle`: walk by code point, drop control characters (`Character.isISOControl`, which is exactly `\p{Cc}`), stop after 80 kept code points, trim; null when the input is null or the result is empty. `post` sets the title to `notificationTitle(nameOf(...)) ?: getString(R.string.app_name)`. The body text is untouched. The name is never logged. `AppModule` wires `nameOf` over `source.snapshots.value.nameOf(...)`, as `agentOf` is. The notifier has only these two constructor call sites (the test harness and `AppModule`).

Overlap note: `feature/1305` and `feature/1318` also edit `AppModule.kt` in unrelated blocks; this edit is one additive line in the `AttentionNotifier` single.

## Testing strategy

Robolectric unit tests in `AttentionNotifierTest`:
- a named conversation's alert carries the name as title, body unchanged; the same id on another host with a different name gets that host's name.
- an unlisted conversation, a null name, and a name empty after cleaning (only controls and spaces) get `app_name`.
- `notificationTitle` drops control characters, caps at 80 code points, never splits a surrogate pair (81 emoji → 80 emoji, 160 chars), and trims.
- `nameOf` reads only the alert's own host and is null when missing.

## Documentation handoff

None requested by the ticket. Pending for the documentation stage, per the verifier's review of PR #1370: `docs/knowledge/features/push-messaging-service.md` → "The agent lookup (#1116)" → "The notification itself" still says the title is the app name and that no daemon-authored name reaches it, and its `AppModule` wiring snippet lacks the `nameOf = …` line.

## Revisions

### 2026-10-01 — Security review added after the plan commit (verifier finding on PR #1370)

The ticket carried `security-sensitive` before the plan commit, but the plan shipped without the audit. The `## Security review` section below is added now; its verdict is PASS with no MUST FIX, so the design above stands. One code change follows from it, taking the verifier's NIT: `notificationTitle`'s final trim also strips U+FEFF, as desktop's JS `String.prototype.trim()` does and Kotlin's `trim()` does not, so a name of only U+FEFF falls back to the app name on both clients. The new contract: drop `\p{Cc}`, keep at most 80 code points, trim whitespace and U+FEFF, null when empty. `theTitleDropsControlsCapsAt80CodePointsAndNeverSplitsASurrogatePair` asserts the U+FEFF cases.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the conversation name is daemon-authored and untrusted. It crosses into the notification in exactly one place, `notificationTitle`, which drops `\p{Cc}`, bounds the result to 80 code points without splitting a surrogate pair, trims, and returns null for nothing left. The only consumer is `NotificationCompat.Builder.setContentTitle`, which renders plain text: no markup, URL, filename or intent extra is built from it.
- [Trust boundaries] Accepted — format characters (`\p{Cf}`: bidi overrides such as U+202E, zero-width characters, inner U+FEFF) pass through, as on desktop's `notificationTitle` (#1593); the two clients keep one contract. A bidi override can reorder only the title's own text: the system notification header draws the app name and icon separately, so a name cannot pose as another app, and the 80-code-point cap bounds what it can display. Daemon-authored text already reaches the conversation list unfiltered the same way.
- [Host keying] No findings — `nameOf` looks up the alert's own `serverId` first and then the conversation id within that host's `channels + chats`, so a second host holding the same conversation id cannot name another host's alert. `theNameLookupReadsOnlyTheAlertsOwnHostAndIsNullWhenMissing` and the cross-host title test assert this.
- [Logging] Requirement, honoured — the name is never logged: `handle` logs only the static `outcome` and `kind` codes. The name is not part of the notification tag, the `PendingIntent` extras or identifier, the ledger digest or any file; those use only the host and conversation ids, hashed.
- [UI-side leakage] Accepted — the notification keeps `NotificationCompat`'s default `VISIBILITY_PRIVATE` with no `publicVersion`. Under the system's "show all content" lock-screen setting the conversation name now appears on the lock screen; under "hide sensitive content" Android shows only the app name. This is intended: the user's lock-screen setting governs, the name is the user's own data, and desktop shows the same title. A redacted `publicVersion` would be a product decision for its own ticket.
- [Tokens, secrets, credentials] Not applicable — the change reads no token, key or credential and writes nothing to storage.
- [File / storage] Not applicable — the name is never written; the ledger stores only SHA-256 digests over ids, unchanged by this ticket.
- [Inter-process] No findings — the tap's `PendingIntent` is unchanged and immutable; `NotificationTap.target` still accepts only ids for a saved host. The title carries no routing.
- [Concurrency] No findings — `nameOf` reads `HostConversationSource.snapshots.value` synchronously on the notifier's existing collector, exactly as `isMuted` and `agentOf` do; a stale snapshot yields an older name or the app name, never a crash.
- [Threat model] Hostile daemon frame: an oversized or control-laden name is bounded and cleaned before display, and an absent one falls back to the app name. A malicious relay cannot author the name, since it sits inside the Noise session.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-01
