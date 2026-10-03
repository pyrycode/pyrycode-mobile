package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * #1043: the status band's task count pill, hosted on [ThreadScreen]. The count is
 * [ThreadUiState.backgroundTaskCount]; the pill opens the same panel the Actions menu's row does.
 * Native graphics, so the pill's label measures its real width and the band its real height.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskCountPillTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var count by mutableIntStateOf(0)

    private fun string(id: Int): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(id)

    private fun setThread(
        initialCount: Int,
        isThinking: Boolean = false,
        withMessage: Boolean = false,
    ) {
        count = initialCount
        val roster =
            BackgroundTaskRoster(
                listOf(BackgroundTask("t1", "toolu_t1", "local_bash", "sleep 300", null, null, null, false)),
                0,
            )
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state =
                        ThreadUiState(
                            conversationId = "c1",
                            displayName = "Test channel",
                            isPromoted = true,
                            hasMessages = withMessage,
                            items = if (withMessage) listOf(newestMessage) else emptyList(),
                            backgroundTasks = roster,
                            backgroundTaskCount = count,
                        ),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    isThinking = isThinking,
                )
            }
        }
    }

    private fun pill(text: String) = composeTestRule.onNodeWithText(text)

    private val newestMessage =
        ThreadItem.MessageItem(
            Message(
                id = "m1",
                sessionId = "s1",
                role = Role.Assistant,
                content = "newest message",
                timestamp = Instant.parse("2026-05-20T10:00:00Z"),
                isStreaming = false,
            ),
        )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun figmaVariant_anchorsTaskPillAtTheInputAreasTopRight() {
        val roster = BackgroundTaskRoster(listOf(BackgroundTask("t1", "toolu_t1", "local_bash", "sleep 300", null, null, null, false)), 0)
        val attachment = PendingAttachment(1, "content://frame/test", "fixture.pdf", "application/pdf", 1)
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                PyrycodeMobileTheme(darkTheme = true) {
                    ThreadScreen(
                        state =
                            ThreadUiState(
                                "c1",
                                "Reference thread",
                                isPromoted = true,
                                hasMessages = true,
                                items = listOf(newestMessage),
                                backgroundTasks = roster,
                                backgroundTaskCount = 2,
                            ),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        isThinking = true,
                        attachments = listOf(attachment),
                    )
                }
            }
        }
        val bounds = composeTestRule.onNodeWithContentDescription("2 tasks running").getUnclippedBoundsInRoot()
        assertEquals(288f, bounds.left.value, 2f)
        assertEquals(392f, bounds.right.value, 2f)
        assertEquals(696f, bounds.top.value, 2f)
        assertEquals(720f, bounds.bottom.value, 2f)
        val editor = composeTestRule.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        val footerActions = composeTestRule.onNodeWithText(string(R.string.thread_footer_actions)).getUnclippedBoundsInRoot()
        assertTrue("footer actions must clear the editor", editor.bottom <= footerActions.top)
    }

    // The list is laid out from its newest end, so the newest message follows the message area's bottom,
    // which is the composer's top: the band's height moves it.
    private fun messageBottom() =
        composeTestRule
            .onNodeWithText("newest message")
            .getUnclippedBoundsInRoot()
            .bottom

    @Test
    fun pill_showsBesideALiveReading_atTheRightOfIt() {
        setThread(initialCount = 2, isThinking = true)

        val thinking = composeTestRule.onNodeWithContentDescription(string(R.string.cd_thread_thinking))
        thinking.assertIsDisplayed()
        pill("2 tasks running").assertIsDisplayed()
        assertTrue(
            pill("2 tasks running").getUnclippedBoundsInRoot().left >= thinking.getUnclippedBoundsInRoot().right,
        )
    }

    @Test
    fun pill_showsAlone_atTheBandsRightEnd() {
        setThread(initialCount = 3)

        pill("3 tasks running").assertIsDisplayed()
        val rootRight = composeTestRule.onRoot().getUnclippedBoundsInRoot().right
        assertEquals((rootRight - ComposerGutter).value, pill("3 tasks running").getUnclippedBoundsInRoot().right.value, 1f)
    }

    @Test
    fun oneTask_readsSingular() {
        setThread(initialCount = 1)

        pill("1 task running").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 tasks running").assertDoesNotExist()
    }

    @Test
    fun zeroCount_showsNoPill_andThePillDoesNotMoveTheBand() {
        setThread(initialCount = 0, withMessage = true)
        val idleBottom = messageBottom()
        composeTestRule.onNodeWithText("running", substring = true).assertDoesNotExist()

        count = 2
        composeTestRule.waitForIdle()
        pill("2 tasks running").assertIsDisplayed()
        // #1312: the band is always composed at the pill's 24dp height, so the pill does not raise it.
        assertEquals(idleBottom, messageBottom())

        count = 0
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("running", substring = true).assertDoesNotExist()
        assertEquals(idleBottom, messageBottom())
    }

    // #1628: the pill hugs its label, 8dp each side, with no minimum width leaving blank pill to its right.
    private fun assertOneTaskPillHugsItsLabel() {
        val label = composeTestRule.onNodeWithText("1 task running", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val bounds = composeTestRule.onNodeWithContentDescription("1 task running").getUnclippedBoundsInRoot()
        assertEquals((label.right - label.left + 16.dp).value, (bounds.right - bounds.left).value, 0.5f)
        assertEquals(24f, bounds.height.value, 0.5f)
        val rootRight = composeTestRule.onRoot().getUnclippedBoundsInRoot().right
        assertEquals((rootRight - ComposerGutter).value, bounds.right.value, 1f)
    }

    @Test
    fun oneTaskPill_alone_hugsItsLabel_atTheBandsRightEnd() {
        setThread(initialCount = 1)

        assertOneTaskPillHugsItsLabel()
    }

    @Test
    fun oneTaskPill_besideAReading_hugsItsLabel_atTheBandsRightEnd() {
        setThread(initialCount = 1, isThinking = true)

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_thread_thinking)).assertIsDisplayed()
        assertOneTaskPillHugsItsLabel()
    }

    @Test
    fun tappingThePill_opensTheBackgroundTaskPanel() {
        setThread(initialCount = 1)
        composeTestRule.onNodeWithText("sleep 300").assertDoesNotExist()

        pill("1 task running").performTouchInput { click(center) }

        composeTestRule.onNodeWithText("Background tasks").assertIsDisplayed()
        composeTestRule.onNodeWithText("sleep 300").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun compactWidthAndLargeText_keepPillClearOfReadingAndOpeningPanel() {
        val roster = BackgroundTaskRoster(listOf(BackgroundTask("t1", "toolu_t1", "local_bash", "sleep 300", null, null, null, false)), 0)
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp))) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    PyrycodeMobileTheme(darkTheme = true) {
                        ThreadScreen(
                            state = ThreadUiState("c1", "Compact thread", backgroundTasks = roster, backgroundTaskCount = 2),
                            onBack = {},
                            onSendMessage = {},
                            connectionState = ConnectionState.Connected,
                            onRetry = {},
                            isThinking = true,
                        )
                    }
                }
            }
        }
        val bounds = composeTestRule.onNodeWithContentDescription("2 tasks running").getUnclippedBoundsInRoot()
        val reading = composeTestRule.onNodeWithContentDescription(string(R.string.cd_thread_thinking)).getUnclippedBoundsInRoot()
        assertTrue(bounds.left >= reading.right)
        assertTrue(bounds.right <= composeTestRule.onRoot().getUnclippedBoundsInRoot().right)
        assertTrue(bounds.height >= 24.dp)

        pill("2 tasks running").performTouchInput { click(center) }
        composeTestRule.onNodeWithText("Background tasks").assertIsDisplayed()
        composeTestRule.onNodeWithText("sleep 300").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun taskPill_pointerAndAdjacentComposerTargetsKeepTheirActions() {
        setThread(initialCount = 2, isThinking = true)
        val root = composeTestRule.onRoot()
        val pillBounds = pill("2 tasks running").getUnclippedBoundsInRoot()
        val editor = composeTestRule.onNode(hasSetTextAction())
        val editorBounds = editor.getUnclippedBoundsInRoot()
        val actions = composeTestRule.onNodeWithText(string(R.string.thread_footer_actions))
        val actionsBounds = actions.getUnclippedBoundsInRoot()
        val reading = composeTestRule.onNodeWithContentDescription(string(R.string.cd_thread_thinking))
        val readingBounds = reading.getUnclippedBoundsInRoot()
        assertTrue("reading clears the pill surface", readingBounds.right <= pillBounds.left)
        assertTrue("editor clears the pill surface", editorBounds.top >= pillBounds.bottom)
        assertTrue("Actions clears the pill surface", actionsBounds.top >= pillBounds.bottom)

        root.performTouchInput {
            click(Offset((editorBounds.left + editorBounds.right).toPx() / 2f, (editorBounds.top + editorBounds.bottom).toPx() / 2f))
        }
        editor.assertIsFocused()
        composeTestRule.onNodeWithText("Background tasks").assertDoesNotExist()

        root.performTouchInput {
            click(Offset((pillBounds.left + pillBounds.right).toPx() / 2f, (pillBounds.top + pillBounds.bottom).toPx() / 2f))
        }
        composeTestRule.onNodeWithText("Background tasks").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Close").performClick()

        root.performTouchInput {
            click(Offset((actionsBounds.left + actionsBounds.right).toPx() / 2f, (actionsBounds.top + actionsBounds.bottom).toPx() / 2f))
        }
        composeTestRule.onNodeWithText("Background tasks (2)").assertIsDisplayed()
        pill("2 tasks running").assertIsDisplayed()
    }
}
