package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.EffectiveEffort
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

    // AC#1: both buttons show the thread state's current values, and the Status sheet is one tap away.
    // #650 moved the permission control into the footer, so the sheet no longer carries a YOLO switch.
    @Test
    fun footer_showsCurrentValues_andKeepsTheStatusSheetOneTapAway() {
        setThread()

        footerButton("Opus 4.7").assertIsDisplayed()
        footerButton("high").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_thread_status_expand)).performClick()
        composeTestRule.onNodeWithText("Run configuration").assertIsDisplayed()
        composeTestRule.onNodeWithText("YOLO mode").assertDoesNotExist()
    }

    // #650: the permission button labels the confirmed mode and offers the modes the wire accepts, with
    // Auto approval left out for a row that does not support it. A choice hands back the wire value.
    @Test
    fun permissionButton_labelsTheConfirmedMode_andChoosingBypassDispatchesItsWireValue() {
        setThread(baseConfig.copy(permissionMode = "plan"))

        footerButton("Plan").performClick()
        overlay().assertExists()
        listOf("Manual approval", "Auto-approve edits", "Plan", "Approved actions only", "Bypass approvals").forEach {
            composeTestRule.onNode(hasText(it) and isSelectable()).assertIsDisplayed()
        }
        composeTestRule.onNode(hasText("Auto approval") and isSelectable()).assertDoesNotExist()

        composeTestRule.onNode(hasText("Bypass approvals") and isSelectable()).performClick()

        assertEquals(listOf("bypassPermissions"), permissionSelections)
        assertTrue(modelSelections.isEmpty())
        overlay().assertDoesNotExist()
    }

    // #650: no confirmed mode, no button — "" beside a live session id proves nothing.
    @Test
    fun permissionButton_isAbsentWithoutAConfirmedMode() {
        setThread(baseConfig.copy(permissionMode = ""))

        composeTestRule.onNodeWithText("Manual approval").assertDoesNotExist()
        composeTestRule.onNodeWithText("Bypass approvals").assertDoesNotExist()
        footerButton("Opus 4.7").assertIsDisplayed()
    }

    // #650: an outstanding write keeps the confirmed label, marks it pending and opens nothing.
    @Test
    fun permissionButton_whilePending_keepsTheConfirmedLabel() {
        setThread(baseConfig.copy(permissionMode = "plan", pendingPermission = "default"))

        composeTestRule.onNode(hasText("Plan") and pendingState).assertIsDisplayed()
        composeTestRule.onNodeWithText("Manual approval").assertDoesNotExist()
    }

    // AC#2 + AC#3: the model overlay lists the published models and nothing else. Choosing one hands the
    // verbatim value to the existing handler and closes the overlay.
    @Test
    fun modelOverlay_listsOnlyPublishedModels_andChoosingOneDispatchesAndCloses() {
        setThread()

        footerButton("Opus 4.7").performClick()
        overlay().assertExists()
        composeTestRule.onNode(hasText("Haiku") and isSelectable()).assertIsDisplayed()
        composeTestRule.onNode(hasText("Opus 4.7") and isSelectable()).assertIsDisplayed()
        composeTestRule.onNodeWithText("max").assertDoesNotExist()

        composeTestRule.onNode(hasText("Haiku") and isSelectable()).performClick()

        assertEquals(listOf("haiku"), modelSelections)
        assertTrue(effortSelections.isEmpty())
        overlay().assertDoesNotExist()
    }

    @Test
    fun effortOverlay_listsTheSelectedRowsLevels_andChoosingOneDispatches() {
        setThread()

        footerButton("high").performClick()
        composeTestRule.onNode(hasText("max") and isSelectable()).assertIsDisplayed()
        composeTestRule.onNode(hasText("Haiku") and isSelectable()).assertDoesNotExist()

        composeTestRule.onNode(hasText("max") and isSelectable()).performClick()

        assertEquals(listOf("max"), effortSelections)
        overlay().assertDoesNotExist()
    }

    // AC#2: a selected row publishing no effort levels has nothing to offer, so its button opens nothing.
    @Test
    fun effortButton_forARowPublishingNoLevels_opensNothing() {
        setThread(baseConfig.copy(savedModel = "haiku"))

        composeTestRule.onNode(hasText("high") and !isSelectable()).assertIsNotEnabled().performClick()

        overlay().assertDoesNotExist()
    }

    // AC#2: with no published menu, the model button opens nothing either.
    @Test
    fun modelButton_withNoPublishedMenu_opensNothing() {
        setThread(baseConfig.copy(choices = emptyList(), menuAvailable = false))

        composeTestRule.onNode(hasText("opus[1m]") and !isSelectable()).assertIsNotEnabled().performClick()

        overlay().assertDoesNotExist()
    }

    // AC#2: a tap outside closes the overlay, selects nothing and never reaches the composer. The click
    // lands near the input field's start, which the scrim covers. Since #884 put the Actions button first,
    // the model overlay opens far enough right to cover the field's centre, so the tap stays clear of it.
    @Test
    fun outsideTap_dismissesWithoutSelecting_andNeverReachesTheComposer() {
        setThread()

        footerButton("Opus 4.7").performClick()
        overlay().assertExists()

        composeTestRule.onNode(hasSetTextAction()).performTouchInput { click(centerLeft + Offset(8.dp.toPx(), 0f)) }

        overlay().assertDoesNotExist()
        composeTestRule.onNode(hasSetTextAction()).assertIsNotFocused()
        assertTrue(modelSelections.isEmpty())
        assertTrue(effortSelections.isEmpty())
    }

    // AC#2: the overlay opens above the button that anchors it.
    @Test
    fun overlay_opensAboveItsFooterButton() {
        setThread()
        val buttonTop = footerButton("Opus 4.7").getUnclippedBoundsInRoot().top

        footerButton("Opus 4.7").performClick()

        val lowestRowBottom =
            listOf("Opus 4.7", "Haiku")
                .map { composeTestRule.onNode(hasText(it) and isSelectable()).getUnclippedBoundsInRoot().bottom }
                .maxBy { it.value }
        assertTrue("overlay must sit above the footer", lowestRowBottom <= buttonTop)
    }

    // A cut menu is never presented as complete. The caption carries the producer's figure plus the
    // client's render cap, not a count of rows.
    @Test
    fun cutModelMenu_saysItIsASubset() {
        setThread(baseConfig.copy(droppedModels = 40, hiddenChoices = 2))

        footerButton("Opus 4.7").performClick()

        composeTestRule.onNodeWithText("+42 not listed").assertIsDisplayed()
    }

    // #889: an explicit null reading clears the selection and explains why, in the button's state
    // description; an applied value replaces the saved one on the button and in the overlay.
    @Test
    fun effortButton_followsTheAppliedReading_andExplainsAMissingOne() {
        setThread(baseConfig.copy(appliedEffort = EffectiveEffort.NotReported))

        footerButton("Effort").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.thread_effort_note_not_reported)),
        )

        state = state(runConfig = baseConfig.copy(appliedEffort = EffectiveEffort.Applied("max")))
        composeTestRule.waitForIdle()

        footerButton("max").performClick()
        composeTestRule.onNode(isSelectable() and hasText("max")).assertIsSelected()
        composeTestRule.onNode(isSelectable() and hasText("high")).assertIsNotSelected()
    }

    // AC#3: a pending tap is visibly distinct, and a refused write (the VM clears the pending tap) returns
    // the button to the value it showed before.
    @Test
    fun pendingSelection_isDistinct_andARefusalRevertsTheButton() {
        setThread(baseConfig.copy(pendingModel = "haiku"))

        composeTestRule.onNode(hasText("Haiku") and !isSelectable()).assert(pendingState)
        composeTestRule.onNode(hasText("high") and !isSelectable()).assert(!pendingState)

        state = state(runConfig = baseConfig)
        composeTestRule.waitForIdle()

        footerButton("Opus 4.7").assert(!pendingState)
        composeTestRule.onAllNodesWithText("Haiku").assertCountEquals(0)
    }

    // AC#4: the overlay never survives the conversation. Switching conversations or leaving the thread and
    // coming back opens with it closed.
    @Test
    fun overlay_doesNotSurviveLeavingTheConversation() {
        setThread()

        footerButton("Opus 4.7").performClick()
        overlay().assertExists()
        state = state(conversationId = "c2")
        composeTestRule.waitForIdle()
        overlay().assertDoesNotExist()

        footerButton("high").performClick()
        overlay().assertExists()
        shown = false
        composeTestRule.waitForIdle()
        shown = true
        composeTestRule.waitForIdle()
        overlay().assertDoesNotExist()
        composeTestRule.onNode(hasText("max") and isSelectable()).assertDoesNotExist()
    }

    private fun actionRow(label: String) = composeTestRule.onNode(hasText(label) and hasClickAction() and !isSelectable())

    // #884 AC#1: the Actions button leads the footer and opens the three rows in order, drawn as buttons.
    @Test
    fun actionsButton_opensTheThreeRowsInOrder() {
        setThread(baseConfig.copy(permissionMode = "plan"))

        val actions = footerButton("Actions").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(actions.left < footerButton("Plan").getUnclippedBoundsInRoot().left)
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
}
