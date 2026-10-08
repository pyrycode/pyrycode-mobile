package de.pyryco.mobile.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttentionPreviewTest {
    @Test
    fun markdownKeepsProseLabelsAndCodeButDropsDestinationsAndDelimiters() {
        assertEquals(
            "Heading bold italic struck label code ./gradlew lint",
            notificationPreview(
                "# Heading\n\n**bold** _italic_ ~~struck~~ [label](https://private.example/path) `code`\n\n```bash\n./gradlew lint\n```",
            ),
        )
        assertEquals("label", notificationPreview("[label][ref]\n\n[ref]: https://private.example/path"))
    }

    @Test
    fun nestedLinkFormattingImagesReferenceLabelsAndBlockMarkersAreRemoved() {
        assertEquals("bold code image", notificationPreview("[**bold** `code`](https://secret) ![image](https://image)"))
        assertEquals("label", notificationPreview("[label]\n\n[label]: https://secret"))
        assertEquals("Heading visible", notificationPreview("Heading\n=======\n\n---\n\n<b>visible</b>"))
        assertEquals("**literal** <tag>", notificationPreview("`**literal** <tag>`"))
    }

    @Test
    fun unicodeWhitespaceCollapsesAndRemainingControlsAreDropped() {
        assertEquals("a b c d", notificationPreview(" \na\tb\r\nc\u00a0\u2003d\u0000\u0007\u200b\u202e "))
        assertEquals("one two", notificationPreview("`one\u0085two`"))
        assertEquals("one two three", notificationPreview("one\u0085two\u0085\n\tthree"))
        assertNull(notificationPreview("\n\t\u0000"))
        assertNull(notificationPreview(null))
    }

    @Test
    fun truncationCountsCodePointsAndIncludesExactlyOneEllipsis() {
        val exact = "😀".repeat(200)
        assertEquals(exact, notificationPreview(exact))
        val cut = requireNotNull(notificationPreview(exact + "z"))
        assertEquals("😀".repeat(199) + "…", cut)
        assertEquals(200, cut.codePointCount(0, cut.length))
    }
}
