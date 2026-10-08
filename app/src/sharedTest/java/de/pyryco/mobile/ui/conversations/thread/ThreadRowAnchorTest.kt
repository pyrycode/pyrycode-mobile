package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.TOOL_ROW_TAG
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
open class ThreadRowAnchorTest {
    @get:Rule val compose = createComposeRule()

    @Test open fun loneToolGrowth_preservesBottomAnchorAndOffset() {
        val ts = Instant.parse("2026-10-08T10:00:00Z")

        fun tool(id: String) =
            ThreadItem.MessageItem(Message(id, "s", Role.Tool, "", ts, isStreaming = false, toolCall = ToolCall("Read", "", "")))
        val history = (1..60).map { ThreadItem.MessageItem(Message("m$it", "s", Role.User, "Older message $it", ts, isStreaming = false)) }
        val queued = (1L..5L).map { QueuedMessage(it, "Queued $it", ts, "") }
        val state =
            mutableStateOf(
                ThreadUiState(
                    conversationId = "c",
                    displayName = "Anchor",
                    isPromoted = true,
                    hasMessages = true,
                    items =
                        history + tool("t1"),
                    queuedMessages = queued,
                ),
            )
        var list: LazyListState? = null
        val observer: (LazyListState) -> Unit = { list = it }
        compose.setContent {
            CompositionLocalProvider(LocalThreadListCompositionObserver provides observer) {
                PyrycodeMobileTheme {
                    ThreadScreen(state.value, {}, {}, ConnectionState.Connected, {}, collapseToolUses = true)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(queued.size)
        compose.waitForIdle()
        compose.runOnIdle { requireNotNull(list).dispatchRawDelta(13f) }
        compose.waitForIdle()
        val toolBounds = compose.onNodeWithTag(TOOL_ROW_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val composerTop = compose.onNodeWithTag("thread-composer").getUnclippedBoundsInRoot().top
        val headerBottom = compose.onNodeWithTag("thread-top-bar").getUnclippedBoundsInRoot().bottom
        assertTrue(
            "the tool is visibly between chrome, not entirely behind the composer",
            toolBounds.top < composerTop && toolBounds.bottom > headerBottom,
        )
        var offset = 0
        compose.runOnIdle {
            val current = requireNotNull(list)
            assertEquals(queued.size, current.firstVisibleItemIndex)
            // Rows composed in end padding lie behind the composer; firstVisibleItemIndex is the
            // reverse list's actual scroll anchor outside that reserved padding.
            val bottom = current.layoutInfo.visibleItemsInfo.first { it.index == current.firstVisibleItemIndex }
            assertEquals("msg:t1", bottom.key)
            assertTrue("reader is away from newest queued row", current.firstVisibleItemIndex > 0)
            offset = current.firstVisibleItemScrollOffset
            assertTrue("probe uses a nonzero physical-pixel offset", offset > 0)
            state.value = state.value.copy(items = state.value.items + tool("t2"))
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNodeWithTag("tool-run:t1").assertExists()
        compose.runOnIdle {
            val current = requireNotNull(list)
            val bottom = current.layoutInfo.visibleItemsInfo.first { it.index == current.firstVisibleItemIndex }
            assertEquals("first-tool representative remains the bottom anchor", "msg:t1", bottom.key)
            assertEquals("anchor offset in physical pixels", offset.toDouble(), current.firstVisibleItemScrollOffset.toDouble(), 1.0)
            assertEquals(queued.size, current.firstVisibleItemIndex)
        }
    }
}
