package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostSystemPromptFacadesTest {
    private val oldEnabled = RelayLog.enabled

    @Before
    fun disableLogs() {
        RelayLog.enabled = false
    }

    @After
    fun restoreLogs() {
        RelayLog.enabled = oldEnabled
    }

    @Test
    fun stableAndCachePassThroughRealRemoteResultsWithoutCaching() =
        runTest {
            val pump = Pump()
            val remote = RemoteConversationRepository(pump, backgroundScope)
            val current = MutableStateFlow<ConversationRepository?>(remote)
            val stable = StableConversationRepository(current)
            val cached = CachingConversationRepository(stable, unusedCache(), "host")
            val read = backgroundScope.async { cached.requestHostSystemPrompt() }
            runCurrent()
            assertEquals("request_host_system_prompt", pump.sent.single().type)
            pump.reply("remote", "default")
            runCurrent()
            assertEquals(HostSystemPromptReading("remote", "default"), read.await().getOrThrow())
            val save = backgroundScope.async { cached.setHostSystemPrompt("") }
            runCurrent()
            assertEquals("set_host_system_prompt", pump.sent.last().type)
            assertEquals(buildJsonObject { put("system_prompt", "") }, pump.sent.last().payload)
            pump.reply("", "default")
            runCurrent()
            assertEquals(HostSystemPromptReading("", "default"), save.await().getOrThrow())
            current.value = null
            assertTrue(cached.requestHostSystemPrompt().exceptionOrNull() is IllegalStateException)
            assertTrue(cached.setHostSystemPrompt("offline").exceptionOrNull() is IllegalStateException)
            assertEquals(2, pump.sent.size)
        }

    @Test
    fun stableNeverRedirectsAnInflightOperationWhenDelegateChanges() =
        runTest {
            val pumpA = Pump()
            val pumpB = Pump()
            val current = MutableStateFlow<ConversationRepository?>(RemoteConversationRepository(pumpA, backgroundScope))
            val stable = StableConversationRepository(current)
            val saveA = backgroundScope.async { stable.setHostSystemPrompt("A") }
            runCurrent()
            current.value = RemoteConversationRepository(pumpB, backgroundScope)
            val readB = backgroundScope.async { stable.requestHostSystemPrompt() }
            runCurrent()
            pumpB.reply("B", "default B")
            runCurrent()
            assertFalse(saveA.isCompleted)
            assertEquals(HostSystemPromptReading("B", "default B"), readB.await().getOrThrow())
            pumpA.reply("A", "default A")
            runCurrent()
            assertEquals(HostSystemPromptReading("A", "default A"), saveA.await().getOrThrow())
        }

    @Test
    fun fakeStoresHostTextSeparatelyFromChannelPromptsAndOtherHosts() =
        runTest {
            val a = FakeConversationRepository()
            val b = FakeConversationRepository()
            val initial = a.requestHostSystemPrompt().getOrThrow()
            assertEquals("", initial.systemPrompt)
            assertEquals("", initial.defaultSystemPrompt)
            for (text in listOf("", "  \n\t ", "custom", initial.defaultSystemPrompt)) {
                assertEquals(HostSystemPromptReading(text, initial.defaultSystemPrompt), a.setHostSystemPrompt(text).getOrThrow())
                assertEquals(text, a.requestHostSystemPrompt().getOrThrow().systemPrompt)
            }
            val channel = a.createChannel("test", null)
            a.setSystemPrompt(channel.id, "channel")
            a.setHostSystemPrompt("host").getOrThrow()
            assertEquals("channel", a.requestSystemPrompt(channel.id).systemPrompt)
            a.setSystemPrompt(channel.id, null)
            assertNull(a.requestSystemPrompt(channel.id).systemPrompt)
            assertEquals("host", a.requestHostSystemPrompt().getOrThrow().systemPrompt)
            a.setSystemPrompt(channel.id, "")
            assertEquals("", a.requestSystemPrompt(channel.id).systemPrompt)
            assertEquals(initial, b.requestHostSystemPrompt().getOrThrow())
            val boundary = "🌸".repeat(2048)
            assertEquals(boundary, a.setHostSystemPrompt(boundary).getOrThrow().systemPrompt)
            assertTrue(a.setHostSystemPrompt(boundary + "a").exceptionOrNull() is IllegalArgumentException)
            assertEquals(boundary, a.requestHostSystemPrompt().getOrThrow().systemPrompt)
        }

    @Test
    fun unchangedDoublesInheritExplicitUnsupportedFailures() =
        runTest {
            val double =
                object : ConversationRepository by FakeConversationRepository() {
                    override suspend fun requestHostSystemPrompt(): Result<HostSystemPromptReading> =
                        super<ConversationRepository>.requestHostSystemPrompt()

                    override suspend fun setHostSystemPrompt(systemPrompt: String): Result<HostSystemPromptReading> =
                        super<ConversationRepository>.setHostSystemPrompt(systemPrompt)
                }
            assertTrue(double.requestHostSystemPrompt().isFailure)
            assertTrue(double.setHostSystemPrompt("secret").isFailure)
        }

    private fun unusedCache() =
        object : ConversationCache {
            override suspend fun removeHost(serverId: String): Result<Unit> = error("host prompt must not remove cache")

            override suspend fun removeConversation(
                serverId: String,
                conversationId: String,
            ): Result<Unit> = error("host prompt must not remove cache")

            override suspend fun readConversations(serverId: String): List<Conversation> = error("host prompt must not read cache")

            override suspend fun writeConversations(
                serverId: String,
                conversations: List<Conversation>,
            ): Result<Unit> = error("host prompt must not write cache")
        }

    private class Pump : SessionPump {
        private val channel = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound: Flow<Envelope> = channel.receiveAsFlow()
        val sent = mutableListOf<Envelope>()

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return true
        }

        fun reply(
            current: String,
            default: String,
        ) {
            check(
                channel
                    .trySend(
                        Envelope(
                            99,
                            "host_system_prompt",
                            "2026-10-04T00:00:00Z",
                            buildJsonObject {
                                put("system_prompt", current)
                                put("default_system_prompt", default)
                            },
                            inReplyTo = sent.last().id,
                        ),
                    ).isSuccess,
            )
        }
    }
}
