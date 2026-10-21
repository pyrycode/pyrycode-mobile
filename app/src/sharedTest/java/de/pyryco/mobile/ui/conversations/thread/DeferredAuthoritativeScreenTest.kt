package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeferredAuthoritativeScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nestedMovedAgentContentsRemainDeferredBehindClosedOuterRun() {
        val conversions = mutableListOf<Long>()
        val presentation =
            projectDeferredAuthoritativeThread("host", "conversation", deferredNestedSnapshot(), onFullConversion = {
                conversions +=
                    it
            })
        compose.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = ThreadUiState("conversation", "Channel", authoritativeThread = presentation),
                    onBack = {},
                    onSendMessage = {},
                    onRetry = {},
                    connectionState = ConnectionState.Connected,
                    collapseToolUses = true,
                )
            }
        }
        compose.onAllNodesWithText("agent-3").assertCountEquals(0)
        assertTrue(conversions.isEmpty())

        fun toggle(
            text: String,
            substring: Boolean = false,
        ) {
            compose.onNodeWithText(text, substring = substring).performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitForIdle()
        }
        toggle("Using tools: 2")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Using tools: 1"))
        // The lazy viewport extends behind the composer; invoke the already composed header's action.
        toggle("Using tools: 1")
        assertEquals(listOf(6L), conversions.distinct())
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("text-6"))
        compose.onNodeWithText("text-6").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("inner-five", substring = true))
        toggle("inner-five", substring = true)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("inner-output"))
        compose.onNodeWithText("inner-output").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Using tools: 2"))
        toggle("Using tools: 2")
        conversions.clear()
        compose.onAllNodesWithText("inner-output").assertCountEquals(0)
        compose.onAllNodesWithText("agent-3").assertCountEquals(0)
        assertTrue(conversions.isEmpty())
        toggle("Using tools: 2")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("inner-output"))
        compose.onNodeWithText("inner-output").assertIsDisplayed()
    }

    @Test fun held8333ItemsOnlyConvertAndComposeDemandedContentsAndRetireCollidingOwnership() {
        var snapshot by mutableStateOf(deferredLargeSnapshot())
        var host by mutableStateOf("host")
        val conversions = mutableListOf<Long>()
        val composed = mutableSetOf<String>()
        lateinit var presentation: AuthoritativeThreadPresentation
        val observer: (String, Boolean) -> Unit = { key, mounted -> if (mounted) composed += key else composed -= key }
        compose.setContent {
            presentation =
                remember(snapshot, host) {
                    projectDeferredAuthoritativeThread(host, "conversation", snapshot, onFullConversion = { conversions += it })
                }
            PyrycodeMobileTheme {
                CompositionLocalProvider(LocalAuthoritativeRowCompositionObserver provides observer) {
                    ThreadScreen(
                        state = ThreadUiState("conversation", "Channel", authoritativeThread = presentation),
                        onBack = {},
                        onSendMessage = {},
                        onRetry = {},
                        connectionState = ConnectionState.Connected,
                        collapseToolUses = true,
                    )
                }
            }
        }

        fun reveal(key: String) {
            val list = compose.onNode(hasScrollToIndexAction())
            val index = list.fetchSemanticsNode().config[SemanticsProperties.IndexForKey](key)
            assertTrue(index >= 0)
            list.performScrollToIndex(index)
        }
        compose.waitForIdle()
        assertTrue(conversions.isEmpty())
        assertEquals(3, composed.size)
        compose.onNode(hasText("agent-1") and hasText("Agent")).performClick()
        compose.waitForIdle()
        assertEquals(listOf(1L), conversions.distinct()) // child run remains closed
        compose.onNodeWithText("Using tools: 1").performClick()
        compose.waitForIdle()
        assertTrue(3L in conversions)
        assertTrue(2L !in conversions) // the nested Read body is still closed
        reveal("msg:${presentation.scope}:2")
        compose.onNodeWithText("closed-file", substring = true).performClick()
        compose.onNodeWithText("hidden-output").assertIsDisplayed()
        assertTrue(2L in conversions)
        reveal("tool-run:${presentation.scope}:2")
        compose.onNodeWithText("Using tools: 1").performClick()
        compose.onNode(hasText("agent-1") and hasText("Agent")).performClick()
        compose.waitForIdle()
        assertEquals(3, composed.size)
        conversions.clear()
        compose.runOnIdle {
            snapshot =
                snapshot.copy(
                    items =
                        snapshot.items.map {
                            if (it.id ==
                                1L
                            ) {
                                authoritativeAgent(1, active = false, endedOrder = 9001)
                            } else {
                                it
                            }
                        },
                )
        }
        compose.onAllNodesWithText("hidden-output").assertCountEquals(0)
        assertTrue(conversions.isEmpty())
        compose.onNodeWithText("Agent finished", substring = true).performClick()
        compose.waitForIdle()
        assertEquals(3, composed.size)
        compose.runOnIdle {
            host = "replacement"
            snapshot =
                snapshot.copy(
                    items =
                        snapshot.items.map { item ->
                            if (item.id == 2L) {
                                authoritativeItem(
                                    2,
                                    "tool_call",
                                    parent = 1,
                                    rev = 9002,
                                    content =
                                        """
                                        {"name":"Read","input":{"file_path":"replacement-file"},
                                        "result":{"result_summary":"replacement-output"}}
                                        """.trimIndent(),
                                )
                            } else {
                                item
                            }
                        },
                )
        }
        compose.waitForIdle()
        assertEquals(3, composed.size)
        assertTrue(conversions.isEmpty())
        compose.onAllNodesWithText("hidden-output").assertCountEquals(0)
        compose.onNodeWithText("Using tools: 1").performClick()
        reveal("msg:${presentation.scope}:2")
        compose.onNodeWithText("replacement-file", substring = true).performClick()
        compose.onNodeWithText("replacement-output").assertIsDisplayed()
        compose.onAllNodesWithText("hidden-output").assertCountEquals(0)
    }
}
