package de.pyryco.mobile.data.network

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/** Supplied fixtures adapted from daemon internal/protocol/thread_test.go and thread_assembly_test.go. */
class ThreadUpdateDecoderTest {
    private val added = "thread_item_added"
    private val changed = "thread_item_changed"
    private val append = "thread_text_append"
    private var now = 0L
    private lateinit var decoder: ThreadUpdateDecoder
    private val logs = mutableListOf<String>()
    private var previousEnabled = false
    private lateinit var previousSink: (Int, String, String) -> Unit

    @Before
    fun setUp() {
        previousEnabled = RelayLog.enabled
        previousSink = RelayLog.sink
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs.add(message) }
        decoder = newDecoder()
    }

    @After
    fun tearDown() {
        RelayLog.enabled = previousEnabled
        RelayLog.sink = previousSink
    }

    @Test
    fun threadCapabilityIsRecognizedWithoutAdvertisement() {
        val ack =
            MobileJson.decodeFromString<HelloAckPayload>(
                """{"protocol_version":"v2","server_id":"srv","conn_id":"conn","capabilities":["interactive","thread"]}""",
            )
        assertTrue(CAPABILITY_THREAD in ack.capabilities)
        val hello = HelloClientPayload(deviceName = "d", clientVersion = "1", token = "secret")
        assertFalse(CAPABILITY_THREAD in hello.capabilities)
        assertFalse(MobileJson.encodeToString(hello).contains("\"thread\""))
    }

    @Test
    fun ordinaryAdditionRetainsUnknownKindStatusAndJson() {
        val raw = addition()
        val value = complete(decoder.decode(envelope(added, raw)))
        assertEquals(raw, value.raw)
        assertEquals(9007199254740991L, value.version)
        val item = requireNotNull(value.item)
        assertEquals(101L, item.id)
        assertEquals(303L, item.rev)
        assertEquals(202L, item.order)
        assertEquals(404L, item.endedOrder)
        assertEquals("future_kind", item.kind)
        assertEquals("future_status", item.status)
        assertEquals("plain fallback", item.summary)
        assertEquals("session-c", item.session)
        assertEquals("codex", item.agent)
        assertEquals("turn-d", item.turn)
        assertEquals(505L, item.parent)
        assertEquals("future_subtype", item.subtype)
        assertFalse(item.active)
        assertFalse(item.shown)
        assertEquals(raw.getValue("item").jsonObject.getValue("content"), item.content)
        assertEquals(JsonPrimitive(true), item.raw["future_fact"])
    }

    @Test
    fun absentAttributionNeverInventsSessionAgentOrChild() {
        val item = addition().getValue("item").jsonObject
        val omitted = JsonObject(item - setOf("session", "agent", "no_child", "turn", "parent", "order", "ended_order", "subtype"))
        val value = requireNotNull(complete(decoder.decode(envelope(added, replace(addition(), "item", omitted)))).item)
        assertNull(value.session)
        assertNull(value.agent)
        assertNull(value.noChild)
        assertNull(value.order)
        val known = replace(omitted, "session", JsonPrimitive("known"))
        val knownItem = requireNotNull(complete(newDecoder().decode(envelope(added, replace(addition(), "item", known)))).item)
        assertEquals("known", knownItem.session)
        assertNull(knownItem.agent)
        val noChild = replace(omitted, "no_child", JsonPrimitive(true))
        assertEquals(
            true,
            requireNotNull(complete(newDecoder().decode(envelope(added, replace(addition(), "item", noChild)))).item).noChild,
        )
    }

    @Test
    fun patchesPreservePresenceAndWholeJsonValues() {
        val patches =
            listOf(
                "{}",
                """{"summary":"","active":false,"shown":false,"order":0,"parent":0,"ended_order":0,"session":"","agent":"","no_child":false,"turn":"","status":"","subtype":"","content":{}}""",
                """{"content":{"replacement":true,"unknown":[null,0,false],"text":"whole new value"}}""",
                """{"content":null,"future_field":0}""",
                """{"content":[false,"",0],"future_field":null}""",
            )
        for (patch in patches) {
            val raw = change(obj(patch))
            val value = complete(decoder.decode(envelope(changed, raw)))
            assertEquals(raw, value.raw)
            assertEquals(obj(patch), value.changes)
            assertEquals(303L, value.baseRev)
            assertEquals(707L, value.rev)
            assertEquals(808L, value.version)
        }
    }

    @Test
    fun appendRetainsSuffixIncludingEmptyWithoutApplying() {
        for (text in listOf("next\ntext", "", " 🌲\u0000\"\\\u2028")) {
            val value = complete(decoder.decode(envelope(append, suffix(text))))
            assertEquals(text, value.text)
            assertNull(value.item)
            assertNull(value.changes)
        }
    }

    @Test
    fun requiredFieldsAndTokenShapesAreStrict() {
        val cases = listOf(added to addition(), changed to change(), append to suffix(""))
        for ((type, raw) in cases) {
            for (key in raw.keys) {
                repair(newDecoder().decode(envelope(type, JsonObject(raw - key))))
            }
            for (key in listOf("conversation_id", "epoch")) {
                for (bad in listOf(JsonNull, JsonPrimitive(0), JsonPrimitive(false), obj("{}"))) {
                    repair(newDecoder().decode(envelope(type, replace(raw, key, bad))))
                }
            }
            for (key in listOf("version", "item_id", "rev", "base_rev").filter { it in raw }) {
                for (bad in listOf("-1", "1.0", "1e0", "9007199254740992", "\"1\"", "00", "true", "null")) {
                    repair(newDecoder().decode(envelope(type, replace(raw, key, MobileJson.parseToJsonElement(bad)))))
                }
            }
        }
        val item = addition().getValue("item").jsonObject
        for (key in listOf("id", "kind", "rev", "status", "active", "shown", "summary", "content")) {
            repair(newDecoder().decode(envelope(added, replace(addition(), "item", JsonObject(item - key)))))
        }
        for (key in listOf("kind", "status", "summary", "session", "agent", "turn", "subtype")) {
            repair(newDecoder().decode(envelope(added, replace(addition(), "item", replace(item, key, JsonPrimitive(1))))))
        }
        for (key in listOf("active", "shown", "no_child")) {
            repair(newDecoder().decode(envelope(added, replace(addition(), "item", replace(item, key, JsonPrimitive("false"))))))
        }
        repair(newDecoder().decode(envelope(changed, replace(change(), "changes", JsonNull))))
        repair(newDecoder().decode(envelope(append, replace(suffix(""), "text", JsonPrimitive(1)))))
        assertEquals(
            JsonNull,
            requireNotNull(
                complete(newDecoder().decode(envelope(added, replace(addition(), "item", replace(item, "content", JsonNull))))).item,
            ).content,
        )
    }

    @Test
    fun multipartRecoversOriginalPayload() {
        val large = "🌲\u0000\\\"\u2028".repeat(15000)
        val rawAddition = replace(addition(), "item", replace(addition().getValue("item").jsonObject, "summary", JsonPrimitive(large)))
        val raws =
            listOf(
                added to rawAddition,
                changed to
                    change(
                        JsonObject(
                            mapOf(
                                "summary" to JsonPrimitive(""),
                                "session" to JsonNull,
                                "active" to JsonPrimitive(false),
                                "content" to JsonObject(mapOf("nested" to JsonPrimitive(large))),
                            ),
                        ),
                    ),
                append to suffix(large),
            )
        for ((type, raw) in raws) {
            val d = newDecoder()
            val parts = parts(type, raw, chunkChars = 10001)
            for ((index, part) in parts.withIndex()) {
                val outcomes = d.decode(part)
                if (index != parts.lastIndex) {
                    pending(outcomes)
                } else {
                    val value = complete(outcomes)
                    assertEquals(raw, value.raw)
                    assertEquals(metadata(type, raw), metadata(type, value.raw))
                }
            }
            assertTrue(d.expire().isEmpty())
        }
        // Escaped logical JSON fragments can end within an escape sequence, and offsets count UTF-8 bytes.
        val logical = """{"conversation_id":"c","epoch":"e","version":4,"item_id":1,"base_rev":3,"rev":4,"text":"\ud83c\udf32\u0000"}"""
        val raw = obj(logical)
        val escapedParts = parts(append, raw, 7, serialized = logical)
        escapedParts.dropLast(1).forEach { pending(decoder.decode(it)) }
        assertEquals("🌲\u0000", complete(decoder.decode(escapedParts.last())).text)
    }

    @Test
    fun sequenceFailuresNeverComplete() {
        val p = parts(append, suffix("x".repeat(400)), 80)
        for (sequence in listOf(listOf(p[1]), listOf(p[0], p[2]), listOf(p[0], p[0]), listOf(p[0], p.last()))) {
            val d = newDecoder()
            sequence.dropLast(1).forEach { pending(d.decode(it)) }
            repair(d.decode(sequence.last()))
            repair(d.decode(p.last()))
            assertTrue(d.expire().isEmpty())
        }
        val badOffset = editContinuation(p[1], "offset", JsonPrimitive(1))
        pending(decoder.decode(p[0]))
        repair(decoder.decode(badOffset))
    }

    @Test
    fun repeatedMetadataMustMatch() {
        val p = parts(append, suffix("x".repeat(400)), 80)
        val mutations =
            mapOf(
                "epoch" to JsonPrimitive("other"),
                "version" to JsonPrimitive(9),
                "item_id" to JsonPrimitive(9),
                "rev" to JsonPrimitive(9),
                "base_rev" to JsonPrimitive(9),
            )
        for ((key, value) in mutations) {
            val d = newDecoder()
            pending(d.decode(p[0]))
            repair(d.decode(p[1].copy(payload = replace(p[1].payload.jsonObject, key, value))))
            assertTrue(d.expire().isEmpty())
        }
        for (bad in listOf(
            p[1].copy(type = changed),
            p[1].copy(payload = JsonObject(p[1].payload.jsonObject - "base_rev")),
            editContinuation(p[1], "total_bytes", JsonPrimitive(999)),
            editContinuation(p[1], "update_id", JsonPrimitive("f".repeat(64))),
        )) {
            val d = newDecoder()
            pending(d.decode(p[0]))
            repair(d.decode(bad))
            assertTrue(d.expire().isEmpty())
        }
    }

    @Test
    fun changedRoutingRepairsOriginalOwnerAndDiscardsItsBuffer() {
        val p = parts(append, suffix("held", "owner"), 17)
        pending(decoder.decode(p.first()))
        val misrouted = p[1].copy(payload = replace(p[1].payload.jsonObject, "conversation_id", JsonPrimitive("other")))
        val result = decoder.decode(misrouted).single() as ThreadDecodeOutcome.Repair
        assertEquals("owner", result.conversationId)
        assertTrue(decoder.abandon().isEmpty())
    }

    @Test
    fun malformedRoutingStillDiscardsIdentifiableAssembly() {
        val p = parts(append, suffix("held", "owner"), 17)
        pending(decoder.decode(p.first()))
        val malformed = p[1].copy(payload = JsonObject(p[1].payload.jsonObject - "conversation_id"))
        val result = decoder.decode(malformed).single() as ThreadDecodeOutcome.Repair
        assertEquals("owner", result.conversationId)
        assertTrue(decoder.abandon().isEmpty())
    }

    @Test
    fun finalDecodedMetadataMustMatchEvenWithCorrectDigest() {
        for (type in listOf(added, changed, append)) {
            val raw =
                when (type) {
                    added -> addition()
                    changed -> change()
                    else -> suffix("text")
                }
            val p = parts(type, raw, 40).map { it.copy(payload = replace(it.payload.jsonObject, "rev", JsonPrimitive(999))) }
            val d = newDecoder()
            p.dropLast(1).forEach { pending(d.decode(it)) }
            repair(d.decode(p.last()))
        }
    }

    @Test
    fun malformedContinuationCannotFallThroughOrdinaryDecode() {
        for (bad in listOf(JsonNull, JsonPrimitive(false), obj("{}"))) {
            repair(decoder.decode(envelope(append, replace(suffix("text"), "continuation", bad))))
        }
        val part = parts(append, suffix("text"), 999).single()
        for (key in part.payload.jsonObject
            .getValue("continuation")
            .jsonObject.keys) {
            val c =
                part.payload.jsonObject
                    .getValue("continuation")
                    .jsonObject
            repair(newDecoder().decode(part.copy(payload = replace(part.payload.jsonObject, "continuation", JsonObject(c - key)))))
        }
        for ((key, bad) in mapOf(
            "index" to JsonPrimitive("0"),
            "offset" to JsonPrimitive(-1),
            "final" to JsonPrimitive("true"),
            "update_id" to JsonPrimitive("A".repeat(64)),
        )) {
            repair(newDecoder().decode(editContinuation(part, key, bad)))
        }
        repair(newDecoder().decode(part.copy(payload = replace(part.payload.jsonObject, "data", JsonPrimitive("")))))
        repair(newDecoder().decode(part.copy(payload = replace(part.payload.jsonObject, "data", JsonPrimitive("\ud800")))))
    }

    @Test
    fun integrityLengthAndJsonFailuresDiscardEverything() {
        val raw = suffix("text")
        val p = parts(append, raw, 30)
        val corrupt =
            p
                .last()
                .copy(
                    payload =
                        replace(
                            p.last().payload.jsonObject,
                            "data",
                            JsonPrimitive(
                                "z".repeat(
                                    p
                                        .last()
                                        .payload.jsonObject
                                        .getValue(
                                            "data",
                                        ).let {
                                            (it as JsonPrimitive).content.length
                                        },
                                ),
                            ),
                        ),
                )
        for (bad in listOf(corrupt, editContinuation(p.last(), "final", JsonPrimitive(true)))) {
            val d = newDecoder()
            p.dropLast(1).forEach { pending(d.decode(it)) }
            val result = d.decode(bad)
            if (bad == corrupt) repair(result) else complete(result)
        }
        for (serialized in listOf("not-json", "{}", "[]", raw.toString().dropLast(1))) {
            val q = parts(append, raw, 3, serialized)
            val d = newDecoder()
            q.dropLast(1).forEach { pending(d.decode(it)) }
            repair(d.decode(q.last()))
            assertTrue(d.expire().isEmpty())
        }
        val d = newDecoder()
        pending(d.decode(p[0]))
        repair(d.decode(editContinuation(p[1], "final", JsonPrimitive(true))))
        val short = editContinuation(parts(append, raw, 999).single(), "total_bytes", JsonPrimitive(1))
        repair(newDecoder().decode(short))
    }

    @Test
    fun missingFinalExpiresWithoutSlidingDeadline() {
        val p = parts(append, suffix("x".repeat(400)), 80)
        pending(decoder.decode(p[0]))
        now = 29000
        pending(decoder.decode(p[1]))
        now = 30000
        assertEquals("c", decoder.expire().single().conversationId)
        repair(decoder.decode(p.last()))
        assertTrue(decoder.expire().isEmpty())
    }

    @Test
    fun expiryReportsOwningConversation() {
        pending(decoder.decode(parts(append, suffix("held", "old"), 3).first()))
        now = 30000
        val results = decoder.decode(envelope(append, suffix("new", "new")))
        assertEquals("old", results.filterIsInstance<ThreadDecodeOutcome.Repair>().single().conversationId)
        assertEquals(
            "new",
            results
                .filterIsInstance<ThreadDecodeOutcome.Complete<ThreadUpdateDto>>()
                .single()
                .value.conversationId,
        )
    }

    @Test
    fun sourcesNeverCombine() {
        val p = parts(append, suffix("x".repeat(200)), 50)
        for (other in listOf(newDecoder("other-host", "conn"), newDecoder("host", "new-conn"))) {
            pending(decoder.decode(p[0]))
            repair(other.decode(p[1]))
            p.drop(1).dropLast(1).forEach { pending(decoder.decode(it)) }
            assertEquals(suffix("x".repeat(200)), complete(decoder.decode(p.last())).raw)
        }
    }

    @Test
    fun interleavedConversationsNeverCombine() {
        val a = parts(append, suffix("first", "a"), 17)
        val b = parts(append, suffix("second", "b"), 17)
        for (i in 0 until maxOf(a.size, b.size)) {
            if (i < a.size) {
                val r = decoder.decode(a[i])
                if (i == a.lastIndex) assertEquals("first", complete(r).text) else pending(r)
            }
            if (i < b.size) {
                val r = decoder.decode(b[i])
                if (i == b.lastIndex) assertEquals("second", complete(r).text) else pending(r)
            }
        }
    }

    @Test
    fun ordinaryDuringAssemblyRepairs() {
        val p = parts(append, suffix("held"), 3)
        pending(decoder.decode(p.first()))
        repair(decoder.decode(envelope(append, suffix("ordinary"))))
        assertTrue(decoder.expire().isEmpty())
    }

    @Test
    fun abandonmentCannotReviveBuffers() {
        val p = parts(append, suffix("held"), 3)
        pending(decoder.decode(p.first()))
        assertEquals("c", decoder.abandon().single().conversationId)
        assertTrue(decoder.abandon().isEmpty())
        repair(decoder.decode(p.last()))
        repair(decoder.decode(envelope(append, suffix("late"))))
        val fresh = newDecoder("host", "conn2")
        p.dropLast(1).forEach { pending(fresh.decode(it)) }
        complete(fresh.decode(p.last()))
    }

    @Test
    fun discardReleasesOnlyOwner() {
        val a = parts(append, suffix("first", "a"), 17)
        val b = parts(append, suffix("second", "b"), 17)
        pending(decoder.decode(a.first()))
        pending(decoder.decode(b.first()))
        decoder.discard("a")
        assertEquals("b", decoder.abandon().single().conversationId)
    }

    @Test
    fun completedReplayIsDecodedWithoutApplication() {
        val p = parts(append, suffix("exact suffix"), 999)
        assertEquals("exact suffix", complete(decoder.decode(p.single())).text)
        assertEquals("exact suffix", complete(decoder.decode(p.single())).text)
        assertEquals("exact suffix", complete(decoder.decode(envelope(append, suffix("exact suffix")))).text)
    }

    @Test
    fun limitsRejectBeforeBufferingAndReleaseOnTerminalPaths() {
        val raw = suffix("x".repeat(200))
        val p = parts(append, raw, 60)
        repair(newDecoder(maxPayloadBytes = 10).decode(p.first()))
        repair(newDecoder(maxBufferedBytes = 10).decode(p.first()))
        val count = newDecoder(maxAssemblies = 1)
        pending(count.decode(p.first()))
        repair(count.decode(parts(append, suffix("other", "other"), 2).first()))
        count.discard("c")
        pending(count.decode(parts(append, suffix("other", "other"), 2).first()))
        val maxParts = newDecoder(maxParts = 1)
        pending(maxParts.decode(p.first()))
        repair(maxParts.decode(p[1]))
        assertTrue(maxParts.abandon().isEmpty())
        val budget =
            p
                .first()
                .payload.jsonObject
                .getValue("data")
                .let { (it as JsonPrimitive).content.toByteArray().size } +
                metadata(append, raw).toString().toByteArray().size
        val aggregate = newDecoder(maxBufferedBytes = budget * 2)
        pending(aggregate.decode(p.first()))
        pending(aggregate.decode(parts(append, suffix("x".repeat(200), "d"), 60).first()))
        repair(aggregate.decode(p[1]))
        aggregate.discard("d")
        val single = parts(append, suffix(""), 999).single()
        complete(aggregate.decode(single))
    }

    @Test
    fun diagnosticsAndStringRepresentationsContainNoContent() {
        val secret = "UNTRUSTED-CONTENT-SECRET"
        val value = complete(decoder.decode(envelope(append, suffix(secret))))
        val bad = parts(append, suffix(secret), 999).single()
        repair(decoder.decode(editContinuation(bad, "update_id", JsonPrimitive("f".repeat(64)))))
        assertFalse(value.toString().contains(secret))
        assertFalse(logs.joinToString().contains(secret))
        assertTrue(logs.any { it.contains("event=thread_decode") })
    }

    private fun newDecoder(
        host: String = "host",
        connection: String = "conn",
        maxPayloadBytes: Int = 8 * 1024 * 1024,
        maxBufferedBytes: Int = 16 * 1024 * 1024,
        maxAssemblies: Int = 32,
        maxParts: Int = 4096,
    ) = ThreadUpdateDecoder(host, connection, maxPayloadBytes, maxBufferedBytes, maxAssemblies, maxParts, nowMillis = { now })

    private fun addition() =
        obj(
            """{"conversation_id":"conv-a","epoch":"epoch-b","version":9007199254740991,"item":{"id":101,"kind":"future_kind","order":202,"rev":303,"ended_order":404,"session":"session-c","agent":"codex","turn":"turn-d","parent":505,"status":"future_status","active":false,"shown":false,"summary":"plain fallback","subtype":"future_subtype","future_fact":true,"content":{"text":"saved text","unknown":{"script":"<script>inert</script>","count":9007199254740991},"nullable":null,"values":[false,0,""]}}}""",
        )

    private fun change(patch: JsonObject = obj("{}")) =
        obj("""{"conversation_id":"c","epoch":"e","version":808,"item_id":101,"base_rev":303,"rev":707,"changes":$patch}""")

    private fun suffix(
        text: String,
        conversation: String = "c",
    ) = obj(
        """{"conversation_id":"$conversation","epoch":"e","version":4,"item_id":1,"base_rev":3,"rev":4,"text":${JsonPrimitive(
            text,
        )}}""",
    )

    private fun obj(json: String) = MobileJson.parseToJsonElement(json).jsonObject

    private fun replace(
        raw: JsonObject,
        key: String,
        value: JsonElement,
    ) = JsonObject(raw + (key to value))

    private fun envelope(
        type: String,
        raw: JsonObject,
    ) = Envelope(1, type, "2026-10-10T06:00:00Z", raw)

    private fun metadata(
        type: String,
        raw: JsonObject,
    ): JsonObject {
        val fields = raw.filterKeys { it in setOf("conversation_id", "epoch", "version", "item_id", "rev", "base_rev") }.toMutableMap()
        if (type ==
            added
        ) {
            fields["item_id"] = raw.getValue("item").jsonObject.getValue("id")
            fields["rev"] =
                raw.getValue("item").jsonObject.getValue("rev")
        }
        return JsonObject(fields)
    }

    private fun parts(
        type: String,
        raw: JsonObject,
        chunkChars: Int,
        serialized: String = raw.toString(),
    ): List<Envelope> {
        val digest = MessageDigest.getInstance("SHA-256").digest((type + "\u0000" + serialized).toByteArray()).toHexString()
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < serialized.length) {
            var end = minOf(start + chunkChars, serialized.length)
            if (end < serialized.length && serialized[end - 1].isHighSurrogate()) end++
            chunks.add(serialized.substring(start, end))
            start = end
        }
        var offset = 0
        return chunks.mapIndexed { index, data ->
            val continuation =
                obj(
                    """{"update_id":"$digest","index":$index,"offset":$offset,"total_bytes":${serialized.toByteArray().size},"final":${index == chunks.lastIndex}}""",
                )
            offset += data.toByteArray().size
            envelope(type, JsonObject(metadata(type, raw) + mapOf("continuation" to continuation, "data" to JsonPrimitive(data))))
        }
    }

    private fun editContinuation(
        part: Envelope,
        key: String,
        value: JsonElement,
    ): Envelope =
        part.copy(
            payload =
                replace(
                    part.payload.jsonObject,
                    "continuation",
                    replace(
                        part.payload.jsonObject
                            .getValue("continuation")
                            .jsonObject,
                        key,
                        value,
                    ),
                ),
        )

    private fun complete(outcomes: List<ThreadDecodeOutcome<ThreadUpdateDto>>): ThreadUpdateDto {
        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is ThreadDecodeOutcome.Complete)
        return (outcomes.single() as ThreadDecodeOutcome.Complete).value
    }

    private fun pending(outcomes: List<ThreadDecodeOutcome<ThreadUpdateDto>>) {
        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is ThreadDecodeOutcome.Pending)
    }

    private fun repair(outcomes: List<ThreadDecodeOutcome<ThreadUpdateDto>>) {
        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is ThreadDecodeOutcome.Repair)
    }
}
