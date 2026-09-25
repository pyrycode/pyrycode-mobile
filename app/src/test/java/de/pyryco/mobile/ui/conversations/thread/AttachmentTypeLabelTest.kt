package de.pyryco.mobile.ui.conversations.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #933: the file tile's type label is a short extension, never the untrusted name itself. */
class AttachmentTypeLabelTest {
    @Test
    fun aShortExtension_isUpperCased() {
        assertEquals("PDF", attachmentTypeLabel("report.pdf"))
        assertEquals("GZ", attachmentTypeLabel("archive.tar.gz"))
        assertEquals("MP4", attachmentTypeLabel("clip.Mp4"))
    }

    @Test
    fun anythingElse_hasNoLabel() {
        assertNull(attachmentTypeLabel("noext"))
        assertNull(attachmentTypeLabel("trailing."))
        assertNull(attachmentTypeLabel("x.toolongext"))
        assertNull(attachmentTypeLabel("a.p f"))
        assertNull(attachmentTypeLabel("a.‮gpj"))
    }
}
