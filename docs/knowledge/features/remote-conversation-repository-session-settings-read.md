# Remote conversation repository — session-settings read, counterpart to the write

Split out of [Remote repository — session settings and conversation writes](remote-conversation-repository-conversation-writes.md) on 2026-10-17 to keep that document under the 50000-byte size cap the docs guard enforces. The section below moved here verbatim and kept its heading, so its anchor is unchanged. Part of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md); see that document and [session settings and conversation writes](remote-conversation-repository-conversation-writes.md) for the rest of the family.

## `observeSessionSettings(conversationId)` / `refreshSessionSettings(conversationId)` — the settings read, counterpart to `setSessionSettings` (#590)

The **read** half `setSessionSettings` never had: a conversation-scoped `request_session_settings` →
`session_settings` round trip, exposed as a cold per-conversation reading rather than a one-shot return —
a **new consumer shape**, not another mutation. `#590` is a net-new read path, not a replacement: mobile
never shipped the bootstrap-scoped read desktop had to retire. `settingsRevision` and this whole read —
`observeSessionSettings`, `sessionSettingsRead`, `readSessionSettings`, `refreshSessionSettings` and
`bumpSettingsRevision` — now live on `SessionSettingsCommands` (#916,
`data/repository/SessionSettingsCommands.kt`); the repository's `override fun observeSessionSettings` /
`refreshSessionSettings` are one-line hand-offs, and its `session_transition` arm calls
`sessionSettingsCommands.bumpSettingsRevision(conversationId)` at the position this used to occupy inline.

```kotlin
// SessionSettingsCommands
fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> =
    settingsRevision
        .map { it[conversationId] ?: 0L }
        .distinctUntilChanged()
        .flatMapLatest { sessionSettingsRead(conversationId) }
        .onStart { emit(null) }

private fun sessionSettingsRead(conversationId: String): Flow<SessionSettings?> =
    flow {
        emit(if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) readSessionSettings(conversationId) else null)
    }.catch { emit(null) }

fun refreshSessionSettings(conversationId: String) = bumpSettingsRevision(conversationId)

/** Public so the repository's `session_transition` arm can call it (was private). */
fun bumpSettingsRevision(conversationId: String) { /* … */ }
```

- **Nothing is cached on this class — the projection is the trigger, not the reading.** `settingsRevision:
  MutableStateFlow<Map<String, Long>>` holds `conversationId -> ordinal`, not a `SessionSettings`. A bump
  means "re-read", and the read always asks the daemon fresh; there is no `StateFlow<SessionSettings>` for
  a stale value to sit in. This is the structural reason a late reply can never overwrite a current
  reading (see below), not a check anywhere in the code.
- **Four refresh triggers, one mechanism each, no fifth mechanism needed.** *Thread entry* is plain
  subscription — the revision `StateFlow` always has a value, so a fresh collector reads immediately (the
  `onStart { emit(null) }` resets to *unavailable* first, so a host handoff never shows the **previous**
  host's values while the new read is in flight). *The owning host's reconnect* falls out of
  [`RelayRepositoryCoordinator`](relay-repository-coordinator.md) minting a **fresh repository** per
  connection plus the [facade](stable-conversation-repository.md)'s `flatMapLatest` re-subscribing under
  it — host isolation is structural, not a checked property, because a reply from another host cannot
  arrive at this instance at all. *That conversation's `session_transition`* is a third write on the
  existing `TYPE_SESSION_TRANSITION` arm, beside `appendSessionBoundary` and `updateCurrentSessionId` —
  `bumpSettingsRevision(conversationId)`, gated by the same decoded conversation id so a transition cannot
  invalidate another conversation's reading. *A caller's `refreshSessionSettings`* — the moment a settled
  `setSessionSettings` write should surface a fresh reading — is the same bump, called directly; it is
  **fire-and-forget, non-suspending, non-throwing**, because an invalidation with no live connection is a
  no-op the caller has nothing to recover from (the next connection re-reads on subscription regardless).
- **`flatMapLatest` is what makes a superseded reply harmless, not a correlation check.** A new trigger
  cancels the in-flight read before starting the next, so a reply that arrives late has no collector to
  reach; `RelayRequests.sendAndAwaitReply`'s `finally` has already deregistered its pending deferred, so the reply
  correlates with nothing and is dropped at the demux — the same idempotent-`complete`-on-no-match posture
  every correlated reply already has. `session_settings` carries **no `conversation_id` of its own**, so a
  reading is routed strictly by the id the caller asked with; a hostile or confused daemon cannot steer one
  into a conversation the phone never asked about.
- **Fails closed to `null`, never to the caller.** `sessionSettingsRead`'s `flow { }.catch { emit(null) }`
  is scoped to the **inner** read flow, not the outer `observeSessionSettings` — a `.catch` on the outer
  flow would be terminal and end the conversation's re-reads after one failure. The caught throwable is
  **discarded, never logged**: only the `effective_effort` decode failure below is this repository's own
  and content-free; a structural decode failure is authored by kotlinx-serialization, whose message can
  quote the offending input, so dropping it (not just writing a clean message) is what keeps "logs no
  payload content" true. A well-meaning `.catch { Log.w(TAG, it) }` added later would leak daemon payload
  content to Logcat in one line — the drop is load-bearing, flagged in a comment at the site.
- **Gated fail-closed on `interactive`, before any frame is sent.** The daemon leaves a conn that never
  negotiated `interactive` fully inert on this verb — no reply at all, not even a not-found — so an
  ungated send would suspend until teardown rather than erroring. Not sending is what keeps "the read sends
  `request_session_settings` and nothing else" true even in the degenerate case: no claude child starts, no
  model turn begins, no `set_session_settings` rides along, and nothing is written to `AppPreferences`.
- **`readSessionSettings` is the repository's `requestHistory` body minus the fold** — encode
  `RequestSessionSettingsPayloadDto(conversationId)`, `requests.sendAndAwaitReply`, decode the reply through
  [`toSessionSettings()`](mobile-protocol-v2-wire-layer-application-payloads.md#the-session-settings-read-exchange-590).
  It throws rather than converting to `null` itself — `sessionSettingsRead` owns that conversion — so every
  failure mode (pump not `Open`, a server `error`, a malformed reply) stays distinguishable at the seam that
  needs to distinguish them. The verb publishes **no reject codes of its own** (it always answers, even for
  an unhosted/unbound/dormant conversation — the all-zero reply is a successful read of "nothing resolved",
  not a failure), so a server `error` here can only be a generic transport-level one the existing
  `RelayRequests.mapError` arm already handles.
- **`effort` is the saved choice; `effectiveEffort` is the applied reading — neither substitutes for the
  other,** and `effectiveEffort` keeps `session_settings`' one optional wire key's three states apart:
  **key omitted** → `EffectiveEffort.Unavailable` (unsupported, or an older daemon that predates the field
  — such a reply still decodes successfully, it just reads `Unavailable`); **explicit `null`** →
  `EffectiveEffort.NotReported` (Claude reported no effort parameter this turn); **a string** →
  `EffectiveEffort.Applied(value)`, retained verbatim including `""` and a level this build does not
  recognise. See [the decode](mobile-protocol-v2-wire-layer-application-payloads.md#the-session-settings-read-exchange-590)
  for how the two-step decode keeps these apart under `explicitNulls = false`.
- **An empty `permissionMode` means *unavailable*, never Manual approval — and `yolo: false` alone is not
  evidence approvals are enforced.** Both accompany a live child that hasn't confirmed yet, or a dormant
  session with no child. Nothing in this read path manufactures a default for either field; `permissionMode`
  stays an open `String` (not an enum) precisely because a closed set would have to mint a member for "no
  confirmation" a consumer could mistake for a posture, and this read accepts `bypassPermissions` even
  though `setSessionSettings`'s write half refuses that spelling.
- **Token counts (`usedTokens`/`windowTokens`) are `Long`, carried but not consumed here** — the
  pyrycode#720 64-bit-Go-`int` width trap. `windowTokens: 0` means the usage reader is unwired, not an
  empty window; a dormant reply reports `0` in both fields as a pair, never independently. No consumer
  reads them until a later context-figure ticket; carried now because the all-zero/dormant reply naming
  their zero state as decodable is part of this ticket's acceptance.
- **`StableConversationRepository`** delegates `observeSessionSettings` through `switchToLive<SessionSettings?>(null)`
  (the [`observeLastMessage`](remote-conversation-repository-reads-and-thread-store.md#observelastmessageconversationid--the-live-last-message-preview-329)
  shape) — here the connection switch **is** the host-isolation mechanism, not just plumbing:
  `flatMapLatest` drops the previous connection's read the instant the connection changes. `refreshSessionSettings`
  is routed through `currentRepository.value?.refreshSessionSettings(id)`, deliberately **not** through the
  throwing `live` helper — invalidation with no connection is a no-op, not an `IllegalStateException`; it is
  the one facade method on this pair that must not throw.
- **`FakeConversationRepository`** seeds a `MutableStateFlow<Map<String, SessionSettings>>` **empty on
  purpose** — an unseeded conversation reads `null`, the same "unavailable" a live repository reports
  before its first reply, so a consumer's unavailable path is the Fake's default rather than a case a test
  has to arrange. A `setSessionSettingsReading(conversationId, reading)` seam sets or clears one entry (the
  test/preview seam for a populated composer); `refreshSessionSettings` records the ask into
  `sessionSettingsRefreshes` (mirroring `setSessionSettingsCalls`) rather than re-emitting anything — the
  Fake has no wire to re-read, so a seeded reading is already current. This is why `observeSessionSettings`
  /`refreshSessionSettings` join `createWorkspaceFolder`/`delete`/`requestScreenSnapshot`/`requestHistory`
  as the group **all three** impls override, unlike `observeStall`/`observeQueue`/`observeApiRetry`/
  `observeCompacting`, which the Fake deliberately inherits — see
  [`conversation-repository.md`](conversation-repository.md) for the full override-set accounting.
- **No composable or screen change** — data layer only. #649 wires the composer controls to `effort`, #650
  projects `permissionMode`/`yolo` into confirmed permissions, and #651 projects `effectiveEffort`; all
  three name this reading as their source. No live rung: nothing operator-facing changes until #649.
