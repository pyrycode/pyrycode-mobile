package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reader's line breaks inside runs too long for Android's text engine to lay out cheaply. */
class LongRunBreaksTest {
    private val br = '\n'

    @Test
    fun textWithoutALongRun_isReturnedAsIs() {
        val text = AnnotatedString("short words " + "x".repeat(MAX_UNBROKEN_RUN) + " end")

        assertSame(text, text.withBreaksInLongRuns())
    }

    @Test
    fun aLongRun_getsALineBreakEveryMaxRun_andKeepsItsCharacters() {
        val run = "a".repeat(MAX_UNBROKEN_RUN + 1)
        val broken = AnnotatedString("lead $run tail").withBreaksInLongRuns().text

        assertEquals("lead $run tail", broken.replace(br.toString(), ""))
        val pieces = broken.removePrefix("lead ").removeSuffix(" tail").split(br)
        assertEquals(listOf(MAX_UNBROKEN_RUN, 1), pieces.map { it.length })
        assertTrue(broken.startsWith("lead "))
    }

    @Test
    fun aBreak_neverSplitsASurrogatePair() {
        val run = "a" + "😀".repeat(MAX_UNBROKEN_RUN * 2)
        val broken = AnnotatedString(run).withBreaksInLongRuns().text

        assertEquals(run, broken.replace(br.toString(), ""))
        broken.forEachIndexed { index, char ->
            if (char == br) {
                assertTrue(!broken[index - 1].isHighSurrogate())
            }
        }
    }

    @Test
    fun stylesAndLinks_coverTheSameCharactersAfterTheBreaks() {
        val run = "b".repeat(MAX_UNBROKEN_RUN * 2)
        val text =
            buildAnnotatedString {
                append("see ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(run) }
                append(" and ")
                withLink(LinkAnnotation.Url("https://example.com")) { append(run) }
            }

        val broken = text.withBreaksInLongRuns()

        val bold = broken.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(run, bold.joinToString("") { broken.text.substring(it.start, it.end) }.replace(br.toString(), ""))
        val links = broken.getLinkAnnotations(0, broken.length)
        assertTrue(links.all { (it.item as LinkAnnotation.Url).url == "https://example.com" })
        assertEquals(run, links.joinToString("") { broken.text.substring(it.start, it.end) }.replace(br.toString(), ""))
    }
}
