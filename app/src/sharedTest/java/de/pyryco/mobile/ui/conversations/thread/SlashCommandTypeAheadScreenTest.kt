package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.SlashCommandMenuRow
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The composer's slash-command type-ahead (#885) on [ThreadScreen]: typing a fragment opens the
 * published rows over the input field, a pick completes the draft without sending, and a tap outside,
 * Back or the keyboard hiding closes the suggestions with the text unchanged.
 */
@RunWith(AndroidJUnit4::class)
class SlashCommandTypeAheadScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    private fun row(
        name: String,
        argumentHint: String = "",
        description: String = "",
    ) = SlashCommandMenuRow(name, argumentHint, description, emptyList(), null)

    private val menu =
        listOf(
            row("clear", description = "Start a new session"),
            row("model", argumentHint = "<model>", description = "Switch the model"),
            row("compact"),
        )

    private var draft by mutableStateOf("")
    private val sent = mutableListOf<String>()

    private fun setThread(commands: List<SlashCommandMenuRow>? = menu) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state =
                        ThreadUiState(
                            conversationId = "c1",
                            displayName = "Test channel",
                            isPromoted = true,
                            slashCommands = commands,
                        ),
                    onBack = {},
                    onSendMessage = { sent += it },
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    draft = draft,
                    onDraftChange = { draft = it },
                )
            }
        }
    }

    private fun field() = composeTestRule.onNode(hasSetTextAction())

    private fun overlay() = composeTestRule.onNodeWithContentDescription(string(R.string.cd_options_overlay_dismiss))

    private fun suggestion(label: String) = composeTestRule.onNode(hasText(label) and hasClickAction())

    private fun type(text: String) {
        field().performTextReplacement(text)
        composeTestRule.waitForIdle()
    }

    // #1607: Figma 685:4232's rows draw the label and the description in their full line boxes; the
    // theme's bodySmall default trims both to their glyphs, shortening every row by a few px against the
    // frame and tightening the label-to-description gap.
    @Test
    fun rowLabelAndDescriptionKeepTheirFullLineBoxes() {
        setThread()
        type("/cl")

        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeTestRule
            .onNode(hasText("Start a new session"))
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(
            androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
            results
                .single()
                .layoutInput.style.lineHeightStyle
                ?.trim,
        )

        val labelResults = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        suggestion("/clear").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(labelResults) }
        assertEquals(
            androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
            labelResults
                .single()
                .layoutInput.style.lineHeightStyle
                ?.trim,
        )
    }

    @Test
    fun aLeadingSlashFragment_listsTheMatchingRows_withHintAndDescription() {
        setThread()

        type("/")
        suggestion("/clear").assertExists()
        suggestion("/compact").assertExists()
        composeTestRule.onNode(hasText("/model <model>")).assertExists()
        composeTestRule.onNode(hasText("Switch the model")).assertExists()

        type("/cl")
        suggestion("/clear").assertExists()
        composeTestRule.onNode(hasText("/model <model>")).assertDoesNotExist()
    }

    @Test
    fun aSlashNotFirst_aSpace_orNoMenu_showNothing() {
        setThread()

        type("x/cl")
        overlay().assertDoesNotExist()
        type("/cl ")
        overlay().assertDoesNotExist()
    }

    @Test
    fun noMenuReceived_showsNothing() {
        setThread(commands = null)

        type("/")
        overlay().assertDoesNotExist()
    }

    @Test
    fun pickingAHintedRow_completesWithASpace_sendsNothing_andTheArgumentSendsNormally() {
        setThread()

        type("/mo")
        composeTestRule.onNode(hasText("/model <model>") and hasClickAction()).performClick()
        composeTestRule.waitForIdle()

        assertEquals("/model ", draft)
        assertTrue(sent.isEmpty())
        overlay().assertDoesNotExist()

        field().performTextInput("opus")
        composeTestRule.waitForIdle()
        assertEquals("/model opus", draft)
        composeTestRule.onNodeWithContentDescription(string(R.string.cd_send_message)).performClick()
        assertEquals(listOf("/model opus"), sent)
    }

    @Test
    fun pickingAnUnhintedRow_completesWithoutASpace_andStaysClosedUntilTheNextEdit() {
        setThread()

        type("/comp")
        suggestion("/compact").performClick()
        composeTestRule.waitForIdle()

        assertEquals("/compact", draft)
        overlay().assertDoesNotExist()
        assertTrue(sent.isEmpty())

        type("/comp")
        overlay().assertExists()
    }

    @Test
    fun anOutsideTap_dismisses_leavesTheText_andTheNextEditReopens() {
        setThread()

        type("/cl")
        overlay().performTouchInput { click(topCenter + Offset(0f, 8.dp.toPx())) }
        composeTestRule.waitForIdle()

        overlay().assertDoesNotExist()
        assertEquals("/cl", draft)

        type("/cle")
        suggestion("/clear").assertExists()
    }

    @Test
    fun back_dismisses_andLeavesTheText() {
        setThread()

        type("/cl")
        overlay().assertExists()
        Espresso.pressBack()
        composeTestRule.waitForIdle()

        overlay().assertDoesNotExist()
        assertEquals("/cl", draft)
    }

    @Test
    fun aMultiLineOverlongDescription_rendersAsABoundedRow() {
        val long = (1..60).joinToString("\n") { "line $it of a very long workspace-authored description" }
        setThread(commands = listOf(row("long", description = long), row("short", description = "One line")))

        type("/")
        val longHeight = suggestion("/long").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        val shortHeight = suggestion("/short").getUnclippedBoundsInRoot().let { it.bottom - it.top }

        assertTrue("row height $longHeight must stay bounded", longHeight <= shortHeight + 20.dp)
        composeTestRule.onNode(hasText("line 60", substring = true)).assertDoesNotExist()
    }

    @Test
    fun hidingTheKeyboard_dismisses_andLeavesTheText() {
        var imeVisible by mutableStateOf(true)
        var completed: String? = null
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                Box(Modifier.fillMaxSize()) {
                    SlashCommandTypeAhead(
                        text = "/cl",
                        commands = menu,
                        anchor = Rect(left = 40f, top = 600f, right = 300f, bottom = 650f),
                        imeVisible = imeVisible,
                        onComplete = { completed = it },
                        resetKey = "c1",
                    )
                }
            }
        }
        suggestion("/clear").assertExists()

        imeVisible = false
        composeTestRule.waitForIdle()

        overlay().assertDoesNotExist()
        assertEquals(null, completed)
    }
}
