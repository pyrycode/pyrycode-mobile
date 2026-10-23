package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.ui.conversations.thread.listKey
import de.pyryco.mobile.ui.conversations.thread.projectDeferredAuthoritativeThread
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExpansionDetailBudgetTest {
    private val oldSink = RelayLog.sink
    private val logs = mutableListOf<String>()

    @Before fun quiet() {
        RelayLog.sink = { _, _, text -> logs += text }
    }

    @After fun restore() {
        RelayLog.sink = oldSink
        assertTrue(logs.none { "private" in it || "秘密" in it || "cursor" in it })
    }

    private fun json(text: String) = MobileJson.parseToJsonElement(text) as JsonObject

    private fun size(value: JsonObject) = value.toString().toByteArray(Charsets.UTF_8).size

    private fun content(bytes: Int): JsonObject {
        val base = JsonObject(mapOf("output" to JsonPrimitive("秘密\n\"\\"), "unknown" to JsonPrimitive("private")))
        val overhead = size(JsonObject(base + ("padding" to JsonPrimitive(""))))
        return JsonObject(base + ("padding" to JsonPrimitive("x".repeat(bytes - overhead))))
            .also { assertEquals(bytes, size(it)) }
    }

    private fun item(
        id: Long,
        parent: Long = 0,
        kind: String = "tool_call",
    ) = ThreadItemDto.decode(
        json(
            """{"id":$id,"rev":4000,"order":0,"parent":$parent,"kind":"$kind",
            "status":"done","shown":true,"active":false,"headline":"private","summary":"private",
            "detail_omitted":true,"content":{"name":"Read"}}""",
        ),
    )

    private fun child(
        id: Long,
        parent: Long = 90,
    ) = ThreadItemDto.decode(JsonObject(item(id, parent).raw + ("content" to kotlinx.serialization.json.JsonNull)))

    private inner class Fixture(
        val host: String = "h",
        val conversation: String = "c",
        val store: AuthoritativeThreadStore = AuthoritativeThreadStore(),
    ) {
        val sent = mutableListOf<Envelope>()
        private var serial = 0L
        val lane =
            AuthoritativeThreadQueries(host, store, AuthoritativeThreadRoute(host, store), { true }, { ++serial }, {
                sent += it
                true
            }, { 0L }, expansionEnabled = { true })

        fun snapshot() = store.current(host, conversation)

        init {
            val roots = (1L..70L).map { item(it) } + item(90, kind = "agent") + item(91, kind = "agent")
            assertEquals(AuthoritativeThreadOutcome.Applied, store.establishComplete(host, conversation, "e", 4000, roots, 0))
        }

        fun body(
            id: Long,
            owner: Any = this,
            retry: Boolean = false,
        ) = lane.bodies(conversation, owner, snapshot(), setOf(id), if (retry) id else null)

        fun children(
            id: Long = 90,
            owner: Any = this,
            more: Boolean = false,
            retry: Boolean = false,
        ) = lane.children(conversation, owner, snapshot(), setOf(id), if (retry) id else null, if (more) id else null)

        fun frame(
            type: String,
            extra: Map<String, kotlinx.serialization.json.JsonElement>,
            final: Boolean,
        ): Envelope {
            val request = sent.last()
            val raw = request.payload as JsonObject
            return Envelope(
                900,
                type,
                "now",
                JsonObject(
                    mapOf(
                        "conversation_id" to JsonPrimitive(conversation),
                        "epoch" to JsonPrimitive("e"),
                        "version" to JsonPrimitive(5000),
                        "item_id" to requireNotNull(raw["item_id"]),
                        "rev" to requireNotNull(raw["expected_rev"]),
                        "final" to JsonPrimitive(final),
                    ) + extra,
                ),
                inReplyTo = request.id,
            )
        }

        fun acceptBody(contents: JsonObject) {
            val kind = if ((sent.last().payload as JsonObject)["item_id"].toString() in setOf("90", "91")) "agent" else "tool_call"
            lane.accept(frame("thread_tool_body", mapOf("kind" to JsonPrimitive(kind), "content" to contents), false))
            lane.accept(frame("thread_tool_body", mapOf("kind" to JsonPrimitive(kind)), true))
        }

        fun acceptChildren(
            rows: List<ThreadItemDto>,
            more: Boolean = false,
            unknownCursor: String = "",
        ): JsonObject? {
            lane.accept(frame("thread_children", mapOf("children" to JsonArray(rows.map { JsonObject(it.raw - "content") })), false))
            val raw = sent.last().payload as JsonObject
            val cursor =
                if (more) {
                    json(
                        """{"conversation_id":"$conversation","epoch":"e","version":5000,
                "item_id":${raw["item_id"]},"rev":4000,
                "order":${rows.last().order},"id":${rows.last().id},"unknown":"$unknownCursor"}""",
                    )
                } else {
                    null
                }
            val completion =
                mapOf("children" to JsonArray(emptyList()), "more" to JsonPrimitive(more)) +
                    (cursor?.let { mapOf("cursor" to it) } ?: emptyMap())
            lane.accept(frame("thread_children", completion, true))
            return cursor
        }

        fun fillBytes(
            total: Int,
            start: Long = 1,
        ): List<Any> {
            var remaining = total
            var id = start
            val owners = mutableListOf<Any>()
            while (remaining > 0) {
                val bytes = minOf(256 * 1024, remaining)
                val owner = Any().also { owners += it }
                body(id++, owner)
                acceptBody(content(bytes))
                remaining -= bytes
            }
            return owners
        }
    }

    @Test fun byteBoundary() =
        runTest {
            val f = Fixture()
            val before = f.snapshot()
            f.fillBytes(16 * 1024 * 1024)
            assertEquals(
                16 * 1024 * 1024,
                f
                    .snapshot()
                    .bodyContents.values
                    .sumOf(::size),
            )
            f.body(65)
            f.acceptBody(content(128))
            assertFalse(f.snapshot().bodyContents.containsKey(65L to 4000L))
            assertEquals(BodyLoad.Retry, f.snapshot().bodyLoads[65L to 4000L])
            assertSame(before.items, f.snapshot().items)
            assertEquals(before.checkpoint, f.snapshot().checkpoint)
            assertEquals(before.completedVersion, f.snapshot().completedVersion)
            assertEquals(before.coverage, f.snapshot().coverage)
        }

    @Test fun oneByteOverIsRejectedAndCapacityRetryIsExplicit() =
        runTest {
            val f = Fixture()
            val owners = f.fillBytes(16 * 1024 * 1024 - 127)
            f.body(65)
            val requests = f.sent.size
            f.acceptBody(content(128))
            assertEquals(BodyLoad.Retry, f.snapshot().bodyLoads[65L to 4000L])
            repeat(3) { f.body(65) }
            assertEquals(requests, f.sent.size)
            f.lane.releaseBodies(owners.first())
            f.body(65, retry = true)
            f.acceptBody(content(128))
            assertTrue(f.snapshot().bodyContents.containsKey(65L to 4000L))
            assertFalse(f.snapshot().bodyContents.containsKey(1L to 4000L))
            assertEquals(1, logs.count { "code=capacity" in it })
            val admittedReads = f.sent.size
            f.body(1, owners.first())
            assertEquals(admittedReads + 1, f.sent.size)
            assertEquals(BodyLoad.Loading, f.snapshot().bodyLoads[1L to 4000L])
        }

    @Test fun summaryBoundaryAndPageCapacityRetry() =
        runTest {
            val f = Fixture()
            repeat(20) { page ->
                f.children(more = page > 0)
                f.acceptChildren((1000L + page * 50 until 1050L + page * 50).map { child(it) }, more = true)
            }
            val held = requireNotNull(f.snapshot().childPages[90L to 4000L])
            assertEquals(1000, held.items.size)
            f.children(more = true)
            f.acceptChildren(listOf(child(2000)))
            assertSame(held, f.snapshot().childPages[90L to 4000L])
            assertEquals(BodyLoad.Retry, f.snapshot().childLoads[90L to 4000L])
            val second = Any()
            f.children(91, second)
            f.acceptChildren(listOf(child(3000, 91)))
            assertFalse(f.snapshot().childPages.containsKey(91L to 4000L))
            f.lane.children("c", f, f.snapshot(), emptySet())?.close()
            f.children(91, second, retry = true)
            f.acceptChildren(listOf(child(3000, 91)))
            assertFalse(f.snapshot().childPages.containsKey(90L to 4000L))
            assertEquals(
                listOf(3000L),
                f
                    .snapshot()
                    .childPages[91L to 4000L]
                    ?.items
                    ?.map { it.id },
            )
        }

    @Test fun combinedByteBoundaryIncludesRawSummariesAndUnknownCursorFields() =
        runTest {
            for (over in listOf(0, 1)) {
                val f = Fixture()
                val rows = listOf(child(1000))
                val cursor =
                    json(
                        """{"conversation_id":"c","epoch":"e","version":5000,
                "item_id":90,"rev":4000,"order":0,"id":1000,"unknown":"秘密\\\""}""",
                    )
                val pageBytes = rows.sumOf { size(JsonObject(it.raw - "content")) } + size(cursor)
                f.fillBytes(16 * 1024 * 1024 - pageBytes + over)
                f.children()
                f.lane.accept(
                    f.frame("thread_children", mapOf("children" to JsonArray(rows.map { JsonObject(it.raw - "content") })), false),
                )
                val completion = mapOf("children" to JsonArray(emptyList()), "more" to JsonPrimitive(true), "cursor" to cursor)
                f.lane.accept(f.frame("thread_children", completion, true))
                if (over == 0) {
                    assertEquals(cursor, f.snapshot().childPages[90L to 4000L]?.cursor)
                    val retainedSummary =
                        f
                            .snapshot()
                            .childPages[90L to 4000L]
                            ?.items
                            ?.single()
                            ?.summaryPayload
                    assertEquals(JsonObject(rows.single().raw - "content"), retainedSummary)
                    assertEquals(
                        16 * 1024 * 1024,
                        f
                            .snapshot()
                            .bodyContents.values
                            .sumOf(::size) + pageBytes,
                    )
                } else {
                    assertNull(f.snapshot().childPages[90L to 4000L])
                    assertEquals(BodyLoad.Retry, f.snapshot().childLoads[90L to 4000L])
                }
            }
        }

    @Test fun combinedBudgetAndLru() =
        runTest {
            val f = Fixture()
            val bodies = f.fillBytes(16 * 1024 * 1024)
            f.lane.releaseBodies(bodies[0])
            val pageConsumer = f.children()
            val rows = listOf(child(1000))
            f.acceptChildren(rows, true, "escaped")
            assertFalse(f.snapshot().bodyContents.containsKey(1L to 4000L))
            assertEquals(63, f.snapshot().bodyContents.size)
            pageConsumer?.close()
            f.body(2, bodies[1])
            f.lane.releaseBodies(bodies[1])
            val count = f.sent.size
            f.body(2, bodies[1])
            assertEquals("unchanged reopen never refetches", count, f.sent.size)
            f.lane.releaseBodies(bodies[1])
            f.body(65)
            f.acceptBody(content(256 * 1024))
            assertTrue(f.snapshot().childPages.isEmpty())
            assertTrue("reopening made the body newer than the closed page", f.snapshot().bodyContents.containsKey(2L to 4000L))
            f.children()
            assertEquals("an evicted page must be fetched again", count + 2, f.sent.size)
        }

    @Test fun reuseAndConsumerProtection() =
        runTest {
            val f = Fixture()
            val owners = f.fillBytes(16 * 1024 * 1024)
            val second = Any()
            val requests = f.sent.size
            f.body(1, second)
            f.lane.releaseBodies(owners.first())
            f.body(65)
            f.acceptBody(content(128))
            assertEquals(BodyLoad.Retry, f.snapshot().bodyLoads[65L to 4000L])
            assertEquals(requests + 1, f.sent.size)
            f.lane.releaseBodies(second)
            f.body(65, retry = true)
            f.acceptBody(content(128))
            assertFalse(f.snapshot().bodyContents.containsKey(1L to 4000L))
        }

    @Test fun ancestorProtection() =
        runTest {
            for (revision in listOf(4000L, 4001L)) {
                val f = Fixture()
                val parent = f.children()
                f.acceptChildren(listOf(child(1000)))
                if (revision > 4000) {
                    val newer = ThreadItemDto.decode(JsonObject(item(1000, 90).raw + ("rev" to JsonPrimitive(revision))))
                    assertEquals(AuthoritativeThreadOutcome.Applied, f.store.applyBatch("h", "c", "e", revision, listOf(newer), 0))
                }
                f.body(1000, Any())
                f.acceptBody(content(128))
                parent?.close()
                val retainedPage = requireNotNull(f.snapshot().childPages[90L to 4000L])

                fun childVisible(): Boolean {
                    val presentation = projectDeferredAuthoritativeThread("h", "c", f.snapshot())
                    return presentation.rows.any {
                        presentation.bodyItem(it.listKey(0))?.let { child ->
                            child.id == 1000L && child.rev == revision && child.order == 0L
                        } ==
                            true
                    }
                }
                assertTrue(childVisible())
                val bytes = retainedPage.items.sumOf { size(requireNotNull(it.summaryPayload)) } + 128
                f.fillBytes(16 * 1024 * 1024 - bytes)
                f.body(65)
                f.acceptBody(content(128))
                assertEquals("ancestor protection with child revision $revision", BodyLoad.Retry, f.snapshot().bodyLoads[65L to 4000L])
                assertSame(retainedPage, f.snapshot().childPages[90L to 4000L])
                assertTrue(f.snapshot().bodyContents.containsKey(1000L to revision))
                assertTrue("the newer order-zero full child still needs the retained page for placement", childVisible())
            }
        }

    @Test fun hostAndResetIsolation() =
        runTest {
            val f = Fixture()
            f.fillBytes(16 * 1024 * 1024)
            val other = Fixture("other", store = f.store)
            other.body(1)
            other.acceptBody(content(128))
            assertTrue(other.snapshot().bodyContents.containsKey(1L to 4000L))
            val sameHost = Fixture(conversation = "second", store = f.store)
            sameHost.body(1)
            sameHost.acceptBody(content(128))
            assertEquals(BodyLoad.Retry, sameHost.snapshot().bodyLoads[1L to 4000L])
            f.store.reset("h", "c", "e")
            sameHost.body(1, retry = true)
            sameHost.acceptBody(content(128))
            assertTrue(sameHost.snapshot().bodyContents.containsKey(1L to 4000L))
        }

    @Test fun recompositionDoesNotRefreshLru() =
        runTest {
            val f = Fixture()
            val owners = f.fillBytes(16 * 1024 * 1024)
            repeat(3) { f.body(1, owners[0]) }
            f.lane.releaseBodies(owners[0])
            f.lane.releaseBodies(owners[1])
            f.body(65)
            f.acceptBody(content(128))
            assertFalse(f.snapshot().bodyContents.containsKey(1L to 4000L))
            assertTrue(f.snapshot().bodyContents.containsKey(2L to 4000L))
        }
}
