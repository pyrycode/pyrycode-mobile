package de.pyryco.mobile.data.cache

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.AutoMigrationSpec
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Shared database shell. Legacy evidence cannot certify the authoritative storage family. */
@Database(
    entities = [
        ConversationCacheDatabase.Header::class, ConversationCacheDatabase.Row::class, ConversationCacheDatabase.Evidence::class,
        ConversationCacheDatabase.AuthoritativeHeader::class, ConversationCacheDatabase.AuthoritativeItem::class,
        ConversationCacheDatabase.FileImport::class,
    ],
    version = 4,
    autoMigrations = [
        AutoMigration(
            from = 1,
            to = 2,
        ), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4, spec = ConversationCacheDatabase.OwnedRowsMigration::class),
    ],
)
abstract class ConversationCacheDatabase : RoomDatabase() {
    class OwnedRowsMigration : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            // Migration is the only backfill. Ordinary mutations never scan the saved thread.
            db.execSQL("UPDATE legacy_rows SET ordinal=-ordinal-1")
            db.query("SELECT host,conversation,ordinal,payload FROM legacy_rows WHERE ordinal<0").use { cursor ->
                while (cursor.moveToNext()) {
                    val identity =
                        try {
                            decodeLegacyRows(listOf(cursor.getString(3))).single().legacyRowKey()
                        } catch (_: IllegalArgumentException) {
                            null // Preserve invalid bytes: readers still withhold them without repairing disk.
                        }
                    db.execSQL(
                        "UPDATE legacy_rows SET ordinal=?,identity=? WHERE host=? AND conversation=? AND ordinal=?",
                        arrayOf<Any?>(
                            (-cursor.getLong(2)) * LEGACY_ORDINAL_STEP,
                            identity,
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getLong(2),
                        ),
                    )
                }
            }
            db.execSQL("UPDATE legacy_evidence SET ordinal=(ordinal+1)*4294967296 WHERE ordinal>=0")
            db.execSQL(
                "UPDATE legacy_headers SET rowCount=(SELECT COUNT(*) FROM legacy_rows r WHERE r.host=legacy_headers.host AND r.conversation=legacy_headers.conversation)",
            )
        }
    }

    internal abstract fun legacy(): LegacyDao

    internal abstract fun authoritative(): AuthoritativeDao

    internal abstract fun imports(): FileImportDao

    /** Public backend removals share this transaction; family replacements use their own deletes. */
    internal suspend fun removeScope(
        host: String,
        conversation: String?,
    ) = withTransaction {
        legacy().deleteEvidence(host, conversation)
        legacy().deleteRows(host, conversation)
        legacy().deleteHeaders(host, conversation)
        authoritative().deleteItems(host, conversation)
        authoritative().deleteHeaders(host, conversation)
    }

    /** #2161: one file family's settled import or retirement; the host-wide row uses conversation "". */
    @Entity(tableName = "file_imports", primaryKeys = ["host", "conversation", "family"])
    internal data class FileImport(
        val host: String,
        val conversation: String,
        val family: String,
        val outcome: String,
    ) {
        override fun toString(): String = "FileImport(family=$family, outcome=$outcome)"
    }

    @Dao
    internal interface FileImportDao {
        @Query("SELECT * FROM file_imports WHERE host=:host AND (family='host' OR (conversation=:conversation AND family=:family))")
        suspend fun settled(
            host: String,
            conversation: String,
            family: String,
        ): List<FileImport>

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun record(imports: List<FileImport>)

        @Query("DELETE FROM file_imports WHERE host=:host")
        suspend fun clearHost(host: String)
    }

    @Entity(tableName = "authoritative_headers", primaryKeys = ["host", "conversation"])
    internal data class AuthoritativeHeader(
        val host: String,
        val conversation: String,
        val epoch: String,
        val generation: String,
        val appliedVersion: Long,
        val checkpoint: Long,
        val coverage: String?,
        val itemCount: Int,
    ) {
        override fun toString(): String = "AuthoritativeHeader(redacted)"
    }

    @Entity(
        tableName = "authoritative_items",
        primaryKeys = ["host", "conversation", "epoch", "id"],
        indices = [Index(value = ["host", "conversation", "epoch", "ordinal"], unique = true)],
    )
    internal data class AuthoritativeItem(
        val host: String,
        val conversation: String,
        val epoch: String,
        val id: Long,
        val ordinal: Int,
        val orderKey: Long,
        val payload: String,
    ) {
        override fun toString(): String = "AuthoritativeItem(redacted)"
    }

    @Dao
    internal interface AuthoritativeDao {
        @Query("SELECT * FROM authoritative_headers WHERE host=:host AND conversation=:conversation")
        suspend fun header(
            host: String,
            conversation: String,
        ): AuthoritativeHeader?

        @Query(
            "SELECT * FROM authoritative_items WHERE host=:host AND conversation=:conversation AND epoch=:epoch AND id IN (SELECT id FROM authoritative_items WHERE host=:host AND conversation=:conversation AND epoch=:epoch ORDER BY orderKey DESC,ordinal DESC LIMIT :limit OFFSET MAX(0,(SELECT itemCount FROM authoritative_headers WHERE host=:host AND conversation=:conversation)-:before)) ORDER BY orderKey DESC,ordinal DESC",
        )
        suspend fun items(
            host: String,
            conversation: String,
            epoch: String,
            before: Int,
            limit: Int,
        ): List<AuthoritativeItem>

        @Query(
            "SELECT ordinal FROM authoritative_items WHERE host=:host AND conversation=:conversation AND epoch=:epoch ORDER BY orderKey DESC,ordinal DESC LIMIT 1 OFFSET MAX(0,(SELECT itemCount FROM authoritative_headers WHERE host=:host AND conversation=:conversation)-:before)",
        )
        suspend fun probe(
            host: String,
            conversation: String,
            epoch: String,
            before: Int,
        ): List<Int>

        @Insert
        suspend fun insertHeader(header: AuthoritativeHeader)

        @Insert
        suspend fun insertItems(items: List<AuthoritativeItem>)

        @Query("SELECT * FROM authoritative_items WHERE host=:host AND conversation=:conversation AND epoch=:epoch AND id=:id")
        suspend fun item(
            host: String,
            conversation: String,
            epoch: String,
            id: Long,
        ): AuthoritativeItem?

        @Query("SELECT MAX(ordinal) FROM authoritative_items WHERE host=:host AND conversation=:conversation AND epoch=:epoch")
        suspend fun maximumOrdinal(
            host: String,
            conversation: String,
            epoch: String,
        ): Int?

        @Query("SELECT MIN(ordinal) FROM authoritative_items WHERE host=:host AND conversation=:conversation AND epoch=:epoch")
        suspend fun minimumOrdinal(
            host: String,
            conversation: String,
            epoch: String,
        ): Int?

        @Upsert
        suspend fun upsertItem(item: AuthoritativeItem)

        @Upsert
        suspend fun upsertHeader(header: AuthoritativeHeader)

        @Query(
            "DELETE FROM authoritative_items WHERE host=:host AND conversation=:conversation AND epoch=:epoch AND id IN (SELECT id FROM authoritative_items WHERE host=:host AND conversation=:conversation AND epoch=:epoch ORDER BY orderKey,ordinal LIMIT :count)",
        )
        suspend fun trimItems(
            host: String,
            conversation: String,
            epoch: String,
            count: Int,
        )

        @Query("DELETE FROM authoritative_items WHERE host=:host AND (:conversation IS NULL OR conversation=:conversation)")
        suspend fun deleteItems(
            host: String,
            conversation: String?,
        )

        @Query("DELETE FROM authoritative_headers WHERE host=:host AND (:conversation IS NULL OR conversation=:conversation)")
        suspend fun deleteHeaders(
            host: String,
            conversation: String?,
        )
    }

    @Entity(tableName = "legacy_headers", primaryKeys = ["host", "conversation"])
    internal data class Header(
        val host: String,
        val conversation: String,
        val generation: String,
        val cursor: String?,
        val atStart: Boolean,
        val coverageHeader: String?,
        @ColumnInfo(defaultValue = "0") val rowCount: Int = 0,
    ) {
        override fun toString(): String = "LegacyHeader(redacted)"
    }

    @Entity(
        tableName = "legacy_rows",
        primaryKeys = ["host", "conversation", "ordinal"],
        indices = [Index(value = ["host", "conversation", "identity"], unique = true)],
    )
    internal data class Row(
        val host: String,
        val conversation: String,
        val ordinal: Long,
        val payload: String,
        @ColumnInfo(defaultValue = "NULL") val identity: String? = null,
    ) {
        override fun toString(): String = "LegacyRow(redacted)"
    }

    @Entity(
        tableName = "legacy_evidence",
        primaryKeys = ["host", "conversation", "kind", "key", "part"],
        indices = [
            Index(
                "host",
                "conversation",
                "ordinal",
            ), Index("host", "conversation", "kind", "low", "high"), Index("host", "conversation", "kind", "high"),
        ],
    )
    internal data class Evidence(
        val host: String,
        val conversation: String,
        val kind: String,
        val key: String,
        val part: Int,
        val ordinal: Long,
        val low: String,
        val high: String,
        val payload: String,
    ) {
        override fun toString(): String = "LegacyEvidence(redacted)"
    }

    @Dao
    internal interface LegacyDao {
        @Query("SELECT * FROM legacy_rows WHERE host=:host AND conversation=:conversation AND identity=:identity")
        suspend fun ownedRow(
            host: String,
            conversation: String,
            identity: String,
        ): Row?

        @Query("SELECT ordinal FROM legacy_rows WHERE host=:host AND conversation=:conversation AND identity=:identity")
        suspend fun ownedOrdinal(
            host: String,
            conversation: String,
            identity: String,
        ): Long?

        @Query(
            "SELECT ordinal FROM legacy_rows WHERE host=:host AND conversation=:conversation AND ordinal>:after ORDER BY ordinal LIMIT 1",
        )
        suspend fun nextOrdinal(
            host: String,
            conversation: String,
            after: Long,
        ): Long?

        @Query("SELECT * FROM legacy_rows WHERE host=:host AND conversation=:conversation ORDER BY ordinal LIMIT :limit")
        suspend fun oldestRows(
            host: String,
            conversation: String,
            limit: Int,
        ): List<Row>

        @Query("UPDATE legacy_rows SET payload=:payload WHERE host=:host AND conversation=:conversation AND ordinal=:ordinal")
        suspend fun updateRow(
            host: String,
            conversation: String,
            ordinal: Long,
            payload: String,
        )

        @Query("DELETE FROM legacy_rows WHERE host=:host AND conversation=:conversation AND ordinal=:ordinal")
        suspend fun removeRow(
            host: String,
            conversation: String,
            ordinal: Long,
        )

        @Query("SELECT * FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind='rowEntries' AND low=:entry")
        suspend fun producingClaims(
            host: String,
            conversation: String,
            entry: String,
        ): List<Evidence>

        @Query(
            "DELETE FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND key=:key AND kind IN ('rowOrder','rowEntries','proofs','legacyKeys','legacyOffsets','deltaLengths','deltaHashes','deltaText')",
        )
        suspend fun removeClaim(
            host: String,
            conversation: String,
            key: String,
        )

        @Query("DELETE FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind=:kind AND key=:key AND part=:part")
        suspend fun removeCell(
            host: String,
            conversation: String,
            kind: String,
            key: String,
            part: Int,
        )

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun updateHeader(header: Header)

        @Query("SELECT * FROM legacy_headers WHERE host=:host AND conversation=:conversation")
        suspend fun header(
            host: String,
            conversation: String,
        ): Header?

        @Query(
            "SELECT * FROM legacy_rows WHERE host=:host AND conversation=:conversation AND ordinal<:before ORDER BY ordinal DESC LIMIT :limit",
        )
        suspend fun rows(
            host: String,
            conversation: String,
            before: Long,
            limit: Int,
        ): List<Row>

        @Query(
            "SELECT ordinal FROM legacy_rows WHERE host=:host AND conversation=:conversation AND ordinal<:before ORDER BY ordinal DESC LIMIT 1",
        )
        suspend fun probe(
            host: String,
            conversation: String,
            before: Long,
        ): List<Long>

        @Query("SELECT * FROM legacy_evidence WHERE host=:host AND conversation=:conversation ORDER BY kind, key, part")
        suspend fun allEvidence(
            host: String,
            conversation: String,
        ): List<Evidence>

        @Query(
            "SELECT * FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND ordinal BETWEEN :first AND :last ORDER BY kind,key,part",
        )
        suspend fun rowEvidence(
            host: String,
            conversation: String,
            first: Long,
            last: Long,
        ): List<Evidence>

        // Closed intervals need the strict predecessor as well as intervals starting at first.
        // Keep the same intersection rule in gapWalks and gapCursors.
        @Query(
            "SELECT * FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind=:kind AND low BETWEEN COALESCE((SELECT low FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind=:kind AND low<:first ORDER BY low DESC LIMIT 1),:first) AND :last AND high>=:first ORDER BY low",
        )
        suspend fun intervals(
            host: String,
            conversation: String,
            kind: String,
            first: String,
            last: String,
        ): List<Evidence>

        @Query(
            "SELECT * FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind='spans' AND high<:edge ORDER BY high DESC LIMIT 1",
        )
        suspend fun olderSpan(
            host: String,
            conversation: String,
            edge: String,
        ): List<Evidence>

        @Query(
            "SELECT * FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind='spans' AND low>:edge ORDER BY low LIMIT 1",
        )
        suspend fun newerSpan(
            host: String,
            conversation: String,
            edge: String,
        ): List<Evidence>

        @Query(
            "SELECT * FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind IN ('cursors','walks') AND low BETWEEN :first AND :last UNION ALL SELECT * FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind='walks' AND low='00000000000000000000' AND low NOT BETWEEN :first AND :last ORDER BY kind,low",
        )
        suspend fun cursors(
            host: String,
            conversation: String,
            first: String,
            last: String,
        ): List<Evidence>

        @Query(
            "SELECT * FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind='cursors' AND low>=:edge ORDER BY low LIMIT 1",
        )
        suspend fun edgeCursor(
            host: String,
            conversation: String,
            edge: String,
        ): List<Evidence>

        @Query(
            "SELECT * FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind='cursors' AND low IN (SELECT (SELECT MIN(c.low) FROM legacy_evidence c WHERE c.host=:host AND c.conversation=:conversation AND c.kind='cursors' AND c.low>=g.high) FROM legacy_evidence g WHERE g.host=:host AND g.conversation=:conversation AND g.kind='gaps' AND g.low BETWEEN COALESCE((SELECT low FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind='gaps' AND low<:first ORDER BY low DESC LIMIT 1),:first) AND :last AND g.high>=:first)",
        )
        suspend fun gapCursors(
            host: String,
            conversation: String,
            first: String,
            last: String,
        ): List<Evidence>

        @Query(
            "SELECT * FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind='walks' AND low IN (SELECT low FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind='gaps' AND low BETWEEN COALESCE((SELECT low FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind='gaps' AND low<:first ORDER BY low DESC LIMIT 1),:first) AND :last AND high>=:first)",
        )
        suspend fun gapWalks(
            host: String,
            conversation: String,
            first: String,
            last: String,
        ): List<Evidence>

        @Insert
        suspend fun insertHeader(header: Header)

        @Insert
        suspend fun insertRows(rows: List<Row>)

        @Insert
        suspend fun insertEvidence(evidence: List<Evidence>)

        @Query("DELETE FROM legacy_headers WHERE host=:host AND (:conversation IS NULL OR conversation=:conversation)")
        suspend fun deleteHeaders(
            host: String,
            conversation: String?,
        )

        @Query("DELETE FROM legacy_rows WHERE host=:host AND (:conversation IS NULL OR conversation=:conversation)")
        suspend fun deleteRows(
            host: String,
            conversation: String?,
        )

        @Query("DELETE FROM legacy_evidence WHERE host=:host AND (:conversation IS NULL OR conversation=:conversation)")
        suspend fun deleteEvidence(
            host: String,
            conversation: String?,
        )
    }
}

/** #2161: a Room cache transaction's value, or the static code of the classified failure it rolled back on. */
internal sealed interface CacheTransaction<out T> {
    class Committed<T>(
        val value: T,
    ) : CacheTransaction<T>

    class Failed(
        val code: String,
    ) : CacheTransaction<Nothing>
}

/**
 * #2161: the one transaction shape of the Room cache backends. [block] and then [beforeCommit], a test hold point,
 * run in one transaction. A classified failure rolls both back and returns its static code; cancellation and
 * unclassified programming errors propagate.
 */
internal suspend fun <T> ConversationCacheDatabase.classifiedTransaction(
    beforeCommit: suspend () -> Unit = {},
    block: suspend () -> T,
): CacheTransaction<T> =
    try {
        CacheTransaction.Committed(withTransaction { block().also { beforeCommit() } })
    } catch (error: Exception) {
        CacheTransaction.Failed(failureCode(error) ?: throw error)
    }

private fun ConversationCacheDatabase.failureCode(error: Exception): String? =
    when {
        error is CancellationException -> null
        error is SecurityException -> "denied"
        error is IOException -> "io"
        error is IllegalArgumentException -> "invalid_data"
        error is IllegalStateException && !isOpen -> "io"
        // Platform SQLite failures are classified without an Android dependency in the data contract.
        generateSequence<Class<*>>(error.javaClass) { it.superclass }.any {
            it.name == "android.database.SQLException" || it.name == "androidx.sqlite.SQLiteException"
        } -> "io"
        else -> null
    }
