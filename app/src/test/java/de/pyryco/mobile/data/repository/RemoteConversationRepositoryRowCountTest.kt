package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Each conversation's thread row count (#1361): a new row raises it, growth of an existing row does not. */
class RemoteConversationRepositoryRowCountTest {
    @Test
    fun newRowsRaiseTheCountAndGrowthOfARowDoesNot() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE) })
            val counts = mutableListOf<Map<String, Int>>()
            backgroundScope.launch { repo.observeThreadRowCounts().collect { counts += it } }
            runCurrent()
            assertEquals(listOf(emptyMap<String, Int>()), counts)

            pump.push(envelope("assistant_delta", """{"conversation_id":"c1","turn_id":"t1","seq":0,"text":"hel"}"""))
            pump.push(
                envelope("tool_use", """{"conversation_id":"c2","turn_id":"t1","tool_use_id":"tu1","name":"Bash","input_summary":"ls"}"""),
            )
            runCurrent()
            assertEquals(mapOf("c1" to 1, "c2" to 1), counts.last())
            val settled = counts.size

            pump.push(envelope("assistant_delta", """{"conversation_id":"c1","turn_id":"t1","seq":1,"text":"lo"}"""))
            pump.push(
                envelope(
                    "tool_result",
                    """{"conversation_id":"c2","turn_id":"t1","tool_use_id":"tu1","is_error":false,"result_summary":"ok"}""",
                ),
            )
            runCurrent()
            assertEquals(settled, counts.size)

            pump.push(envelope("banner", """{"conversation_id":"c1","level":"warning","text":"x","truncated":false,"stops_turn":false}"""))
            runCurrent()
            assertEquals(mapOf("c1" to 2, "c2" to 1), counts.last())
        }

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
        const val TS = "2026-10-01T10:00:00Z"
    }
}
