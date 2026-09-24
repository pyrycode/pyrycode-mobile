package de.pyryco.mobile.ui.conversations.thread

import android.content.ContentResolver
import androidx.core.net.toUri
import de.pyryco.mobile.data.repository.AttachmentUploadLimit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads a pending attachment's bytes through its content URI at send time (#932). An interface so
 * [ThreadViewModel] can take a fake; the Android side is [ContentResolverAttachmentReader], kept in the UI
 * layer beside its only consumer so `data/` stays free of Android types.
 */
fun interface AttachmentReader {
    suspend fun read(uri: String): AttachmentRead
}

/** One read's outcome (#932). Never an exception: every provider failure is [Unreadable]. */
sealed interface AttachmentRead {
    /** The whole file, within `AttachmentUploadLimit.MAX_BYTES`. [toString] prints the size only. */
    class Bytes(
        val bytes: ByteArray,
    ) : AttachmentRead {
        override fun toString(): String = "Bytes(size=${bytes.size})"
    }

    /** The stream ran past `AttachmentUploadLimit.MAX_BYTES`, whatever size the provider reported. */
    data object TooLarge : AttachmentRead

    /** Refused URI, no stream, lapsed grant or a failed read. */
    data object Unreadable : AttachmentRead
}

private const val READ_BUFFER_BYTES = 8192

/**
 * All of [input], or `null` as soon as more than [maxBytes] have been read (#932). The count is this
 * function's own: a provider's reported size is not trusted, and a stream that never ends is cut off
 * one buffer past the bound.
 */
internal fun readBounded(
    input: InputStream,
    maxBytes: Int,
): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return out.toByteArray()
        total += read
        if (total > maxBytes) return null
        out.write(buffer, 0, read)
    }
}

/**
 * Whether a URI may be read for an upload (#932): a `content` URI whose authority is another app's.
 *
 * `ContentResolver.openInputStream` also opens `file://` and `android.resource://` URIs, and this app's
 * own providers, all with this app's identity. The URI comes back from another app's picker, so without
 * this check a hostile provider could name one of our private files and have it uploaded. Our own
 * authorities are [ownPackage] itself or start with `"$ownPackage."`; a name that merely shares the
 * prefix without the dot is someone else's.
 *
 * The comparison is on the provider part of the authority: the resolver drops a `userId@` prefix,
 * up to the last `@`, before it picks a provider, so `0@$ownPackage.fileprovider` still names ours.
 */
internal fun isForeignContentUri(
    scheme: String?,
    authority: String?,
    ownPackage: String,
): Boolean {
    if (scheme != ContentResolver.SCHEME_CONTENT || authority == null) return false
    val provider = authority.substringAfterLast('@')
    return provider.isNotEmpty() && provider != ownPackage && !provider.startsWith("$ownPackage.")
}

/**
 * [AttachmentReader] over the app's [ContentResolver] (#932), on [io] because the stream read blocks.
 *
 * Refuses any URI [isForeignContentUri] rejects before touching the resolver. Never converts a URI to
 * a path and needs no storage permission: the picker's grant is what makes the URI readable. Every
 * failure maps to [AttachmentRead.Unreadable] and the exception is dropped unread — a provider's
 * `FileNotFoundException` message carries the URI, which must never reach a log.
 */
class ContentResolverAttachmentReader(
    private val resolver: ContentResolver,
    private val ownPackage: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AttachmentReader {
    override suspend fun read(uri: String): AttachmentRead =
        withContext(io) {
            try {
                val parsed = uri.toUri()
                if (!isForeignContentUri(parsed.scheme, parsed.authority, ownPackage)) {
                    AttachmentRead.Unreadable
                } else {
                    resolver.openInputStream(parsed)?.use { stream ->
                        readBounded(stream, AttachmentUploadLimit.MAX_BYTES)
                            ?.let { AttachmentRead.Bytes(it) } ?: AttachmentRead.TooLarge
                    } ?: AttachmentRead.Unreadable
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AttachmentRead.Unreadable
            }
        }
}
