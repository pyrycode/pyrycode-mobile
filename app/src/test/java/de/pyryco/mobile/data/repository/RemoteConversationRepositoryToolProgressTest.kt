package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The live lane of `tool_progress` (#812): decoded in [RemoteConversationRepository.onInbound] behind the
 * `interactive` gate and folded onto the open tool row through the same `withToolProgress` the history
 * replay runs. The fold's value semantics are pinned in `HistoryPageReducerTest`; this file pins the
 * wiring — routing by conversation, the gate, and the malformed-frame drop.
 */
class RemoteConversationRepositoryToolProgressTest {
    @Test
    fun repeatedProgress_leavesOneRunningRowWithTheLatestReading() =
        runTest {
            val (pump, repo) = repo()
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(toolUse("c1", "tu1"))
            pump.push(progress("c1", "tu1", 30))
            pump.push(progress("c1", "tu1", -4))
            pump.push(progress("c1", "tu1", 60))
            runCurrent()

            assertEquals(listOf("tu1"), messageIds(emissions.last()))
            assertEquals(ToolCallStatus.Running, toolCallOf(emissions.last(), "tu1")?.status)
            assertEquals(60, toolCallOf(emissions.last(), "tu1")?.elapsedSeconds)
        }

    @Test
    fun progressAfterResult_keepsTheRowClosedWithItsOutcome() =
        runTest {
            val (pump, repo) = repo()
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(toolUse("c1", "tu1"))
            pump.push(progress("c1", "tu1", 30))
            pump.push(toolResult("c1", "tu1", isError = true, summary = "exit 1"))
            runCurrent()
            pump.push(progress("c1", "tu1", 90))
            runCurrent()

            val toolCall = toolCallOf(emissions.last(), "tu1")
            assertEquals(ToolCallStatus.Failed, toolCall?.status)
            assertEquals("exit 1", toolCall?.output)
            assertEquals(null, toolCall?.elapsedSeconds)
            assertEquals(listOf("tu1"), messageIds(emissions.last()))
        }

    @Test
    fun progressForAnotherConversationOrAnUnknownRow_changesNothing() =
        runTest {
            val (pump, repo) = repo()
            val first = collectMessages(repo, "c1")
            val second = collectMessages(repo, "c2")
            runCurrent()

            pump.push(toolUse("c1", "tu1"))
            pump.push(toolUse("c2", "tu1"))
            pump.push(progress("c2", "tu1", 45))
            pump.push(progress("c1", "tuX", 12))
            pump.push(progress("c3", "tu1", 7))
            runCurrent()

            assertEquals(listOf("tu1"), messageIds(first.last()))
            assertEquals(null, toolCallOf(first.last(), "tu1")?.elapsedSeconds)
            assertEquals(45, toolCallOf(second.last(), "tu1")?.elapsedSeconds)
        }

    @Test
    fun malformedProgress_isDroppedAndTheCollectorSurvives() =
        runTest {
            val (pump, repo) = repo()
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(toolUse("c1", "tu1"))
            pump.push(envelope("tool_progress", """{"conversation_id":"c1","turn_id":"t1","tool_use_id":"tu1"}"""))
            runCurrent()
            assertEquals(null, toolCallOf(emissions.last(), "tu1")?.elapsedSeconds)

            pump.push(toolResult("c1", "tu1", isError = false, summary = "ok"))
            runCurrent()
            assertEquals(ToolCallStatus.Done, toolCallOf(emissions.last(), "tu1")?.status)
        }

    @Test
    fun capabilityGateClosed_progressIsIgnored() =
        runTest {
            val pump = FakeSessionPump()
            var capabilities = setOf(CAPABILITY_INTERACTIVE)
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { capabilities })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(toolUse("c1", "tu1"))
            runCurrent()
            capabilities = emptySet()
            pump.push(progress("c1", "tu1", 30))
            runCurrent()

            assertEquals(null, toolCallOf(emissions.last(), "tu1")?.elapsedSeconds)
        }

    // ---- Fixtures ---------------------------------------------------------------------------------

    private fun TestScope.repo(): Pair<FakeSessionPump, RemoteConversationRepository> {
        val pump = FakeSessionPump()
        return pump to RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE) })
    }

    private fun TestScope.collectMessages(
        repo: RemoteConversationRepository,
        conversationId: String,
    ): MutableList<List<ThreadItem>> {
        val emissions = mutableListOf<List<ThreadItem>>()
        backgroundScope.launch { repo.observeMessages(conversationId).collect { emissions += it } }
        return emissions
    }

    private fun messageIds(thread: List<ThreadItem>): List<String> = thread.map { (it as ThreadItem.MessageItem).message.id }

    private fun toolCallOf(
        thread: List<ThreadItem>,
        id: String,
    ): ToolCall? =
        thread
            .filterIsInstance<ThreadItem.MessageItem>()
            .firstOrNull { it.message.id == id && it.message.role == Role.Tool }
            ?.message
            ?.toolCall

    private fun toolUse(
        conversationId: String,
        toolUseId: String,
    ): Envelope =
        envelope(
            "tool_use",
            """{"conversation_id":"$conversationId","turn_id":"t1","tool_use_id":"$toolUseId","name":"Bash","input_summary":"sleep 90"}""",
        )

    private fun toolResult(
        conversationId: String,
        toolUseId: String,
        isError: Boolean,
        summary: String,
    ): Envelope =
        envelope(
            "tool_result",
            """{"conversation_id":"$conversationId","turn_id":"t1","tool_use_id":"$toolUseId","is_error":$isError,"result_summary":"$summary"}""",
        )

    private fun progress(
        conversationId: String,
        toolUseId: String,
        elapsedSeconds: Int,
    ): Envelope =
        envelope(
            "tool_progress",
            """{"conversation_id":"$conversationId","turn_id":"t1","tool_use_id":"$toolUseId","elapsed_seconds":$elapsedSeconds}""",
        )

    private fun envelope(
        type: String,
        payload: String,
    ): Envelope = Envelope(id = 1L, type = type, ts = TS, payload = MobileJson.parseToJsonElement(payload))

    /** Channel-backed fake of the inbound surface: unlimited buffer so pushes pre-subscription survive. */
    private class FakeSessionPump : SessionPump {
        private val inboundChannel = Channel<Envelope>(Channel.UNLIMITED)

        override val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

        override fun send(envelope: Envelope): Boolean = true

        fun push(envelope: Envelope) {
            inboundChannel.trySend(envelope)
        }
    }

    private companion object {
        const val TS = "2026-09-23T10:00:00Z"
    }
}
