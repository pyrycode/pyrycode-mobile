package de.pyryco.mobile.ui.conversations.share

import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentShareTargetsTest {
    private val a = HostConversationTarget("a", "same")
    private val b = HostConversationTarget("b", "same")

    @Test fun identityIsThePairAndRenameNeverMovesIt() {
        val ledger = RecentShareTargets(4)
        ledger.open(a, "old")
        ledger.open(b, "other")
        val id = ledger.id(a)
        ledger.reconcile(setOf("a", "b"), listOf(host("a", listOf(row("same", "renamed"))), host("b", listOf(row("same", "other")))))
        assertEquals(listOf(b, a), ledger.entries.map { it.target })
        assertEquals(id, ledger.id(a))
        assertNotEquals(id, ledger.id(b))
        assertEquals(a, ledger.resolve(id))
        assertEquals("renamed", ledger.entries.last().label)
    }

    @Test fun openingMovesExactlyOneTargetAndPreservesOtherRelativeOrderUnderEveryPermutation() {
        val targets = listOf(a, b, HostConversationTarget("a", "third"))
        for (order in targets.flatMap { first ->
            val rest = targets.filterNot { it == first }
            listOf(listOf(first) + rest, listOf(first) + rest.reversed())
        }) {
            val ledger = RecentShareTargets(4)
            order.forEach { ledger.open(it, "name") }
            order.forEach { target ->
                val before = ledger.entries.map { it.target }
                ledger.open(target, "name")
                ledger.open(target, "name")
                assertEquals(listOf(target) + before.filterNot { it == target }, ledger.entries.map { it.target })
            }
        }
    }

    @Test fun capEvictionRestartAndRemovedTargetsNeverResurrectFromSnapshots() {
        for (limit in listOf(1, 2, 4)) {
            val ledger = RecentShareTargets(limit)
            val targets = (0..5).map { HostConversationTarget("a", "$it") }
            targets.forEach { ledger.open(it, "label") }
            assertEquals(targets.takeLast(limit).reversed(), ledger.entries.map { it.target })
            assertNull(ledger.resolve(ledger.id(targets.first())))
            val restored = RecentShareTargets(limit, ledger.encode())
            assertEquals(ledger.entries, restored.entries)
            restored.reconcile(setOf("a"), listOf(host("a", emptyList())))
            assertTrue(restored.entries.isEmpty())
            restored.reconcile(setOf("a"), listOf(host("a", targets.map { row(it.conversationId) })))
            assertTrue(restored.entries.isEmpty())
            restored.open(targets.first(), "new")
            assertEquals(listOf(targets.first()), restored.entries.map { it.target })
        }
    }

    @Test fun startupCacheAndReconnectAreNotDeletionButLoadedAbsenceArchiveAndUnpairAre() {
        val ledger = RecentShareTargets(4)
        ledger.open(a, "a")
        ledger.open(b, "b")
        ledger.reconcile(setOf("a", "b"), emptyList())
        ledger.reconcile(setOf("a", "b"), listOf(host("a", emptyList(), loaded = false)))
        ledger.reconcile(setOf("a", "b"), listOf(host("a", listOf(row("unrelated")), loaded = false)))
        assertEquals(listOf(b, a), ledger.entries.map { it.target })
        ledger.reconcile(setOf("a", "b"), listOf(host("a", listOf(row("same", archived = true)))))
        assertEquals(listOf(b), ledger.entries.map { it.target })
        ledger.reconcile(setOf("b"), listOf(host("a", listOf(row("same")))))
        assertEquals(listOf(b), ledger.entries.map { it.target })
        ledger.reconcile(emptySet(), emptyList())
        assertTrue(ledger.entries.isEmpty())
    }

    @Test fun malformedStorageAndUnknownIdsCannotResolveAndLabelsReuseNotificationSanitation() {
        val ledger = RecentShareTargets(4, "invalid")
        assertTrue(ledger.entries.isEmpty())
        ledger.open(a, "\u0000" + "😀".repeat(81))
        assertEquals("😀".repeat(80), ledger.entries.single().label)
        assertNull(ledger.resolve("unknown"))
        ledger.open(HostConversationTarget(" ", "x"), "bad")
        assertEquals(1, ledger.entries.size)
    }

    private fun row(
        id: String,
        name: String = "name",
        archived: Boolean = false,
    ) = Conversation(id, name, "/w", "s", emptyList(), true, Instant.fromEpochMilliseconds(0), archived = archived)

    private fun host(
        id: String,
        rows: List<Conversation>,
        loaded: Boolean = true,
    ) = HostConversationSnapshot(
        id,
        null,
        ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down),
        channels = rows,
        rowsLoaded = loaded,
    )
}
