package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.repository.CompactRootFixture
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

class CompactRootDecoderTest {
    private val oldSink = RelayLog.sink

    @Before fun quiet() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restore() {
        RelayLog.sink = oldSink
    }

    @Test fun projectionInvariantCompleteRequestCannotCertifyCompactWindow() {
        val decoder = ThreadQueryDecoder("host", "connection", 7, ThreadQueryRequest.CatchUp("c", "", 0u))
        val marker =
            Envelope(
                8,
                "thread_items",
                "now",
                MobileJson.parseToJsonElement(
                    """{"projection":"compact-v1","conversation_id":"c","epoch":"e","version":41,"items":[],"final":true,"lower_order":0,"upper_order":42,"continuation":0,"older_exists":false}""",
                ),
                inReplyTo = 7,
            )
        assertTrue("a complete request cannot certify a compact root window", decoder.decode(marker).single() is ThreadDecodeOutcome.Repair)
    }

    private fun decoder(request: ThreadQueryRequest = ThreadQueryRequest.CatchUp("c", "", 0u, projection = "compact-v1")) =
        ThreadQueryDecoder("h", "connection", 7, request)

    private fun frame(
        raw: JsonObject,
        request: Long = 7,
    ) = Envelope(8, "thread_items", "now", raw, inReplyTo = request)

    private fun batch(header: JsonObject = CompactRootFixture.header(17)) =
        CompactRootFixture.obj(
            """{"projection":"compact-v1","conversation_id":"c","epoch":"e","version":100,"items":[],"headers":[$header],"final":false}""",
        )

    private fun marker(
        lower: Long = 10,
        upper: Long = 101,
    ) = CompactRootFixture.obj(
        """{"projection":"compact-v1","conversation_id":"c","epoch":"e","version":100,"items":[],"final":true,"lower_order":$lower,"upper_order":$upper,"continuation":$lower,"older_exists":false}""",
    )

    @Test fun ownerInvariantEveryBatchAndMarkerRepeatsProjectionConversationEpochAndRequest() {
        for ((key, value) in listOf(
            "projection" to JsonPrimitive("unknown"),
            "conversation_id" to JsonPrimitive("other"),
            "epoch" to JsonPrimitive("other"),
            "version" to JsonPrimitive(101),
        )) {
            val d = decoder()
            assertTrue(d.decode(frame(batch())).single() is ThreadDecodeOutcome.Complete)
            assertTrue(d.decode(frame(JsonObject(marker() + (key to value)))).single() is ThreadDecodeOutcome.Repair)
        }
        assertTrue(decoder().decode(frame(batch(), 9)).single() is ThreadDecodeOutcome.Repair)
        assertTrue(decoder().decode(frame(JsonObject(batch() - "projection"))).single() is ThreadDecodeOutcome.Repair)
        val page = decoder(ThreadQueryRequest.Page("c", 10u, projection = "compact-v1", epoch = "old"))
        assertTrue(page.decode(frame(marker(5, 10))).single() is ThreadDecodeOutcome.Repair)
    }

    @Test fun summaryInvariantRejectsDetailInventoryNonRootsAndMalformedKnownFacts() {
        val header = CompactRootFixture.header(17)
        val bad =
            listOf(
                JsonObject(header + ("content" to JsonObject(emptyMap()))),
                JsonObject(header + ("children" to JsonArray(emptyList()))),
                JsonObject(header + ("parent" to JsonPrimitive(1))),
                JsonObject(header + ("headline" to JsonPrimitive("x".repeat(201)))),
                JsonObject(header + ("headline" to JsonPrimitive("line\nbreak"))),
                JsonObject(header + ("kind" to JsonPrimitive("assistant_message"))),
                JsonObject(header + ("has_public_children" to JsonPrimitive(false)) + ("public_child_count" to JsonPrimitive(1))),
            )
        bad.forEach { assertTrue(decoder().decode(frame(batch(it))).single() is ThreadDecodeOutcome.Repair) }
        val fullTool =
            JsonObject(
                header - setOf("headline", "detail_omitted") +
                    mapOf("summary" to JsonPrimitive("tool"), "content" to JsonObject(emptyMap())),
            )
        assertTrue(
            decoder().decode(frame(JsonObject(batch() + ("items" to JsonArray(listOf(fullTool)))))).single() is ThreadDecodeOutcome.Repair,
        )
        assertTrue(
            decoder().decode(frame(JsonObject(marker() + ("headers" to JsonArray(emptyList()))))).single() is ThreadDecodeOutcome.Repair,
        )
    }

    @Test fun coverageInvariantMarkerCannotCertifyRootsOutsideItsWindow() {
        val d = decoder()
        assertTrue(d.decode(frame(batch())).single() is ThreadDecodeOutcome.Complete)
        assertTrue(d.decode(frame(marker(18, 101))).single() is ThreadDecodeOutcome.Repair)
    }

    @Test fun assemblyInvariantFinalFragmentIsOnlyDataAndInterruptedAssemblyCannotCertify() {
        val message =
            JsonObject(
                CompactRootFixture.message(30) + ("content" to JsonObject(mapOf("text" to JsonPrimitive("x".repeat(90000))))),
            )
        val raw = JsonObject(batch() - "headers" + ("items" to JsonArray(listOf(message))))
        val serialized = raw.toString()
        val hash =
            MessageDigest.getInstance("SHA-256").digest(("thread_items\u0000" + serialized).toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        val cut = serialized.length / 2
        val parts =
            listOf(serialized.take(cut), serialized.drop(cut)).mapIndexed { index, data ->
                JsonObject(
                    raw.filterKeys { it in setOf("projection", "conversation_id", "epoch", "version") } +
                        mapOf(
                            "item_id" to JsonPrimitive(30),
                            "rev" to JsonPrimitive(30),
                            "data" to JsonPrimitive(data),
                            "continuation" to
                                JsonObject(
                                    mapOf(
                                        "update_id" to JsonPrimitive(hash),
                                        "index" to JsonPrimitive(index),
                                        "offset" to JsonPrimitive(if (index == 0) 0 else cut),
                                        "total_bytes" to JsonPrimitive(serialized.toByteArray().size),
                                        "final" to JsonPrimitive(index == 1),
                                    ),
                                ),
                        ),
                )
            }
        val interrupted = decoder()
        assertTrue(interrupted.decode(frame(parts[0])).single() is ThreadDecodeOutcome.Pending)
        assertTrue(interrupted.decode(frame(marker())).single() is ThreadDecodeOutcome.Repair)
        val d = decoder()
        assertTrue(d.decode(frame(parts[0])).single() is ThreadDecodeOutcome.Pending)
        val assembled = d.decode(frame(parts[1])).single() as ThreadDecodeOutcome.Complete
        assertTrue(assembled.value is ThreadQueryReply.Batch)
        assertEquals(message, (assembled.value as ThreadQueryReply.Batch).items.single().raw)
        assertTrue((d.decode(frame(marker())).single() as ThreadDecodeOutcome.Complete).value is ThreadQueryReply.Success)
        val switched = decoder()
        switched.decode(frame(parts[0]))
        assertTrue(switched.decode(frame(JsonObject(parts[1] - "projection"))).single() is ThreadDecodeOutcome.Repair)
    }
}
