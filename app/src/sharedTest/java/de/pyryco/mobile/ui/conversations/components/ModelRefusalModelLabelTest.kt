package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.thread.ThreadModelChoice
import de.pyryco.mobile.ui.conversations.thread.ThreadRunConfig
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadUiState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The refusal row's model names (#1494, Figma 620:1577 and 646:4707): a model the thread's menu knows reads as
 * its menu label in the title's body style, any other keeps its monospace span, and every name, known or not,
 * is its own span apart from the client copy.
 *
 * Fixtures are hand-written literals, never captured live payloads.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h892dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelRefusalModelLabelTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setThread(
        runConfig: ThreadRunConfig,
        row: ThreadItem.ModelRefusal = ROW,
    ) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                PyrycodeMobileTheme(darkTheme = true) {
                    ThreadScreen(
                        state =
                            ThreadUiState(
                                conversationId = "conversation",
                                displayName = "Refusals",
                                isPromoted = true,
                                hasMessages = true,
                                items = listOf(row),
                                runConfig = runConfig,
                            ),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        switchBackOffer = SwitchBackOffer(AT, row.originalModel, pending = false, failed = false),
                        onSwitchBack = {},
                    )
                }
            }
        }
    }

    private fun annotated(text: String): AnnotatedString =
        composeRule
            .onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .single()

    private fun lineCount(text: String): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().lineCount
    }

    /** The one span covering exactly [name], after asserting no other span straddles it into the copy. */
    private fun AnnotatedString.isolatedSpan(name: String): AnnotatedString.Range<SpanStyle> {
        val start = text.indexOf(name)
        assertTrue("\"$name\" is in \"$text\"", start >= 0)
        val end = start + name.length
        spanStyles.forEach { span ->
            assertTrue(
                "span ${span.start}..${span.end} straddles \"$name\" in \"$text\"",
                (span.start == start && span.end == end) || span.end <= start || span.start >= end,
            )
        }
        return spanStyles.single { it.start == start && it.end == end }
    }

    private fun AnnotatedString.clientSpan() = spanStyles.first { it.start == 0 }

    @Test
    fun knownModels_readAsTheirMenuLabelsInTheBodyStyle_onOneLine() {
        setThread(MENU)

        val title = annotated(KNOWN_TITLE)
        assertEquals(KNOWN_TITLE, title.text)
        for (name in listOf("Opus", "Sonnet")) {
            val span = title.isolatedSpan(name)
            assertNotEquals(FontFamily.Monospace, span.item.fontFamily)
            assertEquals(title.clientSpan().item.color, span.item.color)
        }
        assertEquals(1, lineCount(KNOWN_TITLE))

        val button = annotated("Switch back to Opus")
        assertNotEquals(FontFamily.Monospace, button.isolatedSpan("Opus").item.fontFamily)
    }

    @Test
    fun anIdentifierTheMenuDoesNotKnow_keepsItsMonospaceSpan() {
        setThread(ThreadRunConfig(choices = listOf(SONNET), menuAvailable = true))

        val title = annotated("Refused on claude-opus-5-5, continued on Sonnet")
        assertEquals(FontFamily.Monospace, title.isolatedSpan("claude-opus-5-5").item.fontFamily)
        assertNotEquals(FontFamily.Monospace, title.isolatedSpan("Sonnet").item.fontFamily)
        val button = annotated("Switch back to claude-opus-5-5")
        assertEquals(FontFamily.Monospace, button.isolatedSpan("claude-opus-5-5").item.fontFamily)
    }

    @Test
    fun anAmbiguousMatch_keepsTheMonospaceSpan() {
        val other = ThreadModelChoice("best", "Best", "", emptyList(), resolvedModel = "claude-opus-5-5")
        setThread(MENU.copy(choices = MENU.choices + other))

        val title = annotated("Refused on claude-opus-5-5, continued on Sonnet")
        assertEquals(FontFamily.Monospace, title.isolatedSpan("claude-opus-5-5").item.fontFamily)
        val button = annotated("Switch back to claude-opus-5-5")
        assertEquals(FontFamily.Monospace, button.isolatedSpan("claude-opus-5-5").item.fontFamily)
    }

    @Test
    fun noMenu_keepsBothMonospaceSpans() {
        setThread(ThreadRunConfig())

        val title = annotated("Refused on claude-opus-5-5, continued on claude-sonnet-5")
        assertEquals(FontFamily.Monospace, title.isolatedSpan("claude-opus-5-5").item.fontFamily)
        assertEquals(FontFamily.Monospace, title.isolatedSpan("claude-sonnet-5").item.fontFamily)
        val button = annotated("Switch back to claude-opus-5-5")
        assertEquals(FontFamily.Monospace, button.isolatedSpan("claude-opus-5-5").item.fontFamily)
    }

    @Test
    fun aBlankIdentifier_stillReadsUnknownModel_evenWithAMenu() {
        setThread(MENU, row = ROW.copy(originalModel = "\u001b[0m"))

        annotated("Refused on unknown model, continued on Sonnet")
        annotated("Switch back to unknown model")
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-09-23T12:00:00Z")
        val ROW = ThreadItem.ModelRefusal("claude-opus-5-5", "claude-sonnet-5", "Retried on Sonnet.", false, AT)
        const val KNOWN_TITLE = "Refused on Opus, continued on Sonnet"
        val OPUS = ThreadModelChoice("opus", "Opus", "", emptyList(), resolvedModel = "claude-opus-5-5")
        val SONNET = ThreadModelChoice("sonnet", "Sonnet", "", emptyList(), resolvedModel = "claude-sonnet-5")
        val MENU = ThreadRunConfig(choices = listOf(OPUS, SONNET), menuAvailable = true)
    }
}
