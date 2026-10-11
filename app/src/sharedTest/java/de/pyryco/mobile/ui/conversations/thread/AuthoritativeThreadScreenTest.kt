package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
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
import de.pyryco.mobile.data.repository.ThreadItem
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
    private lateinit var view: View
    private var frameKey: Any? = null
    private val frameTops = mutableListOf<Int?>()

    private fun mount(
        vararg items: ThreadItemDto,
        stopSupported: Boolean = true,
        busy: Boolean = false,
        thinking: Boolean = false,
        callback: (ThreadEvent) -> Unit = {},
    ) {
        snapshot = authoritativeSnapshot(*items)
        compose.setContent {
            view = LocalView.current
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
                        modifier =
                            Modifier.drawWithContent {
                                drawContent()
                                frameKey?.let { key ->
                                    val info = listState.layoutInfo
                                    frameTops +=
                                        info.visibleItemsInfo.firstOrNull { it.key == key }?.let {
                                            info.viewportSize.height - info.beforeContentPadding - it.offset - it.size
                                        }
                                }
                            },
                        isBusy = busy,
                        isThinking = thinking,
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        onAttachmentShown = { shownAttachments += it },
                        onOverflowEvent = {
                            callback(it)
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
                    .item as ThreadItem.MessageItem
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
        assertEquals(listOf("task-4"), events.filterIsInstance<ThreadEvent.BackgroundTaskStop>().map { it.taskId })
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
        assertTrue(events.none { it is ThreadEvent.BackgroundTaskStop || it is ThreadEvent.BackgroundTaskToggle })
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

    @Test fun recordedStopReachesOwningRepositoryWithoutLegacyRosterOrExpansion() {
        val stops = mutableListOf<Pair<String, String>>()
        val vm =
            ThreadViewModel(
                androidx.lifecycle.SavedStateHandle(mapOf("serverId" to "host", "conversationId" to "conversation")),
                de.pyryco.mobile.data.repository
                    .FakeConversationRepository(),
                de.pyryco.mobile.data.repository
                    .FakeConnectionStateSource(),
                ComposerDraftStore(),
                backgroundTaskStopSupported = kotlinx.coroutines.flow.flowOf(true),
                stopBackgroundTask = { conversation, task ->
                    stops += conversation to task
                    Result.success(Unit)
                },
            )
        mount(authoritativeAgent(1, task = "recorded-task"), callback = vm::onOverflowEvent)
        compose.onNodeWithText("1 task running").performClick()
        compose.onNode(hasText("agent-1") and hasText("Running") and hasClickAction()).performClick()
        compose.onNodeWithText("Stop task").performClick()
        compose.waitForIdle()
        assertEquals(listOf("conversation" to "recorded-task"), stops)
    }

    private fun taskActionOwner(stops: MutableList<Pair<String, String>>) =
        ThreadViewModel(
            androidx.lifecycle.SavedStateHandle(mapOf("serverId" to "host", "conversationId" to "conversation")),
            de.pyryco.mobile.data.repository
                .FakeConversationRepository(),
            de.pyryco.mobile.data.repository
                .FakeConnectionStateSource(),
            ComposerDraftStore(),
            backgroundTaskStopSupported = kotlinx.coroutines.flow.flowOf(true),
            stopBackgroundTask = { conversation, task ->
                stops += conversation to task
                Result.success(Unit)
            },
        )

    private fun mountActionPresentation(
        presentation: () -> AuthoritativeThreadPresentation?,
        receiver: () -> ((ThreadEvent) -> Unit),
    ) {
        compose.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                ThreadScreen(
                    state =
                        ThreadUiState(
                            "conversation",
                            "Channel",
                            authoritativeThread = presentation(),
                            backgroundTaskStopSupported = true,
                        ),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    onOverflowEvent = receiver(),
                )
            }
        }
    }

    @Test fun retainedPresentationRebindsRecordedStopToNewReceiverAndPreservesPendingAttempts() {
        val presentation =
            projectAuthoritativeThread("host", "conversation", authoritativeSnapshot(authoritativeAgent(1, task = "recorded-task")))
        val stopsA = mutableListOf<Pair<String, String>>()
        val stopsB = mutableListOf<Pair<String, String>>()
        val ownerA = taskActionOwner(stopsA)
        val ownerB = taskActionOwner(stopsB)
        var receiver by mutableStateOf<(ThreadEvent) -> Unit>(ownerA::onOverflowEvent)
        mountActionPresentation({ presentation }, { receiver })
        compose.runOnIdle { receiver = ownerB::onOverflowEvent }
        compose.onNodeWithText("1 task running").performClick()
        compose.onNode(hasText("agent-1") and hasText("Running") and hasClickAction()).performClick()
        compose.onNodeWithText("Stop task").performClick()
        compose.waitForIdle()
        assertEquals(listOf("conversation" to "recorded-task"), stopsB)
        assertTrue(stopsA.isEmpty())

        // A fresh callback to the same owner republishes facts without retiring its pending send.
        compose.runOnIdle { receiver = { ownerB.onOverflowEvent(it) } }
        compose.onNodeWithText("Stop task").performClick()
        compose.waitForIdle()
        assertEquals(listOf("conversation" to "recorded-task"), stopsB)
        assertTrue(stopsA.isEmpty())
    }

    @Test fun removingAuthoritativeRouteClearsFactsOnReboundReceiver() {
        val presentation =
            projectAuthoritativeThread("host", "conversation", authoritativeSnapshot(authoritativeAgent(1, task = "recorded-task")))
        var supplied by mutableStateOf<AuthoritativeThreadPresentation?>(presentation)
        val stopsA = mutableListOf<Pair<String, String>>()
        val stopsB = mutableListOf<Pair<String, String>>()
        val ownerA = taskActionOwner(stopsA)
        val ownerB = taskActionOwner(stopsB)
        var receiver by mutableStateOf<(ThreadEvent) -> Unit>(ownerA::onOverflowEvent)
        mountActionPresentation({ supplied }, { receiver })
        compose.runOnIdle { receiver = ownerB::onOverflowEvent }
        val stop =
            ThreadEvent.BackgroundTaskStop(
                "recorded-task",
                presentation.scope,
                presentation.tasks.tasks
                    .single()
                    .toolCallId,
            )
        compose.runOnIdle { supplied = null }
        compose.runOnIdle { ownerB.onOverflowEvent(stop) }
        compose.waitForIdle()
        assertTrue(stopsB.isEmpty())

        compose.runOnIdle { supplied = presentation }
        compose.runOnIdle { ownerB.onOverflowEvent(stop) }
        compose.waitForIdle()
        assertEquals(listOf("conversation" to "recorded-task"), stopsB)
        assertTrue(stopsA.isEmpty())
    }

    @Test fun mainTurnReadStatusIsIndependentOfPinnedBackgroundPlacement() {
        mount(
            authoritativeAgent(1),
            authoritativeItem(
                2,
                "tool_call",
                active = true,
                status = "running",
                content = """{"name":"Read","input_summary":"main file"}""",
            ),
            busy = true,
        )
        compose.onNode(hasText("Read", substring = true) and hasAnyAncestor(hasTestTag("thread-composer"))).assertIsDisplayed()
        compose.onAllNodesWithText("Agent", substring = false).assertCountEquals(1)
    }

    @Test fun mainTurnThinkingStatusIsNotReplacedByBackgroundAgent() {
        mount(authoritativeAgent(1), busy = true, thinking = true)
        compose.onNode(hasText("Thinking", substring = true) and hasAnyAncestor(hasTestTag("thread-composer"))).assertIsDisplayed()
    }

    @Test fun hiddenRootSettlementPreservesReaderInItsChildThroughRenderedFrames() = subtreeSettlement(hidden = true)

    @Test fun outerSettlementPreservesReaderInNestedForegroundChildThroughRenderedFrames() = subtreeSettlement(hidden = false)

    private fun subtreeSettlement(hidden: Boolean) {
        val history = (10L..45L).map { authoritativeItem(it) }
        val root = authoritativeAgent(1, shown = !hidden)
        val subtree =
            if (hidden) {
                arrayOf(authoritativeItem(51, parent = 1, order = 3))
            } else {
                arrayOf(
                    authoritativeAgent(50, parent = 1, task = null, active = false, order = 2),
                    authoritativeItem(51, parent = 50, order = 3),
                )
            }
        val remaining =
            arrayOf(
                authoritativeAgent(60),
                authoritativeItem(
                    61,
                    parent = 60,
                    content = """{"text":"remaining\nremaining\nremaining\nremaining\nremaining\nremaining\nremaining\nremaining"}""",
                ),
            )
        mount(root, *history.toTypedArray(), *subtree, *remaining)
        val p = projectAuthoritativeThread("host", "conversation", snapshot)
        val childIndex =
            p.rows.asReversed().indexOfFirst { row ->
                ((row as? ThreadRow.Delivered)?.item as? ThreadItem.MessageItem)?.message?.content == "text-51"
            }
        list().performScrollToIndex(childIndex)
        compose.runOnIdle { listState.dispatchRawDelta(12f) }
        compose.waitForIdle()
        val childKey = p.rows.asReversed()[childIndex].listKey(0)
        val key =
            p.rows
                .filterIsInstance<ThreadRow.Delivered>()
                .first {
                    (it.item as? ThreadItem.MessageItem)?.message?.content == "text-45"
                }.listKey(0)
        val info = listState.layoutInfo
        val first = info.visibleItemsInfo.first { it.index == listState.firstVisibleItemIndex }
        assertEquals("reader is in departing child", childKey, first.key)
        val anchor = info.visibleItemsInfo.first { it.key == key }
        val top = info.viewportSize.height - info.beforeContentPadding - anchor.offset - anchor.size
        frameKey = key
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            snapshot =
                authoritativeSnapshot(
                    authoritativeAgent(1, shown = !hidden, active = false, endedOrder = 20),
                    *history.toTypedArray(),
                    *subtree,
                    *remaining,
                )
        }
        repeat(8) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnUiThread {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                bitmap.recycle()
            }
        }
        assertTrue("must sample rendered frames", frameTops.isNotEmpty())
        frameTops.forEach { assertEquals("stationary history remains at its old position", top, it) }
        assertTrue("reader does not follow settled subtree into history", listState.layoutInfo.visibleItemsInfo.none { it.key == childKey })
    }

    @Test fun terminalBlockAlreadyAtItsRecordedEndDoesNotRelocateItsReader() {
        val history = (10L..45L).map { authoritativeItem(it) }
        val prose =
            authoritativeItem(
                51,
                parent = 1,
                content =
                    kotlinx.serialization.json
                        .JsonObject(
                            mapOf("text" to kotlinx.serialization.json.JsonPrimitive("tall child\n\n".repeat(80))),
                        ).toString(),
            )
        mount(authoritativeAgent(1), *history.toTypedArray(), prose)
        compose.runOnIdle { listState.dispatchRawDelta(200f) }
        compose.waitForIdle()
        val before = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.key }
        assertEquals(1, visible.size)
        compose.runOnIdle {
            snapshot =
                authoritativeSnapshot(
                    authoritativeAgent(1, active = false, endedOrder = 60),
                    *history.toTypedArray(),
                    prose,
                )
        }
        assertEquals(visible, listState.layoutInfo.visibleItemsInfo.map { it.key })
        assertEquals(before, listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset)
    }
}
