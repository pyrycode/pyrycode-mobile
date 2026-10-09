package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.cache.cachedThreadRowProof
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.ui.conversations.thread.fragmentedHistoryFixture
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class HistoryHashCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val oldSink = RelayLog.sink

    @Before fun setup() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun teardown() {
        RelayLog.sink = oldSink
    }

    @Test fun historyIdentityPreservesTheExistingFullLowercaseSha256Encoding() {
        for (identity in listOf(
            "",
            "café 🦉",
            listOf("message", "fragmented-17999"),
            listOf("delta", "turn", ULong.MAX_VALUE),
            listOf("é", null, -1, emptyList<String>()),
            listOf("ab", "c"),
            listOf("a", "bc"),
        )) {
            val text =
                (identity as? List<*>)?.joinToString("") { it.toString().let { value -> "${value.length}:$value" } } ?: identity.toString()
            assertEquals(legacyDigest(text), historyIdentity(identity))
        }
    }

    @Test fun rowProofMatchesThePersistedRowBytesWithThePreviousEncoding() =
        runTest {
            val root = temporary.newFolder()
            val row = ThreadItem.MessageItem(Message("row", "session", Role.User, "café 🦉", Instant.fromEpochSeconds(1), false))
            FileConversationCache(root).writeThread("host", "c", listOf(row)).getOrThrow()
            val document = root.walkTopDown().single { it.isFile }
            val record =
                MobileJson
                    .parseToJsonElement(document.readText())
                    .jsonObject
                    .getValue("rows")
                    .jsonArray
                    .single()
            assertEquals(legacyDigest(record.toString()), cachedThreadRowProof(row))
        }

    @Test fun bulkProofsRemainIndependentAcrossEveryRow() {
        val rows =
            (0 until 20).map { index ->
                ThreadItem.MessageItem(
                    Message("row-$index", "s", Role.User, "café 🦉 $index", Instant.fromEpochSeconds(index.toLong()), false),
                )
            }
        val expected = rows.associate { historyIdentity(it.mergeIdentity()) to cachedThreadRowProof(it) }
        assertEquals(expected, historyRowProofs(rows))
    }

    @Test fun retainedMatchingProofsPreserveTheAlreadyValidatedCoverage() {
        val (coverage, rows) = fragmentedHistoryFixture(listOf(0, 1, 2))
        assertSame(coverage, coverage.retainedBy(rows))
    }

    @Test fun restoredOrderResolvesIndependentHashesWithoutLosingUnsignedPositions() {
        val (_, rows) = fragmentedHistoryFixture((0 until 20).toList())
        val expected = rows.mapIndexed { index, row -> row.mergeIdentity() to ULong.MAX_VALUE - index.toULong() }.toMap()
        val positions = expected.mapKeys { (identity, _) -> historyIdentity(identity) }
        assertEquals(expected, rows.receivedUnsignedHistoryOrder(positions))
    }

    @Test fun fragmentedFixtureIsCanonicalWithoutDependingOnASetupCodecEcho() {
        val (coverage, rows) = fragmentedHistoryFixture(listOf(0, 17999))
        assertEquals(18000, coverage.unsignedSpans.size)
        assertEquals(36000uL, coverage.unsignedSpans.sumOf { it.last - it.first + 1u })
        assertEquals(listOf("Row 0.", "Row 17999."), rows.map { (it as ThreadItem.MessageItem).message.content })
        assertEquals(coverage, MobileJson.decodeFromString<HistoryCoverage>(MobileJson.encodeToString(coverage)))
        assertEquals(historyRowProofs(rows), coverage.proofs)
        assertEquals(listOf(1uL, 71997uL), coverage.unsignedRowOrder.values.toList())
    }

    private fun legacyDigest(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") {
            "%02x".format(it)
        }
}
