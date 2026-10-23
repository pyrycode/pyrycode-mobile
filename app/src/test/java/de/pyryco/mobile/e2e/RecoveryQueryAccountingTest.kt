package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadQueryRequest
import de.pyryco.mobile.data.repository.AuthoritativeThreadRoute
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecoveryQueryAccountingTest {
    private val pump = Any()
    private val savedSink = RelayLog.sink
    private val logs = mutableListOf<String>()
    private var current = true
    private val saved = AuthoritativeThreadSnapshot(epoch = "private-epoch", checkpoint = 1, completedVersion = 1)

    @Before fun begin() {
        RelayLog.sink = { _, _, message -> logs += message }
        AuthoritativeHistoryProbe.begin("private-host", "private-chat", saved)
    }

    @After fun end() {
        AuthoritativeHistoryProbe.end()
        RelayLog.sink = savedSink
        for (secret in listOf("private-host", "private-chat", "private-epoch", "private-error")) {
            assertFalse("diagnostics contain no wire content or identities", logs.any { secret in it })
        }
    }

    @Test fun laterAcceptedQueryNeedsItsOwnTerminal() {
        val first = query(10)
        accept(first)
        receive(terminal(first))
        assertTrue(AuthoritativeHistoryProbe.current())
        val later = query(11)
        accept(later)
        receive(terminal(first))
        assertFalse("a later accepted query invalidates earlier readiness", AuthoritativeHistoryProbe.current())
        assertEquals(2, AuthoritativeHistoryProbe.requests().size)
        assertEquals(1, AuthoritativeHistoryProbe.certificates().size)
        receive(terminal(later))
        assertTrue(AuthoritativeHistoryProbe.current())
        assertTrue(AuthoritativeHistoryProbe.certificates()[1].request === AuthoritativeHistoryProbe.requests()[1])
    }

    @Test fun coherentAdmissionRejectsAQueryAcceptedAfterContentRead() {
        val first = query(10)
        accept(first)
        receive(terminal(first))
        val contentRead = AuthoritativeHistoryProbe.snapshot()
        assertTrue(contentRead.ready)
        assertTrue(AuthoritativeHistoryProbe.isCurrent(contentRead))
        val later = query(11)
        accept(later)
        assertFalse("the earlier content/readiness cannot admit a later acceptance", AuthoritativeHistoryProbe.isCurrent(contentRead))
        val pending = AuthoritativeHistoryProbe.snapshot()
        assertFalse(pending.ready)
        assertFalse(pending.exactlyAccounted)
        receive(terminal(first))
        assertFalse(AuthoritativeHistoryProbe.snapshot().ready)
        receive(terminal(later))
        val complete = AuthoritativeHistoryProbe.snapshot()
        assertTrue(complete.ready)
        assertTrue(complete.exactlyAccounted)
        assertTrue(AuthoritativeHistoryProbe.isCurrent(complete))
        assertFalse("an old token stays invalid after the later query completes", AuthoritativeHistoryProbe.isCurrent(contentRead))
        assertEquals(listOf(10L, 11L), complete.receipts.map { it.requestId })
        complete.receipts.forEach {
            assertTrue(it.connection === pump)
            assertEquals("private-host", it.host)
            assertEquals("private-chat", it.request.conversationId)
            assertEquals("private-epoch", it.epoch)
            assertEquals(0L, it.generation)
            assertEquals("PhoneQueryReceipt(redacted)", it.toString())
        }
        assertEquals("HistoryObservation(redacted)", complete.toString())
    }

    @Test fun oneSuccessCannotCompleteAnotherRequest() {
        for (reverse in listOf(false, true)) {
            AuthoritativeHistoryProbe.begin("private-host", "private-chat", saved)
            val requests = listOf(query(10), query(11))
            requests.forEach(::accept)
            val order = if (reverse) requests.reversed() else requests
            receive(terminal(order[0]))
            assertFalse("identical payloads still require distinct receipts", AuthoritativeHistoryProbe.current())
            receive(terminal(order[0]))
            assertEquals(1, AuthoritativeHistoryProbe.certificates().size)
            assertFalse(AuthoritativeHistoryProbe.current())
            receive(terminal(order[1]))
            assertEquals(2, AuthoritativeHistoryProbe.certificates().size)
            assertTrue(AuthoritativeHistoryProbe.current())
        }
    }

    @Test fun earlyDeliveryWaitsForItsOwnAcceptance() {
        for (accepted in listOf(false, true)) {
            AuthoritativeHistoryProbe.begin("private-host", "private-chat", saved)
            val first = query(10)
            accept(first)
            receive(terminal(first))
            val later = query(11)
            sending(later)
            receive(terminal(later))
            assertFalse("an unresolved send cannot retain earlier admission", AuthoritativeHistoryProbe.current())
            sent(later, accepted)
            assertEquals(if (accepted) 2 else 1, AuthoritativeHistoryProbe.requests().size)
            assertEquals(if (accepted) 2 else 1, AuthoritativeHistoryProbe.certificates().size)
            assertTrue(AuthoritativeHistoryProbe.current())
        }
    }

    @Test fun incompleteWorkCannotBorrowCompletion() {
        for (failure in listOf("partial", "failure", "malformed")) {
            AuthoritativeHistoryProbe.begin("private-host", "private-chat", saved)
            val incomplete = query(10)
            accept(incomplete)
            when (failure) {
                "partial" -> receive(batch(incomplete))
                "failure" ->
                    receive(
                        Envelope(
                            100,
                            "error",
                            "",
                            buildJsonObject {
                                put("code", "private-error")
                                put("message", "private-error")
                                put("retryable", false)
                            },
                            inReplyTo = incomplete.id,
                        ),
                    )
                else -> receive(terminal(incomplete, from = 9))
            }
            val another = query(11)
            accept(another)
            receive(terminal(another))
            assertFalse(failure, AuthoritativeHistoryProbe.current())
            assertEquals(2, AuthoritativeHistoryProbe.requests().size)
            assertEquals(1, AuthoritativeHistoryProbe.certificates().size)
        }
    }

    @Test fun retirementRetainsAcceptedAccounting() {
        for (lateAcceptance in listOf(false, true)) {
            AuthoritativeHistoryProbe.begin("private-host", "private-chat", saved)
            val request = query(10)
            sending(request)
            if (!lateAcceptance) sent(request)
            AuthoritativeHistoryProbe.ended("private-host", pump)
            if (lateAcceptance) sent(request)
            receive(terminal(request))
            assertEquals("close cannot erase an accepted send", 1, AuthoritativeHistoryProbe.requests().size)
            assertTrue(AuthoritativeHistoryProbe.certificates().isEmpty())
            assertFalse(AuthoritativeHistoryProbe.current())
            assertEquals(
                "abandoned",
                AuthoritativeHistoryProbe
                    .snapshot()
                    .receipts
                    .single()
                    .disposition,
            )
        }
    }

    @Test fun endingObservationRetainsAcceptanceReturningAfterEnd() {
        val request = query(10)
        sending(request)
        AuthoritativeHistoryProbe.end()
        sent(request)
        val ended = AuthoritativeHistoryProbe.snapshot()
        assertEquals(1, ended.requests.size)
        assertEquals("abandoned", ended.receipts.single().disposition)
        assertFalse(ended.ready)
        assertFalse(ended.exactlyAccounted)
    }

    @Test fun resetKeepsOldRequestsAndRequiresReplacementCertification() {
        for (outstanding in listOf(false, true)) {
            AuthoritativeHistoryProbe.begin("private-host", "private-chat", saved)
            val resetting = query(10)
            accept(resetting)
            val other = query(11)
            if (outstanding) accept(other)
            receive(reset(resetting))
            assertFalse(AuthoritativeHistoryProbe.current())
            assertTrue(AuthoritativeHistoryProbe.resets().single().request === AuthoritativeHistoryProbe.requests().first())
            val replacement = query(12, version = 0)
            accept(replacement)
            receive(terminal(other))
            assertFalse(AuthoritativeHistoryProbe.current())
            receive(terminal(replacement, from = null))
            assertEquals(!outstanding, AuthoritativeHistoryProbe.current())
            assertEquals(if (outstanding) 3 else 2, AuthoritativeHistoryProbe.requests().size)
            assertEquals(1, AuthoritativeHistoryProbe.certificates().size)
            assertEquals(
                1L,
                AuthoritativeHistoryProbe
                    .certificates()
                    .single()
                    .snapshot.generation,
            )
            val receipts = AuthoritativeHistoryProbe.snapshot().receipts
            assertEquals("reset", receipts.first().disposition)
            assertEquals(0L, receipts.first().generation)
            assertEquals("certified", receipts.last().disposition)
            assertEquals(1L, receipts.last().generation)
            assertEquals(0uL, (receipts.last().request as ThreadQueryRequest.CatchUp).version)
            if (outstanding) assertEquals("abandoned", receipts[1].disposition)
        }
    }

    @Test fun staleSourceCannotSupplyReceipt() {
        val first = query(10)
        accept(first)
        AuthoritativeHistoryProbe.received("private-host", Any(), terminal(first))
        AuthoritativeHistoryProbe.received("other-host", pump, terminal(first))
        assertTrue(AuthoritativeHistoryProbe.certificates().isEmpty())
        current = false
        receive(terminal(first))
        assertTrue(AuthoritativeHistoryProbe.certificates().isEmpty())
        assertFalse(AuthoritativeHistoryProbe.current())
    }

    @Test fun staleGenerationCannotRetainEarlierReadiness() {
        val store = AuthoritativeThreadStore()
        store.restore("private-host", "private-chat", saved, 0)
        val witness =
            OwnedThreadQueryObservation(
                "private-host",
                ThreadEvidenceOwner("private-host", "private-chat", pump, ThreadEvidenceKind.Authoritative, "private-epoch", 0),
                store,
                AuthoritativeThreadRoute("private-host", store),
            )
        witness.bind(pump) { true }
        val first = query(10)
        witness.sending(pump, first)
        witness.sent(pump, first, true)
        witness.received(pump, terminal(first))
        assertNotNull(witness.owner())
        store.reset("private-host", "private-chat", "private-epoch")
        assertNull("reusing epoch text does not reuse certification generation", witness.owner())
    }

    @Test fun replacementEpochCertificationBelongsToItsNewRequest() {
        val old = query(10)
        accept(old)
        receive(reset(old, epoch = "replacement-epoch"))
        val replacement = query(11, version = 0, epoch = "replacement-epoch")
        accept(replacement)
        receive(terminal(old))
        assertFalse(AuthoritativeHistoryProbe.snapshot().ready)
        receive(terminal(replacement, from = null, epoch = "replacement-epoch"))
        val complete = AuthoritativeHistoryProbe.snapshot()
        assertTrue(complete.ready)
        assertEquals(listOf("reset", "certified"), complete.receipts.map { it.disposition })
        assertEquals(listOf("private-epoch", "replacement-epoch"), complete.receipts.map { it.epoch })
        assertEquals(listOf(0L, 1L), complete.receipts.map { it.generation })
        assertTrue(complete.certificates.single().request === complete.requests.last())
    }

    @Test fun wrongEpochCannotSupplyAnotherRequestsMissingReceipt() {
        val wrong = query(10)
        accept(wrong)
        receive(terminal(wrong, epoch = "replacement-epoch"))
        val another = query(11)
        accept(another)
        receive(terminal(another))
        val incomplete = AuthoritativeHistoryProbe.snapshot()
        assertFalse(incomplete.ready)
        assertFalse(incomplete.exactlyAccounted)
        assertEquals(listOf("failed", "certified"), incomplete.receipts.map { it.disposition })
    }

    private fun query(
        id: Long,
        version: Long = 1,
        epoch: String = "private-epoch",
    ) = Envelope(
        id,
        "thread_catch_up",
        "",
        buildJsonObject {
            put("conversation_id", "private-chat")
            put("epoch", epoch)
            put("version", version)
        },
    )

    private fun terminal(
        request: Envelope,
        from: Long? = 1,
        epoch: String = "private-epoch",
    ) = Envelope(
        100,
        "thread_items",
        "",
        buildJsonObject {
            put("conversation_id", "private-chat")
            put("epoch", epoch)
            put("version", 1)
            put("items", JsonArray(emptyList()))
            put("final", true)
            if (from != null) {
                put("from_version", from)
            } else {
                put("lower_order", 0)
                put("upper_order", 0)
                put("continuation", 0)
                put("older_exists", false)
            }
        },
        inReplyTo = request.id,
    )

    private fun batch(request: Envelope) =
        Envelope(
            100,
            "thread_items",
            "",
            buildJsonObject {
                put("conversation_id", "private-chat")
                put("epoch", "private-epoch")
                put("version", 1)
                put("items", JsonArray(emptyList()))
                put("final", false)
            },
            inReplyTo = request.id,
        )

    private fun reset(
        request: Envelope,
        epoch: String = "private-epoch",
    ) = Envelope(
        100,
        "thread_reset",
        "",
        buildJsonObject {
            put("conversation_id", "private-chat")
            put("epoch", epoch)
            put("version", 1)
        },
        inReplyTo = request.id,
    )

    private fun sending(request: Envelope) = AuthoritativeHistoryProbe.sending("private-host", pump, request) { current }

    private fun sent(
        request: Envelope,
        accepted: Boolean = true,
    ) = AuthoritativeHistoryProbe.sent("private-host", pump, request, accepted)

    private fun accept(request: Envelope) {
        sending(request)
        sent(request)
    }

    private fun receive(frame: Envelope) = AuthoritativeHistoryProbe.received("private-host", pump, frame)
}
