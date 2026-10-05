package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext

class VerifierSnapshotRaceTest {
    private class SteppedDispatcher : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            tasks.addLast(block)
        }

        fun step() {
            tasks.removeFirst().run()
        }

        fun drain() {
            var remaining = 200
            while (tasks.isNotEmpty()) {
                check(remaining-- > 0) { "dispatcher did not quiesce" }
                step()
            }
        }
    }

    @Test
    fun reopenDuringDelivery_neverEmitsUnsuppressedTapTimeRows() {
        val projection = ThreadProjection()
        val queue = QueueProjection()
        val at = Instant.parse("2026-10-04T00:00:00Z")
        val own = Message(id = "mine", sessionId = "s1", role = Role.User, content = "marker", timestamp = at, isStreaming = false)
        projection.applyAssistantDelta(LiveSessionEvent.AssistantDelta("c1", "turn-1", 0, "Waiting"))
        projection.recordMinted("c1", "mine")
        projection.appendMessages(listOf("c1" to own))

        fun backlog(items: String) {
            queue.apply(
                Envelope(
                    id = 1L,
                    type = "queue_state",
                    ts = at.toString(),
                    payload = MobileJson.parseToJsonElement("""{"conversation_id":"c1","queued":[$items]}"""),
                ),
            )
            projection.settleQueuedEchoes(queue) { true }
        }
        backlog("""{"queued_msg_id":42,"message_id":"mine","text":"marker","ts":"$at"}""")
        projection.applyToolUse(LiveSessionEvent.ToolUse("c1", "turn-1", "tool-1", name = "Bash", inputSummary = "hold"))
        projection.recordSendNow("c1", "mine")
        backlog("")
        projection.applyToolUse(LiveSessionEvent.ToolUse("c1", "turn-1", "tool-2", name = "Bash", inputSummary = "intervening"))

        val dispatcher = SteppedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val readings = mutableListOf<ThreadSnapshot>()
        try {
            scope.launch { projection.observeSnapshot("c1").collect { readings += it } }
            dispatcher.step() // Start the reopen subscription.
            // The old combine launches independent source collectors; a single source needs no extra step.
            if (dispatcher.tasks.isNotEmpty()) dispatcher.step()
            // The real inbound collector can run on Default while the reopen collection is on Main.
            projection.appendLiveMessage("c1", own)
            dispatcher.drain() // Finish the subscription and its pending delivery emission.
            val ids = readings.map { s -> s.rows.filterIsInstance<ThreadItem.MessageItem>().map { it.message.id } }
            assertTrue("must collect at least one reading", ids.isNotEmpty())
            assertEquals(listOf("turn-1", "tool-1", "tool-2", "mine"), ids.last())
            assertTrue(
                "suppressed echoes must stay hidden",
                readings.all { snapshot ->
                    snapshot.rows.none {
                        it is ThreadItem.MessageItem &&
                            it.message.id in snapshot.suppressedUserMessageIds
                    }
                },
            )
            assertTrue("delivery exposed tap-time ordering: $ids", ids.none { "mine" in it && it.indexOf("mine") < it.indexOf("tool-2") })
        } finally {
            scope.cancel()
            dispatcher.drain()
        }
    }
}
