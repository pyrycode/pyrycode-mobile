package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Both surfaces consume the real repository's replacement and reply, with new settings held. */
@RunWith(AndroidJUnit4::class)
class ScriptedContextFreshnessTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val sent = CopyOnWriteArrayList<Envelope>()
    private lateinit var harness: ScriptedThreadHarness
    private var id = 100L

    @Before
    fun setUp() {
        harness =
            ScriptedThreadHarness(composeRule, onSend = {
                sent += it
                true
            })
        harness.start()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    @Test
    fun resetReplacement_updatesBothContextSurfaces() {
        push(
            "conversations",
            """{"conversations":[{"id":"c1","name":"Harness channel","is_promoted":true,"cwd":"/p","current_session_id":"s1","last_used_at":"$TS","last_message_ts":"$TS"}]}""",
        )
        composeRule.waitUntil(5_000) { sent.any { it.type == "request_session_settings" } }
        val ask = sent.last { it.type == "request_session_settings" }
        push(
            "session_settings",
            """{"session_id":"s1","model":"sonnet","effort":"high","permission_mode":"default","yolo":false,"used_tokens":100000,"window_tokens":200000}""",
            ask.id,
        )
        context(150_000)
        awaitFooter("Context usage warning, 75%")

        harness.pushResetting(true, "wrapping_up", "pending")
        composeRule.waitUntil(5_000) {
            composeRule
                .onAllNodesWithContentDescription(
                    InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.thread_resetting_wrapping_up),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        harness.pushSessionTransition("s1", "s2", "clear")
        awaitFooter("Context usage unavailable")
        openConfiguration()
        composeRule.onAllNodesWithText("Not reported yet").assertCountEquals(2)
        composeRule.onAllNodesWithText("75% used").assertCountEquals(0)

        context(2_000)
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(contextMatcher("1% used")).fetchSemanticsNodes().isNotEmpty() }
        contextReading("1% used").assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()
        awaitFooter("Context usage 1%")
    }

    private fun openConfiguration() {
        composeRule.onNode(hasContentDescription("Expand status details")).performClick()
        composeRule.onNodeWithText("Context window").performScrollTo()
    }

    private fun contextMatcher(reading: String) = hasText(reading)

    private fun contextReading(reading: String) = composeRule.onNode(contextMatcher(reading))

    private fun awaitFooter(description: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasContentDescription(description)).assertIsDisplayed()
    }

    private fun context(used: Long) =
        push(
            "context_usage",
            """{"conversation_id":"c1","total_tokens":$used,"max_tokens":200000,"percentage":99,"categories":[],"dropped_categories":0,"mcp_tools":[],"dropped_mcp_tools":0,"memory_files":[],"dropped_memory_files":0}""",
        )

    private fun push(
        type: String,
        payload: String,
        replyTo: Long? = null,
    ) {
        harness.pushEnvelope(Envelope(id++, type, TS, MobileJson.parseToJsonElement(payload), inReplyTo = replyTo))
    }

    private companion object {
        const val TS = "2026-10-12T00:00:00Z"
    }
}
