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
            owner: Int = 17,
            ownerRev: Int = 23,
            childRev: Int = 7,
            kind: String = "agent",
            order: Int = 0,
            headline: String = "child",
        ): Envelope {
            val data =
                if (final) {
                    ""
                } else {
                    """
                {"id":$child,"rev":$childRev,"order":$order,"parent":$owner,"kind":"$kind","status":"done",
                "shown":true,"active":false,"headline":"$headline","detail_omitted":true}
            """
                }
            val cursor =
                if (!more) {
                    ""
                } else {
                    """
                , "cursor":{"conversation_id":"c","epoch":"e","version":$version,
                "item_id":$owner,"rev":$ownerRev,"order":$order,"id":$child}
            """
                }
            val completion = if (!final) "" else """, "more":$more$cursor"""
            return Envelope(
                90,
                "thread_children",
                "now",
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"c","epoch":"e","version":$version,"item_id":$owner,"rev":$ownerRev,"children":[$data],"final":$final$completion}""",
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

    @Test fun freshnessInvariantEqualRevisionAncestorObservationRefreshesOnlyDemandedPages() =
        runTest {
            for (freshness in listOf("0", "42", null)) {
                val f = fixture()
                val initial =
                    ThreadItemDto.decode(
                        JsonObject(
                            parent().raw + ("subtree_version" to kotlinx.serialization.json.JsonPrimitive(0)),
                        ),
                    )
                f.store.applyBatch("h", "c", "e", 24, listOf(initial), 0)
                f.demand()
                f.success(true)
                val held = requireNotNull(f.page())
                val before = f.snapshot()
                val fields = parent().raw.toMutableMap()
                freshness?.let { fields["subtree_version"] = kotlinx.serialization.json.JsonPrimitive(it.toLong()) }
                f.store.applyBatch("h", "c", "e", 42, listOf(ThreadItemDto.decode(JsonObject(fields))), 0)
                repeat(3) { f.demand() }
                if (freshness == "0") {
                    assertEquals(1, f.sent.size)
                } else {
                    assertEquals("freshness=$freshness", 2, f.sent.size)
                    assertFalse("cursor" in (f.sent.last().payload as JsonObject))
                    assertSame(held, f.page())
                    f.lane.accept(f.reply(version = 43, child = 51))
                    assertSame(held, f.page())
                    f.lane.accept(f.reply(true, version = 43))
                    assertEquals(listOf(51L), f.page()?.items?.map { it.id })
                    repeat(3) { f.demand() }
                    assertEquals(2, f.sent.size)
                }
                assertEquals(before.checkpoint, f.snapshot().checkpoint)
                assertEquals(before.coverage, f.snapshot().coverage)
            }
        }

    private fun Fixture.observe(
        version: Long,
        freshness: Long? = version,
        rev: Long = 23,
    ) {
        val fields = parent(rev).raw.toMutableMap()
        freshness?.let { fields["subtree_version"] = kotlinx.serialization.json.JsonPrimitive(it) }
        assertEquals(
            AuthoritativeThreadOutcome.Applied,
            store.applyBatch("h", "c", "e", version, listOf(ThreadItemDto.decode(JsonObject(fields))), 0),
        )
    }

    private fun Fixture.bodyReply(
        final: Boolean = false,
        request: Envelope = sent.last(),
        version: Int = 43,
    ) = Envelope(
        94,
        "thread_tool_body",
        "now",
        json(
            """{"conversation_id":"c","epoch":"e","version":$version,"item_id":50,"rev":7,"kind":"agent","final":$final${if (final) "" else ",\"content\":{\"input\":\"retained private body\"}"}}""",
        ),
        inReplyTo = request.id,
    )

    @Test fun freshnessInvariantNestedBodyRefreshIsDemandedStickyAndNeverReadSight() =
        runTest {
            val f = fixture()
            f.observe(24, 0)
            f.demand()
            f.success()
            val bodyConsumer = Any()

            fun body(retry: Long? = null) = f.lane.bodies("c", bodyConsumer, f.snapshot(), setOf(50), retry)
            body()
            f.lane.accept(f.bodyReply())
            f.lane.accept(f.bodyReply(true))
            val held = f.snapshot().bodyContents
            f.observe(44)
            body()
            assertEquals(3, f.sent.size)
            assertSame(held, f.snapshot().bodyContents)
            assertFalse(f.snapshot().hasToolBody(f.snapshot().expansionItems.single { it.id == 50L }))
            f.error()
            repeat(3) { body() }
            assertEquals(3, f.sent.size)
            assertEquals(BodyLoad.Retry, f.snapshot().bodyLoads[50L to 7L])
            val p = projectDeferredAuthoritativeThread("h", "c", f.snapshot())
            val rows = p.demandRows(p.rows, setOf("${p.scope}:17", "${p.scope}:50"))
            val nested = rows.filterIsInstance<ThreadRow.Delivered>().single { it.listKey(0) == "msg:${p.scope}:50" }
            assertFalse(p.suppliesReadSight(nested, setOf("${p.scope}:17", "${p.scope}:50")))
            body(50)
            f.lane.accept(f.bodyReply(version = 45))
            f.lane.accept(f.bodyReply(true, version = 45))
            assertTrue(f.snapshot().hasToolBody(f.snapshot().expansionItems.single { it.id == 50L }))
            repeat(3) { body() }
            assertEquals(4, f.sent.size)
            // The closed root page and closed/hidden descendants never acquire speculative demand.
            assertEquals(1, f.sent.count { it.type == "thread_read_children" })
        }

    @Test fun captureInvariantInvalidationBetweenDataAndMarkerDrainsBeforeReplacement() =
        runTest {
            val f = fixture()
            f.observe(24, 0)
            f.demand()
            f.success(true)
            val before = f.snapshot()
            val held = f.page()
            f.demand(more = 17)
            val old = f.sent.last()
            f.lane.accept(f.reply(request = old, child = 51))
            f.observe(44)
            f.demand(more = 17)
            assertEquals(2, f.sent.size)
            assertSame(held, f.page())
            f.lane.accept(f.reply(true, request = old, version = 42))
            assertEquals(2, f.sent.size)
            f.lane.accept(f.reply(true, request = old))
            assertEquals(3, f.sent.size)
            assertFalse("cursor" in (f.sent.last().payload as JsonObject))
            f.lane.accept(f.reply(version = 45, child = 52))
            f.lane.accept(f.reply(true, true, version = 45, child = 52))
            f.lane.accept(f.reply(true, request = old))
            assertEquals(listOf(52L), f.page()?.items?.map { it.id })
            f.demand(more = 17)
            f.lane.accept(f.reply(version = 45, child = 53))
            f.lane.accept(f.reply(true, version = 45, child = 53))
            assertEquals(listOf(52L, 53L), f.page()?.items?.map { it.id })
            assertEquals(before.checkpoint, f.snapshot().checkpoint)
            assertEquals(before.coverage, f.snapshot().coverage)
        }

    @Test fun freshnessInvariantRevisionChangeRetainsRowsUntilAuthoritativeEmptyReplacement() =
        runTest {
            val f = fixture()
            f.observe(24, 0)
            f.demand()
            f.success(true)
            f.observe(44, 0, rev = 44)
            val converted = mutableListOf<Long>()
            val p = projectDeferredAuthoritativeThread("h", "c", f.snapshot(), onFullConversion = { converted += it })
            assertTrue(p.demandRows(p.rows, setOf("${p.scope}:17")).any { it.listKey(0) == "msg:${p.scope}:50" })
            assertFalse(50L in converted)
            f.demand(more = 17)
            assertEquals(2, f.sent.size)
            assertFalse("cursor" in (f.sent.last().payload as JsonObject))
            f.lane.accept(f.reply(true, ownerRev = 44, version = 45))
            assertEquals(
                emptyList<Long>(),
                f
                    .snapshot()
                    .childPages[17L to 44L]
                    ?.items
                    ?.map { it.id },
            )
            assertFalse(f.snapshot().expansionItems.any { it.id == 50L })
            repeat(3) { f.demand() }
            assertEquals(2, f.sent.size)
        }

    @Test fun ownershipInvariantInvalidatedConsumerUnionRejectsHeldAdditionsAndOldCaptures() =
        runTest {
            val f = fixture()
            f.observe(24, 0)
            val first = f.demand()
            f.success(true)
            val second = Any()
            f.demand(second)
            val old = f.snapshot()
            f.observe(44, null)
            f.demand(second)
            first?.close()
            f.lane.children("c", f, old, setOf(17, 50), retry = 17, more = 17)
            assertEquals(2, f.sent.size)
            // A reply to the current request can still carry an obsolete source capture.
            f.lane.accept(f.reply(child = 51))
            f.lane.accept(f.reply(true))
            assertEquals(listOf(50L), f.page()?.items?.map { it.id })
            assertEquals(BodyLoad.Retry, f.snapshot().childLoads[17L to 23L])
            f.demand(second, retry = 17)
            f.lane.accept(f.reply(version = 45, child = 52))
            f.lane.accept(f.reply(true, version = 45))
            assertEquals(listOf(52L), f.page()?.items?.map { it.id })
            repeat(3) { f.demand(second) }
            assertEquals(3, f.sent.size)
        }

    @Test fun captureInvariantOlderFullBatchCannotClearNewerAncestorInvalidation() =
        runTest {
            val f = fixture()
            f.observe(24, 0)
            f.demand()
            f.success()
            f.observe(44, null)
            val full =
                ThreadItemDto.decode(
                    json(
                        """{"id":50,"rev":7,"order":0,"parent":17,"kind":"agent","status":"done","shown":true,"active":false,"summary":"child","content":{"name":"Agent","input":"private"}}""",
                    ),
                )
            f.store.applyBatch("h", "c", "e", 43, listOf(full), 0)
            assertFalse(f.snapshot().hasToolBody(f.snapshot().expansionItems.single { it.id == 50L }))
            f.store.applyBatch("h", "c", "e", 45, listOf(full), 0)
            assertTrue(f.snapshot().hasToolBody(f.snapshot().expansionItems.single { it.id == 50L }))
            f.demand()
            f.lane.accept(f.reply(version = 46))
            f.lane.accept(f.reply(true, version = 46))
            repeat(3) { f.demand() }
            assertEquals(2, f.sent.size)
            // Another genuinely newer unknown observation invalidates once again.
            f.observe(47, null)
            repeat(3) { f.demand() }
            assertEquals(3, f.sent.size)
        }

    @Test fun ownershipInvariantLateRetirementCannotClearReplacementRetryWithCollidingIds() =
        runTest {
            val f = fixture()
            f.demand()
            val oldRequest = f.sent.single()
            val sent = mutableListOf<Envelope>()
            val replacement =
                AuthoritativeThreadQueries("h", f.store, AuthoritativeThreadRoute("h", f.store), { true }, { 1 }, {
                    sent += it
                    true
                }, { 0L }, expansionEnabled = { true })
            val consumer = Any()

            fun demand() = replacement.children("c", consumer, f.snapshot(), setOf(17))
            demand()
            assertEquals(oldRequest.id, sent.single().id)
            replacement.accept(
                Envelope(
                    91,
                    "error",
                    "now",
                    json("""{"code":"thread.query_failed","message":"private","retryable":true}"""),
                    inReplyTo = sent.single().id,
                ),
            )
            assertEquals(BodyLoad.Retry, f.snapshot().childLoads[17L to 23L])
            f.lane.abandon()
            f.lane.accept(f.reply(true, request = oldRequest))
            assertEquals(BodyLoad.Retry, f.snapshot().childLoads[17L to 23L])
            repeat(3) { demand() }
            assertEquals(1, sent.size)
            assertNull(f.page())
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

    @Test fun revisionInvariantRestartRejectsDowngradeAndKeepsFirstEqualSummary() =
        runTest {
            for ((revision, order) in listOf(60 to 0, 70 to 0, 70 to 1, 71 to 0)) {
                val f = fixture()
                val before = f.snapshot()
                f.demand()
                f.lane.accept(f.reply(version = 80, childRev = 70, headline = "first"))
                f.lane.accept(f.reply(true, true, version = 80))
                val held = requireNotNull(f.page())
                f.demand(more = 17)
                f.error()
                f.demand(retry = 17)
                assertFalse("cursor" in (f.sent.last().payload as JsonObject))
                f.lane.accept(f.reply(version = 81, childRev = revision, order = order, headline = "replacement"))
                assertSame(held, f.page())
                f.lane.accept(f.reply(true, version = 81))
                if (revision < 70 || order != 0) {
                    assertSame(held, f.page())
                    assertEquals(BodyLoad.Retry, f.snapshot().childLoads[17L to 23L])
                    repeat(2) { f.demand() }
                    assertEquals(3, f.sent.size)
                } else {
                    assertEquals(81uL, f.page()?.version)
                    assertNull(f.page()?.cursor)
                    assertNull(f.snapshot().childLoads[17L to 23L])
                }
                val winner = f.snapshot().expansionItems.single { it.id == 50L }
                assertEquals(maxOf(70, revision).toLong(), winner.rev)
                if (revision <= 70) assertSame(held.items.single(), winner)
                assertEquals(listOf(50L), f.page()?.items?.map { it.id })
                assertEquals(before.checkpoint, f.snapshot().checkpoint)
                assertEquals(before.coverage, f.snapshot().coverage)
                assertEquals(before.completedVersion, f.snapshot().completedVersion)
            }
        }

    @Test fun ownershipInvariantFinalParentRevisionFencesValidityInBothPageOrders() =
        runTest {
            val f = fixture()
            val full = ThreadItemDto.decode(JsonObject(parent(60, 50).raw + ("parent" to kotlinx.serialization.json.JsonPrimitive(17))))
            f.store.applyBatch("h", "c", "e", 60, listOf(full), 0)
            f.demand()
            f.lane.accept(f.reply(version = 80, childRev = 60))
            f.lane.accept(f.reply(true, true, version = 80))
            f.lane.children("c", Any(), f.snapshot(), setOf(50))
            f.lane.accept(f.reply(version = 80, owner = 50, ownerRev = 60, child = 51, childRev = 61, order = 7))
            f.lane.accept(f.reply(true, version = 80, owner = 50, ownerRev = 60))
            assertTrue(f.snapshot().expansionItems.any { it.id == 51L })
            f.demand(more = 17)
            f.error()
            f.demand(retry = 17)
            f.lane.accept(f.reply(version = 100, childRev = 90))
            f.lane.accept(f.reply(true, version = 100))
            val snapshot = f.snapshot()
            assertEquals(listOf(50L to 60L, 17L to 23L), snapshot.childPages.keys.toList())
            val reversed =
                snapshot.copy(
                    childPages =
                        snapshot.childPages.entries
                            .reversed()
                            .associate { it.toPair() },
                )
            for (ordered in listOf(snapshot, reversed)) {
                assertEquals(90L, ordered.expansionItems.single { it.id == 50L }.rev)
                assertTrue(ordered.expansionItems.any { it.id == 51L })
                assertFalse(ordered.hasChildPage(ordered.expansionItems.single { it.id == 50L }))
                assertFalse(ordered.hasToolBody(ordered.expansionItems.single { it.id == 51L }))
                val converted = mutableListOf<Long>()
                val p = projectDeferredAuthoritativeThread("h", "c", ordered, onFullConversion = { converted += it })
                val rows = p.demandRows(p.rows, setOf("${p.scope}:17", "${p.scope}:50", "${p.scope}:51"))
                assertEquals(listOf("msg:${p.scope}:17", "msg:${p.scope}:50", "msg:${p.scope}:51"), rows.map { it.listKey(0) })
                val retained = rows.filterIsInstance<ThreadRow.Delivered>().last()
                assertFalse(p.suppliesReadSight(retained, setOf("${p.scope}:17", "${p.scope}:50", "${p.scope}:51")))
                assertFalse(51L in converted)
            }
            val requests = f.sent.size
            // Retained rows stay displayable while invalid; closed ancestors still hide their demand.
            f.lane.children("c", Any(), snapshot, emptySet())
            f.lane.bodies("c", Any(), f.snapshot(), emptySet())
            assertEquals(requests, f.sent.size)
            assertSame(full, snapshot.items.single { it.id == 50L })
        }

    @Test fun identityInvariantSummaryCannotChangeFullOrSummaryKind() =
        runTest {
            for (summary in listOf(false, true)) {
                for ((heldKind, incomingKind) in listOf("tool_call" to "agent", "agent" to "tool_call")) {
                    val f = fixture()
                    if (summary) {
                        f.demand()
                        f.lane.accept(f.reply(version = 80, childRev = 70, kind = heldKind))
                        f.lane.accept(f.reply(true, true, version = 80))
                        f.demand(more = 17)
                        f.error()
                    } else {
                        val full =
                            ThreadItemDto.decode(
                                json(
                                    """{"id":50,"rev":70,"order":6,"parent":17,"kind":"$heldKind","status":"done","shown":true,"active":false,"summary":"saved","content":{"name":"Tool"}}""",
                                ),
                            )
                        f.store.applyBatch("h", "c", "e", 70, listOf(full), 0)
                    }
                    val before = f.snapshot()
                    val held = before.expansionItems.single { it.id == 50L }
                    f.demand(retry = if (summary) 17 else null)
                    f.lane.accept(f.reply(version = 81, childRev = 71, kind = incomingKind))
                    assertSame(before.childPages, f.snapshot().childPages)
                    f.lane.accept(f.reply(true, version = 81))
                    assertSame(before.childPages, f.snapshot().childPages)
                    assertSame(held, f.snapshot().expansionItems.single { it.id == 50L })
                    assertEquals(BodyLoad.Retry, f.snapshot().childLoads[17L to 23L])
                    assertEquals(before.checkpoint, f.snapshot().checkpoint)
                    assertEquals(before.coverage, f.snapshot().coverage)
                    assertEquals(before.completedVersion, f.snapshot().completedVersion)
                    if (heldKind == "tool_call") {
                        val requests = f.sent.size
                        f.lane.children("c", Any(), f.snapshot(), setOf(50))
                        assertEquals(requests, f.sent.size)
                    }
                }
            }
        }

    @Test fun identityInvariantFullReplyCannotChangeRetainedSummaryKind() =
        runTest {
            val f = fixture()
            f.demand()
            f.lane.accept(f.reply(version = 80, childRev = 70, kind = "tool_call"))
            f.lane.accept(f.reply(true, version = 80))
            val before = f.snapshot()
            val held = before.expansionItems.single { it.id == 50L }
            f.lane.open("c")
            val request = f.sent.last()
            f.lane.accept(
                Envelope(
                    90,
                    "thread_items",
                    "now",
                    json(
                        """{"conversation_id":"c","epoch":"e","version":81,"items":[{"id":50,"rev":71,"order":6,"parent":17,"kind":"agent","status":"done","shown":true,"active":false,"summary":"full","content":{"name":"Agent"}}],"final":false}""",
                    ),
                    inReplyTo = request.id,
                ),
            )
            assertSame(held, f.snapshot().expansionItems.single { it.id == 50L })
            assertSame(before.items, f.snapshot().items)
            assertSame(before.childPages, f.snapshot().childPages)
            assertTrue(f.snapshot().repairNeeded)
            f.lane.accept(
                Envelope(
                    91,
                    "thread_items",
                    "now",
                    json("""{"conversation_id":"c","epoch":"e","version":81,"items":[],"final":true,"from_version":23}"""),
                    inReplyTo = request.id,
                ),
            )
            assertSame(held, f.snapshot().expansionItems.single { it.id == 50L })
            assertTrue(f.snapshot().repairNeeded)
            assertEquals(before.checkpoint, f.snapshot().checkpoint)
            assertEquals(before.coverage, f.snapshot().coverage)
        }
}
