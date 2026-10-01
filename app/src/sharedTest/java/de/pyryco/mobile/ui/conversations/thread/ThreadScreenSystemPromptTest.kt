package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.repository.SessionPromptStatus
import de.pyryco.mobile.ui.conversations.components.CHANNEL_INFO_PROMPT_FIELD_TAG
import de.pyryco.mobile.ui.conversations.components.SystemPromptEditorState
import de.pyryco.mobile.ui.conversations.components.SystemPromptEditorState.Loaded
import de.pyryco.mobile.ui.conversations.components.SystemPromptRefusal
import de.pyryco.mobile.ui.conversations.components.refusalFor
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #1342: Channel info's System prompt section, desktop's `SystemPromptSectionView` on the phone. */
@RunWith(AndroidJUnit4::class)
class ThreadScreenSystemPromptTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var prompt by mutableStateOf<SystemPromptEditorState?>(null)
    private val events = mutableListOf<ThreadEvent>()

    private fun setContent(initial: SystemPromptEditorState?) {
        prompt = initial
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state =
                        ThreadUiState(
                            conversationId = "ch_abc123",
                            displayName = "Test channel",
                            isPromoted = true,
                            channelInfoOpen = true,
                        ),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    onOverflowEvent = { events += it },
                    systemPrompt = prompt,
                )
            }
        }
    }

    private fun inSheet(text: String): SemanticsNodeInteraction =
        composeTestRule.onNode(hasText(text) and hasAnyAncestor(isDialog()), useUnmergedTree = true)

    private fun button(label: String): SemanticsNodeInteraction =
        composeTestRule.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(isDialog())).performScrollTo()

    private fun top(text: String): Float =
        inSheet(text)
            .fetchSemanticsNode()
            .positionInRoot.y

    @Test
    fun theSectionSitsAfterMemoryAndBeforeActions() {
        setContent(Loaded("kept", SessionPromptStatus.Matches, draft = "kept"))

        val memory = top("Memory")
        val section = top("System prompt")
        val actions = top("Actions")
        assertTrue("memory=$memory section=$section actions=$actions", memory < section && section < actions)
    }

    @Test
    fun loadingUntilTheReadingArrivesThenUnavailableIfItFailed() {
        // The host passes null for the instant before the view model's editor publishes: that is still loading.
        setContent(null)
        inSheet("Reading the stored prompt from the daemon").performScrollTo()
        composeTestRule.onNodeWithTag(CHANNEL_INFO_PROMPT_FIELD_TAG).assertDoesNotExist()

        prompt = SystemPromptEditorState.Unavailable
        inSheet("Couldn't read the stored system prompt.").performScrollTo()
        composeTestRule.onNodeWithTag(CHANNEL_INFO_PROMPT_FIELD_TAG).assertDoesNotExist()
        composeTestRule.onNode(hasText("Save") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun theLoadedBoxCountsBytesAndItsControlsReachTheViewModel() {
        setContent(Loaded("äö€", SessionPromptStatus.Matches, draft = "äö€"))

        composeTestRule.onNodeWithTag(CHANNEL_INFO_PROMPT_FIELD_TAG).performScrollTo().assertTextEquals("äö€")
        inSheet("7 / 8192 bytes").performScrollTo()
        inSheet("The running session was started with a different prompt. Reset session applies the saved one.")
            .assertDoesNotExist()

        composeTestRule.onNodeWithTag(CHANNEL_INFO_PROMPT_FIELD_TAG).performTextReplacement("new")
        button("Save").assertIsEnabled().performClick()
        button("Clear").assertIsEnabled().performClick()
        assertEquals(
            listOf(ThreadEvent.SystemPromptEdit("new"), ThreadEvent.SystemPromptSave, ThreadEvent.SystemPromptClear),
            events,
        )
    }

    @Test
    fun overTheLimitSaveIsWithheldAndClearIsNot() {
        val over = "a".repeat(8193)
        setContent(Loaded(null, SessionPromptStatus.NoSession, draft = over))

        inSheet("8193 / 8192 bytes").performScrollTo()
        inSheet("Over the 8192-byte limit. Shorten it before saving.").performScrollTo()
        button("Save").assertIsNotEnabled()
        button("Clear").assertIsEnabled()
    }

    @Test
    fun theDiffersLineShowsOnlyForDiffers() {
        setContent(Loaded("kept", SessionPromptStatus.Differs, draft = "kept"))
        inSheet("The running session was started with a different prompt. Reset session applies the saved one.")
            .performScrollTo()
    }

    @Test
    fun theWriteLineFollowsTheLastWrite() {
        val base = Loaded("kept", SessionPromptStatus.Matches, draft = "kept")
        setContent(base.copy(saving = true))
        inSheet("Saving").performScrollTo()
        button("Save").assertIsNotEnabled()
        button("Clear").assertIsNotEnabled()

        prompt = base.copy(saved = true)
        inSheet("Saved. A running session keeps the prompt it started with until Reset session.").performScrollTo()

        val refusals =
            mapOf(
                SystemPromptRefusal.Malformed to "Not saved: the daemon refused the request.",
                SystemPromptRefusal.NotFound to "Not saved: the daemon has no record of this channel.",
                SystemPromptRefusal.Unclassified to "Not saved: the daemon refused the write.",
            )
        for ((refusal, line) in refusals) {
            prompt = base.copy(saveFailed = true, refusal = refusal)
            inSheet(line).performScrollTo()
        }
    }

    @Test
    fun refusalsComeFromTheErrorCodeOnly() {
        assertEquals(SystemPromptRefusal.Malformed, refusalFor(RelayErrorException("protocol.malformed", false, "x")))
        assertEquals(SystemPromptRefusal.NotFound, refusalFor(IllegalArgumentException("Unknown conversation")))
        assertEquals(SystemPromptRefusal.Unclassified, refusalFor(RelayErrorException("other", false, "x")))
    }
}
