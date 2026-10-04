package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenToolCallTest {
    private fun toolRow(
        id: String,
        name: String,
        status: ToolCallStatus,
        elapsedSeconds: Int? = null,
        parentToolUseId: String = "",
    ): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(
                id = id,
                sessionId = "s1",
                role = Role.Tool,
                content = "",
                timestamp = Instant.parse("2026-09-24T10:00:00Z"),
                isStreaming = false,
                toolCall =
                    ToolCall(
                        toolName = name,
                        input = "",
                        output = "",
                        status = status,
                        elapsedSeconds = elapsedSeconds,
                        parentToolUseId = parentToolUseId,
                    ),
            ),
        )

    private fun assistant(id: String): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(
                id = id,
                sessionId = "s1",
                role = Role.Assistant,
                content = "text",
                timestamp = Instant.parse("2026-09-24T10:00:00Z"),
                isStreaming = false,
            ),
        )

    @Test
    fun noToolRows_hasNoOpenCall() {
        assertNull(openToolCall(emptyList()))
        assertNull(openToolCall(listOf(assistant("a1"))))
    }

    @Test
    fun closedRows_areNotOpen() {
        val items =
            listOf(
                toolRow("t1", "Read", ToolCallStatus.Done),
                toolRow("t2", "Bash", ToolCallStatus.Failed),
                toolRow("t3", "Write", ToolCallStatus.Denied),
            )

        assertNull(openToolCall(items))
    }

    @Test
    fun aRunningRow_isTheOpenCall() {
        val items = listOf(assistant("a1"), toolRow("t1", "Bash", ToolCallStatus.Running, elapsedSeconds = 65))

        val open = openToolCall(items)

        assertEquals("Bash", open?.toolName)
        assertEquals(65, open?.elapsedSeconds)
    }

    @Test
    fun theNewerRunningRowWins_withItsOwnReading() {
        val items =
            listOf(
                toolRow("t1", "Bash", ToolCallStatus.Running, elapsedSeconds = 65),
                toolRow("t2", "Grep", ToolCallStatus.Running),
            )

        val open = openToolCall(items)

        assertEquals("Grep", open?.toolName)
        assertNull(open?.elapsedSeconds)
    }

    @Test
    fun aLaterClosedRow_doesNotHideAnEarlierRunningOne() {
        val items =
            listOf(
                toolRow("t1", "Bash", ToolCallStatus.Running, elapsedSeconds = 30),
                toolRow("t2", "Read", ToolCallStatus.Done),
            )

        assertEquals("Bash", openToolCall(items)?.toolName)
    }

    @Test
    fun aNewerSubagentCall_doesNotReplaceTheLatestMainThreadCall() {
        val items =
            listOf(
                toolRow("t1", "Bash", ToolCallStatus.Running, elapsedSeconds = 65),
                toolRow("t2", "Grep", ToolCallStatus.Running, elapsedSeconds = 30),
                toolRow("t3", "Read", ToolCallStatus.Running, elapsedSeconds = 90, parentToolUseId = "agent"),
            )

        val open = openToolCall(items)

        assertEquals("Grep", open?.toolName)
        assertEquals(30, open?.elapsedSeconds)
    }

    @Test
    fun onlySubagentCallsRunning_hasNoOpenCall_evenWhenTheParentIsNotLoaded() {
        val backgroundCall = toolRow("t1", "Bash", ToolCallStatus.Running, parentToolUseId = "absent-agent")

        assertNull(openToolCall(listOf(backgroundCall)))
        assertNull(openToolCall(listOf(toolRow("t2", "Read", ToolCallStatus.Done), backgroundCall)))
    }
}
