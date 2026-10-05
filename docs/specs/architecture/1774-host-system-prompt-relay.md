# Host system prompt over the relay (#1774)

## Files read

- `docs/knowledge/features/conversation-repository.md`: optional interface defaults avoid cascading changes to unrelated doubles; channel prompt null semantics remain separate.
- `docs/knowledge/features/remote-conversation-repository-conversation-writes.md`: channel prompt reads use manual decoding to prevent content-bearing exceptions.
- `docs/knowledge/features/mobile-protocol-v2-wire-layer.md`: `Envelope` payloads remain untrusted until their consumer validates them.
- `docs/knowledge/features/stable-conversation-repository.md`: each one-shot snapshots its delegate once; reconnect must never redirect an in-flight write.
- `docs/knowledge/features/caching-conversation-repository.md`: Kotlin delegation passes through one-shots without persisting their values.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`: `SystemPromptLimit`, existing prompt contract and default implementations.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionSettingsCommands.kt`: request construction and channel prompt commands.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `onInbound` correlation and command delegates.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRequests.kt`: register-before-send, idempotent completion, cancellation cleanup and teardown.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt`: live snapshot delegation.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: inherited contract delegation.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt`: isolated in-memory demo state.
- `app/src/main/java/de/pyryco/mobile/data/network/SystemPromptPayloads.kt`: content-free manual decoding precedent.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayLog.kt`: debug-only logging and test capture seam.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositorySystemPromptTest.kt`: real-repository pump tests and unchanged channel regression coverage.
- Current default-branch `pyrycode/docs/protocol-mobile.md`, “Daemon-wide host system prompt” and “Security model”, and `internal/protocol/host_system_prompt.go`: authoritative wire contract, read from GitHub because the sibling checkout may lag.

## Context

The merged daemon contract needs a host-bound mobile read/write pair before the dependent editor slice. Host current/default are required strings, unlike the nullable channel prompt and its running-session verdict. This ticket adds no UI, reset verb, default-text copy, persistence or dependency. No decision record is needed.

Sizing: about 750–900 written lines including tests and this plan; one new exported reading type, three implementing repositories plus the unchanged cache delegate, three acceptance criteria and fewer than ten failure categories. The nearest analogue `e01b8116` wrote 869 inserted and two deleted lines. Overlap: #1642 edits shared repository files for queue delivery, in different blocks; changes here stay additive and local.

## Design

`ConversationRepository.requestHostSystemPrompt()` and `setHostSystemPrompt(systemPrompt: String)` return `Result<HostSystemPromptReading>`. Default failures leave unrelated doubles compiling. The reading holds `systemPrompt` and `defaultSystemPrompt`, uses structural equality, and overrides `toString` to redact both values.

`SessionSettingsCommands` reuses `RelayRequests` and adds the two host commands without an interactive gate. Read sends `{}`; write always sends exactly the required string field, including empty and whitespace. `SystemPromptLimit.fits` rejects oversized writes before sending. Both await and manually decode the same `host_system_prompt` reply in a new internal network decoder. Both fields must be JSON strings; no absent/null fallback. Bound both returned strings with the shared byte limit so downstream consumers hold bounded text and can write the returned reset text unchanged. Unknown additional keys are tolerated.

`RemoteConversationRepository.onInbound` adds the reply to its correlation-only success arm, with no conversation projection. Public methods delegate. `StableConversationRepository` snapshots the current delegate once and returns a failed result when disconnected. `CachingConversationRepository` inherits delegation and never caches host prompts. The fake stores one reading per instance with an empty demo default (explicitly demo data, never a copy of daemon reset text), and accepts the same write limit.

## State and concurrency model

No new jobs, dispatchers, hot flows or retained remote state. The caller owns each suspend operation; the existing inbound collector belongs to the connection scope. Request ids and waiters stay per connection. Registration precedes send; cancellation removes the waiter and propagates as cancellation rather than a failed result. Teardown fails outstanding operations. The stable facade never retries on another connection. Demo updates atomically replace the current reading and return that exact acknowledgement.

## Error handling

Return failures for oversized writes, disconnected sends, teardown, invalid objects/fields or over-limit reply strings, and correlated daemon errors. Manual decoder messages name only static field names. Published `protocol.malformed` and `host_system_prompt.unavailable` retain code and retryability with static messages; other error responses become a content-free generic failure, without the original throwable as cause. This avoids propagating daemon-authored error text through a prompt operation. No exception or log quotes a payload or either prompt.

Debug-only `RelayLog` events report the static operation and sent/succeeded/cancelled or classified failure outcomes. No prompt, payload, raw daemon error, identifier or arbitrary error code is logged.

## Testing strategy

Write the real remote repository tests first and observe their failure before implementation. A channel-backed pump proves serialized read/write envelopes, required keys, empty/custom/whitespace/default round trips, authoritative write acknowledgements, ASCII and multibyte byte boundaries, every missing/null/non-string field shape, malformed objects, error code/retryability, cancellation/teardown/disconnection, concurrent reordered requests, duplicate/unmatched replies and two repositories with colliding request ids. Capture logs and check failures and reading string representations for content leakage.

Facade tests use the real remote repository through stable/cache delegates, prove live-only behavior and reconnect snapshot isolation, and check demo host/channel independence. Run new tests plus existing channel payload/repository, stable/cache and fake repository coverage. Run lint, assembleDebug, spotlessApply and forced spotlessCheck. No shared/device tests change, and no operator-facing flow lands, so no emulator or real-Claude scenario belongs to this data-only slice.

## Open Questions

None. The host contract uses `Result` for the new I/O operations; existing channel methods retain their throwing API unchanged.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] SHOULD FIX addressed by design: `toHostSystemPromptReading` must validate the object and both required strings and reject over-limit UTF-8 data before exposing the reading. Never truncate or substitute empty text. Tests include sensitive sentinels in invalid payloads.
- [Tokens, secrets and credentials] No new credentials or token operations. `HostSystemPromptReading.toString` redacts both instruction strings; no prompt enters logs or throwable causes.
- [Files and storage] No phone persistence; cache facade only delegates. Durable storage is the merged daemon's responsibility. Fake values are per-instance memory only.
- [Android attack surface] No component, intent, provider, WebView or UI change. The dependent editor owns plain-text rendering and UI exposure.
- [Cryptography] Existing authenticated Noise transport remains unchanged, including its nonce ownership and Keystore-backed pairing; no new primitive or credential comparison.
- [Network and I/O] Shared request transport and supervisor retain frame checks and liveness/backoff. Local write/reply byte bounds constrain this family. Cancellation and teardown unblock waits; no offline resend or cross-host retry.
- [Errors, logs and telemetry] SHOULD FIX addressed by design: sanitize daemon errors before returning failure and log only static classified outcomes. Manual decoder failures and redacted representations are pinned by tests. No new telemetry.
- [Concurrency] Per-connection `RelayRequests` is the sole request ledger. Concurrent reordered replies settle their own callers; unmatched/duplicate replies have no state mutation. Cancellation cleanup and stable snapshot isolation are exercised.
- [Threat model] Relay drop/delay/reordering uses existing encrypted transport/liveness and per-request correlation. Server-id spoofing and relay MITM remain covered by Noise authentication. Hostile daemon shapes and oversized values fail closed at the decoder. Token theft remains covered by unchanged Keystore custody and per-device revocation. Prompt injection policy and UI screenshots/accessibility/keyboards belong to the daemon and dependent editor slice #1734; this slice renders nothing.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-04
