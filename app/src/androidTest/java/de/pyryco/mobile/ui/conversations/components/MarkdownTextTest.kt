package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `MarkdownText`'s rendered output for the three GFM constructs #681 added, and for the two the
 * flavour switch brought along uninvited.
 *
 * The AC4 tests here are the ones worth understanding before editing: they assert that a bare URL
 * and a `$…$` span render as their own source characters. Nothing in the renderer *implements* that
 * — it falls out of the dispatcher's `else` arms having no case for those node kinds — so the
 * property rests on an absent branch, and an absent branch reddens nothing when someone later adds
 * the case. These tests are the branch's stand-in. The parser-level half lives in
 * `MarkdownTextParsingTest`, which needs no device.
 */
@RunWith(AndroidJUnit4::class)
class MarkdownTextTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Colours the assertions compare against, captured from the same theme the content renders in. */
    private var onSurfaceVariant: Color = Color.Unspecified

    private fun render(
        markdown: String,
        maxWidth: androidx.compose.ui.unit.Dp? = null,
    ) {
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = false) {
                onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
                Box(
                    modifier =
                        Modifier
                            .testTag(CONTAINER_TAG)
                            .let { if (maxWidth == null) it else it.widthIn(max = maxWidth) },
                ) {
                    MarkdownText(markdown)
                }
            }
        }
    }

    /** The `AnnotatedString` behind the node whose rendered text is exactly [text]. */
    private fun annotatedTextOf(text: String): AnnotatedString =
        composeTestRule
            .onNodeWithText(text)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .single()

    /** The substrings carrying a line-through span, in span order. */
    private fun AnnotatedString.struckSubstrings(): List<String> =
        spanStyles
            .filter { it.item.textDecoration == TextDecoration.LineThrough }
            .map { this.text.substring(it.start, it.end) }

    private fun AnnotatedString.struckColours(): List<Color> =
        spanStyles
            .filter { it.item.textDecoration == TextDecoration.LineThrough }
            .map { it.item.color }

    /** The substrings carrying a span style matching [predicate], in span order. */
    private fun AnnotatedString.substringsStyled(predicate: (SpanStyle) -> Boolean): List<String> =
        spanStyles.filter { predicate(it.item) }.map { this.text.substring(it.start, it.end) }

    // ------------------------------------------------------------------ AC1 — tables

    @Test
    fun table_renders_its_header_and_body_cells() {
        render("| Name | Count |\n|:-----|------:|\n| alpha | 1 |\n| beta | 2 |\n")

        composeTestRule.onNodeWithText("Name").assertIsDisplayed()
        composeTestRule.onNodeWithText("Count").assertIsDisplayed()
        composeTestRule.onNodeWithText("alpha").assertIsDisplayed()
        composeTestRule.onNodeWithText("2").assertIsDisplayed()
        // The pipes and the delimiter row are markup, not content — under CommonMark this whole
        // source rendered as one paragraph of exactly these characters.
        composeTestRule.onNodeWithText("| Name | Count |").assertDoesNotExist()
        composeTestRule.onNodeWithText("|:-----|------:|").assertDoesNotExist()
    }

    /**
     * AC1's scroll clause, proven by scrolling rather than by inspecting a modifier: the far column
     * is laid out but off-screen, a swipe brings it in, and the container never widens to fit it.
     */
    @Test
    fun wide_table_scrolls_horizontally_without_widening_its_container() {
        render(
            "| Col A | Col B | Col C | Col D | Col E |\n" +
                "|---|---|---|---|---|\n" +
                "| alpha one | beta two | gamma three | delta four | epsilon five |\n",
            maxWidth = CONTAINER_MAX_WIDTH,
        )

        // Laid out but off-screen, then reachable by scrolling — `performScrollTo` drives the
        // nearest scrollable ancestor, so this passes only if the table itself is the scroller.
        composeTestRule.onNodeWithText("epsilon five").assertIsNotDisplayed()
        composeTestRule.onNodeWithText("epsilon five").performScrollTo().assertIsDisplayed()
        // The tolerance is px-to-dp rounding in the bounds readback, not slack in the claim: the
        // table's own content runs to several hundred dp, so a container that had grown to fit it
        // would miss this by two orders of magnitude rather than by a fraction of a pixel.
        val containerWidth =
            composeTestRule.onNodeWithTag(CONTAINER_TAG).getUnclippedBoundsInRoot().width
        assertTrue(
            "the table widened its container to $containerWidth",
            containerWidth <= CONTAINER_MAX_WIDTH + ROUNDING_TOLERANCE,
        )
    }

    /** A body row shorter than the header is not an error: the missing cell renders empty. */
    @Test
    fun table_tolerates_a_row_shorter_than_its_header() {
        render("| A | B |\n|---|---|\n| only |\n")

        composeTestRule.onNodeWithText("only").assertIsDisplayed()
        composeTestRule.onNodeWithText("B").assertIsDisplayed()
    }

    // ------------------------------------------------------------------ AC2 — task lists

    @Test
    fun task_list_marks_distinguish_checked_from_unchecked_and_are_inert() {
        render("- [x] done item\n- [ ] todo item\n- plain item\n")

        val checked = context.getString(R.string.markdown_task_mark_checked)
        val unchecked = context.getString(R.string.markdown_task_mark_unchecked)
        composeTestRule.onNodeWithContentDescription(checked).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(unchecked).assertIsDisplayed()

        // AC2's "not toggleable, focusable or otherwise actionable" — the mark renders a message
        // that has already been sent, so there is nothing for a tap to change.
        composeTestRule.onNodeWithContentDescription(checked).assertHasNoClickAction()
        composeTestRule.onNodeWithContentDescription(unchecked).assertHasNoClickAction()

        // The mark replaces the bullet rather than following the raw source through.
        composeTestRule.onNodeWithText("[x] done item").assertDoesNotExist()
        composeTestRule.onNodeWithText("[ ]").assertDoesNotExist()
        composeTestRule.onNodeWithText("done item").assertIsDisplayed()
        // A plain sibling in the same list keeps its bullet: the mark is per item, not per list.
        composeTestRule.onNodeWithText("•").assertIsDisplayed()
    }

    // ------------------------------------------------------------------ AC3 — strikethrough

    @Test
    fun double_tilde_renders_struck_and_de_emphasised() {
        render("Before ~~struck~~ after")

        val annotated = annotatedTextOf("Before struck after")
        assertEquals(listOf("struck"), annotated.struckSubstrings())
        assertEquals(listOf(onSurfaceVariant), annotated.struckColours())
    }

    /**
     * The form this library's parser does not recognise. Desktop strikes it (micromark's
     * `singleTilde` default is on), so the renderer pairs the loose tilde tokens itself — this is
     * the test that proves the two clients agree on the same source.
     */
    @Test
    fun single_tilde_renders_struck_and_de_emphasised() {
        render("Before ~struck~ after")

        val annotated = annotatedTextOf("Before struck after")
        assertEquals(listOf("struck"), annotated.struckSubstrings())
        assertEquals(listOf(onSurfaceVariant), annotated.struckColours())
    }

    /**
     * The hazard desktop's `remarkGfmSubset` docstring names: two home-relative paths on one line.
     * Neither tilde can close under the flanking rule, so nothing strikes and both survive as text.
     */
    @Test
    fun home_relative_paths_keep_their_tildes_and_strike_nothing() {
        render("Copy ~/src to ~/out now")

        val annotated = annotatedTextOf("Copy ~/src to ~/out now")
        assertEquals(emptyList<String>(), annotated.struckSubstrings())
    }

    // ------------------------------------------------- AC4 — what the flavour must NOT introduce

    @Test
    fun a_bare_url_renders_as_text_and_is_not_a_link() {
        render("Visit https://example.com/path now")

        val annotated = annotatedTextOf("Visit https://example.com/path now")
        assertTrue(
            "a bare URL must not become a tappable link under the GFM flavour",
            annotated.getLinkAnnotations(0, annotated.text.length).isEmpty(),
        )
    }

    /** The control for the test above: an authored markdown link still IS one. */
    @Test
    fun an_authored_link_is_still_a_link() {
        render("Visit [the site](https://example.com/path) now")

        val annotated = annotatedTextOf("Visit the site now")
        assertEquals(1, annotated.getLinkAnnotations(0, annotated.text.length).size)
    }

    @Test
    fun a_dollar_span_renders_as_its_own_characters_rather_than_as_maths() {
        render("Cost is \$20 and \$30 per item")

        composeTestRule.onNodeWithText("Cost is \$20 and \$30 per item").assertIsDisplayed()
    }

    @Test
    fun raw_html_renders_as_visible_characters() {
        render("Literally <b>bold</b> here")

        composeTestRule.onNodeWithText("Literally <b>bold</b> here").assertIsDisplayed()
    }

    // ------------------------------------------------------------------ AC5 — the mixed message

    /**
     * One reply carrying all three new constructs beside constructs that already worked, which is
     * where an interaction between them would show — a table arm swallowing the list that follows
     * it, or the tilde pairing reaching across a fence.
     */
    @Test
    fun a_message_mixing_every_construct_renders_each_part() {
        render(
            """
            ## Release check

            | Step | Owner |
            |:-----|------:|
            | build | ci |

            - [x] tagged
            - [ ] published

            The ~~old~~ new path, and `inline code`:

            ```kotlin
            fun ship() = Unit
            ```
            """.trimIndent(),
        )

        composeTestRule.onNodeWithText("Release check").assertIsDisplayed()
        composeTestRule.onNodeWithText("Step").assertIsDisplayed()
        composeTestRule.onNodeWithText("ci").assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.markdown_task_mark_checked))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("tagged").assertIsDisplayed()
        composeTestRule.onNodeWithText("fun ship() = Unit").assertIsDisplayed()

        val annotated = annotatedTextOf("The old new path, and inline code:")
        assertEquals(listOf("old"), annotated.struckSubstrings())
    }

    // ------------------------------------------------------- #768 — the heading's marker whitespace

    /**
     * Exact matches, not `substring` ones, and that is the whole point: the parser nests a
     * heading's content inside `ATX_CONTENT` with the marker whitespace as its first token, so
     * every heading rendered one space indented from the paragraphs around it from #129 until
     * here. A `substring` assertion passes green on that defect, which is how it survived two
     * tickets' worth of heading tests.
     */
    @Test
    fun atx_headings_render_without_the_marker_whitespace() {
        render("# One\n\n## Two\n\n### Three\n\n## Trailing ##\n")

        composeTestRule.onNodeWithText("One").assertIsDisplayed()
        composeTestRule.onNodeWithText("Two").assertIsDisplayed()
        composeTestRule.onNodeWithText("Three").assertIsDisplayed()
        // The closed form pads the content at BOTH ends — the space before the closing marker is
        // inside `ATX_CONTENT` too — so both edges come off together.
        composeTestRule.onNodeWithText("Trailing").assertIsDisplayed()
    }

    /**
     * Only the EDGE whitespace goes. `ATX_CONTENT`'s interior `WHITE_SPACE` tokens are the real
     * spaces between words, so the exact text match below is what separates this fix from the
     * tempting wrong one — filtering every `WHITE_SPACE` a level deeper renders `codeandboldandlink`.
     */
    @Test
    fun a_heading_keeps_its_inline_markup_and_its_interior_spacing() {
        render("## `code` and **bold** and [link](https://example.com)")

        val annotated = annotatedTextOf("code and bold and link")
        assertEquals(
            listOf("code"),
            annotated.substringsStyled { it.fontFamily == FontFamily.Monospace },
        )
        assertEquals(
            listOf("bold"),
            annotated.substringsStyled { it.fontWeight == FontWeight.Bold },
        )
        assertEquals(1, annotated.getLinkAnnotations(0, annotated.text.length).size)
    }

    /**
     * A marker-only heading has no `ATX_CONTENT` child at all. The following paragraph renders only
     * if the content lookup tolerated its absence — a throw here takes the whole composition down.
     */
    @Test
    fun a_marker_only_heading_renders_without_throwing() {
        render("##\n\nAfter the empty heading\n")

        composeTestRule.onNodeWithText("After the empty heading").assertIsDisplayed()
    }

    private companion object {
        const val CONTAINER_TAG = "markdown-container"
        val CONTAINER_MAX_WIDTH = 220.dp
        val ROUNDING_TOLERANCE = 1.dp
    }
}
