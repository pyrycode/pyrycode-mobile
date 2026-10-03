package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.diagnostics.MessageTrail
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.AttachmentChunkPlan
import de.pyryco.mobile.data.network.DequeueMessagePayloadDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MessageAttachmentIds
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.RequestSnapshotPayloadDto
import de.pyryco.mobile.data.network.ScreenSnapshotPayloadDto
import de.pyryco.mobile.data.network.SendMessagePayloadDto
import de.pyryco.mobile.data.network.attachmentDisplayName
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.ERROR_CONVERSATION_NOT_FOUND
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_ATTACHMENT_CHUNK
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_DEQUEUE_MESSAGE
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_REQUEST_SNAPSHOT
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_SEND_MESSAGE
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.UUID

/**
 * The message and transfer commands of one connection (#915): sending, attachment uploads, the screen
 * snapshot, dropping a queued message, and the debug bundle, split out of [RemoteConversationRepository]
 * the way the conversation commands were (#914). The repository keeps the routing — its `onInbound`
 * offers each frame to [routeDebugBundle], then [routeAttachmentUpload], before anything else, and its
 * collector's `finally` calls [endDebugBundle] and [endAttachmentUploads] ahead of the pending-request
 * sweep — and a one-line hand-off per public command.
 *
 * Built on [requests], the connection's one request counter and reply waiters, so every frame takes its
 * envelope id from the same sequence as every other request. [send] is the repository's pump send, used by
 * the frames that go out raw: the attachment chunks, the debug bundle request and the dequeue. A send's
 * echo folds into [conversationList] and [threadProjection]; a drop reads [queueProjection] and records
 * into [threadProjection].
 *
 * One instance per repository, and a fresh repository per connection (#351), so the transfer state is
 * connection-scoped exactly as it was when it lived in the repository. Every accessor of that state is
 * `@Synchronized` on this instance, which nothing else locks.
 *
 * A send records its sent, acknowledged or failed line in [trail] (#1564), the sent line with this
 * connection's redacted token from [connToken].
 */
internal class MessageCommands(
    private val requests: RelayRequests,
    private val send: (Envelope) -> Boolean,
    private val conversationList: ConversationListProjection,
    private val threadProjection: ThreadProjection,
    private val queueProjection: QueueProjection,
    private val trail: MessageTrail,
    private val connToken: () -> String?,
) {
    private var debugBundle: DebugBundleTransfer? = null
    private var bundleInboundEnded = false

    /** One attempt per connection: uncorrelated chunk/done frames cannot safely feed a retry. */
    @Synchronized
    fun requestDebugBundle(): DebugBundleTransfer {
        if (bundleInboundEnded) return DebugBundleTransfer.rejected(DebugBundleStatus.UNAVAILABLE)
        debugBundle?.let {
            return DebugBundleTransfer.rejected(
                if (it.state.value.status == DebugBundleStatus.RECEIVING) DebugBundleStatus.BUSY else DebugBundleStatus.RECONNECT_REQUIRED,
            )
        }
        val request = Envelope(requests.nextRequestId(), "request_debug_bundle", Clock.System.now().toString())
        val transfer = DebugBundleTransfer(request.id).also { debugBundle = it }
        val sent =
            try {
                send(request)
            } catch (_: Exception) {
                false
            }
        if (!sent) transfer.fail(DebugBundleStatus.SEND_FAILED)
        return transfer
    }

    @Synchronized
    fun endDebugBundle() {
        bundleInboundEnded = true
        debugBundle?.fail(DebugBundleStatus.DISCONNECTED)
    }

    @Synchronized
    fun routeDebugBundle(envelope: Envelope): Boolean = debugBundle?.accept(envelope) == true

    /** Held by one upload from its first chunk until it settles (#829), so uploads on this connection run one at a time. */
    private val uploadLock = Mutex()
    private var activeUpload: AttachmentUploadTransfer? = null
    private var uploadInboundEnded = false

    /** Registers [transfer] as the one routed upload, unless inbound has already ended. */
    @Synchronized
    private fun beginUpload(transfer: AttachmentUploadTransfer): Boolean {
        if (uploadInboundEnded) return false
        activeUpload = transfer
        return true
    }

    @Synchronized
    private fun finishUpload(transfer: AttachmentUploadTransfer) {
        if (activeUpload === transfer) activeUpload = null
    }

    /** Runs in the inbound collector's `finally`: the daemon discards a partial upload with its connection. */
    @Synchronized
    fun endAttachmentUploads() {
        uploadInboundEnded = true
        activeUpload?.fail(AttachmentUploadResult.ConnectionLost)
    }

    @Synchronized
    fun routeAttachmentUpload(envelope: Envelope): Boolean = activeUpload?.accept(envelope) == true

    /**
     * Post [text] to [conversationId] over v2 `send_message` (#346). Mints a client-side
     * `message_id`, draws the echo, sends the request, and awaits its correlated reply: an empty `ack`
     * (success) or an `error` (failure). Mirrors [FakeConversationRepository.sendMessage]'s observable
     * contract — returns a `role=User` [Message] reconstructed from the input. There is no server
     * `message` echo to the sender, so the sender's thread shows the message through the **echo** drawn
     * **before** the await (#1355), as desktop's `submitMessage` draws its `userText` echo whether or not
     * the send went out: both read-path projections re-emit at once, and the `ack` adds nothing.
     *
     * Throws [IllegalArgumentException] for an unknown conversation (server `conversation.not_found`,
     * mirroring the fake's type), [RelayErrorException] for any other server `error`, and
     * [IllegalStateException] when the session is not connected. The echo stays in place on every one of
     * them, as on desktop; only the too-many-attachments refusal, raised before an id is minted, draws none.
     */
    suspend fun sendMessage(
        conversationId: String,
        text: String,
    ): Message = sendMessage(conversationId, text, emptyList())

    /**
     * The same send naming [attachments]' ids (#830) on the payload. The bound is checked before a request id
     * is taken, so a refused list sends nothing. Logs only the id count, never an id, a name or the text.
     *
     * The confirmed row carries one reference per distinct id in caller order (#983), its name and MIME hint
     * passed through [attachmentDisplayName]: a local file name is authored by whichever app provided the
     * document, so it is cleaned exactly as a daemon-offered one is.
     */
    suspend fun sendMessage(
        conversationId: String,
        text: String,
        attachments: List<MessageAttachment>,
    ): Message {
        val attachmentIds = attachments.map { it.attachmentId }
        val namedIds =
            try {
                MessageAttachmentIds.forSend(attachmentIds)
            } catch (e: IllegalArgumentException) {
                RelayLog.w { "event=send_message outcome=too_many_attachments count=${attachmentIds.distinct().size}" }
                throw e
            }
        namedIds?.let { RelayLog.d { "event=send_message attachments=${it.size}" } }
        val messageId = UUID.randomUUID().toString()
        val sentAt = Clock.System.now()
        val request =
            Envelope(
                id = requests.nextRequestId(),
                type = TYPE_SEND_MESSAGE,
                ts = sentAt.toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        SendMessagePayloadDto(
                            conversationId = conversationId,
                            messageId = messageId,
                            text = text,
                            attachmentIds = namedIds,
                        ),
                    ),
            )
        val message =
            Message(
                id = messageId,
                // The v2 wire carries no session_id (boundaries are #336); list/thread tiers use the
                // "" placeholder, never a resolved currentSessionId — matching the inbound mappers.
                sessionId = "",
                role = Role.User,
                content = text,
                timestamp = sentAt,
                isStreaming = false,
                attachments =
                    attachments.distinctBy { it.attachmentId }.map { attachment ->
                        attachment.copy(
                            displayName = attachment.displayName?.let(::attachmentDisplayName),
                            mimeType = attachment.mimeType?.let(::attachmentDisplayName),
                        )
                    },
            )
        conversationList.recordLastMessage(conversationId, message)
        threadProjection.appendMessages(listOf(conversationId to message))
        // Record the echo as ours (#781) — only an id in this ledger may later be correlated with a
        // queued item and removed. A failed send's id stays: no queued item will ever carry it.
        threadProjection.recordMinted(conversationId, messageId)
        // Throws on a server `error` / not-Open session, leaving the echo drawn. The empty `{}` ack
        // payload carries nothing to map.
        // The trail (#1564) records the reply where it lands, in order with the queue frames around it.
        var sent = false
        try {
            requests.sendAndAwaitReply(
                request,
                onSent = {
                    sent = true
                    trail.sent(messageId, connToken())
                },
                onReply = { error -> recordReply(messageId, error) },
            )
        } catch (e: Exception) {
            if (!sent) trail.failed(messageId, MessageTrail.Failure.NOT_CONNECTED)
            throw e
        }
        return message
    }

    /** The trail's line for a send's reply (#1564): only an error's code, never its message. */
    private fun recordReply(
        messageId: String,
        error: Throwable?,
    ) = when (error) {
        null -> trail.acknowledged(messageId)
        is RelayErrorException -> trail.failed(messageId, MessageTrail.Failure.DAEMON_ERROR, error.code)
        // The mapped `conversation.not_found` reply (RelayRequests.mapError).
        is IllegalArgumentException -> trail.failed(messageId, MessageTrail.Failure.DAEMON_ERROR, ERROR_CONVERSATION_NOT_FOUND)
        // RelayRequests.failAllPending: the connection tore down before the reply.
        else -> trail.failed(messageId, MessageTrail.Failure.TORN_DOWN)
    }

    /**
     * Upload [bytes] to [conversationId] on **this** connection as `attachment_chunk` frames (#829), under
     * one freshly minted lowercase UUIDv4, and wait for the daemon's outcome. Never throws except on
     * cancellation.
     *
     * An oversized file is refused before anything else. Otherwise the upload takes [uploadLock] and holds
     * it until it settles, so a second upload's chunks only follow a settled first and the socket's send
     * queue never carries two uploads at once. Each chunk's envelope id is recorded before it is sent, and
     * the loop stops at the first settled outcome, so a refused, unsent or disconnected upload sends no
     * further chunk. No timeout: the connection's own liveness teardown ends an upload the daemon never
     * answers, through the inbound collector's `finally`.
     *
     * After each chunk the socket accepted, and before the yield, [onProgress] gets the chunks sent so far
     * and the total, unless the transfer has settled — as desktop's `reportProgress` (#1326).
     *
     * Logs the attachment id, the chunk index and the total — never the bytes, filename, digest or type.
     */
    suspend fun uploadAttachment(
        conversationId: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        onProgress: (sentChunks: Int, totalChunks: Int) -> Unit,
    ): AttachmentUploadResult {
        if (!AttachmentUploadLimit.fits(bytes.size)) return AttachmentUploadResult.TooLarge
        return uploadLock.withLock {
            val transfer = AttachmentUploadTransfer(UUID.randomUUID().toString())
            if (!beginUpload(transfer)) return@withLock AttachmentUploadResult.ReconnectRequired
            try {
                val plan = AttachmentChunkPlan(conversationId, transfer.attachmentId, bytes, filename, mimeType)
                for (index in 0 until plan.totalChunks) {
                    if (transfer.isSettled) break
                    val chunk =
                        Envelope(
                            id = requests.nextRequestId(),
                            type = TYPE_ATTACHMENT_CHUNK,
                            ts = Clock.System.now().toString(),
                            payload = MobileJson.encodeToJsonElement(plan.payload(index)),
                        )
                    transfer.expectReplyTo(chunk.id)
                    val sent =
                        try {
                            send(chunk)
                        } catch (_: Exception) {
                            false
                        }
                    if (!sent) {
                        transfer.fail(AttachmentUploadResult.SendFailed)
                        break
                    }
                    RelayLog.d { "event=attachment_chunk id=${transfer.attachmentId} index=$index total=${plan.totalChunks}" }
                    // The same flag the loop re-reads (#1326): a settle during the send stops the report with the chunks.
                    if (!transfer.isSettled) onProgress(index + 1, plan.totalChunks)
                    // Lets the inbound collector settle a refusal before the next chunk, and makes the loop cancellable.
                    yield()
                }
                transfer.await().also { outcome ->
                    RelayLog.d { "event=attachment_upload id=${transfer.attachmentId} outcome=${outcome::class.simpleName}" }
                }
            } finally {
                finishUpload(transfer)
            }
        }
    }

    /**
     * Request the current claude screen for [conversationId] over v2 `request_snapshot` (#375) and
     * return the correlated `screen_snapshot` reply's rendered [ScreenSnapshotPayloadDto.text] — the
     * always-available, parser-independent snapshot floor (pyrycode#618). A pure read: it mutates no
     * projection.
     *
     * Encodes the request ([RequestSnapshotPayloadDto]: `{conversation_id}`), sends it, and awaits the
     * correlated reply through the shared single inbound collector + [RelayRequests.sendAndAwaitReply] (the #346
     * primitive — no second pump subscription), then decodes the reply through the #374
     * [ScreenSnapshotPayloadDto] boundary and returns its [text][ScreenSnapshotPayloadDto.text]
     * **verbatim** — never parsed, trimmed, or sanitized; `ts` is never read; nothing here is logged
     * (the snapshot text may be sensitive screen content).
     *
     * Throws [IllegalArgumentException] for an unknown conversation (server `conversation.not_found`,
     * mirroring the fake's type), [RelayErrorException] for any other server `error`, and
     * [IllegalStateException] when the session is not connected. A malformed reply throws the #374
     * decode exception ([kotlinx.serialization.SerializationException] / [IllegalArgumentException])
     * caller-side, **after** [RelayRequests.sendAndAwaitReply] returns, so it never threatens the single inbound
     * collector.
     */
    suspend fun requestScreenSnapshot(conversationId: String): String {
        val request =
            Envelope(
                id = requests.nextRequestId(),
                type = TYPE_REQUEST_SNAPSHOT,
                ts = Clock.System.now().toString(),
                payload = MobileJson.encodeToJsonElement(RequestSnapshotPayloadDto(conversationId = conversationId)),
            )
        // Throws on a server `error` / not-Open session; the decode below is unreachable on failure.
        val reply = requests.sendAndAwaitReply(request)
        return MobileJson.decodeFromJsonElement<ScreenSnapshotPayloadDto>(reply).text
    }

    /**
     * Drop a not-yet-drained message from [conversationId]'s queued backlog over v2 `dequeue_message`
     * (#466, ADR 025): send `{conversation_id, queued_msg_id}` and return once the frame is sent. The
     * daemon **never replies** — not on success, and not when it cannot apply the request (an unknown,
     * already-delivered or in-flight head id is silently ignored); `docs/protocol-mobile.md` § Queue
     * (v2) lists no reply (#859). [queuedMessageId] is the `QueuedMessage.id` the caller received from
     * [RemoteConversationRepository.observeQueue] (#460), echoed **verbatim** as the wire `queued_msg_id` (a `uint64` → [Long] JSON
     * number, not a String — the pyrycode#720 trap).
     *
     * The backlog entry leaves only on the next `queue_state` (#467's non-optimistic ruling, which the
     * daemon owns), and that same snapshot is the drop's only confirmation. When it arrives without the
     * dropped `queued_msg_id`, [ThreadProjection.settleDrops] also removes **this device's own undelivered echo** for the
     * message (#781) — the thread row [sendMessage] drew when it sent, which the daemon never
     * authored and which otherwise stays behind reading as a message claude received. The correlation
     * key is the item's `message_id` (pyrycode#2092), resolved from this connection's own snapshot, so
     * no caller above needs to learn a second id. Never logs the payload or either id.
     *
     * Load-bearing properties of the sequence:
     *  - **Resolved before the send.** The confirming `queue_state` replaces the snapshot this reads
     *    from. Reading it early is safe because `queued_msg_id` is a per-conversation counter that is
     *    never recycled — item *N* in a stale snapshot is still the same item *N*.
     *  - **Recorded before the send, withdrawn if it fails.** The request goes into the pending drops first,
     *    so a confirming snapshot can never land before there is a request to settle. A not-connected
     *    send withdraws it and throws [IllegalStateException]: the daemon never heard the drop, the
     *    message will still run, and the echo is still true.
     *  - **Keyed on the requested `queued_msg_id`, never a backlog diff.** A backlog also shrinks when
     *    the daemon *drains* it, so a diff-driven removal would delete the echo of every message that
     *    ran normally. Only an item this device asked to drop settles.
     *  - **Removed only against the minted-id ledger.** An item carrying `""`, one whose id this device
     *    never minted (another paired device's real queued message), or a `queuedMessageId` absent from
     *    the snapshot correlates with nothing: the send still goes and no thread row is touched. Text is
     *    never compared.
     *
     * **The drain/drop race is accepted, not defended.** The daemon pushes the same `queue_state` for a
     * removal and for a drain. If the operator drops the head item just as the running turn ends, it can
     * drain before the dequeue lands; the daemon then ignores the dequeue and the next snapshot lacks
     * the item exactly as it would after a drop. The phone cannot tell the two apart and removes the
     * echo of a message claude did receive. The daemon offers no signal to distinguish them, and the
     * operator had asked for that message to go, so no defence is added.
     */
    suspend fun dropQueuedMessage(
        conversationId: String,
        queuedMessageId: Long,
    ) {
        val request =
            Envelope(
                id = requests.nextRequestId(),
                type = TYPE_DEQUEUE_MESSAGE,
                ts = Clock.System.now().toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        DequeueMessagePayloadDto(conversationId = conversationId, queuedMsgId = queuedMessageId),
                    ),
            )
        // Resolved before the send: the confirming snapshot replaces the one this reads from. "" when the
        // id matches no current item — a correlation this connection cannot make, not an error.
        val echoId =
            queueProjection
                .current(conversationId)
                .firstOrNull { it.id == queuedMessageId }
                ?.messageId
                .orEmpty()
        if (echoId.isNotEmpty()) {
            threadProjection.recordDrop(conversationId, queuedMessageId, echoId)
        }
        if (!send(request)) {
            threadProjection.withdrawDrop(conversationId, queuedMessageId)
            throw IllegalStateException("$TYPE_DEQUEUE_MESSAGE not sent: session not connected")
        }
    }
}
