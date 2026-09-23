package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.isAttachmentIdShape
import de.pyryco.mobile.data.repository.AttachmentFetchResult
import de.pyryco.mobile.data.repository.AttachmentRetrievalResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Retrieved attachments, kept per host in app-private storage (#899).
 *
 * ### The root must be under `noBackupFilesDir`
 *
 * For the reason [FileConversationCache] gives: a file kept here must be exactly as transferable as the
 * Keystore-wrapped pairing that authorised fetching it, which is not at all. [root] is the caller's
 * decision, so the whole contract is provable in a plain JVM test.
 *
 * ### Layout
 *
 * `<root>/<sha256hex(serverId)>/<conversationId>/<attachmentId>` holds the bytes and
 * `<attachmentId>.meta.json` beside it the sanitised display name and MIME hint. The server id is opaque,
 * so it is hashed as [FileConversationCache] hashes it. Both other ids are used as path components only
 * after [isAttachmentIdShape]: lowercase hex and `-` cannot spell anything but themselves. The remote file
 * name never reaches a path. One host is one directory, so removing a host's files is one recursive delete.
 *
 * ### Writes
 *
 * The bytes reach disk only after the connection matched their length and digest. Metadata is written
 * first and the content last, each through a `.part` file and an atomic move, so the content's name is the
 * commit point: a partial file is never readable under the attachment's name, and a kept pair without
 * readable metadata is fetched again.
 *
 * ### One fetch per file
 *
 * Concurrent [retrieve] calls for one `(host, conversation, attachment)` share one fetch and one outcome. The
 * leader checks for a kept file before fetching, so a caller arriving just after a success reads it instead.
 * A cancelled leader hands the fetch on rather than cancelling its followers. The app must resolve a single
 * instance, as it does for [FileConversationCache].
 *
 * Logs the attachment id only after its shape passed; never a path, a name, a MIME type or an exception message.
 */
class AttachmentStore(
    private val root: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val inFlight = HashMap<Key, CompletableDeferred<AttachmentRetrievalResult?>>()

    /**
     * The kept file for [attachmentId] of [conversationId] on [serverId], or else the outcome of [fetch], whose
     * verified bytes are kept before [AttachmentRetrievalResult.Retrieved] is returned. Never throws except
     * on cancellation.
     */
    suspend fun retrieve(
        serverId: String,
        conversationId: String,
        attachmentId: String,
        fetch: suspend () -> AttachmentFetchResult,
    ): AttachmentRetrievalResult {
        if (!isAttachmentIdShape(conversationId) || !isAttachmentIdShape(attachmentId)) return AttachmentRetrievalResult.NotFound
        val key = Key(serverId, conversationId, attachmentId)
        while (true) {
            val (deferred, leader) = join(key)
            if (!leader) {
                // null: the leader was cancelled, so this caller tries again, and may lead.
                deferred.await()?.let { return it }
                continue
            }
            var outcome: AttachmentRetrievalResult? = null
            try {
                outcome = lead(key, fetch)
                return outcome
            } finally {
                release(key, deferred, outcome)
            }
        }
    }

    private suspend fun lead(
        key: Key,
        fetch: suspend () -> AttachmentFetchResult,
    ): AttachmentRetrievalResult {
        val content = contentFile(key)
        withContext(ioDispatcher) { readKept(content) }?.let { return it }
        return when (val fetched = fetch()) {
            is AttachmentRetrievalResult.Failed -> fetched
            is AttachmentFetchResult.Fetched ->
                try {
                    withContext(ioDispatcher) { keep(content, fetched) }
                } catch (_: IOException) {
                    RelayLog.d { "event=attachment_store_failed id=${key.attachmentId}" }
                    AttachmentRetrievalResult.Unavailable
                } catch (_: SecurityException) {
                    RelayLog.d { "event=attachment_store_failed id=${key.attachmentId}" }
                    AttachmentRetrievalResult.Unavailable
                }
        }
    }

    @Synchronized
    private fun join(key: Key): Pair<CompletableDeferred<AttachmentRetrievalResult?>, Boolean> {
        inFlight[key]?.let { return it to false }
        val deferred = CompletableDeferred<AttachmentRetrievalResult?>()
        inFlight[key] = deferred
        return deferred to true
    }

    @Synchronized
    private fun release(
        key: Key,
        deferred: CompletableDeferred<AttachmentRetrievalResult?>,
        outcome: AttachmentRetrievalResult?,
    ) {
        inFlight.remove(key)
        deferred.complete(outcome)
    }

    /** A kept file needs both its content and readable metadata; anything less is fetched again. */
    private fun readKept(content: File): AttachmentRetrievalResult.Retrieved? {
        if (!content.isFile) return null
        val meta =
            try {
                MobileJson.decodeFromString<KeptAttachment>(metaFile(content).readText())
            } catch (_: IOException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (meta.version != VERSION) return null
        return AttachmentRetrievalResult.Retrieved(content, meta.displayName, meta.mimeType)
    }

    private fun keep(
        content: File,
        fetched: AttachmentFetchResult.Fetched,
    ): AttachmentRetrievalResult.Retrieved {
        val directory = content.parentFile ?: throw IOException("attachment directory unavailable")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("attachment directory unavailable")
        val meta = KeptAttachment(VERSION, fetched.displayName, fetched.mimeType)
        writeAtomically(metaFile(content)) { it.write(MobileJson.encodeToString(meta).toByteArray(Charsets.UTF_8)) }
        writeAtomically(content) { fetched.content.writeTo(it) }
        return AttachmentRetrievalResult.Retrieved(content, meta.displayName, meta.mimeType)
    }

    /** A `.part` file then an atomic move; the `.part` is deleted if anything fails. */
    private fun writeAtomically(
        target: File,
        write: (OutputStream) -> Unit,
    ) {
        val temporary = File(target.parentFile, "${target.name}.part")
        try {
            temporary.outputStream().use(write)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun contentFile(key: Key) = File(File(File(root, sha256Hex(key.serverId)), key.conversationId), key.attachmentId)

    private fun metaFile(content: File) = File(content.parentFile, "${content.name}.meta.json")

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private data class Key(
        val serverId: String,
        val conversationId: String,
        val attachmentId: String,
    )

    @Serializable
    private data class KeptAttachment(
        val version: Int,
        @SerialName("display_name") val displayName: String,
        @SerialName("mime_type") val mimeType: String,
    )

    private companion object {
        const val VERSION = 1
    }
}
