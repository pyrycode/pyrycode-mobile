package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.ATTACHMENT_CHUNK_BYTES
import de.pyryco.mobile.data.network.AttachmentChunkPayloadDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ReadWorkspaceFilePayloadDto
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.RequestAttachmentPayloadDto
import de.pyryco.mobile.data.network.attachmentDisplayName
import de.pyryco.mobile.data.network.base64StdDecode
import de.pyryco.mobile.data.network.base64StdEncode
import de.pyryco.mobile.data.network.isAttachmentIdShape
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How one [ConversationRepository.retrieveAttachment] call ended (#899). [Failed] is shared with
 * [AttachmentFetchResult], so a failure reads the same at the connection and at the host.
 */
sealed interface AttachmentRetrievalResult {
    /**
     * The file, kept in app-private storage for its host. [file]'s name is the attachment id, never the
     * remote name. [displayName] is sanitised display text and [mimeType] the daemon's sniffed type: both
     * are hints from attacker-chosen bytes, never a path and never a privilege. Neither is ever logged.
     */
    data class Retrieved(
        val file: File,
        val displayName: String,
        val mimeType: String,
    ) : AttachmentRetrievalResult

    /** Every other outcome. None leaves a file under the attachment's id. */
    sealed interface Failed :
        AttachmentRetrievalResult,
        AttachmentFetchResult

    /** The daemon answered `attachment.not_found`, or an id failed the published shape. Do not retry. */
    data object NotFound : Failed

    /** The claimed size is over [AttachmentRetrievalLimit.MAX_BYTES]; refused before anything was allocated. */
    data object TooLarge : Failed

    /** The stream broke the reassembly or integrity rules. Nothing it carried is kept. */
    data object Invalid : Failed

    /**
     * Retryable: `attachment.stream_aborted` or another refusal, no live connection, a refused send, a
     * dropped connection, no chunk for [AttachmentRetrievals.DEFAULT_STALL_TIMEOUT], or a failed local write.
     */
    data object Unavailable : Failed
}

/** How one connection-level [ConversationRepository.fetchAttachment] call ended (#899). */
sealed interface AttachmentFetchResult {
    /** A complete stream whose length and sha256 matched its claims. [displayName] and [mimeType] are sanitised. */
    class Fetched(
        val content: AttachmentContent,
        val displayName: String,
        val mimeType: String,
    ) : AttachmentFetchResult {
        override fun toString(): String = "Fetched(size=${content.size})"
    }
}

/** Verified file bytes, held as the chunks they arrived in and written out in index order. Opaque to logs. */
class AttachmentContent internal constructor(
    private val chunks: List<ByteArray>,
) {
    val size: Long = chunks.sumOf { it.size.toLong() }

    fun writeTo(output: OutputStream) {
        chunks.forEach { output.write(it) }
    }

    override fun toString(): String = "AttachmentContent(size=$size)"
}

/**
 * The phone's own per-retrieval bound (#899): 512 chunks, 23,040,000 bytes.
 *
 * Reassembly is in memory, and a connection runs one retrieval at a time, so this is the heap one host can
 * hold for a file. It sits above the daemon's 16 MiB upload bound, so any uploaded file fits, and near what
 * the daemon's 32 MiB base64 push queue can deliver in one stream. Desktop refuses at the same figure. The
 * daemon publishes no bound for files it produced; a larger one is [AttachmentRetrievalResult.TooLarge].
 */
object AttachmentRetrievalLimit {
    const val MAX_CHUNKS: Int = 512
    const val MAX_BYTES: Long = MAX_CHUNKS.toLong() * ATTACHMENT_CHUNK_BYTES
}

/**
 * One `request_attachment` or `read_workspace_file` and its answer (#899, #1049). Every answer names [requestId]
 * in `in_reply_to`, the chunks and the `error` alike, so that is the route; the payload `attachment_id` is a
 * second check that the right request was not answered with the wrong file. A `read_workspace_file` answer
 * carries an id the daemon minted, so [attachmentId] starts `null` and the first chunk to arrive pins it, once
 * it has the published shape. Rules: `protocol-mobile.md` § Attachments → "Reassembly & integrity". Settles
 * once; every failure zero-fills what arrived.
 */
internal class AttachmentRetrievalTransfer(
    private val requestId: Long,
    attachmentId: String?,
) {
    /** The id every chunk must carry: the requested one, or the one the first chunk pinned. */
    var attachmentId: String? = attachmentId
        private set

    private val result = CompletableDeferred<AttachmentFetchResult>()
    private val mutableActivity = MutableStateFlow(0)

    /** Changes on every accepted chunk and once on settling: the stall deadline re-arms on every change. */
    val activity: StateFlow<Int> = mutableActivity.asStateFlow()

    val isSettled: Boolean get() = result.isCompleted

    /** The first chunk, whose claims every later chunk must repeat. */
    private var claims: AttachmentChunkPayloadDto? = null
    private var slots: Array<ByteArray?> = emptyArray()
    private var filled = 0

    /** Bytes accepted so far, charged against the claimed size by subtraction so nothing can overflow. */
    private var acceptedBytes = 0L

    @Synchronized
    fun accept(envelope: Envelope): Boolean {
        if (envelope.inReplyTo != requestId) return false
        when (envelope.type) {
            TYPE_ATTACHMENT_CHUNK -> if (!isSettled) acceptChunk(envelope)
            TYPE_ERROR -> fail(refusal(envelope))
            else -> return false
        }
        return true
    }

    @Synchronized
    fun fail(failure: AttachmentRetrievalResult.Failed) {
        if (!result.complete(failure)) return
        slots.forEach { it?.fill(0) }
        slots = emptyArray()
        mutableActivity.value++
    }

    suspend fun await(): AttachmentFetchResult = result.await()

    override fun toString(): String = "AttachmentRetrievalTransfer(attachmentId=$attachmentId)"

    private fun acceptChunk(envelope: Envelope) {
        val chunk =
            try {
                MobileJson.decodeFromJsonElement(AttachmentChunkPayloadDto.serializer(), envelope.payload)
            } catch (_: IllegalArgumentException) {
                return fail(AttachmentRetrievalResult.Invalid)
            }
        val pinned = attachmentId
        if (pinned == null) {
            if (!isAttachmentIdShape(chunk.attachmentId)) return fail(AttachmentRetrievalResult.Invalid)
            attachmentId = chunk.attachmentId
        } else if (chunk.attachmentId != pinned) {
            return fail(AttachmentRetrievalResult.Invalid)
        }
        val claims = claims ?: return admitFirst(chunk)
        try {
            require(chunk.totalChunks == claims.totalChunks && chunk.size == claims.size && chunk.sha256 == claims.sha256)
            place(chunk)
        } catch (_: IllegalArgumentException) {
            fail(AttachmentRetrievalResult.Invalid)
        }
    }

    /** The first chunk fixes the claims. Both are range-checked, and checked against each other, before anything is sized. */
    private fun admitFirst(chunk: AttachmentChunkPayloadDto) {
        if (chunk.size < 0) return fail(AttachmentRetrievalResult.Invalid)
        if (chunk.size > AttachmentRetrievalLimit.MAX_BYTES) return fail(AttachmentRetrievalResult.TooLarge)
        val expectedChunks = maxOf(1L, (chunk.size + ATTACHMENT_CHUNK_BYTES - 1) / ATTACHMENT_CHUNK_BYTES)
        if (chunk.totalChunks.toLong() != expectedChunks) return fail(AttachmentRetrievalResult.Invalid)
        claims = chunk
        slots = arrayOfNulls(chunk.totalChunks)
        try {
            place(chunk)
        } catch (_: IllegalArgumentException) {
            fail(AttachmentRetrievalResult.Invalid)
        }
    }

    private fun place(chunk: AttachmentChunkPayloadDto) {
        require(chunk.index in slots.indices && slots[chunk.index] == null)
        val bytes = base64StdDecode(chunk.data)
        require(base64StdEncode(bytes) == chunk.data)
        require(bytes.size <= ATTACHMENT_CHUNK_BYTES && bytes.size <= chunk.size - acceptedBytes)
        slots[chunk.index] = bytes
        acceptedBytes += bytes.size
        filled++
        mutableActivity.value = filled
        RelayLog.d { "event=attachment_chunk_in id=$attachmentId index=${chunk.index} total=${chunk.totalChunks}" }
        if (filled == slots.size) complete()
    }

    /** Every index has arrived exactly once: only now are the length and the digest compared, exactly. */
    private fun complete() {
        val claims = checkNotNull(claims)
        val chunks = slots.map { checkNotNull(it) }
        val digest = MessageDigest.getInstance("SHA-256")
        chunks.forEach { digest.update(it) }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        require(acceptedBytes == claims.size && sha256 == claims.sha256)
        result.complete(
            AttachmentFetchResult.Fetched(
                content = AttachmentContent(chunks),
                displayName = attachmentDisplayName(claims.filename),
                mimeType = attachmentDisplayName(claims.mimeType),
            ),
        )
        slots = emptyArray()
        mutableActivity.value++
    }

    /** `attachment.not_found` is final; every other refusal, and a malformed one, is worth a retry. */
    private fun refusal(envelope: Envelope): AttachmentRetrievalResult.Failed {
        val code = ((envelope.payload as? JsonObject)?.get(KEY_CODE) as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (code == CODE_NOT_FOUND) AttachmentRetrievalResult.NotFound else AttachmentRetrievalResult.Unavailable
    }

    private companion object {
        const val TYPE_ATTACHMENT_CHUNK = "attachment_chunk"
        const val TYPE_ERROR = "error"
        const val KEY_CODE = "code"
        const val CODE_NOT_FOUND = "attachment.not_found"
    }
}

/**
 * The retrievals of one connection (#899), the retrieval-leg sibling of the upload state in
 * [MessageCommands]. [RemoteConversationRepository]'s `onInbound` offers each frame to [route], and its
 * collector's `finally` calls [end]. One retrieval at a time: that is what bounds the heap to one
 * [AttachmentRetrievalLimit.MAX_BYTES] buffer per host.
 */
internal class AttachmentRetrievals(
    private val nextRequestId: () -> Long,
    private val send: (Envelope) -> Boolean,
    private val stallTimeout: Duration = DEFAULT_STALL_TIMEOUT,
) {
    private val lock = Mutex()
    private var active: AttachmentRetrievalTransfer? = null
    private var inboundEnded = false

    /**
     * Sends one `request_attachment` for [attachmentId] of [conversationId] on this connection and waits for
     * its answer. Both ids must have the published shape before anything is sent or logged. The transfer is
     * registered before the send, so a fast answer cannot be missed. The wait fails
     * [AttachmentRetrievalResult.Unavailable] once [stallTimeout] passes with no accepted chunk. Never throws
     * except on cancellation. Logs the attachment id, chunk index and total only.
     */
    suspend fun fetch(
        conversationId: String,
        attachmentId: String,
    ): AttachmentFetchResult {
        if (!isAttachmentIdShape(conversationId) || !isAttachmentIdShape(attachmentId)) return AttachmentRetrievalResult.NotFound
        return retrieve(
            type = TYPE_REQUEST_ATTACHMENT,
            payload = MobileJson.encodeToJsonElement(RequestAttachmentPayloadDto(conversationId, attachmentId)),
            attachmentId = attachmentId,
            requestEvent = "event=attachment_request id=$attachmentId",
            outcomeEvent = "event=attachment_retrieval id=$attachmentId",
        )
    }

    /**
     * Sends one `read_workspace_file` for [path] in [conversationId]'s workspace (#1049) and waits for its answer,
     * under the same one-at-a-time rule and stall deadline as [fetch]. Every call sends a new request: nothing is
     * cached. The conversation id must have the published shape and [path] must not be blank, else
     * [AttachmentRetrievalResult.NotFound] with nothing sent. [path] is sent as given. Neither is ever logged.
     */
    suspend fun readWorkspaceFile(
        conversationId: String,
        path: String,
    ): AttachmentFetchResult {
        if (!isAttachmentIdShape(conversationId) || path.isBlank()) return AttachmentRetrievalResult.NotFound
        return retrieve(
            type = TYPE_READ_WORKSPACE_FILE,
            payload = MobileJson.encodeToJsonElement(ReadWorkspaceFilePayloadDto(conversationId, path)),
            attachmentId = null,
            requestEvent = "event=workspace_file_request",
            outcomeEvent = "event=workspace_file_read",
        )
    }

    /**
     * The exchange both requests share. The transfer is registered before the send, so a fast answer cannot be
     * missed, and the wait fails [AttachmentRetrievalResult.Unavailable] once [stallTimeout] passes with no
     * accepted chunk.
     */
    private suspend fun retrieve(
        type: String,
        payload: JsonElement,
        attachmentId: String?,
        requestEvent: String,
        outcomeEvent: String,
    ): AttachmentFetchResult =
        lock.withLock {
            val request = Envelope(id = nextRequestId(), type = type, ts = Clock.System.now().toString(), payload = payload)
            val transfer = AttachmentRetrievalTransfer(request.id, attachmentId)
            if (!begin(transfer)) return@withLock AttachmentRetrievalResult.Unavailable
            try {
                RelayLog.d { requestEvent }
                val sent =
                    try {
                        send(request)
                    } catch (_: Exception) {
                        false
                    }
                if (!sent) transfer.fail(AttachmentRetrievalResult.Unavailable)
                while (!transfer.isSettled) {
                    val seen = transfer.activity.value
                    withTimeoutOrNull(stallTimeout) { transfer.activity.first { it != seen } }
                        ?: transfer.fail(AttachmentRetrievalResult.Unavailable)
                }
                transfer.await().also { outcome ->
                    RelayLog.d { "$outcomeEvent outcome=${outcome::class.simpleName}" }
                }
            } finally {
                finish(transfer)
            }
        }

    @Synchronized
    fun route(envelope: Envelope): Boolean = active?.accept(envelope) == true

    /** Runs in the inbound collector's `finally`: a stream cannot outlive its connection. */
    @Synchronized
    fun end() {
        inboundEnded = true
        active?.fail(AttachmentRetrievalResult.Unavailable)
    }

    @Synchronized
    private fun begin(transfer: AttachmentRetrievalTransfer): Boolean {
        if (inboundEnded) return false
        active = transfer
        return true
    }

    @Synchronized
    private fun finish(transfer: AttachmentRetrievalTransfer) {
        if (active === transfer) active = null
    }

    companion object {
        /** No accepted chunk for this long ends the wait; the same 30 s desktop uses and the relay's pong timeout. */
        val DEFAULT_STALL_TIMEOUT: Duration = 30.seconds

        private const val TYPE_REQUEST_ATTACHMENT = "request_attachment"
        private const val TYPE_READ_WORKSPACE_FILE = "read_workspace_file"
    }
}
