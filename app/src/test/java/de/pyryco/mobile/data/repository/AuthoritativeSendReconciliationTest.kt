package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.toSavedAuthoritativeThread
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.ui.conversations.thread.ThreadRow
import de.pyryco.mobile.ui.conversations.thread.listKey
import de.pyryco.mobile.ui.conversations.thread.projectAuthoritativeThread
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeSendReconciliationTest {
    private val oldSink = de.pyryco.mobile.data.network.RelayLog.sink

    @org.junit.Before fun captureLogs() {
        de.pyryco.mobile.data.network.RelayLog.sink = { _, _, _ -> }
    }

    @org.junit.After fun restoreLogs() {
        de.pyryco.mobile.data.network.RelayLog.sink = oldSink
    }

    private val store = AuthoritativeThreadStore()
    private val message = Message("client", "", Role.User, "same text", Instant.fromEpochMilliseconds(0), isStreaming = false)

    @Test fun exactDeviceHostAndConversationOwnSettlementEvenForHiddenDrop() {
        store.registerPending("host", "c", "device", message)
        store.registerPending("peer-host", "c", "device", message)
        store.registerPending("host", "other", "device", message)
        admit(item(device = "peer"))
        admit(item(id = 2, device = null))
        assertEquals(1, store.current("host", "c").pendingSends.size)
        admit(item(id = 3, status = "dropped", shown = false))
        assertTrue(store.current("host", "c").pendingSends.isEmpty())
        assertEquals(1, store.current("peer-host", "c").pendingSends.size)
        assertEquals(1, store.current("host", "other").pendingSends.size)
        admit(item(id = 3, status = "dropped", shown = false))
        assertTrue(store.current("host", "c").pendingSends.isEmpty())
    }

    @Test fun pendingQueuedAndLostSurviveOmissionsAndOlderOverlappingPages() {
        store.registerPending("host", "c", "device", message.copy(id = "unmatched"))
        admit(item(id = 1, rev = 5, status = "lost", active = false))
        admit(item(id = 2, device = "peer"))
        admit(item(id = 1, rev = 1))
        store.applyBatch("host", "c", "epoch", 5, emptyList(), 0)
        val snapshot = store.current("host", "c")
        assertEquals("lost", snapshot.items.first { it.id == 1L }.status)
        assertEquals(2, snapshot.items.size)
        assertEquals(1, snapshot.pendingSends.size)
        val rows = projectAuthoritativeThread("host", "c", snapshot).rows.filterIsInstance<ThreadRow.Queued>()
        assertEquals(listOf("lost", "queued", "sending"), rows.map { it.status })
        assertNull(rows.first().target)
    }

    @Test fun daemonOrderRelocatesSameAcceptanceWithoutWaitingCopy() {
        admit(item(id = 3, order = 9, status = "delivered", active = false))
        admit(item(id = 1))
        val queued = projectAuthoritativeThread("host", "c", store.current("host", "c")).rows.last()
        admit(item(id = 1, rev = 10, order = 10, status = "delivered", active = false))
        val rows = projectAuthoritativeThread("host", "c", store.current("host", "c")).rows
        assertEquals(queued.listKey(0), rows.last().listKey(0))
        assertEquals(2, rows.size)
        assertTrue(rows.all { it is ThreadRow.Delivered })
        admit(item(id = 1))
        assertEquals(rows, projectAuthoritativeThread("host", "c", store.current("host", "c")).rows)
    }

    @Test fun exactUnsignedHandleAndPermanentAcceptanceAreIndependent() {
        assertEquals(
            9007199254740991L,
            authoritativeQueueTarget("host", "c", "epoch", 0, item(id = 9007199254740991L))?.acceptedItemId,
        )
        for (handle in listOf("1", "9223372036854775808", "18446744073709551615")) {
            val fact = item(handle = handle)
            val target = authoritativeQueueTarget("host", "c", "epoch", 0, fact)
            assertNotNull(target)
            assertEquals(handle, target?.queuedMessageId.toString())
            assertEquals(1L, target?.acceptedItemId)
        }
        for (handle in listOf("0", "-1", "1.0", "1e0", "\"1\"", "null", "18446744073709551616")) {
            assertNull(authoritativeQueueTarget("host", "c", "epoch", 0, item(handle = handle)))
        }
        for (fact in listOf(item(shown = false), item(active = false), item(status = "lost"), item(kind = "assistant_message"))) {
            assertNull(authoritativeQueueTarget("host", "c", "epoch", 0, fact))
        }
    }

    @Test fun missingSendingIdentityCannotSettleAndProvisionalFeedbackIsNeverSaved() {
        store.registerPending("host", "c", null, message)
        admit(item())
        assertEquals(1, store.current("host", "c").pendingSends.size)
        val saved = store.current("host", "c").toSavedAuthoritativeThread()
        assertTrue(saved.toSnapshot().pendingSends.isEmpty())
    }

    @Test fun resetRetainsPendingButRejectsRetiredInputAndActions() {
        store.registerPending("host", "c", "device", message)
        admit(item(device = "peer"))
        val target = requireNotNull(authoritativeQueueTarget("host", "c", "epoch", 0, item(device = "peer")))
        store.reset("host", "c", "epoch")
        assertEquals(1, store.current("host", "c").pendingSends.size)
        assertEquals(AuthoritativeThreadOutcome.StaleGeneration, store.applyBatch("host", "c", "epoch", 1, listOf(item()), 0))
        assertFalse(store.isCurrentQueueTarget(target))
        store.applyBatch("host", "c", "epoch", 2, listOf(item(id = 2, device = "peer")), 1)
        assertFalse(store.isCurrentQueueTarget(target))
        assertEquals(1, store.current("host", "c").pendingSends.size)
    }

    @Test fun retainedWaitingTailUsesAcceptanceIdThenLocalSendOrderAcrossReversedBatches() {
        store.registerPending("host", "c", "device", message.copy(id = "second", content = "second local"))
        store.registerPending("host", "c", null, message.copy(id = "first", content = "first local"))
        val waiting = listOf(item(id = 10, device = "peer"), item(id = 3, device = "peer", status = "lost", active = false))
        store.applyBatch("host", "c", "epoch", 10, waiting, 0)
        val before = projectAuthoritativeThread("host", "c", store.current("host", "c")).rows
        store.applyBatch("host", "c", "epoch", 10, waiting.reversed(), 0)
        assertEquals(before, projectAuthoritativeThread("host", "c", store.current("host", "c")).rows)
        assertEquals(listOf("lost", "queued", "sending", "sending"), before.filterIsInstance<ThreadRow.Queued>().map { it.status })
        assertEquals(listOf("second local", "first local"), before.filterIsInstance<ThreadRow.Queued>().takeLast(2).map { it.text })
    }

    private fun admit(fact: ThreadItemDto) = store.applyBatch("host", "c", "epoch", maxOf(fact.rev, fact.order ?: 0), listOf(fact), 0)

    private fun item(
        id: Long = 1,
        rev: Long = id,
        order: Long? = null,
        device: String? = "device",
        status: String = "queued",
        active: Boolean = true,
        shown: Boolean = true,
        handle: String = "7",
        kind: String = "user_message",
    ): ThreadItemDto =
        ThreadItemDto.decodeReplacement(
            Json
                .parseToJsonElement(
                    """{"id":$id,"rev":$rev,"kind":"$kind",${order?.let {
                        "\"order\":$it,"
                    }.orEmpty()}"status":"$status","active":$active,"shown":$shown,"summary":"","content":{"text":"same text","message_id":"client",${device?.let {
                        "\"device_id\":\"$it\","
                    }.orEmpty()}"queued_msg_id":$handle}}""",
                ).jsonObject,
        )
}
