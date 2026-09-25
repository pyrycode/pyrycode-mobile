package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.network.attachmentDisplayName
import java.io.File

/**
 * The directory a note handed to another app is written to (#1068), a child of the attachment store's root
 * and so of the attachment provider's one root. Not 64 hex characters, so it is never a host's directory:
 * a host removal never deletes it, and emptying it never touches a host's files.
 */
internal const val SHARED_NOTE_DIRECTORY = "shared-note"

private const val FALLBACK_NOTE_NAME = "note.md"

// One writer at a time, so two opens never empty the directory under each other's write.
private val SharedNoteLock = Any()

/** Where the shared note is written: under the attachment store root `AppModule` wires, `<noBackupFilesDir>/attachments`. */
internal fun sharedNoteDirectory(noBackupFilesDir: File): File = File(noBackupFilesDir, "attachments/$SHARED_NOTE_DIRECTORY")

/**
 * The file name for a note called [name] (#1068). The name is daemon-authored and may carry a path: this is
 * its last component, sanitised as a display name and bounded to 255 UTF-8 bytes, or `note.md` when that
 * names no file.
 */
internal fun sharedNoteFileName(name: String): String {
    val last = attachmentDisplayName(name.substringAfterLast('/').substringAfterLast('\\')).trim()
    return if (last.isEmpty() || last == "." || last == "..") FALLBACK_NOTE_NAME else last
}

/**
 * Writes [text] as UTF-8 to [directory] under [name]'s file name (#1068) and returns the file, first deleting
 * whatever an earlier open left there, so one note stays on disk at most. `null` when the file would land
 * outside [directory] after canonicalisation, or on any failure. Blocking: call it on an IO dispatcher.
 * Exceptions are dropped unread: their messages can carry the path.
 */
internal fun writeSharedNote(
    directory: File,
    name: String,
    text: String,
): File? =
    synchronized(SharedNoteLock) {
        try {
            directory.mkdirs()
            directory.listFiles()?.forEach { it.deleteRecursively() }
            val file = File(directory, sharedNoteFileName(name))
            if (file.canonicalFile.parentFile != directory.canonicalFile) return null
            file.writeText(text, Charsets.UTF_8)
            file
        } catch (e: Exception) {
            null
        }
    }
