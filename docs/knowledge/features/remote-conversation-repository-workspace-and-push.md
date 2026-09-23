# Remote conversation repository — the Phase 4 `ConversationRepository` — workspace folders, recent workspaces and push registration

Split out of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md); see that document for what it does, its edge cases and its links.

## `createWorkspaceFolder(name)` — the tenth mutation, leanest write-verb, first override of a previously-defaulted read/write pair ([#564](../codebase/564.md))

Creates a new workspace folder on the daemon over v2 `create_workspace_folder` (server pyrycode#887),
overriding the interface's throwing default (`error(...)`) this method inherited unchanged since #312.
Unlike every prior mutation it **names no conversation** — no `conversation_id` field, no projection
fold — and its reply is a **new** type (`workspace_folder_created`), not a reuse of
`conversation_updated`. Its return value (the created path) is the sole effect. The body now lives on
`WorkspaceCommands` (#916, `data/repository/WorkspaceCommands.kt`, the same class `renameWorkspace` /
`archiveWorkspace` / `recentWorkspaces` below moved into); the repository's `override suspend fun
createWorkspaceFolder` is a one-line hand-off:

```kotlin
// WorkspaceCommands
suspend fun createWorkspaceFolder(name: String): String {
    require(name.isNotBlank()) { "name must not be blank" }
    val request = Envelope(
        id = requests.nextRequestId(), type = TYPE_CREATE_WORKSPACE_FOLDER, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(
            CreateWorkspaceFolderPayloadDto(parent = WORKSPACE_FOLDER_PARENT, name = name.trim()),
        ),
    )
    val reply = requests.sendAndAwaitReply(request)   // throws on server `error` / not-Open before any decode
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
  other write-verb `RelayRequests.mapError`'s `IllegalArgumentException` branch is never reached from the server here —
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

## `renameWorkspace` and `archiveWorkspace` — the two workspace-row verbs desktop already had (#663)

Adds desktop's two remaining workspace operations for [#664](../codebase/664.md)'s Edit workspace modal to
call; this ticket draws no UI. Both override throwing interface defaults, the `createWorkspaceFolder` shape,
and both are delegated one line each by [`StableConversationRepository`](stable-conversation-repository.md)
to its `live` handle, the `setSystemPrompt` shape. `HostConversationSource.repositoryFor(serverId)` is
already the host targeting — one repository instance per host — so neither verb adds routing.

**`renameWorkspace(path, label)` is a daemon verb; `archiveWorkspace(path)` is not.** The wire has
`rename_workspace {path, label}`, answered by a correlated `workspace_updated` — the **same** push
[#721](#721-apply-workspace-label-updates-and-the-conversation_updated-split) already applied unconditionally
in [`onInbound`](remote-conversation-repository-reads-and-thread-store.md#the-repository--one-projection-cold-fan-out)
for unsolicited relabels. #721 shipped the apply with nothing to complete; this ticket adds the sender and
the waiter together. The daemon has no `archive_workspace`: `archiveWorkspace` is a client-side fan-out of
the existing per-conversation `archive` (since #914 `ConversationCommands.archive`, still built on the
private `sendArchiveToggle` helper), one `archive_conversation` per active row on
this host whose `cwd` equals `path`. It sends no rename and no delete, so the stored label survives an
archive. `renameWorkspace` was not moved by #914 (which only moved `ConversationCommands`), but both
`renameWorkspace` and `archiveWorkspace` were moved by #916 onto `WorkspaceCommands`
(`data/repository/WorkspaceCommands.kt`, the same class `createWorkspaceFolder` above and
`recentWorkspaces` below live on); the repository's `override suspend fun renameWorkspace` /
`archiveWorkspace` are one-line hand-offs, and `onInbound`'s `TYPE_WORKSPACE_UPDATED` arm now calls
`workspaceCommands.malformedWorkspaceReply()` (was a private repository function, now public on the new
class so the repository can still reach it):

```kotlin
// WorkspaceCommands
suspend fun renameWorkspace(path: String, label: String?) {
    val request = Envelope(
        id = requests.nextRequestId(), type = TYPE_RENAME_WORKSPACE, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(RenameWorkspacePayloadDto(path = path, label = label)),
    )
    val reply = requests.sendAndAwaitReply(request)
    val confirmedPath = try {
        MobileJson.decodeFromJsonElement<WorkspaceUpdatedPayloadDto>(reply).path
    } catch (e: IllegalArgumentException) { null }
    if (confirmedPath != path) throw malformedWorkspaceReply()
}

suspend fun archiveWorkspace(path: String) {
    val targets = conversationList.current().filter { it.cwd == path && !it.archived }.map { it.id }
    var firstFailure: Exception? = null
    for (conversationId in targets) {
        try {
            conversationCommands.archive(conversationId)   // ConversationCommands.archive (#914), called directly
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (firstFailure == null) firstFailure = e
        }
    }
    firstFailure?.let { throw it }
}
```

- **A reply from the shared correlated-reply machinery has to be checked against what the call promised,
  not just awaited.** The `TYPE_WORKSPACE_UPDATED` arm looks up the waiter by `in_reply_to` **before**
  decoding, applies `applyWorkspaceLabel` first, and completes the waiter only after — so the caller resumes
  onto rows that already carry the new label — but the shared correlated arms elsewhere in `onInbound`
  complete *any* waiter with *whatever* payload arrives under its id. A `conversation_updated`, or a
  `workspace_updated` for a **different** path, correlated to this request's id would otherwise complete the
  waiter successfully with `path` left unlabelled. `renameWorkspace` re-decodes the completed payload as
  `WorkspaceUpdatedPayloadDto` and requires `decoded.path == path`, throwing `RelayErrorException` with
  `ERROR_MALFORMED_REPLY` otherwise (also covers a plain decode failure). The verifier's one recorded NIT: a
  correlated `workspace_updated` naming a *different* path is still applied to **that other path's** rows
  before the mismatch is caught — consistent with #721's apply-unconditionally rule (a lying daemon could
  reach the same row through an ungated `conversations` snapshot anyway), left as-is and recorded for #664's
  error copy.
- **The reason a malformed correlated reply cannot be left to the generic decode-or-drop.** Every other
  `onInbound` arm's decode failure just drops the frame and leaves the collector alive — safe, because
  nothing is waiting on it. A `rename_workspace` reply is different: dropping it silently would leave
  `renameWorkspace`'s caller suspended until teardown (`RelayRequests.failAllPending`) instead of failing promptly. So the
  `TYPE_WORKSPACE_UPDATED` arm's decode `catch` calls `waiter?.completeExceptionally(malformedWorkspaceReply())`
  before returning — only when a waiter exists; an unsolicited malformed push still just drops. The
  kotlinx `SerializationException` itself is never forwarded, since its message can quote the payload (a
  path and a label); the waiter gets a static `RelayErrorException(ERROR_MALFORMED_REPLY, ...)` instead.
- **A test asserting "the label is visible when the call returns" can pass for the wrong reason.** Under
  `StandardTestDispatcher` the caller resumes one dispatch after `waiter.complete()`, by which point the
  apply has already run regardless of ordering in the source — so a test on that dispatcher cannot tell
  "apply-then-complete" from "complete-then-apply". `RemoteConversationRepositoryWorkspaceTest`'s caller
  coroutine runs on `UnconfinedTestDispatcher`, so `complete()` resumes it **inside** the inbound collector,
  immediately after the line that calls it — the only way the ordering assertion is load-bearing.
- **`archiveWorkspace` targets a one-time snapshot of `ConversationListProjection.current()`, never a fetched
  or awaited list.**
  A `null` projection (no `conversations` snapshot yet) yields no targets, matching the ticket's "does not
  wait for a list that has not arrived yet." A path with no matching active row sends nothing at all — no
  frame, no round trip. Archived rows at the same path are excluded (already inactive) but never touched
  either way; `archive`'s own confirmed-upsert (on `ConversationCommands` since #914, unchanged from its
  existing per-conversation shape) is what
  actually moves a row off the active list, one row at a time as its own reply lands.
- **Sequential, not concurrent, and `CancellationException` is checked first because it is itself an
  `IllegalStateException`.** Archiving one row at a time keeps ordering deterministic and means a lost
  connection (`RelayRequests.sendAndAwaitReply`'s not-connected `check`, or `RelayRequests.failAllPending`
  mid-await) fails every
  remaining row immediately rather than after a full timeout each — "try the rest" costs nothing once the
  pump is dead. The per-row `catch` tests `CancellationException` before the general `Exception` catch and
  rethrows it at once; getting that order backwards would swallow a caller's own cancellation as an ordinary
  per-row failure and keep firing archive requests after the caller gave up (flagged SHOULD FIX in the
  plan's security review, implemented as shipped). The loop always finishes the remaining rows before
  throwing the **first** failure — confirmed rows stay archived, refused rows stay active, and a retry's
  fresh snapshot sends only the still-active ones.
- **`RenameWorkspacePayloadDto` relies on `MobileJson`'s `explicitNulls = false` to encode "clear" as
  "omitted."** `RenameWorkspacePayloadDto(path, label: String? = null)` is encode-only; a `null` label is
  dropped from the JSON entirely rather than serialized as `"label":null`. The daemon reads a missing
  `label` the same way it reads an explicit `null` — both clear the stored label — so the two encodings are
  interchangeable on the wire, and `RenameWorkspacePayloadDtoTest` pins both shapes byte-for-byte rather
  than trusting that behaviour by inference.
- **Path comparison is exact-bytes, matching `applyWorkspaceLabel`'s existing posture.** `archiveWorkspace`
  filters `it.cwd == path` with plain `String` equality — no trim, no normalization — so a workspace at
  `/w/alpha` and a sibling row at `/w/alpha/` or `/w/alpha ` are different targets, never conflated.
- **Nothing here is logged, on either verb, on any branch.** `path` is a location on the daemon's host and
  `label` is operator-authored text; neither appears in a log call or in any exception message this ticket
  adds (`WORKSPACE_REPLY_MALFORMED` is a static string).

## `recentWorkspaces()` — the fourth read verb, leanest of the family, no fold ([#565](../codebase/565.md))

Lists recently-used workspace folders over v2 `recent_workspaces` (server pyrycode#888, the recents
half of the #825 split whose create half is [#564](../codebase/564.md)), overriding the interface's
`flowOf(emptyList())` default this method inherited unchanged since #312. Unlike `observeConversations`
/ `observeLastMessage` / `observeMessages` there is **no push projection to subscribe to** — #888 is a
**one-shot** request/reply, so this is a cold flow that issues one request and awaits one correlated
reply **per collection**, not a flow over a `StateFlow` fed by the always-running inbound collector. The
body now lives on `WorkspaceCommands` (#916, `data/repository/WorkspaceCommands.kt`, the same class
`createWorkspaceFolder`/`renameWorkspace`/`archiveWorkspace` above live on); the repository's `override
fun recentWorkspaces` is a one-line hand-off:

```kotlin
// WorkspaceCommands
fun recentWorkspaces(): Flow<List<String>> =
    flow {
        val reply = requests.sendAndAwaitReply(recentWorkspacesRequest())
        val list = MobileJson.decodeFromJsonElement<RecentWorkspacesListPayloadDto>(reply)
        emit(list.workspaces.map { it.path }.filter { it.isNotBlank() && it != DEFAULT_SCRATCH_CWD })
    }.catch { emit(emptyList()) }

private fun recentWorkspacesRequest(): Envelope =
    Envelope(
        id = requests.nextRequestId(), type = TYPE_RECENT_WORKSPACES, ts = Clock.System.now().toString(),
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
deferred) and no new error mapping. The body now lives on `ConversationCommands` (#914,
`data/repository/ConversationCommands.kt`); the repository's `suspend fun registerPushToken` is a one-line
hand-off.

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
// ConversationCommands
suspend fun registerPushToken(token: String) {
    val request = Envelope(
        id = requests.nextRequestId(),
        type = TYPE_REGISTER_PUSH_TOKEN, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(
            RegisterPushTokenPayloadDto(platform = PLATFORM_FCM, token = token, deviceName = deviceName)),
    )
    requests.sendAndAwaitReply(request)   // throws on server `error` / not-Open; the empty {} ack carries nothing → ignored
}
```

- **`RegisterPushTokenPayloadDto`** is the fifth encode-only request DTO (`{platform, token, device_name}`,
  all required) — see the [wire-layer doc](mobile-protocol-v2-wire-layer-application-payloads.md#outbound-request-encoders--the-ackerror-correlated-reply-models-346).
  `platform` is the constant `"fcm"`; `device_name` is the `deviceName` `ConversationCommands` is
  constructed with — the repository's connection-level device name, unchanged by the #914 move.
- **`RelayRequests.sendAndAwaitReply` does all the work, unchanged:** it throws `IllegalStateException` when
  the pump send
  returns `false` (session not Open), suspends until the correlated reply lands, returns normally on the
  empty `ack`, and rethrows the collector's exceptional completion on `error` (a `RelayErrorException`
  carrying `code`/`retryable` via the unchanged `RelayRequests.mapError` — `server.binary_busy` retryable /
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
> (pyrycode #319 acks with no registry touch only on a matching triple). [#361](../codebase/361.md) then
> gave the capability its token origin ([`PyryMessagingService.onNewToken`](push-messaging-service.md) →
> `AppPreferences.setPushToken`), so `registerPushToken` now has a live caller and a live token in
> production once a phone has ever received one.
>
> _(The #359 `deviceName` param KDoc at `RemoteConversationRepository.kt:72-76` still describes `deviceName`
> as defaulted for a not-yet-live caller — stale since #365 landed the live caller and stale a second time
> now that #361 landed the token; a known-stale comment #365's code review already flagged as an optional
> NIT and deferred, since the file was outside that PR's surface.)_
