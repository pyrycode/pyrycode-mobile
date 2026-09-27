package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The composer footer's model and effort buttons and their option overlay (#808), hosted on
 * [ThreadScreen] because the overlay layer, its anchor and its conversation-scoped open state all live
 * there. It replaces `ThreadStatusRowTest`, whose single `model · effort` line this footer retires.
 */
@RunWith(AndroidJUnit4::class)
class ThreadComposerFooterTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    private val opus =
        ThreadModelChoice(
            value = "opus[1m]",
            label = "Opus 4.7",
            detail = "",
            effortChoices = listOf(ThreadEffortChoice("high", "high"), ThreadEffortChoice("max", "max")),
        )
    private val haiku = ThreadModelChoice(value = "haiku", label = "Haiku", detail = "", effortChoices = emptyList())

    private val baseConfig =
        ThreadRunConfig(
            choices = listOf(opus, haiku),
            menuAvailable = true,
            settingsAvailable = true,
            savedModel = "opus[1m]",
            savedEffort = "high",
            sessionId = "s1",
        )

    private var state by mutableStateOf(state())
    private var shown by mutableStateOf(true)
    private val modelSelections = mutableListOf<String>()
    private val effortSelections = mutableListOf<String>()
    private val permissionSelections = mutableListOf<String>()
    private val overflowEvents = mutableListOf<ThreadEvent>()
    private val composerCommands = mutableListOf<ComposerAction>()

    private fun state(
        conversationId: String = "c1",
        runConfig: ThreadRunConfig = baseConfig,
        mutationsSupported: Boolean = true,
        absentActions: Set<ComposerAction> = emptySet(),
    ) = ThreadUiState(
        conversationId = conversationId,
        displayName = "Test channel",
        isPromoted = true,
        hasMessages = false,
        runConfig = runConfig,
        mutationsSupported = mutationsSupported,
        absentActions = absentActions,
    )

    private fun setThread(
        runConfig: ThreadRunConfig = baseConfig,
        mutationsSupported: Boolean = true,
        absentActions: Set<ComposerAction> = emptySet(),
    ) {
        state = state(runConfig = runConfig, mutationsSupported = mutationsSupported, absentActions = absentActions)
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                if (shown) {
                    ThreadScreen(
                        state = state,
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        onModelSelected = { modelSelections += it },
                        onEffortSelected = { effortSelections += it },
                        onPermissionModeSelected = { permissionSelections += it },
                        onOverflowEvent = { overflowEvents += it },
                        onComposerCommand = { composerCommands += it },
                    )
                }
            }
        }
    }

    private fun footerButton(label: String) = composeTestRule.onNode(hasText(label) and hasClickAction() and !isSelectable())

    private fun overlay() = composeTestRule.onNodeWithContentDescription(string(R.string.cd_options_overlay_dismiss))

    private val pendingState =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Applying")

    @Test
    fun footerKeepsActionsAndRunConfigurationWithoutDuplicateChoices() {
        setThread(baseConfig.copy(permissionMode = "plan"))

        footerButton("Actions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Opus 4.7").assertDoesNotExist()
        composeTestRule.onNodeWithText("high").assertDoesNotExist()
        composeTestRule.onNodeWithText("Plan").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(string(R.string.cd_thread_status_expand)).performClick()
        composeTestRule.onNodeWithText("Run configuration").assertIsDisplayed()
        composeTestRule.onNodeWithText("Opus 4.7").assertIsDisplayed()
        composeTestRule.onNodeWithText("Plan").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun runConfigurationSelectsModelEffortAndPermission() {
        setThread(baseConfig.copy(permissionMode = "plan"))
        val opener = composeTestRule.onNodeWithContentDescription(string(R.string.cd_thread_status_expand))

        opener.performClick()
        composeTestRule.onNode(hasText("Haiku") and isSelectable()).performClick()
        assertEquals(listOf("haiku"), modelSelections)

        opener.performClick()
        composeTestRule.onNode(hasText("max") and isSelectable()).performClick()
        assertEquals(listOf("max"), effortSelections)

        opener.performClick()
        composeTestRule.onNode(hasText("Bypass approvals") and isSelectable()).performScrollTo().performClick()
        assertEquals(listOf("bypassPermissions"), permissionSelections)
        composeTestRule.onNodeWithText("Run configuration").assertDoesNotExist()
    }

    @Test
    fun runConfigurationShowsUnavailablePermissionWhenNoConfirmedMode() {
        setThread(baseConfig.copy(permissionMode = ""))
        composeTestRule.onNodeWithContentDescription(string(R.string.cd_thread_status_expand)).performClick()
        composeTestRule.onNodeWithText("Permission mode unavailable").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bypass approvals").assertDoesNotExist()
    }

    private fun actionRow(label: String) = composeTestRule.onNode(hasText(label) and hasClickAction() and !isSelectable())

    // #884 AC#1: the Actions button leads the footer and opens the three rows in order, drawn as buttons.
    @Test
    fun actionsButton_opensTheThreeRowsInOrder() {
        setThread(baseConfig.copy(permissionMode = "plan"))

        val actions = footerButton("Actions").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(
            actions.left < composeTestRule.onNodeWithContentDescription(string(R.string.cd_attach_files)).getUnclippedBoundsInRoot().left,
        )
        footerButton("Actions").performClick()
        overlay().assertExists()

        val tops =
            listOf("Reset session", "Compact session", "Knowledge capture").map {
                actionRow(it)
                    .assertIsDisplayed()
                    .assertIsEnabled()
                    .getUnclippedBoundsInRoot()
                    .top
            }
        assertEquals(tops.sorted(), tops)
        composeTestRule.onAllNodes(isSelectable()).assertCountEquals(0)
    }

    // #884 AC#2: Reset session runs the overflow menu's existing reset path and closes the overlay.
    @Test
    fun resetSession_dispatchesTheExistingNewSessionEvent() {
        setThread()

        footerButton("Actions").performClick()
        actionRow("Reset session").performClick()

        assertEquals(listOf<ThreadEvent>(ThreadEvent.NewSession), overflowEvents)
        assertTrue(composerCommands.isEmpty())
        overlay().assertDoesNotExist()
    }

    // #884 AC#2: a command row hands its client-owned action to the send path and closes the overlay.
    @Test
    fun commandRows_dispatchTheirAction() {
        setThread()

        footerButton("Actions").performClick()
        actionRow("Compact session").performClick()
        overlay().assertDoesNotExist()
        footerButton("Actions").performClick()
        actionRow("Knowledge capture").performClick()

        assertEquals(listOf(ComposerAction.CompactSession, ComposerAction.KnowledgeCapture), composerCommands)
        assertTrue(overflowEvents.isEmpty())
    }

    // #884: Reset session keeps the overflow item's mutationsSupported gate.
    @Test
    fun resetSession_isAbsentWithoutMutations() {
        setThread(mutationsSupported = false)

        footerButton("Actions").performClick()

        composeTestRule.onNodeWithText("Reset session").assertDoesNotExist()
        actionRow("Compact session").assertIsDisplayed()
    }

    // #884 AC#3: a command the published menu proves absent is greyed out and does nothing.
    @Test
    fun absentCommand_isDisabled_andInert() {
        setThread(absentActions = setOf(ComposerAction.CompactSession))

        footerButton("Actions").performClick()
        composeTestRule.onNodeWithText("Compact session").assertIsNotEnabled().performClick()

        assertTrue(composerCommands.isEmpty())
        overlay().assertExists()
        actionRow("Knowledge capture").assertIsEnabled()
        actionRow("Reset session").assertIsEnabled()
    }

    // #884 AC#1: an outside tap and Back each close the Actions overlay without acting.
    @Test
    fun actionsOverlay_outsideTapAndBack_closeWithoutActing() {
        setThread()

        footerButton("Actions").performClick()
        composeTestRule.onNode(hasSetTextAction()).performClick()
        overlay().assertDoesNotExist()

        footerButton("Actions").performClick()
        overlay().assertExists()
        Espresso.pressBack()
        composeTestRule.waitForIdle()
        overlay().assertDoesNotExist()

        assertTrue(overflowEvents.isEmpty())
        assertTrue(composerCommands.isEmpty())
    }

    private fun contextSegment() = composeTestRule.onNode(hasTestTag(CONTEXT_USAGE_TEST_TAG))

    // #946 AC#1: with a reading, the footer shows Claude's percentage after the buttons, and the Status sheet
    // one tap away shows the same figure.
    @Test
    fun contextSegment_showsTheReportedPercentage_andTheSheetAgrees() {
        setThread(baseConfig.copy(contextPercent = 84))

        contextSegment()
            .assertTextEquals("Cxt: 84%")
            .assert(hasContentDescription("Context usage 84%"))
            .assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.cd_thread_status_expand)).performClick()
        composeTestRule.onNodeWithText("84% used").assertIsDisplayed()
        composeTestRule.onNodeWithText("Context usage unavailable").assertDoesNotExist()
    }

    // #946 AC#2: no reading is an explicit unavailable state on both surfaces, never a number.
    @Test
    fun contextSegment_withoutAReading_saysUnavailable_andShowsNoNumber() {
        setThread(baseConfig.copy(contextPercent = null))

        contextSegment()
            .assertTextEquals("Cxt: n/a")
            .assert(hasContentDescription("Context usage unavailable"))
        composeTestRule.onAllNodes(hasText("%", substring = true)).assertCountEquals(0)

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_thread_status_expand)).performClick()
        composeTestRule.onNodeWithText("Context usage unavailable").assertIsDisplayed()
        composeTestRule.onAllNodes(hasText("%", substring = true)).assertCountEquals(0)
    }

    // #946 AC#3: a newer reading replaces the shown one; the segment is a display and opens nothing.
    @Test
    fun contextSegment_showsAReplacedReading_andOpensNoOverlay() {
        setThread(baseConfig.copy(contextPercent = 12))
        contextSegment().assertTextEquals("Cxt: 12%").assert(!hasClickAction())

        state = state(runConfig = baseConfig.copy(contextPercent = 37))

        contextSegment().assertTextEquals("Cxt: 37%")
        composeTestRule.onNodeWithText("Cxt: 12%").assertDoesNotExist()
        overlay().assertDoesNotExist()
    }
}
