package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.TransportEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class SuiteConnectionsTest {
    private var savedLogSink = RelayLog.sink

    @Before fun muteDecoderLogs() {
        savedLogSink = RelayLog.sink
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreDecoderLogs() {
        RelayLog.sink = savedLogSink
    }

    @Test fun phoneTeardownRetainsRequiredWorkBeforeTestEnd() =
        runTest {
            val owner = "phone/host/1"
            val query = frame(13, "thread_catch_up", """{"conversation_id":"chat","epoch":"epoch","version":28}""")
            val reply =
                frame(
                    48,
                    "thread_items",
                    """{"conversation_id":"chat","epoch":"epoch","version":28,"items":[],"final":true,"from_version":28}""",
                    13,
                )
            val tape =
                RecordedSuiteTape(
                    listOf(
                        SuiteStep("connect", owner),
                        SuiteStep("open", owner),
                        SuiteStep("client", owner, query),
                        SuiteStep("daemon", owner, reply, delayMs = 1000),
                        SuiteStep("client", "control", frame(4, "test-control:test-end", """{"body":""}""")),
                        SuiteStep("close", owner),
                        SuiteStep("inbound_end", owner),
                        SuiteStep("client", "control", frame(5, "test-control:checkpoint", """{"body":"scenario-complete"}""")),
                    ),
                    literalProducerIdentities = true,
                )
            val hub = SuiteConnections.replaying(backgroundScope, tape)
            try {
                val transport = hub.transport(owner) { error("Live transport started") }
                val pump = hub.pump(transport) { error("Live pump started") }
                pump.start()
                transport.connect()
                runCurrent()
                // The scenario has asserted its rows; its asynchronous query has not sent yet.
                val foreground = async { hub.beforePhoneTeardown() }
                runCurrent()
                if (foreground.isCompleted) {
                    // Activity destruction permits the delayed process stop to close the phone.
                    transport.close()
                    hub.awaitIdle()
                }
                assertFalse("Activity teardown must keep the pending query producer alive", foreground.isCompleted)
                assertTrue(pump.send(query.copy(id = 90)))
                runCurrent()
                advanceTimeBy(20)
                runCurrent()
                assertFalse("Matching the request does not deliver its queued reply", foreground.isCompleted)
                advanceTimeBy(100)
                runCurrent()
                foreground.await()
                assertEquals(reply.copy(inReplyTo = 90), pump.inbound.first())
                // The foreground fence did not consume test-end or the physical close.
                // Send directly through the strict tape: control() uses a blocking bridge, which
                // cannot drive this test's virtual delivery dispatcher while awaiting its barrier.
                assertTrue(tape.send("control", frame(90, "test-control:test-end", """{"body":""}""")).accepted)
                transport.close()
                runCurrent()
                hub.checkpoint("scenario-complete")
                hub.assertComplete()
            } finally {
                hub.close()
            }
        }

    @Test fun automaticPeerApprovalWaitsForTheActualPhoneSend() =
        runTest {
            val peer = "peer/host/0"
            val phone = "phone/host/0"
            val checkpoint = frame(1, "test-control:checkpoint", """{"body":"phone-send"}""")
            val send = frame(1, "send_message", """{"text":"controlled release"}""")
            val approval = frame(1, "modal_answer", """{"modal_id":"prompt","option_id":"allow_once","answer_token":"captured"}""")
            val tape =
                RecordedSuiteTape(
                    listOf(
                        SuiteStep("connect", peer),
                        SuiteStep("open", peer),
                        SuiteStep("connect", phone),
                        SuiteStep("open", phone),
                        SuiteStep("client", "control", checkpoint),
                        SuiteStep("client", phone, send),
                        SuiteStep("client", peer, approval),
                        SuiteStep("close", peer),
                        SuiteStep("inbound_end", peer),
                        SuiteStep("close", phone),
                        SuiteStep("inbound_end", phone),
                    ),
                    literalProducerIdentities = true,
                )
            val hub = SuiteConnections.replaying(backgroundScope, tape)
            try {
                val peerTransport = hub.transport(peer) { error("Live transport started") }
                val phoneTransport = hub.transport(phone) { error("Live transport started") }
                val peerPump = hub.pump(peerTransport) { error("Live pump started") }
                val phonePump = hub.pump(phoneTransport) { error("Live pump started") }
                peerPump.start()
                phonePump.start()
                peerTransport.connect()
                phoneTransport.connect()
                runCurrent()
                val waiting = async { hub.beforePeerPermission(peerPump, "prompt", 1000) }
                runCurrent()
                assertFalse(waiting.isCompleted)
                assertTrue(tape.send("control", checkpoint.copy(id = 10)).accepted)
                advanceTimeBy(20)
                runCurrent()
                assertFalse("the phone checkpoint cannot replace its actual send", waiting.isCompleted)
                assertTrue(phonePump.send(send.copy(id = 11)))
                assertFalse("the phone pump cannot borrow the peer action", hub.peerPermissionReady(phonePump, "prompt"))
                advanceTimeBy(10)
                runCurrent()
                waiting.await()
                assertTrue(peerPump.send(approval.copy(id = 12)))
                assertFalse(hub.peerPermissionReady(peerPump, "prompt"))
                peerTransport.close()
                phoneTransport.close()
                runCurrent()
                hub.assertComplete()
            } finally {
                hub.close()
            }
        }

    @Test fun deliveredQueryCertificateReschedulesLiveProgressWithoutAnotherPhoneRequest() =
        runTest {
            val owner = "phone/host/0"
            val initial = frame(1, "thread_catch_up", """{"conversation_id":"chat","epoch":"epoch","version":0}""")
            val tail = frame(3, "thread_catch_up", """{"conversation_id":"chat","epoch":"epoch","version":20}""")
            val tape =
                RecordedSuiteTape(
                    listOf(
                        SuiteStep("connect", owner),
                        SuiteStep("open", owner),
                        SuiteStep("client", owner, initial),
                        SuiteStep(
                            "daemon",
                            owner,
                            frame(
                                2,
                                "thread_items",
                                """{"conversation_id":"chat","epoch":"epoch","version":20,"items":[],"final":true,"lower_order":0,"upper_order":0,"continuation":0,"older_exists":false}""",
                                reply = 1,
                            ),
                        ),
                        SuiteStep("client", owner, tail),
                        SuiteStep(
                            "daemon",
                            owner,
                            frame(
                                4,
                                "thread_item_added",
                                """{"conversation_id":"chat","epoch":"epoch","version":21,"item":{"id":1,"order":1,"rev":21,"kind":"assistant_message","session":"session","turn":"turn","status":"done","active":false,"shown":true,"summary":"reply","content":{"text":"controlled reply"}}}""",
                            ),
                        ),
                        SuiteStep(
                            "daemon",
                            owner,
                            frame(
                                5,
                                "thread_items",
                                """{"conversation_id":"chat","epoch":"epoch","version":21,"items":[],"final":true,"from_version":20}""",
                                reply = 3,
                            ),
                        ),
                        SuiteStep("close", owner),
                        SuiteStep("inbound_end", owner),
                    ),
                    literalProducerIdentities = true,
                )
            val savedLogSink = RelayLog.sink
            RelayLog.sink = { _, _, _ -> }
            val hub = SuiteConnections.replaying(backgroundScope, tape)
            try {
                val transport = hub.transport(owner) { error("Live transport started") }
                val pump = hub.pump(transport) { error("Live pump started") }
                pump.start()
                transport.connect()
                val live = backgroundScope.async { pump.inbound.first { it.type == "thread_item_added" } }
                runCurrent()
                assertTrue(pump.send(initial.copy(id = 90)))
                runCurrent()
                hub.awaitIdle()
                assertTrue("delivered certificate must release live progress without another request; ${tape.progress()}", live.isCompleted)
                assertFalse("the catch-up remains required", tape.closeReady(owner))
                assertTrue(pump.send(tail.copy(id = 91)))
                runCurrent()
                transport.close()
                runCurrent()
                hub.assertComplete()
            } finally {
                hub.close()
                RelayLog.sink = savedLogSink
            }
        }

    @Test fun phoneRestartWaitsForCurrentQueriesWithoutWaitingForFuturePhonesOrPeers() =
        runTest {
            val old = "phone/host/0"
            val next = "phone/host/1"
            val peer = "peer/host/0"
            val query = frame(1, "thread_catch_up", """{"conversation_id":"chat","epoch":"epoch","version":20}""")
            val reply = frame(2, "thread_items", reply = 1)
            val tape =
                RecordedSuiteTape(
                    listOf(
                        SuiteStep("connect", peer),
                        SuiteStep("open", peer),
                        SuiteStep("connect", old),
                        SuiteStep("open", old),
                        SuiteStep("client", old, query),
                        SuiteStep("daemon", old, reply, delayMs = 1000),
                        SuiteStep("close", old),
                        SuiteStep("inbound_end", old),
                        SuiteStep("connect", next),
                        SuiteStep("open", next),
                        SuiteStep("client", next, query),
                        SuiteStep("daemon", next, reply),
                        SuiteStep("close", next),
                        SuiteStep("inbound_end", next),
                        SuiteStep("client", peer, frame(1, "list_conversations")),
                        SuiteStep("close", peer),
                        SuiteStep("inbound_end", peer),
                    ),
                    literalProducerIdentities = true,
                )
            val hub = SuiteConnections.replaying(backgroundScope, tape)
            try {
                val peerTransport = hub.transport(peer) { error("Live transport started") }
                val peerPump = hub.pump(peerTransport) { error("Live pump started") }
                peerPump.start()
                peerTransport.connect()
                val first = hub.transport(old) { error("Live transport started") }
                val firstPump = hub.pump(first) { error("Live pump started") }
                val received = CompletableDeferred<Envelope>()
                val firstCollector = backgroundScope.launch { firstPump.inbound.collect { received.complete(it) } }
                firstPump.start()
                first.connect()
                runCurrent()
                val restarting =
                    async {
                        hub.beforePhoneRestart()
                        assertTrue("the old phone consumer must survive its close fence", firstCollector.isActive)
                        first.close()
                    }
                runCurrent()
                assertFalse("restart released before its required catch-up; ${tape.progress()}", restarting.isCompleted)
                assertTrue(firstPump.send(query.copy(id = 90)))
                runCurrent()
                assertFalse("restart released before queued reply delivery", restarting.isCompleted)
                advanceTimeBy(200)
                runCurrent()
                restarting.await()
                assertEquals(reply.copy(inReplyTo = 90), received.await())
                runCurrent()

                // A second restart must select the replacement, without reusing the retired owner.
                val replacement = hub.transport(next) { error("Live transport started") }
                val pump = hub.pump(replacement) { error("Live pump started") }
                val replacementReply = CompletableDeferred<Envelope>()
                val replacementCollector = backgroundScope.launch { pump.inbound.collect { replacementReply.complete(it) } }
                pump.start()
                replacement.connect()
                runCurrent()
                val secondRestart = async { hub.beforePhoneRestart() }
                runCurrent()
                assertFalse(secondRestart.isCompleted)
                assertTrue(pump.send(query.copy(id = 91)))
                advanceTimeBy(20)
                runCurrent()
                secondRestart.await()
                assertEquals(reply.copy(inReplyTo = 91), replacementReply.await())
                assertTrue("the replacement consumer must survive its close fence", replacementCollector.isActive)
                replacement.close()
                assertTrue(peerPump.send(frame(90, "list_conversations")))
                peerTransport.close()
                runCurrent()
                hub.assertComplete()
            } finally {
                hub.close()
            }
        }

    @Test fun phoneRestartCannotOmitARequiredQuery() =
        runTest {
            val owner = "phone/host/0"
            val query = frame(1, "thread_catch_up", """{"conversation_id":"chat","epoch":"epoch","version":20}""")
            val tape =
                RecordedSuiteTape(
                    listOf(
                        SuiteStep("connect", owner),
                        SuiteStep("open", owner),
                        SuiteStep("client", owner, query),
                        SuiteStep("close", owner),
                    ),
                )
            val hub = SuiteConnections.replaying(backgroundScope, tape)
            try {
                val transport = hub.transport(owner) { error("Live transport started") }
                val pump = hub.pump(transport) { error("Live pump started") }
                pump.start()
                transport.connect()
                runCurrent()
                val restarting = async { runCatching { hub.beforePhoneRestart() } }
                advanceTimeBy(30001)
                runCurrent()
                assertTrue(restarting.await().exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
                assertThrows(IllegalStateException::class.java) { hub.assertComplete() }
            } finally {
                hub.close()
            }
        }

    @Test fun phoneRestartStillRejectsAChangedQuery() =
        runTest {
            val owner = "phone/host/0"
            val query = frame(1, "thread_catch_up", """{"conversation_id":"chat","epoch":"epoch","version":20}""")
            val hub =
                SuiteConnections.replaying(
                    backgroundScope,
                    RecordedSuiteTape(
                        listOf(
                            SuiteStep("connect", owner),
                            SuiteStep("open", owner),
                            SuiteStep("client", owner, query),
                            SuiteStep("close", owner),
                        ),
                        literalProducerIdentities = true,
                    ),
                )
            try {
                val transport = hub.transport(owner) { error("Live transport started") }
                val pump = hub.pump(transport) { error("Live pump started") }
                pump.start()
                transport.connect()
                runCurrent()
                val restarting = async { runCatching { hub.beforePhoneRestart() } }
                runCurrent()
                assertThrows(IllegalStateException::class.java) {
                    pump.send(frame(90, "thread_catch_up", """{"conversation_id":"chat","epoch":"epoch","version":21}"""))
                }
                advanceTimeBy(20)
                runCurrent()
                assertTrue(restarting.await().exceptionOrNull() is IllegalStateException)
                assertThrows(IllegalStateException::class.java) { hub.assertComplete() }
            } finally {
                hub.close()
            }
        }

    @Test fun anUpcomingDisconnectCannotHideAnUnexpectedRequestOnAnOpenConnection() =
        runTest {
            val owner = "phone/host/0"
            val hub =
                SuiteConnections.replaying(
                    backgroundScope,
                    RecordedSuiteTape(
                        listOf(
                            SuiteStep("connect", owner),
                            SuiteStep("open", owner),
                            SuiteStep("transport_down", owner, delayMs = 1000),
                            SuiteStep("close", owner),
                            SuiteStep("inbound_end", owner),
                        ),
                    ),
                )
            try {
                val transport = hub.transport(owner) { error("Live transport started") }
                val pump = hub.pump(transport) { error("Live pump started") }
                pump.start()
                transport.connect()
                runCurrent()
                assertTrue(pump.state.value is PumpState.Open)
                assertThrows(IllegalStateException::class.java) {
                    pump.send(frame(90, "request_session_settings", """{"conversation_id":"chat"}"""))
                }
            } finally {
                hub.close()
            }
        }

    @Test fun aTransportThatWentDownRefusesLateSettingsBeforeTheSupervisorClosesIt() =
        runTest {
            val owner = "phone/host/0"
            val hub =
                SuiteConnections.replaying(
                    backgroundScope,
                    RecordedSuiteTape(
                        listOf(
                            SuiteStep("connect", owner),
                            SuiteStep("open", owner),
                            SuiteStep("transport_down", owner),
                            SuiteStep("close", owner),
                            SuiteStep("inbound_end", owner),
                        ),
                    ),
                )
            try {
                val transport = hub.transport(owner) { error("Live transport started") }
                val pump = hub.pump(transport) { error("Live pump started") }
                pump.start()
                transport.connect()
                runCurrent()
                val down = async { transport.events.first { it is TransportEvent.Down } }
                runCurrent()
                assertTrue(down.await() is TransportEvent.Down)
                assertTrue(pump.state.value is PumpState.Open)
                assertFalse(pump.send(frame(90, "request_session_settings", """{"conversation_id":"chat"}""")))
                transport.close()
                runCurrent()
                hub.assertComplete()
            } finally {
                hub.close()
            }
        }

    @Test fun aRecordedRefusalAfterTransportDownStillMatchesItsExactRequest() =
        runTest {
            val owner = "phone/host/0"
            val request = frame(1, "request_session_settings", """{"conversation_id":"chat"}""")
            val hub =
                SuiteConnections.replaying(
                    backgroundScope,
                    RecordedSuiteTape(
                        listOf(
                            SuiteStep("connect", owner),
                            SuiteStep("open", owner),
                            SuiteStep("transport_down", owner),
                            SuiteStep("client", owner, request, accepted = false),
                            SuiteStep("close", owner),
                            SuiteStep("inbound_end", owner),
                        ),
                        literalProducerIdentities = true,
                    ),
                )
            try {
                val transport = hub.transport(owner) { error("Live transport started") }
                val pump = hub.pump(transport) { error("Live pump started") }
                pump.start()
                transport.connect()
                runCurrent()
                assertFalse(pump.send(request.copy(id = 90)))
                transport.close()
                runCurrent()
                hub.assertComplete()
            } finally {
                hub.close()
            }
        }

    @Test fun phoneTeardownWaitsForACapturedReplacementAndItsQueriesEvenIfItClosesDuringTheWait() =
        runTest {
            val old = "phone/host/0"
            val next = "phone/host/1"
            val entries =
                listOf(
                    SuiteStep("connect", old),
                    SuiteStep("open", old),
                    SuiteStep("close", old),
                    SuiteStep("inbound_end", old),
                    SuiteStep("connect", next),
                    SuiteStep("open", next),
                    SuiteStep("client", next, frame(1, "list_conversations")),
                    SuiteStep("daemon", next, frame(2, "conversations", reply = 1)),
                    SuiteStep("close", next),
                    SuiteStep("inbound_end", next),
                    SuiteStep("client", "control", frame(1, "test-control:checkpoint", """{"body":"scenario-complete"}""")),
                )
            val hub = SuiteConnections.replaying(backgroundScope, RecordedSuiteTape(entries))
            try {
                val first = hub.transport(old) { error("Live transport started") }
                val firstPump = hub.pump(first) { error("Live pump started") }
                firstPump.start()
                first.connect()
                runCurrent()
                first.close()
                runCurrent()
                val closing = async { hub.beforePhoneTeardown() }
                runCurrent()
                assertFalse(closing.isCompleted)
                val replacement = hub.transport(next) { error("Live transport started") }
                val pump = hub.pump(replacement) { error("Live pump started") }
                pump.start()
                var received: Envelope? = null
                backgroundScope.launch { pump.inbound.collect { received = it } }
                replacement.connect()
                runCurrent()
                advanceTimeBy(10)
                runCurrent()
                assertFalse(closing.isCompleted)
                assertTrue(pump.send(frame(90, "list_conversations")))
                pump.close()
                advanceTimeBy(20)
                runCurrent()
                closing.await()
                assertEquals(90L, received?.inReplyTo)
                hub.checkpoint("scenario-complete")
                runCurrent()
                hub.assertComplete()
            } finally {
                hub.close()
            }
        }

    @Test fun phoneTeardownDoesNotWaitForRegistryDisposalAfterTheCompletionCheckpoint() =
        runTest {
            val owner = "phone/host/0"
            val hub =
                SuiteConnections.replaying(
                    backgroundScope,
                    RecordedSuiteTape(
                        listOf(
                            SuiteStep("connect", owner),
                            SuiteStep("open", owner),
                            SuiteStep("client", "control", frame(1, "test-control:checkpoint", """{"body":"scenario-complete"}""")),
                            SuiteStep("close", owner),
                        ),
                    ),
                )
            try {
                val transport = hub.transport(owner) { error("Live transport started") }
                val pump = hub.pump(transport) { error("Live pump started") }
                pump.start()
                transport.connect()
                runCurrent()
                val beforeTeardown = async { hub.beforePhoneTeardown() }
                runCurrent()
                assertTrue(beforeTeardown.isCompleted)
                beforeTeardown.await()
                hub.checkpoint("scenario-complete")
                pump.close()
                runCurrent()
                hub.assertComplete()
            } finally {
                hub.close()
            }
        }

    @Test fun aRacingCloseCannotHideTheCapturedRefusedAttachmentRequest() =
        runTest {
            val request = frame(1, "request_attachment", """{"conversation_id":"chat","attachment_id":"file"}""")
            val hub =
                SuiteConnections.replaying(
                    backgroundScope,
                    RecordedSuiteTape(
                        listOf(
                            SuiteStep("connect", "phone"),
                            SuiteStep("open", "phone"),
                            SuiteStep("close", "phone"),
                            SuiteStep("client", "phone", request, accepted = false),
                            SuiteStep("inbound_end", "phone"),
                        ),
                        literalProducerIdentities = true,
                    ),
                )
            val transport = hub.transport("phone") { error("Live transport started") }
            transport.connect()
            val pump = hub.pump(transport) { error("Live pump started") }
            pump.start()
            runCurrent()
            val started = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            var closer: Thread? = null
            try {
                synchronized(hub) {
                    closer =
                        thread {
                            started.countDown()
                            try {
                                transport.close()
                            } catch (error: Throwable) {
                                failure.set(error)
                            }
                        }
                    check(started.await(5, TimeUnit.SECONDS))
                    val worker = checkNotNull(closer)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                    assertEquals(Thread.State.BLOCKED, worker.state)
                    // The supervisor and coordinator can both close this connection. The second
                    // close must finish the tape transition before the cut retrieval sends.
                    pump.close()
                    assertFalse(pump.send(request.copy(id = 90)))
                }
                checkNotNull(closer).join(5000)
                assertFalse(checkNotNull(closer).isAlive)
                assertEquals(null, failure.get())
                runCurrent()
                hub.assertComplete()
            } finally {
                closer?.join(5000)
                hub.close()
            }
        }

    @Test fun aCapturedRefusalStillRejectsAChangedAttachmentAfterClose() =
        runTest {
            val hub =
                SuiteConnections.replaying(
                    backgroundScope,
                    RecordedSuiteTape(
                        listOf(
                            SuiteStep("connect", "phone"),
                            SuiteStep("open", "phone"),
                            SuiteStep("close", "phone"),
                            SuiteStep("client", "phone", frame(1, "request_attachment", """{"attachment_id":"file"}"""), accepted = false),
                        ),
                        literalProducerIdentities = true,
                    ),
                )
            try {
                val transport = hub.transport("phone") { error("Live transport started") }
                transport.connect()
                val pump = hub.pump(transport) { error("Live pump started") }
                pump.start()
                runCurrent()
                pump.close()
                assertThrows(IllegalStateException::class.java) {
                    pump.send(frame(90, "request_attachment", """{"attachment_id":"wrong"}"""))
                }
                assertThrows(IllegalStateException::class.java) { hub.assertComplete() }
            } finally {
                hub.close()
            }
        }

    @Test fun aSendWaitingBehindCloseCannotUseAnEarlierOpenState() =
        runTest {
            val hub =
                SuiteConnections.replaying(
                    backgroundScope,
                    RecordedSuiteTape(listOf(SuiteStep("connect", "phone"), SuiteStep("open", "phone"), SuiteStep("close", "phone"))),
                )
            val transport = hub.transport("phone") { error("Live transport started") }
            transport.connect()
            val pump = hub.pump(transport) { error("Live pump started") }
            pump.start()
            runCurrent()
            val started = CountDownLatch(1)
            val result = AtomicReference<Boolean?>()
            val failure = AtomicReference<Throwable?>()
            val sender: Thread
            synchronized(hub) {
                sender =
                    thread {
                        started.countDown()
                        try {
                            result.set(pump.send(frame(90, "attachment_chunk")))
                        } catch (error: Throwable) {
                            failure.set(error)
                        }
                    }
                check(started.await(1, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                while (sender.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, sender.state)
                pump.close()
            }
            sender.join(1000)
            assertFalse(sender.isAlive)
            assertEquals(null, failure.get())
            assertEquals(false, result.get())
            hub.assertComplete()
            hub.close()
        }

    @Test fun aTestDrivenCloseWaitsForRecordedRequestsAndTheirDeliveryBeforeCancellingThePump() =
        runTest {
            val registration =
                frame(1, "register_push_token", """{"token":"recorded-push-device-address","platform":"fcm","device_name":"phone"}""")
            val entries =
                listOf(
                    SuiteStep("connect", "phone"),
                    SuiteStep("open", "phone"),
                    SuiteStep("client", "phone", registration),
                    SuiteStep("daemon", "phone", frame(2, "ack", reply = 1), delayMs = 100),
                    SuiteStep("close", "phone"),
                )
            val hub = SuiteConnections.replaying(backgroundScope, RecordedSuiteTape(entries))
            val transport = hub.transport("phone") { error("Live transport started") }
            transport.connect()
            val pump = hub.pump(transport) { error("Live pump started") }
            pump.start()
            runCurrent()
            var received: Envelope? = null
            backgroundScope.launch { pump.inbound.collect { received = it } }
            val closing = async { hub.beforeClose("phone") }
            runCurrent()
            assertFalse(closing.isCompleted)
            pump.send(registration.copy(id = 90))
            runCurrent()
            assertFalse(closing.isCompleted)
            advanceTimeBy(120)
            runCurrent()
            closing.await()
            assertEquals(90L, received?.inReplyTo)
            pump.close()
            hub.assertComplete()
            hub.close()
        }

    @Test fun observationCheckpointWaitsForEarlierRequestsAndTheirScheduledDelivery() =
        runTest {
            val entries =
                listOf(
                    SuiteStep("connect", "phone"),
                    SuiteStep("open", "phone"),
                    SuiteStep("client", "phone", frame(1, "request_model_list")),
                    SuiteStep("daemon", "phone", frame(2, "model_list", reply = 1), delayMs = 100),
                    SuiteStep("client", "control", frame(1, "test-control:checkpoint", """{"body":"reply-visible"}""")),
                    SuiteStep("close", "phone"),
                )
            val hub = SuiteConnections.replaying(backgroundScope, RecordedSuiteTape(entries))
            val transport = hub.transport("phone") { error("Live transport started") }
            transport.connect()
            val pump = hub.pump(transport) { error("Live pump started") }
            pump.start()
            runCurrent()
            val checkpoint = async { hub.checkpoint("reply-visible") }
            runCurrent()
            assertFalse(checkpoint.isCompleted)
            pump.send(frame(90, "request_model_list"))
            runCurrent()
            assertFalse(checkpoint.isCompleted)
            advanceTimeBy(120)
            runCurrent()
            checkpoint.await()
            assertEquals("model_list", pump.inbound.first().type)
            pump.close()
            hub.assertComplete()
            hub.close()
        }

    @Test fun negativeObservationWaitsForRequestsAndDeliveryButNotTheCapturedTimeout() =
        runTest {
            val entries =
                listOf(
                    SuiteStep("connect", "phone"),
                    SuiteStep("open", "phone"),
                    SuiteStep("client", "phone", frame(1, "request_model_list")),
                    SuiteStep("daemon", "phone", frame(2, "model_list", reply = 1), delayMs = 100),
                    SuiteStep("client", "control", frame(1, "test-control:negative-absence", """{"body":"never-word"}"""), delayMs = 83417),
                    SuiteStep("close", "phone"),
                )
            val hub = SuiteConnections.replaying(backgroundScope, RecordedSuiteTape(entries))
            val transport = hub.transport("phone") { error("Live transport started") }
            transport.connect()
            val pump = hub.pump(transport) { error("Live pump started") }
            pump.start()
            runCurrent()
            val observation = async { hub.beforeNegativeObservation("never-word") }
            runCurrent()
            assertFalse(observation.isCompleted)
            pump.send(frame(90, "request_model_list"))
            runCurrent()
            assertFalse(observation.isCompleted)
            advanceTimeBy(120)
            runCurrent()
            observation.await()
            assertEquals("model_list", pump.inbound.first().type)
            assertEquals(120L, testScheduler.currentTime)
            hub.control("negative-absence", "never-word") { error("Live control started") }
            pump.close()
            hub.assertComplete()
            hub.close()
        }

    private fun frame(
        id: Long,
        type: String,
        payload: String = "{}",
        reply: Long? = null,
    ) = Envelope(id, type, "2026-10-16T00:00:00Z", MobileJson.parseToJsonElement(payload), reply)

    @Test fun replayRoutesPeerResultsToTheRealPhoneConnectionWithoutCreatingLiveObjects() =
        runTest {
            val entries =
                listOf(
                    SuiteStep("connect", "phone"),
                    SuiteStep("open", "phone", capabilities = setOf("interactive")),
                    SuiteStep("connect", "peer"),
                    SuiteStep("open", "peer", capabilities = setOf("interactive")),
                    SuiteStep("client", "peer", frame(1, "send_message")),
                    SuiteStep("daemon", "phone", frame(2, "message", """{"text":"recorded reply"}""")),
                    SuiteStep("daemon", "peer", frame(2, "ack", reply = 1)),
                    SuiteStep("close", "peer"),
                    SuiteStep("close", "phone"),
                )
            val hub = SuiteConnections.replaying(backgroundScope, RecordedSuiteTape(entries))
            val phoneTransport = hub.transport("phone") { error("Live transport started") }
            phoneTransport.connect()
            assertEquals(TransportEvent.Up, phoneTransport.events.first())
            val phone = hub.pump(phoneTransport) { error("Live pump started") }
            phone.start()
            runCurrent()
            assertEquals(setOf("interactive"), (phone.state.value as PumpState.Open).capabilities)
            val peerTransport = hub.transport("peer") { error("Live transport started") }
            peerTransport.connect()
            val peer = hub.pump(peerTransport) { error("Live pump started") }
            peer.start()
            runCurrent()
            val received = async { phone.inbound.first() }
            assertEquals(true, peer.send(frame(90, "send_message")))
            runCurrent()
            assertEquals("message", received.await().type)
            assertEquals(90L, peer.inbound.first().inReplyTo)
            peer.close()
            phone.close()
            hub.assertComplete()
            hub.close()
        }

    @Test fun aClosedPumpRefusesFurtherChunksWithoutSendingThemToTheRecordedDaemon() =
        runTest {
            val entries =
                listOf(
                    SuiteStep("connect", "phone"),
                    SuiteStep("open", "phone"),
                    SuiteStep("close", "phone"),
                )
            val hub = SuiteConnections.replaying(backgroundScope, RecordedSuiteTape(entries))
            val transport = hub.transport("phone") { error("Live transport started") }
            transport.connect()
            val pump = hub.pump(transport) { error("Live pump started") }
            pump.start()
            runCurrent()
            pump.close()
            assertFalse(pump.send(frame(1, "attachment_chunk", """{"index":2}""")))
            hub.assertComplete()
            hub.close()
        }

    @Test fun aCapturedRejectedRequestAfterCloseStillRequiresItsCompletePayload() =
        runTest {
            for (changed in listOf(false, true)) {
                val expected = frame(1, "request_attachment", """{"attachment_id":"file"}""")
                val entries =
                    listOf(
                        SuiteStep("connect", "phone"),
                        SuiteStep("open", "phone"),
                        SuiteStep("close", "phone"),
                        SuiteStep("client", "phone", expected, accepted = false),
                    )
                val tape = RecordedSuiteTape(entries)
                val hub = SuiteConnections.replaying(backgroundScope, tape)
                val transport = hub.transport("phone") { error("Live transport started") }
                transport.connect()
                val pump = hub.pump(transport) { error("Live pump started") }
                pump.start()
                runCurrent()
                pump.close()
                if (changed) {
                    assertThrows(
                        IllegalStateException::class.java,
                    ) { pump.send(frame(80, "request_attachment", """{"attachment_id":"other"}""")) }
                    assertThrows(IllegalStateException::class.java) { hub.assertComplete() }
                } else {
                    assertFalse(pump.send(expected.copy(id = 80, payload = tape.arguments(expected.payload.jsonObject))))
                    hub.assertComplete()
                }
                hub.close()
            }
        }

    @Test fun replayScalesCapturedGapsWithAFloorThatNeverExceedsTheCapture() {
        assertEquals(0L, SuiteConnections.replayDelayMs(0))
        assertEquals(1L, SuiteConnections.replayDelayMs(1))
        assertEquals(20L, SuiteConnections.replayDelayMs(20))
        assertEquals(20L, SuiteConnections.replayDelayMs(150))
        assertEquals(50L, SuiteConnections.replayDelayMs(500))
        assertEquals(2697L, SuiteConnections.replayDelayMs(26975))
    }

    @Test fun everyOriginalSpeedScenarioNamesAShippedRecording() {
        assertTrue(SuiteConnections.ORIGINAL_SPEED_SCENARIOS.isNotEmpty())
        SuiteConnections.ORIGINAL_SPEED_SCENARIOS.forEach { scenario ->
            assertTrue(scenario, java.io.File("src/androidTest/assets/recorded/suite/$scenario.json").isFile)
        }
    }

    @Test fun anOriginalSpeedScenarioKeepsItsExactRecordedGaps() =
        runTest {
            val entries =
                listOf(
                    SuiteStep("connect", "phone"),
                    SuiteStep("open", "phone"),
                    SuiteStep("client", "phone", frame(1, "send_message")),
                    SuiteStep("daemon", "phone", frame(2, "turn_state")),
                    SuiteStep("daemon", "phone", frame(3, "turn_end"), delayMs = 500),
                    SuiteStep("close", "phone"),
                )
            val scenario = SuiteConnections.ORIGINAL_SPEED_SCENARIOS.first()
            val hub =
                SuiteConnections.replaying(
                    backgroundScope,
                    RecordedSuiteTape(entries),
                    originalSpeed = scenario in SuiteConnections.ORIGINAL_SPEED_SCENARIOS,
                )
            val transport = hub.transport("phone") { error("Live transport started") }
            transport.connect()
            val pump = hub.pump(transport) { error("Live pump started") }
            pump.start()
            runCurrent()
            pump.send(frame(20, "send_message"))
            runCurrent()
            assertEquals("turn_state", pump.inbound.first().type)
            val finished = async { pump.inbound.first() }
            advanceTimeBy(499)
            runCurrent()
            assertFalse(finished.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("turn_end", finished.await().type)
            pump.close()
            hub.assertComplete()
            hub.close()
        }

    @Test fun recordedCadencePreservesIntermediateObservations() =
        runTest {
            val entries =
                listOf(
                    SuiteStep("connect", "phone"),
                    SuiteStep("open", "phone"),
                    SuiteStep("client", "phone", frame(1, "send_message")),
                    SuiteStep("daemon", "phone", frame(2, "turn_state")),
                    SuiteStep("daemon", "phone", frame(3, "turn_end"), delayMs = 500),
                    SuiteStep("close", "phone"),
                )
            val hub = SuiteConnections.replaying(backgroundScope, RecordedSuiteTape(entries))
            val transport = hub.transport("phone") { error("Live transport started") }
            transport.connect()
            val pump = hub.pump(transport) { error("Live pump started") }
            pump.start()
            runCurrent()
            pump.send(frame(20, "send_message"))
            runCurrent()
            assertEquals("turn_state", pump.inbound.first().type)
            val finished = async { pump.inbound.first() }
            // The captured 500 ms gap replays scaled to 50 ms; order and the intermediate state remain.
            advanceTimeBy(49)
            runCurrent()
            assertFalse(finished.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("turn_end", finished.await().type)
            pump.close()
            hub.assertComplete()
            hub.close()
        }

    @Test fun anUnexpectedControlActionFailsTheWholeReplay() =
        runTest {
            val tape = RecordedSuiteTape(listOf(SuiteStep("client", "control", frame(1, "test-control:test-begin"))))
            val hub = SuiteConnections.replaying(backgroundScope, tape)
            assertThrows(IllegalStateException::class.java) { hub.control("stop", "") { error("Real control invoked") } }
            assertThrows(IllegalStateException::class.java) { hub.assertComplete() }
            hub.close()
        }

    @Test fun controlResponsesReplayWithoutCallingTheHostAndStillRequireTheExactRequest() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val body = kotlinx.serialization.json.JsonObject(emptyMap())
        val response = MobileJson.parseToJsonElement("""{"state":"held"}""").jsonObject
        val recording = SuiteConnections.recording(scope)
        assertEquals(response, recording.exchangeControl("task/status", body) { response })
        val steps = recording.completedCapture(true)
        recording.close()
        val replay = SuiteConnections.replaying(scope, RecordedSuiteTape(steps))
        assertEquals(response, replay.exchangeControl("task/status", body) { error("Real host invoked during replay") })
        kotlinx.coroutines.runBlocking { replay.awaitIdle() }
        replay.assertComplete()
        replay.close()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun observingALivePumpCannotDelayItsPublishedConnectionState() =
        runTest {
            val published = kotlinx.coroutines.flow.MutableStateFlow<PumpState>(PumpState.Handshaking)
            val delegate =
                object : de.pyryco.mobile.data.repository.ManagedSessionPump {
                    override val state = published
                    override val inbound = kotlinx.coroutines.flow.emptyFlow<Envelope>()

                    override fun start() { }

                    override fun send(envelope: Envelope) = true

                    override fun close() {
                        published.value = PumpState.Closed(null)
                    }
                }
            val transport =
                object : de.pyryco.mobile.data.network.RelayTransport {
                    override val inbound = kotlinx.coroutines.flow.emptyFlow<de.pyryco.mobile.data.network.InnerFrameV2>()
                    override val events = kotlinx.coroutines.flow.emptyFlow<de.pyryco.mobile.data.network.TransportEvent>()

                    override fun connect() { }

                    override fun close() { }

                    override fun send(frame: de.pyryco.mobile.data.network.InnerFrameV2) = true
                }
            val hub = SuiteConnections.recording(backgroundScope)
            val observed = hub.pump(hub.transport("phone") { transport }) { delegate }
            val opened = PumpState.Open("connection", setOf("interactive"))
            published.value = opened
            assertEquals(opened, observed.state.value) // No scheduler turn may be required by the observation tap.
            observed.close()
            hub.close()
        }

    @Test fun aRemotePumpEndClosesItsTransportBeforeThePeerRedials() =
        runTest {
            val entries =
                listOf(
                    SuiteStep("connect", "peer/host/0"),
                    SuiteStep("open", "peer/host/0"),
                    SuiteStep("client", "control", frame(1, "test-control:stop", """{"body":""}""")),
                    SuiteStep("down", "peer/host/0"),
                    SuiteStep("close", "peer/host/0"),
                    SuiteStep("inbound_end", "peer/host/0"),
                    SuiteStep("connect", "peer/host/1"),
                    SuiteStep("open", "peer/host/1"),
                    SuiteStep("close", "peer/host/1"),
                )
            val hub = SuiteConnections.replaying(backgroundScope, RecordedSuiteTape(entries))
            val first = hub.transport("peer/host/0") { error("Live transport started") }
            first.connect()
            val pump = hub.pump(first) { error("Live pump started") }
            pump.start()
            runCurrent()
            hub.control("stop", "") { error("Live control started") }
            runCurrent()
            assertEquals(PumpState.Closed(null), pump.state.value)
            val second = hub.transport("peer/host/1") { error("Live transport started") }
            second.connect()
            val next = hub.pump(second) { error("Live pump started") }
            next.start()
            runCurrent()
            assertEquals(true, next.state.value is PumpState.Open)
            next.close()
            hub.assertComplete()
            hub.close()
        }

    @Test fun aRejectedCloseStillClosesThePumpAndCannotMaskTheScenarioFailure() =
        runTest {
            val owner = "phone/host/0"
            val rows =
                listOf(
                    SuiteStep("connect", owner),
                    SuiteStep("open", owner),
                    SuiteStep("client", owner, frame(1, "send_message")),
                    SuiteStep("close", owner),
                )
            val hub = SuiteConnections.replaying(backgroundScope, RecordedSuiteTape(rows))
            val transport = hub.transport(owner) { error("Live transport started") }
            transport.connect()
            val pump = hub.pump(transport) { error("Live pump started") }
            pump.start()
            runCurrent()
            pump.close()
            assertEquals(PumpState.Closed(null), pump.state.value)
            assertThrows(IllegalStateException::class.java) { hub.assertComplete() }
            hub.close()
        }
}
