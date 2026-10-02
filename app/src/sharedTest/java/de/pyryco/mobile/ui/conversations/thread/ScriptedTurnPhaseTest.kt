package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rung 2 (#1313): a chat whose turn is already running when it is opened shows that turn at once, read
 * from the repository's held turn phase through the real fold on the [ScriptedThreadHarness].
 */
@RunWith(AndroidJUnit4::class)
class ScriptedTurnPhaseTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: ScriptedThreadHarness

    private val working = string(R.string.cd_thread_working)
    private val thinking = string(R.string.cd_thread_thinking)
    private val stop = string(R.string.cd_thread_interrupt)

    @Before
    fun setUp() {
        harness = ScriptedThreadHarness(composeRule, conversationId = CHAT_A)
        harness.start()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    @Test
    fun aTurnRunningInAnotherChat_showsAtOnceWhenThatChatOpens() {
        harness.pushTurnState("thinking", targetConversationId = CHAT_B)
        harness.pushTurnState("responding", targetConversationId = CHAT_B)
        composeRule.waitForIdle()
        // Chat A is open: B's turn shows nothing here.
        listOf(working, thinking, stop).forEach { composeRule.onNodeWithContentDescription(it).assertDoesNotExist() }

        harness.openConversation(CHAT_B, name = "Chat B")

        // No further frame: the held `responding` alone shows the status reading and the stop affordance.
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) { present(working) && present(stop) }
        composeRule.onNodeWithContentDescription(thinking).assertDoesNotExist()
    }

    private fun present(description: String): Boolean =
        composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun string(id: Int): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(id)

    private companion object {
        const val CHAT_A = "chat-a"
        const val CHAT_B = "chat-b"
        const val TIMEOUT_MS = 5_000L
    }
}
