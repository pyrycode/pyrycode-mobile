package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.item
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.HistoryPage
import de.pyryco.mobile.data.repository.NegotiatedThread
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #2175: the production thread route is the ViewModel's only content producer. */
@OptIn(ExperimentalCoroutinesApi::class)
class NegotiatedThreadViewModelTest {
    private val oldSink = RelayLog.sink

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun teardown() {
        Dispatchers.resetMain()
        RelayLog.sink = oldSink
    }

    @Test fun offlineCachedThreadReadsUntilAMissingVerdictSuppressesIt() =
        runTest {
            val route = Route("host")
            route.store.restore(
                "host",
                "c",
                AuthoritativeThreadSnapshot(
                    epoch = "e",
                    items = listOf(item(1, 2, 1, text = "held")),
                    completedVersion = 10,
                    checkpoint = 10,
                ),
                0,
            )
            val owner = ViewModelStore()
            val vm = viewModel(owner, Legacy(), route)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            val offline = requireNotNull(vm.state.value.authoritativeThread)
            assertEquals("host", offline.host)
            assertTrue(offline.rows.isNotEmpty())
            assertTrue(route.opened.isEmpty())

            route.support.value = ThreadSupport.Missing(Any())
            runCurrent()
            assertNull(vm.state.value.authoritativeThread)
            assertTrue(route.opened.isEmpty())

            val capable = Any()
            route.support.value = ThreadSupport.Supported(capable)
            runCurrent()
            assertNotNull(vm.state.value.authoritativeThread)
            assertEquals(listOf(capable), route.opened)
            owner.clear()
        }

    @Test fun opensOncePerSupportedConnectionAndCancelsOnClear() =
        runTest {
            val route = Route("host")
            val owner = ViewModelStore()
            viewModel(owner, Legacy(), route)
            runCurrent()
            val first = Any()
            val second = Any()
            route.support.value = ThreadSupport.Supported(first)
            runCurrent()
            route.support.value = ThreadSupport.Unverified
            runCurrent()
            route.support.value = ThreadSupport.Supported(second)
            runCurrent()
            assertEquals(listOf(first, second), route.opened)
            owner.clear()
            assertEquals(listOf("c"), route.cancelled)
        }

    @Test fun negotiatedRouteNeverSubscribesToLegacyRowsOrHistory() =
        runTest {
            val route = Route("host")
            val legacy = Legacy()
            val owner = ViewModelStore()
            val vm = viewModel(owner, legacy, route)
            backgroundScope.launch { vm.state.collect {} }
            route.support.value = ThreadSupport.Supported(Any())
            runCurrent()
            vm.onDemandOlderHistory()
            vm.onRetryOlderHistory()
            vm.onDemandUnsignedHistoryGap(0uL)
            runCurrent()
            assertEquals(0, legacy.observed)
            assertEquals(0, legacy.historyAsks)
            assertEquals(0, legacy.queueObservations)
            assertEquals(0, legacy.taskObservations)
            assertEquals(2, route.olderAsks)
            assertTrue(
                vm.state.value.items
                    .isEmpty(),
            )
            owner.clear()
        }

    @Test fun anotherHostsRouteIsNotThisDestinationsProducer() =
        runTest {
            val legacy = Legacy()
            val owner = ViewModelStore()
            val vm = viewModel(owner, legacy, Route("other"))
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertTrue(legacy.observed > 0)
            assertNull(vm.state.value.authoritativeThread)
            owner.clear()
        }

    private fun TestScope.viewModel(
        owner: ViewModelStore,
        legacy: Legacy,
        route: Route,
    ): ThreadViewModel {
        val repository =
            object : ConversationRepository by legacy {
                override val negotiatedThread: NegotiatedThread = route
            }
        val factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ThreadViewModel(
                        SavedStateHandle(mapOf("serverId" to "host", "conversationId" to "c")),
                        repository,
                        FakeConnectionStateSource(),
                        ComposerDraftStore(),
                        backgroundTasks = {
                            legacy.taskObservations++
                            kotlinx.coroutines.flow.flowOf(null)
                        },
                    ) as T
            }
        return ViewModelProvider(owner, factory)[ThreadViewModel::class.java]
    }

    /** Counts every legacy content read; the negotiated route must leave both at zero. */
    private class Legacy(
        private val fake: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by fake {
        var observed = 0
        var historyAsks = 0
        var queueObservations = 0
        var taskObservations = 0

        override fun observeQueue(conversationId: String): Flow<List<de.pyryco.mobile.data.repository.QueuedMessage>> {
            queueObservations++
            return fake.observeQueue(conversationId)
        }

        override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> {
            observed++
            return fake.observeMessages(conversationId)
        }

        override suspend fun requestHistory(
            conversationId: String,
            cursor: String,
            limit: Int,
        ): HistoryPage {
            historyAsks++
            return fake.requestHistory(conversationId, cursor, limit)
        }
    }

    private class Route(
        override val host: String,
    ) : NegotiatedThread() {
        override val support = MutableStateFlow<ThreadSupport>(ThreadSupport.Unverified)
        override val store = AuthoritativeThreadStore()
        val opened = mutableListOf<Any>()
        val cancelled = mutableListOf<String>()
        var olderAsks = 0

        override fun observe(conversationId: String): Flow<AuthoritativeThreadSnapshot> = store.observe(host, conversationId)

        override suspend fun open(conversationId: String): Boolean {
            opened += (support.value as ThreadSupport.Supported).connection
            return true
        }

        override fun older(conversationId: String): Boolean {
            olderAsks++
            return true
        }

        override fun cancel(conversationId: String) {
            cancelled += conversationId
        }
    }
}
