package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.network.ChangeWorkspacePayloadDto
import de.pyryco.mobile.data.network.ConversationResponseDto
import de.pyryco.mobile.data.network.CreateWorkspaceFolderPayloadDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RecentWorkspacesListPayloadDto
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RenameWorkspacePayloadDto
import de.pyryco.mobile.data.network.WorkspaceFolderCreatedPayloadDto
import de.pyryco.mobile.data.network.WorkspaceUpdatedPayloadDto
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.ERROR_MALFORMED_REPLY
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_CHANGE_WORKSPACE
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_CREATE_WORKSPACE_FOLDER
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_RECENT_WORKSPACES
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_RENAME_WORKSPACE
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.WORKSPACE_FOLDER_PARENT
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.WORKSPACE_REPLY_MALFORMED
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The workspace commands of one connection (#916): the recent-workspaces read, changing a conversation's
 * workspace, creating a workspace folder, and renaming and archiving a workspace, split out of
 * [RemoteConversationRepository] the way the conversation (#914) and message (#915) commands were. The
 * repository keeps the routing — the `workspace_updated` arm of its `onInbound` decodes the frame, applies
 * the label, then completes a [renameWorkspace] waiter, failing a malformed correlated one with
 * [malformedWorkspaceReply] — and a one-line hand-off per public command.
 *
 * Built on [requests], the connection's one request counter and reply waiters, so every frame takes its
 * envelope id from the same sequence as every other request. A confirmed [changeWorkspace] folds into
 * [conversationList]; [archiveWorkspace] reads it for its targets and archives each through
 * [conversationCommands].
 *
 * One instance per repository, and a fresh repository per connection (#351). It holds no state of its own.
 * Nothing here logs.
 */
internal class WorkspaceCommands(
    private val requests: RelayRequests,
    private val conversationList: ConversationListProjection,
    private val conversationCommands: ConversationCommands,
) {
    /**
     * Recently-used workspace folders (#565), a **one-shot request/reply** read verb modeled on
     * [RemoteConversationRepository.observeConversations]'s `list_conversations` but **without** a push projection — #888 is one-shot
     * daemon-side (no live re-emit on change exists to subscribe to). Each collection issues one
     * `recent_workspaces` request (empty `{}` payload) and awaits the correlated `recent_workspaces_list`
     * reply via [RelayRequests.sendAndAwaitReply], so a fresh picker open re-fetches (cold, per-collector; no caching,
     * no cross-collection dedup, no projection touched).
     *
     * Emits the reply's paths in **wire order** — ordering and dedup are daemon-authoritative (#888), so
     * the client does **not** re-sort — after excluding the two "no bound workspace" sentinels the
     * interface contract mandates: the empty string `""` (via [String.isNotBlank], a safe superset the
     * daemon already trims) and [DEFAULT_SCRATCH_CWD] (load-bearing — #888 folds distinct `Cwd` values
     * and does **not** strip scratch).
     *
     * Fails **closed to empty**: `.catch { emit(emptyList()) }` degrades every non-cancellation throwable
     * to one empty emission (AC #4) — the not-`Open` [IllegalStateException] from [RelayRequests.sendAndAwaitReply]'s
     * `check`, a server [RelayErrorException] (this verb names no conversation, so there is **no**
     * `not_found` path), a teardown-mid-await [IllegalStateException] (#488 `failAllPending`), and a
     * malformed-reply decode exception. [kotlinx.coroutines.flow.catch] is cancellation-transparent — it
     * does not swallow the [kotlinx.coroutines.CancellationException] a lifecycle-STOP / sheet-dismiss
     * raises — so a cancelled collect stops cleanly with no spurious empty emit.
     */
    fun recentWorkspaces(): Flow<List<String>> =
        flow {
            val reply = requests.sendAndAwaitReply(recentWorkspacesRequest())
            val list = MobileJson.decodeFromJsonElement<RecentWorkspacesListPayloadDto>(reply)
            emit(list.workspaces.map { it.path }.filter { it.isNotBlank() && it != DEFAULT_SCRATCH_CWD })
        }.catch { emit(emptyList()) }

    private fun recentWorkspacesRequest(): Envelope =
        Envelope(
            id = requests.nextRequestId(),
            type = TYPE_RECENT_WORKSPACES,
            ts = Clock.System.now().toString(),
            payload = JsonObject(emptyMap()),
        )

    /**
     * Change conversation [conversationId]'s workspace to [workspace] over v2 `change_workspace`
     * (#560, server #823). "Workspace" **is** the conversation's `cwd` — there is no separate
     * workspace-id concept. A line-for-line mirror of [ConversationCommands.rename] with a `cwd` payload
     * ([ChangeWorkspacePayloadDto]: `{conversation_id, cwd}`, both required) instead of `{…, name}`:
     * encodes the request, sends it, and awaits its correlated `conversation_updated` reply — the same
     * reply reuse rename relies on. Decodes the reply through the #318 [ConversationResponseDto]
     * boundary, so a malformed reply throws before any state mutation, then **confirmed-upserts** the
     * returned [Conversation] into [ConversationListProjection] — only after the reply decodes — so the new `cwd`
     * becomes visible on the workspace chip / list (AC #2). The folded `cwd` is the
     * **server-authoritative** reply value (the daemon's resolved realpath), not the request's.
     *
     * [workspace] is an **untrusted** path forwarded **verbatim** — no client-side validation,
     * canonicalisation, or `$HOME` check, and the phone never touches the filesystem with it:
     * confinement is the daemon's job (fail-closed, stores the resolved realpath), which re-validates
     * and rejects out-of-`$HOME` / empty paths server-side (`protocol.malformed`), surfaced here as an
     * ordinary [RelayErrorException]. Throws [IllegalArgumentException] for an unknown conversation
     * (server `conversation.not_found`, as [ConversationCommands.rename]), [RelayErrorException] for any other server
     * `error`, [IllegalStateException] when the session is not connected, and the #318 decode exception
     * for a malformed reply — none of which mutate [ConversationListProjection] (AC #3).
     *
     * `change_workspace` performs **no session transition** — it updates the recorded `cwd` only; the
     * new folder takes effect on the conversation's next fresh session spawn (#823 Out-of-Scope, AC
     * #4). So there is no `session_transition` (#336) and no session-boundary delimiter. The interface
     * forces a [Session] return, but there is no session identity to return: the returned placeholder's
     * identity fields (`id`, `claudeSessionUuid`) are **explicitly unassigned** (empty strings, not a
     * fabricated UUID — the [ConversationCommands.startNewSession] precedent); it is never persisted, never enters
     * [ConversationListProjection], and the #560 `onWorkspacePicked` caller discards it.
     */
    suspend fun changeWorkspace(
        conversationId: String,
        workspace: String,
    ): Session {
        val request =
            Envelope(
                id = requests.nextRequestId(),
                type = TYPE_CHANGE_WORKSPACE,
                ts = Clock.System.now().toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        ChangeWorkspacePayloadDto(conversationId = conversationId, cwd = workspace),
                    ),
            )
        // Throws on a server `error` / not-Open session; the decode + confirmed upsert below are
        // unreachable on any failure path. The reply is the bare conversation object (#318 decodes it).
        val reply = requests.sendAndAwaitReply(request)
        conversationList.upsertConversation(MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply))
        // Vestigial: change_workspace has no session transition (AC #4), so no session identity.
        return Session(
            id = "",
            conversationId = conversationId,
            claudeSessionUuid = "",
            startedAt = Clock.System.now(),
            endedAt = null,
        )
    }

    /**
     * Create a new workspace folder named [name] under the fixed client root over v2
     * `create_workspace_folder` (#564, server #887), returning the daemon's canonical created path.
     * The leanest write-verb: it names no conversation, carries no `conversation_id`, touches **no**
     * projection, and — unlike [ConversationCommands.rename] / [changeWorkspace] — its return value (the created path) is
     * the sole effect (it flows to the Workspace Picker's `onPicked` and becomes the selected
     * workspace). A direct analogue of [ConversationCommands.rename] (encode → [RelayRequests.sendAndAwaitReply] → typed-decode) **minus
     * the state fold**, plus a client-side blank-name guard.
     *
     * The interface passes only [name]; the wire request carries a **parent path and a name**
     * ([CreateWorkspaceFolderPayloadDto]). This sends `parent = `[WORKSPACE_FOLDER_PARENT]` (the fixed
     * `~/pyry-workspace` root, tilde-anchored so the daemon resolves it against **its** `$HOME` — a
     * relative `pyry-workspace` would resolve against the daemon's process cwd) and `name = name.trim()`.
     * Both are **untrusted** path components forwarded verbatim — no client-side validation,
     * canonicalisation, or `$HOME` check, and the phone never touches the filesystem with them:
     * confinement is the daemon's job (fail-closed, symlink-resolved, before `MkdirAll`), which also
     * rejects a `name` that is not a clean single element (empty / absolute / separator / `..`),
     * surfaced here as an ordinary [RelayErrorException]. The returned `path` is the
     * **server-authoritative** canonical realpath, not a client-derived join.
     *
     * Throws [IllegalArgumentException] for a blank/whitespace-only [name] (checked **before** any
     * send — no request reaches the wire, mirroring the fake's contract), [IllegalStateException] when
     * the session is not connected, [RelayErrorException] for any server `error` (create has **no**
     * `conversation.not_found` path — every reject is `protocol.malformed`), and the #318 decode
     * exception ([kotlinx.serialization.SerializationException] / [IllegalArgumentException]) for a
     * malformed reply. No projection is folded on any path — a failure leaves no partial state.
     */
    suspend fun createWorkspaceFolder(name: String): String {
        require(name.isNotBlank()) { "name must not be blank" }
        val request =
            Envelope(
                id = requests.nextRequestId(),
                type = TYPE_CREATE_WORKSPACE_FOLDER,
                ts = Clock.System.now().toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        CreateWorkspaceFolderPayloadDto(parent = WORKSPACE_FOLDER_PARENT, name = name.trim()),
                    ),
            )
        // Throws on a server `error` / not-Open session before the decode below. The reply is the bare
        // {path} object (#318 decodes it); a malformed reply throws here. No state is folded.
        val reply = requests.sendAndAwaitReply(request)
        return MobileJson.decodeFromJsonElement<WorkspaceFolderCreatedPayloadDto>(reply).path
    }

    /**
     * Set or clear the label this host stores for the workspace at [path] over v2 `rename_workspace`
     * (#663). [path] and [label] go out verbatim ([RenameWorkspacePayloadDto]); the daemon validates the
     * label. The [RemoteConversationRepository.TYPE_WORKSPACE_UPDATED] arm of the repository's `onInbound`
     * applies the correlated reply through [ConversationListProjection.applyWorkspaceLabel] **before**
     * completing this waiter, so the rows are relabelled by the time this returns — there is nothing left
     * to fold here.
     *
     * The reply is still re-decoded and must name [path]: a frame of another type correlated to this id
     * (the shared arms complete any waiter with any payload), or a `workspace_updated` for a different
     * path, would otherwise report success while [path] stayed unlabelled. That case, and a malformed
     * reply, throw [RelayErrorException] with [ERROR_MALFORMED_REPLY]. Daemon refusals keep their code
     * through [RelayRequests.mapError] (`workspace.not_found` stays a [RelayErrorException], never the
     * unknown-conversation [IllegalArgumentException]); a not-`Open` pump or teardown mid-await throws
     * [IllegalStateException]. Not gated on `interactive`. Logs nothing, and no exception message carries
     * the path or the label.
     */
    suspend fun renameWorkspace(
        path: String,
        label: String?,
    ) {
        val request =
            Envelope(
                id = requests.nextRequestId(),
                type = TYPE_RENAME_WORKSPACE,
                ts = Clock.System.now().toString(),
                payload = MobileJson.encodeToJsonElement(RenameWorkspacePayloadDto(path = path, label = label)),
            )
        val reply = requests.sendAndAwaitReply(request)
        val confirmedPath =
            try {
                MobileJson.decodeFromJsonElement<WorkspaceUpdatedPayloadDto>(reply).path
            } catch (e: IllegalArgumentException) {
                null
            }
        if (confirmedPath != path) throw malformedWorkspaceReply()
    }

    /**
     * Archive every active row at [path] on this host (#663) — the client-side fan-out desktop's
     * `requestArchiveWorkspace` performs, since the wire has no workspace archive verb. Targets are a
     * one-time snapshot of [ConversationListProjection.current]: rows whose `cwd` equals [path] by plain
     * [String] equality (no trim, no normalization) and that are not already archived. Before the first
     * snapshot there are no rows, so the
     * call does not wait for a list, and no targets means no frame.
     *
     * Each target goes through [ConversationCommands.archive] **sequentially**, which confirmed-upserts its
     * own row on its own reply, so rows leave the active tiers one by one. A failure is kept and the loop moves on; after the
     * last row the first failure is rethrown unchanged. A torn-down connection surfaces as the
     * [IllegalStateException] of [RelayRequests.failAllPending] and then of the not-connected check, so the rest fail
     * fast. Caller cancellation is rethrown at once — checked before the general catch, because
     * [CancellationException] is itself an [IllegalStateException]. Sends no rename or delete, so the
     * stored label stays. Logs nothing: neither the path nor a row id reaches Logcat.
     */
    suspend fun archiveWorkspace(path: String) {
        val targets =
            conversationList
                .current()
                .filter { it.cwd == path && !it.archived }
                .map { it.id }
        var firstFailure: Exception? = null
        for (conversationId in targets) {
            try {
                conversationCommands.archive(conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstFailure == null) firstFailure = e
            }
        }
        firstFailure?.let { throw it }
    }

    fun malformedWorkspaceReply() =
        RelayErrorException(code = ERROR_MALFORMED_REPLY, retryable = false, message = WORKSPACE_REPLY_MALFORMED)
}
