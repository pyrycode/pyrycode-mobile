package de.pyryco.mobile.data.cache

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.di.openConversationCacheDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** The same disk contract runs on Robolectric and real on-device SQLite. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class LegacyDiscardMigrationTest {
    private val owners = listOf("Host" to "c", "Host" to "other", "host" to "c", "host" to "other")
    private val supported = listOf("authoritative_headers", "authoritative_items", "authoritative_retirements", "file_imports")

    @Test fun preservationInvariant_allShippedSchemasUpgradeAndReopen() =
        runBlocking {
            for (version in 1..11) verify(version)
        }

    @Test fun preservationInvariant_freshCreationAndReopen() = runBlocking { verify(0) }

    @Test fun preservationInvariant_productionUpgradeAndFreshReopen() =
        runBlocking {
            // Focused native selection: oldest authoritative, latest shipped, then fresh creation.
            for (version in listOf(2, 11, 0)) verify(version)
        }

    private fun context(root: File): Context =
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this

            override fun getNoBackupFilesDir(): File = root
        }

    private fun schema(version: Int): JsonObject {
        val path = "de.pyryco.mobile.data.cache.ConversationCacheDatabase/$version.json"
        val stream =
            javaClass.classLoader?.getResourceAsStream(path)
                ?: InstrumentationRegistry
                    .getInstrumentation()
                    .context.assets
                    .open(path)
        return stream.bufferedReader().use {
            MobileJson
                .parseToJsonElement(it.readText())
                .jsonObject
                .getValue("database")
                .jsonObject
        }
    }

    private fun saved(
        host: String,
        conversation: String,
    ) = SavedAuthoritativeThread(
        "epoch-$host-$conversation",
        listOf(3, 1, 2).map { id ->
            val kind = if (id < 3) "agent" else "future-kind"
            val parent = if (id == 1) "null" else (id - 1).toString()
            MobileJson
                .parseToJsonElement(
                    """{"id":$id,"rev":${id + 10},"kind":"$kind","status":"done","active":${id == 1},"shown":true,"order":$id,"summary":"$host-$conversation-$id","content":{"future":null},"future":{"keep":true},"parent":$parent}""",
                ).jsonObject
        },
        40,
        30,
        SavedThreadCoverage(1, 3, 1, true),
        incarnation = "installed",
    ).let { if (conversation == "c") it.copy(checkpoint = 0, coverage = null, retirements = mapOf(4L to 25L)) else it }

    private fun rows(
        db: SupportSQLiteDatabase,
        table: String,
    ): List<Map<String, String?>> =
        db.query("SELECT * FROM `$table` ORDER BY 1,2,3").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        cursor.columnNames.associateWith { name ->
                            cursor.getColumnIndexOrThrow(name).let { if (cursor.isNull(it)) null else cursor.getString(it) }
                        },
                    )
                }
            }
        }

    private suspend fun baseline(root: File): Map<String, List<Map<String, String?>>> {
        val db = openConversationCacheDatabase(context(root))
        try {
            val storage = AuthoritativeRoomStorage(db)
            for ((host, conversation) in owners) {
                storage.writeAuthoritativeThread(host, conversation, saved(host, conversation)).getOrThrow()
                db.imports().record(
                    listOf(
                        ConversationCacheDatabase.FileImport(host, conversation, "legacy", "retired"),
                        ConversationCacheDatabase.FileImport(host, conversation, "authoritative", "imported"),
                    ),
                )
            }
            db.imports().record(listOf(ConversationCacheDatabase.FileImport("retired", "", "host", "retired")))
            return supported.associateWith { rows(db.openHelper.writableDatabase, it) }
        } finally {
            db.close()
        }
    }

    private fun seed(
        root: File,
        version: Int,
        original: Map<String, List<Map<String, String?>>>,
    ) {
        val schema = schema(version)
        SQLiteDatabase.openOrCreateDatabase(File(root, "conversation-cache.db"), null).use { db ->
            for (entry in schema.getValue("entities").jsonArray) {
                val entity = entry.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                db.execSQL(
                    entity
                        .getValue("createSql")
                        .jsonPrimitive.content
                        .replace("\${TABLE_NAME}", table),
                )
                entity["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(
                        index.jsonObject
                            .getValue("createSql")
                            .jsonPrimitive.content
                            .replace("\${TABLE_NAME}", table),
                    )
                }
                val fields =
                    entity.getValue("fields").jsonArray.map {
                        it.jsonObject
                            .getValue("columnName")
                            .jsonPrimitive.content
                    }
                if (table.startsWith("legacy_")) {
                    for ((host, conversation) in owners) {
                        val values =
                            fields
                                .map { field ->
                                    when (field) {
                                        "host" -> host
                                        "conversation" -> conversation
                                        "payload" -> "{malformed obsolete bytes"
                                        "generation", "kind", "key", "low", "high", "identity" -> "obsolete"
                                        "cursor", "coverageHeader" -> null
                                        else -> "0"
                                    }
                                }.toTypedArray()
                        db.execSQL("INSERT INTO `$table` (${fields.joinToString()}) VALUES (${fields.joinToString { "?" }})", values)
                    }
                } else {
                    for (row in original.getValue(table)) {
                        db.execSQL(
                            "INSERT INTO `$table` (${fields.joinToString()}) VALUES (${fields.joinToString { "?" }})",
                            fields.map { row[it] }.toTypedArray(),
                        )
                    }
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.version = version
        }
    }

    private suspend fun verify(version: Int) {
        val parent = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "discard-${UUID.randomUUID()}")
        val root = File(parent, "upgrade").apply { mkdirs() }
        try {
            val original = baseline(File(parent, "baseline").apply { mkdirs() })
            if (version > 0) seed(root, version, original)
            val files = FileConversationCache(File(root, "conversations"))
            val legacySources =
                (owners + ("unknown-owner" to "unknown-conversation")).map { (host, conversation) ->
                    val row = ThreadItem.MessageItem(Message("legacy", "", Role.User, "obsolete", Instant.fromEpochSeconds(0), false))
                    files.writeThread(host, conversation, listOf(row)).getOrThrow()

                    fun hash(value: String) =
                        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
                    File(root, "conversations/${hash(host)}/threads/${hash(conversation)}.json").let { it to it.readBytes() }
                }
            var preserved: Map<String, List<Map<String, String?>>>? = null
            repeat(3) { reopen ->
                val db = openConversationCacheDatabase(context(root))
                try {
                    val sql = db.openHelper.writableDatabase
                    sql.query("SELECT name FROM sqlite_master WHERE name LIKE 'legacy_%' OR name LIKE 'index_legacy_%'").use {
                        assertFalse("legacy schema remains: source=$version reopen=$reopen", it.moveToFirst())
                    }
                    assertEquals(12, sql.version)
                    val actual = supported.associateWith { rows(sql, it) }
                    if (reopen == 0) {
                        for (table in supported) {
                            val expected =
                                if (version == 0 || (version == 1 && table != "file_imports") ||
                                    (version < 3 && table == "file_imports") ||
                                    (version < 5 && table == "authoritative_retirements")
                                ) {
                                    emptyList()
                                } else {
                                    original.getValue(table).map { row ->
                                        if (version < 5 && table == "authoritative_headers") row + ("incarnation" to "") else row
                                    }
                                }
                            assertEquals("source=$version table=$table", expected, actual[table])
                        }
                        preserved = actual
                    } else {
                        assertEquals("reopen=$reopen source=$version", preserved, actual)
                    }
                    println("legacy_discard source=$version reopen=$reopen tables=${actual.mapValues { it.value.size }} exact=true")
                    for ((file, bytes) in legacySources) org.junit.Assert.assertArrayEquals(bytes, file.readBytes())
                } finally {
                    db.close()
                }
            }
            verifySupportedOperations(root, version, legacySources)
        } finally {
            assertTrue(parent.deleteRecursively())
        }
    }

    private suspend fun verifySupportedOperations(
        root: File,
        version: Int,
        legacySources: List<Pair<File, ByteArray>>,
    ) {
        fun expected(
            host: String,
            conversation: String,
        ) = saved(host, conversation).let {
            if (version in 2..4) it.copy(incarnation = "") else it
        }
        val db = openConversationCacheDatabase(context(root))
        val files = FileConversationCache(File(root, "conversations"))
        try {
            val cache = MigratingConversationCache(File(root, "conversations"), db)
            val store = AuthoritativeThreadStore()
            val pending = Message("client", "", Role.User, "pending", Instant.fromEpochSeconds(0), false)
            for (host in listOf("Host", "host")) {
                val source = saved(host, "import")
                files.writeAuthoritativeThread(host, "import", source).getOrThrow()
                assertEquals(source.retained(), cache.readAuthoritativeThread(host, "import"))
            }
            // A second cache instance has observed the committed import and may save newer facts.
            val writer = MigratingConversationCache(File(root, "conversations"), db)
            for (host in listOf("Host", "host")) {
                writer.writeAuthoritativeThread(host, "import", saved(host, "import").copy(appliedVersion = 41)).getOrThrow()
            }
            if (version >= 3) {
                files.writeAuthoritativeThread("retired", "c", saved("retired", "c")).getOrThrow()
                assertNull(cache.readAuthoritativeThread("retired", "c"))
            }
            for ((host, conversation) in owners) {
                store.registerPending(host, conversation, "device", pending)
                cache.readAuthoritativeThread(host, conversation)
                cache.writeAuthoritativeThread(host, conversation, expected(host, conversation)).getOrThrow()
                val restored = requireNotNull(cache.readAuthoritativeThread(host, conversation))
                assertEquals(expected(host, conversation).retained(), restored)
                store.restore(host, conversation, restored.toSnapshot(), 0)
                assertEquals(listOf(pending), store.current(host, conversation).pendingSends.map { it.message })
                assertTrue(restored.toSnapshot().pendingSends.isEmpty())
                assertTrue(cache.readThread(host, conversation).isEmpty())
            }
            assertNull(cache.readAuthoritativeThread("unknown-owner", "unknown-conversation"))
            for ((file, bytes) in legacySources) org.junit.Assert.assertArrayEquals(bytes, file.readBytes())
            if (version >= 5) {
                val old = expected("Host", "c")
                val resurrected =
                    old.copy(
                        items =
                            old.items +
                                MobileJson
                                    .parseToJsonElement(
                                        """{"id":4,"rev":24,"kind":"future","status":"done","active":false,"shown":true,"order":4,"summary":"stale","content":{}}""",
                                    ).jsonObject,
                        retirements = emptyMap(),
                    )
                cache.writeAuthoritativeThread("Host", "c", resurrected).getOrThrow()
                assertEquals(old.retained(), cache.readAuthoritativeThread("Host", "c"))
            }
            cache.removeConversation("Host", "c").getOrThrow()
            files.writeAuthoritativeThread("Host", "c", expected("Host", "c")).getOrThrow()
            assertNull(cache.readAuthoritativeThread("Host", "c"))
            assertEquals(expected("Host", "other").retained(), cache.readAuthoritativeThread("Host", "other"))
            assertEquals(expected("host", "c").retained(), cache.readAuthoritativeThread("host", "c"))
            cache.removeHost("Host").getOrThrow()
            files.writeAuthoritativeThread("Host", "other", expected("Host", "other")).getOrThrow()
        } finally {
            db.close()
        }
        val reopened = openConversationCacheDatabase(context(root))
        try {
            val cache = MigratingConversationCache(File(root, "conversations"), reopened)
            assertNull(cache.readAuthoritativeThread("Host", "c"))
            assertNull(cache.readAuthoritativeThread("Host", "other"))
            if (version >= 3) assertNull(cache.readAuthoritativeThread("retired", "c"))
            assertEquals(saved("host", "import").copy(appliedVersion = 41).retained(), cache.readAuthoritativeThread("host", "import"))
            for (conversation in listOf(
                "c",
                "other",
            )) {
                assertEquals(expected("host", conversation).retained(), cache.readAuthoritativeThread("host", conversation))
            }
        } finally {
            reopened.close()
        }
    }
}
