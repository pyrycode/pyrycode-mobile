package de.pyryco.mobile.ui.conversations.thread

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** #1068: the file a note is handed to another app through — its name, its place, and one at a time. */
class SharedNoteFileTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun fileName_isTheLastPathComponent() {
        assertEquals("Plan.md", sharedNoteFileName("Plan.md"))
        assertEquals("Plan.md", sharedNoteFileName("notes/Plan.md"))
        assertEquals("Plan.md", sharedNoteFileName("/etc/../notes/Plan.md"))
        assertEquals("b.md", sharedNoteFileName("a\\b.md"))
        assertEquals("Builder Plan.md", sharedNoteFileName("Builder\u0000 Plan‮.md"))
    }

    @Test
    fun fileName_thatNamesNoFile_fallsBack() {
        for (name in listOf("", " ", ".", "..", "notes/", "notes/..", "a\\.", "\u0007")) {
            assertEquals("name '$name'", "note.md", sharedNoteFileName(name))
        }
    }

    @Test
    fun fileName_fitsTheFileSystemLimit() {
        val name = sharedNoteFileName("é".repeat(300) + ".md")

        assertFalse(name.toByteArray(Charsets.UTF_8).size > 255)
    }

    @Test
    fun theDirectory_isInsideTheProviderRoot_andNeverAHostDirectory() {
        val directory = sharedNoteDirectory(File("/data/no_backup"))

        assertEquals(File("/data/no_backup/attachments"), directory.parentFile)
        assertFalse(Regex("[0-9a-f]{64}").matches(directory.name))
    }

    @Test
    fun write_keepsTheExactText_insideTheDirectory_underTheNotesName() {
        val directory = File(temp.root, "shared-note")
        val text = "# Plan\n\nÄäkköset ja 🚀 emoji.\n"

        val file = writeSharedNote(directory, "notes/Plan.md", text)

        assertNotNull(file)
        assertEquals(directory.canonicalFile, file?.canonicalFile?.parentFile)
        assertEquals("Plan.md", file?.name)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), file?.readBytes())
    }

    @Test
    fun eachWrite_replacesThePreviousOne() {
        val directory = File(temp.root, "shared-note")

        writeSharedNote(directory, "First.md", "one")
        writeSharedNote(directory, "Second.md", "two")

        assertEquals(listOf("Second.md"), directory.list()?.toList())
    }

    @Test
    fun aNameThatWouldClimbOut_staysInside() {
        val directory = File(temp.root, "shared-note")

        val file = writeSharedNote(directory, "../../escape.md", "text")

        assertEquals(directory.canonicalFile, file?.canonicalFile?.parentFile)
        assertFalse(File(temp.root, "escape.md").exists())
    }

    @Test
    fun aDirectoryThatCannotBeMade_writesNothing() {
        val blocked = temp.newFile("shared-note")

        assertNull(writeSharedNote(blocked, "Plan.md", "text"))
    }
}
