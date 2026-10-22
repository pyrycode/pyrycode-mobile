package de.pyryco.mobile.data.cache

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadItemDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuthoritativeRoomStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val path by lazy { File(temporary.root, "cache.db").absolutePath }
    private var database: ConversationCacheDatabase? = null

    private fun open(failure: suspend () -> Unit = {}): AuthoritativeRoomStorage {
        database?.close()
        val db =
            Room
                .databaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    ConversationCacheDatabase::class.java,
                    path,
                ).build()
        database = db
        return AuthoritativeRoomStorage(db, Dispatchers.IO, failure)
    }

    @After fun close() {
        database?.close()
    }

    private fun raw(
        id: Int,
        order: Int? = id,
        rev: Int = id,
    ): JsonObject =
        MobileJson
            .parseToJsonElement(
                """{
                "id":$id,"rev":$rev,"kind":"future-kind","status":"done","active":false,"shown":true,
                "summary":"private-content","content":{"future":[null,1,"text"]}${order?.let { ",\"order\":$it" } ?: ""},
                "unknown":{"x":null}
            }""",
            ).jsonObject

    private fun saved(
        items: List<JsonObject>,
        epoch: String = "epoch",
        version: Long = 200_000,
    ) = SavedAuthoritativeThread(epoch, items, version, version - 1, SavedThreadCoverage(2, 500, 2, true))

    private suspend fun seed(
        store: AuthoritativeRoomStorage,
        value: SavedAuthoritativeThread,
        host: String = "Host",
        thread: String = "thread",
    ) {
        store.writeAuthoritativeThread(host, thread, value).getOrThrow()
    }

    private fun deepFacts(): List<JsonObject> =
        (1..18_001).map { id ->
            JsonObject(
                raw(id) +
                    mapOf(
                        "parent" to JsonPrimitive(if (id == 18_001) 1 else id - 1),
                        "shown" to JsonPrimitive(id >= 18_000),
                        "kind" to JsonPrimitive(if (id == 1) "agent" else "future-kind"),
                    ),
            )
        }

    @Test fun availabilityInvariant_nestedAgentsKeepBoundedKeysThroughImportUpgradeAndStructuralWrites() =
        runTest(timeout = 5.minutes) {
            val facts =
                (1..18_000).map { id ->
                    JsonObject(raw(id) + mapOf("kind" to JsonPrimitive("agent"), "parent" to JsonPrimitive(id - 1)))
                }
            val original = saved(facts)
            val store = open()
            seed(store, original)
            seed(store, saved(listOf(raw(1))), host = "other")

            suspend fun verify(
                expected: SavedAuthoritativeThread,
                order: List<Long>,
            ) {
                val cold = open()
                assertEquals(expected, cold.readAuthoritativeThread("Host", "thread"))
                val db = requireNotNull(database)
                val selected = db.authoritative().displayWindow("Host", "thread", expected.epoch, 200)
                assertEquals(order.takeLast(200).asReversed(), selected.map { it.id })
                db.openHelper.readableDatabase
                    .query(
                        "SELECT id,displayKey,launchKey,anchorId,tieId FROM authoritative_items WHERE host='Host' AND conversation='thread' ORDER BY displayKey",
                    ).use { rows ->
                        val ids = mutableListOf<Long>()
                        var characters = 0L
                        var largest = 0
                        while (rows.moveToNext()) {
                            val id = rows.getLong(0)
                            ids += id
                            val content = rows.getString(1)
                            val launch = rows.getString(2)
                            largest = maxOf(largest, content.length, launch.length)
                            characters += content.length + launch.length
                            val child = if (id == 18_000L || (id == 9000L && expected != original)) 0L else id + 1
                            assertEquals(child, rows.getLong(3))
                            assertEquals(child, rows.getLong(4))
                        }
                        assertEquals(order, ids)
                        assertTrue("largest=$largest", largest <= 62)
                        assertTrue("characters=$characters", characters <= 18_000L * 62)
                        println("nested_agent_keys largest=$largest characters=$characters rows=${ids.size}")
                    }
                val bytes =
                    db.openHelper.readableDatabase.let { sql ->
                        val pages =
                            sql.query("PRAGMA page_count").use {
                                it.moveToFirst()
                                it.getLong(0)
                            }
                        val pageSize =
                            sql.query("PRAGMA page_size").use {
                                it.moveToFirst()
                                it.getLong(0)
                            }
                        pages * pageSize
                    }
                assertTrue("database bytes=$bytes", bytes <= 18_000L * 4096)
                println("nested_agent_storage bytes=$bytes")
                assertEquals(saved(listOf(raw(1))), cold.readAuthoritativeThread("other", "thread"))
            }

            val ascending = (1L..18_000L).toList()
            verify(original, ascending)
            database?.close()
            // Model an installed v10 database: its schema is unchanged and its derived keys are stale.
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("UPDATE authoritative_items SET displayKey='old',anchorId=0,tieId=0 WHERE host='Host'")
                db.version = 10
            }
            verify(original, ascending)
            verify(original, ascending) // Fresh database/storage instances after the upgrade.

            val root = JsonObject(facts.first() + mapOf("order" to JsonPrimitive(40_000), "rev" to JsonPrimitive(300_001)))
            val split = JsonObject(facts[9000] + mapOf("parent" to JsonPrimitive(0), "rev" to JsonPrimitive(300_002)))
            val changes = original.copy(items = listOf(root, split), appliedVersion = 300_002)
            open().upsertAuthoritativeThread("Host", "thread", changes, false, original, emptyMap()).getOrThrow()
            val expected =
                original.copy(
                    items =
                        facts
                            .map {
                                when (it["id"]) {
                                    JsonPrimitive(1) -> root
                                    JsonPrimitive(9001) -> split
                                    else -> it
                                }
                            }.let { it.drop(1) + root },
                    appliedVersion = 300_002,
                )
            verify(expected, (9001L..18_000L).toList() + (1L..9000L).toList())
        }

    private fun assertDeepContributions() {
        val leaf = "0000000000000018000" + "2147501647:18000"
        val sibling = "0000000000000018001" + "2147501648:18001"
        val rootTie = "0000000000000000001" + "2147483648:1"
        requireNotNull(database)
            .openHelper.readableDatabase
            .query(
                "SELECT id,anchorContribution,tieContribution FROM authoritative_items WHERE host='Host' AND conversation='thread' ORDER BY ordinal",
            ).use { cursor ->
                var count = 0
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    assertEquals(if (id == 18_001L) sibling else leaf, cursor.getString(1))
                    assertEquals(
                        if (id == 1L) {
                            rootTie
                        } else if (id == 18_001L) {
                            sibling
                        } else {
                            leaf
                        },
                        cursor.getString(2),
                    )
                    count++
                }
                assertEquals(18_001, count)
            }
    }

    @Test fun availabilityInvariant_deepImportAndMigrationPreserveContributions() =
        runTest(timeout = 5.minutes) {
            val value = saved(deepFacts())
            val cycle = saved((1..3).map { JsonObject(raw(it) + ("parent" to JsonPrimitive(if (it == 1) 3 else it - 1))) })
            seed(open(), value)
            seed(requireNotNull(database).let { AuthoritativeRoomStorage(it) }, cycle, thread = "cycle")
            assertEquals(value, open().readAuthoritativeThread("Host", "thread"))
            assertEquals(cycle, open().readAuthoritativeThread("Host", "cycle"))
            assertDeepContributions()
            database?.close()

            val upgradePath = File(temporary.root, "upgrade.db").absolutePath
            val schema =
                MobileJson
                    .parseToJsonElement(
                        File("src/main/roomSchemas/de.pyryco.mobile.data.cache.ConversationCacheDatabase/9.json").readText(),
                    ).jsonObject
                    .getValue("database")
                    .jsonObject
            SQLiteDatabase.openOrCreateDatabase(upgradePath, null).use { db ->
                for (entry in schema.getValue("entities").jsonArray) {
                    val entity = entry.jsonObject
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    db.execSQL(
                        entity
                            .getValue("createSql")
                            .jsonPrimitive.content
                            .replace("\${TABLE_NAME}", table),
                    )
                    entity["indices"]?.jsonArray?.forEach {
                        db.execSQL(
                            it.jsonObject
                                .getValue("createSql")
                                .jsonPrimitive.content
                                .replace("\${TABLE_NAME}", table),
                        )
                    }
                }
                schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                db.beginTransaction()
                try {
                    for ((conversation, fixture) in mapOf("thread" to value, "cycle" to cycle)) {
                        db.execSQL(
                            "INSERT INTO authoritative_headers VALUES (?,?,?,?,?,?,?,?,?)",
                            arrayOf<Any?>(
                                "Host",
                                conversation,
                                fixture.epoch,
                                "generation",
                                fixture.appliedVersion,
                                fixture.checkpoint,
                                MobileJson.encodeToString(SavedThreadCoverage.serializer(), requireNotNull(fixture.coverage)),
                                fixture.items.size,
                                fixture.incarnation,
                            ),
                        )
                        fixture.items.forEachIndexed { ordinal, raw ->
                            val item = ThreadItemDto.decodeReplacement(raw)
                            db.execSQL(
                                "INSERT INTO authoritative_items (host,conversation,epoch,id,ordinal,orderKey,payload,display,extra) VALUES (?,?,?,?,?,?,?,?,?)",
                                arrayOf<Any?>(
                                    "Host",
                                    conversation,
                                    fixture.epoch,
                                    item.id,
                                    ordinal,
                                    item.order,
                                    raw.toString(),
                                    if (item.savedDisplayRow()) 1 else 0,
                                    if (item.savedRendererExtra()) 1 else 0,
                                ),
                            )
                        }
                    }
                    db.version = 9
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            val upgraded =
                Room
                    .databaseBuilder(
                        ApplicationProvider.getApplicationContext(),
                        ConversationCacheDatabase::class.java,
                        upgradePath,
                    ).build()
            database = upgraded
            assertEquals(value, AuthoritativeRoomStorage(upgraded).readAuthoritativeThread("Host", "thread"))
            assertEquals(cycle, AuthoritativeRoomStorage(upgraded).readAuthoritativeThread("Host", "cycle"))
            assertDeepContributions()
            upgraded.close()
            val reopened =
                Room
                    .databaseBuilder(
                        ApplicationProvider.getApplicationContext(),
                        ConversationCacheDatabase::class.java,
                        upgradePath,
                    ).build()
            database = reopened
            assertEquals(value, AuthoritativeRoomStorage(reopened).readAuthoritativeThread("Host", "thread"))
            assertEquals(cycle, AuthoritativeRoomStorage(reopened).readAuthoritativeThread("Host", "cycle"))
            assertDeepContributions()
        }

    @Test fun availabilityInvariant_deepAndCyclicWinningPathsResolveWitnesses() =
        runTest(timeout = 5.minutes) {
            val value = saved(deepFacts())
            seed(open(), value)
            seed(requireNotNull(database).let { AuthoritativeRoomStorage(it) }, saved(listOf(raw(1))), host = "other")
            val store = open()
            for (cyclic in listOf(false, true)) {
                val root =
                    JsonObject(
                        value.items.first() +
                            mapOf(
                                "rev" to JsonPrimitive(200_001),
                                "order" to JsonPrimitive(2),
                                "parent" to JsonPrimitive(if (cyclic) 18_000 else 0),
                            ),
                    )
                val hidden = JsonObject(value.items[17_999] + mapOf("rev" to JsonPrimitive(200_002), "shown" to JsonPrimitive(false)))
                val resolved =
                    requireNotNull(
                        store.parentClosure(
                            "Host",
                            "thread",
                            value.epoch,
                            emptySet(),
                            emptySet(),
                            structuralChanges = listOf(root, hidden).map(ThreadItemDto::decodeReplacement),
                        ),
                    )
                assertEquals(listOf(value.items.first(), value.items.last()), resolved.items)
                assertEquals(value.checkpoint, resolved.checkpoint)
                assertEquals(value.coverage, resolved.coverage)
                assertEquals(value.appliedVersion, resolved.appliedVersion)
                assertTrue(store.lastReadCounts.queries <= (if (cyclic) 2 else 1) * 54_010)
                assertEquals((if (cyclic) 2 else 1) * 2, store.lastReadCounts.itemDecodes)
                assertEquals(0, store.fullReads)
                println("deep_contribution cyclic=$cyclic counts=${store.lastReadCounts}")
            }
            assertEquals(value, open().readAuthoritativeThread("Host", "thread"))
            assertEquals(saved(listOf(raw(1))), open().readAuthoritativeThread("other", "thread"))
            assertDeepContributions()
        }

    @Test fun orderingInvariant_restartAndBackwardTraversal() =
        runTest {
            val items =
                (1..421).reversed().map {
                    raw(
                        it,
                        if (it % 5 == 0) {
                            null
                        } else if (it % 3 == 0) {
                            0
                        } else {
                            it / 3
                        },
                    )
                }
            val expected = saved(items).retained()
            seed(open(), saved(items))
            var store = open()
            assertEquals(expected, store.readAuthoritativeThread("Host", "thread"))
            var page = requireNotNull(store.newestPage("Host", "thread", 200))
            assertEquals(expected.items.takeLast(200), page.items)
            var walked = page.items
            while (page.continuation != null) {
                val token = requireNotNull(page.continuation)
                store = open()
                page = requireNotNull(store.beforePage("Host", "thread", token, 200))
                walked = page.items + walked
            }
            assertEquals(expected.items, walked)
            assertEquals(421, walked.map { it["id"] }.distinct().size)
        }

    @Test fun replacementInvariant_revisionsAndTokens() =
        runTest {
            seed(open(), saved(listOf(raw(1), raw(2), raw(3))))
            val token = requireNotNull(open().newestPage("Host", "thread", 1)?.continuation)
            val revised =
                JsonObject(
                    raw(1, rev = 9) + mapOf("order" to JsonNull, "parent" to JsonNull, "agent" to JsonNull, "content" to JsonNull),
                )
            val replacement = saved(listOf(revised, raw(4)))
            seed(open(), replacement)
            assertNull(open().beforePage("Host", "thread", token, 1))
            assertEquals(replacement.retained(), open().readAuthoritativeThread("Host", "thread"))
            val next = requireNotNull(open().newestPage("Host", "thread", 1)?.continuation)
            seed(open(), replacement.copy(epoch = "other"))
            assertNull(open().beforePage("Host", "thread", next, 1))
            seed(open(), replacement)
            assertNull(open().beforePage("Host", "thread", token, 1))
            assertNull(open().beforePage("host", "thread", next, 1))
            assertNull(open().beforePage("Host", "other", next, 1))
        }

    @Test fun pageInvariant_emptyAndSingleItem() =
        runTest {
            assertNull(open().newestPage("Host", "thread", 1))
            seed(open(), saved(emptyList()))
            val empty = requireNotNull(open().newestPage("Host", "thread", 1))
            assertTrue(empty.items.isEmpty())
            assertNull(empty.continuation)
            seed(open(), saved(listOf(raw(1))))
            assertEquals(listOf(raw(1)), open().newestPage("Host", "thread", Int.MAX_VALUE)?.items)
            assertNull(open().newestPage("Host", "thread", 0))
            assertNull(open().newestPage("Host", "thread", -1))
            assertNull(open().newestPage("Host", "thread", 1)?.continuation)
        }

    @Test fun certificationInvariant_pagesNeverAdvanceSavedEvidence() =
        runTest {
            val value = saved((1..3).map { raw(it) }).copy(appliedVersion = 10, checkpoint = 4)
            seed(open(), value)
            val store = open()
            var page = requireNotNull(store.newestPage("Host", "thread", 1))
            do {
                assertEquals("Host", page.serverId)
                assertEquals("thread", page.conversationId)
                assertEquals(value.epoch, page.epoch)
                assertEquals(10L, page.appliedVersion)
                assertEquals(4L, page.checkpoint)
                assertEquals(value.coverage, page.coverage)
                val token = page.continuation ?: break
                page = requireNotNull(store.beforePage("Host", "thread", token, 1))
            } while (true)
            assertEquals(value, open().readAuthoritativeThread("Host", "thread"))
        }

    @Test fun boundedInvariant_onlySelectedPayloadsDecode() =
        runTest {
            val counts =
                listOf(401, 4001).map { size ->
                    seed(open(), saved((4002 - size..4001).map { raw(it) }))
                    val store = open()
                    val page = requireNotNull(store.newestPage("Host", "thread", 200))
                    assertEquals(200, page.items.size)
                    val newestCounts = store.lastReadCounts
                    assertEquals(200, store.beforePage("Host", "thread", requireNotNull(page.continuation), 200)?.items?.size)
                    assertEquals(newestCounts, store.lastReadCounts)
                    newestCounts.also {
                        assertEquals(200, it.itemReads)
                        assertEquals(200, it.itemDecodes)
                        assertEquals(1, it.ordinalReads)
                        assertEquals(1, it.evidenceReads)
                        assertEquals(1, it.evidenceDecodes)
                    }
                }
            assertEquals(counts.first(), counts.last())
            // Corrupt an omitted payload: a page must neither fetch nor decode it, even as its probe.
            database?.openHelper?.writableDatabase?.execSQL("UPDATE authoritative_items SET payload='broken' WHERE ordinal=3800")
            val store = open()
            assertEquals(200, store.newestPage("Host", "thread", 200)?.items?.size)
            assertNull(store.readAuthoritativeThread("Host", "thread"))
        }

    @Test fun validationInvariant_rejectsInvalidReplacements() =
        runTest {
            val good = saved(listOf(raw(1)))
            seed(open(), good)
            val invalid =
                listOf(
                    good.copy(items = listOf(raw(1), raw(1))),
                    good.copy(epoch = ""),
                    good.copy(appliedVersion = 0),
                    good.copy(checkpoint = -1),
                    good.copy(checkpoint = good.appliedVersion + 1),
                    good.copy(appliedVersion = 9_007_199_254_740_992),
                    good.copy(items = listOf(raw(0))),
                    good.copy(items = listOf(raw(2, rev = 1))),
                    good.copy(items = listOf(raw(1, rev = 200001))),
                    good.copy(items = listOf(JsonObject(raw(1) + ("order" to JsonPrimitive(-1))))),
                    good.copy(coverage = SavedThreadCoverage(3, 2, 3, false)),
                    good.copy(coverage = SavedThreadCoverage(2, 3, 1, true)),
                    good.copy(items = listOf(JsonObject(raw(1) + ("id" to JsonPrimitive("1"))))),
                )
            for (value in invalid) {
                val failure = open().writeAuthoritativeThread("Host", "thread", value).exceptionOrNull()
                assertTrue(failure is ConversationCacheException)
                assertNull(failure?.cause)
                assertEquals(good, open().readAuthoritativeThread("Host", "thread"))
            }
        }

    @Test fun atomicInvariant_failedMutationsPreserveAuthoritativeState() =
        runTest {
            val good = saved(listOf(raw(1), raw(2)))
            seed(open(), good)
            val token = requireNotNull(open().newestPage("Host", "thread", 1)?.continuation)
            val failure =
                open { throw IOException("sensitive-host") }
                    .writeAuthoritativeThread("Host", "thread", saved(listOf(raw(2))))
            assertTrue(failure.isFailure)
            assertFalse(failure.toString().contains("sensitive-host"))
            assertEquals(good, open().readAuthoritativeThread("Host", "thread"))
            try {
                open { throw CancellationException("cancel") }.removeHost("Host")
                fail("cancellation must propagate")
            } catch (_: CancellationException) {
            }
            assertEquals(good, open().readAuthoritativeThread("Host", "thread"))
            assertTrue(open { throw IOException() }.removeConversation("Host", "thread").isFailure)
            assertEquals(good, open().readAuthoritativeThread("Host", "thread"))
            val denied =
                open { throw SecurityException("sensitive-host") }
                    .writeAuthoritativeThread("Host", "thread", saved(listOf(raw(2))))
            assertTrue(denied.exceptionOrNull() is ConversationCacheException)
            assertTrue(denied.exceptionOrNull()?.message?.endsWith("denied") == true)
            assertEquals(good, open().readAuthoritativeThread("Host", "thread"))
            try {
                open { throw IllegalStateException("programming failure") }
                    .writeAuthoritativeThread("Host", "thread", saved(listOf(raw(2))))
                fail("programming failures must propagate")
            } catch (_: IllegalStateException) {
            }
            assertEquals(good, open().readAuthoritativeThread("Host", "thread"))
            assertEquals(listOf(raw(1)), open().beforePage("Host", "thread", token, 1)?.items)
        }

    @Test fun retentionInvariant_trimDropsCertificationOnly() =
        runTest {
            val value = saved((1..MAX_CACHED_THREAD_ROWS + 1).reversed().map { raw(it) })
            seed(open(), value)
            val restored = requireNotNull(open().readAuthoritativeThread("Host", "thread"))
            assertEquals(MAX_CACHED_THREAD_ROWS, restored.items.size)
            assertEquals(raw(2), restored.items.first())
            assertEquals(raw(MAX_CACHED_THREAD_ROWS + 1), restored.items.last())
            assertEquals(value.appliedVersion, restored.appliedVersion)
            assertEquals(0L, restored.checkpoint)
            assertNull(restored.coverage)
        }

    @Test fun ownershipInvariant_removalStaysIsolated() =
        runTest {
            val value = saved(listOf(raw(1), raw(2)))
            val store = open()
            for ((host, thread) in listOf("Host" to "thread", "host" to "thread", "Host" to "other")) {
                assertNull(store.readAuthoritativeThread(host, thread))
                seed(store, value, host, thread)
                assertEquals(value, store.readAuthoritativeThread(host, thread))
            }
            val token = requireNotNull(store.newestPage("Host", "thread", 1)?.continuation)
            store.removeConversation("Host", "thread").getOrThrow()
            assertNull(store.readAuthoritativeThread("Host", "thread"))
            seed(store, value)
            assertNull(store.beforePage("Host", "thread", token, 1))
            assertEquals(value, store.readAuthoritativeThread("Host", "other"))
            store.removeHost("Host").getOrThrow()
            assertNull(store.readAuthoritativeThread("Host", "other"))
            assertEquals(value, open().readAuthoritativeThread("host", "thread"))
        }

    @Test fun diagnosticsInvariant_resultsRedactAndCorruptReadsNeverRepair() =
        runTest {
            val messages = mutableListOf<String>()
            val oldSink = RelayLog.sink
            val oldEnabled = RelayLog.enabled
            RelayLog.enabled = true
            RelayLog.sink = { _, _, message -> messages.add(message) }
            try {
                seed(open(), saved(listOf(raw(1), raw(2))))
                val page = requireNotNull(open().newestPage("Host", "thread", 1))
                val header = requireNotNull(database).authoritative().header("Host", "thread")
                for (result in listOf(page, page.continuation, page.coverage, header)) {
                    val text = result.toString()
                    listOf("Host", "thread", "epoch", "private-content").forEach { assertFalse(text.contains(it)) }
                }
                requireNotNull(database).openHelper.writableDatabase.execSQL("UPDATE authoritative_headers SET checkpoint=-1")
                assertNull(open().newestPage("Host", "thread", 1))
                assertNull(open().readAuthoritativeThread("Host", "thread"))
                assertEquals(-1L, requireNotNull(database).authoritative().header("Host", "thread")?.checkpoint)
                requireNotNull(database).close()
                assertNull(AuthoritativeRoomStorage(requireNotNull(database)).newestPage("Host", "thread", 1))
                assertTrue(messages.any { "code=invalid_data" in it })
                assertTrue(messages.any { "code=io" in it })
                for (text in messages) listOf("Host", "private-content", "secret-cursor").forEach { assertFalse(text.contains(it)) }
            } finally {
                RelayLog.sink = oldSink
                RelayLog.enabled = oldEnabled
            }
        }
}
