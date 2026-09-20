package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.base64StdEncode
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class DebugBundleTransferTest {
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled
    private val logs = mutableListOf<String>()

    @Before fun captureLogs() {
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After fun restoreLogs() {
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
    }

    @Test
    fun bareRequestOmitsPayloadAndConversationWithoutChangingPayloadMessages() {
        val request = Envelope(1, "request_debug_bundle", "2026-09-21T00:00:00Z", JsonNull)
        val json = MobileJson.parseToJsonElement(MobileJson.encodeToString(request)).jsonObject
        assertFalse(json.containsKey("payload"))
        assertFalse(json.containsKey("conversation_id"))
        val payload = MobileJson.parseToJsonElement("""{"conversation_id":"c","text":"hello"}""")
        val ordinary = request.copy(type = "send_message", payload = payload)
        assertEquals(payload, MobileJson.parseToJsonElement(MobileJson.encodeToString(ordinary)).jsonObject["payload"])
    }

    @Test fun emptySingleAndMultiChunkArchivesExposeOnlyAcceptedProgress() =
        runTest {
            val cases = listOf(emptyList(), listOf(byteArrayOf(0, -1, 5)), listOf("secret-recording".toByteArray(), byteArrayOf(1, 0, -2)))
            for (parts in cases) {
                val f = Fixture(this)
                val transfer = f.repo.requestDebugBundle()
                assertEquals(DebugBundleStatus.RECEIVING, transfer.state.value.status)
                assertEquals(1, f.pump.sent.size)
                parts.forEachIndexed { index, bytes ->
                    f.pump.emit("debug_bundle_chunk", """{"seq":$index,"data":"${base64StdEncode(bytes)}"}""")
                    runCurrent()
                    assertEquals(index + 1, transfer.state.value.acceptedChunks)
                    assertNull(transfer.takeArchive())
                }
                f.pump.emit("debug_bundle_done", """{"total":${parts.size}}""")
                runCurrent()
                val archive = transfer.takeArchive()!!
                val output = ByteArrayOutputStream()
                archive.writeTo(output)
                assertArrayEquals(parts.fold(byteArrayOf()) { a, b -> a + b }, output.toByteArray())
                assertEquals(parts.sumOf { it.size }.toLong(), archive.sizeBytes)
                assertEquals(DebugBundleStatus.COMPLETE, transfer.state.value.status)
                assertNull(transfer.takeArchive())
                assertFalse(archive.toString().contains("secret-recording"))
                f.close()
            }
            assertTrue(logs.none { it.contains("secret-recording") })
        }

    @Test fun malformedFieldsAndOrderingFailWithoutPartialBytesAndKeepOrdinaryEventsAlive() =
        runTest {
            val first = Fixture(this)
            val invalidStart = first.repo.requestDebugBundle()
            first.pump.emit("debug_bundle_chunk", """{"seq":1,"data":"YQ=="}""")
            runCurrent()
            assertEquals(DebugBundleStatus.INVALID_STREAM, invalidStart.state.value.status)
            assertEquals(0, invalidStart.state.value.acceptedChunks)
            assertNull(invalidStart.takeArchive())
            first.close()
            val badChunks =
                listOf(
                    "null",
                    "[]",
                    "{}",
                    """{"seq":"1","data":"YQ=="}""",
                    """{"seq":1.0,"data":"YQ=="}""",
                    """{"seq":-1,"data":"YQ=="}""",
                    """{"seq":2147483648,"data":"YQ=="}""",
                    """{"seq":true,"data":"YQ=="}""",
                    """{"seq":0,"data":"YQ=="}""",
                    """{"seq":2,"data":"YQ=="}""",
                    """{"seq":1,"data":1}""",
                    """{"seq":1,"data":null}""",
                    """{"seq":1,"data":"YQ"}""",
                    """{"seq":1,"data":"YQ==\n"}""",
                    """{"seq":1,"data":"__8="}""",
                    """{"seq":1,"data":"!!!!"}""",
                    """{"seq":1,"data":"YR=="}""",
                )
            val badDone = listOf("null", "{}", """{"total":"1"}""", """{"total":1.0}""", """{"total":0}""", """{"total":2}""")
            val cases =
                badChunks.map { Triple(1, "debug_bundle_chunk", it) } + badDone.map { Triple(1, "debug_bundle_done", it) } +
                    listOf("1e-400", "-1e-400", "0.0", "1e999", "2147483648", "-2147483649").flatMap { number ->
                        listOf(
                            Triple(0, "debug_bundle_chunk", """{"seq":$number,"data":"YQ=="}"""),
                            Triple(0, "debug_bundle_done", """{"total":$number}"""),
                        )
                    }
            for ((accepted, type, payload) in cases) {
                val f = Fixture(this)
                val transfer = f.repo.requestDebugBundle()
                if (accepted == 1) f.pump.emit("debug_bundle_chunk", """{"seq":0,"data":"YQ=="}""")
                f.pump.emit(type, payload)
                runCurrent()
                assertEquals(payload, DebugBundleStatus.INVALID_STREAM, transfer.state.value.status)
                assertEquals(accepted, transfer.state.value.acceptedChunks)
                assertNull(transfer.takeArchive())
                val settled = transfer.state.value
                f.pump.emit("debug_bundle_done", """{"total":$accepted}""")
                f.pump.emit("debug_bundle_chunk", """{"seq":$accepted,"data":"YQ=="}""")
                f.pump.emit("debug_bundle_done", """{"total":${accepted + 1}}""")
                f.pump.emit("conversations", """{"conversations":[]}""")
                runCurrent()
                assertEquals(emptyList<Any>(), f.repo.observeConversations(ConversationFilter.All).first())
                assertEquals(settled, transfer.state.value)
                assertNull(transfer.takeArchive())
                f.close()
            }
        }

    @Test fun correlatedRefusalDoesNotConsumeUnrelatedErrorsAndNeverLeaksDetails() =
        runTest {
            val f = Fixture(this)
            val transfer = f.repo.requestDebugBundle()
            val ordinary = async { runCatching { f.repo.answerModal("m", "yes") } }
            runCurrent()
            val ordinaryId =
                f.pump.sent
                    .last()
                    .id
            val secret = """{"code":"server.binary_offline","retryable":true,"message":"secret-recording"}"""
            f.pump.emit("error", secret, ordinaryId)
            runCurrent()
            assertTrue(ordinary.await().isFailure)
            assertEquals(DebugBundleStatus.RECEIVING, transfer.state.value.status)
            f.pump.emit("error", secret)
            runCurrent()
            assertEquals(DebugBundleStatus.RECEIVING, transfer.state.value.status)
            f.pump.emit(
                "error",
                secret,
                f.pump.sent
                    .first()
                    .id,
            )
            runCurrent()
            assertEquals(DebugBundleStatus.REFUSED, transfer.state.value.status)
            assertEquals(DebugBundleRetry.AFTER_RECONNECT, transfer.state.value.retry)
            assertFalse(
                transfer.state.value
                    .toString()
                    .contains("secret-recording"),
            )
            assertTrue(logs.none { it.contains("secret-recording") })
            f.close()
        }

    @Test fun secondRequestAndLateFramesCannotReplaceOrContaminateTransfer() =
        runTest {
            val f = Fixture(this)
            val first = f.repo.requestDebugBundle()
            assertEquals(
                DebugBundleStatus.BUSY,
                f.repo
                    .requestDebugBundle()
                    .state.value.status,
            )
            assertEquals(1, f.pump.sent.size)
            f.pump.emit("debug_bundle_done", """{"total":0}""")
            runCurrent()
            val settled = first.state.value
            val retry = f.repo.requestDebugBundle()
            assertEquals(DebugBundleStatus.RECONNECT_REQUIRED, retry.state.value.status)
            f.pump.emit("debug_bundle_chunk", """{"seq":0,"data":"YQ=="}""")
            f.pump.emit("debug_bundle_done", """{"total":1}""")
            f.pump.emit(
                "error",
                "{}",
                f.pump.sent
                    .first()
                    .id,
            )
            runCurrent()
            assertEquals(settled, first.state.value)
            assertEquals(1, f.pump.sent.size)
            f.close()
        }

    @Test fun failedSendsAreStaticTerminalFailuresAndRequireFreshConnection() =
        runTest {
            for (throws in listOf(false, true)) {
                val f = Fixture(this)
                f.pump.fail = true
                f.pump.throws = throws
                val transfer = f.repo.requestDebugBundle()
                assertEquals(DebugBundleStatus.SEND_FAILED, transfer.state.value.status)
                assertEquals(DebugBundleRetry.AFTER_RECONNECT, transfer.state.value.retry)
                assertNull(transfer.takeArchive())
                assertEquals(
                    DebugBundleStatus.RECONNECT_REQUIRED,
                    f.repo
                        .requestDebugBundle()
                        .state.value.status,
                )
                f.close()
            }
            assertTrue(logs.none { it.contains("secret-recording") })
        }

    @Test fun incompleteStreamEndsOnCompletionExceptionOrCancellationAndCannotRestart() =
        runTest {
            for (mode in 0..2) {
                val f = Fixture(this)
                val transfer = f.repo.requestDebugBundle()
                f.pump.emit("debug_bundle_chunk", """{"seq":0,"data":"YQ=="}""")
                runCurrent()
                assertEquals(DebugBundleStatus.RECEIVING, transfer.state.value.status)
                when (mode) {
                    0 -> f.pump.channel.close()
                    1 -> f.pump.channel.close(IllegalStateException("secret-recording"))
                    else -> f.close()
                }
                runCurrent()
                assertEquals(DebugBundleStatus.DISCONNECTED, transfer.state.value.status)
                assertNull(transfer.takeArchive())
                assertEquals(
                    DebugBundleStatus.UNAVAILABLE,
                    f.repo
                        .requestDebugBundle()
                        .state.value.status,
                )
                f.close()
            }
            assertTrue(logs.none { it.contains("secret-recording") })
        }

    private class Fixture(
        scope: TestScope,
    ) {
        private val owner =
            CoroutineScope(
                SupervisorJob() + StandardTestDispatcher(scope.testScheduler) + CoroutineExceptionHandler { _, _ -> },
            )
        val pump = Pump()
        val repo = RemoteConversationRepository(pump, owner)

        fun close() = owner.cancel()
    }

    private class Pump : SessionPump {
        val channel = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound = channel.receiveAsFlow()
        val sent = mutableListOf<Envelope>()
        var fail = false
        var throws = false

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            if (throws) error("secret-recording")
            return !fail
        }

        fun emit(
            type: String,
            payload: String,
            inReplyTo: Long? = null,
        ) {
            channel.trySend(Envelope(7, type, "2026-09-21T00:00:00Z", MobileJson.parseToJsonElement(payload), inReplyTo))
        }
    }
}
