# Remote conversation repository — the Phase 4 `ConversationRepository` — workspace folders, recent workspaces and push registration

Split out of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md); see that document for what it does, its edge cases and its links.

## `createWorkspaceFolder(name)` — the tenth mutation, leanest write-verb, first override of a previously-defaulted read/write pair ([#564](../codebase/564.md))

Creates a new workspace folder on the daemon over v2 `create_workspace_folder` (server pyrycode#887),
overriding the interface's throwing default (`error(...)`) this method inherited unchanged since #312.
Unlike every prior mutation it **names no conversation** — no `conversation_id` field, no projection
fold — and its reply is a **new** type (`workspace_folder_created`), not a reuse of
`conversation_updated`. Its return value (the created path) is the sole effect:

```kotlin
override suspend fun createWorkspaceFolder(name: String): String {
    require(name.isNotBlank()) { "name must not be blank" }
    val request = Envelope(
        id = requestId.incrementAndGet(), type = TYPE_CREATE_WORKSPACE_FOLDER, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(
            CreateWorkspaceFolderPayloadDto(parent = WORKSPACE_FOLDER_PARENT, name = name.trim()),
        ),
    )
    val reply = sendAndAwaitReply(request)   // throws on server `error` / not-Open before any decode
    return MobileJson.decodeFromJsonElement<WorkspaceFolderCreatedPayloadDto>(reply).path
}
```

- **The interface passes only `name`; the wire needs a parent + name.** The client sends a **fixed**
  `parent = "~/pyry-workspace"` constant (`WORKSPACE_FOLDER_PARENT`) alongside the trimmed `name`. The
  tilde prefix is load-bearing: the daemon resolves `parent` against **its own** `$HOME` before joining
  `name` — a bare relative `"pyry-workspace"` would resolve against the daemon process's cwd instead
  (unpredictable). Verified byte-for-byte against the daemon's golden fixture
  (`testdata/create_workspace_folder.json` → `{"parent":"~/pyry-workspace","name":"new-project"}`) and
  matches the Figma trigger row ("…under pyry-workspace") and the fake's `pyry-workspace/$name`
  convention (a benign fake≠remote divergence — the fake returns a relative path, the remote the
  daemon's absolute realpath; both satisfy "the created path becomes the selected workspace").
- **New reply type, new demux arm — the same delete-family hazard, closed the same way.** `onInbound`'s
  correlated-reply `when` arm gained `TYPE_WORKSPACE_FOLDER_CREATED` alongside `TYPE_CONVERSATION_DELETED`
  et al. Skipping this would leave `workspace_folder_created` unrouted (falls to `else -> Unit`), so the
  pending deferred would never complete — the same [`delete`](remote-conversation-repository-conversation-writes.md#deleteconversationid--the-eighth-mutation-first-remove-shaped-one-532)
  hazard, not the `changeWorkspace` case (which reused an already-routed type and needed no demux edit).
- **No fold at all — the first mutation with zero projection writes.** `delete` removes from three
  streams, every upsert-shaped mutation writes one; `createWorkspaceFolder` touches **none**. The daemon
  creates a directory and replies to the requester only — no broadcast, no registry entry, no session
  transition. The returned `path` is handed straight to the picker's `onPicked`; nothing is stored in
  `projection`/`threadByConversation`/`lastMessages`.
- **No `conversation.not_found` path exists for this verb** (it names no conversation), so unlike every
  other write-verb `mapError`'s `IllegalArgumentException` branch is never reached from the server here —
  every server reject (malformed / empty-parent / bad-name / rejected-target) is `protocol.malformed` →
  `RelayErrorException`. The only `IllegalArgumentException` on this path is the client-side blank-name
  guard, thrown **before** any send.
- **The returned `path` is server-authoritative and untouched by the phone.** Both outbound path
  components (the fixed `parent`, the untrusted `name`) and the inbound `path` are wire strings only —
  the phone never opens, joins, or canonicalises any of them. `$HOME` confinement and name validation
  (non-empty / not absolute / no separator / no `..`) are entirely server-side, fail-closed, before the
  daemon's `MkdirAll`.
- **The picker's failure surface is the actual crash fix.** Before this ticket `WorkspacePicker.kt`'s
  create-launch had no `try`/`catch`, so any throw here (not-connected, server error, malformed reply)
  crashed the app — reproducing identically from the thread workspace flow, the settings default-workspace
  row, and the FAB long-press picker (all three share this one host). See
  [`WorkspacePicker`](workspace-picker.md) § Error handling for the fix.
- **`mutationsSupported` stays irrelevant here** — this affordance is not gated by that flag (it's not a
  conversation-scoped mutation the #537 family covers); the picker is reachable today from all three entry
  points regardless of the coarse flag's value.

## `recentWorkspaces()` — the fourth read verb, leanest of the family, no fold ([#565](../codebase/565.md))

Lists recently-used workspace folders over v2 `recent_workspaces` (server pyrycode#888, the recents
half of the #825 split whose create half is [#564](../codebase/564.md)), overriding the interface's
`flowOf(emptyList())` default this method inherited unchanged since #312. Unlike `observeConversations`
/ `observeLastMessage` / `observeMessages` there is **no push projection to subscribe to** — #888 is a
**one-shot** request/reply, so this is a cold flow that issues one request and awaits one correlated
reply **per collection**, not a flow over a `StateFlow` fed by the always-running inbound collector:

```kotlin
override fun recentWorkspaces(): Flow<List<String>> =
    flow {
        val reply = sendAndAwaitReply(recentWorkspacesRequest())
        val list = MobileJson.decodeFromJsonElement<RecentWorkspacesListPayloadDto>(reply)
        emit(list.workspaces.map { it.path }.filter { it.isNotBlank() && it != DEFAULT_SCRATCH_CWD })
    }.catch { emit(emptyList()) }

private fun recentWorkspacesRequest(): Envelope =
    Envelope(
        id = requestId.incrementAndGet(), type = TYPE_RECENT_WORKSPACES, ts = Clock.System.now().toString(),
        payload = JsonObject(emptyMap()),
    )
```

- **Cold, per-collection — a fresh picker open re-fetches.** No caching, no cross-collection dedup,
  and (the structural delta from every mutation in this file) **zero projection writes**: `projection`,
  `lastMessages`, and `threadByConversation` are all untouched. The method's only effect is its single
  emission.
- **New reply type, new demux arm — the same delete/create-family hazard, closed the same way.**
  `onInbound`'s correlated-reply `when` arm gained `TYPE_RECENT_WORKSPACES_LIST` alongside
  `TYPE_WORKSPACE_FOLDER_CREATED` et al. Skipping this would leave `recent_workspaces_list` unrouted
  (falls to `else -> Unit`), so the pending deferred would never complete — the same hazard as
  [`delete`](remote-conversation-repository-conversation-writes.md#deleteconversationid--the-eighth-mutation-first-remove-shaped-one-532) and
  [`createWorkspaceFolder`](#createworkspacefoldername--the-tenth-mutation-leanest-write-verb-first-override-of-a-previously-defaulted-readwrite-pair-564)
  before it.
- **No client re-sort or re-dedup — ordering is daemon-authoritative.** The handler folds the
  conversations registry's distinct non-empty `Cwd` values, most-recent-first, and the client preserves
  wire order verbatim. Verified by a test that pushes deliberately non-alphabetical paths and asserts
  the emission matches wire order exactly.
- **Client filters two "no bound workspace" sentinels, one load-bearing.** The interface contract
  ([`ConversationRepository.recentWorkspaces`](conversation-repository.md)) excludes both `""` and
  [`DEFAULT_SCRATCH_CWD`](conversation-repository.md). The daemon already skips empty/whitespace `Cwd`
  server-side, so the `isNotBlank()` filter is belt-and-suspenders there — but the daemon does **not**
  strip `DEFAULT_SCRATCH_CWD` (a conversation bound to the scratch dir would otherwise surface it), so
  the `!= DEFAULT_SCRATCH_CWD` clause is the one genuinely load-bearing client-side rule this method
  applies.
- **Fails closed to empty, not to an exception — the structural delta from every mutation.** Every prior
  mutation propagates its failure to the caller (a thrown `IllegalStateException` /
  `RelayErrorException` / decode exception). This read verb instead wraps its `flow { }` in
  `.catch { emit(emptyList()) }`, degrading not-connected, any server `error` (there is **no**
  `conversation.not_found` path — the verb names no conversation), and a malformed-reply decode
  exception all to one empty emission — matching the AC's "the Recent section degrades to empty rather
  than erroring the collector." `Flow.catch` is **cancellation-transparent** (it does not swallow
  `CancellationException`), so a lifecycle-STOP / sheet-dismiss cancelling the
  `collectAsStateWithLifecycle` collector still propagates normally and stops the collection cleanly —
  the first verb in the family to lean on that distinction.
- **The picker needed no change.** [`WorkspacePicker`](workspace-picker.md) already collected and
  rendered `recentWorkspaces()` from the moment it was written (against the interface default, always
  empty); this ticket closes the loop purely in the data layer.

## `registerPushToken(token)` — the device-concern push registration (#359)

Registers the phone's FCM push token with the paired daemon over v2 `register_push_token`, so the daemon
knows where to send a wake notification when the phone is backgrounded. [#359](../codebase/359.md)
implements it as a **pure request/reply** that reuses #346's correlation primitive and `mapError`
**verbatim** — it adds no `onInbound` branch (the `ack`/`error` arms already complete the pending
deferred) and no new error mapping.

**Two load-bearing departures from the three #314 mutations:**

1. **It is NOT a `ConversationRepository` interface method.** Push-token registration is a
   device/connection concern, not a conversation operation, so `registerPushToken` is a public method on
   the **concrete** class only — never an interface override. Per [[post-352-connection-scoped-repo-behind-facade]]
   the repo is connection-scoped behind the process-lifetime
   [`StableConversationRepository`](stable-conversation-repository.md) facade ViewModels hold, and that
   facade only delegates the `ConversationRepository` interface — so a non-interface method **deliberately
   will not reach consumers through the facade**. The live caller therefore holds the **concrete** handle:
   [#365](../codebase/365.md)'s connect-time hook in the coordinator (which retains the concrete
   `RemoteConversationRepository` it constructs) calls this directly — no facade exposure, new interface
   method, or DI reachability was added.
2. **It mutates no projection.** Unlike `sendMessage` / `createDiscussion` / `promote`, this registers a
   token and produces **no domain object** — the success signal is simply "the call returned without
   throwing". `projection` / `lastMessages` / `threadByConversation` are untouched, so (unlike #346) no
   projection KDoc needed amending.

The flow (the entire method, ≤ ~12 lines):

```kotlin
suspend fun registerPushToken(token: String) {
    val request = Envelope(
        id = requestId.incrementAndGet(),
        type = "register_push_token", ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(
            RegisterPushTokenPayloadDto(platform = "fcm", token = token, deviceName = deviceName)),
    )
    sendAndAwaitReply(request)   // throws on server `error` / not-Open; the empty {} ack carries nothing → ignored
}
```

- **`RegisterPushTokenPayloadDto`** is the fifth encode-only request DTO (`{platform, token, device_name}`,
  all required) — see the [wire-layer doc](mobile-protocol-v2-wire-layer.md#outbound-request-encoders--the-ackerror-correlated-reply-models-346).
  `platform` is the constant `"fcm"`; `device_name` is the connection-level constructor `deviceName`.
- **`sendAndAwaitReply` does all the work, unchanged:** it throws `IllegalStateException` when `pump.send`
  returns `false` (session not Open), suspends until the correlated reply lands, returns normally on the
  empty `ack`, and rethrows the collector's exceptional completion on `error` (a `RelayErrorException`
  carrying `code`/`retryable` via the unchanged `mapError` — `server.binary_busy` retryable /
  `auth.invalid_token` not, so a caller can branch on `retryable`). The returned `{}` ack payload is
  ignored — there is nothing to decode and no projection to fold.
- **No client-side dedupe** — the server dedupes the `(platform, token, device_name)` triple, so this just
  sends. **Never logs the `token`** (the #346 no-secrets posture).

> **Device-name plumbing — the cross-slice handoff #359 deferred, closed by [#365](../codebase/365.md).**
> `device_name` must equal `NoiseClientInfo.deviceName`. #359 threaded it as the **last, defaulted**
> constructor param so that slice touched neither `AppModule` nor the
> [coordinator](relay-repository-coordinator.md) (no #352 conflict), leaving `""` as a placeholder with no
> live caller. #365 then added the live caller and threaded the real value: a `deviceName` param on
> `RelayRepositoryCoordinator` supplied from `NoiseClientInfo` in `AppModule`, passed through to this
> constructor. So `""` is **no longer the production value** — a live caller wired *without* that threading
> would have sent `device_name: ""`, polluting the server's `(platform, token, device_name)` dedup triple
> (pyrycode #319 acks with no registry touch only on a matching triple). The capability now stays dormant
> only until Firebase #361 *stores* a token for the hook to read.
>
> _(The #359 `deviceName` param KDoc at `RemoteConversationRepository.kt:72-76` still describes this as
> the Firebase sibling's pending handoff — a known-stale comment #365's code review flagged as an optional
> NIT and deferred, since the file is outside that PR's surface.)_
