package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConversationRepositoryHostSystemPromptTest {
    private val logs = mutableListOf<String>()
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled

    @Before
    fun captureLogs() {
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun restoreLogs() {
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
    }

    @Test
    fun readAndWriteHaveExactHostOnlyEnvelopesWithoutInteractive() =
        runTest {
            val pump = Pump()
            val repo = repo(pump)
            val read = read(repo)
            runCurrent()
            val ask = pump.sent.single()
            assertEquals("request_host_system_prompt", ask.type)
            assertEquals(JsonObject(emptyMap()), ask.payload)
            assertEnvelope(ask)
            assertFalse(read.isCompleted)
            pump.reply(ask.id, reading(CURRENT, DEFAULT))
            runCurrent()
            assertEquals(HostSystemPromptReading(CURRENT, DEFAULT), read.await().getOrThrow())

            val save = write(repo, "  custom\n🌸  ")
            runCurrent()
            val set = pump.sent.last()
            assertEquals("set_host_system_prompt", set.type)
            assertEquals(buildJsonObject { put("system_prompt", "  custom\n🌸  ") }, set.payload)
            assertEnvelope(set)
            assertTrue(set.id > ask.id)
            assertFalse(save.isCompleted)
            // Return the daemon's authoritative value, not an optimistic copy of submitted text.
            pump.reply(set.id, reading(CURRENT, DEFAULT))
            runCurrent()
            assertEquals(HostSystemPromptReading(CURRENT, DEFAULT), save.await().getOrThrow())
            assertTrue(logs.any { "outcome=sent" in it })
            assertTrue(logs.any { "outcome=succeeded" in it })
            assertContentFree(logs.joinToString())
        }

    @Test
    fun emptyCustomWhitespaceAndReturnedDefaultRoundTripVerbatim() =
        runTest {
            val pump = Pump()
            val repo = repo(pump)
            for (value in listOf("", "  \n\t ", CURRENT, DEFAULT)) {
                val save = write(repo, value)
                runCurrent()
                assertEquals(buildJsonObject { put("system_prompt", value) }, pump.sent.last().payload)
                pump.reply(pump.sent.last().id, reading(value, DEFAULT))
                runCurrent()
                val saved = save.await().getOrThrow()
                assertEquals(value, saved.systemPrompt)
                assertEquals(DEFAULT, saved.defaultSystemPrompt)
                val read = read(repo)
                runCurrent()
                pump.reply(pump.sent.last().id, reading(value, DEFAULT))
                runCurrent()
                assertEquals(saved, read.await().getOrThrow())
            }
            val read = read(repo)
            runCurrent()
            pump.reply(pump.sent.last().id, reading(CURRENT, DEFAULT))
            runCurrent()
            val resetText = read.await().getOrThrow().defaultSystemPrompt
            val reset = write(repo, resetText)
            runCurrent()
            pump.reply(pump.sent.last().id, reading(DEFAULT, DEFAULT))
            runCurrent()
            assertEquals(HostSystemPromptReading(DEFAULT, DEFAULT), reset.await().getOrThrow())
        }

    @Test
    fun inclusiveByteBoundariesUseUtf8AndRejectBeforeSending() =
        runTest {
            val pump = Pump()
            val repo = repo(pump)
            for (text in listOf("a".repeat(8192), "€".repeat(2730) + "ab", "🌸".repeat(2048))) {
                assertEquals(8192, SystemPromptLimit.utf8Bytes(text))
                val save = write(repo, text)
                runCurrent()
                assertEquals(buildJsonObject { put("system_prompt", text) }, pump.sent.last().payload)
                pump.reply(pump.sent.last().id, reading(text, DEFAULT))
                runCurrent()
                assertEquals(text, save.await().getOrThrow().systemPrompt)
            }
            val sentCount = pump.sent.size
            for (text in listOf("a".repeat(8193), "€".repeat(2730) + "äa", "🌸".repeat(2048) + "a")) {
                val save = write(repo, text)
                runCurrent()
                assertTrue(save.await().exceptionOrNull() is IllegalArgumentException)
                assertEquals(sentCount, pump.sent.size)
            }
            assertTrue(logs.any { "outcome=too_long" in it })
        }

    @Test
    fun everyMalformedShapeFailsBothOperationsWithoutLeakingContent() =
        runTest {
            val pump = Pump()
            val repo = repo(pump)
            val payloads = mutableListOf("null", "[]", "42", "true", "\"$CURRENT\"", "{}")
            for (field in listOf("system_prompt", "default_system_prompt")) {
                val other = if (field == "system_prompt") "default_system_prompt" else "system_prompt"
                payloads += """{"$other":"$CURRENT"}"""
                for (invalid in listOf("null", "42", "false", "[]", """{"secret":"$CURRENT"}""")) {
                    payloads += """{"$field":$invalid,"$other":"$DEFAULT"}"""
                }
            }
            val malformed =
                payloads.map(MobileJson::parseToJsonElement) +
                    listOf(reading("a".repeat(8193), DEFAULT), reading(CURRENT, "🌸".repeat(2049)))
            for (payload in malformed) {
                for (save in listOf(false, true)) {
                    val call = if (save) write(repo, CURRENT) else read(repo)
                    runCurrent()
                    pump.reply(pump.sent.last().id, payload)
                    runCurrent()
                    val failure = requireNotNull(call.await().exceptionOrNull())
                    assertTrue(failure is SerializationException)
                    assertContentFree(failure.stackTraceToString())
                }
            }
            val valid = read(repo)
            runCurrent()
            pump.reply(
                pump.sent.last().id,
                buildJsonObject {
                    put("system_prompt", "")
                    put("default_system_prompt", "")
                    put("future", CURRENT)
                },
            )
            runCurrent()
            assertEquals(HostSystemPromptReading("", ""), valid.await().getOrThrow())
            assertContentFree(logs.joinToString())
        }

    @Test
    fun correlatedProtocolAndRetryableStorageErrorsFailReadAndWrite() =
        runTest {
            val pump = Pump()
            val repo = repo(pump)
            for ((code, retryable) in listOf("protocol.malformed" to false, "host_system_prompt.unavailable" to true)) {
                for (save in listOf(false, true)) {
                    val call = if (save) write(repo, CURRENT) else read(repo)
                    runCurrent()
                    pump.push(
                        Envelope(
                            99,
                            "error",
                            TS,
                            buildJsonObject {
                                put("code", code)
                                put("retryable", retryable)
                                put("message", "$CURRENT $DEFAULT")
                            },
                            inReplyTo = pump.sent.last().id,
                        ),
                    )
                    runCurrent()
                    val failure = call.await().exceptionOrNull() as RelayErrorException
                    assertEquals(code, failure.code)
                    assertEquals(retryable, failure.retryable)
                    assertContentFree(failure.stackTraceToString())
                }
            }
            assertContentFree(logs.joinToString())
        }

    @Test
    fun malformedAndUnknownErrorRepliesStayContentFree() =
        runTest {
            val pump = Pump()
            val repo = repo(pump)
            for (payload in listOf(
                MobileJson.parseToJsonElement("null"),
                buildJsonObject { put("message", CURRENT) },
                buildJsonObject {
                    put("code", CURRENT)
                    put("message", DEFAULT)
                    put("retryable", false)
                },
            )) {
                val save = write(repo, CURRENT)
                runCurrent()
                pump.push(Envelope(99, "error", TS, payload, inReplyTo = pump.sent.last().id))
                runCurrent()
                assertContentFree(requireNotNull(save.await().exceptionOrNull()).stackTraceToString())
            }
            assertContentFree(logs.joinToString())
        }

    @Test
    fun bareAckCannotConfirmHostSaveAndAConcurrentReadSurvivesWriteFailure() =
        runTest {
            val pump = Pump()
            val repo = repo(pump)
            val read = read(repo)
            val save = write(repo, CURRENT)
            runCurrent()
            pump.push(Envelope(99, "ack", TS, JsonObject(emptyMap()), inReplyTo = pump.sent.last().id))
            runCurrent()
            assertTrue(save.await().exceptionOrNull() is SerializationException)
            assertFalse(read.isCompleted)
            pump.reply(pump.sent.first().id, reading(CURRENT, DEFAULT))
            runCurrent()
            assertEquals(HostSystemPromptReading(CURRENT, DEFAULT), read.await().getOrThrow())
        }

    @Test
    fun concurrentRepliesAreIsolatedAndStraysCannotUpdateConversationState() =
        runTest {
            val pump = Pump()
            val repo = repo(pump)
            pump.push(
                Envelope(
                    98,
                    "conversation_updated",
                    TS,
                    MobileJson.parseToJsonElement(
                        """{"id":"conv","name":"Before","is_promoted":true,"is_archived":false,"cwd":"/p","last_used_at":"2026-10-04T00:00:00Z"}""",
                    ),
                ),
            )
            runCurrent()
            val before = repo.observeConversations(ConversationFilter.All).first()
            assertEquals("Before", before.single().name)
            val rowsBefore = repo.observeThreadRowCounts().first()
            val read = read(repo)
            val save = write(repo, CURRENT)
            runCurrent()
            val (readId, saveId) = pump.sent.filter { it.type.endsWith("host_system_prompt") }.map { it.id }
            pump.reply(null, reading("stray", DEFAULT))
            pump.reply(saveId + 100, reading("unmatched", DEFAULT))
            runCurrent()
            assertFalse(read.isCompleted)
            assertFalse(save.isCompleted)
            pump.reply(saveId, reading("saved", DEFAULT))
            pump.reply(saveId, reading("duplicate", DEFAULT))
            runCurrent()
            assertFalse(read.isCompleted)
            assertEquals("saved", save.await().getOrThrow().systemPrompt)
            pump.reply(readId, reading("read", DEFAULT))
            runCurrent()
            assertEquals("read", read.await().getOrThrow().systemPrompt)
            pump.reply(readId, reading("late", DEFAULT))
            runCurrent()
            assertEquals(before, repo.observeConversations(ConversationFilter.All).first())
            assertEquals(rowsBefore, repo.observeThreadRowCounts().first())
        }

    @Test
    fun twoHostsWithIdenticalRequestIdsNeverShareReplies() =
        runTest {
            val pumpA = Pump()
            val pumpB = Pump()
            val repoA = repo(pumpA)
            val repoB = repo(pumpB)
            val readA = read(repoA)
            val writeB = write(repoB, "B")
            runCurrent()
            assertEquals(pumpA.sent.single().id, pumpB.sent.single().id)
            pumpB.reply(pumpB.sent.single().id, reading("B", "default B"))
            runCurrent()
            assertFalse(readA.isCompleted)
            assertEquals(HostSystemPromptReading("B", "default B"), writeB.await().getOrThrow())
            pumpA.reply(pumpA.sent.single().id, reading("A", "default A"))
            runCurrent()
            assertEquals(HostSystemPromptReading("A", "default A"), readA.await().getOrThrow())
        }

    @Test
    fun disconnectedAndTeardownFailWhileCallerCancellationPropagates() =
        runTest {
            val pump = Pump()
            val repo = repo(pump)
            pump.connected = false
            for (call in listOf(read(repo), write(repo, CURRENT))) {
                runCurrent()
                assertTrue(call.await().exceptionOrNull() is IllegalStateException)
            }
            pump.connected = true
            val cancelled = read(repo)
            runCurrent()
            val cancelledId = pump.sent.last().id
            cancelled.cancel()
            runCurrent()
            assertTrue(cancelled.isCancelled)
            pump.reply(cancelledId, reading(CURRENT, DEFAULT))
            val pendingRead = read(repo)
            val pendingWrite = write(repo, CURRENT)
            runCurrent()
            pump.close()
            runCurrent()
            assertTrue(pendingRead.await().exceptionOrNull() is IllegalStateException)
            assertTrue(pendingWrite.await().exceptionOrNull() is IllegalStateException)
        }

    @Test
    fun readingRepresentationRedactsBothFieldsButEqualityRetainsThem() {
        val reading = HostSystemPromptReading(CURRENT, DEFAULT)
        assertContentFree(reading.toString())
        assertContentFree(Result.success(reading).toString())
        assertEquals(reading, HostSystemPromptReading(CURRENT, DEFAULT))
        assertNotEquals(reading, HostSystemPromptReading("", DEFAULT))
        assertNotEquals(reading, HostSystemPromptReading(CURRENT, ""))
    }

    private fun assertEnvelope(envelope: Envelope) {
        val encoded = MobileJson.encodeToJsonElement(envelope).jsonObject
        assertEquals(setOf("id", "type", "ts", "payload"), encoded.keys)
        assertTrue(envelope.ts.isNotEmpty())
    }

    private fun assertContentFree(value: String) {
        assertFalse(value.contains(CURRENT))
        assertFalse(value.contains(DEFAULT))
    }

    private fun TestScope.repo(pump: Pump) = RemoteConversationRepository(pump, backgroundScope)

    private fun TestScope.read(repo: ConversationRepository): Deferred<Result<HostSystemPromptReading>> =
        backgroundScope.async { repo.requestHostSystemPrompt() }

    private fun TestScope.write(
        repo: ConversationRepository,
        text: String,
    ): Deferred<Result<HostSystemPromptReading>> = backgroundScope.async { repo.setHostSystemPrompt(text) }

    private fun reading(
        current: String,
        default: String,
    ): JsonElement =
        buildJsonObject {
            put("system_prompt", current)
            put("default_system_prompt", default)
        }

    private class Pump : SessionPump {
        private val channel = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound: Flow<Envelope> = channel.receiveAsFlow()
        val sent = mutableListOf<Envelope>()
        var connected = true

        override fun send(envelope: Envelope): Boolean {
            if (!connected) return false
            sent += envelope
            return true
        }

        fun push(envelope: Envelope) {
            check(channel.trySend(envelope).isSuccess)
        }

        fun reply(
            id: Long?,
            payload: JsonElement,
        ) = push(Envelope(99, "host_system_prompt", TS, payload, inReplyTo = id))

        fun close() {
            channel.close()
        }
    }

    private companion object {
        const val TS = "2026-10-04T00:00:00Z"
        const val CURRENT = "PRIVATE_CURRENT_SENTINEL"
        const val DEFAULT = "PRIVATE_DEFAULT_SENTINEL"
    }
}
