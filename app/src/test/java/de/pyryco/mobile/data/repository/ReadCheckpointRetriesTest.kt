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

    @Test fun qualificationRequiresTheExactLiveOwnerAndCurrentSight() =
        runTest {
            val retry = ReadCheckpointRetries(StandardTestDispatcher(testScheduler))
            try {
                val first = Peer()
                val other = Peer()
                val source = MutableStateFlow<ConversationRepository?>(first)
                val foreign = MutableStateFlow<ConversationRepository?>(other)
                retry.qualify(source, "same", 41u, other) { true }
                retry.qualify(source, "same", 42u, first) { false }
                retry.qualify(source, "same", 43u, first) { true }
                retry.qualify(foreign, "same", 12u, other) { true }
                runCurrent()
                assertEquals(listOf("same" to 43uL), first.writes)
                assertEquals(listOf("same" to 12uL), other.writes)
                val replacement = Peer()
                source.value = replacement
                runCurrent()
                retry.qualify(source, "same", 44u, first) { true }
                runCurrent()
                assertEquals(emptyList<Pair<String, ULong>>(), replacement.writes)
                retry.qualify(source, "same", 45u, replacement) { true }
                runCurrent()
                assertEquals(listOf("same" to 45uL), replacement.writes)
            } finally {
                retry.dispose()
            }
        }

    @Test fun clampedReplyWaitsForFreshSightAndRetirementDropsOwnedWork() =
        runTest {
            val retry = ReadCheckpointRetries(StandardTestDispatcher(testScheduler))
            try {
                val first = Peer().apply { response = 9u }
                val source = MutableStateFlow<ConversationRepository?>(first)
                retry.qualify(source, "c", 10u, first) { true }
                runCurrent()
                first.facts.value = ConversationReadMarks(9u, 100u)
                runCurrent()
                assertEquals(listOf("c" to 10uL), first.writes)
                source.value = null
                runCurrent()
                val next = Peer()
                source.value = next
                runCurrent()
                assertEquals(emptyList<Pair<String, ULong>>(), next.writes)
                retry.qualify(source, "c", 10u, next) { true }
                runCurrent()
                assertEquals(listOf("c" to 10uL), next.writes)
            } finally {
                retry.dispose()
            }
        }

    @Test fun unavailableReadMarksNeverReceiveACommand() =
        runTest {
            val retry = ReadCheckpointRetries(StandardTestDispatcher(testScheduler))
            try {
                val peer = Peer().apply { facts.value = ConversationReadMarks(null, 100u) }
                retry.qualify(MutableStateFlow(peer), "c", 10u, peer) { true }
                runCurrent()
                assertEquals(emptyList<Pair<String, ULong>>(), peer.writes)
            } finally {
                retry.dispose()
            }
        }
}
