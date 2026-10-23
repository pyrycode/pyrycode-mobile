package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.ui.conversations.thread.ThreadRow
import de.pyryco.mobile.ui.conversations.thread.listKey
import de.pyryco.mobile.ui.conversations.thread.projectDeferredAuthoritativeThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentChildrenQueriesTest {
    private val oldSink = RelayLog.sink
    private val logs = mutableListOf<String>()

    @org.junit.Before fun quietLog() {
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @org.junit.After fun restoreLog() {
        RelayLog.sink = oldSink
        assertTrue(logs.none { "private" in it || "Inspect files" in it || "\"cursor\"" in it })
    }

    private fun json(text: String) = MobileJson.parseToJsonElement(text) as JsonObject

    private fun parent(
        rev: Long = 23,
        id: Long = 17,
    ) = ThreadItemDto.decode(
        json(
            """{"id":$id,"rev":$rev,"order":5,"parent":0,"kind":"agent","status":"done","shown":true,"active":false,"summary":"Agent","detail_omitted":true,"content":{"name":"Agent"}}""",
        ),
    )

    private class Fixture(
        now: () -> Long = { 0 },
    ) {
        val store = AuthoritativeThreadStore()
        val sent = mutableListOf<Envelope>()
        var support = true
        var sendWorks = true
        var id = 0L
        val lane =
            AuthoritativeThreadQueries("h", store, AuthoritativeThreadRoute("h", store), { true }, { ++id }, {
                sent += it
                sendWorks
            }, now, expansionEnabled = { support })

        fun snapshot() = store.current("h", "c")

        fun demand(
            consumer: Any = this,
            retry: Long? = null,
            more: Long? = null,
            snapshot: AuthoritativeThreadSnapshot = snapshot(),
        ) = lane.children("c", consumer, snapshot, setOf(17), retry, more)

        fun page() = snapshot().childPages[17L to 23L]

        fun reply(
            final: Boolean = false,
            more: Boolean = false,
            child: Int = 50,
            request: Envelope = sent.last(),
            version: Int = 41,
        ): Envelope {
            val data =
                if (final) {
                    ""
                } else {
                    """
                {"id":$child,"rev":7,"order":0,"parent":17,"kind":"agent","status":"done",
                "shown":true,"active":false,"headline":"child","detail_omitted":true}
            """
                }
            val cursor =
                if (!more) {
                    ""
                } else {
                    """
                , "cursor":{"conversation_id":"c","epoch":"e","version":$version,
                "item_id":17,"rev":23,"order":0,"id":$child}
            """
                }
            val completion = if (!final) "" else """, "more":$more$cursor"""
            return Envelope(
                90,
                "thread_children",
                "now",
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"c","epoch":"e","version":$version,"item_id":17,"rev":23,"children":[$data],"final":$final$completion}""",
                ),
                inReplyTo = request.id,
            )
        }

        fun success(
            more: Boolean = false,
            child: Int = 50,
        ) {
            lane.accept(reply(child = child))
            lane.accept(reply(true, more, child))
        }

        fun error() =
            lane.accept(
                Envelope(
                    91,
                    "error",
                    "now",
                    MobileJson.parseToJsonElement(
                        """{"code":"thread.query_failed","message":"private","retryable":true}""",
                    ),
                    inReplyTo = sent.last().id,
                ),
            )
    }

    private fun fixture(now: () -> Long = { 0 }) =
        Fixture(now).also {
            it.store.establishComplete("h", "c", "e", 23, listOf(parent()), 0)
        }

    @Test fun completionInvariantPagesNeverCertifyHistoryAndReopenDoesNotRefetch() =
        runTest {
            val f = fixture()
            val before = f.snapshot()
            f.demand()
            assertEquals("thread_read_children", f.sent.single().type)
            f.lane.accept(f.reply())
            assertNull(f.page())
            f.lane.accept(f.reply(true, true))
            assertEquals(listOf(50L), f.page()?.items?.map { it.id })
            assertNotNull(f.page()?.cursor)
            assertSame(before.items, f.snapshot().items)
            assertEquals(before.coverage, f.snapshot().coverage)
            assertEquals(before.checkpoint, f.snapshot().checkpoint)
            assertEquals(before.completedVersion, f.snapshot().completedVersion)
            repeat(3) { f.demand() }
            assertEquals(1, f.sent.size)
            f.demand(more = 17)
            f.success(child = 51)
            assertEquals(listOf(50L, 51L), f.page()?.items?.map { it.id })
            assertNull(f.page()?.cursor)
        }

    @Test fun rejectedContinuationRetainsRowsUntilSuccessfulExplicitRestart() =
        runTest {
            val f = fixture()
            f.demand()
            f.success(true)
            f.demand(more = 17)
            f.error()
            repeat(3) { f.demand() }
            assertEquals(2, f.sent.size)
            assertEquals(listOf(50L), f.page()?.items?.map { it.id })
            f.demand(retry = 17)
            assertFalse("cursor" in (f.sent.last().payload as JsonObject))
            f.lane.accept(f.reply(child = 51, version = 42))
            assertEquals(listOf(50L), f.page()?.items?.map { it.id })
            f.lane.accept(f.reply(true, child = 51, version = 42))
            assertEquals(listOf(51L), f.page()?.items?.map { it.id })
            assertFalse(f.snapshot().repairNeeded)
        }

    @Test fun ownershipInvariantConsumersCoalesceWithdrawAndDrainTheCapturedVersion() =
        runTest {
            val f = fixture()
            val old = f.snapshot()
            val second = Any()
            f.demand()
            f.demand(second)
            assertEquals(1, f.sent.size)
            f.lane.accept(f.reply())
            f.lane.children("c", f, old, emptySet())?.close()
            f.lane.children("c", second, old, emptySet())?.close()
            f.demand()
            f.lane.accept(f.reply(true, version = 42))
            assertEquals(1, f.sent.size)
            f.lane.accept(f.reply(true))
            assertEquals(2, f.sent.size)
            assertNull(f.page())
            f.success()
            assertNotNull(f.page())
        }

    @Test fun ownershipInvariantRevisionEpochDeletionDisconnectAndHostCollisionCannotPublish() =
        runTest {
            for (change in listOf("revision", "epoch", "deletion", "disconnect", "host")) {
                val f = fixture()
                f.demand()
                f.lane.accept(f.reply())
                when (change) {
                    "revision" -> f.store.applyBatch("h", "c", "e", 24, listOf(parent(24)), 0)
                    "epoch" -> f.store.reset("h", "c", "next")
                    "deletion" -> f.store.remove(requireNotNull(f.store.captureRemoval("h", "c", 17)))
                    "disconnect" -> f.lane.abandon()
                    else -> {
                        f.store.establishComplete("other", "c", "e", 23, listOf(parent()), 0)
                        f.lane.children("c", f, f.store.current("other", "c"), emptySet())
                    }
                }
                f.lane.accept(f.reply(true))
                assertNull(change, f.page())
            }
        }

    @Test fun sendRefusalAndTimeoutAreStickyAndUnsupportedConnectionNeverRequests() =
        runTest {
            val f = fixture { testScheduler.currentTime }
            f.support = false
            f.demand()
            assertTrue(f.sent.isEmpty())
            f.support = true
            f.sendWorks = false
            f.demand()
            repeat(2) { f.demand() }
            assertEquals(1, f.sent.size)
            f.sendWorks = true
            f.demand(retry = 17)
            val expiry = backgroundScope.launch { f.lane.runExpiry() }
            advanceTimeBy(31000)
            runCurrent()
            assertEquals(BodyLoad.Retry, f.snapshot().childLoads[17L to 23L])
            f.demand()
            assertEquals(2, f.sent.size)
            expiry.cancel()
        }

    @Test fun pageInvariantFiftyZeroOrderTiesContinueWithoutDuplicates() =
        runTest {
            val f = fixture()

            fun batch(first: Int) {
                val frame = f.reply()
                val raw = frame.payload as JsonObject
                val child = (raw["children"] as kotlinx.serialization.json.JsonArray).single() as JsonObject
                val rows =
                    kotlinx.serialization.json.JsonArray(
                        (first until first + 50).map {
                            JsonObject(child + ("id" to kotlinx.serialization.json.JsonPrimitive(it)))
                        },
                    )
                f.lane.accept(frame.copy(payload = JsonObject(raw + ("children" to rows))))
            }
            f.demand()
            batch(50)
            f.lane.accept(f.reply(true, true, child = 99))
            assertEquals(50, f.page()?.items?.size)
            f.demand(more = 17)
            assertEquals("99", ((f.sent.last().payload as JsonObject)["cursor"] as JsonObject)["id"].toString())
            batch(100)
            f.lane.accept(f.reply(true, child = 149))
            assertEquals((50L..149L).toList(), f.page()?.items?.map { it.id })
            assertNull(f.page()?.cursor)
        }

    @Test fun stalePresentationWithdrawsQueuedChildPageBeforeLoadingFrame() =
        runTest {
            val f = fixture()
            f.lane.bodies("c", Any(), f.snapshot(), setOf(17))
            val before = f.snapshot()
            f.demand()
            f.lane.children("c", f, before, emptySet())
            val request = f.sent.single()
            for (final in listOf(false, true)) {
                f.lane.accept(
                    Envelope(
                        90,
                        "thread_tool_body",
                        "now",
                        json(
                            """{"conversation_id":"c","epoch":"e","version":41,"item_id":17,"rev":23,"kind":"agent","final":$final${if (final) "" else ",\"content\":{}"}}""",
                        ),
                        inReplyTo = request.id,
                    ),
                )
            }
            assertEquals(1, f.sent.size)
            assertTrue(f.snapshot().childLoads.isEmpty())
            assertNull(f.page())
        }

    @Test fun anotherExpandedConsumerRetainsAnActiveContinuation() =
        runTest {
            val f = fixture()
            val first = f.demand()
            f.success(true)
            val second = Any()
            f.demand(second)
            f.demand(more = 17)
            first?.close()
            f.success(child = 51)
            assertEquals(listOf(50L, 51L), f.page()?.items?.map { it.id })
        }

    @Test fun pageCannotMoveAnIdentityAndNeverErasesNewerCompleteSavedFacts() =
        runTest {
            for (parent in listOf(17, 18)) {
                val f = fixture()
                val saved =
                    ThreadItemDto.decode(
                        json(
                            """{"id":50,"rev":70,"order":6,"parent":$parent,"kind":"agent","status":"done","shown":true,"active":false,"summary":"saved","content":{"name":"Agent","input":{"description":"complete"}}}""",
                        ),
                    )
                assertEquals(AuthoritativeThreadOutcome.Applied, f.store.applyBatch("h", "c", "e", 70, listOf(saved), 0))
                f.demand()
                f.success()
                assertSame(saved, f.snapshot().items.first { it.id == 50L })
                assertSame(saved, f.snapshot().expansionItems.first { it.id == 50L })
                if (parent == 18) {
                    assertNull(f.page())
                    assertEquals(BodyLoad.Retry, f.snapshot().childLoads[17L to 23L])
                } else {
                    assertNotNull(f.page())
                }
            }
        }

    @Test fun placementInvariantCertifiedPageCannotInventOrderForRetainedFullFact() =
        runTest {
            val f = fixture()
            val saved =
                ThreadItemDto.decode(
                    json(
                        """{"id":50,"rev":70,"parent":17,"kind":"agent","status":"done","shown":true,"active":false,"summary":"saved","content":{"name":"Agent","input":{"description":"complete"}}}""",
                    ),
                )
            f.store.applyBatch("h", "c", "e", 70, listOf(saved), 0)
            f.demand()
            val frame = f.reply(version = 80)
            f.lane.accept(frame.copy(payload = json(frame.payload.toString().replace("\"rev\":7", "\"rev\":70"))))
            f.lane.accept(f.reply(true, version = 80))
            assertNotNull(f.page())
            assertSame(saved, f.snapshot().expansionItems.first { it.id == 50L })
            val converted = mutableListOf<Long>()
            val p = projectDeferredAuthoritativeThread("h", "c", f.snapshot(), onFullConversion = { converted += it })
            val rows = p.demandRows(p.rows, setOf("${p.scope}:17", "${p.scope}:50"))
            assertEquals(listOf("msg:${p.scope}:17"), rows.map { it.listKey(0) })
            assertTrue(converted.isEmpty())
        }

    @Test fun visibilityInvariantQueuedAndLostChildSummariesNeverEnterRootWaitingTail() =
        runTest {
            for (status in listOf("queued", "lost")) {
                val f = fixture()
                f.demand()
                val frame = f.reply()
                val child =
                    frame.payload
                        .toString()
                        .replace("\"kind\":\"agent\"", "\"kind\":\"user_message\"")
                        .replace("\"status\":\"done\"", "\"status\":\"$status\"")
                f.lane.accept(frame.copy(payload = json(child)))
                f.lane.accept(f.reply(true))
                assertNotNull(f.page())
                val p = projectDeferredAuthoritativeThread("h", "c", f.snapshot())
                val expanded = p.demandRows(p.rows, setOf("${p.scope}:17"))
                assertEquals(listOf("msg:${p.scope}:17", "msg:${p.scope}:50"), expanded.map { it.listKey(0) })
                assertTrue(expanded.none { it is ThreadRow.Queued })
                assertEquals(expanded.size, expanded.map { it.listKey(0) }.toSet().size)
                assertEquals(listOf("msg:${p.scope}:17"), p.demandRows(p.rows, emptySet()).map { it.listKey(0) })
            }
        }

    @Test fun captureInvariantFutureChildCannotPoisonRetainedFullRevision() =
        runTest {
            val f = fixture()
            val saved =
                ThreadItemDto.decode(
                    json(
                        """{"id":50,"rev":70,"order":6,"parent":17,"kind":"agent","status":"done","shown":true,"active":false,"summary":"saved","content":{"name":"Agent","input":{"description":"complete"}}}""",
                    ),
                )
            f.store.applyBatch("h", "c", "e", 70, listOf(saved), 0)
            val before = f.snapshot()
            f.demand()
            val frame = f.reply(version = 80)
            f.lane.accept(frame.copy(payload = json(frame.payload.toString().replace("\"rev\":7", "\"rev\":900"))))
            f.lane.accept(f.reply(true, version = 80))
            assertNull(f.page())
            assertEquals(BodyLoad.Retry, f.snapshot().childLoads[17L to 23L])
            assertSame(saved, f.snapshot().expansionItems.first { it.id == 50L })
            assertEquals(before.checkpoint, f.snapshot().checkpoint)
            assertEquals(before.coverage, f.snapshot().coverage)
        }
}
