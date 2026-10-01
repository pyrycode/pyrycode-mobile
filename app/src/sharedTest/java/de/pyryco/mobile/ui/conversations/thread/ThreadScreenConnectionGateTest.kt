package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #1319: Send, Stop, Actions and the Status sheet's settings follow the host connection, re-enabling on
 * reconnect without navigation, while the field stays editable and the draft is kept.
 */
@RunWith(AndroidJUnit4::class)
class ThreadScreenConnectionGateTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var connection by mutableStateOf<ConnectionState>(ConnectionState.Offline)
    private var draft by mutableStateOf("")

    @Test
    fun send_followsTheConnection_andTheDraftSurvives() {
        draft = "hello"
        setScreen()
        val send = composeRule.onNodeWithContentDescription(SEND)

        send.assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextInput(" there")
        composeRule.runOnIdle { assertEquals("hello there", draft) }
        send.assertIsNotEnabled()

        connection = ConnectionState.Connecting
        send.assertIsNotEnabled()

        connection = ConnectionState.Connected
        send.assertIsEnabled()
        composeRule.runOnIdle { assertEquals("hello there", draft) }

        connection = ConnectionState.Offline
        send.assertIsNotEnabled()
    }

    @Test
    fun stop_followsTheConnection() {
        setScreen(isBusy = true)
        val stop = composeRule.onNodeWithContentDescription(STOP)

        stop.assertIsNotEnabled()
        connection = ConnectionState.Connected
        stop.assertIsEnabled()
    }

    @Test
    fun actions_followsTheConnection() {
        setScreen()
        val actions = composeRule.onNode(hasText(ACTIONS) and hasClickAction())

        actions.assertIsNotEnabled()
        connection = ConnectionState.Connected
        actions.assertIsEnabled()
    }

    @Test
    fun statusSheetSettings_followTheConnection() {
        setScreen()
        composeRule.onNodeWithContentDescription(STATUS).performClick()
        val modelRow = composeRule.onNode(hasText(SONNET.label) and isSelectable())

        modelRow.assertIsNotEnabled()
        connection = ConnectionState.Connected
        modelRow.assertIsEnabled()
    }

    private fun setScreen(isBusy: Boolean = false) {
        composeRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state =
                        ThreadUiState(
                            conversationId = "conversation",
                            displayName = "Gate",
                            runConfig =
                                ThreadRunConfig(
                                    choices = listOf(OPUS, SONNET),
                                    menuAvailable = true,
                                    settingsAvailable = true,
                                    savedModel = OPUS.value,
                                    sessionId = "session",
                                ),
                        ),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = connection,
                    onRetry = {},
                    isBusy = isBusy,
                    draft = draft,
                    onDraftChange = { draft = it },
                )
            }
        }
    }

    private companion object {
        const val SEND = "Send message"
        const val STOP = "Stop the running turn"
        const val ACTIONS = "Actions"
        const val STATUS = "Expand status details"
        val OPUS = ThreadModelChoice(value = "opus", label = "Opus 4.7", detail = "", effortChoices = emptyList())
        val SONNET = ThreadModelChoice(value = "sonnet", label = "Sonnet 4.6", detail = "", effortChoices = emptyList())
    }
}
