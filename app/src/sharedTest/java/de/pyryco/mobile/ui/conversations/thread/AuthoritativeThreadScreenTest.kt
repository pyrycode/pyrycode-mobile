package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuthoritativeThreadScreenTest {
    @get:Rule val compose = createComposeRule()
    private var snapshot by mutableStateOf(authoritativeSnapshot())
    private val events = mutableListOf<ThreadEvent>()
    private val shownAttachments = mutableListOf<MessageAttachment>()
    private lateinit var listState: LazyListState

    private fun mount(
        vararg items: ThreadItemDto,
        stopSupported: Boolean = true,
    ) {
        snapshot = authoritativeSnapshot(*items)
        compose.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                CompositionLocalProvider(LocalThreadListCompositionObserver provides { listState = it }) {
                    ThreadScreen(
                        state =
                            ThreadUiState(
                                "conversation",
                                "Channel",
                                authoritativeThread = projectAuthoritativeThread("host", "conversation", snapshot),
                                backgroundTaskCount = 99,
                                backgroundTaskStopSupported = stopSupported,
                            ),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        onAttachmentShown = { shownAttachments += it },
                        onOverflowEvent = {
                            events +=
                                it
                        },
                    )
                }
            }
        }
    }

    private fun list() = compose.onNode(hasScrollToIndexAction())

    private fun show(text: String) {
        list().performScrollToNode(hasText(text, substring = true))
        compose.onNodeWithText(text, substring = true).assertIsDisplayed()
    }

    private fun tool(
        id: Long = 1,
        rev: Long = id,
        parent: Long? = null,
    ) = authoritativeItem(
        id,
        "tool_call",
        parent = parent,
        rev = rev,
        content =
            """
            {
            "name":"Read","input_summary":"file-$id","input":{"file_path":"file-$id"},
            "result":{"result_summary":"expanded-output-$id"}}
            """.trimIndent(),
    )

    @Test fun suppliedRowsAppearWithoutLegacyHasMessagesAndUnknownSummaryIsPlain() {
        mount(
            authoritativeItem(1, "user_message"),
            authoritativeItem(2, kind = "future", summary = "**inert summary**"),
            authoritativeItem(3, shown = false),
        )
        show("text-1")
        show("**inert summary**")
        compose.onAllNodesWithText("text-3").assertCountEquals(0)
        compose.onAllNodesWithText("No messages yet").assertCountEquals(0)
    }

    @Test fun allKindsReachExistingComponentsAndShownNormalEndingRemainsVisible() {
        mount(
            authoritativeItem(1, "user_message"),
            authoritativeItem(2),
            tool(3),
            authoritativeAgent(4, active = false, task = null),
            authoritativeItem(
                5,
                "session_divider",
                content = """{"cause":"operator_reset","previous_session_id":"old","new_session_id":"new"}""",
            ),
            authoritativeItem(6, "compaction", content = """{"trigger":"manual"}"""),
            authoritativeItem(7, "turn_end", summary = "normal ending", content = """{"stop_reason":"end_turn"}"""),
            authoritativeItem(8, "notice", subtype = "future", summary = "notice fallback"),
        )
        show("text-1")
        show("text-2")
        show("file-3")
        show("agent-4")
        list().performScrollToNode(hasTestTag("session-boundary"))
        compose.onNodeWithTag("session-boundary").assertIsDisplayed()
        show("Conversation compacted")
        show("normal ending")
        show("notice fallback")
    }

    @Test fun suppliedMarkdownCopyAndAttachmentReachExistingMessagePresentation() {
        val source = "**Markdown reply**"
        mount(authoritativeItem(1, content = """{"text":"$source","attachment_ids":["recorded-attachment"]}"""))
        show("Markdown reply")
        compose.onNodeWithTag("message_attachment_file", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(listOf("recorded-attachment"), shownAttachments.map { it.attachmentId })
        compose.onNodeWithText("Markdown reply").performClick()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithContentDescription(context.getString(R.string.cd_thread_copy_message)).performClick()
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals(
            source,
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
    }

    @Test fun sameEpochReloadAndRevisionKeepToolExpansionButEpochChangeResetsIt() {
        mount(tool())
        compose.onNodeWithText("file-1", substring = true).performClick()
        compose.onNodeWithText("expanded-output-1").assertIsDisplayed()
        compose.runOnIdle { snapshot = authoritativeSnapshot(tool(rev = 5)) }
        compose.onNodeWithText("expanded-output-1").assertIsDisplayed()
        compose.runOnIdle { snapshot = authoritativeSnapshot(tool(rev = 5), tool(rev = 5)) }
        compose.onNodeWithText("expanded-output-1").assertIsDisplayed()
        compose.runOnIdle { snapshot = snapshot.copy(epoch = "new-epoch") }
        compose.onAllNodesWithText("expanded-output-1").assertCountEquals(0)
    }

    @Test fun completeParentsOwnToolsAndProseAndGoToAgentNavigatesBothRunningBlocks() {
        mount(authoritativeAgent(1), authoritativeAgent(2), tool(10, parent = 1), authoritativeItem(11, parent = 2))
        val p = projectAuthoritativeThread("host", "conversation", snapshot)
        val root = (
            (
                p.rows
                    .filterIsInstance<ThreadRow.Delivered>()
                    .first()
                    .item as de.pyryco.mobile.data.repository.ThreadItem.MessageItem
            ).message.id
        )
        list().performScrollToNode(hasText("agent-1") and hasText("Agent"))
        compose.onNodeWithText("file-10", substring = true).assertIsDisplayed()
        show("text-11")
        assertTrue(
            compose
                .onNodeWithText("text-11")
                .fetchSemanticsNode()
                .boundsInRoot.left > 20f,
        )
        list().performScrollToNode(hasText("agent-1") and hasText("Go to agent ↓"))
        compose.onNode(hasText("agent-1") and hasText("Go to agent ↓")).performClick()
        compose.onNodeWithTag("background-agent:$root").assertIsDisplayed()
    }

    @Test fun priorSessionTerminalAndUnknownActiveFactsAgreeWithPanelAndRecordedStopAction() {
        mount(
            authoritativeAgent(1, active = false, status = "ended_with_session", endedOrder = 3),
            authoritativeAgent(4, status = "future", session = "new-session"),
            tool(5),
            authoritativeAgent(6, task = null),
        )
        compose.onNodeWithText("1 task running").performClick()
        compose.onNodeWithText("Running · 1").assertIsDisplayed()
        compose.onNodeWithText("Finished · 1").assertIsDisplayed()
        compose.onNode(hasText("agent-4") and hasText("Running") and hasClickAction()).performClick()
        compose.onNodeWithText("Stop task").performClick()
        assertEquals(listOf(ThreadEvent.BackgroundTaskStop("task-4")), events.filterIsInstance<ThreadEvent.BackgroundTaskStop>())
        compose.onAllNodes(hasText("agent-6") and hasText("Running")).assertCountEquals(0)
        compose.onAllNodes(hasText("file-5") and hasText("Running")).assertCountEquals(0)
    }

    @Test fun taskExpansionUsesItemIdentityAcrossRevisionReplacementAndEpoch() {
        mount(authoritativeAgent(1, task = "recorded"))
        compose.onNodeWithText("1 task running").performClick()
        compose.onNode(hasText("agent-1") and hasText("Running") and hasClickAction()).performClick()
        compose.onNodeWithText("Stop task").assertIsDisplayed()
        val original = authoritativeAgent(1, task = "recorded")
        compose.runOnIdle {
            snapshot =
                authoritativeSnapshot(
                    authoritativeItem(1, "agent", active = true, status = "running", rev = 50, content = original.content.toString()),
                )
        }
        compose.onNodeWithText("Stop task").assertIsDisplayed()
        compose.runOnIdle { snapshot = authoritativeSnapshot(authoritativeAgent(2, task = "recorded")) }
        compose.onAllNodesWithText("Stop task").assertCountEquals(0)
        compose.onNode(hasText("agent-2") and hasText("Running") and hasClickAction()).performClick()
        compose.onNodeWithText("Stop task").assertIsDisplayed()
        compose.runOnIdle { snapshot = snapshot.copy(epoch = "next") }
        compose.onAllNodesWithText("Stop task").assertCountEquals(0)
    }

    @Test fun unsupportedStopCapabilityOffersNoTaskAction() {
        mount(authoritativeAgent(1), stopSupported = false)
        compose.onNodeWithText("1 task running").performClick()
        compose.onAllNodesWithText("Stop task").assertCountEquals(0)
        compose.onNode(hasText("agent-1") and hasText("Running")).assertHasNoClickAction()
        assertTrue(events.isEmpty())
    }

    @Test fun followerStaysAtNewestAcrossSettlementAndRepeatedDelivery() {
        val history = (2L..14L).map { authoritativeItem(it) }
        mount(authoritativeAgent(1), *history.toTypedArray())
        list().performScrollToIndex(0)
        compose.runOnIdle {
            snapshot =
                authoritativeSnapshot(
                    authoritativeAgent(1, active = false, endedOrder = 5),
                    *history.toTypedArray(),
                )
        }
        compose.runOnIdle {
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
        }
        compose.runOnIdle { snapshot = snapshot.copy(items = snapshot.items.reversed()) }
        compose.runOnIdle { assertEquals(0, listState.firstVisibleItemIndex) }
    }

    @Test fun readerAwayFromNewestRetainsStationaryAnchorAcrossSettlementAndReload() {
        val history = (2L..30L).map { authoritativeItem(it) }
        mount(authoritativeAgent(1), *history.toTypedArray())
        show("text-20")
        val before =
            compose
                .onNodeWithText("text-20")
                .fetchSemanticsNode()
                .boundsInRoot.top
        compose.runOnIdle {
            snapshot =
                authoritativeSnapshot(
                    authoritativeAgent(1, active = false, endedOrder = 5),
                    *history.toTypedArray(),
                )
        }
        val after =
            compose
                .onNodeWithText("text-20")
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertEquals(before, after, 1f)
        compose.runOnIdle { snapshot = snapshot.copy(items = snapshot.items.reversed()) }
        assertEquals(
            after,
            compose
                .onNodeWithText("text-20")
                .fetchSemanticsNode()
                .boundsInRoot.top,
            1f,
        )
    }
}
