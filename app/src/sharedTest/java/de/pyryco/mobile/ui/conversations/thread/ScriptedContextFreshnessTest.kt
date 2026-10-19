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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
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

        context(2_000, sent.last { it.type == "request_context_usage" }.id)
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(contextMatcher("1% used")).fetchSemanticsNodes().isNotEmpty() }
        contextReading("1% used").assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()
        awaitFooter("Context usage 1%")
    }

    @Test
    fun legacyTurns_refreshBothSurfaces_afterResetReply() = legacyTurns(resetUnavailable = false)

    @Test
    fun legacyTurns_refreshBothSurfaces_afterUnavailableResetReply() = legacyTurns(resetUnavailable = true)

    private fun legacyTurns(resetUnavailable: Boolean) {
        context(150_000)
        awaitFooter("Context usage warning, 75%")
        val openingAsk = sent.last { it.type == "request_context_usage" }.id
        harness.pushResetting(true, "wrapping_up", "pending")
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription(
                InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.thread_resetting_wrapping_up),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        harness.pushSessionTransition("s1", "s2", "clear")
        awaitFooter("Context usage unavailable")
        composeRule.waitUntil(5_000) { sent.last { it.type == "request_context_usage" }.id != openingAsk }
        val resetAsk = sent.last { it.type == "request_context_usage" }
        if (resetUnavailable) {
            push("error", """{"code":"context_usage.unavailable","message":"Unavailable","retryable":true}""", resetAsk.id)
        } else {
            context(2_000, resetAsk.id)
            awaitFooter("Context usage 1%")
        }
        push(
            "session_settings",
            """{"session_id":"s2","model":"sonnet","effort":"high","permission_mode":"default","yolo":false,"used_tokens":180000,"window_tokens":200000}""",
            sent.last { it.type == "request_session_settings" }.id,
        )
        awaitFooter(if (resetUnavailable) "Context usage high, 90%" else "Context usage 1%")

        for ((turn, tokens) in listOf(1 to 40_000L, 2 to 60_000L)) {
            harness.pushTurnState("thinking")
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithContentDescription(
                    InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_thread_thinking),
                ).fetchSemanticsNodes().isNotEmpty()
            }
            harness.pushAssistantDelta("replacement-$turn", 1, "Current session reply $turn")
            harness.pushTurnEnd("replacement-$turn")
            harness.pushTurnState("idle")
            val asks = sent.count { it.type == "request_context_usage" }
            context(tokens) // Actual legacy post-turn shape: no producer, correlation or as_of.
            context(150_000) // Delayed predecessor push while verification is pending.
            composeRule.waitUntil(5_000) { sent.count { it.type == "request_context_usage" } > asks }
            composeRule.waitForIdle()
            assertEquals("signals coalesce behind one current read", asks + 1, sent.count { it.type == "request_context_usage" })
            val previousTokens =
                when {
                    turn == 2 -> 40_000L
                    resetUnavailable -> null
                    else -> 2_000L
                }
            assertEquals(previousTokens, runBlocking { harness.observeContextUsage().first()?.totalTokens })
            context(tokens, sent.last { it.type == "request_context_usage" }.id)
            val percent = (tokens / 2_000).toInt()
            awaitFooter("Context usage $percent%")
            assertEquals(asks + 1, sent.count { it.type == "request_context_usage" })
            assertEquals(tokens, runBlocking { harness.observeContextUsage().first()?.totalTokens })
            openConfiguration()
            contextReading("$percent% used").assertIsDisplayed()
            composeRule.onAllNodesWithText("90% used").assertCountEquals(0)
            composeRule.onAllNodesWithText("75% used").assertCountEquals(0)
            composeRule.onNodeWithText("Done").performClick()
        }
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

    private fun context(
        used: Long,
        replyTo: Long? = null,
    ) = push(
        "context_usage",
        """{"conversation_id":"c1","total_tokens":$used,"max_tokens":200000,"percentage":99,"categories":[],"dropped_categories":0,"mcp_tools":[],"dropped_mcp_tools":0,"memory_files":[],"dropped_memory_files":0}""",
        replyTo,
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
