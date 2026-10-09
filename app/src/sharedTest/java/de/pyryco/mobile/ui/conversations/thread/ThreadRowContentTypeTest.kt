package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreadRowContentTypeTest {
    @get:Rule val compose = createComposeRule()

    private val ts = Instant.parse("2026-10-08T10:00:00Z")

    private fun message(
        id: String,
        role: Role,
    ): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(
                id = id,
                sessionId = "s1",
                role = role,
                content = if (role == Role.Tool) "" else id,
                timestamp = ts,
                isStreaming = false,
                toolCall = if (role == Role.Tool) ToolCall(toolName = "Read", input = "", output = "") else null,
            ),
        )

    @Test
    fun foldedRows_exposeDistinctKindsAndSharedMessageTypeInActualListLayout() {
        var list: LazyListState? = null
        val observer: (LazyListState) -> Unit = { list = it }
        compose.setContent {
            CompositionLocalProvider(LocalThreadListCompositionObserver provides observer) {
                PyrycodeMobileTheme {
                    ThreadScreen(
                        state =
                            ThreadUiState(
                                conversationId = "c1",
                                displayName = "Content types",
                                hasMessages = true,
                                items =
                                    listOf(
                                        message("single", Role.Tool),
                                        message("first", Role.User),
                                        message("run-first", Role.Tool),
                                        message("run-second", Role.Tool),
                                        message("second", Role.Assistant),
                                    ),
                                queuedMessages = listOf(QueuedMessage(7L, "Waiting", ts)),
                            ),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        collapseToolUses = true,
                    )
                }
            }
        }

        val keys = listOf("queued-row:7:0", "msg:second", "msg:run-first", "msg:first", "msg:single")
        val types = mutableMapOf<String, Any?>()
        compose.runOnIdle { assertEquals(keys.size, requireNotNull(list).layoutInfo.totalItemsCount) }
        keys.forEachIndexed { index, key ->
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
            compose.runOnIdle {
                val item = requireNotNull(list).layoutInfo.visibleItemsInfo.first { it.key == key }
                assertNotNull("$key has a content type", item.contentType)
                types[key] = item.contentType
            }
        }
        assertEquals(types["msg:first"], types["msg:second"])
        assertEquals(4, listOf("msg:first", "msg:single", "msg:run-first", "queued-row:7:0").map { types[it] }.toSet().size)
    }
}
