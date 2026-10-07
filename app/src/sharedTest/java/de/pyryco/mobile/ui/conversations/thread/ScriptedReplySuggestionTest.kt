package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScriptedReplySuggestionTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var harness: ScriptedThreadHarness

    @Before fun start() {
        harness = ScriptedThreadHarness(composeRule, conversationId = C1)
        harness.start()
        harness.pushSessionTransition("", S1, "clear")
    }

    @After fun close() = harness.close()

    @Test fun setAndClear_reachTheEmptyComposerThroughTheRealRepository() {
        harness.pushEnvelope(suggestion(1, "Try the next step"))
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithText("Try the next step").fetchSemanticsNode() }.isSuccess
        }
        harness.pushEnvelope(suggestion(2, null))
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithText("Try the next step").fetchSemanticsNode() }.isFailure
        }
        composeRule.onNodeWithText("Message").assertExists()
    }

    @Test fun otherConversationsAndSessionsStayIsolated_untilTheActiveSessionChanges() {
        harness.pushEnvelope(suggestion(1, "active"))
        awaitText("active")
        harness.pushEnvelope(suggestion(1, "other session", session = S2))
        harness.pushEnvelope(suggestion(1, "other conversation", conversation = C2))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("active").assertExists()
        composeRule.onNodeWithText("other session").assertDoesNotExist()
        composeRule.onNodeWithText("other conversation").assertDoesNotExist()
        harness.pushSessionTransition(S1, S2, "clear")
        awaitText("other session")
        composeRule.onNodeWithText("active").assertDoesNotExist()
        harness.openConversation(C2, "another thread")
        composeRule.onNodeWithText("other session").assertDoesNotExist()
        composeRule.onNodeWithText("other conversation").assertDoesNotExist()
    }

    private fun awaitText(text: String) =
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun suggestion(
        revision: Int,
        text: String?,
        session: String = S1,
        conversation: String = C1,
    ): Envelope =
        Envelope(
            id = revision.toLong(),
            ts = "2026-10-07T00:00:00Z",
            type = "reply_suggestion",
            payload =
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"$conversation","session_id":"$session","revision":$revision,"suggested_reply":${text?.let {
                        "\"$it\""
                    } ?: "null"}}""",
                ),
        )

    private companion object {
        const val C1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val C2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val S2 = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val S1 = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    }
}
