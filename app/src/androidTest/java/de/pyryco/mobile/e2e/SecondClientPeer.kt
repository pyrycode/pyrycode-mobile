package de.pyryco.mobile.e2e

import com.southernstorm.noise.protocol.Noise
import de.pyryco.mobile.data.crypto.DeviceStaticKeyPair
import de.pyryco.mobile.data.crypto.DeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.network.AttachmentChunkPayloadDto
import de.pyryco.mobile.data.network.AttachmentChunkPlan
import de.pyryco.mobile.data.network.DequeueMessagePayloadDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.HistoryEntryDto
import de.pyryco.mobile.data.network.HistoryPagePayloadDto
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ModalAnswerPayloadDto
import de.pyryco.mobile.data.network.ModalShownPayloadDto
import de.pyryco.mobile.data.network.NoiseClientInfo
import de.pyryco.mobile.data.network.NoiseSessionFactory
import de.pyryco.mobile.data.network.NoiseSessionPump
import de.pyryco.mobile.data.network.OkHttpRelayTransport
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.QuestionAnswerEntryDto
import de.pyryco.mobile.data.network.QuestionAnswerPayloadDto
import de.pyryco.mobile.data.network.QuestionShownPayloadDto
import de.pyryco.mobile.data.network.QueueStatePayloadDto
import de.pyryco.mobile.data.network.QueuedMessageDto
import de.pyryco.mobile.data.network.RequestAttachmentPayloadDto
import de.pyryco.mobile.data.network.RequestHistoryPayloadDto
import de.pyryco.mobile.data.network.SendMessagePayloadDto
import de.pyryco.mobile.data.network.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A second paired device on the live harness (#848) — the bounded stand-in for the desktop app or any
 * other client of the same daemon. It pairs with its own `pyry pair` token (`peerToken`, minted by
 * `scripts/e2e-emulator.sh`) and its own throwaway device key, never the phone's, and speaks the mobile
 * wire protocol over the same relay the phone dials, through the app's own [OkHttpRelayTransport],
 * [NoiseSessionFactory] and [NoiseSessionPump].
 *
 * It never touches the app under test: its pairing record and key live in memory only, so the phone's
 * credential store, host list and registry selection are exactly what they were. One peer per scenario,
 * [open]ed once and [close]d in the scenario's `finally`.
 *
 * Every frame the daemon sends it is recorded, so [awaitFrame] also finds a frame that arrived before
 * the wait began. Nothing here logs; failures name a category or an error `code`, never the token, the
 * key or any payload text.
 */
class SecondClientPeer(
    pairing: PairedServer,
) : AutoCloseable {
    private val transport =
        OkHttpRelayTransport(pairing, CLIENT_INFO, OkHttpRelayTransport.defaultClient())
    private val pump =
        NoiseSessionPump(
            transport,
            NoiseSessionFactory(ThrowawayDeviceKeyStore(), SinglePairingStore(pairing), CLIENT_INFO),
        )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val received = MutableStateFlow<List<Envelope>>(emptyList())
    private val requestId = AtomicLong()
    private val closed = AtomicBoolean(false)

    /** Dial the relay, complete the Noise handshake and start recording frames. */
    suspend fun open(timeoutMs: Long) {
        withTimeout(timeoutMs) {
            // The events channel buffers, so Up is waiting here even if it fired before this read; the
            // peer is its only reader. The pump expects a transport that is already up.
            transport.connect()
            val event = transport.events.first()
            check(event is TransportEvent.Up) { "peer relay link down: code ${(event as? TransportEvent.Down)?.code}" }
            pump.start()
            val state = pump.state.first { it !is PumpState.Handshaking }
            check(state is PumpState.Open) {
                "peer session closed during handshake: ${(state as? PumpState.Closed)?.cause?.message ?: "transport down"}"
            }
        }
        scope.launch { pump.inbound.collect { envelope -> received.update { it + envelope } } }
    }

    /**
     * Post [text] to [conversationId] as this device, naming [attachmentIds] if any (#1016), and wait for the
     * daemon's `ack`.
     */
    suspend fun sendMessage(
        conversationId: String,
        text: String,
        timeoutMs: Long,
        attachmentIds: List<String>? = null,
    ) = request(
        "send_message",
        MobileJson.encodeToJsonElement(
            SendMessagePayloadDto(
                conversationId = conversationId,
                messageId = UUID.randomUUID().toString(),
                text = text,
                attachmentIds = attachmentIds,
            ),
        ),
        timeoutMs,
    )

    /**
     * Upload [bytes] into [conversationId] as this device (#1016), the way the desktop does: every chunk of an
     * [AttachmentChunkPlan] under a freshly minted id, then the daemon's `attachment_stored` for that id. An
     * `error` answering any chunk fails with its `code`. Returns the id, to name on [sendMessage].
     */
    internal suspend fun uploadAttachment(
        conversationId: String,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
        timeoutMs: Long,
    ): String {
        val attachmentId = UUID.randomUUID().toString().lowercase()
        val plan = AttachmentChunkPlan(conversationId, attachmentId, bytes, filename, mimeType)
        val chunkIds =
            (0 until plan.totalChunks).mapTo(mutableSetOf()) { index ->
                send("attachment_chunk", MobileJson.encodeToJsonElement(plan.payload(index)))
            }

        fun Envelope.answers() =
            (type == "attachment_stored" && payloadField("attachment_id") == attachmentId) ||
                (type == "error" && inReplyTo in chunkIds)
        val reply = withTimeout(timeoutMs) { received.first { frames -> frames.any { it.answers() } }.first { it.answers() } }
        check(reply.type == "attachment_stored") { "peer upload refused: ${reply.payloadField("code")}" }
        return attachmentId
    }

    /**
     * Fetch [attachmentId] from [conversationId] as this device (#1016): one `request_attachment`, then the
     * `attachment_chunk` frames answering it, reassembled by `index` until `total_chunks` distinct indices
     * have arrived. An `error` answer fails with its `code`. The bytes are the user's file: never logged.
     */
    internal suspend fun retrieveAttachment(
        conversationId: String,
        attachmentId: String,
        timeoutMs: Long,
    ): RetrievedAttachment {
        val id =
            send(
                "request_attachment",
                MobileJson.encodeToJsonElement(RequestAttachmentPayloadDto(conversationId = conversationId, attachmentId = attachmentId)),
            )
        val chunks =
            withTimeout(timeoutMs) {
                received
                    .map { frames -> frames.filter { it.inReplyTo == id } }
                    .first { answers ->
                        answers.any { it.type != "attachment_chunk" } ||
                            answers.firstOrNull()?.let { first ->
                                answers.mapTo(mutableSetOf()) { it.chunk().index }.size == first.chunk().totalChunks
                            } == true
                    }
            }
        chunks.firstOrNull { it.type != "attachment_chunk" }?.let { error("peer retrieval refused: ${it.payloadField("code") ?: it.type}") }
        val decoded = chunks.map { it.chunk() }.distinctBy { it.index }.sortedBy { it.index }
        val bytes = decoded.fold(ByteArray(0)) { acc, chunk -> acc + Base64.getDecoder().decode(chunk.data) }
        return RetrievedAttachment(decoded.first().filename, bytes)
    }

    /**
     * Every entry of [conversationId]'s history as this device sees it (#1016), newest first: `request_history`
     * pages walked from the newest, each page's cursor echoed verbatim, until one says `at_start`.
     */
    internal suspend fun history(
        conversationId: String,
        timeoutMs: Long,
    ): List<HistoryEntryDto> {
        val entries = mutableListOf<HistoryEntryDto>()
        var cursor = ""
        do {
            val reply =
                exchange(
                    "request_history",
                    MobileJson.encodeToJsonElement(RequestHistoryPayloadDto(conversationId = conversationId, cursor = cursor, limit = 0)),
                    timeoutMs,
                )
            check(reply.type == "history_page") { "peer request_history refused: ${reply.payloadField("code") ?: reply.type}" }
            val page = MobileJson.decodeFromJsonElement(HistoryPagePayloadDto.serializer(), reply.payload)
            entries += page.entries
            cursor = page.cursor
        } while (!page.atStart && page.entries.isNotEmpty())
        return entries
    }

    /**
     * Drop [queuedMsgId] from [conversationId]'s backlog as this device (#849). Fire-and-forget: the daemon
     * never replies to `dequeue_message`, and the removal shows only in the next `queue_state` ([awaitQueue]).
     */
    internal fun dequeueMessage(
        conversationId: String,
        queuedMsgId: Long,
    ) {
        send(
            "dequeue_message",
            MobileJson.encodeToJsonElement(DequeueMessagePayloadDto(conversationId = conversationId, queuedMsgId = queuedMsgId)),
        )
    }

    /**
     * The id of the first permission modal claude raises in [conversationId] (#849), waiting up to
     * [timeoutMs]. The prompt stays outstanding, and its turn open, until a privileged device answers it.
     */
    internal suspend fun awaitPermissionModal(
        conversationId: String,
        timeoutMs: Long,
    ): String {
        val shown =
            MobileJson.decodeFromJsonElement(
                ModalShownPayloadDto.serializer(),
                awaitFrame(conversationId, "modal_shown", timeoutMs).payload,
            )
        check(shown.modalClass == PERMISSION_CLASS) { "peer awaited a permission modal: class ${shown.modalClass}" }
        check(shown.options.any { it.id == ALLOW_ONCE }) { "permission modal offers no $ALLOW_ONCE" }
        return shown.modalId
    }

    /**
     * Allow [modalId] once as this device, and wait for the daemon's `modal_dismissed` for it (#849). The
     * daemon sends no reply to `modal_answer`, and ignores one from a device paired without
     * `--allow-remote-permissions`, so a missing dismissal times out rather than naming a code.
     */
    internal suspend fun allowOnce(
        modalId: String,
        timeoutMs: Long,
    ) {
        send(
            "modal_answer",
            MobileJson.encodeToJsonElement(
                ModalAnswerPayloadDto(modalId = modalId, optionId = ALLOW_ONCE, answerToken = UUID.randomUUID().toString()),
            ),
        )
        val dismissed = awaitDismissal("modal_dismissed", "modal_id", modalId, timeoutMs)
        check(dismissed.payloadField("outcome") == ALLOW_ONCE && dismissed.payloadField("source") == REMOTE_SOURCE) {
            "permission modal resolved otherwise: ${dismissed.payloadField("outcome")} from ${dismissed.payloadField("source")}"
        }
    }

    /**
     * The `modal_dismissed` for [modalId], whichever device resolved it (#966), waiting up to [timeoutMs].
     * Its `outcome` and `source` are daemon-asserted sentinels, safe to compare and to name in a failure.
     */
    internal suspend fun awaitModalDismissed(
        modalId: String,
        timeoutMs: Long,
    ): Envelope = awaitDismissal("modal_dismissed", "modal_id", modalId, timeoutMs)

    /**
     * The batch id of the [occurrence]th clarification question claude raises in [conversationId] (#966),
     * waiting up to [timeoutMs]. The batch stays outstanding, and its turn open, until a privileged device
     * answers or refuses it.
     */
    internal suspend fun awaitQuestion(
        conversationId: String,
        timeoutMs: Long,
        occurrence: Int = 1,
    ): String =
        MobileJson
            .decodeFromJsonElement(
                QuestionShownPayloadDto.serializer(),
                awaitFrame(conversationId, "question_shown", timeoutMs, occurrence).payload,
            ).questionBatchId

    /**
     * Answer question [questionIndex] of [batchId] with [value] as this device, and wait for the daemon's
     * `question_dismissed` for it (#966). As with `modal_answer`, the daemon replies nothing and ignores an
     * answer from a device paired without `--allow-remote-permissions`, so that case surfaces as a bare
     * `TimeoutCancellationException` naming no cause; a caller that watches the phone fails clearer first.
     */
    internal suspend fun answerQuestion(
        batchId: String,
        questionIndex: Int,
        value: String,
        timeoutMs: Long,
    ) {
        send(
            "question_answer",
            MobileJson.encodeToJsonElement(
                QuestionAnswerPayloadDto(
                    questionBatchId = batchId,
                    answerToken = UUID.randomUUID().toString(),
                    answers = listOf(QuestionAnswerEntryDto(questionIndex = questionIndex, values = listOf(value))),
                ),
            ),
        )
        val dismissed = awaitQuestionDismissed(batchId, timeoutMs)
        check(dismissed.payloadField("outcome") == ANSWERED && dismissed.payloadField("source") == REMOTE_SOURCE) {
            "question resolved otherwise: ${dismissed.payloadField("outcome")} from ${dismissed.payloadField("source")}"
        }
    }

    /** The `question_dismissed` for [batchId], whichever device resolved it (#966), waiting up to [timeoutMs]. */
    internal suspend fun awaitQuestionDismissed(
        batchId: String,
        timeoutMs: Long,
    ): Envelope = awaitDismissal("question_dismissed", "question_batch_id", batchId, timeoutMs)

    /**
     * The payload field [name] of [envelope] as a string, or null (#966). Callers compare it or name a
     * daemon-asserted sentinel from it; never claude-authored text.
     */
    internal fun field(
        envelope: Envelope,
        name: String,
    ): String? = envelope.payloadField(name)

    /**
     * The backlog the **latest** recorded `queue_state` for [conversationId] reports, once it satisfies
     * [ready] (#849). Latest, not any: a snapshot is the whole backlog, so an older one describes a queue
     * that has since changed.
     */
    internal suspend fun awaitQueue(
        conversationId: String,
        timeoutMs: Long,
        ready: (List<QueuedMessageDto>) -> Boolean,
    ): List<QueuedMessageDto> =
        withTimeout(timeoutMs) {
            received
                .map { frames -> frames.lastOrNull { it.type == "queue_state" && it.payloadField("conversation_id") == conversationId } }
                .filterNotNull()
                .map { MobileJson.decodeFromJsonElement(QueueStatePayloadDto.serializer(), it.payload).queued.orEmpty() }
                .first(ready)
        }

    /**
     * The [occurrence]th recorded frame of [type] that names [conversationId] (the first by default),
     * waiting up to [timeoutMs] for it.
     */
    suspend fun awaitFrame(
        conversationId: String,
        type: String,
        timeoutMs: Long,
        occurrence: Int = 1,
    ): Envelope =
        withTimeout(timeoutMs) {
            received
                .first { frames -> frames.count { it.isFor(conversationId, type) } >= occurrence }
                .filter { it.isFor(conversationId, type) }[occurrence - 1]
        }

    /**
     * The frames recorded so far that name [conversationId], in arrival order (#977). A snapshot: frames
     * that arrive later are not in it. Callers report counts and booleans from it, never payload text.
     */
    internal fun recorded(conversationId: String): List<Envelope> =
        received.value.filter { it.payloadField("conversation_id") == conversationId }

    /** Tear down the session (wiping its keys), the socket and the recorder. Idempotent. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pump.close()
        scope.cancel()
    }

    /** Send a [type] request carrying [payload], and wait for the reply correlated to it: `ack` or a thrown `code`. */
    private suspend fun request(
        type: String,
        payload: JsonElement,
        timeoutMs: Long,
    ) {
        val reply = exchange(type, payload, timeoutMs)
        check(reply.type == "ack") { "peer $type refused: ${reply.payloadField("code") ?: reply.type}" }
    }

    /** Send a [type] request carrying [payload], and return the first reply correlated to it, whatever its type. */
    private suspend fun exchange(
        type: String,
        payload: JsonElement,
        timeoutMs: Long,
    ): Envelope {
        val id = send(type, payload)
        return withTimeout(timeoutMs) {
            received.first { frames -> frames.any { it.inReplyTo == id } }.first { it.inReplyTo == id }
        }
    }

    private fun Envelope.chunk(): AttachmentChunkPayloadDto =
        MobileJson.decodeFromJsonElement(AttachmentChunkPayloadDto.serializer(), payload)

    /** Send a [type] frame carrying [payload] and return its envelope id. */
    private fun send(
        type: String,
        payload: JsonElement,
    ): Long {
        val id = requestId.incrementAndGet()
        check(pump.send(Envelope(id = id, type = type, ts = Clock.System.now().toString(), payload = payload))) {
            "peer session is not open"
        }
        return id
    }

    private fun Envelope.isFor(
        conversationId: String,
        type: String,
    ): Boolean = this.type == type && payloadField("conversation_id") == conversationId

    /** The first recorded [type] frame whose [idField] is [id], waiting up to [timeoutMs]. */
    private suspend fun awaitDismissal(
        type: String,
        idField: String,
        id: String,
        timeoutMs: Long,
    ): Envelope {
        fun Envelope.matches() = this.type == type && payloadField(idField) == id
        return withTimeout(timeoutMs) {
            received.first { frames -> frames.any { it.matches() } }.first { it.matches() }
        }
    }

    private fun Envelope.payloadField(name: String): String? = (payload as? JsonObject)?.get(name)?.jsonPrimitive?.contentOrNull

    /**
     * The peer's own device key, generated once in memory. The daemon learns a device's static key from
     * each handshake and keeps only the token, so any fresh key pairs with a fresh token. Each read is a
     * copy: [NoiseSessionFactory] zeroes the private key it is handed.
     */
    private class ThrowawayDeviceKeyStore : DeviceStaticKeyStore {
        private val publicKey = ByteArray(KEY_LENGTH)
        private val privateKey = ByteArray(KEY_LENGTH)

        init {
            val dh = Noise.createDH(DH_NAME)
            try {
                dh.generateKeyPair()
                dh.getPublicKey(publicKey, 0)
                dh.getPrivateKey(privateKey, 0)
            } finally {
                dh.destroy()
            }
        }

        override suspend fun loadOrCreate(serverId: String): DeviceStaticKeyPair =
            DeviceStaticKeyPair(publicKey.copyOf(), privateKey.copyOf())

        override suspend fun publicKey(serverId: String): ByteArray = publicKey.copyOf()
    }

    /** The peer's one pairing record, held in memory; the app's credential store never sees it. */
    private class SinglePairingStore(
        private val pairing: PairedServer,
    ) : PairedServerStore {
        override suspend fun load(): PairedServer = pairing

        override suspend fun save(record: PairedServer): Unit = error("the peer's pairing is fixed")
    }

    /**
     * One file fetched with [retrieveAttachment] (#1016): the daemon's stored name for it and its bytes. Both
     * are the user's file: [toString] prints the size only.
     */
    internal class RetrievedAttachment(
        val filename: String,
        val bytes: ByteArray,
    ) {
        override fun toString(): String = "RetrievedAttachment(size=${bytes.size})"
    }

    private companion object {
        val CLIENT_INFO = NoiseClientInfo(deviceName = "e2e-peer", clientVersion = "e2e-peer")
        const val DH_NAME = "25519"
        const val KEY_LENGTH = 32
        const val PERMISSION_CLASS = "permission"
        const val ALLOW_ONCE = "allow_once"
        const val REMOTE_SOURCE = "remote"
        const val ANSWERED = "answered"
    }
}
