package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AttachmentUploadLimit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** A capability minted only after a bounded paste capture. No URI, name or path can mint one. */
class OwnedPasteCopy private constructor(
    private val file: File,
) {
    private var references = 1

    internal fun retain(): Boolean =
        synchronized(this) {
            if (references == 0) {
                false
            } else {
                references++
                true
            }
        }

    /** The last owner deletes only after any active private read leaves this monitor. */
    internal fun release() =
        synchronized(this) {
            if (references == 0) return@synchronized
            references--
            if (references == 0) {
                val removed =
                    try {
                        file.delete()
                    } catch (_: Exception) {
                        false
                    }
                RelayLog.d { "event=composer_paste_copy_cleanup outcome=${if (removed) "deleted" else "delete_failed"}" }
            }
        }

    internal suspend fun read(io: CoroutineDispatcher = Dispatchers.IO): AttachmentRead =
        withContext(io) {
            withInput { input ->
                readBounded(input, AttachmentUploadLimit.MAX_BYTES)?.let { AttachmentRead.Bytes(it) } ?: AttachmentRead.TooLarge
            } ?: AttachmentRead.Unreadable
        }

    /** Both send reads and sampled thumbnail decoding serialize with the last release. */
    internal fun <T> withInput(block: (InputStream) -> T): T? =
        synchronized(this) {
            if (references == 0) return@synchronized null
            try {
                file.inputStream().use(block)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    override fun toString(): String = "OwnedPasteCopy"

    companion object {
        internal const val DIRECTORY = "composer-paste-copies"

        internal suspend fun capture(
            root: File,
            reader: AttachmentReader,
            uri: String,
            io: CoroutineDispatcher = Dispatchers.IO,
        ): PasteCopyCapture {
            var created: File? = null
            var transferred = false
            try {
                val bytes =
                    when (val read = reader.read(uri)) {
                        is AttachmentRead.Bytes -> read.bytes
                        AttachmentRead.TooLarge -> return PasteCopyCapture.TooLarge
                        AttachmentRead.Unreadable -> return PasteCopyCapture.Unreadable
                    }
                if (bytes.size > AttachmentUploadLimit.MAX_BYTES) return PasteCopyCapture.TooLarge
                val copy =
                    withContext(io) {
                        currentCoroutineContext().ensureActive()
                        check(root.isDirectory || root.mkdirs())
                        val file = File.createTempFile("paste-", ".tmp", root)
                        created = file
                        file.outputStream().use { output ->
                            var offset = 0
                            while (offset < bytes.size) {
                                currentCoroutineContext().ensureActive()
                                val count = minOf(8192, bytes.size - offset)
                                output.write(bytes, offset, count)
                                offset += count
                            }
                        }
                        OwnedPasteCopy(file)
                    }
                transferred = true
                RelayLog.d { "event=composer_paste_copy_capture outcome=captured" }
                return PasteCopyCapture.Captured(copy, bytes.size.toLong())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return PasteCopyCapture.Unreadable
            } finally {
                // Includes prompt cancellation while withContext returns a fully written file.
                if (!transferred) {
                    try {
                        created?.delete()
                    } catch (_: Exception) {
                        // Startup retries leftovers.
                    }
                }
            }
        }

        /** Process initialization only; the root is dedicated, and no current-process owner exists yet. */
        internal fun clearLeftovers(root: File) {
            try {
                var failed = false
                root.listFiles()?.forEach { if (!it.delete()) failed = true }
                RelayLog.d { "event=composer_paste_copy_startup outcome=${if (failed) "cleanup_failed" else "cleared"}" }
            } catch (_: Exception) {
                RelayLog.d { "event=composer_paste_copy_startup outcome=cleanup_failed" }
            }
        }
    }
}

internal sealed interface PasteCopyCapture {
    class Captured(
        val copy: OwnedPasteCopy,
        val size: Long,
    ) : PasteCopyCapture

    data object TooLarge : PasteCopyCapture

    data object Unreadable : PasteCopyCapture
}
