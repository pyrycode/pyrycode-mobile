package de.pyryco.mobile.design

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.CanonicalRefusalIdentity
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UnrecognizedSite
import de.pyryco.mobile.ui.conversations.thread.FixtureThread
import de.pyryco.mobile.ui.conversations.thread.projectAuthoritativeThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CaptureThreadTest {
    private val previousSink = RelayLog.sink

    @Before fun quietPortableStoreLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogging() {
        RelayLog.sink = previousSink
    }

    private val timestamp = Instant.parse("2026-10-21T10:00:00Z")

    @Test
    fun nestedToolsRetainStatusNumericParentsResultDetailAndProgress() =
        runTest {
            fun tool(
                id: String,
                name: String,
                status: ToolCallStatus,
                parent: String = "",
                detail: String? = null,
                elapsed: Int? = null,
            ) = ThreadItem.MessageItem(
                Message(
                    id,
                    "session",
                    Role.Tool,
                    "",
                    timestamp,
                    false,
                    toolCall =
                        ToolCall(
                            name,
                            "input",
                            "output",
                            status,
                            parentToolUseId = parent,
                            resultDetail = detail,
                            elapsedSeconds = elapsed,
                        ),
                ),
            )
            val route =
                CaptureThread(
                    FixtureThread("host", "c"),
                    extras =
                        MutableStateFlow(
                            listOf(
                                tool("agent", "Agent", ToolCallStatus.Running),
                                tool("read", "Read", ToolCallStatus.Failed, "agent", "12 files"),
                                tool("bash", "Bash", ToolCallStatus.Running, "agent", elapsed = 14),
                            ),
                        ),
                )
            val snapshot = route.observe("c").first()
            assertEquals(10_000L, snapshot.items.single { it.id == 10_001L }.parent)
            val presentation = projectAuthoritativeThread("host", "c", snapshot, route.progress("c", route).first())
            val messages = presentation.items.filterIsInstance<ThreadItem.MessageItem>().map { it.message }
            assertEquals(
                listOf(ToolCallStatus.Running, ToolCallStatus.Failed, ToolCallStatus.Running),
                messages.map { it.toolCall?.status },
            )
            assertEquals(messages.first().id, messages[1].toolCall?.parentToolUseId)
            assertEquals("12 files", messages[1].toolCall?.resultDetail)
            assertEquals(14, messages[2].toolCall?.elapsedSeconds)
        }

    @Test
    fun unrecognizedScenePreservesSiteTypeAndRawText() =
        runTest {
            val row = ThreadItem.UnrecognizedMessage("original", UnrecognizedSite.CodexItem, "future_item", "{raw}", false, timestamp)
            val route = CaptureThread(FixtureThread("host", "c"), extras = MutableStateFlow(listOf(row)))
            val presentation = projectAuthoritativeThread("host", "c", route.observe("c").first())
            val shown = presentation.items.single() as ThreadItem.UnrecognizedMessage
            assertEquals(row.site, shown.site)
            assertEquals(row.messageType, shown.messageType)
            assertEquals(row.raw, shown.raw)
        }

    @Test
    fun refusalSceneUsesCanonicalIdentityAndTaskFactsReachTheProductionProjection() =
        runTest {
            val row = ThreadItem.ModelRefusal("opus", "sonnet", "declined", false, timestamp)
            val route =
                CaptureThread(
                    FixtureThread("host", "c"),
                    extras = MutableStateFlow(listOf(row)),
                    taskFacts = MutableStateFlow(captureTaskCount(2)),
                )
            val presentation = projectAuthoritativeThread("host", "c", route.observe("c").first())
            assertEquals(
                CanonicalRefusalIdentity("host", "c", "capture", 10_000),
                (presentation.items.single() as ThreadItem.ModelRefusal).canonicalIdentity,
            )
            assertEquals(2, presentation.tasks.liveCount)
        }
}
