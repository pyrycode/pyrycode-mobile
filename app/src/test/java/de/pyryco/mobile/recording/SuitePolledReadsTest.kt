package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SuitePolledReadsTest {
    private val owner = "phone/host-0/0"
    private val payload = MobileJson.parseToJsonElement("""{"conversation_id":"chat"}""")

    private fun frame(
        id: Long,
        type: String,
        body: JsonElement = payload,
        reply: Long? = null,
    ) = Envelope(id, type, "2026-10-16T00:00:00Z", body, reply)

    private fun rows(
        type: String = "request_session_settings",
        response: String = "session_settings",
    ) = listOf(
        SuiteStep("client", owner, frame(1, type)),
        SuiteStep("daemon", owner, frame(2, response, MobileJson.parseToJsonElement("""{"mode":"before"}"""), 1)),
        SuiteStep("client", owner, frame(3, "set_permission_mode")),
        SuiteStep("client", owner, frame(4, type)),
        SuiteStep("daemon", owner, frame(5, response, MobileJson.parseToJsonElement("""{"mode":"after"}"""), 4)),
    )

    private fun cleared(id: Long = 10) =
        frame(id, "session_settings", JsonObject(emptyMap())).copy(
            conversationId = JsonPrimitive("chat"),
            sessionStateCleared = JsonPrimitive(true),
        )

    private fun clearedModel(
        source: List<SuiteStep>,
        enabled: Boolean = true,
    ) = SuitePolledReads(
        source,
        { it },
        { 100 },
        settings = true,
        refusedBackfill = false,
        cancelClearedSettings = enabled,
        queryTypes = setOf("request_session_settings", "thread_catch_up"),
    )

    @Test fun clearedSettingsReadsWaitWithoutAnswersUntilTheRecordedConnectionEnds() {
        val clear = cleared()
        val model = clearedModel(listOf(SuiteStep("daemon", owner, clear), SuiteStep("close", owner)))
        val ask = frame(80, "request_session_settings")
        assertFalse(model.accepts(owner, ask))
        model.observeCleared(owner, clear)
        assertTrue(model.accepts(owner, ask))
        assertTrue(model.send(owner, ask).isEmpty())
        assertThrows(IllegalStateException::class.java) { model.assertComplete() }
        model.connectionEnded("phone/other/0")
        assertThrows(IllegalStateException::class.java) { model.assertComplete() }
        model.connectionEnded(owner)
        model.assertComplete()
        assertFalse(model.accepts(owner, ask.copy(id = 81)))
    }

    @Test fun clearedSettingsReadsNeverUseTheReplacementConnectionsSnapshot() {
        val clear = cleared()
        val next = "phone/host-0/1"
        val replacement = rows().take(2).map { it.copy(owner = next) }
        val model = clearedModel(listOf(SuiteStep("daemon", owner, clear), SuiteStep("close", owner)) + replacement)
        model.observeCleared(owner, clear)
        val ask = frame(80, "request_session_settings")
        assertTrue(model.accepts(owner, ask))
        assertTrue(model.send(owner, ask).isEmpty())
        model.steps.filter { it.direction == "settings_snapshot" }.forEach { assertTrue(model.delivered(it).isEmpty()) }
        assertThrows(IllegalStateException::class.java) { model.assertComplete() }
        model.connectionEnded(owner)
        model.assertComplete()
        assertEquals(
            "before",
            model.send(next, ask.copy(id = 81)).single().message!!.payload.let {
                (it as JsonObject).getValue("mode").toString().trim('"')
            },
        )
    }

    @Test fun clearedSettingsReadStillRequiresItsRecordedOwnerAndCompleteConversationPayload() {
        val clear = cleared()
        val model = clearedModel(listOf(SuiteStep("daemon", owner, clear), SuiteStep("close", owner)))
        model.observeCleared(owner, clear)
        val ask = frame(80, "request_session_settings")
        assertTrue(model.accepts(owner, ask))
        assertFalse(model.accepts("phone/host-0/1", ask))
        assertFalse(model.accepts("peer/host-0/0", ask))
        for (body in listOf("""{"conversation_id":"other"}""", """{"conversation_id":"chat","extra":true}""")) {
            assertFalse(model.accepts(owner, ask.copy(payload = MobileJson.parseToJsonElement(body))))
        }
    }

    @Test fun missingEndFreshStateWritesErrorsAndRefusalsKeepClearedSettingsReadsStrict() {
        val clear = cleared()
        val begin = SuiteStep("daemon", owner, clear)
        val ask = SuiteStep("client", owner, frame(20, "request_session_settings"))
        val close = SuiteStep("close", owner)
        val sources =
            listOf(
                listOf(begin),
                listOf(begin, SuiteStep("daemon", owner, frame(21, "session_settings")), close),
                listOf(begin, SuiteStep("client", owner, frame(21, "set_session_settings")), close),
                listOf(begin, ask, SuiteStep("daemon", owner, frame(21, "error", reply = 20)), close),
                listOf(begin, ask.copy(accepted = false), close),
            )
        for (source in sources) {
            val model = clearedModel(source)
            model.observeCleared(owner, clear)
            assertFalse(model.accepts(owner, frame(80, "request_session_settings")))
        }
    }

    @Test fun olderSettingsPolicyCannotCancelReadsWithoutAnEarlierSuccessfulSnapshot() {
        val clear = cleared()
        val model = clearedModel(listOf(SuiteStep("daemon", owner, clear), SuiteStep("close", owner)), enabled = false)
        model.observeCleared(owner, clear)
        assertFalse(model.accepts(owner, frame(80, "request_session_settings")))
    }

    @Test fun aDeliveredClearInvalidatesTheEarlierSnapshotBeforeItsConnectionEnds() {
        val clear = cleared()
        val model = clearedModel(rows().take(2) + listOf(SuiteStep("daemon", owner, clear), SuiteStep("close", owner)))
        model.steps.filter { it.direction == "settings_snapshot" }.forEach { model.delivered(it) }
        model.observeCleared(owner, clear)
        assertTrue(model.send(owner, frame(80, "request_session_settings")).isEmpty())
        model.connectionEnded(owner)
        model.assertComplete()
    }

    @Test fun aCapturedUnansweredReadAfterClearMayBeOmittedWithoutRemovingItsClearOrEnd() {
        val clear = cleared()
        val source =
            listOf(
                SuiteStep("daemon", owner, clear),
                SuiteStep("client", owner, frame(20, "request_session_settings")),
                SuiteStep("close", owner),
            )
        val model = clearedModel(source)
        assertEquals(listOf(source.first(), source.last()), model.steps)
        model.observeCleared(owner, clear)
        model.connectionEnded(owner)
        model.assertComplete()
    }

    @Test fun theVersionTwoTapeActivatesTheClearOnlyOnDeliveryAndCancelsAtItsRecordedClose() {
        val clear = cleared()
        val tape =
            RecordedSuiteTape(
                listOf(
                    SuiteStep("connect", owner),
                    SuiteStep("open", owner),
                    SuiteStep("daemon", owner, clear),
                    SuiteStep("close", owner),
                    SuiteStep("inbound_end", owner),
                ),
                literalProducerIdentities = true,
                recordedSettingsReads = true,
                cancelledClearedSettingsReads = true,
            )
        val deliveries = tape.lifecycle("connect", owner)
        val delivered = deliveries.single { it.direction == "daemon" }.message!!
        tape.delivered(owner, delivered)
        assertTrue(tape.send(owner, frame(80, "request_session_settings")).deliveries.isEmpty())
        tape.lifecycle("close", owner)
        tape.assertComplete()
    }

    @Test fun settingsReadsUseOnlyTheLatestDeliveredSnapshotAndKeepExactPayloadAndOwner() {
        var next = 100L
        val model = SuitePolledReads(rows(), { it }, { next++ }, settings = true, refusedBackfill = false)
        val request = frame(80, "request_session_settings")
        assertTrue(model.accepts(owner, request))
        assertFalse(model.accepts("phone/host-0/1", request))
        assertFalse(model.accepts("peer/host-0/0", request))
        assertFalse(model.accepts(owner, request.copy(payload = MobileJson.parseToJsonElement("""{"conversation_id":"other"}"""))))
        assertTrue(model.send(owner, request).isEmpty())
        val snapshots = model.steps.filter { it.direction == "settings_snapshot" }
        assertEquals(2, snapshots.size)
        val first = model.delivered(snapshots[0]).single().message!!
        assertEquals(80L, first.inReplyTo)
        assertEquals("before", (first.payload as kotlinx.serialization.json.JsonObject).getValue("mode").toString().trim('"'))
        assertEquals(
            first.payload,
            model
                .send(owner, request.copy(id = 81))
                .single()
                .message!!
                .payload,
        )
        model.delivered(snapshots[1])
        val last = model.send(owner, request.copy(id = 82)).single().message!!
        assertEquals("after", (last.payload as kotlinx.serialization.json.JsonObject).getValue("mode").toString().trim('"'))
        assertEquals(102L, last.id)
        model.assertComplete()
    }

    @Test fun erroredOrRefusedSettingsReadsRemainStrictSourceRequests() {
        for (source in listOf(rows(response = "error"), rows().map { if (it.direction == "client") it.copy(accepted = false) else it })) {
            val model = SuitePolledReads(source, { it }, { 100 }, settings = true, refusedBackfill = true)
            assertEquals(source, model.steps)
            assertFalse(model.accepts(owner, frame(80, "request_session_settings")))
        }
    }

    @Test fun onlyAnExactlyCapturedUnsupportedBackfillProbeCanRepeatOrBeOmitted() {
        val request = frame(1, "backfill_since")
        val refusal = frame(2, "error", MobileJson.parseToJsonElement("""{"code":"protocol.unknown_type","message":"unsupported"}"""), 1)
        val source = listOf(SuiteStep("client", owner, request), SuiteStep("daemon", owner, refusal))
        val model = SuitePolledReads(source, { it }, { 100 }, settings = true, refusedBackfill = true)
        assertTrue(model.steps.isEmpty())
        model.assertComplete()
        assertEquals(
            refusal.payload,
            model
                .send(owner, request.copy(id = 80))
                .single()
                .message!!
                .payload,
        )
        assertEquals(
            refusal.payload,
            model
                .send(owner, request.copy(id = 81))
                .single()
                .message!!
                .payload,
        )
        assertFalse(model.accepts(owner, request.copy(type = "request_history")))
        assertFalse(model.accepts("phone/other/0", request))
        val changed =
            source.dropLast(1) +
                source.last().copy(message = refusal.copy(payload = MobileJson.parseToJsonElement("""{"code":"different"}""")))
        assertEquals(changed, SuitePolledReads(changed, { it }, { 100 }, settings = true, refusedBackfill = true).steps)
    }

    @Test fun aFinalSettingsReadCancelledByCloseDoesNotDisableObservedSnapshots() {
        val source =
            rows() +
                listOf(
                    SuiteStep("client", owner, frame(6, "request_session_settings")),
                    SuiteStep("close", owner),
                )
        val model = SuitePolledReads(source, { it }, { 100 }, settings = true, refusedBackfill = false)
        assertTrue(model.steps.none { it.direction == "client" && it.message?.type == "request_session_settings" })
        val snapshots = model.steps.filter { it.direction == "settings_snapshot" }
        assertEquals(2, snapshots.size)
        snapshots.forEach { model.delivered(it) }
        model.assertComplete()
        assertEquals(
            snapshots.last().message!!.payload,
            model
                .send(owner, frame(80, "request_session_settings"))
                .single()
                .message!!
                .payload,
        )
    }

    @Test fun anUnansweredReadBeforeAnotherActionOrWithoutAnySnapshotStaysStrict() {
        val missing = SuiteStep("client", owner, frame(6, "request_session_settings"))
        for (source in listOf(
            rows() + missing + SuiteStep("client", owner, frame(7, "send_message")) + SuiteStep("close", owner),
            listOf(missing, SuiteStep("close", owner)),
            rows() + missing + SuiteStep("close", "phone/other/0"),
        )) {
            val model = SuitePolledReads(source, { it }, { 100 }, settings = true, refusedBackfill = false)
            assertEquals(source, model.steps)
            assertFalse(model.accepts(owner, frame(80, "request_session_settings")))
        }
    }

    @Test fun aWriteInvalidatesEarlierSnapshotsIncludingAlreadyQueuedReplies() {
        val before = MobileJson.parseToJsonElement("""{"session_id":"session","effort":""}""")
        val after = MobileJson.parseToJsonElement("""{"session_id":"session","effort":"max"}""")
        val write = frame(5, "set_session_settings", MobileJson.parseToJsonElement("""{"session_id":"session","effort":"max"}"""))
        val source =
            listOf(
                SuiteStep("client", owner, frame(1, "request_session_settings")),
                SuiteStep("daemon", owner, frame(2, "session_settings", before, 1)),
                SuiteStep("client", owner, frame(3, "request_session_settings")),
                SuiteStep("daemon", owner, frame(4, "session_settings", before, 3)),
                SuiteStep("client", owner, write),
                SuiteStep("client", owner, frame(6, "request_session_settings")),
                SuiteStep("daemon", owner, frame(7, "session_settings", after, 6)),
            )
        val model = SuitePolledReads(source, { it }, { 100 }, settings = true, refusedBackfill = false)
        val snapshots = model.steps.filter { it.direction == "settings_snapshot" }
        model.delivered(snapshots[0])
        model.command(owner, write)
        model.delivered(snapshots[1])
        assertTrue(model.send(owner, frame(80, "request_session_settings")).isEmpty())
        val response = model.delivered(snapshots[2]).single().message!!
        assertEquals(after, response.payload)
        assertEquals(80L, response.inReplyTo)
        model.assertComplete()
    }
}
