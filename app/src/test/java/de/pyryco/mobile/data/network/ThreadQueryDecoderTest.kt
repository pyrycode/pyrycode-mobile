package de.pyryco.mobile.data.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/** Fixtures follow daemon #3233, protocol-mobile.md Thread query messages and thread_query_test.go. */
class ThreadQueryDecoderTest {
    private var savedSink = RelayLog.sink

    @Before
    fun captureLogs() {
        savedSink = RelayLog.sink
        RelayLog.sink = { _, _, _ -> }
    }

    @After
    fun restoreLogs() {
        RelayLog.sink = savedSink
    }

    private val request = ThreadQueryRequest.CatchUp("conv-a", "epoch-b", 707uL)

    @Test
    fun requestsPreserveUnsignedNumbersAndOptionalLimits() {
        for (bound in listOf(0uL, Long.MAX_VALUE.toULong(), Long.MAX_VALUE.toULong() + 1uL, ULong.MAX_VALUE)) {
            val catchUp = ThreadQueryRequest.CatchUp("conv-a", "epoch-b", bound)
            val page = ThreadQueryRequest.Page("conv-a", bound)
            assertEquals("thread_catch_up", catchUp.type)
            assertEquals("thread_page", page.type)
            assertEquals(obj("""{"conversation_id":"conv-a","epoch":"epoch-b","version":$bound}"""), catchUp.payload())
            assertEquals(obj("""{"conversation_id":"conv-a","before_order":$bound}"""), page.payload())
            val encoded = MobileJson.encodeToString(Envelope(43, catchUp.type, "now", catchUp.payload()))
            assertTrue(encoded.contains("\"version\":$bound"))
            assertFalse(encoded.contains("\"limit\""))
        }
    }

    @Test
    fun limitsDefaultClampAndNeverTruncateNonzeroCatchUp() {
        for (limit in listOf(null, 0L, 1L, 256L, 257L, Long.MAX_VALUE)) {
            for (r in listOf(
                ThreadQueryRequest.CatchUp("conv-a", "epoch-b", 707uL, limit),
                ThreadQueryRequest.Page("conv-a", 809uL, limit),
            )) {
                assertEquals(if (limit == null || limit == 0L) 50 else limit.coerceAtMost(256).toInt(), r.windowLimit)
                assertEquals(limit?.coerceAtMost(256)?.let(::JsonPrimitive), r.payload()["limit"])
            }
        }
        for (limit in listOf(-1L, Long.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { ThreadQueryRequest.CatchUp("c", "e", 1uL, limit) }
            assertThrows(IllegalArgumentException::class.java) { ThreadQueryRequest.Page("c", 0uL, limit) }
        }
        val d = decoder(ThreadQueryRequest.CatchUp("conv-a", "epoch-b", 707uL, 1))
        val items = List(300) { item(it.toLong() + 1) }
        assertEquals(300, batch(d.decode(frame(batchRaw(items)))).items.size)
        assertTrue(fact(d.decode(frame(marker()))) is ThreadQueryReply.Success)
    }

    @Test
    fun emptyAndMultibatchRepliesNeedMarker() {
        val d = decoder()
        assertTrue(batch(d.decode(frame(batchRaw(emptyList())))).items.isEmpty())
        assertEquals(2, batch(d.decode(frame(batchRaw(listOf(item(2), item(1)))))).items.size)
        val final = fact(d.decode(frame(marker()))) as ThreadQueryReply.Success
        assertEquals(808uL, final.checkpoint.version)
        assertEquals(707uL, (final.coverage as ThreadQueryReply.Coverage.CatchUp).fromVersion)
        val empty = fact(decoder().decode(frame(marker()))) as ThreadQueryReply.Success
        assertEquals("epoch-b", empty.checkpoint.epoch)
    }

    @Test
    fun suppliedOrderIdentityAndUnknownJsonSurvive() {
        val omitted = item(2)
        val full =
            obj(
                """{"id":1,"kind":"unknown-kind","rev":37,"order":23,"ended_order":41,"session":"session","agent":"agent","no_child":true,"turn":"turn","parent":5,"status":"unknown-status","active":true,"shown":false,"summary":"inert\ntext","subtype":"future","content":{"unknown":[false,0,null,-1.2300E+1000],"clear":null},"future":null}""",
            )
        val raw = batchRaw(listOf(omitted, full, omitted))
        val value = batch(decoder().decode(frame(raw)))
        assertSame(raw, value.raw)
        assertEquals(listOf(2L, 1L, 2L), value.items.map { it.id })
        assertEquals(listOf(omitted, full, omitted), value.items.map { it.raw })
        assertNull(value.items.first().order)
        assertNull(value.items.first().session)
        assertEquals("unknown-kind", value.items[1].kind)
        assertEquals("unknown-status", value.items[1].status)
        assertEquals(false, value.items[1].shown)
        assertEquals(5L, value.items[1].parent)
    }

    @Test
    fun bothWindowQueriesPreserveRequiredZeroFalseAndUnsignedCoverage() {
        for (r in listOf(ThreadQueryRequest.CatchUp("conv-a", "epoch-b", 0uL), ThreadQueryRequest.Page("conv-a", ULong.MAX_VALUE))) {
            val d = decoder(r)
            val raw = windowMarker(0uL, ULong.MAX_VALUE, false)
            val value = fact(d.decode(frame(raw))) as ThreadQueryReply.Success
            val coverage = value.coverage as ThreadQueryReply.Coverage.Window
            assertSame(raw, value.raw)
            assertEquals(0uL, coverage.lowerOrder)
            assertEquals(ULong.MAX_VALUE, coverage.upperOrder)
            assertEquals(0uL, coverage.continuation)
            assertFalse(coverage.olderExists)
        }
    }

    @Test
    fun parentsAndActiveExtrasNeverExpandCoverage() {
        val d = decoder(ThreadQueryRequest.Page("conv-a", 809uL))
        val extras = listOf(edit(item(1), "order", JsonPrimitive(1)), edit(item(2), "order", JsonPrimitive(900)), item(3))
        assertEquals(extras, batch(d.decode(frame(batchRaw(extras)))).items.map { it.raw })
        val coverage =
            (
                fact(
                    d.decode(frame(windowMarker(101uL, 809uL, true))),
                ) as ThreadQueryReply.Success
            ).coverage as ThreadQueryReply.Coverage.Window
        assertEquals(101uL, coverage.lowerOrder)
        assertEquals(809uL, coverage.upperOrder)
        assertEquals(101uL, coverage.continuation)
        assertTrue(coverage.olderExists)
    }

    @Test
    fun unsignedCheckpointBoundariesRemainExact() {
        for (version in listOf(0uL, Long.MAX_VALUE.toULong(), Long.MAX_VALUE.toULong() + 1uL, ULong.MAX_VALUE)) {
            val d = decoder(ThreadQueryRequest.Page("conv-a", ULong.MAX_VALUE))
            val value = batch(d.decode(frame(edit(batchRaw(), "version", number(version)))))
            assertEquals(version, value.checkpoint.version)
            val result =
                fact(
                    d.decode(frame(edit(windowMarker(0uL, ULong.MAX_VALUE, false), "version", number(version)))),
                ) as ThreadQueryReply.Success
            assertEquals(version, result.checkpoint.version)
        }
        val d = decoder(ThreadQueryRequest.CatchUp("conv-a", "epoch-b", ULong.MAX_VALUE))
        val result =
            fact(
                d.decode(frame(edit(edit(marker(), "version", number(ULong.MAX_VALUE)), "from_version", number(ULong.MAX_VALUE)))),
            ) as ThreadQueryReply.Success
        assertEquals(ULong.MAX_VALUE, (result.coverage as ThreadQueryReply.Coverage.CatchUp).fromVersion)
    }

    @Test
    fun requiredBatchAndItemFieldsCannotBeCoercedOrOmitted() {
        val raw = batchRaw()
        for (key in listOf("conversation_id", "epoch", "version", "items", "final")) {
            rejectedThenSticky(JsonObject(raw - key))
            for (bad in listOf(JsonNull, JsonPrimitive("false"), obj("{}"))) rejectedThenSticky(edit(raw, key, bad))
        }
        for (token in listOf("-1", "1.0", "1e0", "\"1\"", "00", "18446744073709551616", "true", "null")) {
            rejectedThenSticky(edit(raw, "version", MobileJson.parseToJsonElement(token)))
        }
        for (key in listOf("id", "kind", "rev", "status", "active", "shown", "summary", "content")) {
            rejectedThenSticky(batchRaw(listOf(JsonObject(item(1) - key))))
        }
        rejectedThenSticky(batchRaw(listOf(edit(item(1), "id", JsonPrimitive("1")))))
        rejectedThenSticky(batchRaw(listOf(edit(item(1), "content", MobileJson.parseToJsonElement("00")))))
    }

    @Test
    fun coverageIsMarkerOnlyAndExactlyOneShape() {
        for (key in listOf("from_version", "lower_order", "upper_order", "continuation", "older_exists")) {
            rejectedThenSticky(edit(batchRaw(), key, JsonPrimitive(0)))
            rejectedThenSticky(JsonObject(marker() - "from_version"))
        }
        rejectedThenSticky(edit(marker(), "items", JsonArray(listOf(item(1)))))
        rejectedThenSticky(edit(marker(), "from_version", JsonPrimitive(0)))
        rejectedThenSticky(edit(marker(), "from_version", JsonPrimitive(809)))
        rejectedThenSticky(edit(marker(), "from_version", JsonPrimitive(706)))
        for (key in listOf("lower_order", "upper_order", "continuation", "older_exists")) {
            rejectedThenSticky(edit(marker(), key, JsonPrimitive(0)))
        }
        val page = ThreadQueryRequest.Page("conv-a", 809uL)
        val raw = windowMarker(101uL, 809uL, true)
        for (key in listOf("lower_order", "upper_order", "continuation", "older_exists")) {
            rejectedThenSticky(JsonObject(raw - key), page)
            rejectedThenSticky(edit(raw, key, JsonNull), page)
            rejectedThenSticky(edit(raw, key, JsonPrimitive("0")), page)
        }
        rejectedThenSticky(edit(raw, "lower_order", JsonPrimitive(810)), page)
        rejectedThenSticky(edit(raw, "continuation", JsonPrimitive(100)), page)
        rejectedThenSticky(edit(raw, "from_version", JsonPrimitive(707)), page)
        rejectedThenSticky(marker(), page)
        rejectedThenSticky(raw)
    }

    @Test
    fun multipartRetainsLogicalPayloadWithoutCompletingRequest() {
        // Use a JSON builder for exact escaped payload bytes, as the daemon fixture does.
        val logical =
            batchRaw(
                listOf(
                    edit(
                        item(1),
                        "content",
                        JsonObject(
                            mapOf(
                                "unknown" to JsonPrimitive("🌲\n\"\\\u0000".repeat(1000)),
                                "clear" to JsonNull,
                            ),
                        ),
                    ),
                ),
            )
        val d = decoder()
        val p = parts(logical, 113)
        p.dropLast(1).forEach { assertTrue(d.decode(it).single() is ThreadDecodeOutcome.Pending) }
        val value = batch(d.decode(p.last()))
        assertEquals(logical, value.raw)
        assertEquals(logical["items"], JsonArray(value.items.map { it.raw }))
        assertTrue(fact(d.decode(frame(marker()))) is ThreadQueryReply.Success)
    }

    @Test
    fun interruptedAssemblyCannotBeRescuedByMarker() {
        val p = parts(batchRaw(), 31)
        for (sent in listOf(p.take(1), p.dropLast(1), listOf(p.first(), p.last()))) {
            val d = decoder()
            sent.forEach { d.decode(it) }
            repair(d.decode(frame(marker())))
            p.forEach { assertFalse(d.decode(it).any { outcome -> outcome is ThreadDecodeOutcome.Complete<*> }) }
            repair(d.decode(frame(marker())))
        }
    }

    @Test
    fun failedPartsCannotBeRescuedByLaterFrames() {
        val p = parts(batchRaw(), 31)
        val corruptions =
            listOf(
                partEdit(p[1], "index", JsonPrimitive(99)),
                partEdit(p[1], "offset", JsonPrimitive(99)),
                partEdit(p[1], "total_bytes", JsonPrimitive(99999)),
                partEdit(p[1], "update_id", JsonPrimitive("0".repeat(64))),
                p[1].copy(payload = edit(p[1].payload.jsonObject, "data", JsonPrimitive(""))),
                p[1].copy(payload = edit(p[1].payload.jsonObject, "data", JsonPrimitive("\uD800"))),
                p[1].copy(payload = edit(p[1].payload.jsonObject, "item_id", JsonPrimitive(2))),
                p[1].copy(payload = edit(p[1].payload.jsonObject, "rev", JsonPrimitive(99))),
                p[1].copy(payload = edit(p[1].payload.jsonObject, "base_rev", JsonPrimitive(0))),
            )
        for (bad in corruptions) {
            val d = decoder()
            assertTrue(d.decode(p.first()).single() is ThreadDecodeOutcome.Pending)
            repair(d.decode(bad))
            p.drop(1).forEach { repair(d.decode(it)) }
            repair(d.decode(frame(marker())))
        }
        val one = parts(batchRaw(), Int.MAX_VALUE).single()
        for (bad in listOf(
            partEdit(one, "update_id", JsonPrimitive("0".repeat(64))),
            one.copy(payload = edit(one.payload.jsonObject, "item_id", JsonPrimitive(2))),
        )) {
            val d = decoder()
            repair(d.decode(bad))
            repair(d.decode(frame(marker())))
        }
    }

    @Test
    fun changedAttributionCannotCompleteReply() {
        val p = parts(batchRaw(), 31)
        val changes: List<(Envelope) -> Envelope> =
            listOf(
                { it.copy(inReplyTo = null) },
                { it.copy(inReplyTo = 44) },
                { it.copy(conversationId = JsonPrimitive("other-route")) },
                { it.copy(conversationId = null) },
                { it.copy(sessionProvenance = SessionProvenance(JsonNull)) },
                { it.copy(sessionProvenance = SessionProvenance()) },
                { it.copy(sessionStateCleared = JsonPrimitive(true)) },
                { it.copy(payload = edit(it.payload.jsonObject, "conversation_id", JsonPrimitive("other-conv"))) },
                { it.copy(payload = edit(it.payload.jsonObject, "epoch", JsonPrimitive("other-epoch"))) },
                { it.copy(payload = edit(it.payload.jsonObject, "version", JsonPrimitive(809))) },
            )
        for (change in changes) {
            for (multipart in listOf(false, true)) {
                val d = decoder()
                d.decode(if (multipart) p.first() else frame(batchRaw()))
                repair(d.decode(change(if (multipart) p[1] else frame(marker()))))
                repair(d.decode(frame(marker())))
            }
        }
    }

    @Test
    fun envelopePresenceAndInvalidMetadataAreCheckedWithoutRecursiveComparison() {
        for (session in listOf(null, JsonNull, JsonPrimitive("s"))) {
            val d = decoder()
            val start = frame(batchRaw()).copy(conversationId = null, sessionProvenance = SessionProvenance(session))
            batch(d.decode(start))
            assertTrue(fact(d.decode(start.copy(payload = marker()))) is ThreadQueryReply.Success)
        }
        var nested: JsonElement = JsonNull
        repeat(6000) { nested = JsonArray(listOf(nested)) }
        for (bad in listOf(
            frame(marker()).copy(conversationId = nested),
            frame(marker()).copy(sessionProvenance = SessionProvenance(nested)),
            frame(marker()).copy(sessionStateCleared = nested),
        )) {
            repair(decoder().decode(bad))
        }
    }

    @Test
    fun checkpointAndRoutingRemainConsistentAcrossOrdinaryBatches() {
        val d = decoder()
        batch(d.decode(frame(batchRaw())))
        batch(d.decode(frame(batchRaw()).copy(id = 999, ts = "later")))
        assertTrue(fact(d.decode(frame(marker()))) is ThreadQueryReply.Success)
        val other = decoder()
        repair(other.decode(frame(edit(batchRaw(), "epoch", JsonPrimitive("wrong")))))
        repair(other.decode(frame(marker())))
    }

    @Test
    fun errorWithoutConversationRemainsAttributed() {
        val d = decoder()
        d.decode(parts(batchRaw(), 31).first())
        val raw =
            obj(
                """{"code":"app.busy","message":"private error text","retryable":false,"retry_after_s":0,"min_client_version":"1.2.3","future":null}""",
            )
        val value = fact(d.decode(frame(raw, "error"))) as ThreadQueryReply.Failure
        assertEquals(raw, value.raw)
        assertEquals("app.busy", value.error.code)
        assertEquals("private error text", value.error.message)
        assertFalse(value.error.retryable)
        assertEquals("1.2.3", value.error.minClientVersion)
        assertEquals("host", d.hostId)
        assertEquals("connection", d.connectionId)
        assertEquals(43L, d.requestId)
        assertEquals("conv-a", d.request.conversationId)
        repair(d.decode(frame(marker())))
    }

    @Test
    fun malformedErrorsAndChangedCorrelationStayTerminalFailures() {
        val valid = obj("""{"code":"app.busy","message":"text","retryable":true,"conversation_id":"conv-a","retry_after_s":1}""")
        for (key in listOf("code", "message", "retryable")) {
            val d = decoder()
            repair(d.decode(frame(JsonObject(valid - key), "error")))
            repair(d.decode(frame(marker())))
        }
        for (bad in listOf(
            edit(valid, "conversation_id", JsonPrimitive("other")),
            edit(valid, "retryable", JsonPrimitive("true")),
            edit(valid, "retry_after_s", JsonPrimitive("1")),
        )) {
            repair(decoder().decode(frame(bad, "error")))
        }
        val d = decoder()
        repair(d.decode(frame(valid, "error").copy(inReplyTo = 44)))
        repair(d.decode(frame(marker())))
    }

    @Test
    fun resetNeverCertifiesCoverage() {
        val d = decoder()
        d.decode(parts(batchRaw(), 31).first())
        val raw = obj("""{"conversation_id":"conv-a","epoch":"new-epoch","version":18446744073709551615}""")
        val value = fact(d.decode(frame(raw, "thread_reset"))) as ThreadQueryReply.Reset
        assertEquals("new-epoch", value.checkpoint.epoch)
        assertEquals(ULong.MAX_VALUE, value.checkpoint.version)
        repair(d.decode(frame(marker())))
        for (key in listOf("items", "final", "from_version", "lower_order", "upper_order", "continuation", "older_exists")) {
            repair(decoder().decode(frame(edit(raw, key, JsonPrimitive(0)), "thread_reset")))
        }
    }

    @Test
    fun expiryCannotBeRescuedByMarker() {
        var now = 0L
        val p = parts(batchRaw(), 31)
        for (idle in listOf(true, false)) {
            now = 0
            val d = ThreadQueryDecoder("host", "connection", 43, request, lifetimeMillis = 10, nowMillis = { now })
            d.decode(p.first())
            now = 9
            d.decode(p[1])
            now = 10
            if (idle) assertEquals("timeout", d.expire().single().code)
            repair(d.decode(frame(marker())))
            repair(d.decode(p.last()))
        }
    }

    @Test
    fun abandonmentAndCrossConnectionIsolation() {
        val p = parts(batchRaw(), 31)
        val old = decoder()
        old.decode(p.first())
        assertEquals("abandoned", old.abandon().single().code)
        repair(old.decode(frame(marker())))
        for ((host, connection) in listOf("host" to "new", "other" to "connection")) {
            val d = ThreadQueryDecoder(host, connection, 43, request)
            repair(d.decode(p.last()))
            repair(d.decode(frame(marker())))
            val fresh = ThreadQueryDecoder(host, connection, 43, request)
            p.dropLast(1).forEach { assertTrue(fresh.decode(it).single() is ThreadDecodeOutcome.Pending) }
            batch(fresh.decode(p.last()))
            assertTrue(fact(fresh.decode(frame(marker()))) is ThreadQueryReply.Success)
        }
        val unused = decoder()
        unused.abandon()
        repair(unused.decode(frame(marker())))
    }

    @Test
    fun terminalSuccessIsEmittedOnce() {
        val d = decoder()
        assertTrue(fact(d.decode(frame(marker()))) is ThreadQueryReply.Success)
        repair(d.decode(frame(marker())))
        repair(d.decode(frame(batchRaw())))
    }

    @Test
    fun malformedOrMultipleItemAssembliesNeverProduceFacts() {
        for (raw in listOf(edit(batchRaw(), "final", JsonPrimitive(true)), batchRaw(emptyList()), batchRaw(listOf(item(1), item(2))))) {
            val d = decoder()
            val p = parts(raw, 31)
            p.dropLast(1).forEach { d.decode(it) }
            repair(d.decode(p.last()))
            repair(d.decode(frame(marker())))
        }
        val malformed = batchRaw().toString().replace("\"summary\":\"text\"", "\"summary\":\"raw\ncontrol\"")
        val d = decoder()
        val p = parts(batchRaw(), 31, malformed)
        p.dropLast(1).forEach { d.decode(it) }
        repair(d.decode(p.last()))
        repair(d.decode(frame(marker())))
    }

    @Test
    fun interleavedRequestsDoNotSharePendingOrTerminalState() {
        val a = decoder()
        val b = ThreadQueryDecoder("host", "connection", 44, request)
        val p = parts(batchRaw(), 31)
        for (part in p.dropLast(1)) {
            a.decode(part)
            b.decode(part.copy(inReplyTo = 44))
        }
        a.abandon()
        batch(b.decode(p.last().copy(inReplyTo = 44)))
        assertTrue(fact(b.decode(frame(marker()).copy(inReplyTo = 44))) is ThreadQueryReply.Success)
        repair(a.decode(frame(marker())))
    }

    @Test
    fun ordinaryAndMultipartNestingRemainStackSafe() {
        var content: JsonElement = JsonNull
        repeat(6000) { content = JsonArray(listOf(content)) }
        val raw = batchRaw(listOf(edit(item(1), "content", content)))
        assertSame(raw, batch(decoder().decode(frame(raw))).raw)
        val serialized = batchRaw().toString().replace("\"content\":null", "\"content\":" + "[".repeat(6000) + "null" + "]".repeat(6000))
        val d = decoder()
        val p = parts(batchRaw(), 8192, serialized)
        p.dropLast(1).forEach { assertTrue(d.decode(it).single() is ThreadDecodeOutcome.Pending) }
        batch(d.decode(p.last()))
        assertTrue(fact(d.decode(frame(marker()))) is ThreadQueryReply.Success)
        val small = ThreadQueryDecoder("host", "connection", 43, request, maxPayloadBytes = 256)
        assertEquals("capacity", repair(small.decode(frame(raw))).code)
    }

    @Test
    fun metadataAndFragmentBudgetsAreCombinedAndCannotBeRescued() {
        val logical = batchRaw()
        val p = parts(logical, Int.MAX_VALUE).single().copy(sessionProvenance = SessionProvenance(JsonPrimitive("s".repeat(300))))
        val payloadSize = logical.toString().toByteArray().size
        val d = ThreadQueryDecoder("host", "connection", 43, request, maxPayloadBytes = payloadSize + 100)
        assertEquals("capacity", repair(d.decode(p)).code)
        repair(d.decode(frame(marker())))
        val cap = ThreadQueryDecoder("host", "connection", 43, request, maxParts = 1)
        val many = parts(logical, 31)
        cap.decode(many.first())
        assertEquals("capacity", repair(cap.decode(many[1])).code)
        repair(cap.decode(frame(marker())))
    }

    @Test
    fun rejectedBatchIsAtomicAndCannotBeRescuedByValidBatch() {
        val d = decoder()
        repair(d.decode(frame(batchRaw(listOf(item(1), JsonObject(item(2) - "content"))))))
        repair(d.decode(frame(batchRaw())))
        repair(d.decode(frame(marker())))
        val parts = parts(batchRaw(), 31)
        val interrupted = decoder()
        interrupted.decode(parts.first())
        repair(interrupted.decode(frame(batchRaw())))
        repair(interrupted.decode(frame(marker())))
    }

    @Test
    fun everyCoverageIntegerRejectsNoncanonicalOrOverflowingTokens() {
        val r = ThreadQueryRequest.Page("conv-a", ULong.MAX_VALUE)
        for (key in listOf("lower_order", "upper_order", "continuation")) {
            for (token in listOf("-1", "1.0", "1e0", "00", "18446744073709551616", "true")) {
                rejectedThenSticky(edit(windowMarker(0uL, ULong.MAX_VALUE, false), key, MobileJson.parseToJsonElement(token)), r)
            }
        }
        for (token in listOf("-1", "1.0", "1e0", "00", "18446744073709551616", "true", "\"707\"")) {
            rejectedThenSticky(edit(marker(), "from_version", MobileJson.parseToJsonElement(token)))
        }
    }

    @Test
    fun combinedAssemblyBoundIsInclusiveAndAggregateLimitIsEnforced() {
        val logical = batchRaw()
        val p = parts(logical, Int.MAX_VALUE).single()
        val route = obj("""{"conversation_id":"route","session_id":"s","session_state_cleared":false}""")
        val metadata =
            edit(obj("""{"conversation_id":"conv-a","epoch":"epoch-b","version":808,"item_id":1,"rev":37}"""), "envelope_routing", route)
        val bound = logical.toString().toByteArray().size + metadata.toString().toByteArray().size
        val inclusive = ThreadQueryDecoder("host", "connection", 43, request, maxPayloadBytes = bound, maxBufferedBytes = bound)
        batch(inclusive.decode(p))
        assertTrue(fact(inclusive.decode(frame(marker()))) is ThreadQueryReply.Success)
        for ((payloadBound, bufferBound) in listOf(bound - 1 to bound, bound to bound - 1)) {
            val d = ThreadQueryDecoder("host", "connection", 43, request, maxPayloadBytes = payloadBound, maxBufferedBytes = bufferBound)
            assertEquals("capacity", repair(d.decode(p)).code)
            repair(d.decode(frame(marker())))
        }
    }

    @Test
    fun digestLengthAndRepeatedFinalFlagFailuresAreSticky() {
        val p = parts(batchRaw(), Int.MAX_VALUE).single()
        val raw = p.payload.jsonObject
        val data = (raw["data"] as JsonPrimitive).content.replace("future-kind", "mutant-kind")
        val cases =
            listOf(
                p.copy(payload = edit(raw, "data", JsonPrimitive(data))),
                partEdit(p, "total_bytes", JsonPrimitive((raw["data"] as JsonPrimitive).content.toByteArray().size + 1)),
                partEdit(p, "final", JsonPrimitive("true")),
                partEdit(p, "final", JsonPrimitive(false)),
            )
        for (bad in cases) {
            val d = decoder()
            val outcome = d.decode(bad).single()
            assertFalse(outcome is ThreadDecodeOutcome.Complete<*>)
            repair(d.decode(frame(marker())))
            repair(d.decode(p))
        }
    }

    @Test
    fun terminalFramesCannotUseObjectContinuationToBypassAdmission() {
        for (type in listOf("error", "thread_reset")) {
            val raw =
                if (type == "error") {
                    obj("""{"code":"app.busy","message":"text","retryable":false}""")
                } else {
                    obj("""{"conversation_id":"conv-a","epoch":"epoch-b","version":808}""")
                }
            val oversized = edit(edit(raw, "continuation", obj("{}")), "future", JsonPrimitive("x".repeat(1000)))
            val d = ThreadQueryDecoder("host", "connection", 43, request, maxPayloadBytes = 256)
            assertEquals("capacity", repair(d.decode(frame(oversized, type))).code)
            repair(d.decode(frame(marker())))
        }
    }

    @Test
    fun multipartUnsignedCheckpointAndMixedBatchesRetainSuppliedSequence() {
        val d = decoder(ThreadQueryRequest.Page("conv-a", ULong.MAX_VALUE))
        val supplied = mutableListOf<Long>()
        for (id in listOf(1L, 2L, 1L)) {
            val raw = edit(batchRaw(listOf(item(id))), "version", number(ULong.MAX_VALUE))
            val p = parts(raw, 31)
            p.dropLast(1).forEach { assertTrue(d.decode(it).single() is ThreadDecodeOutcome.Pending) }
            val batch = batch(d.decode(p.last()))
            assertEquals(ULong.MAX_VALUE, batch.checkpoint.version)
            supplied.addAll(batch.items.map { it.id })
        }
        assertEquals(listOf(1L, 2L, 1L), supplied)
        batch(d.decode(frame(edit(batchRaw(emptyList()), "version", number(ULong.MAX_VALUE)))))
        assertTrue(
            fact(
                d.decode(frame(edit(windowMarker(0uL, ULong.MAX_VALUE, false), "version", number(ULong.MAX_VALUE)))),
            ) is ThreadQueryReply.Success,
        )
    }

    @Test
    fun diagnosticsAndStringRepresentationsRevealNoPayloads() {
        val previousEnabled = RelayLog.enabled
        val previousSink = RelayLog.sink
        val logs = mutableListOf<String>()
        RelayLog.enabled = true
        RelayLog.sink = { _, _, text -> logs.add(text) }
        try {
            val d = decoder()
            val secret = "private-secret-message"
            val value = batch(d.decode(frame(batchRaw(listOf(edit(item(1), "summary", JsonPrimitive(secret)))))))
            val failure = fact(d.decode(frame(obj("""{"code":"private-code","message":"$secret","retryable":false}"""), "error")))
            val strings =
                listOf(
                    d.toString(),
                    request.toString(),
                    value.toString(),
                    value.checkpoint.toString(),
                    value.items.single().toString(),
                    failure.toString(),
                )
            assertFalse((logs + strings).any { secret in it || "private-code" in it || "conv-a" in it || "epoch-b" in it })
        } finally {
            RelayLog.enabled = previousEnabled
            RelayLog.sink = previousSink
        }
    }

    private fun decoder(r: ThreadQueryRequest = request) = ThreadQueryDecoder("host", "connection", 43, r)

    private fun obj(text: String) = MobileJson.parseToJsonElement(text).jsonObject

    private fun number(value: ULong) = MobileJson.parseToJsonElement(value.toString())

    private fun edit(
        raw: JsonObject,
        key: String,
        value: JsonElement,
    ) = JsonObject(raw + (key to value))

    private fun item(id: Long) =
        obj(
            """{"id":$id,"kind":"future-kind","rev":37,"status":"future-status","active":false,"shown":false,"summary":"text","content":null}""",
        )

    private fun batchRaw(items: List<JsonObject> = listOf(item(1))) =
        obj("""{"conversation_id":"conv-a","epoch":"epoch-b","version":808,"items":[],"final":false}""").let {
            edit(it, "items", JsonArray(items))
        }

    private fun marker() =
        obj("""{"conversation_id":"conv-a","epoch":"epoch-b","version":808,"items":[],"final":true,"from_version":707}""")

    private fun windowMarker(
        lower: ULong,
        upper: ULong,
        older: Boolean,
    ) = obj(
        """{"conversation_id":"conv-a","epoch":"epoch-b","version":808,"items":[],"final":true,"lower_order":$lower,"upper_order":$upper,"continuation":$lower,"older_exists":$older}""",
    )

    private fun frame(
        raw: JsonObject,
        type: String = "thread_items",
    ) = Envelope(
        51,
        type,
        "now",
        raw,
        inReplyTo = 43,
        conversationId = JsonPrimitive("route"),
        sessionProvenance = SessionProvenance(JsonPrimitive("s")),
    )

    private fun fact(outcomes: List<ThreadDecodeOutcome<ThreadQueryReply>>) = (outcomes.single() as ThreadDecodeOutcome.Complete).value

    private fun batch(outcomes: List<ThreadDecodeOutcome<ThreadQueryReply>>) = fact(outcomes) as ThreadQueryReply.Batch

    private fun repair(outcomes: List<ThreadDecodeOutcome<ThreadQueryReply>>): ThreadDecodeOutcome.Repair {
        assertTrue(outcomes.isNotEmpty())
        assertTrue(outcomes.all { it is ThreadDecodeOutcome.Repair })
        return outcomes.first() as ThreadDecodeOutcome.Repair
    }

    private fun rejectedThenSticky(
        raw: JsonObject,
        r: ThreadQueryRequest = request,
    ) {
        val d = decoder(r)
        repair(d.decode(frame(raw)))
        repair(d.decode(frame(marker())))
    }

    private fun partEdit(
        part: Envelope,
        key: String,
        value: JsonElement,
    ): Envelope {
        val raw = part.payload.jsonObject
        return part.copy(payload = edit(raw, "continuation", edit(raw.getValue("continuation").jsonObject, key, value)))
    }

    private fun parts(
        raw: JsonObject,
        chunk: Int,
        serialized: String = raw.toString(),
    ): List<Envelope> {
        val bytes = serialized.toByteArray(Charsets.UTF_8)
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .apply {
                    update("thread_items".toByteArray())
                    update(0.toByte())
                }.digest(bytes)
                .toHexString()
        var offset = 0
        val fragments = serialized.chunked(chunk).toMutableList()
        // Keep surrogate pairs together, so each string is a valid UTF-8 fragment.
        for (index in 0 until fragments.lastIndex) {
            if (fragments[index].lastOrNull()?.isHighSurrogate() == true) {
                val high = fragments[index].last()
                fragments[index] = fragments[index].dropLast(1)
                fragments[index + 1] = high + fragments[index + 1]
            }
        }
        return fragments.mapIndexed { index, data ->
            val first = (raw["items"] as? JsonArray)?.firstOrNull() as? JsonObject
            val metadata =
                JsonObject(
                    raw.filterKeys { it in setOf("conversation_id", "epoch", "version") } +
                        mapOf(
                            "item_id" to (first?.get("id") ?: JsonPrimitive(1)),
                            "rev" to (first?.get("rev") ?: JsonPrimitive(37)),
                        ),
                )
            val continuation =
                obj(
                    """{"update_id":"$digest","index":$index,"offset":$offset,"total_bytes":${bytes.size},"final":${index == fragments.lastIndex}}""",
                )
            offset += data.toByteArray(Charsets.UTF_8).size
            frame(edit(edit(metadata, "continuation", continuation), "data", JsonPrimitive(data)))
        }
    }
}
