package de.pyryco.mobile.data.cache

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.historyKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OwnedLegacyMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun row(id: Int) =
        ThreadItem.MessageItem(Message("id-$id", "s", Role.User, "text-$id", Instant.fromEpochSeconds(id.toLong()), false))

    @Test fun migrationInvariant_existingRowsGainOwnedIndexWithoutChangingHeldOrder() =
        runTest {
            val path = File(temporary.root, "cache.db").absolutePath
            val schema =
                MobileJson
                    .parseToJsonElement(
                        File("src/main/roomSchemas/de.pyryco.mobile.data.cache.ConversationCacheDatabase/3.json").readText(),
                    ).jsonObject
                    .getValue("database")
                    .jsonObject
            val rows = listOf(row(3), row(1), row(2))
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                schema.getValue("entities").jsonArray.forEach { entry ->
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
                for (host in listOf("Host", "host")) {
                    db.execSQL("INSERT INTO legacy_headers VALUES (?, 'c','old','cursor',0,NULL)", arrayOf(host))
                    rows.forEachIndexed { index, row ->
                        db.execSQL("INSERT INTO legacy_rows VALUES (?, 'c', ?, ?)", arrayOf<Any>(host, index, encodeLegacyRow(row)))
                        db.execSQL(
                            "INSERT INTO legacy_evidence VALUES (?, 'c','proofs',?,0,?,'0','0',?)",
                            arrayOf<Any>(host, row.historyKeys().first(), index, cachedThreadRowProof(row)),
                        )
                    }
                }
                db.version = 3
            }

            fun database() =
                Room.databaseBuilder(ApplicationProvider.getApplicationContext(), ConversationCacheDatabase::class.java, path).build()
            val changed = row(1).let { it.copy(message = it.message.copy(content = "changed")) }
            val migrated = database()
            try {
                val db = migrated
                val cache = MigratingConversationCache(File(temporary.root, "files"), db, Dispatchers.IO)
                assertEquals(rows, cache.readThread("Host", "c"))
                assertEquals(3, db.legacy().header("Host", "c")?.rowCount)
                cache
                    .mutateLegacyRows(
                        "Host",
                        "c",
                        listOf(OwnedLegacyRow(row(6), null, rows.first().legacyRowKey())),
                        emptySet(),
                    ).getOrThrow()
                cache
                    .mutateLegacyRows(
                        "Host",
                        "c",
                        listOf(
                            OwnedLegacyRow(changed, null, row(2).legacyRowKey()),
                            OwnedLegacyRow(row(4), changed.legacyRowKey(), row(2).legacyRowKey()),
                        ),
                        setOf(row(3).legacyRowKey()),
                    ).getOrThrow()
                cache
                    .mutateLegacyRows(
                        "Host",
                        "c",
                        listOf(OwnedLegacyRow(row(5), changed.legacyRowKey(), row(4).legacyRowKey())),
                        emptySet(),
                    ).getOrThrow()
            } finally {
                migrated.close()
            }
            val reopened = database()
            try {
                val db = reopened
                val cache = MigratingConversationCache(File(temporary.root, "files"), db, Dispatchers.IO)
                assertEquals(listOf(row(6), changed, row(5), row(4), row(2)), cache.readThread("Host", "c"))
                assertEquals(rows, cache.readThread("host", "c"))
                val held = requireNotNull(db.legacy().ownedRow("Host", "c", row(2).legacyRowKey()))
                assertEquals(3 * LEGACY_ORDINAL_STEP, held.ordinal)
                assertEquals(encodeLegacyRow(row(2)), held.payload)
                assertEquals(
                    cachedThreadRowProof(row(2)),
                    db
                        .legacy()
                        .allEvidence("Host", "c")
                        .single()
                        .payload,
                )
            } finally {
                reopened.close()
            }
        }
}
