package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.network.ArchiveConversationPayloadDto
import de.pyryco.mobile.data.network.ConversationDeletedPayloadDto
import de.pyryco.mobile.data.network.ConversationResponseDto
import de.pyryco.mobile.data.network.CreateConversationPayloadDto
import de.pyryco.mobile.data.network.DeleteConversationPayloadDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ModalAnswerPayloadDto
import de.pyryco.mobile.data.network.ModalCancelPayloadDto
import de.pyryco.mobile.data.network.PromoteConversationPayloadDto
import de.pyryco.mobile.data.network.RegisterPushTokenPayloadDto
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RenameConversationPayloadDto
import de.pyryco.mobile.data.network.SetConversationMutedPayloadDto
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.PLATFORM_FCM
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_ARCHIVE_CONVERSATION
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_CREATE_CONVERSATION
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_DELETE_CONVERSATION
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_INTERRUPT
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_MODAL_ANSWER
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_MODAL_CANCEL
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_NEW_SESSION
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_PROMOTE_CONVERSATION
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_REGISTER_PUSH_TOKEN
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_RENAME_CONVERSATION
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_SET_CONVERSATION_MUTED
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_UNARCHIVE_CONVERSATION
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The conversation commands of one connection (#914): create, promote, rename, archive, unarchive, delete,
 * new session, interrupt, push-token registration and the modal answer and cancel, split out of
 * [RemoteConversationRepository] the way the projections were (#912, #913). The repository keeps the
 * routing and a one-line hand-off per public command.
 *
 * Built on [requests], the connection's one request counter and reply waiters, so every command takes its
 * envelope id from the same sequence as every other request. [send] is the repository's pump send, used
 * only by the fire-and-forget frames. Confirmed replies fold into [conversationList]; a delete also clears
 * [threadProjection]. [deviceName] is the repository's connection-level device name.
 *
 * One instance per repository, and a fresh repository per connection (#351), so the state is
 * connection-scoped exactly as it was when it lived in the repository. Nothing here logs.
 */
internal class ConversationCommands(
    private val requests: RelayRequests,
    private val send: (Envelope) -> Boolean,
    private val conversationList: ConversationListProjection,
    private val threadProjection: ThreadProjection,
    private val deviceName: String,
) {
    /**
     * Create an unpromoted discussion over v2 `create_conversation` (#347). Encodes the request
     * ([CreateConversationPayloadDto]: `is_promoted=false`, optional `cwd`), sends it, and awaits its
     * correlated `conversation_created` reply — the **typed** bare-conversation payload (contrast
     * [RemoteConversationRepository.sendMessage]'s empty `ack`, which it reconstructs from input). Decodes
     * the reply through the #318 [ConversationResponseDto] boundary, so a malformed reply throws before any
     * state mutation, then **confirmed-inserts** the returned [Conversation] into [ConversationListProjection]
     * — only after the reply decodes — so [RemoteConversationRepository.observeConversations] re-emits with
     * it (AC #2). The returned `cwd` is the **server-assigned** value from the reply (a null [workspace]
     * requests a scratch cwd the server picks), never the input (AC #1).
     *
     * Throws [RelayErrorException] for a server `error`, [IllegalStateException] when the session is
     * not connected, and the #318 decode exception ([kotlinx.serialization.SerializationException] /
     * [IllegalArgumentException]) for a malformed reply — none of which mutate [ConversationListProjection] (AC #3).
     */
    suspend fun createDiscussion(workspace: String?): Conversation = create(CreateConversationPayloadDto(cwd = workspace))

    /**
     * Create a named, promoted channel in [workspace] over one v2 `create_conversation` (#956) —
     * `is_promoted=true`, [name] and [workspace] all sent **verbatim** (the caller trims) — instead of a
     * discussion promoted afterwards. Same reply, decode, confirmed insert and failure contract as
     * [createDiscussion]; the returned conversation holds the daemon's values, never the request's.
     */
    suspend fun createChannel(
        name: String,
        workspace: String?,
    ): Conversation = create(CreateConversationPayloadDto(isPromoted = true, name = name, cwd = workspace))

    /**
     * The shared `create_conversation` round trip behind [createDiscussion] and [createChannel]: send
     * [payload], await the correlated `conversation_created`, decode it through [ConversationResponseDto]
     * and only then insert it into [ConversationListProjection].
     */
    private suspend fun create(payload: CreateConversationPayloadDto): Conversation {
        val request =
            Envelope(
                id = requests.nextRequestId(),
                type = TYPE_CREATE_CONVERSATION,
                ts = Clock.System.now().toString(),
                payload = MobileJson.encodeToJsonElement(payload),
            )
        // Throws on a server `error` / not-Open session; the decode + confirmed insert below are
        // unreachable on any failure path. The reply is the bare conversation object (#318 decodes it).
        val reply = requests.sendAndAwaitReply(request)
        return conversationList.upsertConversation(MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply))
    }

    /**
     * Promote an existing (scratch) conversation into a named, persistent channel over v2
     * `promote_conversation` (#348). Resolves the required wire `cwd` from [workspace] or — when null
     * ("promote in place") — the conversation's existing cwd in [ConversationListProjection], encodes the request
     * ([PromoteConversationPayloadDto], all three fields required), sends it, and awaits its correlated
     * `conversation_updated` reply — the **typed** bare-conversation payload (contrast
     * [RemoteConversationRepository.sendMessage]'s empty `ack`). Decodes the reply through the #318
     * [ConversationResponseDto] boundary, so a malformed reply throws before any state mutation, then
     * **confirmed-upserts** the returned [Conversation] into [ConversationListProjection] — only after the
     * reply decodes — so [RemoteConversationRepository.observeConversations] re-emits with it now in the
     * Channels tier (AC #2). The returned `name`/`cwd`/`isPromoted` are the **server-authoritative** reply
     * values (AC #1), never the request's resolved cwd.
     *
     * Throws [IllegalArgumentException] for an unknown conversation (server `conversation.not_found`,
     * mirroring the fake's type), [RelayErrorException] for any other server `error`,
     * [IllegalStateException] when the session is not connected, and the #318 decode exception
     * ([kotlinx.serialization.SerializationException] / [IllegalArgumentException]) for a malformed
     * reply — none of which mutate [ConversationListProjection] (AC #3).
     */
    suspend fun promote(
        conversationId: String,
        name: String,
        workspace: String?,
    ): Conversation {
        // Null workspace ("promote in place") resolves to the conversation's existing cwd from the read
        // projection — the remote analog of the fake's `workspace ?: record.conversation.cwd`. The
        // `?: ""` fallback is only reachable when the conversation is absent from the projection (not
        // reachable from the shipped UI, which only promotes a visible, hence loaded, conversation).
        val cwd = workspace ?: conversationList.current().firstOrNull { it.id == conversationId }?.cwd ?: ""
        val request =
            Envelope(
                id = requests.nextRequestId(),
                type = TYPE_PROMOTE_CONVERSATION,
                ts = Clock.System.now().toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        PromoteConversationPayloadDto(conversationId = conversationId, name = name, cwd = cwd),
                    ),
            )
        // Throws on a server `error` / not-Open session; the decode + confirmed upsert below are
        // unreachable on any failure path. The reply is the bare conversation object (#318 decodes it).
        val reply = requests.sendAndAwaitReply(request)
        return conversationList.upsertConversation(MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply))
    }

    /**
     * Register the phone's FCM push [token] with the paired daemon over v2 `register_push_token`
     * (#359) — so the daemon knows where to send a wake notification when the phone is backgrounded.
     * Encodes the request ([RegisterPushTokenPayloadDto]: `platform="fcm"`, the [token], and the
     * connection-level [deviceName]), sends it, and awaits its correlated reply: an empty `ack`
     * (success) or an `error` (failure). A pure request/reply with **no** projection side effect —
     * unlike the conversation mutations, this registers a token and produces no domain object, so the
     * success signal is simply "the call returned without throwing".
     *
     * The server dedupes the `(platform, token, device_name)` triple, so this does no client-side
     * dedupe — it just sends. **Dormant** until the Firebase sibling provides a real token and a live
     * caller; this slice only exposes the capability. Never logs the [token] or the request payload.
     *
     * Throws [RelayErrorException] for a server `error` (carrying the structured `code`/`retryable`,
     * e.g. `server.binary_busy` retryable / `auth.invalid_token` not), and [IllegalStateException]
     * when the session is not connected ([SessionPump.send] returns `false`) — neither mutates any
     * state (there is nothing to mutate).
     */
    suspend fun registerPushToken(token: String) {
        val request =
            Envelope(
                id = requests.nextRequestId(),
                type = TYPE_REGISTER_PUSH_TOKEN,
                ts = Clock.System.now().toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        RegisterPushTokenPayloadDto(platform = PLATFORM_FCM, token = token, deviceName = deviceName),
                    ),
            )
        // Throws on a server `error` / not-Open session. The empty `{}` ack payload carries nothing
        // to map and no projection is mutated, so the returned reply is ignored.
        requests.sendAndAwaitReply(request)
    }

    /**
     * Answer the surfaced permission/choice modal (#437) over v2 `modal_answer` (#438): send
     * `{modal_id, option_id, answer_token}` and await its correlated reply — an empty `ack` (the
     * daemon received and will process the answer) or an `error` (failure). [modalId] and [optionId]
     * are the opaque tokens the caller (#439, via the #437 decode) hands in — echoed **verbatim**,
     * never parsed or validated; the daemon validates [modalId] against its own outstanding modal
     * (first-answer-wins; a stale id is rejected) and maps [optionId] against its own option list.
     * The `answer_token` is minted here ([answerToken] helper) as a deterministic idempotency key.
     * [alwaysAllow] (#818) adds `always_allow: true` to grant the modal's offered rules for the session;
     * unset, the field is omitted. It does not take part in the token.
     *
     * A pure request/reply control call with **no** projection side effect — success is simply "the
     * call returned without throwing". The modal's eventual *resolution* arrives asynchronously as the
     * inbound `modal_dismissed` event on [RemoteConversationRepository.modalEvents] (#437); this method
     * does **not** await it. Never logs the payload (the modal may name a sensitive command/path).
     *
     * Throws [RelayErrorException] for a server `error` (carrying `code`/`retryable` — incl. the
     * ungranted-device reject pyrycode#702/#703, whose read-only degrade is #440's concern, not
     * caught here), and [IllegalStateException] when the session is not connected ([SessionPump.send]
     * returns `false`) — neither mutates any state (there is none).
     */
    suspend fun answerModal(
        modalId: String,
        optionId: String,
        alwaysAllow: Boolean = false,
    ) {
        val request =
            Envelope(
                id = requests.nextRequestId(),
                type = TYPE_MODAL_ANSWER,
                ts = Clock.System.now().toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        ModalAnswerPayloadDto(
                            modalId = modalId,
                            optionId = optionId,
                            answerToken = answerToken(modalId, optionId),
                            alwaysAllow = if (alwaysAllow) true else null,
                        ),
                    ),
            )
        // Throws on a server `error` / not-Open session; the empty `{}` ack is ignored.
        requests.sendAndAwaitReply(request)
    }

    /**
     * Cancel the surfaced permission/choice modal (#437) over v2 `modal_cancel` (#438): send
     * `{modal_id}` and await its correlated `ack`/`error`. [modalId] is echoed **verbatim**, never
     * parsed; the daemon validates it against its own outstanding modal (a stale id — e.g. a re-cancel
     * of an already-resolved modal — is rejected). Cancel carries no idempotency token (pyrycode#701).
     * Same request/reply, no-projection, never-log posture as [answerModal].
     *
     * Throws [RelayErrorException] for a server `error` and [IllegalStateException] when the session
     * is not connected ([SessionPump.send] returns `false`).
     */
    suspend fun cancelModal(modalId: String) {
        val request =
            Envelope(
                id = requests.nextRequestId(),
                type = TYPE_MODAL_CANCEL,
                ts = Clock.System.now().toString(),
                payload = MobileJson.encodeToJsonElement(ModalCancelPayloadDto(modalId = modalId)),
            )
        // Throws on a server `error` / not-Open session; the empty `{}` ack is ignored.
        requests.sendAndAwaitReply(request)
    }

    /**
     * Stop the named conversation over v2 `interrupt` (protocol-mobile.md, Interrupt v2).
     * Fire-and-forget: the daemon sends no ack, so use [SessionPump.send], not
     * [RelayRequests.sendAndAwaitReply]. The daemon validates the conversation lookup key and enforces the
     * interactive capability. No local turn state changes; the existing inbound turn events remain
     * authoritative.
     *
     * Throws [IllegalStateException] when the session is not connected so the caller can swallow it.
     */
    suspend fun interrupt(conversationId: String) {
        check(send(interruptRequest(conversationId))) { "$TYPE_INTERRUPT not sent: session not connected" }
    }

    /** Explicitly targeted, fire-and-forget control frame — see [interrupt]. */
    private fun interruptRequest(conversationId: String): Envelope =
        Envelope(
            id = requests.nextRequestId(),
            type = TYPE_INTERRUPT,
            ts = Clock.System.now().toString(),
            payload = JsonObject(mapOf("conversation_id" to JsonPrimitive(conversationId))),
        )

    /**
     * Mint the `answer_token` for a `modal_answer`: a deterministic, collision-free encoding of the
     * answer's identity `(modalId, optionId)` (pyrycode#701 — uniqueness + stability matter, secrecy
     * does not). A **pure** function: no stored state, no random, no clock — purity is what gives the
     * two AC#2 properties by construction:
     *
     *  - **Stable across a retry of the same logical answer:** the same `(modalId, optionId)` always
     *    yields the same token, so a resend carries the identical token and the daemon dedups it to a
     *    no-op (no remembered state, no cache lifetime).
     *  - **Unique per distinct answer:** distinct pairs always yield distinct tokens. Both ids
     *    participate — two different modals each answered with an option id `"allow"` are distinct
     *    answers and must carry distinct tokens.
     *
     * The modal id is **length-prefixed** so the opaque ids (which may contain the `:` separator)
     * cannot alias: `("a:b","c")` → `"3:a:b:c"` and `("a","b:c")` → `"1:a:b:c"` are distinct. The
     * daemon treats the token as an opaque comparison key, so this conforming encoding is wire-correct.
     */
    private fun answerToken(
        modalId: String,
        optionId: String,
    ): String = "${modalId.length}:$modalId:$optionId"

    suspend fun archive(conversationId: String): Unit = sendArchiveToggle(conversationId, TYPE_ARCHIVE_CONVERSATION)

    suspend fun unarchive(conversationId: String): Unit = sendArchiveToggle(conversationId, TYPE_UNARCHIVE_CONVERSATION)

    /**
     * Archive or restore [conversationId] over v2 [type] (`archive_conversation` /
     * `unarchive_conversation`, #549, server pyrycode#881) — the shared body both toggle overrides
     * delegate to (mirroring the server's one parameterized handler registered under both verbs). Encodes
     * the id-only [ArchiveConversationPayloadDto] request, sends it, and awaits its correlated
     * `conversation_updated` reply — the **typed** bare-conversation payload now carrying `is_archived`
     * (pyrycode#881). Decodes the reply through the #318 [ConversationResponseDto] boundary, so a
     * malformed reply throws before any state mutation, then **confirmed-upserts** the returned
     * [Conversation] into [ConversationListProjection] — only after the reply decodes — so
     * [RemoteConversationRepository.observeConversations] re-emits with the conversation in its new tier
     * (leaving/entering [ConversationFilter.Archived]).
     *
     * A direct analogue of [rename] (encode → [RelayRequests.sendAndAwaitReply] → typed-decode → fold) minus
     * the return value: the [ConversationRepository] contract returns [Unit], so the decoded conversation is
     * folded but not returned. Idempotent: pyrycode#881 replies `conversation_updated` with the unchanged
     * state on a re-archive/re-unarchive, and [ConversationListProjection.upsertConversation] replaces the entry with an equal value
     * (a benign re-emit). Throws [IllegalArgumentException] for an unknown conversation (server
     * `conversation.not_found`, mirroring the fake's type), [RelayErrorException] for any other server
     * `error`, [IllegalStateException] when the session is not connected, and the #318 decode exception
     * for a malformed reply — none of which mutate [ConversationListProjection] (AC #3, #4). Adds no logging (the id and
     * reply stay off the log, the `security-sensitive` discipline).
     */
    private suspend fun sendArchiveToggle(
        conversationId: String,
        type: String,
    ) {
        val request =
            Envelope(
                id = requests.nextRequestId(),
                type = type,
                ts = Clock.System.now().toString(),
                payload = MobileJson.encodeToJsonElement(ArchiveConversationPayloadDto(conversationId = conversationId)),
            )
        // Throws on a server `error` / not-Open session; the decode + confirmed upsert below are
        // unreachable on any failure path. The reply is the bare conversation object (#318 decodes it).
        val reply = requests.sendAndAwaitReply(request)
        conversationList.upsertConversation(MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply))
    }

    /**
     * Set or clear [conversationId]'s mute flag over v2 `set_conversation_muted` (#1000, server
     * pyrycode#2572) — [sendArchiveToggle]'s round trip with a [SetConversationMutedPayloadDto]: await the
     * correlated `conversation_updated`, decode it, and only then fold it into [ConversationListProjection].
     * The daemon also pushes the same record uncorrelated to the requester; that push takes the ordinary
     * uncorrelated fold, and both are upserts by id, so the list keeps one row. Same failure contract as
     * [sendArchiveToggle]: nothing is folded on an `error`, a disconnected session or a malformed reply.
     */
    suspend fun setMuted(
        conversationId: String,
        muted: Boolean,
    ) {
        val request =
            Envelope(
                id = requests.nextRequestId(),
                type = TYPE_SET_CONVERSATION_MUTED,
                ts = Clock.System.now().toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        SetConversationMutedPayloadDto(conversationId = conversationId, muted = muted),
                    ),
            )
        val reply = requests.sendAndAwaitReply(request)
        conversationList.upsertConversation(MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply))
    }

    /**
     * Permanently delete [conversationId] over v2 `delete_conversation` (#532, server pyrycode#822 /
     * PR #884). Encodes the id-only [DeleteConversationPayloadDto] request, sends it, and awaits its
     * correlated `conversation_deleted` ack. Unlike [rename] / [sendArchiveToggle] (whose reply is a
     * bare `conversation_updated` folded via [ConversationListProjection.upsertConversation]), delete's reply is a dedicated
     * `{id}` ack — the record is gone, so there is nothing to upsert. The ack is decoded through the
     * [ConversationDeletedPayloadDto] boundary **only** to validate the reply shape (#318 posture — a
     * malformed ack throws here, before any removal); the decoded value is **discarded** (the repo
     * removes the id it *sent*, not the id the reply echoes, so a lying relay cannot redirect the
     * removal). On a well-formed ack it **removes** [conversationId] from all three read projections
     * ([removeConversation]) so [RemoteConversationRepository.observeConversations] re-emits without it,
     * [RemoteConversationRepository.observeMessages] → `emptyList()`, and
     * [RemoteConversationRepository.observeLastMessage] → `null` — the faithful mirror of the fake's
     * whole-record removal.
     *
     * **`conversation.not_found` converges as success**, the deliberate divergence from [rename] /
     * [sendArchiveToggle]: the [ConversationRepository.delete] contract is *tolerant* of unknown ids
     * (converges on the post-condition, not [IllegalArgumentException]), so an already-gone id is
     * removed locally and returns normally. [RelayRequests.mapError] maps `conversation.not_found` — and
     * nothing else — to [IllegalArgumentException], so the tight `catch` below captures exactly that case;
     * it is scoped to [RelayRequests.sendAndAwaitReply] alone, so a malformed-ack decode
     * [IllegalArgumentException] still propagates (never mis-read as already-gone, so a bad ack removes
     * nothing).
     *
     * Throws [IllegalStateException] when the session is not connected (or on teardown mid-await),
     * [RelayErrorException] for any other server `error`, and the #318 decode exception for a malformed
     * ack — none of which mutate a projection (AC #2, #3). Adds no logging (the id and reply stay off
     * the log, the `security-sensitive` discipline; [RelayErrorException.message] is server-supplied).
     */
    suspend fun delete(conversationId: String) {
        val request =
            Envelope(
                id = requests.nextRequestId(),
                type = TYPE_DELETE_CONVERSATION,
                ts = Clock.System.now().toString(),
                payload = MobileJson.encodeToJsonElement(DeleteConversationPayloadDto(conversationId = conversationId)),
            )
        val reply =
            try {
                requests.sendAndAwaitReply(request)
            } catch (alreadyGone: IllegalArgumentException) {
                // mapError maps conversation.not_found → IAE and nothing else, so this is exactly the
                // not-found case. Delete's post-condition is "absent", so already-gone is success (AC #4):
                // converge locally and return. The catch is scoped to the await only — the decode below
                // is NOT inside it, so a malformed-ack IAE cannot be mis-read as already-gone.
                removeConversation(conversationId)
                return
            }
        // #318 boundary: a malformed ack throws here (SerializationException ⊂ IllegalArgumentException),
        // BEFORE removeConversation, so a bad ack mutates nothing (AC #2). Shape-validated, then discarded.
        MobileJson.decodeFromJsonElement<ConversationDeletedPayloadDto>(reply)
        removeConversation(conversationId)
    }

    /**
     * Remove [conversationId] from **all three** read projections after a confirmed `delete` (#532) —
     * the contrast to [ConversationListProjection.upsertConversation]. The [ConversationRepository.delete] contract's
     * post-condition spans all three streams, and the fake achieves it by removing its *unified* record
     * ([FakeConversationRepository]'s `state - conversationId` empties list, messages, and last-message
     * at once); the remote holds three *separate* `StateFlow`s read independently by
     * [RemoteConversationRepository.observeMessages] / [RemoteConversationRepository.observeLastMessage], so a
     * list-only removal would leave those streams emitting a hard-deleted conversation's rows. Clearing all
     * three is *completing* the delete, not scope creep. Since #913 the list and last-message streams are
     * cleared by [ConversationListProjection.remove] and the thread by [ThreadProjection.remove]. Idempotent by
     * construction: `List.filterNot` returns an element-equal list when the id is absent, and
     * `Map - missingKey` an equals-identical map, so [kotlinx.coroutines.flow.StateFlow] conflation makes
     * deleting an already-absent id re-emit nothing on any of the three.
     */
    private fun removeConversation(conversationId: String) {
        conversationList.remove(conversationId)
        threadProjection.remove(conversationId)
    }

    /**
     * Rename an existing conversation (channel or discussion) to [name] over v2 `rename_conversation`
     * (#530, server #820). Encodes the request ([RenameConversationPayloadDto]: `{conversation_id, name}`,
     * both required — no `cwd`, contrast [promote]), sends it, and awaits its correlated
     * `conversation_updated` reply — the **typed** bare-conversation payload. Decodes the reply through
     * the #318 [ConversationResponseDto] boundary, so a malformed reply throws before any state
     * mutation, then **confirmed-upserts** the returned [Conversation] into [ConversationListProjection] — only after
     * the reply decodes — so [RemoteConversationRepository.observeConversations] re-emits with the new name
     * (and the thread top bar, derived from the same projection). The returned `name` is the
     * **server-authoritative** reply value, not the request's.
     *
     * [name] is forwarded **verbatim** — the `RenameDialog` is the sole trim authority; the daemon
     * re-validates and rejects empty/whitespace titles server-side (`protocol.malformed`), surfaced
     * here as an ordinary [RelayErrorException]. Throws [IllegalArgumentException] for an unknown
     * conversation (server `conversation.not_found`, mirroring the fake's type), [RelayErrorException]
     * for any other server `error`, [IllegalStateException] when the session is not connected, and the
     * #318 decode exception ([kotlinx.serialization.SerializationException] / [IllegalArgumentException])
     * for a malformed reply — none of which mutate [ConversationListProjection] (AC #3).
     */
    suspend fun rename(
        conversationId: String,
        name: String,
    ): Conversation {
        val request =
            Envelope(
                id = requests.nextRequestId(),
                type = TYPE_RENAME_CONVERSATION,
                ts = Clock.System.now().toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        RenameConversationPayloadDto(conversationId = conversationId, name = name),
                    ),
            )
        // Throws on a server `error` / not-Open session; the decode + confirmed upsert below are
        // unreachable on any failure path. The reply is the bare conversation object (#318 decodes it).
        val reply = requests.sendAndAwaitReply(request)
        return conversationList.upsertConversation(MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply))
    }

    /**
     * Send v2 `new_session` for the viewed conversation, explicitly naming its id so another
     * device's activity cannot redirect the reset through the daemon's follow-active cursor.
     * See the upstream protocol's New session (v2) contract. This is fire-and-forget via
     * [SessionPump.send], never [RelayRequests.sendAndAwaitReply]; session changes arrive through inbound
     * events. The daemon validates the id and enforces the interactive capability.
     *
     * [workspace] is not part of this control frame. Throws [IllegalStateException] when
     * [SessionPump.send] returns false, so the caller can surface the failure.
     *
     * The interface forces a [Session] return, but a fire-and-forget frame yields no session identity —
     * the real one arrives later via the out-of-scope `session_transition` marker (#336 fold). So the
     * returned placeholder's identity fields (`id`, `claudeSessionUuid`) are **explicitly unassigned**
     * (empty strings, not a fabricated-to-look-real UUID); it is never persisted, never enters
     * [ConversationListProjection], and the #540 consumer discards it.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun startNewSession(
        conversationId: String,
        workspace: String?,
    ): Session {
        check(send(newSessionFrame(conversationId))) { "$TYPE_NEW_SESSION not sent: session not connected" }
        return Session(
            id = "",
            conversationId = conversationId,
            claudeSessionUuid = "",
            startedAt = Clock.System.now(),
            endedAt = null,
        )
    }

    /** Explicitly targeted, fire-and-forget control frame — see [startNewSession]. */
    private fun newSessionFrame(conversationId: String): Envelope =
        Envelope(
            id = requests.nextRequestId(),
            type = TYPE_NEW_SESSION,
            ts = Clock.System.now().toString(),
            payload = JsonObject(mapOf("conversation_id" to JsonPrimitive(conversationId))),
        )
}
