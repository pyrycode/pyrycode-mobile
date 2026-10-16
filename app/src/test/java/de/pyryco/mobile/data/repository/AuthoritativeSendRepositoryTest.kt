package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.CAPABILITY_THREAD
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.TYPE_THREAD_ITEM_ADDED
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.item
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthoritativeSendRepositoryTest {
    @Test fun retiredSendSourceCannotRegisterFeedbackInItsReplacementStore() =
        runTest {
            val store = AuthoritativeThreadStore()
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val (pump, repo) = repository(store, scope)
            scope.cancel()
            runCurrent()
            val attempt = async { runCatching { kotlinx.coroutines.withTimeout(100) { repo.sendMessage("c", "private text") } } }
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            assertTrue(attempt.await().isFailure)
            assertTrue(store.current("host", "c").pendingSends.isEmpty())
            assertTrue(pump.sent.none { it.type == "send_message" })
        }

    private val oldSink = RelayLog.sink

    @org.junit.Before fun captureLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @org.junit.After fun restoreLogs() {
        RelayLog.sink = oldSink
    }

    @Test fun delayedIdentityReadDoesNotDelayFeedbackAndLateErrorCannotRecreateSettledSend() =
        runTest {
            val identity = kotlinx.coroutines.CompletableDeferred<String?>()
            val store = AuthoritativeThreadStore()
            val pump = Pump()
            val repo =
                RemoteConversationRepository(
                    pump,
                    backgroundScope,
                    negotiatedCapabilities = { setOf(CAPABILITY_THREAD, CAPABILITY_INTERACTIVE) },
                    authoritativeHostId = "host",
                    authoritativeThreads = store,
                    authoritativeDeviceId = { identity.await() },
                )
            val send = async { runCatching { repo.sendMessage("c", "private text") } }
            runCurrent()
            assertEquals(1, store.current("host", "c").pendingSends.size)
            assertTrue(pump.sent.isEmpty())
            identity.complete("device")
            runCurrent()
            val request = pump.sent.single { it.type == "send_message" }
            pump.push(live(accepted(request)))
            runCurrent()
            assertTrue(store.current("host", "c").pendingSends.isEmpty())
            pump.push(
                reply(
                    request,
                    "error",
                    buildJsonObject {
                        put("code", "send.failed")
                        put("message", "private")
                        put("retryable", true)
                    },
                ),
            )
            runCurrent()
            assertTrue(send.await().isFailure)
            assertTrue(store.current("host", "c").pendingSends.isEmpty())
        }

    @Test fun terminalRecoveryOwnsLifecycleAndDisconnectedSourceCannotAct() =
        runTest {
            for (status in listOf("delivered", "dropped", "lost")) {
                val store = AuthoritativeThreadStore()
                val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
                val (pump, repo) = repository(store, scope)
                val send = async { runCatching { repo.sendMessage("c", "private text") } }
                runCurrent()
                val request = pump.sent.single { it.type == "send_message" }
                val queued = accepted(request)
                store.applyBatch("host", "c", "e", 1, listOf(queued), 0)
                val target = requireNotNull(authoritativeQueueTarget("host", "c", "e", 0, queued))
                scope.cancel()
                runCurrent()
                assertTrue(send.await().isFailure)
                assertTrue(repo.actOnAuthoritativeQueue(target, false).isFailure)
                val final =
                    accepted(request, rev = 2, status = status, active = false).let { fact ->
                        ThreadItemDto.decodeReplacement(
                            JsonObject(
                                fact.raw +
                                    if (status == "dropped") {
                                        mapOf("shown" to JsonPrimitive(false))
                                    } else if (status == "delivered") {
                                        mapOf("order" to JsonPrimitive(2))
                                    } else {
                                        emptyMap()
                                    },
                            ),
                        )
                    }
                store.applyBatch("host", "c", "e", 2, listOf(final), 0)
                assertTrue(store.current("host", "c").pendingSends.isEmpty())
                assertEquals(
                    status,
                    store
                        .current("host", "c")
                        .items
                        .single()
                        .status,
                )
                store.applyBatch("host", "c", "e", 1, listOf(queued), 0)
                assertEquals(
                    final.raw,
                    store
                        .current("host", "c")
                        .items
                        .single()
                        .raw,
                )
            }
        }

    @Test fun acknowledgementIsReceiptAndReversedDuplicateItemsRetireOnlyOnce() =
        runTest {
            for (itemFirst in listOf(false, true)) {
                val store = AuthoritativeThreadStore()
                val (pump, repo) = repository(store)
                val send = async { repo.sendMessage("c", "private text") }
                runCurrent()
                val request = pump.sent.single { it.type == "send_message" }
                assertEquals(1, store.current("host", "c").pendingSends.size)
                val fact = accepted(request)
                if (itemFirst) {
                    pump.push(live(fact))
                    runCurrent()
                    assertTrue(store.current("host", "c").pendingSends.isEmpty())
                }
                pump.push(reply(request, "ack", buildJsonObject {}))
                runCurrent()
                send.await()
                assertEquals(if (itemFirst) 0 else 1, store.current("host", "c").pendingSends.size)
                pump.push(live(fact))
                pump.push(live(fact))
                runCurrent()
                assertTrue(store.current("host", "c").pendingSends.isEmpty())
                assertEquals(1, store.current("host", "c").items.size)
            }
        }

    @Test fun lostAcknowledgementRecoversSeparatelyThroughCatchUpAndPageAcrossConnectionReplacement() =
        runTest {
            for (page in listOf(false, true)) {
                val store = AuthoritativeThreadStore()
                store.establishComplete("host", "c", "e", 10, listOf(item(1, 2, 5)), 0)
                val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
                val (pump, repo) = repository(store, scope)
                val send = async { runCatching { repo.sendMessage("c", "private text") } }
                runCurrent()
                val request = pump.sent.single { it.type == "send_message" }
                scope.cancel()
                runCurrent()
                assertTrue(send.await().isFailure)
                assertEquals(1, store.current("host", "c").pendingSends.size)
                val (freshPump, fresh) = repository(store)
                if (page) {
                    // Establish the existing history continuation through the real query route.
                    store.reset("host", "c", "e")
                    fresh.openAuthoritativeThread("c")
                    val window = freshPump.sent.last()
                    freshPump.push(batch(window, 10, emptyList(), true, window = true))
                    runCurrent()
                    assertTrue(fresh.requestOlderAuthoritativeThread("c"))
                } else {
                    assertTrue(fresh.openAuthoritativeThread("c"))
                    assertEquals("thread_catch_up", freshPump.sent.last().type)
                }
                val recovery = freshPump.sent.last()
                freshPump.push(batch(recovery, 20, listOf(accepted(request, 11, 20)), false))
                runCurrent()
                assertTrue(store.current("host", "c").pendingSends.isEmpty())
                assertEquals(
                    "queued",
                    store
                        .current("host", "c")
                        .items
                        .first { it.id == 11L }
                        .status,
                )
                assertTrue(freshPump.sent.none { it.type == "send_message" })
            }
        }

    @Test fun bothBoundCommandsPreserveUnsignedNumbersAndNeverMutateOrRetarget() =
        runTest {
            for (handle in listOf("1", "9223372036854775808", "18446744073709551615")) {
                val store = AuthoritativeThreadStore()
                val (pump, repo) = repository(store)
                val fake = Envelope(1, "send_message", "", buildJsonObject { put("message_id", "client") })
                val fact = accepted(fake, handle = handle)
                store.applyBatch("host", "c", "e", 1, listOf(fact), 0)
                val target = requireNotNull(authoritativeQueueTarget("host", "c", "e", 0, fact))
                for (sendNow in listOf(false, true)) {
                    assertTrue(repo.actOnAuthoritativeQueue(target, sendNow).isSuccess)
                    val command = pump.sent.last()
                    assertEquals(if (sendNow) "send_queued_now" else "dequeue_message", command.type)
                    assertEquals(
                        "c",
                        command.payload.jsonObject["conversation_id"]
                            ?.jsonPrimitive
                            ?.content,
                    )
                    assertEquals(handle, command.payload.jsonObject["queued_msg_id"].toString())
                    assertEquals("1", command.payload.jsonObject["accepted_item_id"].toString())
                    assertEquals(
                        fact.raw,
                        store
                            .current("host", "c")
                            .items
                            .single()
                            .raw,
                    )
                    pump.succeeds = false
                    assertTrue(repo.actOnAuthoritativeQueue(target, sendNow).isFailure)
                    assertEquals(
                        fact.raw,
                        store
                            .current("host", "c")
                            .items
                            .single()
                            .raw,
                    )
                    pump.succeeds = true
                }
                store.applyBatch("host", "c", "e", 3, listOf(accepted(fake, id = 3, handle = handle)), 0)
                // Before the old loss revision arrives, its captured target still sends only its old guard.
                for (sendNow in listOf(false, true)) {
                    assertTrue(repo.actOnAuthoritativeQueue(target, sendNow).isSuccess)
                    assertEquals(
                        "1",
                        pump.sent
                            .last()
                            .payload.jsonObject["accepted_item_id"]
                            .toString(),
                    )
                }
                store.applyBatch("host", "c", "e", 4, listOf(accepted(fake, rev = 4, status = "lost", active = false, handle = handle)), 0)
                val count = pump.sent.size
                assertTrue(repo.actOnAuthoritativeQueue(target, false).isFailure)
                assertTrue(repo.actOnAuthoritativeQueue(target, true).isFailure)
                assertEquals(count, pump.sent.size)
                assertTrue(repo.actOnAuthoritativeQueue(target.copy(host = "other"), false).isFailure)
                assertTrue(repo.actOnAuthoritativeQueue(target.copy(conversation = "other"), false).isFailure)
            }
        }

    @Test fun timeoutAfterItemAndLateAcknowledgementLeaveOnlyDaemonOwnership() =
        runTest {
            val store = AuthoritativeThreadStore()
            val (pump, repo) = repository(store)
            val send = async { runCatching { kotlinx.coroutines.withTimeout(1000) { repo.sendMessage("c", "private text") } } }
            runCurrent()
            val request = pump.sent.single { it.type == "send_message" }
            pump.push(live(accepted(request)))
            runCurrent()
            advanceTimeBy(1001)
            runCurrent()
            assertTrue(send.await().isFailure)
            pump.push(reply(request, "ack", buildJsonObject {}))
            runCurrent()
            assertTrue(store.current("host", "c").pendingSends.isEmpty())
            assertEquals(1, store.current("host", "c").items.size)
        }

    @Test fun terminalRecoveryRetiresUnacceptedFeedbackWithoutInferringOutcome() =
        runTest {
            for (status in listOf("delivered", "dropped", "lost")) {
                val store = AuthoritativeThreadStore()
                val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
                val (pump, repo) = repository(store, scope)
                val send = async { runCatching { repo.sendMessage("c", "private text") } }
                runCurrent()
                val request = pump.sent.single { it.type == "send_message" }
                scope.cancel()
                runCurrent()
                assertTrue(send.await().isFailure)
                assertEquals(1, store.current("host", "c").pendingSends.size)
                val fact = accepted(request, status = status, active = false)
                val raw =
                    when (status) {
                        "delivered" -> fact.raw + ("order" to JsonPrimitive(1))
                        "dropped" -> fact.raw + ("shown" to JsonPrimitive(false))
                        else -> fact.raw
                    }
                store.applyBatch("host", "c", "e", 1, listOf(ThreadItemDto.decodeReplacement(JsonObject(raw))), 0)
                assertTrue(store.current("host", "c").pendingSends.isEmpty())
                assertEquals(
                    status,
                    store
                        .current("host", "c")
                        .items
                        .single()
                        .status,
                )
            }
        }

    private fun TestScope.repository(
        store: AuthoritativeThreadStore,
        scope: CoroutineScope = backgroundScope,
    ): Pair<Pump, RemoteConversationRepository> {
        val pump = Pump()
        return pump to
            RemoteConversationRepository(
                pump,
                scope,
                negotiatedCapabilities = { setOf(CAPABILITY_THREAD, CAPABILITY_INTERACTIVE) },
                authoritativeHostId = "host",
                authoritativeThreads = store,
                authoritativeDeviceId = { "device" },
                authoritativeNowMillis = { testScheduler.currentTime },
            )
    }

    private fun accepted(
        request: Envelope,
        id: Long = 1,
        rev: Long = id,
        status: String = "queued",
        active: Boolean = true,
        handle: String = "7",
    ) = ThreadItemDto.decodeReplacement(
        buildJsonObject {
            put("id", id)
            put("rev", rev)
            put("kind", "user_message")
            put("status", status)
            put("shown", true)
            put("active", active)
            put("summary", "")
            put(
                "content",
                buildJsonObject {
                    put("text", "private text")
                    put("message_id", request.payload.jsonObject.getValue("message_id"))
                    put("device_id", "device")
                    put("queued_msg_id", Json.parseToJsonElement(handle))
                },
            )
        },
    )

    private fun live(fact: ThreadItemDto) =
        Envelope(
            100,
            TYPE_THREAD_ITEM_ADDED,
            "",
            buildJsonObject {
                put("conversation_id", "c")
                put("epoch", "e")
                put("version", fact.rev)
                put("item_id", fact.id)
                put("rev", fact.rev)
                put("item", fact.raw)
            },
        )

    private fun reply(
        request: Envelope,
        type: String,
        payload: JsonObject,
    ) = Envelope(1000, type, "", payload, inReplyTo = request.id)

    private fun batch(
        request: Envelope,
        version: Long,
        facts: List<ThreadItemDto>,
        final: Boolean,
        window: Boolean = false,
    ) = reply(
        request,
        "thread_items",
        buildJsonObject {
            put("conversation_id", "c")
            put("epoch", "e")
            put("version", version)
            put("items", JsonArray(facts.map { it.raw }))
            put("final", final)
            if (window) {
                put("lower_order", 5)
                put("upper_order", 11)
                put("continuation", 5)
                put("older_exists", true)
            }
        },
    )

    private class Pump : SessionPump {
        private val channel = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound = channel.receiveAsFlow()
        val sent = mutableListOf<Envelope>()
        var succeeds = true

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return succeeds
        }

        fun push(envelope: Envelope) {
            channel.trySend(envelope)
        }
    }
}
