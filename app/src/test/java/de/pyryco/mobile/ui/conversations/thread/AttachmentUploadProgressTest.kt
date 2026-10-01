package de.pyryco.mobile.ui.conversations.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1327: desktop's upload figure and its chunk threshold. */
class AttachmentUploadProgressTest {
    @Test
    fun percent_isTheFloorOfSentOverTotal() {
        assertEquals(33, uploadProgressPercent(1, 3))
        assertEquals(66, uploadProgressPercent(2, 3))
        assertEquals(100, uploadProgressPercent(3, 3))
        assertEquals(0, uploadProgressPercent(0, 10))
    }

    @Test
    fun percent_isClampedAndZeroForANonPositiveTotal() {
        assertEquals(100, uploadProgressPercent(11, 10))
        assertEquals(0, uploadProgressPercent(-1, 10))
        assertEquals(0, uploadProgressPercent(5, 0))
        assertEquals(0, uploadProgressPercent(5, -3))
        assertEquals(50, uploadProgressPercent(Int.MAX_VALUE / 2, Int.MAX_VALUE - 1))
    }

    @Test
    fun progress_existsOnlyFromEightChunks() {
        assertNull(attachmentUploadProgress(key = 1, sentChunks = 3, totalChunks = 7))
        assertEquals(AttachmentUploadProgress(1, 37), attachmentUploadProgress(key = 1, sentChunks = 3, totalChunks = 8))
    }
}
