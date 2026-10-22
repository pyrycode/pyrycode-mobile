package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.TYPE_THREAD_ITEM_ADDED
import de.pyryco.mobile.data.network.TYPE_THREAD_ITEM_CHANGED
import de.pyryco.mobile.data.network.TYPE_THREAD_TEXT_APPEND
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.network.ThreadUpdateDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthoritativeThreadStoreTest {
    private val previousSink = RelayLog.sink
    private val previousEnabled = RelayLog.enabled
    private val logs = mutableListOf<String>()

    @Before fun captureLogs() {
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After fun restoreLogs() {
        RelayLog.sink = previousSink
        RelayLog.enabled = previousEnabled
    }

    private val store = AuthoritativeThreadStore()

    private fun state(
        host: String = "h",
        conversation: String = "c",
    ) = store.current(host, conversation)

    private fun apply(
        update: ThreadUpdateDto,
        host: String = "h",
    ) = store.apply(host, update, state(host, update.conversationId).generation)

    private fun batch(
        items: List<ThreadItemDto>,
        version: Long = 100,
        host: String = "h",
        conversation: String = "c",
        epoch: String = "e",
    ) = store.applyBatch(host, conversation, epoch, version, items, state(host, conversation).generation)

    @Test fun batchesPreserveNewerAndAbsentItemsAcrossEveryOverlap() {
        for (overlap in listOf(listOf(1L, 2L), listOf(2L, 3L), listOf(1L, 3L), listOf(1L), emptyList())) {
            val local = AuthoritativeThreadStore()
            val held = listOf(item(1, 10, 30), item(2, 20, 10), item(3, 30, 20))
            assertEquals(AuthoritativeThreadOutcome.Applied, local.applyBatch("h", "c", "e", 30, held, 0))
            val incoming = overlap.reversed().map { item(it, 9, 40 - it) }
            local.applyBatch("h", "c", "e", 50, incoming, 0)
            local.applyBatch("h", "c", "e", 50, incoming.reversed(), 0)
            assertEquals(listOf(2L, 3L, 1L), local.current("h", "c").items.map { it.id })
            assertEquals(held.associate { it.id to it.raw }, local.current("h", "c").items.associate { it.id to it.raw })
            assertEquals(0L, local.current("h", "c").checkpoint)
        }
    }

    @Test fun identitiesNeverShareState() {
        for (host in listOf("a", "b")) {
            for (conversation in listOf("one", "two")) {
                batch(listOf(item(1, 2, 1, text = "$host/$conversation")), host = host, conversation = conversation)
            }
        }
        for (host in listOf("a", "b")) {
            for (conversation in listOf("one", "two")) {
                assertEquals("$host/$conversation", text(state(host, conversation).items.single()))
            }
        }
    }

    @Test fun unorderedFactsStayAbsentUntilDelivery() {
        for ((index, status) in listOf("queued", "dropped", "lost").withIndex()) {
            apply(added(item(1, 2L + index, null, status = status)))
            assertEquals(status, state().items.single().status)
            assertNull(state().items.single().order)
            assertNull(state().items.single().agent)
        }
        apply(added(item(1, 5, 70, status = "delivered")))
        assertEquals(70L, state().items.single().order)
        assertEquals("delivered", state().items.single().status)
    }

    @Test fun patchesRequireExactBaseAndNeverRepeatSuffix() {
        apply(added(item()))
        val append = patch(TYPE_THREAD_TEXT_APPEND, 2, 3, "\"text\":\" tail\"")
        assertEquals(AuthoritativeThreadOutcome.Applied, apply(append))
        assertEquals("start tail", text(state().items.single()))
        assertEquals(AuthoritativeThreadOutcome.RepairNeeded, apply(append))
        assertEquals("start tail", text(state().items.single()))
        assertEquals(3L, state().items.single().rev)
        for (base in listOf(0L, 2L, 4L)) {
            assertEquals(
                AuthoritativeThreadOutcome.RepairNeeded,
                apply(patch(TYPE_THREAD_ITEM_CHANGED, base, 5, "\"changes\":{\"summary\":\"bad\"}")),
            )
            assertEquals("fallback", state().items.single().summary)
        }
        assertEquals(AuthoritativeThreadOutcome.RepairNeeded, apply(patch(TYPE_THREAD_TEXT_APPEND, 3, 3, "\"text\":\"bad\"")))
    }

    @Test fun repairStaysStickyUntilExplicitCompleteCertification() {
        batch(listOf(item()))
        assertEquals(AuthoritativeThreadOutcome.Applied, store.establishComplete("h", "c", "e", 100, emptyList(), 0))
        assertEquals(100L, state().checkpoint)
        apply(patch(TYPE_THREAD_TEXT_APPEND, 3, 4, "\"text\":\"missed\"", version = 101))
        apply(patch(TYPE_THREAD_TEXT_APPEND, 2, 5, "\"text\":\" valid\"", version = 102))
        batch(listOf(item(6, 6, 6)), version = 103)
        assertTrue(state().repairNeeded)
        assertEquals(100L, state().checkpoint)
        assertEquals(103L, state().completedVersion)
        assertEquals(AuthoritativeThreadOutcome.RepairNeeded, store.establishComplete("h", "c", "e", 102, emptyList(), 0))
        store.establishComplete("h", "c", "e", 104, listOf(item(1, 7, 1)), 0)
        assertFalse(state().repairNeeded)
        assertEquals(104L, state().checkpoint)
        assertEquals(setOf(1L, 6L), state().items.map { it.id }.toSet())
    }

    @Test fun changesRetainPresenceAndReplaceWholeContent() {
        apply(added(item(extra = "\"parent\":8,\"session\":\"s\",\"agent\":\"codex\",\"no_child\":true,")))
        val changes = """"changes":{"parent":0,"session":null,"agent":"","no_child":false,
            "active":false,"shown":false,"summary":"","content":{},"future":null}"""
        assertEquals(AuthoritativeThreadOutcome.Applied, apply(patch(TYPE_THREAD_ITEM_CHANGED, 2, 3, changes)))
        val held = state().items.single()
        assertEquals(JsonNull, held.raw["session"])
        assertEquals(JsonObject(emptyMap()), held.content)
        assertEquals("", held.agent)
        assertFalse(held.active)
        assertFalse(held.shown)
        assertEquals(JsonNull, held.raw["future"])
        assertEquals("", held.summary)
        apply(patch(TYPE_THREAD_ITEM_CHANGED, 3, 4, "\"changes\":{\"content\":null}"))
        assertEquals(JsonNull, state().items.single().content)
    }

    @Test fun unknownFactsRetainTheirSummaryAndEntireJson() {
        val unknown = item(kind = "future", status = "alien", content = "[null,{\"opaque\":true}]", extra = "\"future\":{\"x\":1},")
        batch(listOf(unknown))
        assertEquals(unknown.raw, state().items.single().raw)
        assertEquals("fallback", state().items.single().summary)
        assertEquals(AuthoritativeThreadOutcome.RepairNeeded, apply(patch(TYPE_THREAD_TEXT_APPEND, 2, 3, "\"text\":\"no\"")))
    }

    @Test fun resetRejectsStaleGenerationAndIsolatesConversation() {
        batch(listOf(item()))
        batch(listOf(item()), conversation = "other")
        batch(listOf(item()), host = "otherHost")
        val before = state().generation
        assertEquals(AuthoritativeThreadOutcome.ResetNeeded, apply(added(item(), epoch = "new")))
        assertTrue(state().items.isEmpty())
        assertTrue(state().resetNeeded)
        assertEquals(1, state(conversation = "other").items.size)
        assertEquals(1, state(host = "otherHost").items.size)
        assertEquals(AuthoritativeThreadOutcome.ResetNeeded, apply(added(item(), epoch = "new")))
        store.reset("h", "c", "new")
        assertEquals(AuthoritativeThreadOutcome.StaleGeneration, store.apply("h", added(item()), before))
        apply(added(item(), epoch = "new"))
        val generation = state().generation
        store.reset("h", "c", "new")
        assertEquals(AuthoritativeThreadOutcome.StaleGeneration, store.apply("h", added(item(), epoch = "new"), generation))
        assertTrue(state().items.isEmpty())
    }

    @Test fun invalidBatchIsAtomicAndImmutableFieldsNeverChange() {
        batch(listOf(item()))
        for (invalid in listOf(item(1, 3, 1, kind = "tool_call"), item(0, 1, 1), item(9, 8, 1))) {
            assertEquals(AuthoritativeThreadOutcome.RepairNeeded, batch(listOf(item(2, 3, 2), invalid)))
            assertEquals(listOf(1L), state().items.map { it.id })
        }
        for (changes in listOf(
            "\"id\":1",
            "\"kind\":\"assistant_message\"",
            "\"rev\":99",
            "\"order\":9007199254740992",
            "\"active\":null",
        )) {
            assertEquals(AuthoritativeThreadOutcome.RepairNeeded, apply(patch(TYPE_THREAD_ITEM_CHANGED, 2, 3, "\"changes\":{$changes}")))
            assertEquals(2L, state().items.single().rev)
        }
        assertEquals(100L, state().completedVersion)
    }

    @Test fun historyNumbersStayBelowExactJavascriptLimit() {
        val max = 9007199254740991L
        assertEquals(AuthoritativeThreadOutcome.Applied, batch(listOf(item(max, max, max)), version = max))
        assertEquals(max, state().items.single().id)
        assertEquals(AuthoritativeThreadOutcome.RepairNeeded, batch(emptyList(), version = max + 1))
        assertEquals(max, state().completedVersion)
    }

    @Test fun observableSnapshotsAreAtomicAndScoped() =
        runTest {
            batch(listOf(item()), conversation = "other")
            assertTrue(
                store
                    .observe("h", "c")
                    .first()
                    .items
                    .isEmpty(),
            )
            batch(listOf(item()))
            val observed = store.observe("h", "c").first()
            assertEquals(100L, observed.completedVersion)
            assertEquals(1, observed.items.size)
            assertFalse(observed.toString().contains("start"))
        }

    @Test fun newerFullBatchesConvergeAcrossReplayAndArrivalOrders() {
        val older = listOf(item(1, 2, 30), item(2, 3, 10))
        val newer = listOf(item(1, 8, 30, text = "new"), item(3, 9, 20))
        for (batches in listOf(listOf(older, newer), listOf(newer, older), listOf(older.reversed(), newer.reversed(), older))) {
            val local = AuthoritativeThreadStore()
            batches.forEach { local.applyBatch("h", "c", "e", 100, it, 0) }
            val result = local.current("h", "c")
            assertEquals(listOf(2L, 3L, 1L), result.items.map { it.id })
            assertEquals("new", text(result.items.last()))
            assertEquals(8L, result.items.last().rev)
        }
    }

    @Test fun everyOptionalNullClearIsRetainedAndOmittedFieldsStayUnchanged() {
        val extra = """"ended_order":8,"session":"s","agent":"codex","no_child":true,
            "turn":"t","parent":9,"subtype":"sub","""
        apply(added(item(order = 7, extra = extra)))
        val fields = listOf("order", "ended_order", "session", "agent", "no_child", "turn", "parent", "subtype")
        val changes = fields.joinToString(",") { "\"$it\":null" }
        assertEquals(AuthoritativeThreadOutcome.Applied, apply(patch(TYPE_THREAD_ITEM_CHANGED, 2, 3, "\"changes\":{$changes}")))
        val held = state().items.single()
        fields.forEach { assertEquals(it, JsonNull, held.raw[it]) }
        assertNull(held.order)
        assertNull(held.endedOrder)
        assertNull(held.session)
        assertNull(held.agent)
        assertNull(held.noChild)
        assertNull(held.turn)
        assertNull(held.parent)
        assertNull(held.subtype)
        assertTrue(held.active)
        assertTrue(held.shown)
        assertEquals("fallback", held.summary)
        assertEquals("start", text(held))
    }

    @Test fun onlyMessageObjectsWithStringTextCanAppendIncludingEmptySuffix() {
        for (kind in listOf("user_message", "assistant_message")) {
            val local = AuthoritativeThreadStore()
            local.apply("h", added(item(kind = kind)), 0)
            local.apply("h", patch(TYPE_THREAD_TEXT_APPEND, 2, 3, "\"text\":\"\""), 0)
            val held = local.current("h", "c").items.single()
            assertEquals("start", text(held))
            assertEquals(JsonPrimitive(1), (held.content as JsonObject)["other"])
            assertEquals(3L, held.rev)
        }
        for (content in listOf("null", "[]", "{}", "{\"text\":false}", "{\"text\":null}")) {
            val local = AuthoritativeThreadStore()
            val original = item(content = content)
            local.apply("h", added(original), 0)
            assertEquals(
                AuthoritativeThreadOutcome.RepairNeeded,
                local.apply("h", patch(TYPE_THREAD_TEXT_APPEND, 2, 3, "\"text\":\"bad\""), 0),
            )
            assertEquals(
                original.raw,
                local
                    .current("h", "c")
                    .items
                    .single()
                    .raw,
            )
        }
    }

    @Test fun failedAndStaleCertificationCannotClearRepairOrChangeCompletedState() {
        batch(listOf(item()))
        store.requestRepair("h", "c")
        assertEquals(
            AuthoritativeThreadOutcome.RepairNeeded,
            store.establishComplete("h", "c", "e", 100, listOf(item(1, 3, 1, kind = "agent")), 0),
        )
        assertEquals(2L, state().items.single().rev)
        assertTrue(state().repairNeeded)
        store.reset("h", "c", "e")
        assertEquals(AuthoritativeThreadOutcome.StaleGeneration, store.establishComplete("h", "c", "e", 200, listOf(item()), 0))
        assertEquals(AuthoritativeThreadOutcome.StaleGeneration, store.applyBatch("h", "c", "e", 200, listOf(item()), 0))
        assertEquals(0L, state().completedVersion)
        assertEquals(0L, state().checkpoint)
        assertTrue(state().items.isEmpty())
    }

    @Test fun supplementingKnownLiveFactsDoesNotInventRepairOrCertification() {
        val live = item(text = "held prefix")
        batch(listOf(live), version = 10)
        assertFalse(state().repairNeeded)
        for (savedVersion in listOf(5L, 10L)) {
            val saved = AuthoritativeThreadSnapshot("e", listOf(live), completedVersion = savedVersion)
            assertEquals(AuthoritativeThreadOutcome.Applied, store.restore("h", "c", saved, 0))
            assertFalse("a supplement at/below the admitted live version must not invent repair", state().repairNeeded)
            assertEquals(listOf(live.raw), state().items.map { it.raw })
            assertEquals(10L, state().completedVersion)
            assertEquals(0L, state().checkpoint)
            assertNull(state().coverage)
        }
    }

    @Test fun coldAndAheadUncertifiedRestoresStillRequireRepair() {
        for (knownVersion in listOf(0L, 5L)) {
            val local = AuthoritativeThreadStore()
            if (knownVersion > 0) local.applyBatch("h", "c", "e", knownVersion, listOf(item()), 0)
            val saved = AuthoritativeThreadSnapshot("e", listOf(item()), completedVersion = 10)
            assertEquals(AuthoritativeThreadOutcome.Applied, local.restore("h", "c", saved, 0))
            assertTrue(local.current("h", "c").repairNeeded)
            assertEquals(0L, local.current("h", "c").checkpoint)
            assertNull(local.current("h", "c").coverage)
        }
    }

    @Test fun knownLiveRestoreCannotClearExistingRepairOrCrossReset() {
        batch(listOf(item()), version = 10)
        store.requestRepair("h", "c")
        val saved = AuthoritativeThreadSnapshot("e", listOf(item()), completedVersion = 10)
        assertEquals(AuthoritativeThreadOutcome.Applied, store.restore("h", "c", saved, 0))
        assertTrue(state().repairNeeded)
        store.reset("h", "c", "e")
        val reset = state()
        assertEquals(AuthoritativeThreadOutcome.StaleGeneration, store.restore("h", "c", saved, 0))
        assertEquals(reset, state())
    }

    companion object {
        internal fun item(
            id: Long = 1,
            rev: Long = 2,
            order: Long? = 1,
            kind: String = "assistant_message",
            status: String = "running",
            text: String = "start",
            content: String = "{\"text\":\"$text\",\"other\":1}",
            extra: String = "",
        ): ThreadItemDto =
            ThreadItemDto.decode(
                MobileJson.parseToJsonElement(
                    """
                {"id":$id,"rev":$rev,"kind":"$kind",${order?.let { "\"order\":$it," }.orEmpty()}
                 $extra"status":"$status","active":true,"shown":true,"summary":"fallback","content":$content}
                """,
                ) as JsonObject,
            )

        internal fun added(
            item: ThreadItemDto,
            conversation: String = "c",
            epoch: String = "e",
            version: Long = 100,
        ) = requireNotNull(
            ThreadUpdateDto.decode(
                TYPE_THREAD_ITEM_ADDED,
                buildJsonObject {
                    put("conversation_id", conversation)
                    put("epoch", epoch)
                    put("version", version)
                    put("item", item.raw)
                },
            ),
        )

        internal fun patch(
            type: String,
            base: Long,
            rev: Long,
            fields: String,
            version: Long = 100,
        ) = requireNotNull(
            ThreadUpdateDto.decode(
                type,
                MobileJson.parseToJsonElement(
                    """
                {"conversation_id":"c","epoch":"e","version":$version,"item_id":1,"base_rev":$base,"rev":$rev,$fields}
                """,
                ) as JsonObject,
            ),
        )

        internal fun text(item: ThreadItemDto) = ((item.content as JsonObject)["text"] as JsonPrimitive).content
    }
}
