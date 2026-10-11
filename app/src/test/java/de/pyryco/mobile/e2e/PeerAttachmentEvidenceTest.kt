package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PeerAttachmentEvidenceTest {
    @Test
    fun distinctDeadlinesRetainTheTimeoutAndAttributeTheOperation() =
        runTest {
            val boundaries =
                listOf(
                    PeerAttachmentStage.PeerOpen,
                    PeerAttachmentStage.InitialPhoneConnection,
                    PeerAttachmentStage.ChatCreation,
                    PeerAttachmentStage.ChatRename,
                    PeerAttachmentStage.SendAcknowledgement,
                    PeerAttachmentStage.PostRestartPhoneConnection,
                )
            for (stage in boundaries) {
                val records = mutableListOf<String>()
                val evidence = PeerAttachmentEvidence({ emptyMap() }, { records += it }, { testScheduler.currentTime })
                val start = testScheduler.currentTime
                var original: Throwable? = null
                var calls = 0
                val failure =
                    runCatching {
                        evidence.step(stage) {
                            calls++
                            try {
                                withTimeout(30_000) { awaitCancellation() }
                            } catch (error: Throwable) {
                                original = error
                                throw error
                            }
                        }
                    }.exceptionOrNull()
                assertTrue(failure is TimeoutCancellationException)
                assertSame(original, failure)
                assertFalse(failure?.message.orEmpty().contains(stage.name))
                assertEquals(30_000, testScheduler.currentTime - start)
                assertEquals(1, calls)
                assertEquals("missing attribution for $stage", 2, records.size)
                assertTrue(records.first().contains("stage=${stage.name} phase=started"))
                assertTrue(records.last().contains("stage=${stage.name} phase=failed"))
            }
        }

    @Test
    fun cancellationIsRethrownBeforeCleanup() =
        runTest {
            val records = mutableListOf<String>()
            val evidence = PeerAttachmentEvidence({ emptyMap() }, { records += it })
            val cancellation = CancellationException("private cancellation reason")
            var observed: Throwable? = null
            var original: Throwable? = null
            val job =
                launch {
                    try {
                        evidence.step(PeerAttachmentStage.SendAcknowledgement) {
                            try {
                                awaitCancellation()
                            } catch (failure: Throwable) {
                                original = failure
                                throw failure
                            }
                        }
                    } catch (failure: Throwable) {
                        observed = failure
                        throw failure
                    } finally {
                        evidence.record(PeerAttachmentStage.BeforeCleanup, "observed")
                    }
                }
            runCurrent()
            job.cancel(cancellation)
            job.join()
            assertSame(original, observed)
            assertTrue(records[1].contains("phase=failed"))
            assertTrue(records.last().contains("stage=BeforeCleanup"))
            assertFalse(records.joinToString().contains("private cancellation"))
        }

    @Test
    fun observationFailuresRemainUnknownAndPreserveTheFailure() {
        for (broken in listOf("snapshot", "clock", "sink")) {
            val records = mutableListOf<String>()
            var sinkCalls = 0
            val observerError = IllegalStateException("secret observer detail")
            val evidence =
                PeerAttachmentEvidence(
                    snapshot = { if (broken == "snapshot") throw observerError else emptyMap() },
                    sink = { if (broken == "sink" && ++sinkCalls % 2 == 1) throw observerError else records += it },
                    clock = { if (broken == "clock") throw observerError else 1L },
                )
            val original = AssertionError("original scenario failure")
            assertSame(original, runCatching { evidence.step(PeerAttachmentStage.Upload) { throw original } }.exceptionOrNull())
            assertEquals(2, records.size)
            assertTrue(records.all { "observation=unknown" in it })
            assertTrue(records.last().contains("phase=failed"))
            assertFalse(records.joinToString().contains("secret observer detail"))
        }
    }

    @Test
    fun repeatedStagesRunOnceAndUseFreshSnapshots() {
        val records = mutableListOf<String>()
        var links = 1
        var calls = 0
        val evidence = PeerAttachmentEvidence({ mapOf("peer_links" to links.toString()) }, { records += it }, { 1L })
        repeat(2) {
            assertEquals(
                7,
                evidence.step(PeerAttachmentStage.PeerOpen) {
                    calls++
                    links++
                    7
                },
            )
        }
        assertEquals(2, calls)
        assertEquals(4, records.size)
        assertTrue(records[0].contains("peer_links=1"))
        assertTrue(records[1].contains("peer_links=2"))
        assertTrue(records[3].contains("peer_links=3"))
    }

    @Test
    fun failureAndFinalSnapshotPrecedeCleanup() {
        val order = mutableListOf<String>()
        var live = true
        val evidence =
            PeerAttachmentEvidence({
                check(live)
                mapOf("phone_ready" to "true")
            }, { order += it })
        val original = AssertionError()
        val failure =
            runCatching {
                try {
                    evidence.step(PeerAttachmentStage.HistoryRetrieval) { throw original }
                } finally {
                    evidence.record(PeerAttachmentStage.BeforeCleanup, "observed")
                    live = false
                    order += "cleanup"
                }
            }.exceptionOrNull()
        assertSame(original, failure)
        assertEquals(4, order.size)
        assertTrue(order[1].contains("phase=failed"))
        assertTrue(order[2].contains("phone_ready=true"))
        assertEquals("cleanup", order.last())
    }

    @Test
    fun hostileSnapshotFieldsCannotEmitCredentialsOrDynamicText() {
        val records = mutableListOf<String>()
        val evidence =
            PeerAttachmentEvidence({
                mapOf(
                    "chat_x" to "07acd65b-f57e-4a6e-8a23-a18879b3a9bb",
                    "attachment_id" to "secret-token",
                    "phone_relay" to "Connected\nsecret-key",
                    "peer_links" to "-1",
                    "selected_host_sha256" to "a".repeat(64),
                    "token" to "secret-token",
                    "filename" to "private-document.txt",
                )
            }, { records += it }, { 1L })
        evidence.record(PeerAttachmentStage.BeforeCleanup, "observed")
        val record = records.single()
        assertTrue(record.contains("chat_x=07acd65b-f57e-4a6e-8a23-a18879b3a9bb"))
        assertTrue(record.contains("selected_host_sha256=${"a".repeat(64)}"))
        for (secret in listOf("secret-token", "secret-key", "private-document", "peer_links=-1")) {
            assertFalse(record.contains(secret))
        }
        assertTrue(record.contains("attachment_id=unknown"))
        assertTrue(record.contains("phone_relay=unknown"))
    }

    @Test
    fun frameCountsAreBoundedAndKeepGlobalRepliesSeparateFromChatFrames() {
        val chat = "07acd65b-f57e-4a6e-8a23-a18879b3a9bb"

        fun frame(
            type: String,
            id: String? = null,
        ) = Envelope(
            id = 1,
            type = type,
            ts = "",
            payload = JsonObject(id?.let { mapOf("conversation_id" to JsonPrimitive(it)) }.orEmpty()),
        )
        val frames =
            List(5000) { frame("turn_end", chat) } +
                listOf(
                    frame("attachment_stored"),
                    frame("ack"),
                    frame("history_page", chat),
                    frame("turn_end", "other"),
                )
        val counts = peerAttachmentFrameEvidence(frames, chat, "session open (link 2, replaced 1×)")
        assertEquals("5004", counts["peer_frames"])
        assertEquals("4096", counts["peer_sampled"])
        assertEquals("4093", counts["peer_chat_frames"])
        assertEquals("4092", counts["peer_terminal"])
        assertEquals("1", counts["peer_stored"])
        assertEquals("1", counts["peer_ack"])
        assertEquals("1", counts["peer_history"])
        assertEquals("2", counts["peer_links"])
        assertEquals("true", counts["peer_open"])
    }

    @Test
    fun absentChatAndMalformedPayloadNeverBecomeZeroEvidenceOrRawLinkText() {
        val frame = Envelope(id = 1, type = "turn_end", ts = "", payload = JsonObject(mapOf("conversation_id" to JsonObject(emptyMap()))))
        val counts = peerAttachmentFrameEvidence(listOf(frame), null, "no open session: link 3 ended (secret-token); redialing")
        assertEquals("unknown", counts["peer_terminal"])
        assertEquals("unknown", counts["peer_history"])
        assertEquals("unknown", counts["peer_chat_frames"])
        assertEquals("false", counts["peer_open"])
        assertEquals("3", counts["peer_links"])
        assertFalse(counts.toString().contains("secret-token"))
        assertEquals("0", peerAttachmentFrameEvidence(emptyList(), null, "no open session: none settled yet; redialing")["peer_links"])
    }
}
