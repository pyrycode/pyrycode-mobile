package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #843: whether the thread offers Re-pair, as its host's rejected-pairing state says. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelRePairTest {
    private val logs = mutableListOf<String>()
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
    }

    private fun vm(pairingRejected: Flow<Boolean>? = null): ThreadViewModel {
        val handle = SavedStateHandle(mapOf("serverId" to "host-a", "conversationId" to "c1"))
        return if (pairingRejected == null) {
            ThreadViewModel(handle, FakeConversationRepository(), FakeConnectionStateSource(), ComposerDraftStore())
        } else {
            ThreadViewModel(
                handle,
                FakeConversationRepository(),
                FakeConnectionStateSource(),
                ComposerDraftStore(),
                pairingRejected = pairingRejected,
            )
        }
    }

    @Test
    fun rePair_isNotOffered_byDefault() =
        runTest {
            val vm = vm()
            backgroundScope.launch { vm.rePairAvailable.collect {} }
            runCurrent()
            assertFalse(vm.rePairAvailable.value)
        }

    @Test
    fun rePair_followsTheHostsRejectedPairing_andClearsWhenItRecovers() =
        runTest {
            val rejected = MutableStateFlow(false)
            val vm = vm(rejected)
            backgroundScope.launch { vm.rePairAvailable.collect {} }
            runCurrent()
            assertFalse(vm.rePairAvailable.value)

            rejected.value = true
            assertTrue(vm.rePairAvailable.value)

            // A successful re-pair brings the connection back, and the action goes away.
            rejected.value = false
            assertFalse(vm.rePairAvailable.value)
        }

    @Test
    fun rePair_logsOnlyAContentFreeEvent_whenOffered() =
        runTest {
            val rejected = MutableStateFlow(true)
            val vm = vm(rejected)
            backgroundScope.launch { vm.rePairAvailable.collect {} }
            runCurrent()
            assertEquals(listOf("event=thread_repair_offered"), logs.filter { it.contains("repair") })
            assertFalse("no host id in the log", logs.any { it.contains("host-a") })
        }
}
