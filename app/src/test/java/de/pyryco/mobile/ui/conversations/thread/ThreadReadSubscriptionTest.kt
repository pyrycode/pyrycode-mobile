package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadReadEvidence
import de.pyryco.mobile.data.repository.ThreadSnapshot
import de.pyryco.mobile.data.repository.ThreadSnapshotSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadReadSubscriptionTest {
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun teardown() {
        Dispatchers.resetMain()
    }

    @Test fun rowsAndEvidenceShareOneReceiptSubscriptionAcrossUpdates() =
        runTest {
            val row =
                ThreadItem.MessageItem(
                    Message("m", "", Role.User, "seen", Instant.parse("2026-10-07T00:00:00Z"), isStreaming = false),
                )
            val evidence = ThreadReadEvidence(versions = mapOf(row to setOf(1u)), facts = mapOf(1uL to false))
            val reading = MutableStateFlow(ThreadSnapshot(listOf(row), readEvidence = evidence))
            var subscriptions = 0
            val repository =
                object : ConversationRepository by FakeConversationRepository(), ThreadSnapshotSource {
                    override fun observeThreadSnapshot(conversationId: String) = reading.onStart { subscriptions++ }

                    override fun observeMessages(conversationId: String) = observeThreadSnapshot(conversationId).map { it.rows }
                }
            val vm =
                ThreadViewModel(
                    SavedStateHandle(mapOf("serverId" to "host", "conversationId" to "seed-channel-personal")),
                    repository,
                    FakeConnectionStateSource(),
                    ComposerDraftStore(),
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            runCurrent()
            assertEquals(1, subscriptions)
            assertEquals(listOf(row), vm.state.value.items)
            assertEquals(evidence, vm.state.value.readEvidence)
            reading.value = reading.value.copy(rows = listOf(row.copy(message = row.message.copy(content = "update"))))
            runCurrent()
            assertEquals(1, subscriptions)
        }
}
