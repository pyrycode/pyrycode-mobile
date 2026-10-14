package de.pyryco.mobile.data.cache

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase

/** Shared database shell. Legacy evidence cannot certify the authoritative storage family. */
@Database(
    entities = [ConversationCacheDatabase.Header::class, ConversationCacheDatabase.Row::class, ConversationCacheDatabase.Evidence::class],
    version = 1,
)
abstract class ConversationCacheDatabase : RoomDatabase() {
    internal abstract fun legacy(): LegacyDao

    @Entity(tableName = "legacy_headers", primaryKeys = ["host", "conversation"])
    internal data class Header(
        val host: String,
        val conversation: String,
        val generation: String,
        val cursor: String?,
        val atStart: Boolean,
        val coverageHeader: String?,
    ) {
        override fun toString(): String = "LegacyHeader(redacted)"
    }

    @Entity(tableName = "legacy_rows", primaryKeys = ["host", "conversation", "ordinal"])
    internal data class Row(
        val host: String,
        val conversation: String,
        val ordinal: Int,
        val payload: String,
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
        val ordinal: Int,
        val low: String,
        val high: String,
        val payload: String,
    ) {
        override fun toString(): String = "LegacyEvidence(redacted)"
    }

    @Dao
    internal interface LegacyDao {
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
            before: Int,
            limit: Int,
        ): List<Row>

        @Query(
            "SELECT ordinal FROM legacy_rows WHERE host=:host AND conversation=:conversation AND ordinal<:before ORDER BY ordinal DESC LIMIT 1",
        )
        suspend fun probe(
            host: String,
            conversation: String,
            before: Int,
        ): List<Int>

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
            first: Int,
            last: Int,
        ): List<Evidence>

        @Query(
            "SELECT * FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind=:kind AND low BETWEEN COALESCE((SELECT low FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind=:kind AND low<=:first ORDER BY low DESC LIMIT 1),:first) AND :last AND high>=:first ORDER BY low",
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
            "SELECT * FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind='cursors' AND low IN (SELECT (SELECT MIN(c.low) FROM legacy_evidence c WHERE c.host=:host AND c.conversation=:conversation AND c.kind='cursors' AND c.low>=g.high) FROM legacy_evidence g WHERE g.host=:host AND g.conversation=:conversation AND g.kind='gaps' AND g.low BETWEEN COALESCE((SELECT low FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind='gaps' AND low<=:first ORDER BY low DESC LIMIT 1),:first) AND :last AND g.high>=:first)",
        )
        suspend fun gapCursors(
            host: String,
            conversation: String,
            first: String,
            last: String,
        ): List<Evidence>

        @Query(
            "SELECT * FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind='walks' AND low IN (SELECT low FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind='gaps' AND low BETWEEN COALESCE((SELECT low FROM legacy_evidence WHERE host=:host AND conversation=:conversation AND kind='gaps' AND low<=:first ORDER BY low DESC LIMIT 1),:first) AND :last AND high>=:first)",
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
