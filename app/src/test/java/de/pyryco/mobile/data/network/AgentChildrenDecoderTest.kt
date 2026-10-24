package de.pyryco.mobile.data.network

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChildrenDecoderTest {
    private val oldSink = RelayLog.sink

    @org.junit.Before fun quietLog() {
        RelayLog.sink = { _, _, _ -> }
    }

    @org.junit.After fun restoreLog() {
        RelayLog.sink = oldSink
    }

    private fun objectOf(text: String) = MobileJson.parseToJsonElement(text) as JsonObject

    private fun summary(
        id: Int = 50,
        order: Int = 0,
        parent: Int = 17,
    ) =
        """{"id":$id,"kind":"agent","order":$order,"rev":7,"parent":$parent,"status":"done","active":false,"shown":true,"headline":"Inspect files","detail_omitted":true,"session":"recorded","no_child":true,"subtree_version":0,"error_summary":"","has_public_children":false,"unknown":{"inert":true}}"""

    private fun raw(
        children: String = summary(),
        final: Boolean = false,
        extra: String = "",
    ) = objectOf("""{"conversation_id":"c","epoch":"e","version":41,"item_id":17,"rev":23,"children":[$children],"final":$final$extra}""")

    private fun cursor(
        id: Int = 50,
        order: Int = 0,
    ) = objectOf("""{"conversation_id":"c","epoch":"e","version":41,"item_id":17,"rev":23,"order":$order,"id":$id}""")

    private fun decoder(cursor: JsonObject? = null) =
        ThreadQueryDecoder("h", "connection", 37, ThreadQueryRequest.Children("c", "e", 17uL, 23uL, cursor))

    private fun frame(raw: JsonObject) = Envelope(90, "thread_children", "now", raw, inReplyTo = 37)

    private fun accepted(
        d: ThreadQueryDecoder,
        raw: JsonObject,
    ) = assertTrue(d.decode(frame(raw)).single() is ThreadDecodeOutcome.Complete)

    private fun rejected(
        d: ThreadQueryDecoder,
        raw: JsonObject,
    ) = assertTrue(d.decode(frame(raw)).single() is ThreadDecodeOutcome.Repair)

    @Test fun markerOnlyEmptyIsAnExplicitChildrenReply() {
        val raw =
            MobileJson.parseToJsonElement(
                """{"conversation_id":"c","epoch":"e","version":0,"item_id":17,"rev":23,"children":[],"final":true,"more":false}""",
            ) as JsonObject
        assertNotNull(decodeThreadQueryReply("thread_children", raw))
    }

    @Test fun boundaryInvariantFiftyRowsIncludingZeroTiesAndStrictContinuation() {
        val d = decoder()
        accepted(d, raw((50..99).joinToString(",") { summary(it) }))
        accepted(d, raw("", true, ",\"more\":true,\"cursor\":${cursor(99)}"))
        val next = decoder(cursor(99))
        accepted(next, raw(summary(100)))
        accepted(next, raw("", true, ",\"more\":false"))
        rejected(decoder(cursor()), raw(summary(50)))
        rejected(decoder(), raw((50..100).joinToString(",") { summary(it) }))
    }

    @Test fun shortPageRequiresSeparateMatchingMarkerAndPreservesFacts() {
        val d = decoder()
        val data = (d.decode(frame(raw())).single() as ThreadDecodeOutcome.Complete).value as ThreadQueryReply.Children
        assertFalse(data.final)
        assertEquals("recorded", data.children.single().session)
        assertEquals(objectOf(summary())["unknown"], data.children.single().raw["unknown"])
        accepted(d, raw("", true, ",\"more\":true,\"cursor\":${cursor()}"))
        rejected(decoder(), raw("", true, ",\"more\":true,\"cursor\":${cursor()}"))
        accepted(decoder(), raw("", true, ",\"more\":false"))
    }

    @Test fun malformedForeignDuplicateUnorderedAndBodyDataCannotSucceed() {
        for (children in listOf(
            summary(parent = 18),
            summary() + "," + summary(),
            summary(51) + "," + summary(50),
            summary().replace("\"active\":false,", ""),
            summary().replace("\"order\":0,", ""),
            summary().replace("\"detail_omitted\":true", "\"detail_omitted\":false"),
            summary().dropLast(1) + ",\"content\":{}}",
            summary().dropLast(1) + ",\"children\":[]}",
            summary().replace("\"has_public_children\":false", "\"has_public_children\":false,\"public_child_count\":1"),
        )) {
            rejected(decoder(), raw(children))
        }
        for (extra in listOf("", ",\"more\":true", ",\"more\":false,\"cursor\":${cursor()}")) {
            val d = decoder()
            accepted(d, raw())
            rejected(d, raw("", true, extra))
        }
    }

    @Test fun ownershipInvariantCaptureRoutingParentAndLastDeliveredCursorMustAgree() {
        for ((key, value) in listOf("version" to "42", "epoch" to "\"other\"", "rev" to "24", "item_id" to "18")) {
            val d = decoder()
            accepted(d, raw())
            rejected(d, objectOf(raw("", true, ",\"more\":false").toString().replace("\"$key\":${raw()[key]}", "\"$key\":$value")))
        }
        val d = decoder()
        accepted(d, raw())
        rejected(d, raw("", true, ",\"more\":true,\"cursor\":${cursor(51)}"))
        val route = decoder()
        accepted(route, raw())
        assertTrue(
            route
                .decode(
                    frame(raw("", true, ",\"more\":false")).copy(conversationId = kotlinx.serialization.json.JsonPrimitive("other")),
                ).single() is ThreadDecodeOutcome.Repair,
        )
        rejected(decoder(cursor()), objectOf(raw(summary(51)).toString().replace("\"version\":41", "\"version\":42")))
    }

    @Test fun requestUsesFiftyAndPreservesUnsignedCursorTokens() {
        val request = ThreadQueryRequest.Children("c", "e", ULong.MAX_VALUE, ULong.MAX_VALUE)
        assertEquals("50", request.payload()["limit"].toString())
        assertEquals(ULong.MAX_VALUE.toString(), request.payload()["item_id"].toString())
        val d = decoder()
        rejected(d, raw(summary().replace("Inspect files", "x".repeat(70000))))
    }

    @Test fun captureInvariantFutureChildCannotStageOrComplete() {
        val d = decoder()
        rejected(d, raw(summary().replace("\"rev\":7", "\"rev\":900")))
        rejected(d, raw("", true, ",\"more\":false"))
        val boundary = decoder()
        accepted(boundary, raw(summary().replace("\"rev\":7", "\"rev\":41")))
        accepted(boundary, raw("", true, ",\"more\":false"))
    }

    @Test fun captureInvariantFutureParentRejectsDataAndMarkerOnlySuccess() {
        for (revision in listOf(900uL, ULong.MAX_VALUE)) {
            for (final in listOf(false, true)) {
                val d = ThreadQueryDecoder("h", "connection", 37, ThreadQueryRequest.Children("c", "e", 17uL, revision))
                val reply = if (final) raw("", true, ",\"more\":false") else raw()
                rejected(d, objectOf(reply.toString().replace("\"rev\":23", "\"rev\":$revision")))
            }
        }
        val d = decoder()
        accepted(d, raw())
        rejected(d, objectOf(raw("", true, ",\"more\":false").toString().replace("\"version\":41", "\"version\":6")))
        val unsigned = ThreadQueryDecoder("h", "connection", 37, ThreadQueryRequest.Children("c", "e", 17uL, ULong.MAX_VALUE))
        accepted(
            unsigned,
            objectOf(
                raw("", true, ",\"more\":false")
                    .toString()
                    .replace("\"rev\":23", "\"rev\":${ULong.MAX_VALUE}")
                    .replace("\"version\":41", "\"version\":${ULong.MAX_VALUE}"),
            ),
        )
    }

    @Test fun capacityInvariantDataAndMarkerShareOneEscapedEnvelopeBudget() {
        accepted(decoder(), raw(summary().replace("\"shown\":true", "\"shown\":false")))
        val d = decoder()
        val data = summary().dropLast(1) + ",\"opaque\":\"${"x".repeat(62000)}\"}"
        accepted(d, raw(data))
        rejected(d, raw("", true, ",\"more\":false,\"opaque\":\"${"x".repeat(4000)}\""))
        val unsigned = decoder()
        accepted(unsigned, objectOf(raw().toString().replace("\"version\":41", "\"version\":18446744073709551615")))
        accepted(
            unsigned,
            objectOf(raw("", true, ",\"more\":false").toString().replace("\"version\":41", "\"version\":18446744073709551615")),
        )
    }
}
