package de.pyryco.mobile.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadCheckpointRetriesTest {
    private val oldEnabled = de.pyryco.mobile.data.network.RelayLog.enabled

    @org.junit.Before fun disableAndroidLogging() {
        de.pyryco.mobile.data.network.RelayLog.enabled = false
    }

    @org.junit.After fun restoreLogging() {
        de.pyryco.mobile.data.network.RelayLog.enabled = oldEnabled
    }

    private class Peer : ConversationRepository by FakeConversationRepository() {
        val facts = MutableStateFlow<ConversationReadMarks?>(ConversationReadMarks(0u, 100u))
        val writes = mutableListOf<Pair<String, ULong>>()
        var response: ULong? = null
        var hold = false

        override fun observeReadMarks(conversationId: String) = facts

        override suspend fun markConversationRead(
            conversationId: String,
            upTo: ULong,
        ): Result<ULong> {
            writes += conversationId to upTo
            if (hold) awaitCancellation()
            return response?.let { Result.success(it) } ?: Result.failure(IllegalStateException("offline"))
        }
    }

    @Test fun pendingMaximumSurvivesOfflineAndScreenClosureWithoutCrossingHosts() =
        runTest {
            val retry = ReadCheckpointRetries(StandardTestDispatcher(testScheduler))
            try {
                val a = MutableStateFlow<ConversationRepository?>(null)
                val bPeer = Peer()
                val b = MutableStateFlow<ConversationRepository?>(bPeer)
                retry.qualify(a, "same", 41u)
                retry.qualify(a, "same", 43u)
                retry.qualify(a, "same", 42u)
                retry.qualify(b, "same", 12u)
                runCurrent()
                assertEquals(listOf("same" to 12uL), bPeer.writes)
                val first = Peer().apply { hold = true }
                a.value = first
                runCurrent()
                assertEquals(listOf("same" to 43uL), first.writes)
                a.value = null
                runCurrent()
                val second = Peer().apply { response = 43u }
                a.value = second
                runCurrent()
                assertEquals(listOf("same" to 43uL), second.writes)
                a.value = null
                runCurrent()
                val third = Peer()
                a.value = third
                retry.qualify(a, "same", 42u)
                runCurrent()
                assertEquals(emptyList<Pair<String, ULong>>(), third.writes)
                assertEquals(listOf("same" to 12uL), bPeer.writes)
            } finally {
                retry.dispose()
            }
        }

    @Test fun failuresAndClampedRepliesRetainPendingButDoNotRepeatOnRecomposition() =
        runTest {
            val retry = ReadCheckpointRetries(StandardTestDispatcher(testScheduler))
            try {
                val first = Peer().apply { response = 9u }
                val source = MutableStateFlow<ConversationRepository?>(first)
                retry.qualify(source, "c", 10u)
                runCurrent()
                repeat(3) { retry.qualify(source, "c", 10u) }
                first.facts.value = ConversationReadMarks(9u, 100u)
                runCurrent()
                assertEquals(listOf("c" to 10uL), first.writes)
                source.value = null
                runCurrent()
                val next = Peer()
                source.value = next
                runCurrent()
                assertEquals(listOf("c" to 10uL), next.writes)
                next.facts.value = ConversationReadMarks(11u, 100u)
                runCurrent()
                source.value = null
                runCurrent()
                val final = Peer()
                source.value = final
                runCurrent()
                assertEquals(emptyList<Pair<String, ULong>>(), final.writes)
            } finally {
                retry.dispose()
            }
        }

    @Test fun olderDaemonNeverReceivesPendingCommand() =
        runTest {
            val retry = ReadCheckpointRetries(StandardTestDispatcher(testScheduler))
            try {
                val peer = Peer().apply { facts.value = ConversationReadMarks(null, 100u) }
                retry.qualify(MutableStateFlow(peer), "c", 10u)
                runCurrent()
                assertEquals(emptyList<Pair<String, ULong>>(), peer.writes)
            } finally {
                retry.dispose()
            }
        }
}
