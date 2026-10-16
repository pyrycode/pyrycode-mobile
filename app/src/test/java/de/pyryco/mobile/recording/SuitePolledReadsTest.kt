package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
